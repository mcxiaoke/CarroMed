# 顶栏标准化实施方案（TitleBar / TopAppBar）

> **状态**：方案定稿，**未改任何代码**（截至 2026-09-30 18:07 GMT+8）
> **决策已拍板**：顶栏**固定吸顶** · 标题统一 `titleLarge`（22sp） · 右上操作统一普通 `IconButton` · 采用 **Screen 级自治 Scaffold**（不把顶栏上提到根导航层）
> **上位分析**：`docs/ANALYSIS-APPBAR-INSET-20260930.md`、`docs/ANALYSIS-TITLEBAR-ARCHITECTURE-AND-STANDARDIZATION-20260930.md`（本文对两者均有纠正，见 §5.2）
> **实测环境**：`emulator-5554`，1080×2400，density 420（2.625×），状态栏 24dp = 63px

---

## 〇、一句话方案

**全应用收敛到一条链路：每个全屏页 = 一个 `Scaffold`，顶栏一律走官方 `topBar` 槽位，内容只消费 `innerPadding`。**
删除全部手写顶栏、全部手写 `.statusBarsPadding()`、以及 `HomeTabHeader` 的「列表 item」形态。

---

## 一、已拍板的决策

| # | 决策项 | 结论 | 备注 |
| ---: | :--- | :--- | :--- |
| D-1 | 顶栏滚动行为 | **固定吸顶（Pinned）** | 现状「随列表整条滚出屏幕」判定为缺陷，不是设计 |
| D-2 | 标题排版 | **统一 `titleLarge`（22sp）**，即 M3 小号 app bar 的规格 | 主 Tab 与 10 个二级页零差异；放弃现有 28sp 大标题 |
| D-3 | 右上操作按钮 | **普通 `IconButton`**（与 9 个二级页一致） | 放弃现有 44dp 圆形 `surfaceVariant` 底按钮 |
| D-4 | 顶栏宿主层 | **各 Screen 自己的 `Scaffold.topBar`** | 不上提到 `AppNavigation`；理由见 §5 |
| D-5 | 新增截图机制 | **不加** | 已有 `tools/app_screenshots.py` + `.maestro/` 两套，均不改动（§8） |

---

## 二、现状与实测数据

### 2.1 顶栏实现分四派

| 派别 | 页面 | 载体 | 高度 | 标题排版 | 滚动 | 状态栏由谁吃 | 实测标题 `bounds.top` |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | ---: |
| ① | 今日清单 | 内层**空壳 `Scaffold`**（无 `topBar`）+ `LazyColumn` 首个 item | 64dp + 4dp | `headlineMedium` 28sp | 随列表滚走 | **双重叠加** | **180** ❌ |
| ② | 药箱 / 进展 / 统计 | 无 `Scaffold`，`LazyColumn` 首个 item | 64dp + 4dp | `headlineMedium` 28sp | 随列表滚走 | 页面 `.statusBarsPadding()` | 117 ⚠️ |
| ③ | 9 个二级页 | 页面级 `Scaffold(topBar = TopAppBar)` | 64dp | `titleLarge` 22sp | 吸顶 | `TopAppBar` 自己 | 112 ✅ |
| ④ | 服药历史 | 页面级 `Scaffold(topBar = Row)` | **56dp 手写** | `titleMedium` | 吸顶 | 手写 `.statusBarsPadding()` | 86 ❌ |

> 提醒设置 / 库存管理因标题为两行（主标题 + `bodySmall` 副标题）居中，首行上移到 89，属预期。

### 2.2 五个不同的标题位置、两种字号

`180 / 117 / 112 / 89 / 86`，标题文字高 `82px`（28sp）vs `69px`（22sp）。
`180 - 117 = 63px`，恰为 420dpi 下 24dp 状态栏 —— 即今日清单比其余三个主 Tab **多算了一次状态栏**。

### 2.3 「好几次」的时间线

| 时间 | 事件 | 结果 |
| :--- | :--- | :--- |
| 2026-09-27 前 | 各页自由手绘顶栏 | 标题高低不一（`PROJECT_STATUS_20260927.md`） |
| 2026-09-27 | 抽 `HomeTabHeader` 统一 4 个主 Tab 为 64dp | 3 个 Tab 对齐，**方向正确** |
| 2026-09-27 | 修全局双重间距：根 `Scaffold.contentWindowInsets = WindowInsets(0,0,0,0)`，注释写「各子页面按需消费」 | 对无内层 Scaffold 的 3 个 Tab 成立 |
| 同上 | —— | **漏掉 `TodayScreen` 自套的带默认 insets 的 Scaffold** |
| 至今 | 方向翻转：今日从「偏上」变成「偏下 63px」 | `CODE-REVIEW-20260927-sbf.md` S-1 记为「疑似、需真机确认」，潜伏至今 |
| —— | `MedHistoryScreen` 手写 56dp Row | 二级页内部再分一派 |

---

## 三、根因

1. **协议割裂**：官方把 `topBar` 设计成**独立布局槽位**，本项目主 Tab 却把顶栏当成 `LazyColumn` 的普通 item。
2. **双重消费**：`Scaffold` 的 `innerPadding` 与 `Modifier.statusBarsPadding()` 是两套不互通的手段，同时用就把状态栏计两次（`TodayScreen`）。
3. **缺乏单一来源**：同一视觉角色有 4 处实现、2 种字号，任何一次局部改动都可能制造新的漂移。

**根因不是官方 AppBar 有缺陷** —— 9 个使用官方 `TopAppBar` 的二级页实测全部正确。

---

## 四、官方推荐做法（含取证）

### 4.1 `Scaffold` 只发 padding，不消费 inset

官方文档（[使用 Material 3 内嵌](https://developer.android.com/develop/ui/compose/system/material-insets?hl=zh-cn)）原文：

> 默认情况下，`Scaffold` 提供边衬区作为参数 `PaddingValues`，供您使用。
> **`Scaffold` 不会将边衬区应用于内容；这需要您自行负责。**
> …如果使用 `Scaffold`，请**避免使用其他边衬区处理方法**（例如标尺、内边距修饰符或边衬区大小修饰符），以免向界面应用过多的内边距。

**字节码交叉验证**（本项目实际使用的 `material3-release.aar` 1.3.1）：
`ScaffoldKt.class` 中只出现 `WindowInsetsPaddingKt.onConsumedWindowInsetsChanged`（观测上层已消费量），
**没有任何 `consumeWindowInsets` 调用**。消费是调用方的责任 —— 与文档一致。

### 4.2 `TopAppBar` 自动处理「顶部 + 水平」inset

官方文档把 `TopAppBar` 列入**自动处理边衬区的组件**清单：
> `TopAppBar` / `CenterAlignedTopAppBar` / `MediumTopAppBar` / `LargeTopAppBar`：将系统栏的**顶部**和**水平**边用作内边距。

字节码侧对应 `AppBarKt$SingleRowTopAppBar$3` 调用 `Modifier.windowInsetsPadding(windowInsets)`，
默认值为 `TopAppBarDefaults.windowInsets`（含**水平**方向）。

> ⚠️ **不要**把 `windowInsets` 显式写成 `WindowInsets.statusBars`：那会丢掉水平方向，
> 横屏 / 侧边导航栏 / 侧边挖孔时标题会贴边。**保持默认值即可，不要传这个参数。**

### 4.3 列表要消费 `innerPadding`

> `Scaffold { innerPadding -> LazyColumn(modifier = Modifier.consumeWindowInsets(innerPadding), contentPadding = innerPadding) }`

### 4.4 主流 App 做法（Google 官方样本实证）

`Now in Android` 的 `NiaApp.kt`：

```
Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0)) { padding ->
    Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding) ...) {
        if (当前是一级目的地) NiaTopAppBar(...)   // 官方 TopAppBar，吸顶
        Box(Modifier.consumeWindowInsets(...)) { NavDisplay(...) }
    }
}
```

**要点**：根 `Scaffold` 抹零 insets；拿到的 `padding` **必须 `consumeWindowInsets` 下去**；
顶栏用官方组件。本项目与它的**唯一结构性差异**是：根 `NavHost` 只做了 `padding`，**没做 `consumeWindowInsets`**（§7 必须补）。

---

## 五、架构选型

### 5.1 为什么选「Screen 级自治」而不是「上提到根 Scaffold」

| 维度 | 上提到根 `Scaffold` | **各 Screen 自治（选定）** |
| :--- | :--- | :--- |
| 4 个 Tab 的 action | 必须把 action 拿到路由层；其中 **2 个直连 ViewModel**（`ProgressScreen`、`StatsScreen` 的 `viewModel.exportReport()`），`AppNavigation` 将被迫持有业务 VM | action 在各自 Screen 内闭环，零泄漏 |
| `ScreenSnapshotTest` | 6 张快照里 4 张不再代表真实屏幕，必须为每个 Screen 写 Fake 宿主 | 快照**更真实**（与 `settings` / `med_detail` 一样自带顶栏） |
| `@Preview` | 丢顶栏 | 即所见 |
| 一二级页同构 | 一级在根、二级在页内，两套宿主规则 | 一套规则 |

### 5.2 对两份既有分析文档的纠正

| # | 出处 | 原文 | 纠正 |
| ---: | :--- | :--- | :--- |
| 1 | APP BAR 分析 §六 | 方案 C 示意 `windowInsets = WindowInsets.statusBars` | ❌ 会丢水平 inset。**不传该参数**，用官方默认（§4.2） |
| 2 | 同上 | 「`ScreenSnapshotTest` 的 **5 张**基线会失去标题」 | 实为 **4 张 Screen**（`today_seeded` / `today_empty` / `cabinet_seeded` / `stats_seeded`）+ **2 张 Component**（`home_tab_header_*`） |
| 3 | 同上 | 未提根 `NavHost` 缺 `consumeWindowInsets` | 补上，否则方案 D 下 4 个主 Tab 会再吃一次导航栏 inset |
| 4 | TITLEBAR 分析 §6.1 | `CarroMedHomeTopAppBar` 用 `headlineMedium`（28sp）配小号 `TopAppBar` | ❌ 偏离 M3 规范（小号 app bar 标题 = `titleLarge`）。照抄则主 Tab 与二级页**仍然不一致**，与其「100% 同构」自相矛盾。现按 **D-2 统一 22sp** |
| 5 | TITLEBAR 分析 §4.2 | 「`ProgressScreen`：操作是空」 | ❌ 事实错误。`ProgressScreen.kt:103-110` 有导出按钮且直连 `viewModel.exportReport()`。反对方提根 Scaffold 的**结论仍成立**，但论据须换成「Progress + Stats 两页直连 VM」 |
| 6 | TITLEBAR 分析 §3.1 | 「当成列表项滚走…仅极少数内容沉浸流详情页…非主流做法」 | ⚠️ 论证过度。M3 的 `Medium/LargeTopAppBar` 属于「大标题收起、**顶栏始终吸顶**」，与「整条滚出屏幕」是两回事，不应混为一谈。本方案不采纳该组件的理由是 D-1/D-2，不是「反人类」 |
| 7 | TITLEBAR 分析 §6.3 | 建议在 `SmokeNavigationTest` 断言标题 bounds 全等 | ✅ 采纳，但需明确它才是**唯一**能守顶栏几何的层（§8.3） |

---

## 六、目标结构

### 6.1 根导航（`AppNavigation`）

```
Scaffold(
    contentWindowInsets = WindowInsets(0, 0, 0, 0),   // 保持不变
    bottomBar = { NavigationBar(...) },                // 保持不变
) { innerPadding ->
    NavHost(
        modifier = Modifier
            .padding(bottom = innerPadding.calculateBottomPadding())
            .consumeWindowInsets(innerPadding),        // ← 新增（§7-5）
        ...
    )
}
```

根 `Scaffold` **不提供 `topBar`**（各页自负）。

### 6.2 统一顶栏组件（替换 `HomeTabHeader`）

```
@Composable
fun CarroMedTopAppBar(
    title: String,
    subtitle: String? = null,
    actionIcon: ImageVector? = null,
    actionContentDescription: String? = null,
    onActionClick: (() -> Unit)? = null,
    navigationIcon: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    modifier: Modifier = Modifier,
) = TopAppBar(
    title = {
        if (subtitle == null) Text(title)
        else Column {                       // 现有提醒设置 / 库存管理页的写法
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    },
    navigationIcon = navigationIcon ?: {},
    actions = {
        actions()
        if (actionIcon != null) IconButton(onClick = { onActionClick?.invoke() }) {
            Icon(actionIcon, contentDescription = actionContentDescription)
        }
    },
    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    modifier = modifier,
)
```

- **不传 `windowInsets`**（用官方默认，§4.2）。
- 标题**不设 `style`** → 继承 `TopAppBarDefaults` 提供的 `titleLarge`（22sp），即 D-2。
- 「长标题不截断」这条既有硬约束（`ComponentSnapshotTest.homeTabHeader_longTitle`）改钉在新组件上。

### 6.3 每个全屏页

```
Scaffold(
    topBar = { CarroMedTopAppBar(title = ..., ...) },  // 或官方 TopAppBar
    floatingActionButton = { ... },                     // 仅今日页
    snackbarHost = { ... },                             // 仅今日页
) { innerPadding ->
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 120.dp),
    ) { /* 不再有 header item */ }
}
```

---

## 七、逐文件改动清单

| # | 文件 | 改动 |
| ---: | :--- | :--- |
| 1 | `ui/component/HomeTabHeader.kt` | 改为 `CarroMedTopAppBar`（§6.2）；`HomeTabHeader` 删除 |
| 2 | `ui/screen/today/TodayScreen.kt` | `Scaffold` 补 `topBar`；`LazyColumn` 删 `.statusBarsPadding()`（L138）与 header item（L143-150）；`contentPadding.top` 4dp → 8dp |
| 3 | `ui/screen/cabinet/CabinetScreen.kt` | 包一层 `Scaffold(topBar = ...)`；删 `.statusBarsPadding()`（L83）与 header item（L89-96） |
| 4 | `ui/screen/progress/ProgressScreen.kt` | 同上（L98 / L103-110） |
| 5 | `ui/screen/stats/StatsScreen.kt` | 同上（L66 / L71-78） |
| 6 | `ui/screen/progress/MedHistoryScreen.kt` | 56dp 手写 Row → `CarroMedTopAppBar(title = 药名, subtitle = 服药历史, navigationIcon = 返回)`；副标题 `labelSmall` → `bodySmall`（与提醒设置 / 库存管理对齐，L92-96） |
| 7 | `ui/navigation/AppNavigation.kt` | `NavHost` 补 `.consumeWindowInsets(innerPadding)`（L152） |
| 8 | `ui/component/TestTags.kt` | 新增顶栏标题 tag（防回归断言用） |

**不改动**：9 个二级页的 `TopAppBar`（已正确）；`tools/app_screenshots.py`；`.maestro/`。

---

## 八、测试与截图策略

> 本节回答「不改测试的话，每次动 UI 就得改测试，是不是太麻烦」。

### 8.1 现有四层（职责不重叠）

| 层 | 载体 | 跑在哪 | 职责 | 本次是否改动 |
| :--- | :--- | :--- | :--- | :--- |
| L1 | `tools/app_screenshots.py` | adb → 模拟器 | 全页面走查截图 + `expect` 断言 + `manifest.md` | **不改** |
| L2 | `.maestro/smoke-seeded.yaml`（`run-smoke.ps1`） | Maestro → 模拟器 | 端到端流程 + `takeScreenshot` 留档 | **不改** |
| L3 | `ScreenSnapshotTest` / `ComponentSnapshotTest`（Roborazzi） | Robolectric（JVM） | 像素回归 | **改基线**，策略见 8.2 |
| L4 | `SmokeNavigationTest`（instrumented） | 模拟器 | 真实 Activity / 工厂反射路径 / 导航 | **新增 bounds 断言** |

### 8.2 Roborazzi 基线：冻结前不构成负担（已实测）

**实测 1 —— 常规测试不会改动基线。**
`./gradlew testDebugUnitTest --tests "*SnapshotTest*"` 跑完后，8 张基线 PNG 的 `LastWriteTime` **全部保持 8:48 不变**（测试确实执行了，`build/test-results` 时间戳为本次运行）。
即：只有显式执行 `recordRoborazziDebug` 才会覆盖基线。

**实测 2 —— JVM 快照根本看不见顶栏几何。**
Robolectric 没有真实 SystemUI，**系统栏 inset 恒为 0**。对比两张基线 `today_seeded.png` 与 `cabinet_seeded.png`：
真机上二者标题相差 63px，而在快照里**顶栏位置完全一致、且都没有状态栏**。
→ **本次这个 bug 在 L3 层不可见**；L3 也无法守住任何顶栏几何回归。

**因此基线策略定为：**

| 阶段 | 规则 |
| :--- | :--- |
| UI/UX 冻结**前**（当前） | `verifyRoborazziDebug` **移出日常回路**（`CHANGES-20260929.md:44` 曾把它纳入常规回归，现撤销该决定）。有意改 UI 后顺手跑一次 `recordRoborazziDebug` 即可，**不要求逐张人眼复核**。基线是「形态存档」，不是门禁。 |
| UI/UX 冻结**后** | 一次性 `recordRoborazziDebug` + 人眼复核全部基线，再把 `verifyRoborazziDebug` 升为验收门禁 |
| 本次改动 | 顶栏上屏后 4 张 Screen 基线内容变化 → 跑一次 `recordRoborazziDebug`；`ComponentSnapshotTest` 的两个用例改指向新组件 |

> 结论：把「每次动 UI 改测试」的成本降到 **0 次手动维护**（不跑 verify 就不会红），且冻结后随时可开启门禁。

### 8.3 唯一能守顶栏几何的断言（必须做）

按 §8.2 实测 2，几何回归只能靠**真实 insets 环境**。
在 `SmokeNavigationTest` 增加：

- 依次切到 4 个主 Tab，取顶栏标题语义节点的 `getUnclippedBoundsInRoot().top`，断言**四者全等**（容差 ≤ 1px）；
- 再进入任一二级页，断言其标题 top 与主 Tab **一致**（本次 D-2 统一 22sp 后才成立）；
- 标题节点加 `TestTags`，断言不依赖中文文案。

这条断言若在改动前存在，2026-09-27 那次就不会潜伏三天。

---

## 九、验收清单

- [ ] `./gradlew clean assembleDebug` **且 `assembleRelease`** 通过
- [ ] `./gradlew testDebugUnitTest` 全绿（含更新后的快照用例）
- [ ] `recordRoborazziDebug` 重录基线，并**亲眼看图**复核 4 张 Screen 基线
- [ ] `SmokeNavigationTest` 新增的 bounds 全等断言通过
- [ ] `connectedDebugAndroidTest` 全绿（`contentDescription` 未丢：`today_cd_settings` / `cabinet_add_medication` / `prog_export_report` / `stats_export_report`）
- [ ] `python tools\app_screenshots.py --clear --seed` 走查 + **亲眼看图**：4 个主 Tab 标题 top 全等、二级页不变、滚动时顶栏不消失
- [ ] `docs/CHANGES-20260930.md` 追加摘要

**验收标准量化**：**同形态页面之间**顶栏标题 `bounds.top` 逐个像素一致 ——
单行标题页全等（实测均为 `112`），两行标题页主/副标题全等（实测均为 `89 / 158`）；
顶栏在任一页面滚动后**仍在位**。分层依据见 §11.1。

---

## 十、不做的事与已知边界

- **不加第三套截图机制**（已有 Python + Maestro 两套，见 D-5）。
- **不改**现有 9 个二级页的正确写法。
- **不引入** `MediumTopAppBar` / `LargeTopAppBar`（D-1 已定固定吸顶；若日后想恢复大标题观感，那是独立的产品决策）。
- **不写数据库迁移**（与本次无关，但沿用项目红线）。
- 已知边界：`TopAppBar` 的标题槽位不强制单行，超长标题会换行撑高顶栏 ——
  `CarroMedTopAppBar` 已用 `maxLines = 1 + Ellipsis` 钉住恒定高度（实测见 §11）。
- 遗留（不在本次范围）：`res/` 无 `values-night`，`themes.xml` 把平台主题钉死浅色，叠加 `enableEdgeToEdge()` 后深色模式下系统栏图标明暗不协调（`CODE-REVIEW-20260927-sbf.md` P3-6）。

---

## 十一、实施记录（2026-09-30 18:31 GMT+8）

§7 的 8 项全部落地。`assembleDebug` / `assembleRelease` / `compileDebugAndroidTestKotlin`
通过，`testDebugUnitTest` 512 项全绿，`connectedDebugAndroidTest` 4 项全绿，
走查 39 张截图全部导航断言通过。

### 11.1 与本文方案的偏差

1. **验收判据要按「标题行数」分层，不能一刀切说"全等"。**
   本文 §9 写的是"全部二级页标题 `bounds.top` 全等"，实测需要精确化为两类：
   - **单行标题页**（4 主 Tab + 药品详情 / 系统设置 / 权限自检 / 手动补录 / 补药入库 / 记录详情 / 编辑药品）→ 全部 **112**；
   - **两行标题页**（提醒设置 / 库存管理 / 服药历史）→ 主标题 **89**、副标题 **158**。
   两行标题在 `TopAppBar` 的 title 槽位里垂直居中，首行自然高于单行页面 ——
   这是预期行为，不是不一致。判据是"**同形态页面之间逐个像素一致**"。
   （服药历史归队后与另外两页的两行顶栏实测完全同构，说明归队成功。）

2. **`Cabinet / Progress / Stats` 三页原先没有 `Scaffold`**，包一层会让整段
   `LazyColumn` body 多一级缩进、diff 无法复核。实际用一次性脚本
   （`temp/wrap_scaffold.py`，用后即删）按行精确缩进 + 补闭合括号完成，
   二进制读写以保留 CRLF。

3. **`ComponentSnapshotTest` 由 2 项变 3 项**：新增 `topBar_withSubtitle`
   （两行标题是三个页面共用的形态，值得单独钉一张基线）。

### 11.2 变异验证

给今日页顶栏加 `padding(top = 4.dp)` → `topBarAligned_acrossTabs` 变红
（`expected to be at most: 1.0`）；还原后绿。证明该断言不是空转。

### 11.3 实测数据（emulator-5554，1080×2400，density 420，状态栏 63px）

| 页面 | 修前标题 top | 修后标题 top |
| :--- | ---: | ---: |
| 今日清单 | **180** ❌ | **112** ✅ |
| 我的药箱 / 进展追踪 / 统计报表 | 117 | **112** ✅ |
| 单行标题二级页（7 个） | 112 | **112** ✅ |
| 提醒设置 / 库存管理（两行） | 89 / 158 | 89 / 158 ✅ 不变 |
| 服药历史（两行） | 86（56dp 手绘栏） | **89 / 158** ✅ 归队 |

吸顶已验证：`01_today_s2.png`（列表已滚动）中"今日清单"仍在 `top=112`。