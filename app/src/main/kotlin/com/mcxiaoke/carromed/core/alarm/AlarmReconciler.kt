package com.mcxiaoke.carromed.core.alarm

import android.content.Context
import android.util.Log
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import com.mcxiaoke.carromed.core.domain.service.DoseTrackingService
import java.time.LocalDate

/**
 * 闹钟全量对账器 (Reconciler)
 * 在 App 启动、开机、换包、系统改时、备份恢复后调用，保证：
 * 1. 未来 7 天槽位已按当前策略幂等补齐
 * 2. 所有活跃药品的待服/推迟槽位均注册了精确闹钟
 * 3. 已停药/暂停/过期槽位的闹钟全部取消，杜绝幽灵唤醒
 * 4. 超过计划时间 2 小时仍 PENDING 的槽位标记 EXPIRED (不冤枉判漏服)
 */
object AlarmReconciler {

    /** 槽位过期判定窗口: 计划时间过后 2 小时 */
    private const val EXPIRE_WINDOW_MS = 2 * 60 * 60 * 1000L

    suspend fun rescheduleAll(context: Context, db: AppDatabase) {
        val now = System.currentTimeMillis()
        Log.i("AlarmReconciler", "rescheduleAll start, now=$now")

        // 1. 过期槽位结算: PENDING 且计划时间已过 2 小时 → EXPIRED
        //    严格限定为「已排期」的历史槽位：用户此刻新建的药品若时点设为 08:30 而当前已 10:36，
        //    那是一条排在过去的槽位，同样应结算为逾期；反之未来槽位永不误判。
        val staleSlots = db.doseSlotDao().getStalePendingSlots(now - EXPIRE_WINDOW_MS)
        staleSlots.forEach { stale ->
            db.doseSlotDao().updateStatus(stale.id, SlotStatus.EXPIRED, null)
            AlarmScheduler.cancel(context, stale.id)
        }
        if (staleSlots.isNotEmpty()) {
            Log.i("AlarmReconciler", "expired ${staleSlots.size} overdue slots")
        }

        // 2. 活跃药品（在服且截至今天未暂停）未来 7 天排班幂等补齐
        //
        // ⚠️ 暂停判断必须走 `MedicationOverview.isPausedOn(today)`（它转给
        // `ReminderSettingsEntity.isPausedOn`），**不能**写成 `pausedUntil != null`：
        // 暂停到期后那种写法会让闹钟静默不再排 —— 用户以为有提醒、实际没有。
        val today = LocalDate.now()
        val activeMeds = db.medicationDao().getActiveOverviews()
            .filter { !it.isPausedOn(today) }
        val tracking = DoseTrackingService(db)
        for (med in activeMeds) {
            tracking.reconcileSchedule(
                medicationId = med.id,
                fromDate = today,
                toDate = today.plusDays(7)
            )
        }

        // 3. 全量闹钟对账: 该注册的注册，该取消的取消
        val activeIds = activeMeds.map { it.id }.toSet()
        val advanceByMed = activeMeds.associate { it.id to it.advanceMinutes }
        val openSlots = db.doseSlotDao().getOpenSlots() // PENDING + SNOOZED
        var scheduled = 0
        var cancelled = 0
        for (slot in openSlots) {
            val triggerAt = if (slot.status == SlotStatus.SNOOZED) {
                slot.snoozeUntilTs ?: slot.scheduledTs
            } else {
                slot.scheduledTs
            }
            if (slot.medicationId in activeIds && triggerAt > now) {
                // 提前提醒：药品配置了 advance_minutes 时，在计划时间前 N 分钟额外唤醒一次。
                // 使用独立 requestCode 槽位 (advance=true)，取消主闹钟不会误伤提前闹钟。
                val advance = advanceByMed[slot.medicationId] ?: 0
                if (advance > 0) {
                    val advanceAt = slot.scheduledTs - advance * 60_000L
                    if (advanceAt > now) {
                        runCatching { AlarmScheduler.schedule(context, slot.id, advanceAt, advance = true) }
                            .onFailure { Log.e("AlarmReconciler", "advance schedule failed slot=${slot.id}", it) }
                    }
                }
                runCatching { AlarmScheduler.schedule(context, slot.id, triggerAt) }
                    .onFailure { Log.e("AlarmReconciler", "schedule failed slot=${slot.id}", it) }
                scheduled++
            } else {
                runCatching { AlarmScheduler.cancel(context, slot.id) }
                    .onFailure { Log.e("AlarmReconciler", "cancel failed slot=${slot.id}", it) }
                cancelled++
            }
        }
        Log.i("AlarmReconciler", "done: activeMeds=${activeIds.size}, openSlots=${openSlots.size}, scheduled=$scheduled, cancelled=$cancelled")
    }
}
