package com.mcxiaoke.carromed.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow

/**
 * 全应用统一顶栏（PLAN-TITLEBAR-STANDARDIZATION-20260930.md）。
 *
 * ## 为什么必须是官方 `TopAppBar`
 *
 * 旧实现是本文件里一个固定在 64dp 的 `Row`（`HomeTabHeader`），被当成
 * `LazyColumn` 的第一个 `item` 用。那带来两个结构性问题：
 *
 * 1. **状态栏 inset 归因错位** —— 官方 `Scaffold` 只在顶栏走 `topBar` 槽位时
 *    才把状态栏算进 `topBarHeight`；把手绘顶栏塞进列表，`Scaffold` 认不出它，
 *    `innerPadding.top` 会等于状态栏高度，页面再自加一次 `.statusBarsPadding()`
 *    就**把状态栏计了两次**（今日清单实测偏下 63px = 24dp）。
 * 2. **顶栏会随列表滚出屏幕** —— 列表 item 没有任何吸顶容器，滚一屏后标题与
 *    右上按钮整条消失，用户在列表深处既不知道自己在哪个 Tab，也够不到操作入口。
 *
 * 官方 `TopAppBar` 自己消费「顶部 + 水平」inset，放进 `Scaffold.topBar` 后
 * `innerPadding.top` 归零，**结构上不可能再重复**。
 *
 * ## 使用纪律
 *
 * - **不要传 `windowInsets`**：官方默认值是 `systemBarsForVisualComponents`
 *   （含水平方向）。显式写成 `WindowInsets.statusBars` 会丢掉水平 inset，
 *   横屏 / 侧边导航栏 / 侧边挖孔时标题会贴边。
 * - **调用方不要再加 `.statusBarsPadding()`**：消费 `innerPadding` 即可。
 * - 标题排版不显式指定 `style`，继承 `TopAppBarDefaults` 的 `titleLarge`（22sp）
 *   —— 这是 M3 小号 app bar 的规格，也是全应用 14 个页面标题字号一致的唯一前提。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CarroMedTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    /** 副标题走 `bodySmall`（与提醒设置 / 库存管理两页的写法一致） */
    subtitle: String? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    actionIcon: ImageVector? = null,
    actionContentDescription: String? = null,
    onActionClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    TopAppBar(
        title = {
            // maxLines = 1：顶栏高度必须恒定，长标题只能省略号收尾，不能换行撑高
            // —— 否则切换到标题更长的 Tab 时整条顶栏会变高、内容整体下移。
            if (subtitle == null) {
                TitleText(title)
            } else {
                Column {
                    TitleText(title)
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        navigationIcon = navigationIcon ?: {},
        actions = {
            actions()
            if (actionIcon != null) {
                IconButton(onClick = { onActionClick?.invoke() }) {
                    Icon(imageVector = actionIcon, contentDescription = actionContentDescription)
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background
        ),
        modifier = modifier
    )
}

@Composable
private fun TitleText(title: String) = Text(
    text = title,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    modifier = Modifier.testTag(TestTags.TOP_BAR_TITLE)
)