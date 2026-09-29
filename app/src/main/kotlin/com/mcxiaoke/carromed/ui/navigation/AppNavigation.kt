package com.mcxiaoke.carromed.ui.navigation

import android.app.Application
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mcxiaoke.carromed.ui.screen.cabinet.CabinetScreen
import com.mcxiaoke.carromed.ui.screen.cabinet.CabinetViewModel
import com.mcxiaoke.carromed.ui.screen.detail.MedicationDetailScreen
import com.mcxiaoke.carromed.ui.screen.detail.MedicationDetailViewModel
import com.mcxiaoke.carromed.ui.screen.edit.AddEditMedicationScreen
import com.mcxiaoke.carromed.ui.screen.edit.AddEditMedicationViewModel
import com.mcxiaoke.carromed.ui.screen.inventory.InventoryScreen
import com.mcxiaoke.carromed.ui.screen.inventory.InventoryViewModel
import com.mcxiaoke.carromed.ui.screen.manual.ManualDoseScreen
import com.mcxiaoke.carromed.ui.screen.manual.ManualDoseViewModel
import com.mcxiaoke.carromed.ui.screen.progress.MedHistoryScreen
import com.mcxiaoke.carromed.ui.screen.record.DoseRecordEditScreen
import com.mcxiaoke.carromed.ui.screen.progress.ProgressScreen
import com.mcxiaoke.carromed.ui.screen.progress.ProgressViewModel
import com.mcxiaoke.carromed.ui.screen.refill.RefillScreen
import com.mcxiaoke.carromed.ui.screen.reminder.ReminderSettingsScreen
import com.mcxiaoke.carromed.ui.screen.reminder.ReminderSettingsViewModel
import com.mcxiaoke.carromed.ui.screen.refill.RefillViewModel
import com.mcxiaoke.carromed.ui.screen.settings.PermissionCheckScreen
import com.mcxiaoke.carromed.ui.screen.settings.SettingsScreen
import com.mcxiaoke.carromed.ui.screen.settings.SettingsViewModel
import com.mcxiaoke.carromed.ui.screen.stats.StatsScreen
import com.mcxiaoke.carromed.ui.screen.stats.StatsViewModel
import com.mcxiaoke.carromed.ui.screen.today.TodayScreen
import com.mcxiaoke.carromed.ui.screen.today.TodayViewModel

data class BottomNavItem(
    val route: String,
    val title: String,
    val icon: ImageVector
)

val BottomNavItems = listOf(
    BottomNavItem(Screen.Today.route, "今日", Icons.Default.Checklist),
    BottomNavItem(Screen.Cabinet.route, "药箱", Icons.Default.Medication),
    BottomNavItem(Screen.Progress.route, "进展", Icons.Default.BarChart),
    BottomNavItem(Screen.Stats.route, "统计", Icons.Default.QueryStats)
)

@Composable
fun AppNavigation(navController: NavHostController = rememberNavController()) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val isTopLevel = BottomNavItems.any { it.route == currentRoute }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (isTopLevel) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    BottomNavItems.forEach { item ->
                        val selected = currentRoute == item.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (currentRoute != item.route) {
                                    navController.navigate(item.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = { Icon(item.icon, contentDescription = item.title) },
                            label = { Text(item.title, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Today.route,
            modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None }
        ) {
            // 1. 今日清单
            composable(Screen.Today.route) {
                val vm: TodayViewModel = viewModel()
                TodayScreen(
                    viewModel = vm,
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                    onNavigateToAddMedication = { navController.navigate(Screen.AddEditMedication.createRoute()) },
                    onNavigateToManualDose = { navController.navigate(Screen.ManualDose.createRoute()) },
                    onNavigateToRefill = { medId -> navController.navigate(Screen.Refill.createRoute(medId)) },
                    onNavigateToInventory = { medId -> navController.navigate(Screen.Inventory.createRoute(medId)) },
                    onNavigateToMedDetail = { medId -> navController.navigate(Screen.MedicationDetail.createRoute(medId)) }
                )
            }

            // 2. 我的药箱
            composable(Screen.Cabinet.route) {
                val vm: CabinetViewModel = viewModel()
                CabinetScreen(
                    viewModel = vm,
                    onNavigateToAddMedication = { navController.navigate(Screen.AddEditMedication.createRoute()) },
                    onNavigateToMedDetail = { medId -> navController.navigate(Screen.MedicationDetail.createRoute(medId)) }
                )
            }

            // 3. 进展追踪
            composable(Screen.Progress.route) {
                val vm: ProgressViewModel = viewModel()
                ProgressScreen(
                    viewModel = vm,
                    onNavigateToRecord = { recordId ->
                        navController.navigate(Screen.DoseRecordEdit.createRoute(recordId))
                    },
                    onNavigateToMedHistory = { medId ->
                        navController.navigate(Screen.MedHistory.createRoute(medId))
                    }
                )
            }

            // 4. 统计报表
            composable(Screen.Stats.route) {
                val vm: StatsViewModel = viewModel()
                StatsScreen(viewModel = vm)
            }

            // 5. 药品专属详情页 (二级全屏)
            composable(
                route = Screen.MedicationDetail.route,
                arguments = listOf(navArgument("medId") { type = NavType.LongType })
            ) { backStackEntry ->
                val medId = backStackEntry.arguments?.getLong("medId") ?: 0L
                val vm: MedicationDetailViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            MedicationDetailViewModel(
                                application = this[APPLICATION_KEY] as Application,
                                medId = medId
                            )
                        }
                    }
                )
                MedicationDetailScreen(
                    viewModel = vm,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToEditInfo = { id -> navController.navigate(Screen.AddEditMedication.createRoute(id)) },
                    onNavigateToReminder = { id -> navController.navigate(Screen.ReminderSettings.createRoute(id)) },
                    onNavigateToInventory = { id -> navController.navigate(Screen.Inventory.createRoute(id)) }
                )
            }

            // 5b. 提醒设置 (二级全屏) —— 与药品信息、库存完全分离
            composable(
                route = Screen.ReminderSettings.route,
                arguments = listOf(navArgument("medId") { type = NavType.LongType })
            ) { backStackEntry ->
                val medId = backStackEntry.arguments?.getLong("medId") ?: 0L
                val vm: ReminderSettingsViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            ReminderSettingsViewModel(
                                application = this[APPLICATION_KEY] as Application,
                                medId = medId
                            )
                        }
                    }
                )
                ReminderSettingsScreen(
                    viewModel = vm,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            // 5c. 库存管理 (二级全屏) —— 与药品信息、提醒设置完全分离
            composable(
                route = Screen.Inventory.route,
                arguments = listOf(navArgument("medId") { type = NavType.LongType })
            ) { backStackEntry ->
                val medId = backStackEntry.arguments?.getLong("medId") ?: 0L
                val vm: InventoryViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            InventoryViewModel(
                                application = this[APPLICATION_KEY] as Application,
                                medId = medId
                            )
                        }
                    }
                )
                InventoryScreen(
                    viewModel = vm,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToRefill = { id -> navController.navigate(Screen.Refill.createRoute(id)) }
                )
            }

            // 6. 添加/编辑用药与计划 (二级全屏)
            composable(
                route = Screen.AddEditMedication.route,
                arguments = listOf(navArgument("medId") {
                    type = NavType.LongType
                    defaultValue = 0L
                })
            ) { backStackEntry ->
                val medId = backStackEntry.arguments?.getLong("medId")?.takeIf { it > 0 }
                val vm: AddEditMedicationViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            // 有 medId = 编辑既有药品 (仅药品信息维度)
                            // 无 medId = 新增 (药品信息 + 提醒计划 + 初始库存 一次填完)
                            AddEditMedicationViewModel(
                                application = this[APPLICATION_KEY] as Application,
                                medId = medId
                            )
                        }
                    }
                )
                AddEditMedicationScreen(
                    viewModel = vm,
                    onNavigateBack = { navController.popBackStack() },
                    onSavedSuccess = { newMedId ->
                        // ⚠️ 新建后**直接进药品详情页**（2026-09-29 UX 改造）。
                        //
                        // 旧行为是 `popBackStack()` 退回药箱。现在新建页不再配提醒，
                        // 用户存完就"消失"了 —— 他还没设提醒，而这味药从此不响。
                        //
                        // 进详情页之后，「提醒设置」入口就在眼前（详情页本来就有），
                        // 顺手的事；而且详情页会在没有计划时显式提示
                        // 「尚未设置服药计划」——**诚实的空缺好过虚假的完成感**。
                        navController.popBackStack()
                        navController.navigate(Screen.MedicationDetail.createRoute(newMedId))
                    }
                )
            }

            // 7. 手动补录服药 (二级全屏)
            composable(
                route = Screen.ManualDose.route,
                arguments = listOf(navArgument("medId") {
                    type = NavType.LongType
                    defaultValue = 0L
                })
            ) { backStackEntry ->
                val medId = backStackEntry.arguments?.getLong("medId")?.takeIf { it > 0 }
                val vm: ManualDoseViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            ManualDoseViewModel(
                                application = this[APPLICATION_KEY] as Application,
                                initialMedId = medId
                            )
                        }
                    }
                )
                ManualDoseScreen(
                    viewModel = vm,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            // 7b. 服药记录详情 (二级全屏)
            composable(
                route = Screen.DoseRecordEdit.route,
                arguments = listOf(navArgument("recordId") { type = NavType.LongType })
            ) {
                DoseRecordEditScreen(
                    recordId = it.arguments?.getLong("recordId") ?: 0L,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            // 7c. 单个药品的服药历史 (二级全屏)
            composable(
                route = Screen.MedHistory.route,
                arguments = listOf(navArgument("medId") { type = NavType.LongType })
            ) {
                MedHistoryScreen(
                    medId = it.arguments?.getLong("medId") ?: 0L,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToRecord = { recordId ->
                        navController.navigate(Screen.DoseRecordEdit.createRoute(recordId))
                    }
                )
            }

            // 8. 补药入库 (二级全屏)
            composable(
                route = Screen.Refill.route,
                arguments = listOf(navArgument("medId") { type = NavType.LongType })
            ) { backStackEntry ->
                val medId = backStackEntry.arguments?.getLong("medId") ?: 0L
                val vm: RefillViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            RefillViewModel(
                                application = this[APPLICATION_KEY] as Application,
                                medId = medId
                            )
                        }
                    }
                )
                RefillScreen(
                    viewModel = vm,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            // 9. 系统设置 (二级全屏)
            composable(Screen.Settings.route) {
                val vm: SettingsViewModel = viewModel()
                SettingsScreen(
                    viewModel = vm,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToPermissionCheck = { navController.navigate(Screen.PermissionCheck.route) }
                )
            }

            // 10. 系统特权自检与保活指引 (二级全屏)
            composable(Screen.PermissionCheck.route) {
                PermissionCheckScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }
}
