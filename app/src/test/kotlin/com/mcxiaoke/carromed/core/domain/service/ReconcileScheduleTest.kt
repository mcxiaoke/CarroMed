package com.mcxiaoke.carromed.core.domain.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.DoseSlotEntity
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.entity.PolicyTimeEntity
import com.mcxiaoke.carromed.core.data.entity.SchedulePolicyEntity
import com.mcxiaoke.carromed.core.data.model.PolicyType
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import com.mcxiaoke.carromed.core.domain.engine.SlotProjectionEngine
import com.mcxiaoke.carromed.core.testing.assertDoseMilli
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.LocalDate

/**
 * 排班重对齐的幂等性与唯一性测试（不变量 I5 / I6，对应 **P1-5**）。
 *
 * ## 旧实现为什么必然泄漏闹钟
 *
 * `reconcileSchedule` 此前是「`deleteFuturePendingSlots` + 全量重新投影插入」。
 * 由于 `slot.id` 同时是闹钟的 `requestCode`，**每次对账都换一批 id**：
 * 新 id 的闹钟注册上去，旧 id 的闹钟失去任何引用，变成**永远不会被取消的孤儿**。
 * 用户表现为同一次服药收到多次提醒，且重启也清不掉。
 *
 * 现在改为幂等 diff：已存在的槽位**保留原 id**、一行不动。
 * 所以下面每条测试盯的都是"id 是否稳定"，而不只是"行数对不对"。
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class ReconcileScheduleTest {

    private lateinit var db: AppDatabase
    private lateinit var service: DoseTrackingService

    private val today: LocalDate = LocalDate.of(2026, 9, 28)

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        service = DoseTrackingService(db)
    }

    @After
    @Throws(IOException::class)
    fun tearDown() = db.close()

    /** 建一个"每天两次（08:00 / 20:00）"的药，返回 (medId, policyId) */
    private suspend fun newMed(name: String = "测试药"): Pair<Long, Long> {
        val medId = db.medicationDao().insert(MedicationEntity(name = name))
        val policyId = db.schedulePolicyDao().savePolicyWithTimes(
            SchedulePolicyEntity(
                medicationId = medId,
                policyType = PolicyType.DAILY,
                startDate = today.toString()
            ),
            listOf(
                PolicyTimeEntity(policyId = 0, timeOfDay = "08:00", doseAmount = 1000),
                PolicyTimeEntity(policyId = 0, timeOfDay = "20:00", doseAmount = 1000)
            )
        )
        return medId to policyId
    }

    private suspend fun slotsOf(medId: Long): List<DoseSlotEntity> =
        db.doseSlotDao().getAllSlots()
            .filter { it.medicationId == medId }
            .sortedWith(compareBy({ it.scheduledDate }, { it.scheduledTime }))

    // ==================== I6 幂等性 ====================

    @Test
    fun `I6 重复对账不产生任何变化 且 slot id 完全稳定`() = runTest {
        val (medId, _) = newMed()
        service.reconcileSchedule(medId, today, today.plusDays(7))
        val first = slotsOf(medId)
        assertThat(first).isNotEmpty()

        // 连跑 5 次（模拟反复打开 App / 反复对账 / WorkManager 周期对账）
        repeat(5) { service.reconcileSchedule(medId, today, today.plusDays(7)) }
        val after = slotsOf(medId)

        assertThat(after.size).isEqualTo(first.size)
        // ⭐ id 逐一相同 —— 这正是旧实现（全删重建）做不到的
        assertThat(after.map { it.id }).isEqualTo(first.map { it.id })
    }

    @Test
    fun `I6 对账不会凭空造出重复槽位`() = runTest {
        val (medId, _) = newMed()
        repeat(3) { service.reconcileSchedule(medId, today, today.plusDays(7)) }

        val all = db.doseSlotDao().getAllSlots().filter { it.medicationId == medId }
        val keys = all.map { Triple(it.medicationId, it.scheduledDate, it.scheduledTime) }
        assertThat(keys).containsNoDuplicates()
    }

    @Test
    fun `I6 对账产出的槽位集合与引擎直接投影完全一致`() = runTest {
        val (medId, policyId) = newMed()
        service.reconcileSchedule(medId, today, today.plusDays(5))

        val policy = db.schedulePolicyDao().getActivePolicyForMedication(medId)!!
        val times = db.schedulePolicyDao().getTimesForPolicy(policyId)
        val projected = SlotProjectionEngine.projectSlots(
            policy = policy,
            times = times,
            fromDate = today,
            toDate = today.plusDays(5)
        )

        val inDb = slotsOf(medId).map { it.scheduledDate to it.scheduledTime }.toSet()
        val fromEngine = projected.map { it.scheduledDate to it.scheduledTime }.toSet()
        assertThat(inDb).isEqualTo(fromEngine)
    }

    // ==================== I5 数据库唯一约束 ====================

    @Test
    fun `I5 数据库层面拒绝重复的 药品-日期-时刻`() = runTest {
        val (medId, policyId) = newMed()
        service.reconcileSchedule(medId, today, today.plusDays(2))
        val existing = slotsOf(medId).first()

        // 绕过 reconcileSchedule 的去重逻辑，直接插一条完全重复的槽位
        val duplicate = DoseSlotEntity(
            medicationId = medId,
            policyId = policyId,
            scheduledDate = existing.scheduledDate,
            scheduledTime = existing.scheduledTime,
            scheduledTs = existing.scheduledTs + 1,
            doseAmount = 1000,
            status = SlotStatus.PENDING
        )
        // insertAll 用 IGNORE 策略 ⇒ 重复键被静默忽略，而不是让整批插入失败
        db.doseSlotDao().insertAll(listOf(duplicate))

        val after = slotsOf(medId)
        assertThat(after.count { it.scheduledDate == existing.scheduledDate &&
            it.scheduledTime == existing.scheduledTime }).isEqualTo(1)
        // 并且原行的 id 没被换掉（REPLACE 会换 id ⇒ 闹钟孤儿）
        assertThat(after.first { it.id == existing.id }.id).isEqualTo(existing.id)
    }

    // ==================== P1-5 闹钟身份稳定 ====================

    @Test
    fun `P1-5 重复对账后既有槽位的 id 一行不动`() = runTest {
        val (medId, _) = newMed()
        service.reconcileSchedule(medId, today, today.plusDays(3))
        val before = slotsOf(medId).associateBy { "${it.scheduledDate} ${it.scheduledTime}" }

        service.reconcileSchedule(medId, today, today.plusDays(3))
        val after = slotsOf(medId).associateBy { "${it.scheduledDate} ${it.scheduledTime}" }

        assertThat(after.keys).isEqualTo(before.keys)
        before.forEach { (key, slot) ->
            assertThat(after.getValue(key).id).isEqualTo(slot.id)
        }
    }

    // ==================== 改计划：增删正确、既有事实不动 ====================

    @Test
    fun `收窄窗口后窗口外的待服槽位被删 窗口内保留原 id`() = runTest {
        val (medId, _) = newMed()
        service.reconcileSchedule(medId, today, today.plusDays(6))
        val wide = slotsOf(medId)
        assertThat(wide).isNotEmpty()
        val firstId = wide.first().id

        // 窗口缩到 2 天 ⇒ 3~6 天的待服槽位应消失
        service.reconcileSchedule(medId, today, today.plusDays(2))
        val narrow = slotsOf(medId)

        assertThat(narrow.all { it.scheduledDate <= today.plusDays(2).toString() }).isTrue()
        assertThat(narrow.map { it.id }).contains(firstId)
    }

    @Test
    fun `已完成的槽位绝对不被对账删除`() = runTest {
        val (medId, _) = newMed()
        service.reconcileSchedule(medId, today, today.plusDays(3))
        val target = slotsOf(medId).first()

        db.doseSlotDao().markCompletedIfOpen(target.id, System.currentTimeMillis())
        val completedId = target.id

        // 窗口后移 ⇒ 今天的槽位不再被投影命中
        service.reconcileSchedule(medId, today.plusDays(2), today.plusDays(5))

        val stillThere = db.doseSlotDao().getSlotById(completedId)
        assertThat(stillThere).isNotNull()
        assertThat(stillThere!!.status).isEqualTo(SlotStatus.COMPLETED)
    }

    @Test
    fun `已跳过与已逾期的槽位同样不被对账删除`() = runTest {
        val (medId, _) = newMed()
        service.reconcileSchedule(medId, today, today.plusDays(3))
        val slots = slotsOf(medId)

        val skipped = slots[0]
        db.doseSlotDao().forceStatusForTest(skipped.id, SlotStatus.SKIPPED, System.currentTimeMillis())
        val expired = slots[1]
        db.doseSlotDao().forceStatusForTest(expired.id, SlotStatus.EXPIRED, null)

        service.reconcileSchedule(medId, today.plusDays(2), today.plusDays(5))

        assertThat(db.doseSlotDao().getSlotById(skipped.id)?.status).isEqualTo(SlotStatus.SKIPPED)
        assertThat(db.doseSlotDao().getSlotById(expired.id)?.status).isEqualTo(SlotStatus.EXPIRED)
    }

    @Test
    fun `推迟中的槽位不因对账被删（它是用户主动改期的事实）`() = runTest {
        val (medId, _) = newMed()
        service.reconcileSchedule(medId, today, today.plusDays(3))
        val target = slotsOf(medId).first()

        val until = System.currentTimeMillis() + 30 * 60_000L
        db.doseSlotDao().snoozeSlot(target.id, until)

        // 窗口后移使其不再被投影命中
        service.reconcileSchedule(medId, today.plusDays(2), today.plusDays(5))

        val after = db.doseSlotDao().getSlotById(target.id)
        assertThat(after).isNotNull()
        assertThat(after!!.status).isEqualTo(SlotStatus.SNOOZED)
    }

    // ==================== 投机区：窗口之外的未来必须被丢弃 ====================

    @Test
    fun `投机区（窗口外）的待服槽位被整段丢弃`() = runTest {
        val (medId, _) = newMed()
        service.reconcileSchedule(medId, today, today.plusDays(6))
        assertThat(slotsOf(medId).any { it.scheduledDate > today.plusDays(6).toString() }).isFalse()

        // 排一次 10 天窗口 ⇒ 产生 4 天的投机区槽位
        service.reconcileSchedule(medId, today, today.plusDays(10))
        val speculative = slotsOf(medId).filter { it.scheduledDate > today.plusDays(6).toString() }
        assertThat(speculative).isNotEmpty()

        // 收窄回 6 天 ⇒ 投机区必须被清空，否则会留下"已不存在的时刻"的闹钟
        service.reconcileSchedule(medId, today, today.plusDays(6))
        val left = slotsOf(medId).filter { it.scheduledDate > today.plusDays(6).toString() }
        assertThat(left).isEmpty()
    }

    @Test
    fun `投机区丢弃只针对开放槽位 已完成的事实仍然保留`() = runTest {
        val (medId, _) = newMed()
        service.reconcileSchedule(medId, today, today.plusDays(10))

        val tail = slotsOf(medId).first { it.scheduledDate > today.plusDays(6).toString() }
        db.doseSlotDao().markCompletedIfOpen(tail.id, System.currentTimeMillis())
        val completedTailId = tail.id

        service.reconcileSchedule(medId, today, today.plusDays(6))

        val survivor = db.doseSlotDao().getSlotById(completedTailId)
        assertThat(survivor).isNotNull()
        assertThat(survivor!!.status).isEqualTo(SlotStatus.COMPLETED)
    }

    // ==================== 新增槽位 ====================

    @Test
    fun `窗口后移只新增槽位 不动已有槽位`() = runTest {
        val (medId, _) = newMed()
        service.reconcileSchedule(medId, today, today.plusDays(2))
        val before = slotsOf(medId).map { it.id }.toSet()

        service.reconcileSchedule(medId, today, today.plusDays(6))
        val after = slotsOf(medId)

        assertThat(after.size).isGreaterThan(before.size)
        assertThat(after.map { it.id }).containsAtLeastElementsIn(before)
    }

    @Test
    fun `剂量在毫单位口径下正确物化`() = runTest {
        val (medId, _) = newMed()
        service.reconcileSchedule(medId, today, today.plusDays(1))
        slotsOf(medId).forEach { assertDoseMilli(it.doseAmount, 1000) }
    }

    @Test
    fun `无有效计划时对账是空操作`() = runTest {
        val medId = db.medicationDao().insert(MedicationEntity(name = "无计划药"))
        service.reconcileSchedule(medId, today, today.plusDays(3))
        assertThat(slotsOf(medId)).isEmpty()
    }
}
