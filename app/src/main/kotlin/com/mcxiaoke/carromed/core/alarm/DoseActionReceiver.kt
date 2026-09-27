package com.mcxiaoke.carromed.core.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.domain.service.DoseTrackingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 通知栏快捷操作接收器 (极速直写，无需打开 App)
 * [✅ 确认已吃] → Room 事务打卡 + 扣库存 + 销毁通知 + 取消闹钟
 * [⏰ 推迟30分钟] → 槽位置 SNOOZED + 重排临时闹钟 + 销毁通知
 * [⏭️ 跳过本次] → 写入跳过事实 + 销毁通知 + 取消闹钟
 *
 * 打卡与撤销均以 dose_slots 主键状态为幂等锚点，双击/连击不会重复扣减。
 */
class DoseActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Notifications.ACTION_TAKE &&
            action != Notifications.ACTION_SNOOZE &&
            action != Notifications.ACTION_SKIP
        ) return

        val slotId = intent.getLongExtra(Notifications.EXTRA_SLOT_ID, -1L)
        if (slotId <= 0) return

        val result = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(appContext)
                val tracking = DoseTrackingService(db)
                val slot = db.doseSlotDao().getSlotById(slotId)
                // 幂等守卫：仅待服/推迟中的槽位允许快捷操作，防止双击连击重复扣减
                val isStillOpen = slot != null &&
                    (slot.status == com.mcxiaoke.carromed.core.data.model.SlotStatus.PENDING ||
                        slot.status == com.mcxiaoke.carromed.core.data.model.SlotStatus.SNOOZED)

                when (action) {
                    Notifications.ACTION_TAKE -> {
                        val ok = isStillOpen && tracking.takeDose(
                            slotId = slotId,
                            note = "通知栏快捷打卡"
                        )
                        Notifications.cancelDoseNotification(appContext, slotId)
                        AlarmScheduler.cancel(appContext, slotId)
                        notifyUser(appContext, if (ok) "已记录服药，库存已同步 💊" else "该提醒已处理过")
                    }

                    Notifications.ACTION_SNOOZE -> {
                        val minutes = intent.getIntExtra(Notifications.EXTRA_MINUTES, 30)
                        val ok = isStillOpen && tracking.snoozeDose(slotId, minutes)
                        if (ok) {
                            Notifications.cancelDoseNotification(appContext, slotId)
                            val snoozedSlot = db.doseSlotDao().getSlotById(slotId)
                            val triggerAt = snoozedSlot?.snoozeUntilTs
                                ?: (System.currentTimeMillis() + minutes * 60_000L)
                            AlarmScheduler.schedule(appContext, slotId, triggerAt)
                            notifyUser(appContext, "已推迟 $minutes 分钟，到时再提醒")
                        }
                    }

                    Notifications.ACTION_SKIP -> {
                        val ok = isStillOpen && tracking.skipDose(slotId, reason = "通知栏快捷跳过")
                        Notifications.cancelDoseNotification(appContext, slotId)
                        AlarmScheduler.cancel(appContext, slotId)
                        notifyUser(appContext, if (ok) "已跳过本次，不扣减库存" else "该提醒已处理过")
                    }
                }
            } finally {
                result.finish()
            }
        }
    }

    private fun notifyUser(context: Context, message: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
        }
    }
}
