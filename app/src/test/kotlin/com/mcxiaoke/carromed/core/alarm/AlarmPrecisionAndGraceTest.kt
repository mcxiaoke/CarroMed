package com.mcxiaoke.carromed.core.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
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
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
        assertThat(context.getString(AlarmScheduler.Precision.INEXACT.labelRes)).contains("延迟")
        assertThat(context.getString(AlarmScheduler.Precision.EXACT.labelRes)).contains("到点")
        // 3-2 收口：降级档位不得再声称"到点必响"。
        // ALARM_CLOCK 与 INEXACT 都是降级，写"必响"就是自检页对用户撒谎。
        assertThat(context.getString(AlarmScheduler.Precision.INEXACT.labelRes)).doesNotContain("到点必响")
        assertThat(context.getString(AlarmScheduler.Precision.ALARM_CLOCK.labelRes)).doesNotContain("到点必响")
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
        //
        // 3-2：必须是 INEXACT 而**不是** ALARM_CLOCK。`setAlarmClock()` 与
        // `setExact*()` 同受精确闹钟权限约束，权限被撤时同样抛 SecurityException
        // ⇒ 实际生效的就是 +1h 窗口的 `setAndAllowWhileIdle`。
        // 报 ALARM_CLOCK（文案"到点必响"）等于自检页继续撒谎。
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertThat(AlarmScheduler.currentPrecision(alarmManager()))
            .isEqualTo(AlarmScheduler.Precision.INEXACT)
    }

    // ==================== M1-7 补响与「当日结束」结算 ====================

    /** 当地当日 0 点的纪元毫秒 —— 结算线（见 AlarmReconciler 第 1 步） */
    private fun startOfTodayMs(): Long =
        today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /**
     * 必落在补响窗口内、且必不跨结算线的时点。
     *
     * 直接取 `now - minutesAgo` 会在 0 点后 [minutesAgo] 分钟内运行时落到**昨天**
     * （跨过结算线 ⇒ 被结算而不是补响），测试就随时钟变色 —— 正是 AGENTS §3
     * "测试在下午全绿、早上全红"的 fixture 陷阱。
     * 取 `max(now - minutesAgo, 今日 0 点)`：深夜运行时退到 0 点整 ——
     * 0 点整属于今天（严格小于才结算），且此时必然仍在补响窗口内
     * （该分支只在 `now < 今日0点 + minutesAgo` 时走到，此时 `now - 2h < 今日0点`）。
     */
    private fun catchupWindowTs(minutesAgo: Long): Long =
        maxOf(System.currentTimeMillis() - minutesAgo * 60_000L, startOfTodayMs())

    /**
     * 造一条"闹钟丢了"的开放槽位。
     *
     * ## 为什么日期取**昨天**
     *
     * `rescheduleAll` 的重排窗口是 `[今天, 今天+HORIZON_DAYS]`。若把造出来的槽位
     * 挂在**今天**，它不在投影里（时刻是编的），`reconcileSchedule` 的"删"分支
     * 会在过期结算**之后**把它当"已不存在的时刻"删掉 ——
     * 于是无论结算逻辑对不对，`getSlotById` 都返回 null，测试报 NPE，
     * 报错信息与真实原因完全无关。
     *
     * 挂昨天就落在重排窗口之外，不会被碰。而"昨晚的闹钟因为关机没响、
     * 用户早上开机"恰恰就是 M1-7 要处理的**真实场景**。
     *
     * `scheduledTs` 是结算判定与闹钟注册的唯一时间依据，所以直接给绝对时间戳。
     */
    private suspend fun seedMissedSlot(
        template: DoseSlotEntity,
        timeOfDay: String,
        scheduledTs: Long
    ): Long = db.doseSlotDao().insert(
        template.copy(
            id = 0,
            status = SlotStatus.PENDING,
            scheduledDate = today.minusDays(1).toString(),
            scheduledTime = timeOfDay,
            scheduledTs = scheduledTs
        )
    )

    @Test
    fun `补响窗口内的错过槽位既不被结算 也确实被补响`() = runBlocking {
        AlarmReconciler.rescheduleAll(context, db)
        val template = db.doseSlotDao().getOpenSlots().first()
        // 半小时前（深夜运行则退到今日 0 点）—— 必落在补响窗口内
        val id = seedMissedSlot(template, "03:17", catchupWindowTs(minutesAgo = 30))
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

    @Test
    fun `未通知过的槽位托盘有通知不补响 托盘无通知才补一次`() = runBlocking {
        // 这条守的是"补响只响一次"的核心：
        // 场景 1：如果托盘里还挂着通知（未通知过但托盘已有），对账不补响；
        // 场景 2：真正漏掉（未通知过且托盘无通知，如关机后开机），下一轮对账补响一次。
        AlarmReconciler.rescheduleAll(context, db)
        val template = db.doseSlotDao().getOpenSlots().first()
        val id = seedMissedSlot(template, "03:17", catchupWindowTs(minutesAgo = 30))
        val slot = db.doseSlotDao().getSlotById(id)!!

        // 按生产约定（Notifications 的通知 id 就是 slotId）发一条，
        // 替身"托盘里已有通知"的世界状态。
        Notifications.ensureChannel(context)
        NotificationManagerCompat.from(context).notify(
            Notifications.notificationIdOf(id),
            NotificationCompat.Builder(context, Notifications.CHANNEL_DOSE_REMINDER_V3)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
        )

        AlarmReconciler.rescheduleAll(context, db)
        // 通知在 = 已经有通知挂着 ⇒ 不补响
        assertThat(shadowAlarms()).doesNotContain(pendingMainOf(slot))

        // 重启或系统清空托盘（且未记录过 lastMainNotifiedTs）⇒ 补响一次
        NotificationManagerCompat.from(context).cancel(Notifications.notificationIdOf(id))
        AlarmReconciler.rescheduleAll(context, db)
        assertThat(shadowAlarms()).contains(pendingMainOf(slot))
    }

    @Test
    fun `已成功通知过的槽位划掉通知不再补响 消除15分钟骚扰`() = runBlocking {
        // P1-1 守门测试：
        // 旧实现把「托盘无通知」等同于「未曾提醒过」，导致用户主动划掉通知后，
        // 周期对账 Worker 每 15 分钟再次强行补响一次。
        // 新实现：只要 lastMainNotifiedTs != null，即使托盘无通知，也绝不再次补响！
        AlarmReconciler.rescheduleAll(context, db)
        val template = db.doseSlotDao().getOpenSlots().first()
        val id = seedMissedSlot(template, "03:18", catchupWindowTs(minutesAgo = 30))
        // 模拟 AlarmReceiver 已经成功弹出通知并持久化记录了时间戳
        db.doseSlotDao().updateLastMainNotifiedTs(id, System.currentTimeMillis() - 60_000L)
        val slot = db.doseSlotDao().getSlotById(id)!!
        assertThat(slot.lastMainNotifiedTs).isNotNull()

        // 托盘通知被用户主动划掉（托盘上无通知）
        NotificationManagerCompat.from(context).cancel(Notifications.notificationIdOf(id))

        // 触发对账（例如 15 分钟后的周期 Worker）
        AlarmReconciler.rescheduleAll(context, db)

        // 核心断言：绝不给此槽位补响闹钟！彻底终结 15 分钟骚扰
        assertThat(shadowAlarms()).doesNotContain(pendingMainOf(slot))
    }

    @Test
    fun `跨过当日0点仍未处理的槽位被结算为漏服`() = runBlocking {
        // 「当日结束」规则的定案侧：昨晚 23:00（今日 0 点前 1 小时）没处理的槽位，
        // 0 点后的第一轮对账就结算 —— 当天的欠账跨天不再豁免。
        AlarmReconciler.rescheduleAll(context, db)
        val template = db.doseSlotDao().getOpenSlots().first()
        val id = seedMissedSlot(template, "23:00", startOfTodayMs() - 3600_000L)
        assertThat(id).isGreaterThan(0L)

        AlarmReconciler.rescheduleAll(context, db)

        val after = db.doseSlotDao().getSlotById(id)
        assertThat(after).isNotNull()
        assertThat(after!!.status).isEqualTo(SlotStatus.EXPIRED)
    }

    @Test
    fun `今天的槽位过久未处理也不结算 静默待办留在清单上且不再响`() = runBlocking {
        // 「当日结束」规则的豁免侧（本次改造的主场景）：
        // 时点已过去 2.5 小时的今日槽位 —— 旧规则（计划时间 + 2 小时）会把它
        // 结算成 EXPIRED、挂上「已逾期」徽标并进依从率分母；新规则下它原样
        // 留在清单上（PENDING），且因落在补响窗口（2 小时）之外而不再响。
        //
        // 静默待办区间 [今日 0 点, now-2h) 在 0 点后 2 小时内物理上不存在，
        // 深夜运行时跳过（见 AGENTS §3：换时点不是修法，诚实的 Assume 才是）。
        Assume.assumeTrue(
            "静默待办区间在 0 点后 2 小时内不存在",
            System.currentTimeMillis() - startOfTodayMs() > 121 * 60_000L
        )

        // 走**真实投影**而不是编造行：今日槽位必须仍被投影命中，
        // 否则 reconcileSchedule 的"删"分支会把它当"已不存在的时刻"删掉。
        // 与"用户此刻新建药品、时点设在过去"是同一条生产路径。
        val pastTime = LocalTime.now().minusMinutes(150)
            .format(DateTimeFormatter.ofPattern("HH:mm"))
        val medId = db.medicationDao().insert(MedicationEntity(name = "晨间药"))
        db.schedulePolicyDao().savePolicyWithTimes(
            SchedulePolicyEntity(
                medicationId = medId,
                policyType = PolicyType.DAILY,
                startDate = today.toString()
            ),
            listOf(PolicyTimeEntity(policyId = 0, timeOfDay = pastTime, doseAmount = 1000))
        )
        db.reminderSettingsDao().ensureDefaults(medId)

        // ⚠️ 必须跑**两轮**：同一轮 rescheduleAll 里结算（第 1 步）先于投影（第 2 步），
        // 刚由投影造出来的槽位轮不到结算 —— 只跑一轮的话，把结算线改回旧值也照样绿。
        // 生产里这条槽位是在**更早**的对账中物化的，下一轮（≤15 分钟后）就会被结算。
        AlarmReconciler.rescheduleAll(context, db)
        AlarmReconciler.rescheduleAll(context, db)

        val slot = db.doseSlotDao().getSlotsForDate(today.toString())
            .first { it.medicationId == medId }
        assertThat(slot.status).isEqualTo(SlotStatus.PENDING)
        // 静默待办：本条不再有任何闹钟
        assertThat(shadowAlarms()).doesNotContain(pendingMainOf(slot))
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
}
