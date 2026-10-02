package com.mcxiaoke.carromed.core.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import com.mcxiaoke.carromed.core.domain.AppLog
import com.mcxiaoke.carromed.core.domain.engine.SlotProjectionEngine
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
 * 3. **触发后续期**（P0-2）：响铃弹通知后，把续期交给 [ReconcileWorker.enqueueOneShot]
 *    立即补一轮 —— **不在广播窗口内联跑全量对账**（N3）：goAsync 的窗口就是广播超时，
 *    而对账耗时随数据线性放大。这是"装好 App 后不碰它也不会漏提醒"的第一道即时保险；
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
            AppLog.w("AlarmReceiver", "unparseable alarm uri=${intent.data}")
            return
        }
        AppLog.i("AlarmReceiver", "dose alarm fired, key=$key kind=$kind")

        val appContext = context.applicationContext
        // 显式持有 10 秒超时 WakeLock (P1-2 / P0-4)：
        // 避免在灭屏且处于 Deep Doze 时，协程切到 Dispatchers.IO 被系统 cgroup 冻结挂起，
        // 直到按亮电源键才弹出的"灭屏不响"痛点。
        val pm = context.getSystemService(PowerManager::class.java)
        val wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "carromed:alarm_receiver")
        wakeLock?.acquire(10_000L)

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
                    AppLog.i("AlarmReceiver", "skip: no open slot for $key (已打卡/已结算/已删除)")
                    return@launch
                }
                val slot = db.doseSlotDao().getSlotById(slotId)
                AppLog.i("AlarmReceiver", "slot loaded: $slot")
                if (slot == null || (slot.status != SlotStatus.PENDING && slot.status != SlotStatus.SNOOZED)) {
                    AppLog.i("AlarmReceiver", "skip: slot not open (status=${slot?.status})")
                    return@launch
                }
                // 一次 JOIN 取回药品档案 + 台账余额 + 提醒运行态。
                // 拆表没有让这里变慢：原先是"查槽位 + 查药品"两次，现在仍是两次，
                // 且提醒运行态顺带一次取回（原先要再查第三次）。
                val overview = db.medicationDao().getOverviewById(slot.medicationId)
                val med = overview?.medication
                val slotDate = runCatching {
                    LocalDate.parse(slot.scheduledDate, SlotProjectionEngine.DATE_FORMATTER)
                }.getOrNull() ?: LocalDate.now()
                val paused = overview?.isPausedOn(slotDate) == true
                AppLog.i("AlarmReceiver", "med loaded: ${med?.name}, paused=$paused, archived=${med?.isArchived}")
                if (overview == null || paused || overview.medication.isArchived) return@launch

                // 按药品解析提醒行为 (推迟时长 / 夜间静音 / 重要提醒)，全部读用户真实配置
                val behavior = ReminderSettings.resolve(appContext, db, med.id)
                val shown = Notifications.showDoseNotification(
                    context = appContext,
                    slot = slot,
                    overview = overview,
                    behavior = behavior,
                    kind = kind
                )
                // ⚠️ 按真实投递结果记日志（orsbf P0-5）：通知权限被拒 / 渠道被关时
                // notify() 是静默空操作，旧实现这里恒记 "notification shown" ——
                // 事后排查日志会得出"通知已发出"的错误结论。
                if (!shown) {
                    AppLog.e(
                        "AlarmReceiver",
                        "notification NOT delivered for slot=$slotId " +
                            "(通知权限或渠道被关闭，去系统设置开启后才能收到提醒)"
                    )
                    // 通知不可达时**不入队续期对账**：续期的补响判据是
                    // 「托盘里没有这条通知」，恒为 false，会以 30 秒为周期
                    // 反复唤醒设备直到补响窗口结束（orsbf P0-5 的风暴根因）。
                    // 修复出口（开权限）之前，反复对账只是空转耗电。
                    return@launch
                }
                AppLog.i("AlarmReceiver", "notification shown for slot=$slotId kind=$kind")

                // 持久化记录本次提醒成功弹出（P1-1）与重复提醒调度
                val nowTs = System.currentTimeMillis()
                when (kind) {
                    AlarmScheduler.Kind.MAIN, AlarmScheduler.Kind.REPEAT -> {
                        val newCount = slot.reminderCount + 1
                        db.doseSlotDao().updateReminderCountAndLastNotified(slot.id, newCount, nowTs)
                        // 调度下一次重复提醒（如果开启且未达到上限）
                        if (behavior.repeatReminderEnabled && newCount < behavior.repeatReminderMaxCount) {
                            val nextRepeatAt = nowTs + behavior.repeatReminderIntervalMinutes * 60_000L
                            runCatching {
                                AlarmScheduler.schedule(
                                    appContext,
                                    slot,
                                    nextRepeatAt,
                                    AlarmScheduler.Kind.REPEAT
                                )
                                AppLog.i("AlarmReceiver", "scheduled repeat reminder #$newCount for slot=${slot.id} at $nextRepeatAt (interval=${behavior.repeatReminderIntervalMinutes}m)")
                            }.onFailure {
                                AppLog.e("AlarmReceiver", "schedule repeat reminder failed for slot=${slot.id}", it)
                            }
                        } else {
                            AppLog.i("AlarmReceiver", "repeat reminder complete/disabled: count=$newCount, max=${behavior.repeatReminderMaxCount}, enabled=${behavior.repeatReminderEnabled}")
                        }
                    }
                    AlarmScheduler.Kind.SNOOZE -> {
                        db.doseSlotDao().updateLastSnoozeNotifiedTs(slot.id, nowTs)
                    }
                    AlarmScheduler.Kind.ADVANCE -> {
                        // ADVANCE 是提前预告，不能算作已完成准点主提醒，保持 lastMainNotifiedTs 不变
                    }
                }

                // 后续期（P0-2）：本次响铃就是"用户最可能还在用手机"的时刻，此刻续期最划算，
                // 也让唤醒链在用户完全不打开 App 的情况下自维持。
                // 但续期本体**不在这里跑**（N3）：goAsync 的窗口是广播超时（前台 10s），
                // 全量对账随 药品数 × 时点数 × 14 天视野线性放大，内联迟早撞线。
                // 交给 Worker 的一次性任务：进程存活由系统托管，失败还能走退避重试。
                //
                // ⚠️ 必须先弹通知、后入队对账，顺序不能反：对账的补响判据是
                // 「托盘里没有这条槽位的通知」（Notifications.isDoseNotificationShown）。
                // 若先对账，刚到点的槽位会被当成"从没提醒过"，30 秒后再补响一次，
                // 并以 30 秒为周期循环到补响窗口结束。
                runCatching { ReconcileWorker.enqueueOneShot(appContext) }
                    .onFailure { AppLog.e("AlarmReceiver", "enqueue oneshot reconcile failed", it) }
            } catch (t: Throwable) {
                AppLog.e("AlarmReceiver", "failed to show notification", t)
            } finally {
                try {
                    if (wakeLock?.isHeld == true) {
                        wakeLock.release()
                    }
                } catch (t: Throwable) {
                    AppLog.w("AlarmReceiver", "wakeLock release failed", t)
                } finally {
                    result.finish()
                }
            }
        }
    }

    /**
     * 解析 `carromed://alarm/{medId}/{date}/{time}/{kind}`。
     *
     * **生产代码与测试共用**这一份解析 —— 测试自己再写一遍就守不到影子。
     * 解析失败返回 `null`（宁可不响，也不要响给错的人）。
     *
     * 放在 companion 里：`DoseActionReceiver` 的通知 Action 用同一套
     * "内容寻址 + 反查开放槽位"的哲学（`carromed://action/...`，同样的
     * 4 段路径形状），共用一份解析器才不会两边漂移。
     */
    data class AlarmKey(val medicationId: Long, val date: String, val time: String)

    companion object {
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
}
