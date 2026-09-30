package com.mcxiaoke.carromed.ui.navigation

import kotlinx.serialization.Serializable

/**
 * 应用页面类型安全路由定义 (Navigation 2.8+ Type-Safe Navigation)
 *
 * 信息架构原则（对齐 MyTherapy 实机截图）：
 * **药品信息 / 提醒设置 / 库存**是三件互相独立的事，各有各的编辑界面。
 *
 * 现在的原则：**新建只建档案**（名称 / 类别 / 默认剂量），
 * 提醒与库存各自从详情页的入口进去配。建完药直接进详情页，
 * 没有计划的药会在「提醒设置」那一行显式提示"尚未设置服药计划"。
 */
sealed interface ScreenRoute {
    // 底部导航四大主页面
    @Serializable data object Today : ScreenRoute
    @Serializable data object Cabinet : ScreenRoute
    @Serializable data object Progress : ScreenRoute
    @Serializable data object Stats : ScreenRoute

    // ---------- 二级页面 (隐藏底部导航栏) ----------

    /** 药品详情：只读总览 + 四个分节入口 */
    @Serializable data class MedicationDetail(val medId: Long) : ScreenRoute

    /**
     * 药品信息新增 / 编辑 (复用同一界面)
     * - medId == 0L → 新增：**只填药品档案 + 初始库存**，提醒稍后在详情页配
     * - medId > 0L → 编辑：仅药品信息维度
     */
    @Serializable data class AddEditMedication(val medId: Long = 0L) : ScreenRoute

    /** 提醒设置 (频次 / 疗程 / 时点 / 提醒行为) —— 独立于药品信息 */
    @Serializable data class ReminderSettings(val medId: Long) : ScreenRoute

    /** 库存管理 (余量 / 预警 / 盘点 / 流水) —— 独立于药品信息 */
    @Serializable data class Inventory(val medId: Long) : ScreenRoute

    /** 手动补录服药 (漏打卡 / 按需临时用药) */
    @Serializable data class ManualDose(val medId: Long = 0L) : ScreenRoute

    /**
     * **统一记录详情页**：待服 / 已逾期 / 已服 / 已跳过 / 手动补录，一个页面按状态渲染。
     *
     * 两个查询参数二选一：
     * - `slotId > 0`：今日清单三分区，以及进展流水/单药历史里**有排班**的记录
     * - `recordId > 0`：手动补录记录（`slot_id == null`）
     */
    @Serializable data class DoseDetail(val slotId: Long = 0L, val recordId: Long = 0L) : ScreenRoute

    /** 补药入库 */
    @Serializable data class Refill(val medId: Long) : ScreenRoute

    /** 单个药品的服药历史（按月分组） */
    @Serializable data class MedHistory(val medId: Long) : ScreenRoute

    /** 系统设置 */
    @Serializable data object Settings : ScreenRoute

    /** 系统特权自检与保活指引 */
    @Serializable data object PermissionCheck : ScreenRoute
}
