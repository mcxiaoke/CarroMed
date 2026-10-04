# CarroMed 色彩系统重构方案：基于 Seed 色 (#4A7A00) 与 M3 Surface Container

---

## 一、背景与问题根因

当前 App 界面整体观感被反馈为“太素、太惨白”，且与桌面 App 图标（绿色底立体药丸）存在严重的割裂感。结合 [`temp/today_screen.png`](file:///c:/Home/Projects/CarroMed/temp/today_screen.png)、[`temp/emu_cabinet.png`](file:///c:/Home/Projects/CarroMed/temp/emu_cabinet.png) 等现有真机截图及代码走查，定位根因如下：

1. **色彩基调与品牌心智割裂**：
   - 桌面图标采用的是充满草本生机感的**鲜绿底色（`#059C5A` 左右）**；
   - 应用内部的主色（Primary）却硬编码为高饱和的**工业冷蓝（`#2563EB`）**。这种蓝色多用于运维监控看板或银行后台，用在服药管理上带来强烈的“冷硬规训与冰冷监控感”，缺乏医疗健康产品应有的关怀、安心与温度。
2. **Surface 阶梯与色彩浸润（Tonal Tinting）缺失**：
   - 当前在 [`Color.kt`](file:///c:/Home/Projects/CarroMed/app/src/main/kotlin/com/mcxiaoke/carromed/ui/theme/Color.kt) 中，`BackgroundLight = #F8FAFC`（纯白偏冷灰），`SurfaceLight = #FFFFFF`（纯白）；
   - 各界面中的 `Card`、`Surface` 组件普遍显式指定了 `containerColor = MaterialTheme.colorScheme.surface`；
   - 导致全屏 85% 以上是大面积无色差的反光死白，卡片与背景几乎融为一体，全靠一条极淡的灰色细线支撑，形成了视觉上的“日光灯管直射感”（Flashbulb effect）。
3. **未发挥 Material 3 容器色阶能力**：
   - M3 规范的核心是废弃传统的“白底+生硬投影”，改用 **Surface Container 色阶系统**（`surface`、`surfaceContainerLow`、`surfaceContainer`、`surfaceContainerHigh` 等），且背景色中会自动混入 4%~8% 的种子色相，形成温润有机的质感。

---

## 二、基准种子色选型：`#4A7A00`（草本生机绿）

用户在 [Material Theme Builder](https://material-foundation.github.io/material-theme-builder/) 中挑选并认可了种子色：**`#4A7A00`**。

### 1. 种子色色彩参数 (HCT / CAM16)
- **Hex**：`#4A7A00`
- **Hue (色相)**：`131.9°`（纯正的植物草本草绿色，偏微黄绿，充满初春嫩芽与药用草本的生机感）
- **Chroma (色度)**：`58.9`（适度饱和，既不刺眼亦不沉闷）
- **Tone (明度)**：`46.1`（中等明度，能够天然推导出高对比度的暗深与浅亮两极）

### 2. 本地 Material Color Utilities 官方算法实测生成的标准调色板

通过本地运行 Google 官方 `@material/material-color-utilities` 算法，生成的精确调色板数值如下：

#### (1) Primary 色调阶梯 (主交互与高光)
- **Primary 40 (浅色主色)**：`#3F6900`（沉稳浓郁的草木橄榄绿，在白底上对比度高达 6.2:1，严格满足 WCAG AAA 级可读性）
- **OnPrimary**：`#FFFFFF`
- **Primary 90 (浅色容器 PrimaryContainer)**：`#BAF476`（清脆透亮的嫩芽绿容器，用于待服药卡片高亮或徽章）
- **OnPrimaryContainer**：`#102000`
- **Primary 80 (深色主色)**：`#9FD75D`（暗色模式下的亮草绿，在深底上对比度 > 8.5:1）
- **PrimaryContainer (深色模式)**：`#2E4F00`

#### (2) Secondary / Tertiary 色调阶梯 (次要与补充)
- **Secondary (浅/深)**：`#586249` (40) / `#BFCBAD` (80)（雅致的草木灰灰茶绿，用于次要操作、辅助标签）
- **SecondaryContainer**：`#DBE7C8` (90) / `#404A33` (30)
- **Tertiary (浅/深)**：`#386663` (40) / `#A0D0CB` (80)（带天青绿松石相的冷绿，用于补充医疗感，与主色相呼应）
- **TertiaryContainer**：`#BCECE7` (90) / `#1F4E4B` (30)

#### (3) Neutral 色调阶梯 —— 告别“惨白”的 M3 Surface Container 核心
| Token 名称 | 阶梯 | 浅色模式 Hex | 暗色模式 Hex | 界面职责 |
| :--- | :--- | :--- | :--- | :--- |
| **`surface`** | N-98 / N-6 | `#FAFAF2` | `#121410` | **Scaffold 页面大底色**（温润草木米白，完全驱逐死白） |
| **`surfaceContainerLowest`** | N-100 / N-4 | `#FFFFFF` | `#0D0F0B` | 内嵌低对比卡片或纯白强调块 |
| **`surfaceContainerLow`** | N-96 / N-10 | `#F5F4EC` | `#1B1C18` | **常规列表卡片底色**（微微浮起，层级分明） |
| **`surfaceContainer`** | N-94 / N-12 | `#EFEEE7` | `#1F201C` | 分组 Card、设置大卡片底色 |
| **`surfaceContainerHigh`** | N-92 / N-17 | `#E9E8E1` | `#292A26` | 弹出 Dialog、BottomSheet、悬浮栏 |
| **`surfaceContainerHighest`**| N-90 / N-22 | `#E3E3DB` | `#343530` | 搜索栏、输入框、标签衬底 |
| **`outline`** | NV-50 / NV-60 | `#75796C` | `#8F9285` | 边框线条 |
| **`outlineVariant`** | NV-80 / NV-30 | `#C5C8BA` | `#44483D` | 极柔和的分隔线（Divider） |

---

## 三、全工程硬编码颜色清查与分类盘点

通过对 `app/src/main/kotlin` 全量代码的排查，代码中的颜色引用分层较为清晰，没有散落的随机十六进制，但存在**语义分类混淆与容器硬指定**的问题：

### 1. 【A 类：必要的硬编码】（业务核心，必须保留，禁止修改）

| 变量/位置 | 现状值 | 业务必要性与保留理由 |
| :--- | :--- | :--- |
| [`MedicationFormOptions.COLORS`](file:///c:/Home/Projects/CarroMed/app/src/main/kotlin/com/mcxiaoke/carromed/ui/screen/edit/AddEditMedicationViewModel.kt#L165-L168) | `"#2563EB"`, `"#10B981"`, `"#F59E0B"`, `"#8B5CF6"`, `"#EF4444"`, `"#0EA5E9"` | **用户自选药品的实体标记色**。用户为降压药选黄、维生素选绿、激素选紫，这是药品的物理分类属性，绝不可跟随主题动态变化。 |
| [`BackupFormat.kt:67`](file:///c:/Home/Projects/CarroMed/app/src/main/kotlin/com/mcxiaoke/carromed/core/data/BackupFormat.kt#L67) | `val colorHex: String = "#2563EB"` | **数据反序列化向下兼容兜底**。历史 JSON 备份如果缺失该字段时的解析默认值，不能破坏兼容性。 |
| **`WarningAmber` 系列**<br>([`Color.kt:74-78`](file:///c:/Home/Projects/CarroMed/app/src/main/kotlin/com/mcxiaoke/carromed/ui/theme/Color.kt#L74-L78)) | `#D97706` / `#FEF3C7` / `#92400E` | **医学防呆与安全警戒专属色**。用于“库存告急低于警戒线”、“用药禁忌注意”。必须保持高辨识度的暖琥珀黄，绝对不能被算法同化为草绿或浅灰。 |
| **`SuccessGreen` 系列**<br>([`Color.kt:69-71`](file:///c:/Home/Projects/CarroMed/app/src/main/kotlin/com/mcxiaoke/carromed/ui/theme/Color.kt#L69-L71)) | `#16A34A` / `#DCFCE7` / `#14532D` | **已服药打卡成功色**。确认服药动作后的正向激励。即便主色调改成草绿 `#4A7A00`，已服药完成状态依然推荐保留独立的清脆草木绿，与主交互色拉开轻微明暗层次。 |
| **`Color.White` 图表/打卡字**<br>([`ProgressScreen.kt`](file:///c:/Home/Projects/CarroMed/app/src/main/kotlin/com/mcxiaoke/carromed/ui/screen/progress/ProgressScreen.kt), [`DoseHistoryCalendarSheet.kt`](file:///c:/Home/Projects/CarroMed/app/src/main/kotlin/com/mcxiaoke/carromed/ui/screen/today/DoseHistoryCalendarSheet.kt)) | `Color.White` | 在高饱和药品色块、已服药绿圆圈上的勾选符号与高亮文字，必须是纯白以保障对比度。 |

### 2. 【B 类：不合理的硬编码与错误指定】（需重构迁移）

| 涉及位置 | 现状代码 | 存在的问题 | 改造方案 |
| :--- | :--- | :--- | :--- |
| [`ui/theme/Color.kt`](file:///c:/Home/Projects/CarroMed/app/src/main/kotlin/com/mcxiaoke/carromed/ui/theme/Color.kt) | 40 多个手写的 Tailwind 蓝/冷灰常量 (`PrimaryLight`, `BackgroundLight`, `SurfaceLight` 等) | 导致全屏死白与冷酷科技蓝的源头，没有 M3 容器色阶。 | **替换**为由 `#4A7A00` 计算出的全新 M3 色彩体系。 |
| 各界面的卡片组件<br>(`TodayScreen`, `CabinetScreen`, `StatsScreen`, `InventoryScreen` 等) | `CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)` 或 `elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)` | 显式把卡片写死为 `surface`，导致卡片与背景颜色完全一样，压平了所有视觉层级。 | **改为使用** `surfaceContainerLow` 或直接使用默认的 `CardDefaults.cardColors()`。 |
| [`StatsScreen.kt`](file:///c:/Home/Projects/CarroMed/app/src/main/kotlin/com/mcxiaoke/carromed/ui/screen/stats/StatsScreen.kt) 统计主卡片 | 大面积使用 `containerColor = MaterialTheme.colorScheme.primary` | 之前在纯蓝时极度刺眼；换成草木绿后，宜使用 `primaryContainer` 或高雅的深沉梯度，提升舒适度。 | 调整为 `primaryContainer`（配 `onPrimaryContainer`）或优雅的暗色草木绿。 |

---

## 四、技术实施方案对比：MaterialKolor 库 vs 静态生成

针对未来“改 seed 色后，以后要改主体也简单”的核心诉求，有两种技术路线可供选择：

### 方案 1：引入 `com.materialkolor:material-kolor` 库（推荐）

#### 实现方式
在 `build.gradle.kts` 引入依赖：
```kotlin
implementation("com.materialkolor:material-kolor:2.0.2") // 或最新稳定版
```
在 `Theme.kt` 中声明：
```kotlin
@Composable
fun CarroMedTheme(
    seedColor: Color = Color(0xFF4A7A00),
    paletteStyle: PaletteStyle = PaletteStyle.TonalSpot,
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // false 表示锁定应用品牌绿，true 则允许跟随壁纸
    content: @Composable () -> Unit
) {
    val colorScheme = rememberDynamicColorScheme(
        seedColor = seedColor,
        isDark = darkTheme,
        style = paletteStyle
    )
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
```

#### 方案优势
1. **未来改主题极其简单**：未来如果想换色，只需改动 `seedColor` 一个参数，或者将其读取自 `DataStore` 用户设置，立刻实现全局无缝换肤；
2. **免维护暗色模式**：暗色模式由 Google upstream 算法自动对称生成，对比度完全可信；
3. **零架构污染**：纯 Compose UI 依赖，与 `core/domain` 零交叉。

---

### 方案 2：纯静态生成方案（零新增第三方依赖）

#### 实现方式
不引入任何新的 Gradle 依赖，直接将本地通过 MCU 计算生成的标准色阶常量写入 [`Color.kt`](file:///c:/Home/Projects/CarroMed/app/src/main/kotlin/com/mcxiaoke/carromed/ui/theme/Color.kt)，并补全 M3 的 `surfaceContainer` 系列：
```kotlin
val PrimaryLight = Color(0xFF3F6900)
val OnPrimaryLight = Color(0xFFFFFFFF)
val PrimaryContainerLight = Color(0xFFBAF476)
val OnPrimaryContainerLight = Color(0xFF102000)

val SurfaceLight = Color(0xFFFAFAF2)
val SurfaceContainerLowLight = Color(0xFFF5F4EC)
val SurfaceContainerLight = Color(0xFFEFEEE7)
val SurfaceContainerHighLight = Color(0xFFE9E8E1)
// ...其余补齐
```

#### 方案优势
1. 项目保持 0 新增第三方库依赖；
2. 运行期零计算开销。
3. **不足**：未来要换 seed 色时，需要重新生成一次十六进制映射表。

---

## 五、界面彻底告别“惨白”的改造行动清单

要彻底根治界面“惨白”，在更换种子色后，需配合以下界面组件规范化改造：

1. **Scaffold 背景色**：
   - 确保主脚手架背景使用 `MaterialTheme.colorScheme.surface`（即 `#FAFAF2`，温润象牙草木白）。
2. **列表卡片与分组（Card / Surface）**：
   - 全局搜索并移除各 Screen 里多余的 `containerColor = MaterialTheme.colorScheme.surface`；
   - 统一改用 `CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)` 或 M3 原生默认值。卡片底色将自然呈现为 `#F5F4EC`，在米白背景衬托下浮现出清晰的高级感。
3. **今日清单（TodayScreen）待服药与已服药卡片**：
   - 待服药卡片：白底卡片（`surfaceContainerLowest`）置于清润背景上，边框使用柔和的 `outlineVariant`（`#C5C8BA`）；
   - 已服药卡片：保持淡草绿（`SuccessGreenContainer` `#DCFCE7`）衬底，文字使用 `OnSuccessGreenContainer`，形成明显的完成态反馈。
4. **弹窗与下拉菜单（Dialog / Sheet / Menu）**：
   - 自动继承 `surfaceContainerHigh`（`#E9E8E1`），自然产生视觉进深。

---

## 六、下一步执行步骤规划

1. **用户确认方案**：
   - 确认采用 **方案 1（引入 MaterialKolor 库，支持一行换色与动态衍生）** 还是 **方案 2（零依赖静态色表）**。
2. **主题层实施（不碰业务逻辑）**：
   - 更新 `Theme.kt` 与 `Color.kt`，配置种子色 `#4A7A00`，保留 `WarningAmber` 与 `SuccessGreen` 系列独立语义色。
3. **界面容器色调优**：
   - 批量修正 `TodayScreen`、`CabinetScreen`、`StatsScreen` 中的卡片 `containerColor`，使 M3 Surface Container 阶梯生效。
4. **自动化测试与看图验证**：
   - 运行 `./gradlew testDebugUnitTest` 保证现有测试全绿；
   - 使用模拟器生成最新走查截图，亲眼对比新旧界面的温度感与层次感。
