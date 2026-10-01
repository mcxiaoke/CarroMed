package com.mcxiaoke.carromed.core.domain.service

import android.content.Context
import com.mcxiaoke.carromed.core.alarm.AlarmReconciler
import com.mcxiaoke.carromed.core.alarm.AlarmScheduler
import com.mcxiaoke.carromed.core.alarm.Notifications
import com.mcxiaoke.carromed.core.alarm.ReconcileWorker
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.model.RecordStatus
import com.mcxiaoke.carromed.core.domain.AppLog
import com.mcxiaoke.carromed.core.domain.engine.SlotActionPolicy
import com.mcxiaoke.carromed.core.domain.engine.SlotProjectionEngine
import java.time.LocalDate

/**
 * 一次「施加结论」的动作结果（打卡 / 跳过）。
 *
 * ## 为什么不是 `Boolean`
 *
 * `false` 把两件**副作用处理恰好相反**的事压成同一个值：
 *
 * | 失败原因 | 槽位状态 | 该不该撤闹钟 | 该说什么 |
 * | :--- | :--- | :--- | :--- |
 * | `ALREADY_HANDLED` | 已 COMPLETED / SKIPPED（或槽位已不存在） | **要撤** —— 它不该再提醒 | "该服药记录已处理过" |
 * | `FUTURE_SLOT` | 仍是 PENDING 的明天/更远的槽位 | **绝不能撤** —— 那是明天的提醒 | "未来的服药时间不能提前确认" |
 *
 * 若只有 `false`，调用方只能二选一：要么两处都撤（把明天的提醒弄丢）、
 * 要么两处都不撤（"确认过了还在响"）。这正是 `DoseSlotDao` KDoc 里点名过的
 * "按钮存在却永远失败、toast 只能撒谎"那一类缺陷。
 */
enum class DoseActionResult {
    APPLIED,
    ALREADY_HANDLED,
    FUTURE_SLOT;

    val isApplied: Boolean get() = this == APPLIED
}

/**
 * 用药条目的动作编排层：把「改数据 → 改闹钟 → 撤通知」三件事绑成一次调用。
 *
 * ## 为什么需要它
 *
 * 这三件事必须**同进同退**：漏掉闹钟，用户确认过了到点还会再响一次；
 * 漏掉撤销时的重排，"撤销了却再也不响"；漏掉通知，托盘上会长期挂着一条
 * 说「该吃药了」而按钮全都只会回答"已处理过"的僵尸通知
 * （`AlarmReconciler.cancelNotificationOf` 的 KDoc 记过这个缺陷）。
 *
 * 此前这套编排在 `TodayViewModel`（打卡 / 跳过 / 推迟 / 撤销四处）与
 * `DoseActionReceiver`（通知栏三个按钮）里**各写了一份**，而记录详情页
 * 需要第三份 —— 三份必然漂移，且漂移的后果是静默的（不会报错，只是不响）。
 *
 * ## 职责边界
 *
 * 只做编排，不做校验：幂等锚点与「未来槽位不可表态」都住在
 * `DoseTrackingService` 与 SQL 的 `WHERE` 里（AGENTS.md §2 第 1 条）。
 * 本类**唯一**的判断是"失败之后要不要动闹钟"，而这个判断用的是同一个
 * [SlotActionPolicy] —— 判据仍然只有一份。
 */
class DoseEntryActions(
    private val context: Context,
    private val db: AppDatabase,
    /**
     * "今天"的来源，与 [DoseTrackingService] 共用同一个（可注入）。
     *
     * 必须共用：若这里读挂钟而服务层用注入值，两者的"今天"可能不同一天，
     * 于是"服务层拒了、编排层判成已处理"⇒ 把一条**未来槽位的闹钟**撤掉。
     * 测试注入固定日期时，两侧必须一起跟着走。
     */
    private val todayProvider: () -> LocalDate = { LocalDate.now() }
) {

    private companion object {
        const val TAG = "DoseEntryActions"
    }

    private val tracking = DoseTrackingService(db, todayProvider)

    /**
     * 确认服药：事务打卡（扣一次库存）→ 撤三种闹钟 → 撤托盘通知。
     *
     * 「已处理过」与「槽位不存在」这类**非未来**的失败照旧撤闹钟与通知：
     * 槽位已经不该再提醒了，留着只会让"确认过了还在响"这条最坏的体验成真。
     *
     * ⚠️ 唯一的例外是**未来的槽位**：它仍然是 PENDING、仍然该在明天响，
     * 撤掉之后 `AlarmReconciler` 也不会再排（闹钟身份是内容寻址的，已经 cancel 掉了）
     * ⇒ 用户以为在提前处理，实际把明天的提醒弄丢了，且毫无察觉。
     */
    suspend fun confirm(
        slotId: Long,
        takenAmount: Float? = null,
        note: String? = null,
        noteKey: String? = null
    ): DoseActionResult {
        val slot = db.doseSlotDao().getSlotById(slotId)
        val today = todayProvider().toString()
        val isRetro = slot?.let { it.scheduledDate < today } ?: false
        val ok = tracking.takeDose(
            slotId = slotId,
            takenAmount = takenAmount,
            note = note,
            noteKey = noteKey,
            isRetrospective = isRetro
        )
        if (!ok) {
            val failure = classifyFailure(slotId)
            if (failure == DoseActionResult.FUTURE_SLOT) {
                AppLog.w(TAG, "confirm applied=false slot=$slotId reason=future-slot alarms-kept")
                return failure
            }
        }
        cancelAlarmsAndNotification(slotId)
        return if (ok) DoseActionResult.APPLIED else DoseActionResult.ALREADY_HANDLED
    }

    /** 跳过本次：写跳过事实（不扣库存）→ 撤闹钟 → 撤通知（未来槽位同样不碰闹钟） */
    suspend fun skip(
        slotId: Long,
        reason: String? = null,
        noteKey: String? = null
    ): DoseActionResult {
        val ok = tracking.skipDose(slotId = slotId, reason = reason, noteKey = noteKey)
        if (!ok) {
            val failure = classifyFailure(slotId)
            if (failure == DoseActionResult.FUTURE_SLOT) {
                AppLog.w(TAG, "skip applied=false slot=$slotId reason=future-slot alarms-kept")
                return failure
            }
        }
        cancelAlarmsAndNotification(slotId)
        return if (ok) DoseActionResult.APPLIED else DoseActionResult.ALREADY_HANDLED
    }

    /**
     * 改判（已服 ↔ 已跳过）：一个事务内作废旧结论、施加新结论（库存按净额守恒）。
     *
     * 只在**成功**时撤闹钟：失败说明槽位已不是目标状态，
     * 此时它可能刚刚被别人改成开放态，贸然取消会把新排的闹钟一并杀掉。
     */
    suspend fun restate(
        slotId: Long,
        target: RecordStatus,
        note: String? = null
    ): Boolean {
        val ok = tracking.restateSlot(slotId = slotId, target = target, note = note)
        if (ok) cancelAlarmsAndNotification(slotId)
        return ok
    }

    /**
     * 撤销（回未确认）：事实标 REVERTED + 台账按净额冲正 → 全量重排闹钟。
     *
     * 这里**必须**走 `AlarmReconciler.rescheduleAll` 而不是撤闹钟：
     * 槽位回到了 `PENDING`，它对外又该有闹钟了 —— 用撤销前的旧时刻去重排是错的，
     * 交给对账器按当前策略与当前时间统一推导。
     */
    suspend fun undo(slotId: Long): Boolean {
        val ok = tracking.undoDose(slotId)
        if (!ok) return false
        try {
            AlarmReconciler.rescheduleAll(context, db)
        } catch (t: Throwable) {
            // 重排失败不能像旧实现那样**静默吞掉**（orsbf P1-7）：槽位已回到 PENDING
            // 却没有闹钟，"撤销了却再也不响"，而函数仍返回 true。
            // 也不向调用方抛 —— VM 的失败文案是"撤销失败"，但撤销本体
            // （事实标 REVERTED + 台账冲正）已经落库，说"失败"会诱导用户
            // 去重试一个已完成的动作。改为：落 ERROR 日志 + 入队一次性对账，
            // 由 Worker 按退避策略重试直到排上为止。
            AppLog.e(TAG, "reschedule after undo failed slot=$slotId, fallback to worker retry", t)
            runCatching { ReconcileWorker.enqueueOneShot(context) }
                .onFailure { AppLog.e(TAG, "enqueue oneshot reconcile after undo failure failed", it) }
        }
        return true
    }

    /**
     * 推迟提醒：置 `SNOOZED` → 撤原定准点/提前闹钟 → 排一个 `SNOOZE` 种类 → 撤通知。
     *
     * 只撤 `MAIN` / `ADVANCE` 而不撤 `SNOOZE`：用户可能连续推迟两次，
     * 撤掉 `SNOOZE` 会把上一次推迟刚排的唤醒一起清掉。
     */
    suspend fun snooze(slotId: Long, minutes: Int): Boolean {
        val ok = tracking.snoozeDose(slotId, minutes)
        if (!ok) return false

        Notifications.cancelDoseNotification(context, slotId)
        val slot = db.doseSlotDao().getSlotById(slotId) ?: return true
        val triggerAt = slot.snoozeUntilTs ?: (System.currentTimeMillis() + minutes * 60_000L)

        AlarmScheduler.cancelAll(
            context, slot.medicationId, slot.scheduledDate, slot.scheduledTime, slot.id,
            kinds = listOf(AlarmScheduler.Kind.MAIN, AlarmScheduler.Kind.ADVANCE)
        )
        // 不吞异常：排闹钟失败必须能被用户看见（调用方负责上报），
        // 静默失败等于"我推迟了、以为会提醒，其实没有"。
        AlarmScheduler.schedule(context, slot, triggerAt, AlarmScheduler.Kind.SNOOZE)
        return true
    }

    /**
     * 一次拒绝的归因：未来槽位，还是"已被处理过 / 不存在"。
     *
     * 读一次槽位就够，且必须是**写失败之后**读 —— 归因依据的是"现在这条槽位是什么样"，
     * 而不是"调用前是什么样"（并发下两者可能不同）。
     *
     * 归因偏保守的方向是安全的：只有当槽位**确实**仍然是一条未来的槽位时，
     * 才会走"不撤闹钟"分支；其它一切情况（含槽位已消失）照旧撤闹钟，
     * 与改动前的语义完全一致。
     */
    private suspend fun classifyFailure(slotId: Long): DoseActionResult {
        val slot = db.doseSlotDao().getSlotById(slotId) ?: return DoseActionResult.ALREADY_HANDLED
        val todayStr = todayProvider().format(SlotProjectionEngine.DATE_FORMATTER)
        return if (SlotActionPolicy.isActionableOn(slot.scheduledDate, todayStr)) {
            DoseActionResult.ALREADY_HANDLED
        } else {
            DoseActionResult.FUTURE_SLOT
        }
    }

    /**
     * 槽位已产生结论：三种闹钟种类一次清干净，并撤掉托盘上那条已经过期的通知。
     *
     * 闹钟身份是内容寻址的 `(medId, date, time, kind)`，所以取消必须带齐定位信息 ——
     * 不能只凭 `slotId`（AGENTS.md §2 第 4 条）。
     */
    private suspend fun cancelAlarmsAndNotification(slotId: Long) {
        val slot = db.doseSlotDao().getSlotById(slotId) ?: return
        AlarmScheduler.cancelAll(
            context, slot.medicationId, slot.scheduledDate, slot.scheduledTime, slot.id
        )
        Notifications.cancelDoseNotification(context, slotId)
    }
}
