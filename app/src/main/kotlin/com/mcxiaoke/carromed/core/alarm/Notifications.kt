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
        val channel = NotificationChannel(
            CHANNEL_DOSE_REMINDER,
            "服药提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "到点吃药的浮动横幅与通知栏提醒"
            enableVibration(true)
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
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
     */
    fun showDoseNotification(context: Context, slot: DoseSlotEntity, med: MedicationEntity) {
        ensureChannel(context)

        val doseText = if (slot.doseAmount % 1f == 0f) {
            "${slot.doseAmount.toInt()} ${med.unit}"
        } else {
            "${slot.doseAmount} ${med.unit}"
        }
        val body = buildString {
            append("计划 ${slot.scheduledTime} · 剂量 $doseText")
            if (med.noticeShort.isNotBlank()) append("\n${med.noticeShort}")
        }

        val contentIntent = PendingIntent.getActivity(
            context,
            slot.id.toInt(),
            Intent(context, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_DOSE_REMINDER)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("该吃药了：${med.name}")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(false)
            .addAction(
                0, "✅ 确认已吃",
                actionPendingIntent(context, slot.id, ACTION_TAKE, RC_TAKE)
            )
            .addAction(
                0, "⏰ 推迟30分钟",
                actionPendingIntent(context, slot.id, ACTION_SNOOZE, RC_SNOOZE) {
                    putExtra(EXTRA_MINUTES, 30)
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
