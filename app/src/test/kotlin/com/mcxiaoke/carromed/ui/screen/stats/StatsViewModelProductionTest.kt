package com.mcxiaoke.carromed.ui.screen.stats

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.DoseRecordEntity
import com.mcxiaoke.carromed.core.data.entity.DoseSlotEntity
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.entity.PolicyTimeEntity
import com.mcxiaoke.carromed.core.data.entity.SchedulePolicyEntity
import com.mcxiaoke.carromed.core.data.model.PolicyType
import com.mcxiaoke.carromed.core.data.model.RecordStatus
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.LocalDate

/**
 * 统计页的**生产代码路径**测试（M6-1 + M4-2 + M4-3）。
 *
 * ## 为什么测 [StatsStateBuilder] 而不是 [StatsViewModel]
 *
 * 上一轮的真实事故：`StatsUnitGroupingTest` 在测试文件里**重写**了一遍
 * `groupByUnit`，于是把生产代码改回跨单位求和的旧 bug 时，5 条测试**全绿**。
 * 影子测试比没有测试更危险 —— 它让人以为这条不变量有门禁。
 *
 * 只测 [com.mcxiaoke.carromed.core.domain.engine.StatsEngine.groupByUnit] 还不够：
 * 那一层现在是正确的，但如果生产代码路径不再调用它（或者又自己写一遍），
 * 测试照样全绿。本文件直接驱动统计页**真正使用**的那个类。
 *
 * 而 `StatsViewModel` **不能**被构造：它只接受一个 `Application`，
 * 走 `AndroidViewModelFactory` 反射（见该类 KDoc 里踩过的坑），
 * 要注入内存库就必须给构造器加参数 —— 那会崩掉整个统计页。
 * 所以可测的部分被抽成了 [StatsStateBuilder] 这个普通类。
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class StatsViewModelProductionTest {

    private lateinit var db: AppDatabase
    private lateinit var vm: StatsStateBuilder

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val today: LocalDate get() = LocalDate.now()

    /** 槽位时刻的全局序号，见 [givenSlot]：避开 `(med, date, time)` UNIQUE 索引 */
    private var nextSeq = 0

    @Before
    fun setup(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        vm = StatsStateBuilder(db)
    }

    @After
    @Throws(IOException::class)
    fun tearDown() = db.close()

    private suspend fun givenMed(name: String, unit: String): Long {
        val id = db.medicationDao().insert(MedicationEntity(name = name, unit = unit))
        db.reminderSettingsDao().ensureDefaults(id)
        return id
    }

    /**
     * 造一条槽位。
     *
     * ⚠️ [seq] 是必需的：`dose_slots` 上有 `(medication_id, scheduled_date, scheduled_time)`
     * UNIQUE 索引，而 `insert` 用的是 `REPLACE`。同一天同一药反复插 `08:00` 会**互相顶掉**，
     * 造出 4 条 COMPLETED 却只留下 1 行 —— 断言报"expected 4 but was 1"，
     * 而真实原因是 fixture 自己撞了唯一索引，与被测逻辑毫无关系。
     */
    private suspend fun givenSlot(
        medId: Long,
        doseMilli: Int,
        status: SlotStatus,
        date: String = today.toString(),
        seq: Int = nextSeq++
    ) {
        db.doseSlotDao().insert(
            DoseSlotEntity(
                medicationId = medId,
                policyId = 0,
                scheduledDate = date,
                scheduledTime = String.format("%02d:%02d", 6 + seq / 4, (seq % 4) * 15),
                scheduledTs = System.currentTimeMillis(),
                doseAmount = doseMilli,
                status = status
            )
        )
    }

    private suspend fun givenRecord(medId: Long, doseMilli: Int) {
        db.doseRecordDao().insert(
            DoseRecordEntity(
                slotId = null,
                medicationId = medId,
                actualTs = System.currentTimeMillis(),
                doseTaken = doseMilli,
                status = RecordStatus.COMPLETED
            )
        )
    }

    // ==================== M6-1 跨单位不得求和 ====================

    @Test
    fun `生产路径 多种单位时逐单位分组 不给跨单位的假总量`() = runBlocking {
        val a = givenMed("环孢素", "片")
        val b = givenMed("生理盐水", "ml")
        val c = givenMed("维生素D", "粒")
        givenSlot(a, 30000, SlotStatus.COMPLETED)
        givenSlot(b, 5000, SlotStatus.COMPLETED)
        givenSlot(c, 12000, SlotStatus.COMPLETED)
        givenRecord(a, 30000)
        givenRecord(b, 5000)
        givenRecord(c, 12000)

        val state = vm.build(StatsPeriod.WEEK)

        assertThat(state.totalDosesByUnit).hasSize(3)
        assertThat(state.totalDosesByUnit["片"]).isEqualTo(30f)
        assertThat(state.totalDosesByUnit["ml"]).isEqualTo(5f)
        assertThat(state.totalDosesByUnit["粒"]).isEqualTo(12f)
        // ⭐ 关键：`mixedUnits` 为真时**不能**给出单一总量。
        // 旧 bug 给出 totalDoses=47 / totalDoseUnit="片" —— 47 片这个物理量不存在。
        assertThat(state.mixedUnits).isTrue()
        assertThat(state.totalDoseUnit).isNull()
        assertThat(state.totalDoses).isEqualTo(0f)
        assertThat(state.totalDoses).isNotEqualTo(47f)
    }

    @Test
    fun `生产路径 同单位时可以给出总量`() = runBlocking {
        val a = givenMed("环孢素", "片")
        val b = givenMed("他克莫司", "片")
        givenSlot(a, 30000, SlotStatus.COMPLETED)
        givenSlot(b, 20000, SlotStatus.COMPLETED)
        givenRecord(a, 30000)
        givenRecord(b, 20000)

        val state = vm.build(StatsPeriod.WEEK)

        assertThat(state.mixedUnits).isFalse()
        assertThat(state.totalDoseUnit).isEqualTo("片")
        assertThat(state.totalDoses).isEqualTo(50f)
    }

    // ==================== M4-3 归档不进统计分母 ====================

    /**
     * 归档一个药之后，依从率必须**上升**。
     *
     * 旧实现的 `getSlotStatusCounts` 没有归档过滤，而排行榜与"在服药品数"
     * 都只算 active —— 同一屏的数字来自两个不同的集合。
     * 用户停药三个月，统计页的依从率仍被那个药的旧槽位拉低，
     * 而他看到的"在服药品 1 种"告诉他只剩一种药在吃。
     */
    @Test
    fun `归档一个药后 依从率立刻反映新的分母`() = runBlocking {
        val keep = givenMed("环孢素", "片")
        val drop = givenMed("已停用的药", "片")
        // keep: 10 次全服 = 100%
        repeat(10) { givenSlot(keep, 1000, SlotStatus.COMPLETED) }
        // drop: 10 次全漏 = 0%
        repeat(10) { givenSlot(drop, 1000, SlotStatus.EXPIRED) }

        val before = vm.build(StatsPeriod.WEEK)
        assertThat(before.adherenceRate).isEqualTo(0.5f)

        db.medicationDao().updateArchiveStatus(drop, isArchived = true)

        val after = vm.build(StatsPeriod.WEEK)
        // 只剩 keep 的 10 次全服 ⇒ 100%
        assertThat(after.adherenceRate).isEqualTo(1.0f)
        assertThat(after.breakdown.missed).isEqualTo(0)
        assertThat(after.breakdown.completed).isEqualTo(10)
    }

    /**
     * 归档过滤只发生在**统计页的全局聚合**里，不是全表删除 ——
     * 药品详情页仍要能看到"我过去吃了什么"，包括已停用的药。
     *
     * 这是一条**反向**约束：防止有人为了"修好统计口径"顺手把归档药的历史也抹掉。
     * 详情页走 [DoseSlotDao.getSlotStatusCounts] 之后按 `medicationId` 过滤，
     * 归档药自己那一行**必须**还在。
     */
    @Test
    fun `归档过滤不牵连该药自身的历史（详情页仍看得到）`() = runBlocking {
        val drop = givenMed("已停用的药", "片")
        repeat(3) { givenSlot(drop, 1000, SlotStatus.COMPLETED) }
        db.medicationDao().updateArchiveStatus(drop, isArchived = true)

        val start = today.minusDays(6).toString()
        val rows = db.doseSlotDao().getSlotStatusCounts(start, today.toString())

        // 全局聚合里已经没有它了（不进统计分母）
        assertThat(rows.none { it.medicationId == drop }).isTrue()
        // ⭐ 但它的历史行**一行都没少** —— 归档不是删除
        val all = db.doseSlotDao().getAllSlots().filter { it.medicationId == drop }
        assertThat(all).hasSize(3)
        assertThat(all.all { it.status == SlotStatus.COMPLETED }).isTrue()
    }

    // ==================== M4-2 统计页要跟着刷新 ====================

    @Test
    fun `跳过一次后 统计页的依从率必须变化`() = runBlocking {
        val medId = givenMed("环孢素", "片")
        repeat(4) { givenSlot(medId, 1000, SlotStatus.COMPLETED) }
        val before = vm.build(StatsPeriod.WEEK)
        assertThat(before.adherenceRate).isEqualTo(1.0f)
        assertThat(before.breakdown.completed).isEqualTo(4)

        givenSlot(medId, 1000, SlotStatus.SKIPPED)

        val after = vm.build(StatsPeriod.WEEK)
        // 跳过进分母但不算已服 ⇒ 4/5
        assertThat(after.breakdown.skipped).isEqualTo(1)
        assertThat(after.adherenceRate).isEqualTo(4f / 5f)
    }

    @Test
    fun `漏服结算后 统计页必须变化`() = runBlocking {
        val medId = givenMed("环孢素", "片")
        repeat(3) { givenSlot(medId, 1000, SlotStatus.COMPLETED) }
        assertThat(vm.build(StatsPeriod.WEEK).adherenceRate).isEqualTo(1.0f)

        givenSlot(medId, 1000, SlotStatus.EXPIRED)

        assertThat(vm.build(StatsPeriod.WEEK).adherenceRate).isEqualTo(3f / 4f)
    }

    /**
     * 「变化探针」必须真的发射。
     *
     * 这是 M4-2 的核心：旧实现的 `combine` 只挂了「药品概览」，
     * 而那张表在打卡 / 跳过 / 结算时**一行都不变** ⇒ Flow 不发射 ⇒ 数字不刷新。
     * 探针 DAO 写错（比如误加归档过滤、误只数 PENDING）都会让这里变红。
     */
    @Test
    fun `已产生结论的槽位探针 随打卡与归档而变化`() = runBlocking {
        val a = givenMed("环孢素", "片")
        val b = givenMed("已停用的药", "片")

        assertThat(db.doseSlotDao().observeDecidedSlotCount().first()).isEqualTo(0)

        givenSlot(a, 1000, SlotStatus.COMPLETED)
        assertThat(db.doseSlotDao().observeDecidedSlotCount().first()).isEqualTo(1)

        // 待服槽位**不**该计入：14 天窗口每天新增一批 PENDING，
        // 那样每次对账都会触发一次无谓的全量重新聚合。
        givenSlot(a, 1000, SlotStatus.PENDING)
        assertThat(db.doseSlotDao().observeDecidedSlotCount().first()).isEqualTo(1)

        // 归档不改槽位状态，但**必须**让探针变化，否则统计页的归档过滤形同虚设
        givenSlot(b, 1000, SlotStatus.COMPLETED)
        assertThat(db.doseSlotDao().observeDecidedSlotCount().first()).isEqualTo(2)
        db.medicationDao().updateArchiveStatus(b, isArchived = true)
        assertThat(db.doseSlotDao().observeDecidedSlotCount().first()).isEqualTo(1)
    }

    // ==================== 周期切换 ====================

    @Test
    fun `不同周期的取数区间不同`() = runBlocking {
        val medId = givenMed("环孢素", "片")
        // 今天完成 1 次；45 天前完成 1 次
        givenSlot(medId, 1000, SlotStatus.COMPLETED, today.toString())
        givenSlot(
            medId, 1000, SlotStatus.COMPLETED,
            today.minusDays(45).toString()
        )

        val week = vm.build(StatsPeriod.WEEK)
        val month = vm.build(StatsPeriod.MONTH)
        val year = vm.build(StatsPeriod.YEAR)

        assertThat(week.breakdown.completed).isEqualTo(1)
        assertThat(month.breakdown.completed).isEqualTo(1)
        assertThat(year.breakdown.completed).isEqualTo(2)
    }

    // ==================== 空态 ====================

    @Test
    fun `空库不产生任何单位 也不假装有 100% 依从率以外的数字`() = runBlocking {
        val state = vm.build(StatsPeriod.WEEK)
        assertThat(state.totalDosesByUnit).isEmpty()
        assertThat(state.totalDoseUnit).isNull()
        assertThat(state.mixedUnits).isFalse()
        assertThat(state.scheduledDoseCount).isEqualTo(0)
        // 分母为 0 ⇒ 领域层约定 1.0f（UI 显示「—」，不显示 100%）
        assertThat(state.adherenceRate).isEqualTo(1.0f)
    }

    @Test
    fun `全部待服时依从率按约定为 1 不冤枉用户`() = runBlocking {
        val medId = givenMed("环孢素", "片")
        repeat(5) { givenSlot(medId, 1000, SlotStatus.PENDING) }
        val state = vm.build(StatsPeriod.WEEK)
        assertThat(state.breakdown.pending).isEqualTo(5)
        assertThat(state.scheduledDoseCount).isEqualTo(5)
        assertThat(state.adherenceRate).isEqualTo(1.0f)
    }

    @Test
    fun `有计划的药才会进入统计 无计划不影响任何数字`() = runBlocking {
        val medId = givenMed("环孢素", "片")
        db.schedulePolicyDao().savePolicyWithTimes(
            SchedulePolicyEntity(
                medicationId = medId,
                policyType = PolicyType.DAILY,
                startDate = today.toString()
            ),
            listOf(PolicyTimeEntity(policyId = 0, timeOfDay = "08:00", doseAmount = 1000))
        )
        givenSlot(medId, 1000, SlotStatus.COMPLETED)

        assertThat(vm.build(StatsPeriod.WEEK).breakdown.completed).isEqualTo(1)
    }
}
