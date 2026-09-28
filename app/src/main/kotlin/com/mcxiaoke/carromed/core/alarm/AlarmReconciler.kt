package com.mcxiaoke.carromed.core.alarm

import android.content.Context
import android.util.Log
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.DoseSlotEntity
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import com.mcxiaoke.carromed.core.domain.service.DoseTrackingService
import java.time.LocalDate

/**
 * 闹钟全量对账器 (Reconciler)
 *
 * 在 App 启动、开机、换包、系统改时、备份恢复、以及每个闹钟触发后调用，保证：
 * 1. 未来 [HORIZON_DAYS] 天槽位已按当前策略幂等补齐
 * 2. 所有活跃药品的待服/推迟槽位均注册了精确闹钟
 * 3. 已停药/暂停/过期槽位的闹钟全部取消，杜绝幽灵唤醒
 * 4. 超过计划时间 2 小时仍 PENDING 的槽位标记 EXPIRED（不冤枉判漏服）
 *
 * ## 窗口为什么是 14 天而不是 7 天（P0-2）
 *
 * 旧实现只排未来 7 天，且**没有自续期** —— 唯一的续期途径是"用户某天打开 App"。
 * 后果是：装好 App 后不碰它，第 8 天的提醒全部静默消失，用户毫无察觉。
 *
 * 这条缺陷上一轮代码审查没能发现，原因是结构性的：
 * `FINAL-PRODUCT:159` 的验收标准恰好是「连续 **7 天**零漏提醒」，
 * **验收时长等于机制边界**，于是问题在测试期内不可能暴露。
 * 验收标准已因此改为 21 天（见 `docs/CODE-REVIEW-20260927-sbf.md` P0-2 末段）。
 *
 * 现在有三层保险，任何一层单独失效都不会漏提醒：
 * 1. **14 天窗口**（本类的 `HORIZON_DAYS`）
 * 2. **触发后续期**：[AlarmReceiver] 每次响铃后就地续期，唤醒链因此自维持
 * 3. **周期对账兜底**：`ReconcileWorker`（A6）周期性地重跑一次本方法
 *
 * ## 对账的顺序：先快照、后重排、再按快照清理孤儿
 *
 * `reconcileSchedule` 会删掉不再被投影命中的槽位行，但领域层不知道 `Context`，
 * 删掉的行对应的闹钟就成���孤儿。旧实现完全没处理这件事（P1-5 闹钟泄漏的另一半）。
 * 现在：
 * 1. 先对所有开放槽位拍快照（含 medId / date / time，足以定位闹钟身份）
 * 2. 跑重排
 * 3. 快照里已不在库中的、或已不该排的 ⇒ **取消其全部种类闹钟**
 * 4. 库中仍开放且活跃的 ⇒ 注册闹钟（同一 Uri 重复注册是"替换"，不会堆积）
 */
object AlarmReconciler {

    private const val TAG = "AlarmReconciler"

    /** 槽位过期判定窗口：计划时间过后 2 小时 */
    private const val EXPIRE_WINDOW_MS = 2 * 60 * 60 * 1000L

    /**
     * 闹钟视野天数。
     *
     * 7 → 14 天的意义不只是"多排一周"：它把"用户多久没打开 App"与"会漏几天提醒"解耦。
     * 14 天意味着**一个月不开 App 也只会漏 1~2 天**（配合触发后续期几乎为零）。
     */
    const val HORIZON_DAYS = 14L

    /** 闹钟身份快照：足以定位一个 PendingIntent，且不依赖会变的 slot.id */
    private data class AlarmIdentity(
        val slotId: Long,
        val medicationId: Long,
        val date: String,
        val time: String
    ) {
        fun cancelAll(context: Context) =
            AlarmScheduler.cancelAll(context, medicationId, date, time, slotId)
    }

    private fun DoseSlotEntity.identity() = AlarmIdentity(id, medicationId, scheduledDate, scheduledTime)

    suspend fun rescheduleAll(context: Context, db: AppDatabase) {
        val now = System.currentTimeMillis()
        Log.i(TAG, "rescheduleAll start, now=$now")

        // 0. 拍快照：在重排之前。孤儿闹钟只能靠这份快照找回来。
        val snapshot = db.doseSlotDao().getOpenSlots().map { it.identity() }.toSet()

        // 1. 过期槽位结算: PENDING 且计划时间已过 2 小时 → EXPIRED
        //    严格限定为「已排期」的历史槽位：用户此刻新建的药品若时点设为 08:30 而当前已 10:36，
        //    那是一条排在过去的槽位，同样应结算为逾期；反之未来槽位永不误判。
        val staleSlots = db.doseSlotDao().getStalePendingSlots(now - EXPIRE_WINDOW_MS)
        staleSlots.forEach { stale ->
            db.doseSlotDao().updateStatus(stale.id, SlotStatus.EXPIRED, null)
            // 结算即不再需要闹钟；三种种类一次清干净
            stale.identity().cancelAll(context)
        }
        if (staleSlots.isNotEmpty()) {
            Log.i(TAG, "expired ${staleSlots.size} overdue slots")
        }

        // 2. 活跃药品（在服且截至今天未暂停）未来 HORIZON_DAYS 天排班幂等补齐
        //
        // ⚠️ 暂停判断必须走 `MedicationOverview.isPausedOn(today)`（它转给
        // `ReminderSettingsEntity.isPausedOn`），**不能**写成 `pausedUntil != null`：
        // 暂停到期后那种写法会让闹钟静默不再排 —— 用户以为有提醒、实际没有。
        val today = LocalDate.now()
        val activeMeds = db.medicationDao().getActiveOverviews()
            .filter { !it.isPausedOn(today) }
        val tracking = DoseTrackingService(db)
        for (med in activeMeds) {
            runCatching {
                tracking.reconcileSchedule(
                    medicationId = med.id,
                    fromDate = today,
                    toDate = today.plusDays(HORIZON_DAYS)
                )
            }.onFailure { Log.e(TAG, "reconcileSchedule failed med=${med.id}", it) }
        }

        // 3. 清理孤儿：快照里已不在库中（被重排删掉）或已不该排的槽位
        val stillOpen = db.doseSlotDao().getOpenSlots().associateBy { it.id }
        val activeIds = activeMeds.map { it.id }.toSet()
        var cancelled = 0
        for (id in snapshot) {
            val slot = stillOpen[id.slotId]
            val shouldKeep = slot != null &&
                slot.medicationId in activeIds &&
                (slot.scheduledTs > now || slot.snoozeUntilTs?.let { it > now } == true)
            if (!shouldKeep) {
                runCatching { id.cancelAll(context) }
                    .onFailure { Log.e(TAG, "cancel failed slot=${id.slotId}", it) }
                cancelled++
            }
        }

        // 4. 注册：库中仍开放且活跃的槽位
        val advanceByMed = activeMeds.associate { it.id to it.advanceMinutes }
        var scheduled = 0
        for (slot in stillOpen.values) {
            if (slot.medicationId !in activeIds) continue
            val mainAt = slot.scheduledTs
            val snoozeAt = slot.snoozeUntilTs

            // 推迟中的槽位：主闹钟已无意义（用户主动改期），只排推迟唤醒
            if (slot.status == SlotStatus.SNOOZED) {
                if (snoozeAt != null && snoozeAt > now) {
                    runCatching { AlarmScheduler.schedule(context, slot, snoozeAt, AlarmScheduler.Kind.SNOOZE) }
                        .onFailure { Log.e(TAG, "snooze schedule failed slot=${slot.id}", it) }
                    scheduled++
                }
                continue
            }

            // 提前提醒：药品配了 advance_minutes 时，在计划时间前 N 分钟额外唤醒一次。
            // 身份靠 Uri 的 kind 段区分，取消主闹钟不会误伤提前闹钟（P0-1 修复的核心收益）。
            val advance = advanceByMed[slot.medicationId] ?: 0
            if (advance > 0) {
                val advanceAt = mainAt - advance * 60_000L
                if (advanceAt > now) {
                    runCatching { AlarmScheduler.schedule(context, slot, advanceAt, AlarmScheduler.Kind.ADVANCE) }
                        .onFailure { Log.e(TAG, "advance schedule failed slot=${slot.id}", it) }
                }
            }
            if (mainAt > now) {
                runCatching { AlarmScheduler.schedule(context, slot, mainAt, AlarmScheduler.Kind.MAIN) }
                    .onFailure { Log.e(TAG, "schedule failed slot=${slot.id}", it) }
            }
            scheduled++
        }

        Log.i(
            TAG,
            "done: horizon=${HORIZON_DAYS}d activeMeds=${activeIds.size}, " +
                "openSlots=${stillOpen.size}, scheduled=$scheduled, cancelled=$cancelled"
        )
    }
}
