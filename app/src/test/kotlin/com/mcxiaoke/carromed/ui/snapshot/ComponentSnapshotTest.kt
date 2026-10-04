package com.mcxiaoke.carromed.ui.snapshot

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.mcxiaoke.carromed.ui.component.CarroMedTopAppBar
import com.mcxiaoke.carromed.ui.component.NotificationPermissionBanner
import com.mcxiaoke.carromed.ui.screen.alert.AlarmAlertContent
import com.mcxiaoke.carromed.ui.theme.CarroMedTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 纯展示组件的视觉回归快照（PLAN-UI-TEST-20260929.md P2）。
 *
 * 只快照**无 ViewModel 依赖**的展示组件——整页快照见 [ScreenSnapshotTest]
 * （真实 Activity + 真实工厂路径）。
 *
 * 基线策略（PLAN-TITLEBAR-STANDARDIZATION-20260930.md §8.2）：
 * UI/UX 冻结前 `verifyRoborazziDebug` 不进门禁，有意改 UI 后跑一次
 * `recordRoborazziDebug` 覆盖即可，不做逐张人眼复核；冻结后再升为门禁。
 * 另注意 Robolectric 的系统栏 inset 恒为 0，**顶栏几何回归在这里不可见**，
 * 那类断言在 `SmokeNavigationTest.topBarAligned_acrossTabs`。
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
@RunWith(AndroidJUnit4::class)
class ComponentSnapshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun topBar_withAction() {
        composeRule.setContent {
            CarroMedTheme {
                CarroMedTopAppBar(
                    title = "今日清单",
                    actionIcon = Icons.Filled.Settings,
                    actionContentDescription = "系统设置",
                    onActionClick = {}
                )
            }
        }
        composeRule.onRoot().captureRoboImage(File("src/test/snapshots/component/top_bar_with_action.png"))
    }

    @Test
    fun topBar_longTitle() {
        // 长标题不撑高顶栏是顶栏规格的硬约束（4 Tab 切换标题不跳动）：
        // CarroMedTopAppBar 里 maxLines = 1 + Ellipsis 就是为了守住它。
        composeRule.setContent {
            CarroMedTheme {
                CarroMedTopAppBar(
                    title = "系统特权自检与保活指引",
                    actionIcon = Icons.Filled.Settings,
                    actionContentDescription = "系统设置",
                    onActionClick = {}
                )
            }
        }
        composeRule.onRoot().captureRoboImage(File("src/test/snapshots/component/top_bar_long_title.png"))
    }

    @Test
    fun topBar_withSubtitle() {
        // 副标题形态（提醒设置 / 库存管理 / 服药历史三页都在用）
        composeRule.setContent {
            CarroMedTheme {
                CarroMedTopAppBar(
                    title = "钙和维生素D",
                    subtitle = "服药历史",
                    navigationIcon = { }
                )
            }
        }
        composeRule.onRoot().captureRoboImage(File("src/test/snapshots/component/top_bar_with_subtitle.png"))
    }

    /**
     * 通知权限永久拒绝的引导横幅（3-3）。
     *
     * 这条横幅只在"用户勾了不再询问"这一种状态下出现，**走查脚本到不了**
     * （它要求真实的系统权限状态），所以用组件快照把它的版式钉住：
     * 标题/正文/两个操作的位置与换行，改文案时能立刻看出溢出。
     */
    @Test
    fun notificationPermissionBanner() {
        composeRule.setContent {
            CarroMedTheme {
                NotificationPermissionBanner(onGoToSettings = {}, onDismiss = {})
            }
        }
        composeRule.onRoot().captureRoboImage(File("src/test/snapshots/component/notification_permission_banner.png"))
    }

    /**
     * 全屏提醒的正文（3-1 重构后）。
     *
     * 该页是 `singleTop` 复用的实例，内容由 payload 状态驱动；
     * 推迟档位固定四档 + 药品自定义档（这里取 45 作代表），
     * 走查脚本只截"正常"形态，这里把"重要提醒"形态也钉住。
     *
     * `qualifiers` 加高设备高度：该页是 `verticalScroll`，Robolectric 默认屏高会把
     * 推迟档位与「跳过本次」截在图外（只有上半张，等于没钉住）。
     */
    @Test
    @Config(qualifiers = "w411dp-h1200dp")
    fun alarmAlertContent_normal() {
        composeRule.setContent {
            CarroMedTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    AlarmAlertContent(
                        medName = "二甲双胍",
                        scheduledTime = "08:30",
                        doseText = "1 片",
                        notice = "随餐服用，避免空腹",
                        isCritical = false,
                        snoozeOptions = listOf(30, 45, 60, 90, 120),
                        onTake = {}, onSnooze = {}, onSkip = {}
                    )
                }
            }
        }
        composeRule.onRoot().captureRoboImage(File("src/test/snapshots/component/alarm_alert_content_normal.png"))
    }

    @Test
    @Config(qualifiers = "w411dp-h1200dp")
    fun alarmAlertContent_critical() {
        composeRule.setContent {
            CarroMedTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    AlarmAlertContent(
                        medName = "环孢素",
                        scheduledTime = "22:00",
                        doseText = "2 粒",
                        notice = "与西柚汁间隔 2 小时",
                        isCritical = true,
                        snoozeOptions = listOf(30, 60, 90, 120),
                        onTake = {}, onSnooze = {}, onSkip = {}
                    )
                }
            }
        }
        composeRule.onRoot().captureRoboImage(File("src/test/snapshots/component/alarm_alert_content_critical.png"))
    }
}
