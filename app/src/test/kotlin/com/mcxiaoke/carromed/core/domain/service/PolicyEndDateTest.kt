package com.mcxiaoke.carromed.core.domain.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.LocalDate

/**
 * 疗程结束日的"改 / 不改 / 清空"三态（治 P1-5）。
 *
 * ## 缺陷
 *
 * `saveReminderPolicy` 用 `draft.endDate ?: previous?.endDate` 实现"没改就沿用"，
 * 但 `null` 同时表达了两种意图。于是提醒设置页**确实提供**的那个清空入口
 * （`Switch` 关掉 + 日期框 clear 图标）传上来的 `endDate = null` 被当成"没改"。
 *
 * 后果不是"开关看着关了但没生效"这么轻：
 * 抗生素设了「疗程至 10-05」，疗程结束后医生说继续吃，用户关掉开关保存 ——
 * `endDate` 仍写回 `2026-10-05`，**10-06 起所有提醒静默消失**，
 * 而用户以为自己已经改成长期服用了。
 *
 * 这是"提醒不能漏"的反面，所以按严重度当 bug 修，而不是当"小瑕疵"。
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class PolicyEndDateTest {

    private lateinit var db: AppDatabase
    private lateinit var admin: MedicationAdminService
    private lateinit var tracking: DoseTrackingService

    private val today: LocalDate = LocalDate.of(2026, 9, 28)

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        admin = MedicationAdminService(db)
        tracking = DoseTrackingService(db)
    }

    @After
    @Throws(IOException::class)
    fun tearDown() = db.close()

    private suspend fun newMed(): Long = admin.saveProfile(
        MedicationAdminService.ProfileDraft(name = "阿莫西林", unit = "片", defaultDose = 1f)
    )

    private fun draft(
        endDate: String? = null,
        clear: Boolean = false,
        start: String = today.toString()
    ) = MedicationAdminService.PolicyDraft(
        startDate = start,
        endDate = endDate,
        clearEndDate = clear,
        times = listOf(MedicationAdminService.TimeDraft("08:00", 1f, "早间"))
    )

    private suspend fun activeEnd(medId: Long): String? =
        db.schedulePolicyDao().getActivePolicyForMedication(medId)?.endDate

    // ================================================================
    // 三态
    // ================================================================

    @Test
    fun `新药不传结束日 就是无限期`() = runTest {
        val medId = newMed()
        admin.saveReminderPolicy(medId, draft())
        assertThat(activeEnd(medId)).isNull()
    }

    @Test
    fun `设了结束日会被记住`() = runTest {
        val medId = newMed()
        admin.saveReminderPolicy(medId, draft(endDate = "2026-10-05"))
        assertThat(activeEnd(medId)).isEqualTo("2026-10-05")
    }

    /** 改别的字段而没碰结束日 ⇒ 沿用历史值（这是 `?:` 原本要服务的那条） */
    @Test
    fun `没碰结束日时 改其它设置不会把结束日冲掉`() = runTest {
        val medId = newMed()
        admin.saveReminderPolicy(medId, draft(endDate = "2026-10-05"))
        // 换个时刻，endDate 仍不传
        admin.saveReminderPolicy(medId, draft())
        assertThat(activeEnd(medId)).isEqualTo("2026-10-05")
    }

    @Test
    fun `显式清空结束日 真的清得掉`() = runTest {
        val medId = newMed()
        admin.saveReminderPolicy(medId, draft(endDate = "2026-10-05"))
        assertThat(activeEnd(medId)).isEqualTo("2026-10-05")

        // ★ 这就是 UI 关掉 Switch 时传上来的组合
        admin.saveReminderPolicy(medId, draft(endDate = null, clear = true))
        assertThat(activeEnd(medId)).isNull()
    }

    @Test
    fun `清空后 疗程结束日之后照样排得出槽位（提醒不再静默停止）`() = runTest {
        val medId = newMed()
        admin.saveReminderPolicy(medId, draft(endDate = today.plusDays(2).toString()))
        // 疗程结束日之后确实没有槽位
        tracking.reconcileSchedule(medId, today.plusDays(3), today.plusDays(9))
        val afterCourse = db.doseSlotDao().getSlotsInRange(
            today.plusDays(3).toString(), today.plusDays(9).toString()
        )
        assertThat(afterCourse).isEmpty()

        // 清空疗程
        admin.saveReminderPolicy(medId, draft(endDate = null, clear = true))
        tracking.reconcileSchedule(medId, today.plusDays(3), today.plusDays(9))

        // ★ 现在同一区间必须有槽位 —— 旧实现下这里仍然是空的，
        // 于是提醒在该日静默停止而用户以为还在吃药
        assertThat(db.doseSlotDao().getSlotsInRange(
            today.plusDays(3).toString(), today.plusDays(9).toString()
        )).isNotEmpty()
    }

    @Test
    fun `清空再设回来可以正常生效`() = runTest {
        val medId = newMed()
        admin.saveReminderPolicy(medId, draft(endDate = "2026-10-05"))
        admin.saveReminderPolicy(medId, draft(endDate = null, clear = true))
        admin.saveReminderPolicy(medId, draft(endDate = "2026-10-20"))
        assertThat(activeEnd(medId)).isEqualTo("2026-10-20")
    }

    /** `clearEndDate` 与非空 `endDate` 同时给出时，"清空"优先 —— 否则草稿自相矛盾 */
    @Test
    fun `同时给清空标志与日期值 以清空为准`() = runTest {
        val medId = newMed()
        admin.saveReminderPolicy(medId, draft(endDate = "2026-10-05"))
        admin.saveReminderPolicy(medId, draft(endDate = "2026-10-20", clear = true))
        assertThat(activeEnd(medId)).isNull()
    }

    /** 清空结束日不得顺手改掉已经排好的开放槽位 */
    @Test
    fun `清空结束日不改动已排好的开放槽位`() = runTest {
        val medId = newMed()
        admin.saveReminderPolicy(medId, draft(endDate = today.plusDays(6).toString()))
        tracking.reconcileSchedule(medId, today, today.plusDays(6))
        val before = db.doseSlotDao().getSlotsInRange(today.toString(), today.plusDays(6).toString())
        assertThat(before).isNotEmpty()

        admin.saveReminderPolicy(medId, draft(endDate = null, clear = true))
        tracking.reconcileSchedule(medId, today, today.plusDays(6))

        val after = db.doseSlotDao().getSlotsInRange(today.toString(), today.plusDays(6).toString())
        assertThat(after.map { it.id to it.status })
            .isEqualTo(before.map { it.id to it.status })
    }

    // ================================================================
    // 推迟的状态守卫（治 P1-6）
    // ================================================================

    /**
     * 已完成的槽位不得被推迟打回 `SNOOZED`。
     *
     * 可达路径：长按卡片弹出推迟菜单的同时，通知栏的「确认已吃」被点了。
     * 槽位变 `COMPLETED`、事实入库、**库存已扣**；若随后被改回 `SNOOZED`，
     * SNOOZE 闹钟再响一次就会插入第二条 `COMPLETED` 事实并**二次扣减库存**。
     */
    @Test
    fun `已完成的槽位不得被推迟`() = runTest {
        val medId = newMed()
        admin.saveReminderPolicy(medId, draft())
        tracking.reconcileSchedule(medId, today, today)
        val slotId = db.doseSlotDao().getSlotsForDate(today.toString())
            .first { it.medicationId == medId }.id

        assertThat(tracking.takeDose(slotId)).isTrue()
        assertThat(db.doseSlotDao().getSlotById(slotId)!!.status).isEqualTo(SlotStatus.COMPLETED)

        // 返回 false 且状态不变
        assertThat(tracking.snoozeDose(slotId, 10)).isFalse()
        val after = db.doseSlotDao().getSlotById(slotId)!!
        assertThat(after.status).isEqualTo(SlotStatus.COMPLETED)
        assertThat(after.snoozeUntilTs).isNull()
    }

    /** 推迟中的槽位可以**改**推迟时长（幂等，守卫含 SNOOZED） */
    @Test
    fun `推迟中的槽位可以改推迟时长`() = runTest {
        val medId = newMed()
        admin.saveReminderPolicy(medId, draft())
        tracking.reconcileSchedule(medId, today, today)
        val slotId = db.doseSlotDao().getSlotsForDate(today.toString())
            .first { it.medicationId == medId }.id

        assertThat(tracking.snoozeDose(slotId, 10)).isTrue()
        val first = db.doseSlotDao().getSlotById(slotId)!!.snoozeUntilTs
        assertThat(first).isNotNull()

        assertThat(tracking.snoozeDose(slotId, 30)).isTrue()
        val second = db.doseSlotDao().getSlotById(slotId)!!.snoozeUntilTs
        assertThat(second!!).isGreaterThan(first!!)
    }

    /** 超出取值域的推迟时长不得让 `snooze_until_ts` 溢出到过去 */
    @Test
    fun `极端推迟时长不会溢出成过去的时间戳`() = runTest {
        val medId = newMed()
        admin.saveReminderPolicy(medId, draft())
        tracking.reconcileSchedule(medId, today, today)
        val slotId = db.doseSlotDao().getSlotsForDate(today.toString())
            .first { it.medicationId == medId }.id

        val before = System.currentTimeMillis()
        assertThat(tracking.snoozeDose(slotId, Int.MAX_VALUE)).isTrue()
        val ts = db.doseSlotDao().getSlotById(slotId)!!.snoozeUntilTs!!

        // ★ 必须在未来且不超过 4 小时
        assertThat(ts).isGreaterThan(before)
        assertThat(ts).isAtMost(before + 240L * 60_000L)
    }
}
