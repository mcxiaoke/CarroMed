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
        db.doseSlotDao().getStalePendingSlots(now - EXPIRE_WINDOW_MS).forEach { stale ->
            db.doseSlotDao().updateStatus(stale.id, SlotStatus.EXPIRED, null)
        }

        // 2. 活跃药品 (在服且未暂停) 未来 7 天排班幂等补齐
        val activeMeds = db.medicationDao().getActiveMedications()
            .filter { !it.isPaused && !it.isArchived }
        val tracking = DoseTrackingService(db)
        for (med in activeMeds) {
            tracking.reconcileSchedule(
                medicationId = med.id,
                fromDate = LocalDate.now(),
                toDate = LocalDate.now().plusDays(7)
            )
        }

        // 3. 全量闹钟对账: 该注册的注册，该取消的取消
        val activeIds = activeMeds.map { it.id }.toSet()
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
                runCatching { AlarmScheduler.schedule(context, slot.id, triggerAt) }
                    .onFailure { Log.e("AlarmReconciler", "schedule failed slot=${slot.id}", it) }
                scheduled++
            } else {
                runCatching { AlarmScheduler.cancel(context, slot.id) }
                cancelled++
            }
        }
        Log.i("AlarmReconciler", "done: activeMeds=${activeIds.size}, openSlots=${openSlots.size}, scheduled=$scheduled, cancelled=$cancelled")
    }
}
