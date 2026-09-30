# 方案评审：`PLAN-MODERN-NAVIGATION-MOTION-20260930.md`

> **文档代号**：PLAN-REVIEW-20260930-modern-nav-motion
> **评审对象**：`docs/PLAN-MODERN-NAVIGATION-MOTION-20260930.md`（现代化导航体系与转场动效演进方案）
> **评审时间**：2026-09-30 20:44 (GMT+8)
> **评审方式**：逐条核对方案的事实性断言 → 源码 / 构建脚本 / 上游 release notes；未改动任何源码
> **评审结论**：**方向正确，但不建议按原顺序开工。** 事实层 5 处硬错、设计层 3 处会自相打架或引入回归、验收标准多条不可证伪。

---

## 〇、总体判断

方案的动机（消除硬切、类型安全路由、无障碍）是正确的，四条底线守护（`core/domain` 零 `android.*`、全屏尺寸恒定、动效有限时长、走查工具链衔接）也立得对。
问题集中在**手段层**：诊断与修复对不上号、示例代码违反方案自己的不变量、「空间隐喻矩阵」在 Navigation Compose 的 API 里表达不出来、以及验收标准里混进了恒真条件。

方案里最值得肯定的一点它自己没意识到：**Phase 2 的爆炸半径极小**（见 §三.10），这本该是「可以放心做」的硬依据。

---

## 一、事实性错误（有证据）

### 1. 预测性返回的诊断和修复都错了

**方案原文**：§1 现状 2 称因未声明 `android:enableOnBackInvokedCallback="true"`，且 §1.1 提出加这个声明来修复。

**事实**：`android:enableOnBackInvokedCallback` 在 `targetSdkVersion ≥ 34` 时**默认就是 `true`**
（官方 `<application>` 元素文档；Android Rust `android-manifest` 文档亦写明
"The default value is `true` if you've set either `minSdkVersion` or `targetSdkVersion` to `14` or higher"）。
本项目 `targetSdk = 35`（`app/build.gradle.kts:27`）。

⇒ **§1.1 的 manifest 改动是空操作。**

**真实原因**：navigation 2.8 的 `NavHost` 内置注册了 `PredictiveBackHandler`（release notes 2.8.0-alpha06 / beta04 多次修补其行为），系统的默认「卡片缩略」动画因此被抑制；而项目把转场设成 `None`（`AppNavigation.kt:121-122`），手势期间无事可做 → 松手硬切。
**修法在 §1.2（给 `popExit`/`popEnter` 真实转场），不在 §1.1。**

**连带一条**：§5 阶段一验收写「能看到平滑的**预测性返回卡片缩略**手势动画」。
「卡片缩略」是 Android 13 时代系统级动画的形态。Navigation Compose 2.8 的预测性返回是**按目的地级转场被手势进度驱动**的，不会有卡片缩略。照这条验收，实现者会去找一个不存在的东西。

### 2. 漏掉 navigation-compose 2.8.3 的已知崩溃

Navigation **2.8.5** release notes 明确：

> Fixed an issue where `NavHost` could throw an exception inside of the `PredictiveBackHandler`
> if the back stack is popped down to 1 entry and a system back are triggered in the same frame.
> （[b/375343407](https://issuetracker.google.com/issues/375343407)）

项目锁 `androidx.navigation:navigation-compose:2.8.3`（`app/build.gradle.kts:112`）。
本方案的全部目的就是把预测性返回变成主力交互，却没看一眼上游 changelog ——
**改之前查一次就能省掉一整轮回归的事。**

### 3. Phase 4 说共享元素 API「正式推出稳定」是错的

方案原文：「在 Compose 1.7+ 中，Google 正式推出了稳定的共享元素 API」。

**事实**：`SharedTransitionLayout` / `Modifier.sharedElement` / `Modifier.sharedBounds`
至今仍标注 `@ExperimentalSharedTransitionApi`（Google I/O 2024 官方文章原文即写 "Shared element support is experimental"）。
本项目 Compose BOM 2024.10.01 = Compose 1.7.5，必须 opt-in。

「稳定」三个字把 API 破坏性变更风险写进了立项依据。

### 4. 示例代码违反方案自己的 §2.3 不变量

`slideInHorizontally` / `slideInVertically` 的默认 `animationSpec` 是
**`spring(stiffness = Spring.StiffnessMediumLow)`（无界时长）**，不是 tween。

- §1.2 矩阵里所有 slide 只给了 `fadeIn(tween(...))`，**漏掉 slide 自己的 `animationSpec`**
  → 实际时长由弹簧决定 → 直接推翻「所有转场 200~300ms」这条**专门用来保障测试稳定性**的保证（§2.3）。
- §1.3 底栏 `AnimatedVisibility` 更直接：显式写了 `spring(stiffness = Spring.StiffnessMediumLow)`，
  与 §2.3 同一条不变量正面冲突。

### 5. 「420 项」是旧数

`docs/CHANGES-20260929.md:153` 已写到 **455 项 / 49 套全绿**（449 之后又加过）。
方案里出现两次（§二.1 与 §四风险表），顺手改掉。

---

## 二、设计层：「空间隐喻矩阵」在 Navigation Compose 的 API 里表达不出来

### 6. 每个目的地只有一组转场，而「场景」是按 (来源, 目标) 定义的

`composable` 只能给一组 `enterTransition` / `exitTransition` / `popEnterTransition` / `popExitTransition`。

而 §1.2 矩阵第一行「主底栏同级切换 fade」和第三行「层级返回」作用在**同一批**目的地
（`Today` / `Cabinet` / `Progress` / `Stats`）上：
`Today` 既是 tab 切换的目标，又是从 `Settings` 返回时的 `popEnter` 目标。

**结论**：它**能**分清（`enter` 给 tab 切换、`popEnter` 给从二级页返回），
但方案没意识到要按「一个目的地有两个角色」分派。写成一张给人看的表，实现时必然反复拧巴。

**必须落成代码里的两组函数，而不是文档里的一张表。**

### 7. §1.2「层级返回」其实不是跟手的

方案写底层页 `slideInHorizontally(initialOffsetX = { -it / 3 })`，
即返回过程中**底层页面自己在往右挪**，并称之为「跟手预测性退场」。

预测性返回期间 `NavHost` 用手势进度驱动 `popExit`。此时底层页再叠一个自己的位移，
**两层运动不同源** —— 视觉上就是背景在反向漂。

M3 / Compose 对全屏目的地的预测性返回约定是：
**只有顶层跟手指，底层保持静止（可做 scale / fade），松手后再 settle。**

⇒ 跟手位移必须挂在 `popExit` 上、由手势进度驱动；
**`popEnter` 应当恒为静止。**

**顺带一个纯技术问题**：`slideOut(targetOffsetX = { -it / 3 })` 会让画面左侧露出宽 1/3 屏的窗口底色，
再叠 `fadeOut` 就变成「透过半透明看到底色」—— 高刷屏上是一帧一帧的脏边。退出偏移应更小，或去掉 exit 的 fade。

### 8. 矩阵有覆盖漏洞

以下目的地不在表里：

| 目的地 | 问题 |
| :--- | :--- |
| `DoseDetail` | **全应用点得最多的二级页**（今日三分区、进展流水、单药历史三处入口），却完全没定义 |
| `PermissionCheck` | 无定义 |
| `AddEditMedication` | 既是「模态」又是「编辑既有」；从 `MedicationDetail` 进入时不该从下往上浮 |

§5 也没有一条验收覆盖 `DoseDetail`。

---

## 三、Phase 2（类型安全路由）：真正的风险点一条都没提

### 9. `destination.route` 会变成序列化描述符串，三处依赖会静默失效

改完之后，`currentBackStackEntryAsState()?.destination?.route` 不再是 `"today"`，
而是形如 `com.mcxiaoke.carromed.ui.navigation.ScreenRoute.Today`。

受影响的全在同一个文件里，而且**改完能编译、单测全绿、只是行为变了**：

| 位置 | 失效后果 |
| :--- | :--- |
| `AppNavigation.kt:96-98` `isTopLevel` | 恒为 `false` → **底栏永不出现** |
| `AppNavigation.kt:86-90` `BottomNavItems(route: String)` | 需改泛型 / `KClass` + `navigate<T>()` |
| `AppNavigation.kt:399-405` `tabTag` 的 `when(item.route)` | 匹配不到 → `TestTags` 全丢 → `SmokeNavigationTest` 挂 |

这三条正好是 §5 阶段二验收「无手动 `navArgument`、无 `backStackEntry.arguments` 解析」
这条**纯语法**标准抓不到的 —— 它只能证明「字符串没了」，**证不了「路由还对」**。

项目自己的 doctrine 就是「门禁是不变量而非项数」（`docs/PLAN-RECORD-DETAIL-20260929.md:344`），
这份方案在这里违反了自己的规矩。

### 10. 爆炸半径其实很小，但方案没说

grep 核实：全工程 **52 处**路由字符串**只**出现在 `Screen.kt` + `AppNavigation.kt` 两个文件，外部 **0 引用**。

这是「可以放心做 Phase 2」的硬依据，方案反而含混过去了（只在 §阶段二验收里说「12 个页面」）。

### 11. 可空参数被写成了零风险

§2.2 收益第 2 条：「可空参数（如 `slotId` / `recordId`）由 Kotlin 自动处理，无须手动声明复杂的默认值参数树」。

不负责任。`Json` 默认 `encodeDefaults = true`，null 查询参数是否被省略在小版本间踩过
`IllegalArgumentException: Navigation destination that matches request cannot be found`。

**更稳的形状是拆成两个目的地**（`AddMedication` / `EditMedication(medId: Long)`），`ManualDose` 同理 ——
顺带消掉现在 `AppNavigation.kt:258 / 297 / 331 / 332` 那个把 `0` 当 `null` 的 `takeIf { it > 0 }` 哨兵。

---

## 四、Phase 3（Pager）：收益被高估，代价被低估

### 12. 组件选型自相矛盾

方案写 `SingleChoiceSegmentedButtonRow` **或** `SecondaryTabRow` 二选一。

但两者语义不同：SegmentedButton 是「2–3 个互斥选项的**瞬时开关**」，
M3 自己的指导里它**不配 Pager**；只有 `TabRow` 才和 Pager 成对。

现状是「同一份数据的两个筛选视图」，本质是 switch 不是 tab。

### 13. Pager 会打挂走查脚本，而风险表给出的结论正好是反的

**(a) 走查脚本没有视口过滤**

`tools/app_screenshots.py:227 / 231` 靠
`Step("text", "服药流水")` / `Step("text", "7 天打卡矩阵")` 点进展页第二个 tab。

`find_by_text`（`:478-495`）**只校验 `center(node)` 的 bounds 有效，不过滤是否在屏幕内**。
`HorizontalPager` 的相邻页在滑动 / 预取期间是**已布局但越界**的（x 落在 `[1080, 2160]`），
脚本会选中它，然后 `driver.tap(1620, 900)` 打到 1080px 宽的屏幕之外
→ `adb input tap` 静默失败 → 报成 `点击后未跳到含「服药流水」的页面`
→ **一个和真实原因毫无关系的诊断**。

**(b) 合并语义会吃掉 text 节点**

`SegmentedButton` 会**合并语义**。本项目已经被这件事咬过：
`SmokeNavigationTest.kt:275` 的注释写着
「⚠️ ExtendedFAB 合并语义后 Text 不可见（本版本行为），用图标 desc 点它」。

所以风险表里「标准组件自带无障碍锚点，使节点查找更加健壮」是**未经验证的乐观假设**，
在本项目里历史证据指向相反方向。

**(c) 「自带 wait_for 稳定重试机制」是虚构的**

风险表称「走查脚本定位节点时自带 `wait_for` 稳定重试机制」。**脚本里没有这个。**

真实机制（`:611-625`）：

```
for _ in range(3):
    root = driver.dump_ui()
    target = find_by_text(...)
    if target is not None: break
    time.sleep(0.8)
```

即 `tap(settle=0.9)` 固定 sleep + `dump_ui(retries=3)` + **只对「找不到」重试 3 次**。
对「找到了但点不到」**零防护**。

### 14. 改造量被一句话盖过去

`ProgressScreen` 的 tab 选择器是**同一个 `LazyColumn` 的第 0 个 item**（`:110`），
触底加载也挂在这**一个** `listState` 上（`:81-92` 的 `LaunchedEffect(uiState.selectedTab)` + `snapshotFlow`）。

上 Pager 必须：选择器提到 `Column` 头部、拆成两个 `rememberLazyListState`、分页逻辑重接到流水页那一个。

Cabinet 同理（选择器在 `:160` 的 `item {}` 里，搜索框在它上面）。

方案 §3.2「矩阵视图与流水视图分别运行在独立的分页容器中」这句话背后，
是这两屏各一次**结构性重写**。

### 15. 「内存与重组开销」那条防御无效

风险表给的防御是「列表项已具备唯一稳定的 `key`，状态通过 `StateFlow` 单向派发」。

`key` 解决的是**重组复用**，不解决**同时存在两份已测量节点**。
Pager 让在服列表和归档列表同时布局，这条风险行没给出可执行防御。

另外：TalkBack 的水平滑动读屏手势会和 Pager 横滑直接打架。

---

## 五、验收标准里不可证伪 / 抓不到回归的条目

| 方案条目 | 问题 |
| :--- | :--- |
| 「今日页 FAB 在底栏出退场全程高度锁死，无跳动下沉」 | **改动前就恒真**。FAB 位置由 `MainTabContent` 的固定 `padding(bottom = navBarHeight)`（`AppNavigation.kt:439-443`）决定，`NavHost` 恒 `fillMaxSize`。守不住任何东西 |
| 「能看到平滑的预测性返回卡片缩略手势动画」 | 见 §一.1，描述的是不存在的效果 |
| 「全工程零 Pager」「全工程无 `HorizontalPager`」 | 这是**现状陈述**，不是验收标准，出现在 checklist 里是错位的 |
| （缺失） | **真正缺的是回归用例**。本次改动的风险集中在「从哪进、从哪回」这一层，而唯一跨路由的门禁是 `SmokeNavigationTest`（instrumented，**不在 `./gradlew testDebugUnitTest` 里**）。Phase 2 / 3 的验收清单里没有 `connectedDebugAndroidTest` |

---

## 六、建议的更优方案

保留方向，改掉手段，按「**先测量、再改结构、最后做视觉**」重排。

### Step 0（必做，半天）先量再动

现在的预测性返回到底什么样，是可以证伪的：

```powershell
adb -s emulator-5554 shell dumpsys package com.mcxiaoke.carromed | findstr enableOnBackInvokedCallback
adb -s emulator-5554 shell input swipe 5 1200 600 1200 900   # 慢速侧滑，动画被拉长，中途截一帧
```

**先有截图再谈矩阵**，否则 Phase 1 的验收无从对照。

### Step 1 把矩阵变成代码里的两组规格

新增 `ui/navigation/Motion.kt`，三条铁律：

1. 所有规格**显式给 `animationSpec`**，一律 `tween` + `FastOutSlowInEasing`（不用裸 `spring`）；
2. **`popEnter` 永远静止** —— 跟手只在 `popExit` 上做（这是 §二.7 的修正）；
3. 四个主 Tab 的 `popEnter = EnterTransition.None`；
   未列出的目的地（含 `DoseDetail`、`PermissionCheck`）走 `secondary()` 默认，**不留空洞**。

### Step 2 只做「看得见有变、看不见没变」的事

先落 M3 正确的 **Fade Through**（进场 0.92→1.0 缩放 + 淡入，出场缩到 0.92 + 90ms 淡出），
**不要**用方案里那个 `delayMillis = 80` 的交叉淡入 ——
两个不透明全屏面同时半透明叠 180ms，在高刷屏上是可见的「重影」，不是「平稳」。

底栏 `AnimatedVisibility` 换 `tween(200)`。

> 这一步做完**必须亲眼看图**。DEVGUIDE §4 的理由在这条上完全成立：
> 两条都是纯视觉接线，编译和单测都看不见。

### Step 3 升 navigation-compose

2.8.3 → 2.8.5+，吃到 b/375343407 的修复。
补一条 androidTest：连续 `pressBack` 打到只剩 1 个 entry 那一帧。
有上游 issue 号的确定收益，成本是一行版本号。

### Step 4 类型安全路由，但验收换成行为断言

爆炸半径已核实只有 2 个文件，值得做。同时：

- 拆 `AddEditMedication` / `ManualDose` 为「新建 / 编辑」两个目的地；
- `BottomNavItems` 改为存 `KClass` / 泛型，不再存 `route: String`；
- **新增导航契约测试**（JVM 单测）：一张 `ScreenRoute → 期望 route 串` 的表 + `isTopLevel` 真值表。
  这是唯一能在改路由时把 §三.9 那三处钉住的东西。

### Step 5 交互升级：只上标准组件，先不上 Pager

药箱 / 进展换 `SingleChoiceSegmentedButtonRow`（拿到 `Role.Tab` + `selectAsGroup`），
内容用 `Crossfade` + 各自 `rememberLazyListState`。先看图、先跑走查。

真要 Pager，**排在走查脚本加视口过滤之后**，
且 `ProgressScreen` 需单独立项（要拆 `LazyColumn`）。

### Step 6 共享元素转场删掉，或降级为「待观察」

实验性 API 意味着破坏性变更，跟一个「不碰核心、只做展现层」的方案配在一起是错配。

另外 `SharedTransitionLayout` 包根节点会给全应用每个可见节点加一层 bounds 匹配计算，
LazyColumn 越长越贵，而收益只在两条转场上 —— **ROI 不成立**。

---

## 七、如果只提三件事

1. **删掉 §1.1**（空操作），换成 `navigation-compose` 2.8.3 → 2.8.5 的版本升级
   + 一条有上游 issue 号（b/375343407）的回归。
2. **把 §1.2 的矩阵改写成 `Motion.kt` 里的两组规格**，
   并修正「跟手只在 `popExit`、`popEnter` 恒静止」。
3. **Phase 3 之前先修 `tools/app_screenshots.py` 的 `find_by_text` 视口过滤**
   （用已有的 `driver.screen_size()` 排除 x 越界命中），
   并给药箱的「在服 / 已停药」补 `Step`（现在完全没覆盖，`:187` 只是 note）。
   否则 Pager 那一步会以「点击后未跳到含 X 的页面」这种误导性诊断失败，**你会去查错的地方**。

---

## 附：本次评审核对的证据清单

| 断言 | 核对方式 |
| :--- | :--- |
| `enableOnBackInvokedCallback` 默认为 true | 官方 `<application>` 元素文档 + Rust `android-manifest` 文档 |
| NavHost 内置 PredictiveBackHandler 及 2.8.5 崩溃修复 | Navigation 官方 release notes |
| SharedTransitionLayout 仍为 experimental | Google I/O 2024 官方文章 + compose-animation release notes |
| 路由字符串只在 2 个文件 | 全工程 grep，52 处命中全在 `Screen.kt` / `AppNavigation.kt` |
| `isTopLevel` / `tabTag` 依赖 `destination.route` | `AppNavigation.kt:96-98 / 399-405` |
| 单测基线 455 项 | `docs/CHANGES-20260929.md:153` |
| 无快照测试驱动 `NavHost` | grep `ScreenSnapshotTest` / `ComponentSnapshotTest` / `DoseRecordDetailWindowTest` |
| 走查脚本无视口过滤、无稳定重试 | `tools/app_screenshots.py:478-495 / 611-625` |
| 进展页 tab 有走查覆盖、药箱 tab 无 | `tools/app_screenshots.py:227 / 231` vs `:187` |
| 合并语义吃掉 text 节点的前例 | `SmokeNavigationTest.kt:275` 注释 |
| ProgressScreen 单 LazyColumn + 单一 listState | `ProgressScreen.kt:73 / 81-92 / 110` |
| Cabinet 选择器在 LazyColumn item 内 | `CabinetScreen.kt:160` |
| `slide*` 默认 spec 为 spring | Compose animation API 签名 |
