package com.mcxiaoke.carromed.core.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 精确闹钟调度器 (三档降级链路)
 * 1. Android 12+ 且已授予精确闹钟权限 → setExactAndAllowWhileIdle
 * 2. 未授权精确闹钟 → setAlarmClock (系统闹钟通道，不受 Doze 限制，无需特殊权限)
 * 3. 重启/换包/改时 → 由 BootReceiver 触发 AlarmReconciler 全量对账自愈
 *
 * RequestCode = dose_slots.id (自增主键，天然全局唯一，零碰撞)
 */
object AlarmScheduler {

    const val ACTION_DOSE_ALARM = "com.mcxiaoke.carromed.action.DOSE_ALARM"
    const val EXTRA_SLOT_ID = "slot_id"
    const val EXTRA_IS_ADVANCE = "is_advance"

    /**
     * requestCode 分段：主闹钟 = slotId；提前提醒闹钟 = slotId * 10 + 1。
     * slotId 是全局唯一自增主键，因此两个号段天然不相交，取消互不干扰。
     */
    private fun pendingIntent(context: Context, slotId: Long, advance: Boolean): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(ACTION_DOSE_ALARM)
            .putExtra(EXTRA_SLOT_ID, slotId)
            .putExtra(EXTRA_IS_ADVANCE, advance)
        val requestCode = if (advance) (slotId * 10 + 1).toInt() else slotId.toInt()
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun schedule(
        context: Context,
        slotId: Long,
        triggerAtMillis: Long,
        advance: Boolean = false
    ) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent(context, slotId, advance)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()
        if (canExact) {
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                return
            } catch (_: SecurityException) {
                // 权限被运行时回收，落入下方兜底
            }
        }
        // 第二档: 系统闹钟通道兜底 (部分 ROM 上同样要求精确闹钟权限，需捕获)
        try {
            val info = AlarmManager.AlarmClockInfo(triggerAtMillis, null)
            alarmManager.setAlarmClock(info, pi)
            return
        } catch (_: SecurityException) {
            // 落入第三档
        }
        // 第三档: 非精确闹钟兜底 (Doze 下仍允许唤醒，误差通常 < 15 分钟)
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
    }

    /** 取消该槽位的全部闹钟 (主闹钟 + 提前提醒闹钟) */
    fun cancel(context: Context, slotId: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.cancel(pendingIntent(context, slotId, advance = false))
        alarmManager.cancel(pendingIntent(context, slotId, advance = true))
    }
}
