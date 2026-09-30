# CarroMed 现代化导航体系与转场动效演进方案（v2 修订版）

> **文档代号**：PLAN-MODERN-NAVIGATION-MOTION-20260930-v2  
> **修订日期**：2026-09-30 (GMT+8)  
> **修订依据**：吸收 [`PLAN-REVIEW-20260930-modern-nav-motion.md`](file:///c:/Home/Projects/CarroMed/docs/PLAN-REVIEW-20260930-modern-nav-motion.md) 全部评审意见，彻底剔除实验性 API 与高风险结构改造，按「先量测、抽规范、小步进、可证伪」原则重构  
> **实施状态**：方案草案（等待评审，不改动任何源码）  
> **技术基线**：Android 15 (targetSdk 35) · Compose BOM 2024.10.01 · Navigation Compose 2.8.5+ · Material 3 1.3  

---

## 〇、方案设计核心原则：可靠、极简、可预测

针对 v1 方案中存在的「诊断偏差、弹簧时长不可控、Pager 破坏列表结构及走查脚本、类型安全路由迁移存在静默破坏地雷」等问题，本 v2 方案确立以下四条硬性纪律：

1. **零高风险结构改造**：
   * **彻底删除实验性「共享元素转场（Shared Element）」**：API 仍为 `@ExperimentalSharedTransitionApi`，根容器包装会引发全树 bounds 计算开销，ROI 极低，不予引入。
   * **子页面选项卡放弃激进的 `HorizontalPager`**：药箱与进展页本质是数据视图切换（Filter Switch），且 Tab 位于 `LazyColumn` 头部。改用 M3 规范的 `SingleChoiceSegmentedButtonRow` + `Crossfade`，**零结构侵入**，既补齐无障碍 `Role.Tab` 又消除硬切，彻底避免破坏滚动状态和击穿走查脚本。
2. **动效时长 100% 确定性，彻底禁用无界 Spring**：
   * 所有转场动效显式声明 `animationSpec = tween(..., easing = FastOutSlowInEasing)`，严格锁定在 200ms ~ 250ms 之间，彻底封杀默认的 `spring` 弹簧，确保 `SmokeNavigationTest`（`waitForIdle()`）和 UI 走查工具链稳定无抖动。
3. **修复手势单向性，守住预测性返回标准形态**：
   * 预测性返回期间，`NavHost` 由手势进度驱动 `popExit`；
   * **`popEnter` 严格保持静止（无位移漂移）**，杜绝底层页面反向滑动与脏边重影。
4. **单测守门，防御类型安全路由三大“静默地雷”**：
   * 路由字符串经核实仅在 `Screen.kt` 与 `AppNavigation.kt` 内部闭环（共 52 处，外部 0 引用）；
   * 在迁移到 `@Serializable` 前，必须先落地 `NavigationRouteContractTest` 单元测试，钉死 `destination.hasRoute<T>()`、`isTopLevel` 以及测试标签映射，防止底栏在编译通过的情况下“静默消失”。

---

## 一、现状与根因校准（精准诊断）

| 模块 | 真实技术现状 | 真实根本原因 | 修复正解（v2） |
| :--- | :--- | :--- | :--- |
| **预测性返回** | 手势期间无跟随，松手直接硬切 | `AppNavigation.kt:121-122` 显式设置了 `EnterTransition.None`，抑制了 `PredictiveBackHandler` 的过渡插值（`targetSdk=35` 下 Manifest 默认即为 true，无须改 Manifest） | 在 `Motion.kt` 中为 `popExit` 配置标准横向退场过渡，`popEnter` 保持静止 |
| **底层库稳定性** | 当前锁定 `navigation-compose:2.8.3` | 存在已知上游崩溃 `b/375343407`（返回栈仅剩 1 项时并发触发返回必崩） | 升级至 `navigation-compose:2.8.5+` |
| **页面内选项卡** | `Cabinet` / `Progress` 手写圆角 `Row` 胶囊，硬分支渲染 | 早期原型手写代码，缺少 Material 3 标准组件支持；无 `Role.Tab` 语义 | 替换为 `SingleChoiceSegmentedButtonRow` + `SegmentedButton`，内容过渡接入 `Crossfade` |
| **路由安全性** | 字符串模板拼接与手动提取参数（52 处） | 历史遗留 Navigation 2.7 以前的旧实现，样板代码繁重且无编译期类型约束 | 引入 `@Serializable` 契约，以 `hasRoute<T>()` 替换字符串匹配 |

---

## 二、三阶段稳健实施路径

整体改造拆分为三个完全独立、步步可测、风险收敛的阶段：

```
Step 1: 升级 navigation-compose 2.8.5 + 抽离统一 Motion 规范 (解决硬切与预测性返回)
   │   （产物：ui/navigation/Motion.kt，零业务逻辑改动，直接看图）
   ▼
Step 2: 建立导航契约单测 + 迁移至 Navigation 2.8+ 类型安全路由 (消灭字符串债)
   │   （产物：ScreenRoute 契约，单测保住 isTopLevel 与底栏可见性）
   ▼
Step 3: 页面内选项卡升级 M3 SegmentedButton + Crossfade (无障碍与视图平滑切换)
   │   （产物：CabinetScreen / ProgressScreen 局部视图微调，零列表结构手术）
```

---

### 阶段一：库版本升级与规范化转场动效（`Motion.kt`）

#### 1.1 升级依赖（消除 `b/375343407` 崩溃）
在 `app/build.gradle.kts` 中：
```kotlin
// 升级至 2.8.5+，吸收 NavHost PredictiveBackHandler 在栈底返回时的崩溃修复
implementation("androidx.navigation:navigation-compose:2.8.5")
```

#### 1.2 建立确定性动效规范（`ui/navigation/Motion.kt`）
新增专门的动效管理文件，集中维护有限时长的 `tween` 动效，严禁无界 `spring`：

```kotlin
package com.mcxiaoke.carromed.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically

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

    // ---------- 5. 底栏渐入渐出 ----------
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
```

#### 1.3 `AppNavigation.kt` 接入配置
在 `NavHost` 中，彻底移除全局 `EnterTransition.None` / `ExitTransition.None`，全量覆盖所有 12 个目的地：
* **四大主 Tab**：`enterTransition = { MotionSpec.tabEnter }`, `exitTransition = { MotionSpec.tabExit }`, `popEnterTransition = { EnterTransition.None }`；
* **二级详情与功能页**（含 `MedicationDetail`、`ReminderSettings`、`Inventory`、`DoseDetail`、`MedHistory`、`Settings`、`PermissionCheck`）：
  使用 `secondaryEnter` / `secondaryExit` / `secondaryPopEnter` / `secondaryPopExit`；
* **新增与补录页面**（`AddEditMedication` 处于新增状态、`ManualDose`）：使用 `modalEnter` / `modalExit`；
* **底栏 `NavigationBar` 进出场**：使用 `AnimatedVisibility(visible = isTopLevel, enter = MotionSpec.navBarEnter, exit = MotionSpec.navBarExit)`，由于 `MainTabContent` 依然恒定锁定尺寸，底栏完全不引起任何布局跳动。

---

### 阶段二：类型安全路由迁移与单测防御

此阶段爆炸半径明确闭环在 `Screen.kt` 与 `AppNavigation.kt`。

#### 2.1 编写防静默失效单元测试（`NavigationRouteContractTest.kt`）
在动手改任何业务代码前，在 `app/src/test/kotlin/...` 新建纯 JVM 契约测试：
1. **测试 `isTopLevel` 判定函数**：传入所有 12 个页面路由，断言仅 4 个主 Tab 返回 `true`，其余 8 个二级页恒返回 `false`；
2. **测试 `tabTag` 匹配函数**：断言 4 个主 Tab 准确映射到 `TestTags.TAB_TODAY` 等标识；
3. **测试参数解构**：断言无参和带参路由的序列化一致性。

#### 2.2 路由契约定义（`Screen.kt`）
基于 `@Serializable`，消除可空参数岐义，拆分清晰的目的地：

```kotlin
package com.mcxiaoke.carromed.ui.navigation

import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

sealed interface ScreenRoute {
    // 底部 4 个主 Tab
    @Serializable data object Today : ScreenRoute
    @Serializable data object Cabinet : ScreenRoute
    @Serializable data object Progress : ScreenRoute
    @Serializable data object Stats : ScreenRoute

    // 二级全屏与详情
    @Serializable data class MedicationDetail(val medId: Long) : ScreenRoute
    @Serializable data class ReminderSettings(val medId: Long) : ScreenRoute
    @Serializable data class Inventory(val medId: Long) : ScreenRoute
    
    // 拆分新建与编辑（彻底消除 0 与 null 的哨兵值混淆）
    @Serializable data object AddMedication : ScreenRoute
    @Serializable data class EditMedication(val medId: Long) : ScreenRoute
    
    @Serializable data object ManualDose : ScreenRoute
    @Serializable data class ManualDoseWithMed(val medId: Long) : ScreenRoute
    
    @Serializable data class DoseDetailForSlot(val slotId: Long) : ScreenRoute
    @Serializable data class DoseDetailForRecord(val recordId: Long) : ScreenRoute
    
    @Serializable data class Refill(val medId: Long) : ScreenRoute
    @Serializable data class MedHistory(val medId: Long) : ScreenRoute

    @Serializable data object Settings : ScreenRoute
    @Serializable data object PermissionCheck : ScreenRoute
}
```

#### 2.3 `AppNavigation.kt` 安全重构
* **核心防线**：使用 Navigation 2.8 的 `destination.hasRoute<T>()` 或类型集合判断：
  ```kotlin
  val TopLevelDestinations: Set<KClass<out ScreenRoute>> = setOf(
      ScreenRoute.Today::class,
      ScreenRoute.Cabinet::class,
      ScreenRoute.Progress::class,
      ScreenRoute.Stats::class
  )

  // 绝不用 route 字符串匹配，直接用类型判断：
  val isTopLevel = TopLevelDestinations.any { destination.hasRoute(it) }
  ```
* `BottomNavItem` 数据类改造为持有目标类型或对应实例，`composable<ScreenRoute.X>` 彻底移除 `navArgument(...)` 样板解析。

---

### 阶段三：子页面选项卡现代化（M3 SegmentedButton + Crossfade）

本阶段明确**不上 `HorizontalPager`**，不对现有 `LazyColumn` 进行大手术，仅做展现与组件层升级：

#### 3.1 药箱页（`CabinetScreen.kt`）改造
1. 将 `item { Row(...) }` 内的手写胶囊替换为 Material 3 官方推荐的 `SingleChoiceSegmentedButtonRow` + `SegmentedButton`；
2. 保持在 `CabinetViewModel.selectTab(index)` 驱动的状态模型；
3. 内容列表处使用 `Crossfade(targetState = uiState.selectedTab, animationSpec = tween(200))` 包裹渲染，提供平滑淡入淡出过渡；
4. 获得完整的无障碍 `Role.Tab`，TalkBack 读屏完美识别。

#### 3.2 进展追踪页（`ProgressScreen.kt`）改造
1. 同样将 `ProgressTabSelector` 升级为 `SingleChoiceSegmentedButtonRow` + `SegmentedButton`；
2. 视图切换（日历矩阵 vs 流水列表）采用 `Crossfade`；
3. 保留原有单 `LazyColumn` 和统一的 `listState` 触底加载逻辑，**零破坏既有分页加载逻辑**；
4. 保持 `tools/app_screenshots.py` 查找“服药流水”文本的屏幕内坐标有效性，100% 免疫离屏越界点击 Bug。

---

## 三、风险核对与不可动摇的防御（Verification Matrix）

| 潜在隐患 | 危害程度 | 方案采用的硬核防御手段 |
| :--- | :--- | :--- |
| **`destination.route` 变更导致底栏消失** | 极高（P0 事故） | 1. 废除任何对 `destination.route` 的字符串判定；<br>2. 改用 `destination.hasRoute(it)`；<br>3. 前置运行 `NavigationRouteContractTest` 严格校验真值表。 |
| **转场动画导致自动化测试超时或抖动** | 高（CI 阻塞） | 1. 禁用无界 `spring`，全量固定为 200~250ms `tween`；<br>2. 统一使用 `FastOutSlowInEasing`；<br>3. `SmokeNavigationTest` 依赖 `waitForIdle()` 自动无损结算。 |
| **走查脚本击穿（越界点击无效）** | 中（工具链失效） | 坚决放弃 `HorizontalPager`，采用原位 `Crossfade`，所有候选节点物理坐标恒在 `[0, 1080]` 视口内。 |
| **返回手势双层重影** | 低（UI 脏边） | `popEnter` 严格固定为静态（无位移），仅由 `popExit` 承载跟手滑动退出。 |

---

## 四、分步验收标准（Checklist）

### Step 1 验收（动效与版本升级）
- [ ] `./gradlew testDebugUnitTest` 保持全绿（455 项测试全部通过）；
- [ ] 在模拟器上慢速侧滑返回，页面平滑跟随手指右滑退场，底层页面平稳无重影；
- [ ] 快速连续点击返回键至首页，应用不发生 `b/375343407` 崩溃；
- [ ] 四大主 Tab 切换为平稳的 Fade Through，无横滑穿帮；
- [ ] `SmokeNavigationTest` 冒烟测试全通；
- [ ] 运行 `python tools/app_screenshots.py --clear --seed`，全量走查通过。

### Step 2 验收（类型安全路由）
- [ ] `NavigationRouteContractTest` 单元测试通过；
- [ ] `Screen.kt` 与 `AppNavigation.kt` 中 52 处字符串路由彻底清除；
- [ ] 底栏在四大主 Tab 正常显示，进入二级页后平滑滑出隐藏，无闪变；
- [ ] Debug 与 Release 构建均正常编译。

### Step 3 验收（选项卡组件化）
- [ ] 药箱与进展页采用 M3 `SegmentedButton`，切换有平滑渐变，无突兀跳帧；
- [ ] 列表上滑下滑、分页触底加载行为与改动前完全一致；
- [ ] TalkBack 读屏能够清晰播报“选项卡：正在服用，第 1 项，共 2 项，已选中”。
