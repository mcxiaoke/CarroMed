package com.mcxiaoke.carromed.ui.navigation

/**
 * 应用页面路由定义
 */
sealed class Screen(val route: String) {
    // 底部导航四大主页面
    data object Today : Screen("today")
    data object Cabinet : Screen("cabinet")
    data object Progress : Screen("progress")
    data object Stats : Screen("stats")

    // 全屏独立二级子页面 (隐藏底部导航栏，展示顶部返回)
    data object MedicationDetail : Screen("med_detail/{medId}") {
        fun createRoute(medId: Long) = "med_detail/$medId"
    }

    data object AddEditMedication : Screen("med_edit?medId={medId}") {
        fun createRoute(medId: Long? = null) = if (medId != null) "med_edit?medId=$medId" else "med_edit"
    }

    data object ManualDose : Screen("manual_dose?medId={medId}") {
        fun createRoute(medId: Long? = null) = if (medId != null) "manual_dose?medId=$medId" else "manual_dose"
    }

    data object Refill : Screen("refill/{medId}") {
        fun createRoute(medId: Long) = "refill/$medId"
    }

    data object Settings : Screen("settings")
    data object PermissionCheck : Screen("permission_check")
}
