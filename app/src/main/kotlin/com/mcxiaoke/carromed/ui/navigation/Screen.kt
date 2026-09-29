package com.mcxiaoke.carromed.ui.navigation

/**
 * 应用页面路由定义
 *
 * 信息架构原则（对齐 MyTherapy 实机截图并更进一步）：
 * **药品信息 / 提醒设置 / 库存**是三件互相独立的事，各有各的编辑界面。
 * 「添加药品」把三者合在一次低门槛录入里；「编辑」则必须分别进入对应子页，
 * 避免"想改个药名却要滚过一整套闹钟计划"。
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
     * - medId 为空 → 新增：药品信息 + 提醒计划 + 初始库存 一次填完
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
     * 服药记录详情：查看 / 改剂量备注 / 撤销跳过。
     *
     * 独立页面而非弹菜单 —— 低频操作有四个（撤销、跳过、确认、改剂量），
     * 弹菜单只给操作不给上下文，而"我记错了"恰恰发生在已经忘了当时填了什么的时候。
     */
    data object DoseRecordEdit : Screen("dose_record/{recordId}") {
        fun createRoute(recordId: Long) = "dose_record/$recordId"
    }

    /** 补药入库 */
    data object Refill : Screen("refill/{medId}") {
        fun createRoute(medId: Long) = "refill/$medId"
    }

    data object Settings : Screen("settings")
    data object PermissionCheck : Screen("permission_check")
}
