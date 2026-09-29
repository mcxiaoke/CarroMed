package com.mcxiaoke.carromed.core.alarm

import android.app.AlarmManager
import android.app.PendingIntent
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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.io.IOException
import java.time.LocalDate

/**
 * 对账器对「闹钟档位」与「宽限期黑洞」的处理（M1-1 / M1-7）。
 *
 * ## M1-1 守的是什么
 *
 * 降级链路此前是**静默**的：Manifest 声明可撤销的 `SCHEDULE_EXACT_ALARM`、
 * App 从不引导授权 ⇒ `canScheduleExactAlarms()` 恒 false ⇒
 * 全部落到 `setAndAllowWhileIdle`，而该 API 在 Android 上带 **+1 小时窗口**
 * （`dumpsys alarm` 实测 67 个闹钟全部 `window=3600000`）。
 * "到点提醒"这条第一承诺在 Android 12+ 上整体不成立，而 App 无从知晓、
 * 更无从告知用户。
 *
 * 现在档位由 [AlarmScheduler.currentPrecision] **可查询**，
 * 降级对用户可见。本测试守"降级时档位确实被识别为降级"——
 * 也就是自检页不会对用户撒谎。
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class AlarmPrecisionAndGraceTest {

    private lateinit var db: AppDatabase
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val today: LocalDate get() = LocalDate.now()

    @Before
    fun setup(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val medId = db.medicationDao().insert(MedicationEntity(name = "环孢素"))
        db.schedulePolicyDao().savePolicyWithTimes(
            SchedulePolicyEntity(
                medicationId = medId,
                policyType = PolicyType.DAILY,
                startDate = today.toString()
            ),
            listOf(PolicyTimeEntity(policyId = 0, timeOfDay = "23:30", doseAmount = 1000))
        )
        db.reminderSettingsDao().ensureDefaults(medId)
    }

    @After
    @Throws(IOException::class)
    fun tearDown() {
        // `setCanScheduleExactAlarms` 是 **static** 的，跨测试方法不重置。
        // 不复位就会让后续方法读到上一个方法留下的档位 —— 与
        // `AppDatabase.getInstance` 单例是同一类坑（AGENTS §3）。
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        db.close()
    }

    private fun alarmManager(): AlarmManager =
        context.getSystemService(AlarmManager::class.java)

    // ==================== M1-1 档位可查 ====================

    @Test
    fun `档位枚举自带是否降级的判据 不靠调用方自己比字符串`() {
        assertThat(AlarmScheduler.Precision.EXACT.isDegraded).isFalse()
        assertThat(AlarmScheduler.Precision.ALARM_CLOCK.isDegraded).isTrue()
        assertThat(AlarmScheduler.Precision.INEXACT.isDegraded).isTrue()
    }

    @Test
    fun `降级档位的文案必须写明可能延迟 而不是含糊带过`() {
        // 自检页直接把 label 显示给用户。若文案只写"不精确"，
        // 用户无法判断"到点提醒"这个承诺是否还成立。
        assertThat(AlarmScheduler.Precision.INEXACT.label).contains("延迟")
        assertThat(AlarmScheduler.Precision.EXACT.label).contains("到点")
    }

    @Test
    fun `取不到 AlarmManager 时保守报降级 不谎报精确`() {
        // `currentPrecision(null)` 在 Android 12+ 上无法确认权限状态。
        // 谎报 EXACT 会让自检页显示绿灯而实际闹钟带 1 小时窗口 ——
        // 那正是本工单要消灭的"虚假保证"。保守报降级是唯一安全的一侧。
        val p = AlarmScheduler.currentPrecision(alarmManager = null)
        assertThat(p.isDegraded).isTrue()
    }

    @Test
    fun `已授权时档位是 EXACT 走第一档`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        assertThat(AlarmScheduler.currentPrecision(alarmManager())).isEqualTo(AlarmScheduler.Precision.EXACT)
    }

    @Test
    fun `未授权时档位如实降级 自检页据此显示 而非谎报已授权`() {
        // ⚠️ 这条正是 P0-2 的守门测试。
        //
        // 旧实现里档位是**静默**的：系统不给精确闹钟权限时全部落到
        // `setAndAllowWhileIdle`（+1 小时窗口），而自检页恒显示「已授权」。
        // 用户看到全绿，认定提醒已配置妥当。
        //
        // 现在档位可查。未授权 ⇒ 必须报降级，让自检页把这件事告诉用户。
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertThat(AlarmScheduler.currentPrecision(alarmManager()))
            .isEqualTo(AlarmScheduler.Precision.ALARM_CLOCK)
    }

    // ==================== M1-7 宽限期内补响 ====================

    /**
     * 关机错过的闹钟必须补响。
     *
     * 旧实现里 `(now-2h, now]` 这个区间是**黑洞**：过期结算只管 cutoff 之前，
     * 注册只管 `> now`，中间那段既不结算也不排 —— 用户什么都不知道。
     *
     * 这里用**过去的时点**造一个 PENDING 槽位，模拟"开机后发现错过了"。
     * 断言它仍在库里（没被结算成 EXPIRED）且被判为宽限期内。
     */
    /**
     * 造一条"闹钟丢了但仍在宽限期内"的开放槽位。
     *
     * ## 为什么日期取**昨天**
     *
     * `rescheduleAll` 的重排窗口是 `[今天, 今天+HORIZON_DAYS]`。若把造出来的槽位
     * 挂在**今天**，它不在投影里（时刻是编的），`reconcileSchedule` 的"删"分支
     * 会在过期结算**之后**把它当"已不存在的时刻"删掉 ——
     * 于是无论宽限期逻辑对不对，`getSlotById` 都返回 null，测试报 NPE，
     * 报错信息与真实原因完全无关。
     *
     * 挂昨天就落在重排窗口之外，不会被碰。而"昨晚的闹钟因为关机没响、
     * 用户早上开机"恰恰就是 M1-7 要处理的**真实场景**。
     *
     * `scheduledTs` 是过期判定与闹钟注册的唯一时间依据，所以直接给绝对时间戳：
     * 距今 [agoMinutes] 分钟。
     */
    private suspend fun seedMissedSlot(
        template: DoseSlotEntity,
        timeOfDay: String,
        agoMinutes: Long
    ): Long = db.doseSlotDao().insert(
        template.copy(
            id = 0,
            status = SlotStatus.PENDING,
            scheduledDate = today.minusDays(1).toString(),
            scheduledTime = timeOfDay,
            scheduledTs = System.currentTimeMillis() - agoMinutes * 60_000L
        )
    )

    @Test
    fun `宽限期内的错过槽位既不被结算 也确实被补响`() = runBlocking {
        AlarmReconciler.rescheduleAll(context, db)
        val template = db.doseSlotDao().getOpenSlots().first()
        // 30 分钟前 —— 落在 2 小时宽限期内
        val id = seedMissedSlot(template, "03:17", agoMinutes = 30)
        assertThat(id).isGreaterThan(0L)

        val before = shadowAlarms()

        AlarmReconciler.rescheduleAll(context, db)

        // 前提先钉住：它**没有被**结算成 EXPIRED
        val after = db.doseSlotDao().getSlotById(id)
        assertThat(after).isNotNull()
        assertThat(after!!.status).isEqualTo(SlotStatus.PENDING)
        // 且**这一条**补响了闹钟（而不是让它无声消失）。
        //
        // ⚠️ 不能断言"闹钟总数变多"：对账会给其它未来槽位排几十个闹钟，
        // 那个断言在补响逻辑整个坏掉时仍然成立 —— 正是 AGENTS §3 点名的
        // "恒真断言"。必须按**这一条的闹钟身份**（Uri = medId/date/time/kind）去认。
        assertThat(before).doesNotContain(pendingMainOf(after))
        assertThat(shadowAlarms()).contains(pendingMainOf(after))
    }

    /**
     * Robolectric 里已注册的闹钟的 [PendingIntent] 集合。
     *
     * 用 `PendingIntent` 本身（而不是 `scheduledAlarms.size` 或某种字符串）做断言，
     * 因为 `PendingIntent` 的 `equals` 就是 Android 真实的判重语义
     * —— 与 `AlarmIdentityTest` 同一套做法。
     */
    private fun shadowAlarms(): Set<PendingIntent> {
        val shadow = Shadows.shadowOf(alarmManager()) as ShadowAlarmManager
        return shadow.scheduledAlarms.mapNotNullTo(mutableSetOf()) { it.operation }
    }

    /**
     * 生产调度器会给某槽位注册的**准点**闹钟身份。
     *
     * 走 [AlarmScheduler.alarmIntent]（生产路径）+ 与 `pendingIntent()` 相同的
     * requestCode / flags，而不是在测试里重写一遍 Uri 构造 ——
     * 那样守的是测试里的影子（`AlarmIdentityTest` KDoc 记的正是这个坑）。
     */
    private fun pendingMainOf(slot: DoseSlotEntity): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        AlarmScheduler.alarmIntent(
            context, slot.medicationId, slot.scheduledDate, slot.scheduledTime, slot.id,
            AlarmScheduler.Kind.MAIN
        ),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    @Test
    fun `超出宽限期的槽位被结算 不补响`() = runBlocking {
        AlarmReconciler.rescheduleAll(context, db)
        val template = db.doseSlotDao().getOpenSlots().first()
        val id = seedMissedSlot(template, "04:18", agoMinutes = 180)
        assertThat(id).isGreaterThan(0L)

        AlarmReconciler.rescheduleAll(context, db)

        val after = db.doseSlotDao().getSlotById(id)
        assertThat(after).isNotNull()
        assertThat(after!!.status).isEqualTo(SlotStatus.EXPIRED)
    }

    @Test
    fun `宽限期边界上 过期结算与补响互补不重叠`() = runBlocking {
        // EXPIRE_WINDOW 是 2 小时；紧贴 cutoff 两侧的两条槽位必须有确定归属：
        // 1 小时前 → 补响；3 小时前 → 结算。**不允许两条都落空**——
        // 那就是旧实现的黑洞区间。
        AlarmReconciler.rescheduleAll(context, db)
        val template = db.doseSlotDao().getOpenSlots().first()
        val inGrace = seedMissedSlot(template, "01:00", agoMinutes = 60)
        val outGrace = seedMissedSlot(template, "02:00", agoMinutes = 180)

        AlarmReconciler.rescheduleAll(context, db)

        assertThat(db.doseSlotDao().getSlotById(inGrace)!!.status).isEqualTo(SlotStatus.PENDING)
        assertThat(db.doseSlotDao().getSlotById(outGrace)!!.status).isEqualTo(SlotStatus.EXPIRED)
    }
}
