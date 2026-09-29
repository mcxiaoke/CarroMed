package com.mcxiaoke.carromed.ui.navigation

/**
 * 应用页面路由定义
 *
 * 信息架构原则（对齐 MyTherapy 实机截图）：
 * **药品信息 / 提醒设置 / 库存**是三件互相独立的事，各有各的编辑界面。
 *
 * ⚠️ 2026-09-29 更新：这一段原先写着「添加药品把三者合在一次低门槛录入里」——
 * 那正是本次 UX 改造要拆掉的设计。留着不改的话，它会像 `current_stock`
 * 那样"比代码活得更久"，把后来的读者引向已经不存在的做法。
 *
 * 现在的原则：**新建只建档案**（名称 / 类别 / 默认剂量），
 * 提醒与库存各自从详情页的入口进去配。建完药直接进详情页，
 * 没有计划的药会在「提醒设置」那一行显式提示"尚未设置服药计划"。
 */
sealed class Screen(val route: String) {
    // 底部导航四大主页面
    data object Today : Screen("today")
    data object Cabinet : Screen("cabinet")
    data object Progress : Screen("progress")
    data object Stats : Screen("stats")

    // ---------- 二级页面 (隐藏底部导航栏) ----------

    /** 药品详情：只读总览 + 四个分节入口 */
    data object MedicationDetail : Screen("med_detail/{medId}") {
        fun createRoute(medId: Long) = "med_detail/$medId"
    }

    /**
     * 药品信息新增 / 编辑 (复用同一界面)
     * - medId 为空 → 新增：**只填药品档案 + 初始库存**，提醒稍后在详情页配
     * - medId 有值 → 编辑：仅药品信息维度
     */
    data object AddEditMedication : Screen("med_edit?medId={medId}") {
        fun createRoute(medId: Long? = null) = if (medId != null) "med_edit?medId=$medId" else "med_edit"
    }

    /** 提醒设置 (频次 / 疗程 / 时点 / 提醒行为) —— 独立于药品信息 */
    data object ReminderSettings : Screen("med_reminder/{medId}") {
        fun createRoute(medId: Long) = "med_reminder/$medId"
    }

    /** 库存管理 (余量 / 预警 / 盘点 / 流水) —— 独立于药品信息 */
    data object Inventory : Screen("med_inventory/{medId}") {
        fun createRoute(medId: Long) = "med_inventory/$medId"
    }

    /** 手动补录服药 (漏打卡 / 按需临时用药) */
    data object ManualDose : Screen("manual_dose?medId={medId}") {
        fun createRoute(medId: Long? = null) = if (medId != null) "manual_dose?medId=$medId" else "manual_dose"
    }

    /**
     * **统一记录详情页**：待服 / 已逾期 / 已服 / 已跳过 / 手动补录，一个页面按状态渲染。
     *
     * 两个查询参数**二选一**（都必须有 `defaultValue`，否则路由匹配不上）：
     * - `slotId`：今日清单三分区，以及进展流水/单药历史里**有排班**的记录
     *   （导航层按 `record.slotId` 归一，见 `AppNavigation`）
     * - `recordId`：手动补录记录（`slot_id == null`）
     *
     * 为什么是一个页面：三种状态共享同一套信息区，只有操作区不同；
     * 而状态在运行期会变（点「撤销」后同一条就从"已服"变"待服"），
     * 单页面 + Flow 数据源能让形态自动跟随。理由详见
     * `docs/PLAN-RECORD-DETAIL-20260929.md` §3。
     */
    data object DoseDetail : Screen("dose_detail?slotId={slotId}&recordId={recordId}") {
        fun forSlot(slotId: Long) = "dose_detail?slotId=$slotId"
        fun forRecord(recordId: Long) = "dose_detail?recordId=$recordId"
    }

    /** 补药入库 */
    data object Refill : Screen("refill/{medId}") {
        fun createRoute(medId: Long) = "refill/$medId"
    }

    /** 单个药品的服药历史（按月分组） */
    data object MedHistory : Screen("med_history/{medId}") {
        fun createRoute(medId: Long) = "med_history/$medId"
    }

    data object Settings : Screen("settings")
    data object PermissionCheck : Screen("permission_check")
}
