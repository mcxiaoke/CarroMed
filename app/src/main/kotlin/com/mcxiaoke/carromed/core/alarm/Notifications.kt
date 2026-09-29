package com.mcxiaoke.carromed.core.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.mcxiaoke.carromed.MainActivity
import com.mcxiaoke.carromed.R
import com.mcxiaoke.carromed.core.data.entity.DoseSlotEntity
import com.mcxiaoke.carromed.core.data.model.MedicationOverview
import com.mcxiaoke.carromed.ui.component.Quantity
import com.mcxiaoke.carromed.core.domain.model.Dose
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity

/**
 * 服药提醒通知构建器
 * 高优先级渠道 + Heads-up 浮动横幅 + 通知栏快捷操作 (已吃/推迟/跳过)
 */
object Notifications {

    const val CHANNEL_DOSE_REMINDER = "dose_reminder"

    /**
     * 夜间静音渠道。
     * Android 的渠道重要性 (IMPORTANCE) 一经创建不可修改，因此要静音必须走**独立渠道**，
     * 再在推送时按「是否处于夜间 + 是否重要提醒」选择用哪个渠道。
     * 此前本项目只有一个 HIGH 渠道，导致设置页的「夜间免打扰」开关彻底无效。
     */
    const val CHANNEL_DOSE_REMINDER_SILENT = "dose_reminder_silent"

    // 通知栏快捷操作指令
    const val EXTRA_SLOT_ID = AlarmScheduler.EXTRA_SLOT_ID
    const val EXTRA_MINUTES = "snooze_minutes"
    const val ACTION_TAKE = "com.mcxiaoke.carromed.action.DOSE_TAKE"
    const val ACTION_SNOOZE = "com.mcxiaoke.carromed.action.DOSE_SNOOZE"
    const val ACTION_SKIP = "com.mcxiaoke.carromed.action.DOSE_SKIP"

    // 通知栏 Action 按钮 requestCode 偏移 (slotId * 10 + offset 保证唯一)
    private const val RC_TAKE = 0
    private const val RC_SNOOZE = 1
    private const val RC_SKIP = 2

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        val loud = NotificationChannel(
            CHANNEL_DOSE_REMINDER,
            "服药提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "到点吃药的浮动横幅与通知栏提醒"
            enableVibration(true)
            setShowBadge(true)
        }

        val silent = NotificationChannel(
            CHANNEL_DOSE_REMINDER_SILENT,
            "服药提醒 (夜间静音)",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "夜间 23:00-07:00 的服药提醒，不响铃不震动"
            enableVibration(false)
            setSound(null, null)
            setShowBadge(true)
        }

        nm.createNotificationChannels(listOf(loud, silent))
    }

    /**
     * 通知栏 Action 的 PendingIntent。
     *
     * ## 这里用算术编码是**安全**的，而 `AlarmScheduler` 里同样的写法不安全
     *
     * 差别在 `Intent.filterEquals` 会比较 **action**：
     * - 本方法三个 Action 的 action 互不相同（`DOSE_TAKE` / `DOSE_SNOOZE` / `DOSE_SKIP`），
     *   所以同一槽位的三个按钮天然是三个不同 PendingIntent；
     * - 不同槽位之间靠 `requestCode` 区分，`slotId*10+{0,1,2}` 在 `slotId >= 1` 时无交叠。
     *
     * 而 `AlarmScheduler` 的 main / advance 两个分支 **action 完全相同**（`DOSE_ALARM`），
     * 于是只能靠 requestCode 区分，而 `10N+1` 与 `M` 必然相交 —— 那就是 P0-1。
     *
     * ⚠️ 若将来要合并这里的算术编码，请一并确认 action 是否仍能区分两者。
     */
    private fun actionPendingIntent(
        context: Context,
        slotId: Long,
        action: String,
        requestCode: Int,
        extras: Intent.() -> Unit = {}
    ): PendingIntent {
        val intent = Intent(context, DoseActionReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_SLOT_ID, slotId)
            .apply(extras)
        return PendingIntent.getBroadcast(
            context,
            (slotId * 10 + requestCode).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 弹出某槽位的服药提醒通知 (Heads-up)
     *
     * @param behavior 用户在设置页 / 提醒设置页配置的提醒行为。
     *   推迟按钮的分钟数此前写死 30，现改为跟随配置；
     *   夜间静音通过切换到 LOW 重要性渠道实现。
     */
    fun showDoseNotification(
        context: Context,
        slot: DoseSlotEntity,
        /**
         * 传读模型而不是裸 `MedicationEntity`。
         *
         * 重要提醒标记已随 A2 迁到 `reminder_settings` 表，实体上取不到；
         * 更重要的是**签名本身就是护栏** —— 若这里收实体，调用方必须自己去别处
         * 拼 `isCriticalReminder`，拼错就是"重要药品夜里被静音"这种静默故障。
         */
        overview: MedicationOverview,
        behavior: ReminderSettings.Behavior = ReminderSettings.Behavior(),
        /**
         * 闹钟种类，决定标题与文案（P1-20）。
         *
         * 原先提前提醒和准点提醒的文案**完全一样**，用户看到"该吃药了"却还有 10 分钟，
         * 分不清是提醒早了还是自己在看手机。
         */
        kind: AlarmScheduler.Kind = AlarmScheduler.Kind.MAIN
    ) {
        val med = overview.medication
        ensureChannel(context)

        // doseAmount 是整数毫单位（D-7）。⚠️ 原先的 `doseAmount % 1f == 0f`
        // 判断能编译（Kotlin 允许 Int % Float）却恒为真，会把 1 片显示成「1000 片」。
        val doseText = Quantity.withUnit(Dose(slot.doseAmount).asFloat, med.unit)
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val silent = ReminderSettings.shouldSilence(behavior, overview.isCriticalReminder, hour)
        val channel = if (silent) CHANNEL_DOSE_REMINDER_SILENT else CHANNEL_DOSE_REMINDER

        val advanceMinutes = overview.advanceMinutes
        val body = buildString {
            // ⚠️ `slot.scheduledTime` 是**原始计划时点**，不是推迟目标。
            // 推迟目标是 `snoozeUntilTs`。SNOOZE 闹钟在 `snoozeUntilTs` 那一刻响，
            // 此时"已推迟到 <scheduledTime>"是错的（那正是用户已经错过的时间）。
            when (kind) {
                AlarmScheduler.Kind.ADVANCE ->
                    append("$advanceMinutes 分钟后到 ${slot.scheduledTime}，该服用 $doseText 了")
                AlarmScheduler.Kind.SNOOZE ->
                    append("推迟的时间到了 · 剂量 $doseText")
                AlarmScheduler.Kind.MAIN ->
                    append("计划 ${slot.scheduledTime} · 剂量 $doseText")
            }
            if (med.noticeShort.isNotBlank()) append("\n${med.noticeShort}")
            if (silent) append("\n夜间静音中（可在设置中调整，或将该药设为重要提醒）")
        }

        val contentIntent = PendingIntent.getActivity(
            context,
            slot.id.toInt(),
            Intent(context, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val snooze = behavior.snoozeMinutes.coerceIn(1, 240)

        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(
                when {
                    // 重要提醒优先：它决定的是"响不响"，不是"什么时候提醒"
                    overview.isCriticalReminder -> "重要提醒：${med.name}"
                    kind == AlarmScheduler.Kind.ADVANCE -> "快到时间了：${med.name}"
                    kind == AlarmScheduler.Kind.SNOOZE -> "该吃药了（推迟后）：${med.name}"
                    else -> "该吃药了：${med.name}"
                }
            )
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(if (silent) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(false)
            .addAction(
                0, "✅ 确认已吃",
                actionPendingIntent(context, slot.id, ACTION_TAKE, RC_TAKE)
            )
            .addAction(
                0, "⏰ 推迟 $snooze 分钟",
                actionPendingIntent(context, slot.id, ACTION_SNOOZE, RC_SNOOZE) {
                    putExtra(EXTRA_MINUTES, snooze)
                }
            )
            .addAction(
                0, "⏭️ 跳过本次",
                actionPendingIntent(context, slot.id, ACTION_SKIP, RC_SKIP)
            )
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(slot.id.toInt(), notification)
        }.onFailure { Log.e("Notifications", "notify failed for slot=${slot.id}", it) }
    }

    fun cancelDoseNotification(context: Context, slotId: Long) {
        NotificationManagerCompat.from(context).cancel(slotId.toInt())
    }

    /**
     * 该槽位的提醒通知当前是否还挂在托盘上。
     *
     * ## 为什么补响判据要用它（PLAN-EXPIRE-WINDOW-20260929 §3.3）
     *
     * 托盘通知是「已经发生过的陈述」（撤它的理由见 [AlarmReconciler.cancelNotificationOf]）：
     * 通知还在 = 用户已经被提醒过，对账就不该再补响 —— 否则 AlarmReceiver 响铃后
     * 就地重跑对账，刚响过的槽位 30 秒后再次满足补响条件，每条未确认的服药
     * 都会以 30 秒为周期反复响到补响窗口结束。
     * 通知不在（关机 / 重启 / 被用户清掉）才补响；用户主动滑掉后下一轮对账
     * 会再补一次 —— 漏服提醒需要这份执着，且仍受补响窗口封顶。
     *
     * 查询失败按「不在」处理：漏提醒比多提醒严重（提醒不漏是第一承诺），
     * 不能因为查询失败就把补响整个吞掉。
     */
    fun isDoseNotificationShown(context: Context, slotId: Long): Boolean =
        runCatching {
            NotificationManagerCompat.from(context).activeNotifications.any { it.id == slotId.toInt() }
        }.getOrDefault(false)
}
