# 深色语义色整改方案（FIX-PLAN 3-5 / 3-6）+ 主题模式切换项

> ## ⚠️ 本文结论已被 2026-10-04 14:30 的落地取代 —— 请先读这一段
>
> **最终采用的是本文「第三、四节」之外的更简方案**：把 `SuccessGreen` / `WarningAmber`
> 两组自定义语义色**整组删除**，直接用 M3 预定义槽位 ——
> 完成/已服 → `primary` / `primaryContainer` / `onPrimaryContainer` / `onPrimary`，
> 告急/漏服/禁忌 → `error` / `errorContainer` / `onErrorContainer` / `onError`；
> 同时新增「设置 → 外观 → 主题模式」供 App 内切换深浅。
>
> **本文保留的价值**（结论与落地一致的部分）：
> - 第一节的**根因分析**：同一常量被用在两种明度需求相反的角色上（前景 vs 实心填充）。
> - 第三节的**数学不可能性证明**：单一色值无法同时满足浅底 ≥4.5:1 与深底 ≥4.5:1
>   （需 `L ≤ 0.161` 与 `L ≥ 0.22`，无交集）。这正是"必须两套色"的根据，
>   而 M3 色板天然一次生成两套。
> - 第二节 2.4 的 **MyTherapy 实测取色**与 2.1–2.3 的改动前基线数据。
>
> **本文作废的部分**：第四节「按角色拆分语义色槽位（`success` / `successSolid` / …）」
> 与 §4.2 的 MyTherapy 取值表 —— 拆出 10 个槽位属于过度设计，
> M3 已有成对且自动适配深浅的槽位，不需要自建一套。§4.3 的调用点映射表仍然有效。
>
> 落地记录见 `docs/CHANGES-20261004.md` 的 14:30 节。

---

> **原始制定**：2026-10-04 (GMT+8)
> **来源**：`docs/FIX-PLAN-20261004.md` 3-5 / 3-6（[未开始]，均标注「需按当前成图重测」）
> **性质**：**方案文档，未修改任何生产代码**
> **前置**：`docs/THEME-COLOR-SPEC-20261004.md`、`docs/COLOR-THEME-REFACTOR-20261004.md`

---

## 一、根因（重新核实后的结论）

`ui/theme/Color.kt` 里的 8 个语义色常量（`SuccessGreen` / `SuccessGreenContainer` /
`OnSuccessGreenContainer` / `WarningAmber` / `WarningAmberContainer` / `OnWarningAmber` /
`OnWarningAmberContainer` / `WarningAmberBorder`）**只有一套固定色值，没有深色变体**，
且被直接引用（不经过 `MaterialTheme.colorScheme`）。

真正的结构性问题是：**同一个常量被用在两种互斥的角色上**。

| 角色 | 需求 | 调用点（已核实） |
| :--- | :--- | :--- |
| **A. 前景**（文字 / 图标，压在浅色卡片上） | 浅色模式下必须**够深**；深色模式下必须**够亮** | `InventoryScreen.kt:528`、`MedicationDetailScreen.kt:543,912,971`、`ProgressScreen.kt:613` |
| **B. 实心填充**（当底色，上面压白字） | 必须**足够深**才能承载白字，两种模式都需要 | `AlarmAlertActivity.kt:373`、`DoseHistoryCalendarSheet.kt:222,232`、`ProgressScreen.kt:409,444`、`StatsScreen.kt:356,372` |
| **C. 容器/前景成对** | 自成一对，与页面底色无关 | `DoseHistoryCalendarSheet.kt:227-228`、`ProgressScreen.kt:432-436`、`DoseRecordDetailScreen.kt:225-246` |

角色 A 与角色 B 对明度的要求**方向相反**，塞进同一个常量必然顾此失彼 ——
这就是 3-5 与 3-6 会同时出现的机制。

`OnWarningAmber` 与 `WarningAmberBorder` 经全量检索**无任何引用**（仅 `Color.kt` 定义），
属死常量。

---

## 二、实测数值复核（已完成）

**复算方法**：色板用本地 `@material/material-color-utilities` 0.4.0（`temp/mcu_tool/`）
对种子色 `#4A7A00` 重新生成并逐项比对 —— 与 `COLOR-THEME-REFACTOR` 的色阶表**完全一致**
（N-4 `#0D0F0B`、N-6 `#121410`、N-10 `#1B1C18`、N-12 `#1F201C`、N-17 `#292A26`、N-22 `#343530`）。
对比度按 WCAG 2.1 计算（sRGB 线性化 + 相对亮度比），并按各调用点**逐层 alpha 混合**求出真实底色。
脚本：`temp/contrast_check.py`、`temp/contrast_check2.py`。

### 2.1 3-5 —— 今日页「已服」徽章

底链：`SuccessGreen@12%` 叠在 `surfaceContainerLow@60%` 叠在 `surface` 上；字 `OnSuccessGreenContainer`。

| 模式 | 徽章实际底色 | 对比度 | AA(4.5:1) |
| :--- | :--- | :-: | :-: |
| 浅色 | `#DCECDA` | **7.40:1** | ✅ |
| 深色 | `#172A1B` | **1.67:1** | ❌ |

> 报告中为 1.50:1；差异来自父层叠色的细微假设，量级与结论一致。
> **另一个已验证的结论**：单纯提高 `SuccessGreen` 的 alpha **没用** ——
> `@20%` → 1.47:1、`@25%` → 1.35:1，越加越糟（底色越绿、深绿字越糊）。
> 必须换前景色或换容器色。

### 2.2 3-6 —— 语义色当正文

| 调用点 | 底色 | 浅色模式 | 深色模式 |
| :--- | :--- | :-: | :-: |
| `InventoryScreen.kt:528` `SuccessGreen` | `surfaceVariant@35%` 叠卡片 `#EFF0E5` | **2.87:1** ❌ | 4.46:1 ❌ |
| `MedicationDetailScreen.kt:543` `SuccessGreen` | 卡片 `#F5F4EC` | **2.99:1** ❌ | 5.20:1 ✅ |
| `MedicationDetailScreen.kt:912` `WarningAmber` | 卡片 `#F5F4EC` | **2.89:1** ❌ | 5.38:1 ✅ |

### 2.3 角色 B（实心填充 + 白字）—— 顺带发现的同类问题

| 用途 | 对比度 | 说明 |
| :--- | :-: | :--- |
| 白字 on `SuccessGreen #16A34A` | 3.30:1 | 18sp Bold 只需 3:1（`AlarmAlertActivity:373` 勉强过）；**日历/进度里的小字不达标** |
| 白字 on `WarningAmber #D97706` | **3.19:1** | 同上（`DoseHistoryCalendarSheet:232`、`ProgressScreen:444`） |

### 2.4 MyTherapy 参考配色实测（2026-10-04 补）

参照对象：`F:\Temp\mytherapy\` 的 4 张真机截图（2 浅色 + 2 深色，MyTherapy app）。
取样方式：Pillow 按区域取众数（底色）+ 取离底色最远的像素（前景），脚本
`temp/sample_mytherapy.py`、`temp/sample2_mytherapy.py`、`temp/sample3_mytherapy.py`，
对照裁切图 `temp/mytherapy_crops/compare.png`、`zoom.png`。

**实测结果**

| 语义 | 浅色 | 深色 | 说明 |
| :--- | :--- | :--- | :--- |
| 页面底 | `#F8F4F1` | `#141414` | 浅色暖米白、深色近纯黑 |
| 卡片底 | `#FFFFFF` | `#2C2C2C` | 深色下卡片与底色的明度差**明显大于** CarroMed 现状 |
| **完成态实心圆** | `#5CC480` | `#8CF8C4` | 深色下反而更亮更饱和 |
| 圆内前景（勾） | `#FFFFFF`（**仅 2.17:1，不达标**） | `#06301C` | 见下方警告 |
| **部分完成淡绿条** | `#C0ECD8` | `#486C5C` | 深色下是压暗的墨绿，不是浅绿 |
| 强调/选中底 | `#644438`（深棕） | `#F8DCD0`（浅桃） | **成对反转** |
| 强调底上的前景 | `#FFFFFF`（8.66:1） | `#21201C` | 同上 |
| 桃色容器 | `#F8E8E0`，字 `#773C10`（7.22:1） | 棕底 `#60382C`，字 `#FBDDD3`（7.80:1） | 同上 |
| 导航激活色 | ≈`#C8323E` | ≈`#C4625E` | — |

**三条可直接复用的结论**

1. **它的深/浅不是「同一色变亮变暗」，而是整对反转**：浅色 = 深底 + 白字，
   深色 = 浅底 + 深字；绿实心也是浅色中绿配白勾、深色薄荷配深勾。
   这从外部佐证了本方案「按角色成对 + 分模式提供」的方向是对的。
2. **深色卡片与底色的明度差必须够大**：MyTherapy 是 `#141414` → `#2C2C2C`（差 0x18），
   而 CarroMed 现在是 `#121410` → `#1B1C18`（差 0x09）。这是本次范围外的观感问题，
   但值得单独记一笔（见 §八）。
3. ⚠️ **MyTherapy 自己的浅色绿实心 + 白勾只有 2.17:1**，连图形对象 3:1 都不到，
   直接照搬会把 CarroMed 走查清单里的「对比度」一项做坏。因此**只取它的绿色值，
   前景按 WCAG 修正**（见 §4.2）。

---

## 三、为什么「没有两套色」修不了 3-6（数学上不可行）

要把 3-6 的正文色压到浅色底上 ≥4.5:1，需要 `L ≤ 0.161`；
要让同一颜色在深色底上 ≥4.5:1，需要 `L ≥ 0.22`。两者无交集。

> 结论：**单一静态常量无法同时满足双模式**。3-6 不存在「只改一个十六进制」的修法，
> 引入深浅两套语义色是唯一出路。3-5 则可以在不改架构的前提下用「成对容器」单独修掉，
> 但既然 3-6 已强制要求双套色，两处应一并按同一套架构做。

---

## 四、建议架构：语义色收进主题，按明暗两套提供

### 4.1 数据结构

`ui/theme/Color.kt` 改为按**角色**拆分，而不是按颜色名：

```kotlin
@Immutable
data class SemanticColors(
    val success: Color,             // 角色 A：正文 / 图标
    val successContainer: Color,    // 角色 C：容器
    val onSuccessContainer: Color,  // 角色 C：容器上的字
    val successSolid: Color,        // 角色 B：实心填充底
    val onSuccessSolid: Color,      // 角色 B：填充上的前景
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val warningSolid: Color,
    val onWarningSolid: Color
)

val LightSemanticColors = SemanticColors(...)
val DarkSemanticColors = SemanticColors(...)

val LocalSemanticColors = staticCompositionLocalOf { LightSemanticColors }
```

`ui/theme/Theme.kt` 在 `MaterialTheme` 外层包一层
`CompositionLocalProvider(LocalSemanticColors provides if (darkTheme) DarkSemanticColors else LightSemanticColors)`，
并暴露 `val MaterialTheme.semanticColors get() = LocalSemanticColors.current`。

这样 `darkTheme` 只有一个真源（与 `rememberDynamicColorScheme`、状态栏图标同源），
不会出现「主题是深的、语义色还是浅的」这类分叉。

### 4.2 建议取值 —— **对齐 MyTherapy 实测色**（全量复算，一次全过）

绿色直接采用 §2.4 的实测值，前景按 WCAG 修正；琥珀色 MyTherapy 截图里没有警示态，
保留 CarroMed 独立的医学警戒色。脚本：`temp/final_palette_check.py`。

| 槽位 | 浅色 | 深色 | 实测对比度 |
| :--- | :--- | :--- | :--- |
| `success`（正文 / 图标） | `#176B43` | `#8CF8C4` | 浅 5.67–6.52:1；深 10.87–14.42:1 |
| `successContainer` | `#C0ECD8` | `#2F5744` | MyTherapy 淡绿条原值；深色压深一档取余量 |
| `onSuccessContainer` | `#14532D` | `#C0ECD8` | 7.04:1 / 6.32:1 |
| `successSolid`（实心填充） | `#5CC480` | `#8CF8C4` | MyTherapy 实心圆原值 |
| `onSuccessSolid` | `#06301C` | `#06301C` | 6.68:1 / 11.29:1 |
| `warning`（正文 / 图标） | `#92400E` | `#FBBF24` | 浅 6.17–7.09:1；深 8.37–11.10:1 |
| `warningContainer` | `#FEF3C7` | `#4A3418` | — |
| `onWarningContainer` | `#92400E` | `#FDE68A` | 6.37:1 / 9.39:1 |
| `warningSolid` | `#B45309` | `#FBBF24` | — |
| `onWarningSolid` | `#FFFFFF` | `#3A2A08` | 5.02:1 / 8.31:1 |

**取值说明（三处偏离 MyTherapy 原版，均有数据理由）**

1. **浅色实心绿的前景改用深色 `#06301C`，不用白**。
   MyTherapy 原版是白勾 on `#5CC480` = **2.17:1**，连图形对象 3:1 都不达标；
   换深勾后 6.68:1，且与它深色的做法一致（深色本来就是深勾）。
   > 若坚持浅色要「绿底白字」的原版观感，实心底必须压深到 `#27854F`（白字 4.61:1）——
   > 但那就不再是 MyTherapy 的绿了，需你拍板二选一。
2. **浅色正文绿 `#176B43` 是从 MyTherapy 绿 `#5CC480` 同色相压深派生的**：
   MyTherapy 从不把绿当正文色，没有现成值。`#176B43` 在 CarroMed 最不利底
   （`surfaceVariant@35%` 的 `#EFF0E5`）上 5.67:1，留了余量。
   > 顺带说明：`#1E7A4C`（更接近原绿）在该底色上只有 4.63:1，**贴线**，不取。
3. **深色 `successContainer` 由 MyTherapy 的 `#486C5C` 压深到 `#2F5744`**：
   原值配 `#C0ECD8` 只有 4.54:1，做小徽章文字太贴线；压深一档后 6.32:1。

**顺带的收益**：`successSolid` 与 `warningSolid` 都从原来的 3.2–3.3:1 提到 5.0–6.7:1，
日历数字格与进度态小字一并达标（原先只有 18sp Bold 的按钮勉强过）。

### 4.3 调用点改造映射

| 现状 | 改为 | 涉及文件 |
| :--- | :--- | :--- |
| `SuccessGreen` 当正文 | `semanticColors.success` | `InventoryScreen:528`、`MedicationDetail:543,912,971`、`ProgressScreen:613` |
| `WarningAmber` 当正文 | `semanticColors.warning` | `MedicationDetail:912` |
| `SuccessGreen@12%` + `OnSuccessGreenContainer`（徽章） | `successContainer` + `onSuccessContainer` | `TodayScreen.kt:828-836` |
| `X` 当实心填充 + 白字 | `successSolid` / `warningSolid` + `on*Solid` | `AlarmAlertActivity:373`、`DoseHistoryCalendarSheet:222,232`、`ProgressScreen:409,444` |
| `WarningAmberContainer` + `OnWarningAmberContainer` | `warningContainer` + `onWarningContainer` | Today / Cabinet / Inventory / MedicationDetail / DoseRecordDetail / TodayStreakBadge / DoseHistoryCalendarSheet |
| `SuccessGreenContainer` + `OnSuccessGreenContainer` | `successContainer` + `onSuccessContainer` | `DoseHistoryCalendarSheet:227-228`、`ProgressScreen:432-436`、`DoseRecordDetailScreen:225-226`、`MedHistoryScreen` |
| `OnWarningAmber` / `WarningAmberBorder` | **删除**（零引用） | `Color.kt` |

「图标衬底」这类 `X.copy(alpha=0.15f)` 的写法（`TodayScreen:868`、
`MedicationDetailScreen:844`）直接用模式相关的 `success` / `warning`，
不新增槽位。

> 注意：实心填充类调用点里，`AlarmAlertActivity:373` 的「确认已服」按钮与
> `DoseHistoryCalendarSheet:222` 的日历数字格都要**同时**换成 `on*Solid` 前景——
> 现在它们靠 `ButtonDefaults` 默认的 `onPrimary`（白）和硬编码的 `Color.White`，
> 不改前景就会出现「深字压深底」的相反错误。

---

## 五、主题模式切换项（解决「不好测」）

**问题**：`CarroMedTheme` 的 `darkTheme` 现在硬绑 `isSystemInDarkTheme()`，
App 内没有开关 —— 测深色只能去改模拟器系统设置，且改完会连累其它应用。

### 5.1 取值与存储

新增枚举 + 一个轻量存储，**不进 Room**：

```kotlin
enum class ThemeMode { SYSTEM, LIGHT, DARK }
```

存 **SharedPreferences**（`carromed_ui` / `theme_mode`），理由有二：

1. `MainActivity.onCreate` 里必须能**同步**取到值再 `setContent`。
   Room 是异步的，用 Flow 收集会在冷启动时先按系统主题渲染一帧再翻转 ——
   而 `CHANGES-20261002 §二-31` 加 `values-night/themes.xml` 的目的正是
   「消除深色模式冷启动白闪」，新引入一个闪烁源会把那条改动打回去。
2. 主题模式是**设备级外观偏好**，不属于要进备份/恢复的业务数据。

`MainActivity` 里已有 `permissionPrefs` 这个同性质的先例，口径一致。

### 5.2 接线

- `MainActivity`：`val mode = remember { mutableStateOf(ThemePreference.read(this)) }`（初值同步读），
  `CarroMedTheme(darkTheme = mode.value.resolveDark(isSystemInDarkTheme()))`；
  注册 `OnSharedPreferenceChangeListener` 让设置页改动即时生效。
- `SettingsViewModel`：`AndroidViewModel` 已有 `getApplication()`，
  **不需要加构造参数**（避免 AGENTS §四「默认参数不生成单参 Java 构造器」那个真机崩的坑）。
  `SettingsUiState` 加 `themeMode: ThemeMode`，`loadSettings()` 里一并读回。
- `SettingsScreen`：新增一张 `ElevatedCard`（与现有 3 张同款式），
  标题「外观 / 主题模式」，三档 `ExposedDropdownMenuBox` 或 `SingleChoiceSegmentedButtonRow`：
  跟随系统 / 浅色 / 深色。

### 5.3 已知限制（需在走查里确认，不阻止合并）

系统深色 + App 强制浅色时，Activity 窗口背景仍取 `values-night`，
Compose 首帧前可能有极短的一帧深色。若要彻底消除需另做（`AppCompatDelegate`
对纯 Compose 的 `ComponentActivity` 不生效），本方案先如实记录。

---

## 六、落地步骤（建议顺序）

| 步 | 内容 | 验收 |
| :-- | :--- | :--- |
| 1 | `Color.kt` 加 `SemanticColors` + 双套值；`Theme.kt` 注入 | 编译通过 |
| 2 | 改 5 个文件的调用点（§4.3 映射表）；删死常量 | 双编译 + 走查 |
| 3 | 加 `ThemePreference` + `MainActivity` 接线 | 切档即时生效 |
| 4 | 设置页加「主题模式」卡片 | 走查截图 |
| 5 | 走查脚本加**深色 pass**：切到深色后重截今日 / 库存 / 药品详情 / 设置，逐张看图 | 无截断/无糊字 |
| 6 | 同步更新 `THEME-COLOR-SPEC-20261004.md` WL-3/WL-4（白名单条目由「单值」改为「双模式成对槽位」） | 文档一致 |

**硬标准**（沿用 `FIX-PLAN` 每批口径）：`assembleDebug` + `assembleRelease` 双通过 →
`testDebugUnitTest` 全绿 → 走查截图并**亲眼看图** → `docs/CHANGES-<今日>.md` 顶部追加摘要。

> 走查脚本登记：本项**不新增全屏页**，但新增了一个设置项与一次深色截图 pass，
> 需改 `scripts/app_screenshots.py` 的设置页 `Step`（当前 `key="settings"`，`shots=2`）
> 并补深色步骤，否则深色形态永远不会被自动验证到（DEVGUIDE §4.1 同一理由）。

---

## 七、待用户确认

1. **浅色实心绿的前景二选一**（§4.2 说明 1）：
   - A（推荐）：保留 MyTherapy 的 `#5CC480` + 深前景 `#06301C`（6.68:1），
     观感与 MyTherapy 的「亮绿底 + 深勾」一致，但「确认已服」按钮不再是绿底白字；
   - B：浅色实心压深到 `#27854F` + 白字（4.61:1），保住"绿底白字"的按钮观感，
     但绿色偏离 MyTherapy 原值。
2. 主题切换项与语义色整改是否**同一个 PR** 做（建议同做，否则深色无法验收）。

---

## 八、范围外但本次核对中发现的观感差异（仅记录，不建议合并进来）

1. **深色下卡片与底色的明度差偏小**：MyTherapy 是 `#141414` → `#2C2C2C`（差 0x18），
   CarroMed 是 `#121410` → `#1B1C18`（差 0x09），卡片"浮不起来"。
   若要改，动的是 `surfaceContainerLow` 的映射，属于主题级调整，
   会影响全部页面，应单独立项并全量走查。
2. **MyTherapy 深色页面底 `#141414` 是中性灰**，CarroMed 的 `#121410` 带一点绿相
   （种子色浸润）。两者观感接近，不建议改 —— 色相浸润是 `THEME-COLOR-SPEC` 明确要保留的。
3. **MyTherapy 的强调色是棕色系 + 桃色容器**，与 CarroMed 的草本绿品牌无关，
   不建议移植；本次只取它的**语义状态色**（完成态绿 / 淡绿条）与「成对反转」的做法。

---

*制定：2026-10-04 (GMT+8) · 未修改任何生产代码*
*2026-10-04 更新：绿色系改为对齐 `F:\Temp\mytherapy` 的截图实测值（§2.4），并全量复算（§4.2）*
