package com.mcxiaoke.carromed.core.domain.service

import android.content.Context
import com.mcxiaoke.carromed.core.alarm.AlarmReconciler
import com.mcxiaoke.carromed.core.alarm.AlarmScheduler
import com.mcxiaoke.carromed.core.alarm.Notifications
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.model.RecordStatus

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
 * 只做编排，不做校验：幂等锚点仍住在 `DoseTrackingService` 与 SQL 的 `WHERE` 里
 * （AGENTS.md §2 第 1 条）。所以本类的方法**返回底层服务的返回值**，
 * 调用方据此决定提示文案。
 */
class DoseEntryActions(
    private val context: Context,
    private val db: AppDatabase
) {

    private val tracking = DoseTrackingService(db)

    /**
     * 确认服药：事务打卡（扣一次库存）→ 撤三种闹钟 → 撤托盘通知。
     *
     * 无论底层是否成功都撤闹钟与通知：槽位已经不该再提醒了，
     * 留着只会让"确认过了还在响"这条最坏的体验成真。
     */
    suspend fun confirm(
        slotId: Long,
        takenAmount: Float? = null,
        note: String? = null
    ): Boolean {
        val ok = tracking.takeDose(
            slotId = slotId,
            takenAmount = takenAmount,
            note = note
        )
        cancelAlarmsAndNotification(slotId)
        return ok
    }

    /** 跳过本次：写跳过事实（不扣库存）→ 撤闹钟 → 撤通知 */
    suspend fun skip(slotId: Long, reason: String? = null): Boolean {
        val ok = tracking.skipDose(slotId = slotId, reason = reason)
        cancelAlarmsAndNotification(slotId)
        return ok
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
        runCatching { AlarmReconciler.rescheduleAll(context, db) }
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
