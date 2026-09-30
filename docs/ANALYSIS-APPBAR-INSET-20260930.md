# 主 Tab 顶栏高度不一致 —— 根因分析与方案取舍

> 状态：**仅分析，未改任何代码**（截至 2026-09-30 17:40）
> 决策：**方案 C（顶栏上移根 Scaffold + 官方 TopAppBar）** 与 **MedHistoryScreen 一并统一**，已拍板待实施
> 实测环境：`emulator-5554`，1080×2400，density 420（2.625×），状态栏 24dp = 63px

---

## 〇、一句话结论

**官方 AppBar 本身没有任何问题 —— 本项目在主 Tab 位置上从来就没用过它。**
真正的问题是：`AppNavigation` 把顶部 inset 交给各页自管，而 `TodayScreen` 又私自套了一层**带默认 insets 的 `Scaffold`**，导致状态栏被计入两次。

---

## 一、实测数据（不是推断）

`python tools/app_screenshots.py --only today,cabinet,progress,stats --dump-ui`
导出语义树后量 `HomeTabHeader` 标题 Text 的 `bounds.top`：

| 页面 | 顶栏实现 | 标题 `bounds.top` | 标题高 |
|:--|:--|--:|--:|
| **今日清单** | HomeTabHeader + **内层 Scaffold** | **180** | 82 |
| 我的药箱 | HomeTabHeader | 117 | 82 |
| 进展追踪 | HomeTabHeader | 117 | 82 |
| 统计报表 | HomeTabHeader | 117 | 82 |
| 系统设置 | M3 `TopAppBar` | 112 | 69 |
| 手动补录服药 | M3 `TopAppBar` | 112 | 69 |

- 今日比其余三个主 Tab 低 **63px = 恰好一个状态栏高度**。
- 三个主 Tab 之间**完全一致**（117/117/117），说明 `HomeTabHeader` 抽象本身是有效的。
- 9 个用了官方 `TopAppBar` 的二级页**无一例外全部正常**（实测 112）。
- 截图肉眼可辨：`temp/shots/insets-probe/01_today.png` 状态栏与标题之间有明显空档，
  `02_cabinet.png` 没有。

### 附带发现（次级，非本次主因）

1. 4 个主 Tab 的 `contentPadding = top 4.dp` 使标题比二级页 `TopAppBar` 低 4dp（117 vs 112）。
2. `TodayScreen.kt:137` 的 `.padding(innerPadding)` 连 bottom 一起吃了，而内层 Scaffold 带 FAB，
   `innerPadding.bottom ≥ FAB 高度`，与 `contentPadding = bottom 120.dp` 重复叠加（不显形，但冗余）。

---

## 二、官方机制到底是什么（M3 1.3.1 字节码实证）

> 取证方式：反编译 Gradle 缓存里的 `material3-release.aar`（1.3.1），
> `javap -p -c androidx/compose/material3/ScaffoldKt.class` 等。**不靠记忆下结论。**

### 事实 1：`ScaffoldDefaults.contentWindowInsets` = 系统栏

`ScaffoldDefaults.class` → `getContentWindowInsets` 字节码 offset 29：

```
getSystemBarsForVisualComponents(...)   // Scaffold.kt:292
```

即：**Scaffold 不显式传 `contentWindowInsets` 时，`innerPadding.top` 必然等于状态栏高度。**

### 事实 2：Scaffold 用「有无 topBar」来覆盖 top padding

`ScaffoldKt$ScaffoldLayout$1$1$bodyContentPlaceables$1.class` 反编译还原：

```java
basePadding = contentWindowInsets.asPaddingValues(density)
top    = topBarPlaceables.isEmpty()  ? basePadding.calculateTopPadding()     : topBarHeight.toDp()
bottom = bottomBarPlaceables.isEmpty()? basePadding.calculateBottomPadding() : bottomBarHeight.toDp()
start/end = basePadding.calculateStart/EndPadding(layoutDirection)
```

**关键：`TopAppBar` 只有作为 `Scaffold` 的 `topBar` 槽位传入时，才走 `else` 分支。**

### 事实 3：Scaffold 不消费 inset，只做通知

`ScaffoldKt.class` offset 1087 出现的是：

```
WindowInsetsPaddingKt.onConsumedWindowInsetsChanged(...)
```

这是 `WindowInsetsHolder` 的**观测回调**（把"你消费了多少"告诉上层重排），
**不是 `consumeWindowInsets()`**。它不会把已消费量从子节点的 `WindowInsets` 里扣掉。
**消费是调用方的责任。**

### 事实 4：`TopAppBarDefaults.windowInsets` = 系统栏

`TopAppBarDefaults.class` → `getWindowInsets`，offset 29（`AppBar.kt:1025`）：
同样是 `getSystemBarsForVisualComponents(...)`。

### 官方链路因此自洽

```
TopAppBar 自己吃掉 statusBars   → topBarHeight 已含 63px
Scaffold 用 topBarHeight 覆盖 top → innerPadding.top = 0（不重复）
body 用 .padding(innerPadding)   → 恰好一次  ✅
```

---

## 三、真正的根因：两套 inset 协议被混用

| | 官方 AppBar 链路（9 个二级页 ✅） | 本项目主 Tab 链路（4 个 tab ❌） |
|:--|:--|:--|
| 谁吃 statusBars | `TopAppBar` 自己 | 页面自己 `statusBarsPadding()` |
| Scaffold 角色 | **有 `topBar`** → `innerPadding.top` 归零 | **无 `topBar`** → `innerPadding.top` = 63px |
| body 要做什么 | 只 `.padding(innerPadding)` | `.padding(innerPadding)` **+** `.statusBarsPadding()` ❌ |

### 决定性证据：`TodayScreen.kt:122` 那个 Scaffold 里**没有 `topBar` 参数**

它是个**空壳 Scaffold**，只为挂 `snackbarHost` 和 `floatingActionButton`；
标题是手绘的 `HomeTabHeader`，且放在 `LazyColumn` 的**第一个 `item {}` 里**（会随列表滚走）。

```
TodayScreen.kt:122   Scaffold(            ← 无 topBar
TodayScreen.kt:123       snackbarHost = { ... },
TodayScreen.kt:124       floatingActionButton = { ... }
TodayScreen.kt:133   ) { innerPadding ->
TodayScreen.kt:137       .padding(innerPadding)   // +63px
TodayScreen.kt:138       .statusBarsPadding()     // +63px  ← 第二次
```

**所以「今日」页从来没在主 Tab 位置上使用过官方 AppBar。
官方方案在本项目已被验证 9 次，次次正确。**

### 算术闭合验证（排除"只算了一次"的可能）

设 `rowOffset` 为标题在 64dp 行内的偏移，`4dp contentPadding` = 10.5px。

```
药箱:  0 + 63 + 10.5 + rowOffset = 117  →  rowOffset = 43.5
今日:  X + Y   + 10.5 + 43.5       = 180  →  X + Y = 126
```

- `X = innerPadding.top`，由事实 1 上限即状态栏 63px。
- 若 Scaffold 消费了 inset（`Y = 0`），则需 `X = 126` —— **超出上限，凑不出来**。
- **故只有 `X = 63`（Scaffold 的 `contentWindowInsets`）+ `Y = 63`（`statusBarsPadding`）成立。**

两处都在生效，确证。

---

## 四、这个 bug 的完整生命周期

| 时间 | 事件 | 后果 |
|:--|:--|:--|
| 2026-09-27 前 | 各页顶栏高度不一，`HomeTabHeader` 未抽象 | `PROJECT_STATUS_20260927.md:68` 记录「今日清单比我的药箱**靠上**」 |
| 2026-09-27 | 抽 `HomeTabHeader`（`CHANGES-20260927.md:159`） | 3 个 tab 对齐，**决策正确且有效** |
| 2026-09-27 | 修全应用双重间距（`CHANGES-20260927.md:196`）：根 Scaffold `contentWindowInsets = WindowInsets(0,0,0,0)`，注释写「各子页面按需消费 statusBars 边距」 | 对**无内层 Scaffold** 的 3 个 tab 成立 |
| 2026-09-27 | —— | **唯独漏掉 `TodayScreen`：它自己又套了一层带默认 insets 的 Scaffold** |
| 至今 | 方向翻转：今日从「偏上」变成「**偏下 63px**」 | `CODE-REVIEW-20260927-sbf.md:1290` 记为 **S-1「疑似，需真机确认」**，一直没验 |

**一次全局修复 + 一处局部例外 = 幸存者。** 这是典型的"改对了 95%，漏了 5%"。

---

## 五、为什么"直接换成官方 TopAppBar"不能作为最小修法

这是选方案时最容易踩的坑，必须写明：

当前 `HomeTabHeader` 有**两个刻意的设计决策**，官方 `TopAppBar` **不支持**：

1. **随列表滚动**：`HomeTabHeader` 是 `LazyColumn` 的 `item {}`，标题会滚出视口。
   `TopAppBar` 是 Scaffold 的固定槽位，**永不滚动**。
   → 若只在 `TodayScreen` 换 `TopAppBar`，今日页顶栏固定、其余 3 个 tab 仍滚动，
   **反而制造出比现在更大的不一致**。
2. **64dp 统一规格**：`CHANGES-20260927.md:159` 明确记录 `HomeTabHeader` 就是为修
   「4 个 Tab 标题高度不统一」而抽的，4 个主 Tab 都在用。
   → 这是**已经生效的一致性成果**，不该推翻。

**所以分歧点不是「官方 vs 自绘」，而是「顶栏在架构上属于哪一层」。**

---

## 六、三个方案的取舍

### 方案 A：最小修复（只动 `TodayScreen`）

给内层 Scaffold 加 `contentWindowInsets = WindowInsets(0,0,0,0)`，
`.padding(innerPadding)` 改为只取 `calculateBottomPadding()`，保留 `statusBarsPadding()`。

- ✅ 改动约 5 行，风险最低，不动其他页面
- ✅ 顺带消掉 bottom 的重复叠加
- ❌ **不解决根本问题**：`HomeTabHeader` 仍是列表 item，顶栏在架构上仍与官方链路割裂
- ❌ 依赖纪律：靠「主 Tab 一律不套 Scaffold」这条**口头约定**防止回归，而这条约定已经失效过一次
- ❌ `HomeTabHeader` 与二级页 `TopAppBar` 的分裂继续存在

### 方案 B：主 Tab 也用固定 `TopAppBar`（但留在各页内）

- ❌ **否决**。顶栏固定后与滚动列表内容重叠，且 4 个 tab 各写一遍 `Scaffold`，
  标题来源仍是 4 处，物理上仍可能漂移。只是把问题从"高度"换成"重复"。

### 方案 C（**已选**）：顶栏上移根 Scaffold

根 `AppNavigation` 为 4 个主 Tab 提供 `topBar`，各页退化为纯列表页：

```kotlin
// AppNavigation.kt（示意）
Scaffold(
    topBar = {
        if (isTopLevel) {
            TopAppBar(
                title = { Text(currentTabTitle, fontWeight = FontWeight.Bold) },
                actions = { /* 今日=设置 药箱=+ 进展=导出 统计=导出 */ },
                windowInsets = WindowInsets.statusBars,  // 官方默认，自己吃
                colors = topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    },
    contentWindowInsets = WindowInsets(0, 0, 0, 0),   // topBar 已含状态栏
    bottomBar = { /* NavigationBar 不变 */ }
)
```

各 Tab 页面变为与二级页**完全同构**：
`LazyColumn(Modifier.padding(innerPadding))`，删掉 `statusBarsPadding()` 与 `HomeTabHeader` item。

**收益**

| 收益 | 说明 |
|:--|:--|
| inset 只算一次 | 官方链路，`innerPadding.top` 归零，**结构上不可能再重复** |
| 4 个 Tab 强一致 | 标题由根 Scaffold 唯一渲染，物理上无法漂移 |
| 高度统一 | 64dp，与现有 `HomeTabHeader` 同高，视觉基本不变 |
| 兑现既有决策 | 保留 `CHANGES-20260927.md:159` 的统一规格，只换实现载体 |
| 收敛第三派 | `MedHistoryScreen` 的 56dp 自绘 Row 一并归队，10 个二级页完全同构 |
| FAB/Snackbar 可上移 | 根 Scaffold 收编后，`TodayScreen` 变回纯列表页，与其余 3 页结构一致 |

**代价（必须正视）**

1. **顶栏从"随列表滚动"变为"固定"** —— 这是**真实的体验变化，需要产品确认**。
   视觉上标题不再随内容滚走。
2. **`ScreenSnapshotTest` 的 5 张基线会失去标题。**
   该测试**直接调用 `TodayScreen(...)` / `CabinetScreen(...)` / `StatsScreen(...)`，
   不经过 `AppNavigation`**（`ScreenSnapshotTest.kt:143-198`）。
   顶栏上移后这些快照**不再代表真实屏幕**。
   → 处置：把这几个用例改为包一层带 `topBar` 的宿主，或改为走 `AppNavigation`；
   无论如何需 `recordRoborazziDebug` 重录基线并**人眼复核**。
3. `ComponentSnapshotTest` 引用 `HomeTabHeader`（`ComponentSnapshotTest.kt:37,53`），
   若组件删除需一并处置 2 张 `home_tab_header_*.png` 基线。
4. `SmokeNavigationTest` 用 `today_cd_settings` / `cabinet_add_medication` 等
   **contentDescription** 定位顶栏按钮（`SmokeNavigationTest.kt:191,213`），
   迁到 `TopAppBar` 的 `actions` 槽位时**必须保留这些 desc**，否则冒烟测试会红。

---

## 七、待办：防回归断言（本次未做）

`DEVGUIDE.md:311` 已经记过同样的教训：
> 「这类问题只有看图 + 量 bounds 才能发现。」

因此必须补一条结构性断言：

- 在 `SmokeNavigationTest` 增加：切到 4 个主 Tab，断言标题节点 `bounds.top` **全等**。
  （需要给 `TopAppBar` 的 title 加 `testTag`，走 `TestTags` 池。）
- 可选：在 `tools/app_screenshots.py` 的 manifest 输出各页标题 top 横向对比表，
  让走查时一眼看出偏移。

**在断言落地前，任何顶栏改动都只能靠人眼发现回归 —— 这正是本 bug 潜伏三天的原因。**

---

## 八、附：本次调查发现的其他遗留问题（不在本次范围）

1. **`MedHistoryScreen.kt:70-99` 手绘 56dp 顶栏**（已拍板一并统一为 `TopAppBar`）。
   注意它在 `topBar` 槽位内、且自带 `statusBarsPadding()`，位置**不会出错**，
   只是与 9 个 64dp `TopAppBar` 不一致。

2. **顶栏实现分三派**（方案 C 落地后收敛为两派）：

   | 实现 | 页面数 | 高度 | 滚动 |
   |:--|--:|--:|:--|
   | 自绘 `HomeTabHeader` | 4 个主 Tab | 64dp | 随列表 |
   | M3 `TopAppBar` | 9 个二级页 | 64dp | 固定 |
   | 自绘 `Row` | 1 个（MedHistory） | 56dp | 固定 |

3. **无 `values-night`**：`res/` 下只有 `values` 和 `xml`，`themes.xml:3` 用
   `android:Theme.Material.Light.NoActionBar` 把平台主题钉死在浅色。
   `CODE-REVIEW-20260927-sbf.md:1161` 的 **P3-6 仍未修**。
   叠加 `MainActivity.kt:33` `enableEdgeToEdge()`，
   状态栏/导航栏图标明暗在深色模式下会不协调。

4. **`themes.xml` 平台主题强制浅色**与项目已有 Compose 主题（`ui/theme/`）职责重叠，
   属同一条 P3-6 线索。

---

## 附：取证产物

| 产物 | 位置 |
|:--|:--|
| 4 个主 Tab 截图 + 语义树 | `temp/shots/insets-probe/` |
| 二级页对照截图 + 语义树 | `temp/shots/subpage-probe/` |
| 标题 bounds 测量脚本 | `temp/measure_header.py`、`temp/measure_subpage.py` |
| M3 1.3.1 反编译目录 | `C:\Home\Temp\m3probe\`（临时，未入库） |

> `temp/shots/subpage-probe/manifest.md` 记有 1 处断言未通过
> （`text/记录详情` 页面标题匹配失败），属走查脚本的中文匹配问题，
> **与本次 inset 问题无关**，但建议单独跟进。
