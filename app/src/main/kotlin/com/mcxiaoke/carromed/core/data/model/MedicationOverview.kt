package com.mcxiaoke.carromed.core.data.model

import androidx.room.Embedded
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.entity.ReminderSettingsEntity
import com.mcxiaoke.carromed.core.domain.model.Dose
import com.mcxiaoke.carromed.core.domain.model.PauseStatus
import java.time.LocalDate

/**
 * 药品列表 / 详情的**读模型**：内嵌 [MedicationEntity] 全字段，额外附带
 * 「由台账聚合出的库存余额」与「提醒运行态」。
 *
 * ## 为什么需要它
 *
 * 两类字段都不再是 `medications` 表的一部分：
 *
 * 1. **余额**由 `SUM(inventory_transactions.change_amount)` 聚合而来
 *    （`current_stock` 已删除，见 `docs/REMINDER-DOMAIN-REDESIGN.md` §2.3）；
 * 2. **提醒运行态**（重要提醒 / 专属推迟 / 提前提醒 / 暂停结束日）住在
 *    `reminder_settings` 表（A2 拆表）。
 *
 * 但列表页、详情页、今日页、药箱页都要显示它们，且都是响应式的（Room `Flow`），
 * 因此需要一个能一次性 JOIN 出来、可直接订阅的读模型。
 *
 * ## 为什么不直接在实体上放可空字段
 *
 * 那会让"档案字段"、"聚合字段"、"运行态字段"混在一个类里，调用方无从判断哪个值可信。
 * 拆成两个类型后，**类型本身就是文档**：
 * - 拿到 `MedicationEntity` ⇒ 你手上的都是药品档案字段
 * - 拿到 `MedicationOverview` ⇒ 你手上还额外有可信的台账余额与提醒运行态
 *
 * @param stock 由 `SUM(inventory_transactions.change_amount)` 聚合得到，单位同药品的 `unit`。
 *              **可以为负** —— 这是 `FINAL-PRODUCT` D-9 的明确要求：
 *              "允许扣为负数，绝不阻止打卡；负库存单独视觉化提示盘点"。
 * @param reminderSettings 提醒运行态；药品刚建、尚未写过设置时由查询补一份默认值。
 */
data class MedicationOverview(
    @Embedded val medication: MedicationEntity,
    /** 台账聚合出的账面余额（毫单位 -> 展示值）。**可为负**，见 FINAL-PRODUCT D-9。 */
    val stock: Float,
    val reminderSettings: ReminderSettingsEntity = ReminderSettingsEntity(medication.id),
) {
    // ---- 代理常用字段，避免调用方到处写 .medication.xxx ----
    val id: Long get() = medication.id
    val name: String get() = medication.name
    val unit: String get() = medication.unit
    val isStockTracked: Boolean get() = medication.isStockTracked
    /** 预警线（毫单位）转展示值 */
    val minStockAlert: Float get() = Dose(medication.minStockAlert).asFloat
    val isArchived: Boolean get() = medication.isArchived

    // ---- 提醒运行态代理（经 reminder_settings 表）----

    val isCriticalReminder: Boolean get() = reminderSettings.isCriticalReminder
    val snoozeMinutes: Int get() = reminderSettings.snoozeMinutes
    val advanceMinutes: Int get() = reminderSettings.advanceMinutes

    /**
     * 截至 [today] 是否处于暂停状态。
     *
     * **不要在实体上再存一个 `isPaused` 字段** —— 那是两处真相，
     * 且到期自动恢复后必然漂移。判断一律转给 [ReminderSettingsEntity.isPausedOn]。
     */
    fun isPausedOn(today: LocalDate): Boolean = reminderSettings.isPausedOn(today)

    /** 结构化暂停状态；展示层负责格式化（B4，D-A）。 */
    fun pauseStatus(today: LocalDate): PauseStatus = reminderSettings.pauseStatus(today)
}
