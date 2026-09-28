package com.mcxiaoke.carromed.core.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 精确闹钟触发接收器
 *
 * 到点唤醒后直接在广播进程内查询槽位并弹出高优先级通知，
 * 全程不拉起 Activity，保证灭屏/Doze 状态下到达。
 *
 * ## 三件事
 *
 * 1. **幂等守卫**：槽位已被处理（已服/跳过/过期）则静默丢弃本次唤醒。
 *    内容寻址后同一槽位同一时刻只有一个 PendingIntent，但系统仍可能重投
 *    （例如进程被杀后重放），所以这道守卫必须留。
 * 2. **按 `kind` 区分文案**（P1-20）：提前提醒说的是「快到时间了」，
 *    准点提醒说的是「该吃药了」，原先两者文案完全一样，用户分不清。
 * 3. **触发后续期**（P0-2）：响铃后就地续期 + 重排，让闹钟视野自维持。
 *    这是"装好 App 后不碰它也不会漏提醒"的第一道即时保险；
 *    第二道是 14 天窗口，第三道是 A6 的周期对账。
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmScheduler.ACTION_DOSE_ALARM) return
        val slotId = intent.getLongExtra(AlarmScheduler.EXTRA_SLOT_ID, -1L)
        val kind = AlarmScheduler.Kind.fromCode(intent.data?.lastPathSegment)
        if (slotId <= 0) {
            Log.w("AlarmReceiver", "missing slotId, uri=${intent.data}")
            return
        }
        Log.i("AlarmReceiver", "dose alarm fired, slotId=$slotId kind=$kind")

        val appContext = context.applicationContext
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(appContext)
                val slot = db.doseSlotDao().getSlotById(slotId)
                Log.i("AlarmReceiver", "slot loaded: $slot")
                if (slot == null || (slot.status != SlotStatus.PENDING && slot.status != SlotStatus.SNOOZED)) {
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
                val behavior = ReminderSettings.resolve(appContext, db, med.id)
                Notifications.showDoseNotification(
                    context = appContext,
                    slot = slot,
                    overview = overview,
                    behavior = behavior,
                    kind = kind
                )
                Log.i("AlarmReceiver", "notification shown for slot=$slotId")

                // 后续期：本次响铃就是"用户最可能还在用手机"的时刻，此刻续期最划算，
                // 也让唤醒链在用户完全不打开 App 的情况下自维持。
                runCatching { AlarmReconciler.rescheduleAll(appContext, db) }
                    .onFailure { Log.e("AlarmReceiver", "reschedule after fire failed", it) }
            } catch (t: Throwable) {
                Log.e("AlarmReceiver", "failed to show notification", t)
            } finally {
                result.finish()
            }
        }
    }
}
