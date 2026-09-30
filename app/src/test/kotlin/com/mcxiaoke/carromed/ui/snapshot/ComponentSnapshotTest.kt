package com.mcxiaoke.carromed.ui.snapshot

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.mcxiaoke.carromed.ui.component.CarroMedTopAppBar
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
}
