# UI 测试与验证补强方案（Compose UI Test / Roborazzi / Maestro）

> 日期：2026-09-29
> 本文档取代 `temp/Android-Compose-UITest-Screenshots.md`（temp 目录那份是通用选型综述，
> 本文档是核对过本仓库代码后的落地计划，以本文档为准）。
> 状态：**阶段 1/2/3 已实施（2026-09-30）**，阶段 4（迁移决策）进入两周试点观察期。
> 实施记录与踩坑明细见 `docs/TASK-PROGRESS-20260929.md`；与本文档的三处分歧：
> ① Roborazzi 版本取 1.40.1 而非"最新稳定"（1.72.0 是 Kotlin 2.3 元数据，与本项目
> Kotlin 2.0.21 不兼容）；② 整页快照用"真 VM + 真库手工组装"而非
> `createAndroidComposeRule`（DB 重置与活动协程踩事务，见 TASK-PROGRESS）；
> ③ 时钟确定性不靠 Robolectric 配置，走种子器 today 参数 +
> `CurrentDateHolder.setTodayForTest`（M3-2 测试钩子）。

---

## 0. 现状核对结论（2026-09-29 实查代码）

| 事实 | 出处 |
| :--- | :--- |
| **没有 `androidTest` 源集**，没有任何 instrumented 测试 | `app/src/` 下只有 `debug` / `main` / `test` |
| **主源码里没有任何 `testTag`**，也没有自定义 semantics | 全源码 grep 确认 |
| `debugImplementation("androidx.compose.ui:ui-test-manifest")` 已存在 | `app/build.gradle.kts:159` |
| `testInstrumentationRunner` 已配好 | `app/build.gradle.kts:30` |
| Compose BOM **2024.10.01**（含 ui-test 1.7.4），无 version catalog，依赖直接写坐标 | `app/build.gradle.kts:102` |
| 单测跑 JUnit 5 Platform + Vintage Engine，Robolectric 4.14.1（支持 SDK 35） | `app/build.gradle.kts:90-92, 141, 156` |
| `testOptions.unitTests.isIncludeAndroidResources = true` 已开（Roborazzi 需要） | `app/build.gradle.kts:76` |
| 路由共 **14 个**：`today` / `cabinet` / `progress` / `stats` 四主 tab，加 `med_detail` / `med_edit` / `med_reminder` / `med_inventory` / `manual_dose` / `dose_detail` / `refill` / `med_history` / `settings` / `permission_check` | `ui/navigation/Screen.kt` |
| `AppNavigation()` 无参挂在 `MainActivity.setContent`，根组件在 `AppNavigation.kt:80` | `MainActivity.kt:56` |
| debug 源集有 `DevDataReceiver`（`SEED` / `CLEAR` 广播，必须显式 `-n` 组件），instrumented 测试跑 debug 变体时**进程内直接可用** | `app/src/debug/.../DevDataReceiver.kt` |

**核心判断**：目前 UI 验证只有一条腿——Python 走查脚本（`tools/app_screenshots.py`）。
它本质是手写的 instrumented 走查（语义树定位 + 导航断言 + 截图），能发现 AGENTS.md 坑 5
那类"编译过 + 单测全绿但一点就崩"的缺陷，但要跑完整走查才知道，且定位靠文本、
等待/重试/滚回顶部全靠手维护。三条补强路线按收益排序如下。

## 0.1 方案总览与优先级

| 优先级 | 方案 | 填补的空缺 | 需要模拟器 | 理由 |
| :--- | :--- | :--- | :--- | :--- |
| **P1** | Compose UI Test（androidTest）冒烟 | **UI 接线**：viewModel 工厂、导航、页面崩溃 | 是 | 直接堵 AGENTS.md 坑 5，可进常规构建门槛 |
| **P2** | Roborazzi 视觉回归 | **像素回归**："长相偷偷变了" | 否（JVM） | 复用现有 Robolectric 基建，零新增环境 |
| **P3** | Maestro 试点 | **端到端流程 + 自动留档截图** | 是 | Python 脚本的成熟替代，但先试点再决定替换 |

三者互补不互斥，且**共享同一个前提**：语义标识基建（§1）。

---

## 1. 共同前提：语义标识基建（P1 第一步，一次性投入）

主源码目前零 `testTag`。三种工具的定位需求不同，据此划定**最小加标集**，不搞全量覆盖：

| 消费方 | 定位手段 | 需要加标的位置 |
| :--- | :--- | :--- |
| Compose UI Test | `testTag` / 文本 / contentDescription | 仅底部 4 个 tab + 药品卡片 + 各详情页入口行 + 关键操作按钮（打卡 / 撤销 / FAB） |
| Maestro | `id:`（= `testTag`，需 `testTagsAsResourceId`）或文本 | 同上，同一批 tag 两边共用 |

改动清单：

1. 新建 `ui/component/TestTags.kt`：集中定义 tag 常量（object 常量池，避免散落魔法字符串）。
   预估 **15~25 个 tag**，涉及约 10 个文件（`AppNavigation.kt` 的底栏、今日页清单卡片、
   药品详情四个入口行、打卡/撤销按钮等）。
2. `AppNavigation.kt:80` 的根 Modifier 上挂
   `Modifier.semantics { testTagsAsResourceId = true }`，**仅 `BuildConfig.DEBUG` 生效**
   （release 不泄露测试标识，也避免无关语义变化）。
3. 现有 `contentDescription` 图标按钮不动——它们已经是合法定位点。

注意：文案断言（页面标题）在四个主 tab 已由 `HomeTabHeader` 统一提供，冒烟测试可直接
`onNodeWithText("今日清单")` 等，**不依赖**新加的 tag。tag 只给"无文本可依"的交互元素。

---

## 2. P1：Compose UI Test 冒烟（androidTest）

### 2.1 目标

用真实 Activity + 真实 `viewModel()` 工厂路径跑通全部路由，专堵坑 5：
`StatsViewModel` 构造器加参数那种缺陷，单测永远全绿，只有走工厂反射的路径才会炸。

### 2.2 依赖改动（`app/build.gradle.kts`）

```kotlin
// ui-test-junit4 已在 BOM 2024.10.01 内，无需版本号
androidTestImplementation(composeBom)          // 现有 val composeBom 提到 dependencies 外共享
androidTestImplementation("androidx.compose.ui:ui-test-junit4")
androidTestImplementation("androidx.test:runner:1.6.2")
androidTestImplementation("androidx.test.ext:junit:1.2.1")
```

> `ui-test-manifest` 已有（159 行），但它是 `debugImplementation`——Activity 场景
> （`createAndroidComposeRule<MainActivity>()`）下它不参与，真正需要的组件都在 App 本体里。

### 2.3 新增文件

`app/src/androidTest/kotlin/com/mcxiaoke/carromed/ui/SmokeNavigationTest.kt`，约 200 行，结构：

```kotlin
@RunWith(AndroidJUnit4::class)
class SmokeNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun resetDb(clean: Boolean, seed: Boolean) {
        // 进程内直接 sendBroadcast 到 DevDataReceiver（显式 setClassName），
        // 复用 debug 源集现有机制，不用 adb。广播是异步的：发完轮询等待生效。
    }

    @Test fun allTabs_open_withoutCrash() { /* 四个主 tab 逐个点，断言各自标题可见 */ }

    @Test fun allRoutes_open_withoutCrash() { /* 空库 + 播种库两种形态：
        经由页面入口到达 med_detail / med_edit / med_reminder / med_inventory /
        manual_dose / dose_detail / refill / med_history / settings / permission_check，
        每到一页断言标题可见；无数据可达的页面用播种库保证可达 */ }

    @Test fun coreFlow_takeDose_thenUndo() { /* 打卡 → 断言状态翻转 → 撤销 → 断言回补
        （库存回补同时用流水口径校验：重进库存页看余量） */ }
}
```

### 2.4 运行与验收

```powershell
./gradlew connectedDebugAndroidTest   # 需 emulator-5554 在线
```

- **变异验证**（AGENTS.md §3 纪律，这里尤其重要）：临时给某个二级页 ViewModel 加一个
  带默认值的构造参数，确认 `allRoutes_open_withoutCrash` 变红，再还原。
  这条测试若抓不住坑 5 类缺陷，就等于白写。
- 收口自检（§9）建议追加一项：`connectedDebugAndroidTest` 全绿。

### 2.5 边界与不做的事

- 只做冒烟 + 一条核心交互，**不把 Python 走查的 13 页详细断言搬进来**——那是走查脚本和
  人工看图的职责，重复维护两套详细断言必腐化。
- 权限弹窗（POST_NOTIFICATIONS）会遮挡：测试用 `GrantPermissionRule.grant(...)` 预授权。
- 闹钟对账在 `MainActivity.onCreate` 起协程，冒烟测试不去断言它（已有单测覆盖）。

---

## 3. P2：Roborazzi 视觉回归（JVM 快照）

### 3.1 目标

守住"组件长相偷偷变了"：主题、间距、截断、对比度这类改动，在 PR 期就能看到像素 diff，
不用等走查 + 人眼。**选 Roborazzi 而不是 Paparazzi**：本项目已在 Robolectric 上重度投入，
Roborazzi 跑在 Robolectric 里直接进现有 `testDebugUnitTest` 循环（JUnit 4 + Vintage Engine
兼容），零新增基础设施；Paparazzi 走 layoutlib，与新版 Compose/Kotlin 的兼容风险历来更高。

### 3.2 依赖改动（`app/build.gradle.kts`）

```kotlin
// 根 build.gradle.kts plugins 里：
id("io.github.takahirom.roborazzi") version "…" apply false
// app/build.gradle.kts：
id("io.github.takahirom.roborazzi")
testImplementation("com.github.takahirom.roborazzi:roborazzi:1.26.0+")   // ≥1.26 支持 Compose 1.7；落地时取当时最新稳定版
testImplementation("com.github.takahirom.roborazzi:roborazzi-compose:同版本")
testImplementation("com.github.takahirom.roborazzi:roborazzi-junit-rule:同版本")
```

> 版本号落地时以官方 release 为准，不在此拍死。`isIncludeAndroidResources = true` 已具备。

### 3.3 新增文件

`app/src/test/kotlin/com/mcxiaoke/carromed/ui/snapshot/SnapshotTest.kt`：

```kotlin
@GraphicsMode(GraphicsMode.Mode.NATIVE)   // Robolectric 原生渲染；默认值随版本变化，以官方文档为准
@Config(sdk = [34])                       // 固定 SDK 保证渲染稳定，全员/CI 一致
@RunWith(AndroidJUnit4::class)
class SnapshotTest {
    @get:Rule val rule = RoborazziRule(...)

    @Test fun todayScreen_seeded() = captureRoboImage("src/test/snapshots/today_seeded.png") {
        CarroMedTheme { TodayScreen(preview 形态或注入演示数据) }
    }
    // 首批建议 6~8 张：今日页(有数据/空态)、药品卡片、剂量详情、统计页、设置页、
    // 浅色主题为主。多屏页面截首屏即可。
}
```

**Screens 可快照的前提**：Composable 需能在无 Activity 环境渲染。现有 Screen 大多依赖
ViewModel（`viewModel()` 走 AndroidViewModelFactory），直接快照整页会踩坑 5 的反面。
落地时二选一：

- 优先快照**纯展示组件**（`HomeTabHeader`、剂量卡片、徽标、清单分区块）——最稳，收益最高；
- 整页快照仅限能以参数注入状态（state hoisting 完备）的 Screen，不强求。

### 3.4 工作流

```powershell
./gradlew recordRoborazziDebug    # 有意改 UI 后更新 baseline（快照文件提交进 git）
./gradlew verifyRoborazziDebug    # 常规回归比对，diff 超阈即红
```

### 3.5 边界与不做的事

- 快照验证的是**渲染结果**，替代不了 AGENTS.md §4.2 的人眼复核（信息层级、文案质量
  机器判不了）；也替代不了模拟器实测（字体缩放、通知、闹钟）。
- 快照对字体渲染有平台敏感性：团队多机 / CI 环境需一致，`@Config(sdk=...)` 固定；
  若出现无意义 diff，先查环境再怀疑代码。
- **不迁移**现有 Robolectric 测试，不把快照铺到 14 个页面全量——先小批验证维护成本。

---

## 4. P3：Maestro 试点（端到端 + 自动留档）

### 4.1 目标

验证 Maestro 能否替代 `tools/app_screenshots.py` 的走查职责：YAML 声明式流程、
自带智能等待与重试、自动截图与报告，甩掉脚本里手维护的 `uiautomator dump` /
滚回顶部 / 退避重试那几百行。

### 4.2 试点范围（先 1~2 条 flow，不做全量迁移）

`.maestro/smoke-seeded.yaml`：冷启动 → SEED 广播（`runScript`/`launchApp` 前 adb 一步，
或保留现有广播方式）→ 逐 tab 断言 → 进药品详情 → 打卡 → 撤销，每步 `takeScreenshot`。
直接对照 `tools/app_screenshots.py` 的 `PROGRAM`（113 行起）翻译，页码清单不变。

```powershell
maestro test .maestro/smoke-seeded.yaml    # 报告与截图自动落 .maestro/reports/
```

### 4.3 前置与风险（试点要回答的问题）

1. **Windows CLI 可用性**：Maestro 对 Windows 的支持成熟得晚，本机安装后先跑通一条
   最小 flow 再评估；不行就 WSL / 只在 CI 跑。**这是本方案最大的不确定项，试点先行就是为它。**
2. `id:` 匹配 `testTag` 依赖 §1 的 `testTagsAsResourceId`（仅 debug 构建开，Maestro 装的
   正是 debug 包，天然满足）。
3. 广播造数：现有 `DevDataReceiver` 机制可继续用（Maestro 可在 flow 前用 adb shell 步骤）。

### 4.4 决策点

试点跑 2 周后评估：稳定性、维护成本 vs 现有 Python 脚本。**通过才迁移全部 13 页，
否则脚本保留**。迁移完成后 `tools/app_screenshots.py` 的走查职责移交 Maestro，
脚本仅保留 `--dump-ui`（语义树量 bounds，§4.3 量化调优仍需要它）。

---

## 5. 实施顺序与工作量估算

| 阶段 | 内容 | 预估 | 依赖 |
| :--- | :--- | :--- | :--- |
| 1 | §1 语义标识基建 + P1 冒烟三测 + 变异验证 | ~1 人日 | 无 |
| 2 | P2 Roborazzi：依赖 + 首批 6~8 张组件快照 + baseline | ~1 人日 | 阶段 1 的 tag 部分非必需 |
| 3 | P3 Maestro 试点 1~2 条 flow + 两周评估 | ~0.5 人日 + 观察期 | 阶段 1 的 testTagsAsResourceId |
| 4 | （视评估结果）迁移走查 / 更新 AGENTS.md §4、§9 | 另计 | 阶段 3 结论 |

每阶段独立提交（§7 分步提交纪律），阶段 1 完成时同步在 AGENTS.md §9 收口自检追加
`connectedDebugAndroidTest` 一项。

## 6. 明确不做的事

- **不上 Appium / Detox / 商业视觉对比**：单平台单应用，复杂度不值。
- **不迁移**现有 JUnit 4 + Robolectric 测试到 JUnit 5（AGENTS.md §3 既有决策，风险大于收益）。
- **不做全量 testTag 覆盖**：只加"无文本可依"的交互元素；文本本身是合法定位点。
- **不追求快照覆盖全部页面**：纯展示组件优先，整页快照以 state hoisting 完备为前提。
- 本项目未发布，无需任何兼容旧快照 / 旧报告的迁移逻辑。
