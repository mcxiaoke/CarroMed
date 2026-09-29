package com.mcxiaoke.carromed.core.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
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
        val kind = AlarmScheduler.Kind.fromCode(intent.data?.lastPathSegment)

        // ⭐ 按**业务键**（medId + date + time）反查槽位，而不是 extras 里的 slotId（M5-1）。
        //
        // 闹钟身份本来就是内容寻址的 `carromed://alarm/{medId}/{date}/{time}/{kind}`，
        // 而 `dose_slots.id` 是**会变**的：备份恢复会用备份里的 id 覆盖当前库，
        // 于是"恢复前排的闹钟"带着旧 id、"恢复后的库"用新 id，两边交叠。
        // 按 slotId 反查就会**取到另一个槽位** ⇒ 给错药发提醒。
        //
        // `slot.id` 只在找到槽位之后用于取记录；extras 里的 slotId 干脆不再信任
        // （`filterEquals` 不看 extras，它本来就只是给人看的）。
        val key = parseAlarmKey(intent.data)
        if (key == null) {
            Log.w("AlarmReceiver", "unparseable alarm uri=${intent.data}")
            return
        }
        Log.i("AlarmReceiver", "dose alarm fired, key=$key kind=$kind")

        val appContext = context.applicationContext
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(appContext)
                val slotId = db.doseSlotDao().findOpenSlotId(
                    medicationId = key.medicationId,
                    scheduledDate = key.date,
                    scheduledTime = key.time
                )
                if (slotId == null) {
                    Log.i("AlarmReceiver", "skip: no open slot for $key (已打卡/已结算/已删除)")
                    return@launch
                }
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

    /**
     * 解析 `carromed://alarm/{medId}/{date}/{time}/{kind}`。
     *
     * **生产代码与测试共用**这一份解析 —— 测试自己再写一遍就守不到影子。
     * 解析失败返回 `null`（宁可不响，也不要响给错的人）。
     */
    data class AlarmKey(val medicationId: Long, val date: String, val time: String)

    fun parseAlarmKey(uri: Uri?): AlarmKey? {
        if (uri == null) return null
        val segments = uri.pathSegments
        // carromed://alarm/a/b/c/d ⇒ pathSegments = [a, b, c, d]（authority 被去掉）
        if (segments.size < 4) return null
        return runCatching {
            AlarmKey(
                medicationId = segments[0].toLong(),
                date = segments[1],
                time = segments[2]
            )
        }.getOrNull()?.takeIf { it.medicationId > 0 }
    }
}
