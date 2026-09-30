package com.mcxiaoke.carromed.ui.snapshot

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.mcxiaoke.carromed.ui.component.HomeTabHeader
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
 * （真实 Activity + 真实工厂路径）。基线文件提交进 git：
 * `recordRoborazziDebug` 有意改 UI 后更新，`verifyRoborazziDebug` 常规比对。
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
@RunWith(AndroidJUnit4::class)
class ComponentSnapshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun homeTabHeader_withAction() {
        composeRule.setContent {
            CarroMedTheme {
                HomeTabHeader(
                    title = "今日清单",
                    actionIcon = Icons.Filled.Settings,
                    actionContentDescription = "系统设置",
                    onActionClick = {}
                )
            }
        }
        composeRule.onRoot().captureRoboImage(File("src/test/snapshots/component/home_tab_header_with_action.png"))
    }

    @Test
    fun homeTabHeader_longTitle() {
        // 长标题不截断是顶栏规格的硬约束（4 Tab 切换标题不跳动）
        composeRule.setContent {
            CarroMedTheme {
                HomeTabHeader(
                    title = "系统特权自检与保活指引",
                    actionIcon = Icons.Filled.Settings,
                    actionContentDescription = "系统设置",
                    onActionClick = {}
                )
            }
        }
        composeRule.onRoot().captureRoboImage(File("src/test/snapshots/component/home_tab_header_long_title.png"))
    }
}
