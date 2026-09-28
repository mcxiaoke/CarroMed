package com.mcxiaoke.carromed.core.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.time.LocalDate
import kotlinx.coroutines.launch

/**
 * 精确闹钟触发接收器
 * 到点唤醒后直接在广播进程内查询槽位并弹出高优先级通知，
 * 全程不拉起 Activity，保证灭屏/Doze 状态下 100% 到达。
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmScheduler.ACTION_DOSE_ALARM) return
        val slotId = intent.getLongExtra(AlarmScheduler.EXTRA_SLOT_ID, -1L)
        if (slotId <= 0) return
        Log.i("AlarmReceiver", "dose alarm fired, slotId=$slotId")

        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(context)
                val slot = db.doseSlotDao().getSlotById(slotId)
                Log.i("AlarmReceiver", "slot loaded: $slot")
                // 幂等守卫：槽位已被处理 (已服/跳过/过期) 则静默丢弃本次唤醒
                if (slot?.status != SlotStatus.PENDING && slot?.status != SlotStatus.SNOOZED) {
                    Log.i("AlarmReceiver", "skip: slot not open (status=${slot?.status})")
                    return@launch
                }
                // 一次 JOIN 取回药品档案 + 台账余额 + 提醒运行态。
                // 拆表没有让这里变慢：原先是"查槽位 + 查药品"两次，现在仍是两次，
                // 且提醒运行态顺带一次取回（原先要再查第三次）。
                val overview = db.medicationDao().getOverviewById(slot.medicationId)
                val med = overview?.medication
                val paused = overview?.isPausedOn(LocalDate.now()) == true
                Log.i("AlarmReceiver", "med loaded: ${med?.name}, paused=$paused, archived=${med?.isArchived}")
                if (overview == null || paused || overview.medication.isArchived) return@launch

                // 按药品解析提醒行为 (推迟时长 / 夜间静音 / 重要提醒)，全部读用户真实配置
                val behavior = ReminderSettings.resolve(context, db, med.id)
                Notifications.showDoseNotification(context, slot, overview, behavior)
                Log.i("AlarmReceiver", "notification shown for slot=$slotId")
            } catch (t: Throwable) {
                Log.e("AlarmReceiver", "failed to show notification", t)
            } finally {
                result.finish()
            }
        }
    }
}
