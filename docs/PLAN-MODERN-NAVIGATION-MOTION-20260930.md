# CarroMed 现代化导航体系与转场动效演进方案

> **文档代号**：PLAN-MODERN-NAVIGATION-MOTION-20260930  
> **创建日期**：2026-09-30  
> **实施状态**：方案草案（等待评审，不改动代码）  
> **相关基线**：Android 15 (API 35) · Compose BOM 2024.10.01 · Navigation Compose 2.8.3 · Material 3 1.3  

---

## 一、背景与现状诊断

CarroMed 当前定位为高可靠、隐私优先的本地用药与库存管理应用，在领域层逻辑、本地事务一致性、Room 局部更新、精确闹钟自愈等方面拥有严密的工程保障。然而，在 **UI 转场动效、路由架构与页面选项卡** 层面，实现仍然停留在 Jetpack Compose 早期形态，与当前 Android 14/15 官方标准及 Material 3 动效规范存在显著代差。

### 现状四大技术债与体验断层

1. **转场动效全关，呈现生硬硬切（Hard Cut）**
   * **现状代码**（`AppNavigation.kt:121-122`）：
     ```kotlin
     NavHost(
         navController = navController,
         startDestination = Screen.Today.route,
         modifier = Modifier.fillMaxSize(),
         enterTransition = { EnterTransition.None },
         exitTransition = { ExitTransition.None }
     )
     ```
   * **影响**：进入二级页、返回上一页、切换底栏四大 Tab，界面均为 0ms 瞬间跳变。没有任何层级（Depth）或空间方位（Directionality）隐喻，在现代高刷屏（90Hz/120Hz）设备上体验极其突兀。

2. **预测性返回（Predictive Back）完全失效**
   * **现状**：项目 `compileSdk = 35`、`targetSdk = 35`，属于 Android 15 原生应用。但在 `AndroidManifest.xml` 中未显式声明 `android:enableOnBackInvokedCallback="true"`，且由于根 `NavHost` 强制设定了 `EnterTransition.None`，Navigation Compose 2.8 原生支持的预测性返回手势（跟手拖拽缩略卡片预览下层）被完全扼杀。

3. **路由停留在 2.7 以前的旧版字符串模板（Legacy String-based Routes）**
   * **现状代码**（`Screen.kt` / `AppNavigation.kt`）：
     使用 `"med_detail/{medId}"`、`"dose_detail?slotId={slotId}&recordId={recordId}"` 等手写 URL 字符串，配合大量的 `navArgument(...)` 声明与 `backStackEntry.arguments?.getLong(...)` 手动判空取值。
   * **影响**：依赖易碎的 URL 字符串拼装与解析，缺乏编译期类型安全保障；重构时若修改参数，无法在编译阶段暴露漏改或类型错配。

4. **选项卡手工拼凑，全工程零 Pager 体验**
   * **现状代码**（`CabinetScreen.kt:159-197`、`ProgressScreen.kt:164-195`）：
     药箱的双分组（在服 / 归档）与进展页的双视图（矩阵 / 流水），均使用 `Row` + `Box` + `clickable` 手工绘制胶囊背景。
   * **影响**：
     * **无无障碍语义**：缺少 `Role.Tab` 和 `selected` 语义，无障碍读屏器（TalkBack）无法识别其为选项卡组件；
     * **零滑动交互**：全工程没有引入 `HorizontalPager`，内容切换仅靠 `if-else` 分支直接重组渲染，用户无法左右滑动手势切页，指示器也无法跟随手指拖动进行物理插值。

---

## 二、架构原则与底线守护（Invariants & Guardrails）

在现代化重构过程中，必须恪守以下四条铁律，绝不因追求视觉动效而破坏已建立的工程稳定性基石：

1. **领域与持久层绝对不动**：
   `core/domain` 零 `android.*` 依赖铁律不变；Room 本地事务、库存流水单向扣减、闹钟 `USE_EXACT_ALARM` / `AlarmReconciler` 对账链路保持原状。现存全部 JVM 单元测试（420 项）必须持续全绿。
2. **坚守全屏尺寸恒定，严防 Double Insets 与 FAB 下弹回归**：
   * 2026-09-30 修复过的重大布局陷阱（`NavHost` 随 `bottomBar` 显示/隐藏而跳变容器高度，导致今日页 FAB 下弹 80dp+）绝不可再现；
   * `NavHost` 必须维持全屏撑满（`Modifier.fillMaxSize()`），底栏安全边距继续由 `MainTabContent` 在 Tab 内部统一消费；底栏本身的进出场动画不得挤压或改变主内容容器的尺寸。
3. **有限且高效的动效曲线，保障自动化测试稳定性**：
   * 所有转场动画时长控制在 200ms ~ 300ms 之间，采用 M3 标准的 `FastOutSlowInEasing` 或标准阻尼 Spring；
   * `SmokeNavigationTest` 和 `ComponentSnapshotTest` 依赖 `waitForIdle()`，必须确保没有无限循环的补间或悬空动画阻塞测试线程。
4. **与既有 UI 走查工具链无缝衔接**：
   `tools/app_screenshots.py` 依赖 `uiautomator dump` 获取无障碍树节点进行自动化点击与断言。改造后暴露的标准语义角色不得导致脚本在断言目标节点时超时或定位失败。

---

## 三、分阶段演进方案

为保证实施过程低风险、可步进、可独立验证，整体改造划分为三个核心阶段与一个进阶探索阶段：

```
Phase 1: 恢复 M3 转场动效与预测性返回 (AppNavigation 局部改动，解决硬切)
   │
   ▼
Phase 2: 迁移至 Navigation 2.8+ 类型安全导航 (Screen.kt 契约现代化，消灭字符串)
   │
   ▼
Phase 3: 选项卡升级与 HorizontalPager 手势引入 (Cabinet & Progress 交互升级)
   │
   ▼
Phase 4 (可选): 共享元素转场 (Shared Element Transition 深度视觉打磨)
```

---

### 阶段一：恢复 Material 3 转场动效与启用预测性返回

#### 1.1 启用 Android 14/15 预测性返回
在 `app/src/main/AndroidManifest.xml` 的 `<application>` 标签中显式添加：
```xml
<application
    android:name=".CarroMedApp"
    android:enableOnBackInvokedCallback="true"
    ...>
```

#### 1.2 建立 M3 空间转场矩阵（Motion Semantic Matrix）
移除 `NavHost` 顶层的 `EnterTransition.None` / `ExitTransition.None`，按页面空间隐喻配置专门的转场动画：

| 导航场景 | 对应路由 | 入场动画（Enter） | 离场动画（Exit） | 设计考量与语义 |
| :--- | :--- | :--- | :--- | :--- |
| **主底栏同级切换** | Today ↔ Cabinet ↔ Progress ↔ Stats | `fadeIn(animationSpec = tween(220, delayMillis = 80))` | `fadeOut(animationSpec = tween(180))` | **Fade Through**：同级页面不具备线性先后顺序，严禁左右横滑，采用微淡入淡出保持视觉平稳 |
| **父子层级下钻** | 药箱 → 药品详情 / 进展 → 服药历史 | `slideInHorizontally(initialOffsetX = { it }) + fadeIn(tween(250))` | `slideOutHorizontally(targetOffsetX = { -it / 3 }) + fadeOut(tween(200))` | **Shared Axis X**：新页面从右侧滑入，前驱页面向左微缩退入背景，建立明确的纵深层级 |
| **层级返回 (Pop)** | 药品详情 → 药箱 (含手势返回) | `slideInHorizontally(initialOffsetX = { -it / 3 }) + fadeIn(tween(200))` | `slideOutHorizontally(targetOffsetX = { it }) + fadeOut(tween(250))` | **跟手预测性退场**：配合系统边缘侧滑，当前页向右滑出，底层页面从左侧还原 |
| **模态表单下钻** | 新建用药 / 手动补录 / 补药入库 | `slideInVertically(initialOffsetY = { it / 4 }) + fadeIn(tween(300))` | `slideOutVertically(targetOffsetY = { it / 4 }) + fadeOut(tween(250))` | **Modal Sheet**：由下往上浮现，传达“填写完成后即关闭返回”的临时上下文语义 |

#### 1.3 底部导航栏平滑出退场
目前 `AppNavigation.kt` 中：
```kotlin
if (isTopLevel) {
    NavigationBar(...) { ... }
}
```
进入二级页面时，底栏在 1 帧内被直接从组合树卸载，视觉上呈现突兀闪现。
**改造方案**：使用 `AnimatedVisibility` 包裹 `NavigationBar`：
```kotlin
AnimatedVisibility(
    visible = isTopLevel,
    modifier = Modifier.align(Alignment.BottomCenter),
    enter = slideInVertically(
        initialOffsetY = { it },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
    ) + fadeIn(),
    exit = slideOutVertically(
        targetOffsetY = { it },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
    ) + fadeOut()
) {
    NavigationBar(...) { ... }
}
```
*注：由于 `NavHost` 全屏撑满并消费底栏高度由内部 `MainTabContent` 承担，底栏动画浮于内容之上，完全不会引发任何容器重排或高度跳动。*

---

### 阶段二：重构路由层为 Navigation 2.8+ 类型安全导航（Type-Safe Navigation）

CarroMed 当前已在 `build.gradle.kts` 中启用了 `kotlinx.serialization` 与 `navigation-compose:2.8.3`，具备了无缝升级类型安全路由的基础。

#### 2.1 路由契约定义（`Screen.kt`）
将基于字符串的 `sealed class Screen(val route: String)` 改造为基于 `@Serializable` 的多态路由层次：

```kotlin
package com.mcxiaoke.carromed.ui.navigation

import kotlinx.serialization.Serializable

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
    @Serializable data class AddEditMedication(val medId: Long? = null) : ScreenRoute
    @Serializable data class ManualDose(val medId: Long? = null) : ScreenRoute
    @Serializable data class DoseDetail(val slotId: Long? = null, val recordId: Long? = null) : ScreenRoute
    @Serializable data class Refill(val medId: Long) : ScreenRoute
    @Serializable data class MedHistory(val medId: Long) : ScreenRoute

    // 设置与自检
    @Serializable data object Settings : ScreenRoute
    @Serializable data object PermissionCheck : ScreenRoute
}
```

#### 2.2 `AppNavigation.kt` 的声明与解构现代化
消灭脆弱的 `navArgument(...)` 样板代码，使用标准 DSL：

```kotlin
// 注册与解析
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
    MedicationDetailScreen(...)
}

// 页面跳转呼叫方
navController.navigate(ScreenRoute.MedicationDetail(medId = med.id))
navController.navigate(ScreenRoute.DoseDetail(slotId = slot.id))
```

**收益**：
1. 完全消灭字符串 URL 拼接中可能发生的斜杠缺失、参数键名拼错等隐患；
2. 可空参数（如 `slotId` / `recordId`）由 Kotlin 自动处理，无须手动声明复杂的默认值参数树；
3. 导航目的地变更在编译期即可得到强类型校验。

---

### 阶段三：子页面选项卡现代化与 Pager 手势支持

#### 3.1 药箱页（`CabinetScreen`）交互升级
* **当前形态**：`uiState.selectedTab == 0` 控制 `LazyColumn` 显示在服列表还是归档列表，手写圆角 `Row` 胶囊。
* **现代化方案**：
  1. 引入 `val pagerState = rememberPagerState(initialPage = 0) { 2 }`；
  2. 选项卡组件采用 Material 3 官方推荐的 `SingleChoiceSegmentedButtonRow` + `SegmentedButton`（或 `SecondaryTabRow` + `Tab`）；
  3. 下方列表由 `HorizontalPager(state = pagerState)` 托管：
     * Page 0：正在服用药品卡片列表；
     * Page 1：已停药归档列表；
  4. 联动机制：点击分段按钮触发 `coroutineScope.launch { pagerState.animateScrollToPage(index) }`，用户左右滑屏时分段按钮选中态自动跟随，手感丝滑流畅。

#### 3.2 进展追踪页（`ProgressScreen`）交互升级
* **当前形态**：`ProgressTabSelector` 手写胶囊选择器，分支切换「遵从度矩阵」和「服药历史流水」。
* **现代化方案**：
  1. 采用相同的 `rememberPagerState { 2 }` + `HorizontalPager`；
  2. 矩阵视图（月历网格）与流水视图（分页无限列表）分别运行在独立的分页容器中；
  3. **优势**：各页面的滚动位置（`LazyListState`）在手势滑动切页时自然保留，不会因频繁重组销毁而丢失滚动锚点。

#### 3.3 无障碍（Accessibility）自动修复
升级为官方标准组件后，选项卡自动携带 `Modifier.selectable` 与 `Role.Tab`，TalkBack 读屏器能标准播报“当前选中：正在服用，第 1 项，共 2 项”，彻底解决视障支持缺陷。

---

### 阶段四（进阶可选）：共享元素转场（Shared Element Transition）

在 Compose 1.7+ 中，Google 正式推出了稳定的共享元素 API。

* **适用场景**：
  * 从「我的药箱」列表中的药品卡片，点击进入「药品专属详情页」；
  * 从「今日清单」的待服药槽位，点击进入「记录详情页」。
* **实施路径**：
  1. 在根 `AppNavigation` 外层包裹 `SharedTransitionLayout`；
  2. 在 `NavHost` 的 `composable` 作用域中，利用 `AnimatedVisibilityScope`；
  3. 卡片外框使用 `Modifier.sharedBounds(...)`，药名/规格使用 `Modifier.sharedElement(...)`。
* **评估建议**：
  该功能属于纯视觉增强（Eye Candy），不影响核心业务流。建议在前三个阶段完成并通过完整回归测试后，作为独立小特性单独立项实施。

---

## 四、风险评估与防回归策略

| 风险项 | 潜在危害 | 预防与防御策略 |
| :--- | :--- | :--- |
| **转场动画期间 Insets 计算抖动** | 页面滑入滑出瞬间，状态栏或导航栏高度出现 1 帧跳闪 | 维持既有原则：状态栏边距在各 Screen 的 TopAppBar 消费，底栏由 `MainTabContent` 统一消费；转场动画不改变 Inset 消费所有权。 |
| **单元测试 / Robolectric 耗时漂移** | 开启 Compose 动画可能导致部分单测执行变慢或超时 | 项目中 420 项 JVM 单测位于 JVM 内存运行，均直接针对 ViewModel 和 Domain 纯逻辑，不调用 UI 渲染；UI 测试采用 `composeRule.waitForIdle()`，在快进时钟下会自动平滑结算。 |
| **UI 走查脚本（app_screenshots.py）点击错位** | 动画未完成时 `uiautomator dump` 获取到半透明或位移中的节点 | 保持合理的快节奏动画时长（200~250ms）；走查脚本定位节点时自带 `wait_for` 稳定重试机制；标准组件自带无障碍锚点，使节点查找更加健壮。 |
| **内存与重组开销（Pager 嵌套）** | `HorizontalPager` 若未合理配置缓存，可能导致两页重复触发不必要重组 | 页面内 ViewModel 状态通过 `StateFlow` 单向派发，列表项已具备唯一稳定的 `key`，Pager 内部页面仅复用状态，不额外触发网络或重度计算。 |

---

## 五、分步验收标准（Checklist）

### 阶段一验收标准
- [ ] 在 Android 14/15（模拟器或实机）上，从二级页面侧滑，能看到平滑的“预测性返回卡片缩略”手势动画；
- [ ] 主底栏 4 个 Tab 切换平滑（Fade Through），无跳动、无横向穿帮；
- [ ] 列表进入二级详情呈现自右向左的平滑滑入，返回自左向右滑出；
- [ ] 今日页 FAB 在底栏出退场全程高度锁死，无跳动下沉；
- [ ] `./gradlew testDebugUnitTest` 保持全绿；
- [ ] `SmokeNavigationTest` 冒烟测试全通。

### 阶段二验收标准
- [ ] `Screen.kt` 中字符串 URL 彻底清理，全部 12 个页面使用 `@Serializable` 路由；
- [ ] `AppNavigation.kt` 中无任何手动 `navArgument` 和 `backStackEntry.arguments` 解析；
- [ ] Debug 与 Release 两个 Variant 均可顺利编译打包；
- [ ] 运行 `python tools/app_screenshots.py --clear --seed`，全量页面走查清单 `manifest.md` 100% 通过。

### 阶段三验收标准
- [ ] 药箱页支持左右滑动切换「在服 / 归档」，分段胶囊高亮块跟随手指实时插值平移；
- [ ] 进展页支持左右滑动切换「矩阵 / 流水」，切页后双侧列表滚动高度不丢失；
- [ ] 开启 TalkBack 旁白测试，分段选项卡能被正确读出角色与选中状态。

---

## 六、结语与结论

本方案完全基于 Android 15 与 Jetpack Compose 官方最新设计演进规范制定，充分吸收了 CarroMed 既有架构的防御性设计经验。整个方案**不触碰领域层核心逻辑，不改动数据库与闹钟调度，只做展现层现代化升级**。各阶段解耦清晰，兼顾了极致的交互体验与工业级的稳定性保障。
