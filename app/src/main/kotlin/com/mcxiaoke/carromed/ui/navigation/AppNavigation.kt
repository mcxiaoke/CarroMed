package com.mcxiaoke.carromed.ui.navigation

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import com.mcxiaoke.carromed.R
import com.mcxiaoke.carromed.ui.component.TestTags
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlin.reflect.KClass
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
import com.mcxiaoke.carromed.ui.screen.record.DoseRecordDetailScreen
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
    val route: ScreenRoute,
    val targetClass: KClass<out ScreenRoute>,
    val titleRes: Int,
    val icon: ImageVector,
    val testTag: String
)

val BottomNavItems = listOf(
    BottomNavItem(ScreenRoute.Today, ScreenRoute.Today::class, R.string.nav_tab_today, Icons.Default.Checklist, TestTags.TAB_TODAY),
    BottomNavItem(ScreenRoute.Cabinet, ScreenRoute.Cabinet::class, R.string.nav_tab_cabinet, Icons.Default.Medication, TestTags.TAB_CABINET),
    BottomNavItem(ScreenRoute.Progress, ScreenRoute.Progress::class, R.string.nav_tab_progress, Icons.Default.BarChart, TestTags.TAB_PROGRESS),
    BottomNavItem(ScreenRoute.Stats, ScreenRoute.Stats::class, R.string.nav_tab_stats, Icons.Default.QueryStats, TestTags.TAB_STATS)
)

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun AppNavigation(navController: NavHostController = rememberNavController()) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val isTopLevel = BottomNavItems.any { item ->
        currentDestination?.hasRoute(item.targetClass) == true
    }

    val navBarBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val navBarHeight = 80.dp + navBarBottomInset

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 语义标识（PLAN-UI-TEST-20260929.md §1）：Compose 测试默认找不到
            // testTag（它不映射成 resource-id），必须显式打开。仅 debug 生效——
            // release 不泄露测试标识，语义树也保持最小。
            .then(
                if (com.mcxiaoke.carromed.BuildConfig.DEBUG) {
                    Modifier.semantics { testTagsAsResourceId = true }
                } else {
                    Modifier
                }
            )
    ) {
        NavHost(
            navController = navController,
            startDestination = ScreenRoute.Today,
            modifier = Modifier.fillMaxSize(),
            enterTransition = { MotionSpec.secondaryEnter },
            exitTransition = { MotionSpec.secondaryExit },
            popEnterTransition = { MotionSpec.secondaryPopEnter },
            popExitTransition = { MotionSpec.secondaryPopExit }
        ) {
            // 1. 今日清单
            composable<ScreenRoute.Today>(
                enterTransition = { MotionSpec.tabEnter },
                exitTransition = { MotionSpec.tabExit },
                popEnterTransition = { EnterTransition.None }
            ) {
                val vm: TodayViewModel = viewModel()
                MainTabContent(navBarHeight) {
                    TodayScreen(
                        viewModel = vm,
                        onNavigateToSettings = { navController.navigate(ScreenRoute.Settings) },
                        onNavigateToAddMedication = { navController.navigate(ScreenRoute.AddEditMedication()) },
                        onNavigateToManualDose = { navController.navigate(ScreenRoute.ManualDose()) },
                        onNavigateToRefill = { medId -> navController.navigate(ScreenRoute.Refill(medId)) },
                        onNavigateToInventory = { medId -> navController.navigate(ScreenRoute.Inventory(medId)) },
                        // 今日清单的 item 一律进记录详情页（不再跳药品详情）
                        onOpenDose = { slotId -> navController.navigate(ScreenRoute.DoseDetail(slotId = slotId)) }
                    )
                }
            }

            // 2. 我的药箱
            composable<ScreenRoute.Cabinet>(
                enterTransition = { MotionSpec.tabEnter },
                exitTransition = { MotionSpec.tabExit },
                popEnterTransition = { EnterTransition.None }
            ) {
                val vm: CabinetViewModel = viewModel()
                MainTabContent(navBarHeight) {
                    CabinetScreen(
                        viewModel = vm,
                        onNavigateToAddMedication = { navController.navigate(ScreenRoute.AddEditMedication()) },
                        onNavigateToMedDetail = { medId -> navController.navigate(ScreenRoute.MedicationDetail(medId)) }
                    )
                }
            }

            // 3. 进展追踪
            composable<ScreenRoute.Progress>(
                enterTransition = { MotionSpec.tabEnter },
                exitTransition = { MotionSpec.tabExit },
                popEnterTransition = { EnterTransition.None }
            ) {
                val vm: ProgressViewModel = viewModel()
                MainTabContent(navBarHeight) {
                    ProgressScreen(
                        viewModel = vm,
                        onOpenDose = { slotId, recordId ->
                            navController.navigate(doseDetailRoute(slotId, recordId))
                        },
                        onNavigateToMedHistory = { medId ->
                            navController.navigate(ScreenRoute.MedHistory(medId))
                        }
                    )
                }
            }

            // 4. 统计报表
            composable<ScreenRoute.Stats>(
                enterTransition = { MotionSpec.tabEnter },
                exitTransition = { MotionSpec.tabExit },
                popEnterTransition = { EnterTransition.None }
            ) {
                val vm: StatsViewModel = viewModel()
                MainTabContent(navBarHeight) {
                    StatsScreen(
                        viewModel = vm,
                        onNavigateToSettings = { navController.navigate(ScreenRoute.Settings) }
                    )
                }
            }

            // 5. 药品专属详情页 (二级全屏)
            composable<ScreenRoute.MedicationDetail> { backStackEntry ->
                val route = backStackEntry.toRoute<ScreenRoute.MedicationDetail>()
                val vm: MedicationDetailViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            MedicationDetailViewModel(
                                application = this[APPLICATION_KEY] as Application,
                                medId = route.medId
                            )
                        }
                    }
                )
                MedicationDetailScreen(
                    viewModel = vm,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToEditInfo = { id -> navController.navigate(ScreenRoute.AddEditMedication(id)) },
                    onNavigateToReminder = { id -> navController.navigate(ScreenRoute.ReminderSettings(id)) },
                    onNavigateToInventory = { id -> navController.navigate(ScreenRoute.Inventory(id)) }
                )
            }

            // 5b. 提醒设置 (二级全屏) —— 与药品信息、库存完全分离
            composable<ScreenRoute.ReminderSettings> { backStackEntry ->
                val route = backStackEntry.toRoute<ScreenRoute.ReminderSettings>()
                val vm: ReminderSettingsViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            ReminderSettingsViewModel(
                                application = this[APPLICATION_KEY] as Application,
                                medId = route.medId
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
            composable<ScreenRoute.Inventory> { backStackEntry ->
                val route = backStackEntry.toRoute<ScreenRoute.Inventory>()
                val vm: InventoryViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            InventoryViewModel(
                                application = this[APPLICATION_KEY] as Application,
                                medId = route.medId
                            )
                        }
                    }
                )
                InventoryScreen(
                    viewModel = vm,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToRefill = { id -> navController.navigate(ScreenRoute.Refill(id)) }
                )
            }

            // 6. 添加/编辑用药与计划 (二级全屏)
            composable<ScreenRoute.AddEditMedication> { backStackEntry ->
                val route = backStackEntry.toRoute<ScreenRoute.AddEditMedication>()
                val medId = route.medId.takeIf { it > 0 }
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
                        // ⚠️ 新建后直接进药品详情页（2026-09-29 UX 改造）。
                        navController.popBackStack()
                        navController.navigate(ScreenRoute.MedicationDetail(newMedId))
                    }
                )
            }

            // 7. 手动补录服药 (二级全屏)
            composable<ScreenRoute.ManualDose> { backStackEntry ->
                val route = backStackEntry.toRoute<ScreenRoute.ManualDose>()
                val medId = route.medId.takeIf { it > 0 }
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

            // 7b. 记录详情 (二级全屏；统一承载待服 / 已逾期 / 已服 / 已跳过 / 手动补录)
            composable<ScreenRoute.DoseDetail> { backStackEntry ->
                val route = backStackEntry.toRoute<ScreenRoute.DoseDetail>()
                DoseRecordDetailScreen(
                    slotId = route.slotId.takeIf { it > 0 },
                    recordId = route.recordId.takeIf { it > 0 },
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            // 7c. 单个药品的服药历史 (二级全屏)
            composable<ScreenRoute.MedHistory> { backStackEntry ->
                val route = backStackEntry.toRoute<ScreenRoute.MedHistory>()
                MedHistoryScreen(
                    medId = route.medId,
                    onNavigateBack = { navController.popBackStack() },
                    onOpenDose = { slotId, recordId ->
                        navController.navigate(doseDetailRoute(slotId, recordId))
                    }
                )
            }

            // 8. 补药入库 (二级全屏)
            composable<ScreenRoute.Refill> { backStackEntry ->
                val route = backStackEntry.toRoute<ScreenRoute.Refill>()
                val vm: RefillViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            RefillViewModel(
                                application = this[APPLICATION_KEY] as Application,
                                medId = route.medId
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
            composable<ScreenRoute.Settings> {
                val vm: SettingsViewModel = viewModel()
                SettingsScreen(
                    viewModel = vm,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToPermissionCheck = { navController.navigate(ScreenRoute.PermissionCheck) }
                )
            }

            // 10. 系统特权自检与保活指引 (二级全屏)
            composable<ScreenRoute.PermissionCheck> {
                PermissionCheckScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }

        AnimatedVisibility(
            visible = isTopLevel,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = MotionSpec.navBarEnter,
            exit = MotionSpec.navBarExit
        ) {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                BottomNavItems.forEach { item ->
                    val selected = currentDestination?.hasRoute(item.targetClass) == true
                    NavigationBarItem(
                        modifier = Modifier.testTag(item.testTag),
                        selected = selected,
                        onClick = {
                            if (!selected) {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        // contentDescription = null：NavigationBarItem 已有 label，
                        // 图标再带同文案会被 TalkBack 念两遍（orsbf P3-9）
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(stringResource(item.titleRes), fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
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
}

@Composable
private fun MainTabContent(
    navBarHeight: Dp,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = navBarHeight)
            .consumeWindowInsets(WindowInsets.navigationBars)
    ) {
        content()
    }
}

/**
 * 一条服药事实该以哪种身份打开记录详情页。
 *
 * **有排班的记录（`slot_id != null`）必须按槽位打开**：待服 / 已服 / 已跳过三种形态
 * 都由槽位承载，按事实打开就取不到"还没有事实"的待服形态；
 * 而手动补录（`slot_id == null`）没有槽位，只能按事实打开。
 */
private fun doseDetailRoute(slotId: Long?, recordId: Long): ScreenRoute.DoseDetail =
    if (slotId != null) ScreenRoute.DoseDetail(slotId = slotId) else ScreenRoute.DoseDetail(recordId = recordId)
