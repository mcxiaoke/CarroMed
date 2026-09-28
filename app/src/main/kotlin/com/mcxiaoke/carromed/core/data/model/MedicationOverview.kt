package com.mcxiaoke.carromed.core.data.model

import androidx.room.Embedded
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.domain.model.Dose

/**
 * 药品列表 / 详情的**读模型**：内嵌 [MedicationEntity] 全字段，额外附带一个由流水聚合出的库存余额。
 *
 * ## 为什么需要它
 *
 * `medications.current_stock` 已被删除（见 [MedicationEntity] 的说明与
 * `docs/REMINDER-DOMAIN-REDESIGN.md` §2.3）。余额不再是药品档案的一部分，
 * 而是**台账流水的聚合结果**。但列表页、详情页、今日页、药箱页都需要显示余额，
 * 且这些页面是响应式的（Room `Flow`），因此需要一个能直接订阅的读模型。
 *
 * ## 为什么不直接在实体上放一个可空字段
 *
 * 那会让"档案字段"与"聚合字段"混在一个类里，调用方无从判断哪个值可信。
 * 拆成两个类型后，**类型本身就是文档**：
 * - 拿到 `MedicationEntity` ⇒ 你手上的都是档案字段
 * - 拿到 `MedicationOverview` ⇒ 你手上还额外有一个可信的台账余额
 *
 * @param stock 由 `SUM(inventory_transactions.change_amount)` 聚合得到，单位同药品的 `unit`。
 *              **可以为负** —— 这是 `FINAL-PRODUCT` D-9 的明确要求：
 *              "允许扣为负数，绝不阻止打卡；负库存单独视觉化提示盘点"。
 */
data class MedicationOverview(
    @Embedded val medication: MedicationEntity,
    /** 台账聚合出的账面余额（毫单位 -> 展示值）。**可为负**，见 FINAL-PRODUCT D-9。 */
    val stock: Float,
) {
    // ---- 代理常用字段，避免调用方到处写 .medication.xxx ----
    val id: Long get() = medication.id
    val name: String get() = medication.name
    val unit: String get() = medication.unit
    val isStockTracked: Boolean get() = medication.isStockTracked
    /** 预警线（毫单位）转展示值 */
    val minStockAlert: Float get() = Dose(medication.minStockAlert).asFloat
    val isPaused: Boolean get() = medication.isPaused
    val isArchived: Boolean get() = medication.isArchived
}
