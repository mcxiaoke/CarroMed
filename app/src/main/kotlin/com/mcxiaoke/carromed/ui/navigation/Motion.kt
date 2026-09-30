package com.mcxiaoke.carromed.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically

/**
 * 确定性转场动效规格集合。
 *
 * 核心设计纪律（PLAN-MODERN-NAVIGATION-MOTION-20260930-v2 §1.2）：
 * 1. 禁用无界弹簧（spring），全量使用显式 `tween + FastOutSlowInEasing`；
 * 2. 时长严格限定在 180ms ~ 250ms，保证在高刷屏上利落顺畅且不阻塞测试；
 * 3. 预测性返回期间手势只驱动 `popExit`，`popEnter` 严格保持静止，彻底杜绝双层反向漂移与重影。
 */
object MotionSpec {
    const val DURATION_FAST = 180
    const val DURATION_NORMAL = 220
    const val DURATION_MODAL = 250

    val MotionEasing = FastOutSlowInEasing

    // ---------- 1. 主底栏同级切换：Fade Through (M3 标准规范) ----------
    // 进场：轻微放大 (0.96 -> 1.0) 并淡入
    val tabEnter: EnterTransition =
        scaleIn(initialScale = 0.96f, animationSpec = tween(DURATION_NORMAL, easing = MotionEasing)) +
            fadeIn(animationSpec = tween(DURATION_NORMAL, easing = MotionEasing))

    // 出场：仅淡出，避免与进场页面重叠造成脏影
    val tabExit: ExitTransition =
        fadeOut(animationSpec = tween(DURATION_FAST, easing = MotionEasing))

    // ---------- 2. 二级页面父子层级跳转 (Shared Axis X) ----------
    // 前进进入：从右侧 100% 滑入 + 淡入
    val secondaryEnter: EnterTransition =
        slideInHorizontally(
            initialOffsetX = { fullWidth -> fullWidth },
            animationSpec = tween(DURATION_NORMAL, easing = MotionEasing)
        ) + fadeIn(animationSpec = tween(DURATION_NORMAL, easing = MotionEasing))

    // 前进离场：向左微推 (仅退 20%，不露大面积底色) + 淡出
    val secondaryExit: ExitTransition =
        slideOutHorizontally(
            targetOffsetX = { fullWidth -> -fullWidth / 5 },
            animationSpec = tween(DURATION_FAST, easing = MotionEasing)
        ) + fadeOut(animationSpec = tween(DURATION_FAST, easing = MotionEasing))

    // ---------- 3. 预测性返回 / Pop 返回 (跟手退场) ----------
    // 底层恢复（popEnter）：严格保持静止！绝不反向位移，仅轻微淡入，杜绝视觉打架
    val secondaryPopEnter: EnterTransition =
        fadeIn(animationSpec = tween(DURATION_FAST, easing = MotionEasing))

    // 顶层退场（popExit）：向右 100% 滑出，由手势进度驱动
    val secondaryPopExit: ExitTransition =
        slideOutHorizontally(
            targetOffsetX = { fullWidth -> fullWidth },
            animationSpec = tween(DURATION_NORMAL, easing = MotionEasing)
        ) + fadeOut(animationSpec = tween(DURATION_FAST, easing = MotionEasing))

    // ---------- 4. 模态表单下钻 (新建用药 / 手动补录) ----------
    val modalEnter: EnterTransition =
        slideInVertically(
            initialOffsetY = { fullHeight -> fullHeight / 4 },
            animationSpec = tween(DURATION_MODAL, easing = MotionEasing)
        ) + fadeIn(animationSpec = tween(DURATION_MODAL, easing = MotionEasing))

    val modalExit: ExitTransition =
        slideOutVertically(
            targetOffsetY = { fullHeight -> fullHeight / 4 },
            animationSpec = tween(DURATION_FAST, easing = MotionEasing)
        ) + fadeOut(animationSpec = tween(DURATION_FAST, easing = MotionEasing))

    // ---------- 5. 底栏平滑进出场 ----------
    val navBarEnter: EnterTransition =
        slideInVertically(
            initialOffsetY = { it },
            animationSpec = tween(DURATION_NORMAL, easing = MotionEasing)
        ) + fadeIn(animationSpec = tween(DURATION_NORMAL, easing = MotionEasing))

    val navBarExit: ExitTransition =
        slideOutVertically(
            targetOffsetY = { it },
            animationSpec = tween(DURATION_FAST, easing = MotionEasing)
        ) + fadeOut(animationSpec = tween(DURATION_FAST, easing = MotionEasing))
}
