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
        med: MedicationEntity,
        behavior: ReminderSettings.Behavior = ReminderSettings.Behavior()
    ) {
        ensureChannel(context)

        val doseText = if (slot.doseAmount % 1f == 0f) {
            "${slot.doseAmount.toInt()} ${med.unit}"
        } else {
            "${slot.doseAmount} ${med.unit}"
        }
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val silent = ReminderSettings.shouldSilence(behavior, med.isCriticalReminder, hour)
        val channel = if (silent) CHANNEL_DOSE_REMINDER_SILENT else CHANNEL_DOSE_REMINDER

        val body = buildString {
            append("计划 ${slot.scheduledTime} · 剂量 $doseText")
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
                if (med.isCriticalReminder) "重要提醒：${med.name}" else "该吃药了：${med.name}"
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
}
