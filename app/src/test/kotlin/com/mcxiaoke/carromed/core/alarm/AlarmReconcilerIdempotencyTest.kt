package com.mcxiaoke.carromed.core.alarm

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.entity.PolicyTimeEntity
import com.mcxiaoke.carromed.core.data.entity.SchedulePolicyEntity
import com.mcxiaoke.carromed.core.data.model.PolicyType
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.LocalDate

/**
 * 全量对账的幂等性测试（A6 的前提，兑现 `FINAL-PRODUCT` D-14 第三档）。
 *
 * ## 为什么这条测试必须存在
 *
 * A6 让 WorkManager 每 15 分钟重跑一次 `AlarmReconciler.rescheduleAll`。
 * 一旦它**不幂等**，后果是灾难性的而非缓慢的：
 *
 * | 不幂等的表现 | 用户看到什么 |
 * | :--- | :--- |
 * | 每次对账换一批 slot id | 闹钟 `requestCode` 漂移，旧闹钟成孤儿 ⇒ 同一次服药反复响 |
 * | 每次对账多插一批槽位 | 「今日清单」出现重复条目、统计翻倍 |
 * | 每次对账清空已登记的闹钟 | 提醒随机漏响，且**越是重排越容易漏** |
 *
 * 15 分钟一轮意味着一天 96 次。前两次可能看不出来，一天以后就完全失控。
 *
 * ## 为什么显式传库而不是用 `AppDatabase.getInstance`
 *
 * 那是 `companion object` 的静态单例，**跨 Robolectric 测试方法不会重置**，
 * 而每个方法都会重建 `Application` 与沙箱文件系统。用它会让本测试
 * 读到上一个测试留下的坏句柄而假红。用内存库则每次都是干净的。
 * 同样的坑也导致 `ReconcileWorkerTest` 里不能测 `doWork()`（见该文件的 KDoc）。
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class AlarmReconcilerIdempotencyTest {

    private lateinit var db: AppDatabase

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val today: LocalDate get() = LocalDate.now()

    @Before
    fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // ⚠️ fixture 的时点**都选在 23:00 之后**。
        //
        // 早期版本用 08:00 / 20:00 / 13:30，于是测试**随时钟翻转**：
        // 「计划时间 + 2 小时」一过，`rescheduleAll` 的过期结算就把当天那条改成 EXPIRED，
        // 而 `reconcileSchedule` **只删 PENDING / SNOOZED**（EXPIRED 是既成事实），
        // 于是任何按**槽位条数**写的断言都会在下午变色红。
        //
        // 换时点只是把窗口从"每天下午"挪到"每天午夜前"，**并没有解决问题**。
        // 真正的修法是断言不变量本身：下面所有断言都只针对 `openSlotsOf`
        // （PENDING / SNOOZED），且尽量按**日期集合**而非条数。
        seedMedication(name = "环孢素", times = listOf("23:10", "23:20"))
        seedMedication(name = "维生素D", times = listOf("23:50"))
    }

    @After
    @Throws(IOException::class)
    fun tearDown() = db.close()

    private suspend fun seedMedication(name: String, times: List<String>) {
        val medId = db.medicationDao().insert(MedicationEntity(name = name))
        db.schedulePolicyDao().savePolicyWithTimes(
            SchedulePolicyEntity(
                medicationId = medId,
                policyType = PolicyType.DAILY,
                startDate = today.toString()
            ),
            times.map { PolicyTimeEntity(policyId = 0, timeOfDay = it, doseAmount = 1000) }
        )
        // A2 起提醒运行态在独立表，必须显式建行，否则 getActiveOverviews 拿不到
        db.reminderSettingsDao().ensureDefaults(medId)
    }

    private suspend fun allSlots() = db.doseSlotDao().getAllSlots()
        .sortedWith(compareBy({ it.medicationId }, { it.scheduledDate }, { it.scheduledTime }))

    // ==================== 幂等 ====================

    @Test
    fun `连续 5 次全量对账 槽位集合与 id 完全不变`() = runBlocking {
        AlarmReconciler.rescheduleAll(context, db)
        val first = allSlots()
        assertThat(first).isNotEmpty()

        repeat(5) { AlarmReconciler.rescheduleAll(context, db) }
        val after = allSlots()

        assertThat(after).hasSize(first.size)
        // ⭐ id 逐一相同：id 就是闹钟身份，变了就意味着闹钟漂移
        assertThat(after.map { it.id }).isEqualTo(first.map { it.id })
        assertThat(after.map { it.scheduledDate to it.scheduledTime })
            .isEqualTo(first.map { it.scheduledDate to it.scheduledTime })
    }

    @Test
    fun `连续对账不会造出重复槽位`() = runBlocking {
        repeat(3) { AlarmReconciler.rescheduleAll(context, db) }
        val keys = allSlots().map { Triple(it.medicationId, it.scheduledDate, it.scheduledTime) }
        assertThat(keys).containsNoDuplicates()
    }

    @Test
    fun `视野覆盖满 HORIZON_DAYS 天且不多不少`() = runBlocking {
        AlarmReconciler.rescheduleAll(context, db)
        val dates = allSlots().map { it.scheduledDate }.toSortedSet()
        assertThat(dates.first()).isEqualTo(today.toString())
        assertThat(dates.last()).isEqualTo(today.plusDays(AlarmReconciler.HORIZON_DAYS).toString())
        // 中间每一天都必须有槽位（2 个药 → 每天 3 个槽位）
        assertThat(dates).hasSize(AlarmReconciler.HORIZON_DAYS.toInt() + 1)
        assertThat(allSlots()).hasSize((AlarmReconciler.HORIZON_DAYS.toInt() + 1) * 3)
    }

    @Test
    fun `HORIZON_DAYS 至少 14 天（低于它就回到 P0-2 的静默漏提醒）`() {
        // 旧实现是 7 天，且没有自续期 ⇒ 装好 App 后不碰它，第 8 天开始静默漏提醒
        assertThat(AlarmReconciler.HORIZON_DAYS).isAtLeast(14L)
    }

    // ==================== 暂停：槽位存在 ⟺ 会提醒 ====================
    //
    // 产品口径（用户 2026-09-28 拍板）：「今日清单显示的是**当日会提醒**的项；
    // 没有提醒存在，为什么要显示」。
    //
    // 实现选择是让暂停参与**投影**（SlotProjectionEngine 收 pausedUntil），
    // 而不是加一层显示过滤：槽位不产生 ⇒ 今日清单、统计、闹钟三处自动一致。
    // 早期实现只把暂停当成"影响闹钟"，于是槽位留在库里，被统计算成漏服、
    // 也仍列在今日清单，而闹钟早已被撤掉 —— 用户看到一条永远不会兑现的待办。
    //
    // ─────────────────────────────────────────────────────────────────
    // ⚠️ 下面每条断言都**只针对 PENDING / SNOOZED**，且尽量**按日期而非总数**断言。
    //
    // 这不是洁癖，是被现实教训逼的：早先版本按"总数"断言、fixture 时点取 13:30，
    // 于是测试**随时钟翻转**——
    //   15:30 之前：13:30 还没到「计划时间 + 2 小时」的逾期线，测试通过；
    //   15:30 之后：它被 rescheduleAll 的过期结算改成 EXPIRED，
    //              而 EXPIRED 是**既成事实**、reconcileSchedule 不会删它，
    //              于是"一条都不剩"多出 1 条而变红。
    // 换 fixture 时点（13:30 → 23:50）只是把窗口从"每天下午"挪到"每天午夜前"，
    // **并没有解决问题**。真正的修法是断言不变量本身：
    // 「暂停期内不存在**待办**」，而不是「暂停期内一行都没有」。

    /** 某药在给定日期范围内仍处于"待办"（会被提醒）的槽位 */
    private suspend fun openSlotsOf(
        medId: Long,
        onOrBefore: String? = null,
        onOrAfter: String? = null
    ) = db.doseSlotDao().getAllSlots().filter {
        it.medicationId == medId &&
            (onOrBefore == null || it.scheduledDate <= onOrBefore) &&
            (onOrAfter == null || it.scheduledDate >= onOrAfter) &&
            (it.status == SlotStatus.PENDING || it.status == SlotStatus.SNOOZED)
    }

    @Test
    fun `暂停期内该药不再有待办槽位（而不是留在清单里当待办）`() = runBlocking {
        val medId = db.medicationDao().getActiveOverviews().first { it.name == "维生素D" }.id
        AlarmReconciler.rescheduleAll(context, db)
        // 未暂停时窗口内确实有待办 —— 否则下面全是恒真断言
        assertThat(openSlotsOf(medId).size)
            .isEqualTo(AlarmReconciler.HORIZON_DAYS.toInt() + 1)

        // 暂停至明天 ⇒ 今天 + 明天两天都不该有待办
        val pauseEnd = today.plusDays(1).toString()
        db.reminderSettingsDao().setPausedUntil(medId, pauseEnd)
        repeat(3) { AlarmReconciler.rescheduleAll(context, db) }

        assertThat(openSlotsOf(medId, onOrBefore = pauseEnd)).isEmpty()
        // 恢复日之后照常有待办 —— 证明不是"把这个药整个清空了"
        assertThat(openSlotsOf(medId, onOrAfter = today.plusDays(2).toString())).isNotEmpty()
    }

    @Test
    fun `暂停只影响该药 其它药照常排班`() = runBlocking {
        val medId = db.medicationDao().getActiveOverviews().first { it.name == "维生素D" }.id
        AlarmReconciler.rescheduleAll(context, db)
        assertThat(openSlotsOf(medId).size).isGreaterThan(0)

        db.reminderSettingsDao().setPausedUntil(medId, "")
        AlarmReconciler.rescheduleAll(context, db)

        assertThat(openSlotsOf(medId)).isEmpty()
        // 另一味药照常有待办 ⇒ 过滤是按药品粒度的
        assertThat(db.doseSlotDao().getAllSlots().any { it.medicationId != medId }).isTrue()
    }

    @Test
    fun `无限期暂停清空该药全部待办槽位`() = runBlocking {
        val medId = db.medicationDao().getActiveOverviews().first { it.name == "维生素D" }.id
        AlarmReconciler.rescheduleAll(context, db)
        assertThat(openSlotsOf(medId).size).isGreaterThan(0)

        // "" = 无限期，须用户手动恢复
        db.reminderSettingsDao().setPausedUntil(medId, "")
        AlarmReconciler.rescheduleAll(context, db)

        assertThat(openSlotsOf(medId)).isEmpty()
    }

    @Test
    fun `恢复后窗口补满 恢复日之后的槽位保留原 id`() = runBlocking {
        val medId = db.medicationDao().getActiveOverviews().first { it.name == "维生素D" }.id
        AlarmReconciler.rescheduleAll(context, db)
        val pauseEnd = today                       // 暂停至今天 ⇒ 只压掉今天
        val idBefore = openSlotsOf(medId).associateBy { it.scheduledDate }
        assertThat(idBefore).isNotEmpty()

        db.reminderSettingsDao().setPausedUntil(medId, pauseEnd.toString())
        AlarmReconciler.rescheduleAll(context, db)
        // 暂停期内没有待办。**故意不按总数断言**：今天的槽位可能已被过期结算改成
        // EXPIRED，而 EXPIRED 是既成事实、reconcileSchedule 不会删它 ——
        // 那正是我们要的行为，按总数断言会把它当成失败。
        assertThat(openSlotsOf(medId, onOrBefore = pauseEnd.toString())).isEmpty()

        db.reminderSettingsDao().resume(medId)
        AlarmReconciler.rescheduleAll(context, db)

        // ⭐ 恢复日**之后**的槽位一行不动 —— 它们的 id 就是闹钟身份，绝不能漂移。
        //    只对 > 恢复日 的部分断言：恢复日当天的槽位被删过又重建，id 必然变。
        val after = openSlotsOf(medId, onOrAfter = today.plusDays(1).toString())
        assertThat(after).isNotEmpty()
        after.forEach { slot ->
            val original = idBefore[slot.scheduledDate]
            assertThat(original).isNotNull()
            assertThat(slot.id).isEqualTo(original!!.id)
        }
    }

    @Test
    fun `暂停到期后无需用户操作 即自动恢复排班`() = runBlocking {
        val medId = db.medicationDao().getActiveOverviews().first { it.name == "维生素D" }.id
        // 暂停到昨天 ⇒ 按 isPausedOn 判据，今天已经不在暂停期内
        db.reminderSettingsDao().setPausedUntil(medId, today.minusDays(1).toString())

        AlarmReconciler.rescheduleAll(context, db)

        // 关键：不能因为 `paused_until != null` 就判成"还暂停着"
        assertThat(openSlotsOf(medId).size)
            .isEqualTo(AlarmReconciler.HORIZON_DAYS.toInt() + 1)
    }

    @Test
    fun `暂停不会删除既成事实（已逾期的槽位必须留下）`() = runBlocking {
        val medId = db.medicationDao().getAllMedications().first { it.name == "环孢素" }.id
        AlarmReconciler.rescheduleAll(context, db)
        val target = db.doseSlotDao().getAllSlots().first { it.medicationId == medId }
        db.doseSlotDao().forceStatusForTest(target.id, SlotStatus.EXPIRED, null)
        val expiredId = target.id

        db.reminderSettingsDao().setPausedUntil(medId, "")
        AlarmReconciler.rescheduleAll(context, db)

        // ⭐ 既成事实不因暂停而消失。`reconcileSchedule` 只删 PENDING / SNOOZED
        // 正是为了这条 —— 逾期是"该吃没吃"的历史，不是待办。
        val still = db.doseSlotDao().getSlotById(expiredId)
        assertThat(still).isNotNull()
        assertThat(still!!.status).isEqualTo(SlotStatus.EXPIRED)
    }

    // ==================== 删掉服用计划 ====================

    @Test
    fun `删掉服用计划后 该药不再有待办槽位`() = runBlocking {
        val medId = db.medicationDao().getActiveOverviews().first { it.name == "维生素D" }.id
        AlarmReconciler.rescheduleAll(context, db)
        assertThat(openSlotsOf(medId)).isNotEmpty()

        db.schedulePolicyDao().deactivatePoliciesForMedication(medId)
        AlarmReconciler.rescheduleAll(context, db)

        // 没有计划 ⇒ 不该再有"该吃没吃"的待办（它们不是既成事实）
        assertThat(openSlotsOf(medId)).isEmpty()
    }

    @Test
    fun `删掉服用计划不影响另一个药`() = runBlocking {
        val target = db.medicationDao().getActiveOverviews().first { it.name == "维生素D" }.id
        val otherId = db.medicationDao().getAllMedications().first { it.name == "环孢素" }.id
        AlarmReconciler.rescheduleAll(context, db)
        // ⚠️ 断言**日期覆盖集合**而不是条数。
        // 环孢素的 fixture 含 08:00，过了当天 10:00 就会被过期结算改成 EXPIRED，
        // 于是"条数"本身随时钟变化 —— 拿它当基准等于把断言绑在运行时刻上。
        val datesBefore = openSlotsOf(otherId).map { it.scheduledDate }.toSortedSet()
        assertThat(datesBefore).isNotEmpty()

        db.schedulePolicyDao().deactivatePoliciesForMedication(target)
        AlarmReconciler.rescheduleAll(context, db)

        assertThat(openSlotsOf(otherId).map { it.scheduledDate }.toSortedSet())
            .isEqualTo(datesBefore)
    }

    // ==================== 停药归档 ====================

    @Test
    fun `归档的药品不再参与排班`() = runBlocking {
        AlarmReconciler.rescheduleAll(context, db)
        val medId = db.medicationDao().getActiveOverviews().first { it.name == "维生素D" }.id
        val otherId = db.medicationDao().getAllMedications().first { it.name == "环孢素" }.id
        val before = openSlotsOf(medId).size
        assertThat(before).isGreaterThan(0)

        db.medicationDao().updateArchiveStatus(medId, isArchived = true)
        AlarmReconciler.rescheduleAll(context, db)

        // 归档药不再被排新槽位
        assertThat(openSlotsOf(medId).size).isAtMost(before)
        // 另一味药照常排班 ⇒ 过滤是按药品粒度的，没有误伤
        assertThat(openSlotsOf(otherId)).isNotEmpty()
    }

    /**
     * 归档后不得残留开放槽位（治代码审查 zcg 报告 P2#4）。
     *
     * 上一条测试的 `isAtMost(before)` 恰好把缺陷行为放进了容差：归档只撤闹钟，
     * `reconcileSchedule` 只遍历在服药品 ⇒ 归档药的 PENDING/SNOOZED 槽位**没有任何
     * 代码路径会删**（对比：删除药品有 FK 级联清空）。它们继续出现在今日清单上
     * ——而且因为 `medMap` 只含在服药品，渲染时兜底成"未知药品"，
     * 确认按钮照常工作、照常扣库存（`takeDose` 不校验 isArchived）。
     *
     * 本条把不变量钉死：**rescheduleAll 是"全量对账"，归档药的开放槽位不允许活过它**。
     * 归档不是既成事实，"停药"的药不该再欠任何待办。
     */
    @Test
    fun `归档后 该药名下不得残留任何开放槽位`() = runBlocking {
        val medId = db.medicationDao().getActiveOverviews().first { it.name == "维生素D" }.id
        AlarmReconciler.rescheduleAll(context, db)
        // 前提：归档前确实有开放槽位，否则断言恒真
        assertThat(openSlotsOf(medId)).isNotEmpty()

        db.medicationDao().updateArchiveStatus(medId, isArchived = true)
        AlarmReconciler.rescheduleAll(context, db)

        // ★ 当前实现这里留着整窗 PENDING ⇒ 红
        assertThat(openSlotsOf(medId)).isEmpty()
    }
}
