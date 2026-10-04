# CarroMed 主题与颜色系统规范 (THEME & COLOR SPEC)

> 适用版本：CarroMed 1.0+  
> 生效日期：2026-10-04  
> 规范等级：**强制架构规范 (UI Architecture Rule)**

---

## 一、系统设计理念与愿景

作为一款专注个人用药提醒与库存管理的健康管理应用，CarroMed 的色彩系统遵循以下三大原则：

1. **温润生命力（Vitality & Reassurance）**：
   - 摒弃冰冷生硬的工业监控蓝与惨白日光灯感。
   - 采用植物草本与生命初芽的**种子色 `#4A7A00`**，使界面具备自然、安心、亲和的健康守护感，并与桌面应用图标（绿色立体药丸）建立统一步调的视觉心智。
2. **严整阶梯感（Tonal Surface Hierarchy）**：
   - 彻底告别“全屏死白”与“平铺生硬描线”。
   - 依托 Material Design 3 的 **Surface Container 色阶系统**，通过背景与容器之间细腻的明度差（Tonal Elevation）构建自然的层次进深，减轻长时间查看服药清单的视觉疲劳。
3. **绝对安全性（Safety & Determinism）**：
   - 严禁界面主题色侵蚀药品的物理识别色与医学安全警戒色。
   - 药品色标、库存警戒、打卡反馈具备固定的色彩语义，无论主题如何切换均保持最高识别度与 WCAG AAA 级对比度。

---

## 二、架构核心准则：全面采用 Material Color Scheme (MCS)

### 1. 全局单一种子色生成体系

应用的主题由 `com.materialkolor:material-kolor`（基于 Google 官方 `material-color-utilities` 算法）根据种子色动态计算生成浅色与深色模式下的完整 Material 3 调色板：

```kotlin
// 默认品牌基准种子色
val DefaultSeedColor = Color(0xFF4A7A00) // 草本生机绿 (Hue: 132°, Chroma: 58.9, Tone: 46.1)
```

在 `Theme.kt` 中通过 `rememberDynamicColorScheme` 统一挂载至 `MaterialTheme.colorScheme`：

```kotlin
@Composable
fun CarroMedTheme(
    seedColor: Color = DefaultSeedColor,
    paletteStyle: PaletteStyle = PaletteStyle.TonalSpot,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = rememberDynamicColorScheme(
        seedColor = seedColor,
        isDark = darkTheme,
        isAmoled = false,
        style = paletteStyle
    )
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
```

### 2. 界面颜色引用的唯一入口

- **所有界面组件、文本、图标、边框、分割线及背景颜色，必须 100% 通过 `MaterialTheme.colorScheme.*` 获取**。
- **严禁在页面或组件层直接硬编码十六进制颜色（例如 `Color(0xFF...)`）**。
- **严禁为了特定局部视觉私自声明孤立的颜色常量**。

---

## 三、硬编码颜色白名单（受限场景与业务理由）

为保证药品物理属性与医疗安全逻辑的确定性，全工程仅允许在以下 **3 处有限场景** 中使用独立的硬编码颜色，其余任何位置出现硬编码皆视为 Lint/Review 违规。

> **2026-10-04 修订**：原先的 `SuccessGreen` / `WarningAmber` 两组自定义语义色**已整组删除**。  
> 它们只有一套固定色值、没有深色变体，导致深色模式下「已服」徽章对比度仅 1.67:1、  
> 浅色模式下语义绿当正文色只有 2.87–2.99:1（详见 `FIX-PLAN-20261004.md` 的 3-5 / 3-6）。  
> 现在统一改用 M3 预定义槽位，见 §三-1。

### 白名单详细清单

| 场景编号     | 符号/位置                                                                                                                                                                | 允许的颜色值                                                                       | 业务必要性与保留理由                                                                                                                                                                                        |
| :------- | :------------------------------------------------------------------------------------------------------------------------------------------------------------------- | :--------------------------------------------------------------------------- | :------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **WL-1** | [`MedicationFormOptions.COLORS`](file:///c:/Home/Projects/CarroMed/app/src/main/kotlin/com/mcxiaoke/carromed/ui/screen/edit/AddEditMedicationViewModel.kt#L165-L168) | `"#2563EB"`, `"#10B981"`, `"#F59E0B"`, `"#8B5CF6"`, `"#EF4444"`, `"#0EA5E9"` | **药<u>品实体的物理色标选项</u>**<u>。<br />用户为降压药选黄色、维生素选绿色、降糖药选紫色，这是药品的真实世界识别属性，</u>**<u>绝对不能</u>**<u>随系统或主题切换而变化，否则会引发</u>服药混淆，危及生命安全。                                                                     |
| **WL-2** | [`BackupFormat.DEFAULT_MEDICATION_COLOR`](file:///c:/Home/Projects/CarroMed/app/src/main/kotlin/com/mcxiaoke/carromed/core/data/BackupFormat.kt#L67)                 | `"#2563EB"`                                                                  | **数据反序列化与历史备份兼容**。<br />历史导出的 JSON 数据可能缺少某些字段，提供固定的 Hex 默认值以保障版本兼容，属于底层数据协议。                                                                                                                      |
| **WL-3** | **`Color.White`**<br />(进度图表、打卡勾选标识)                                                                                                                                 | `Color.White`                                                                | **实体色块上的高对比度前景**。<br />在用户自选的高饱和药品色块、已服药圆形打卡徽标内部的勾选符号与纯白数字，确保在任何背景色上均达到 WCAG AAA 级（对比度 > 7:1）。<br />⚠️ 压在 **M3 槽位**（`primary` / `error` / `outline`）上的前景**不算白名单**，必须用配对的 `onPrimary` / `onError`。 |

### 1. 语义状态色 → M3 预定义槽位（强制映射）

界面上的「完成 / 告警」语义**不再使用私有常量**，一律按下表取色。浅色与深色两套由  
`rememberDynamicColorScheme` 从种子色一并推导，天然成对，不会出现「漏了深色变体」。

| 语义                   | 实心底       | 其上前景                                | 容器底                | 容器上的前景               | 正文 / 图标            |
| :------------------- | :-------- | :---------------------------------- | :----------------- | :------------------- | :----------------- |
| **已服 / 完成（正向）**      | `primary` | `onPrimary`                         | `primaryContainer` | `onPrimaryContainer` | `primary`          |
| **告急 / 漏服 / 禁忌（警示）** | `error`   | `onError`                           | `errorContainer`   | `onErrorContainer`   | `error`            |
| **中性 / 跳过**          | `outline` | `onSurface`（或 `Color.White`，见 WL-3） | `surfaceVariant`   | `onSurfaceVariant`   | `onSurfaceVariant` |

M3 没有 warning 槽位：CarroMed 的「库存告急 / 漏服 / 依从率偏低」全部归入 `error` ——  
对医疗 App 而言，这些本就是负面告警，用红色比琥珀色更不易被忽略。

---

## 四、Surface Container 色阶使用指引（拒绝死白）

M3 彻底弃用了“纯白背景 + 生硬阴影”的旧模式，采用带有轻微色相浸润（Tonal Tint）的温润色阶：

```
[surface] (大底色：象牙草木白 #FAFAF2)
    └── [surfaceContainerLow] (基础卡片：微浮起 #F5F4EC)
            └── [surfaceContainer] (内嵌子卡片/表单组 #EFEEE7)
                    └── [surfaceContainerHigh] (对话框/浮层 #E9E8E1)
                            └── [surfaceContainerHighest] (输入框/搜索栏 #E3E3DB)
```

### 1. 组件色彩映射规范

| 界面层级 / 组件                 | 推荐使用的 ColorScheme Token                                              | 说明与视觉效果                                                          |
| :------------------------ | :------------------------------------------------------------------- | :--------------------------------------------------------------- |
| **Scaffold 大背景**          | `MaterialTheme.colorScheme.surface`                                  | 浅色下为 `#FAFAF2`（温润草木米白），深色下为 `#121410`。杜绝冷光刺眼死白。                  |
| **通用列表卡片**                | `MaterialTheme.colorScheme.surfaceContainerLow`                      | `PendingDoseCard`、`MedicationCard` 等。在 `surface` 背景衬托下自然浮起，层级分明。 |
| **分组大卡片 / 设置区块**          | `MaterialTheme.colorScheme.surfaceContainerLow` 或 `surfaceContainer` | 形成结构清晰的视觉岛屿（Card Island）。                                        |
| **弹窗 / 底部抽屉 (Sheet)**     | `MaterialTheme.colorScheme.surfaceContainerHigh`                     | 浮于页面最上层的临时交互组件。                                                  |
| **输入框 / 搜索条 (TextField)** | `MaterialTheme.colorScheme.surfaceContainerHighest`                  | 为用户提供直观的可键入凹入感。                                                  |
| **柔和边框与分割线**              | `MaterialTheme.colorScheme.outlineVariant`                           | 代替过去纯黑/纯灰的生硬边框（浅色下为 `#C5C8BA`）。                                  |

### 2. 交互操作色映射规范

| 交互类型                 | 容器 Token                           | 前景内容 Token                         | 使用场景              |
| :------------------- | :--------------------------------- | :--------------------------------- | :---------------- |
| **主要操作 (Primary)**   | `colorScheme.primary`              | `colorScheme.onPrimary`            | 确定服药、保存药品、核心 FAB。 |
| **高亮展示容器**           | `colorScheme.primaryContainer`     | `colorScheme.onPrimaryContainer`   | 待服药时间高亮、重要服药提示条。  |
| **次要操作 (Secondary)** | `colorScheme.secondaryContainer`   | `colorScheme.onSecondaryContainer` | 稍后提醒、次级筛选过滤 Chip。 |
| **中立辅助操作**           | `colorScheme.surfaceContainerHigh` | `colorScheme.onSurface`            | 取消、返回、查看历史。       |

---

## 五、代码审查检查清单 (Anti-Patterns / 避坑指南)

在编写或审查 Compose 代码时，出现以下代码必须立即纠正：

### ❌ 错误示范 1：在 Card 上硬编码 `surface` 导致惨白扁平

```kotlin
// 错误：显式指定 surface，使得卡片颜色与外层 Scaffold 颜色完全相同，失去所有立体层次
Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
)
```

**✅ 正确写法：**

```kotlin
Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
)
```

### ❌ 错误示范 2：随手定义十六进制或私有色

```kotlin
// 错误：在 Screen 文件顶部定义 private val CardBg = Color(0xFFF3F4F6)
Text(text = "标题", color = Color(0xFF333333))
```

**✅ 正确写法：**

```kotlin
Text(text = "标题", color = MaterialTheme.colorScheme.onSurface)
```

### ❌ 错误示范 3：在深色模式下使用不可适配的前景

```kotlin
// 错误：只考虑了浅色，深色模式下一团漆黑看不清
Text(text = "说明", color = Color.DarkGray)
```

**✅ 正确写法：**

```kotlin
Text(text = "说明", color = MaterialTheme.colorScheme.onSurfaceVariant)
```

---

## 六、未来换肤扩展指南

本架构保证了未来改动色彩的极简性与低风险性：

1. **若需更换全局基准色**：
   - 仅需修改 [`Color.kt`](file:///c:/Home/Projects/CarroMed/app/src/main/kotlin/com/mcxiaoke/carromed/ui/theme/Color.kt) 中的 `DefaultSeedColor`，全工程立即自动衍生出成套的、满足对比度的深浅双模调色板。
2. **若需支持用户自选多套主题色**：
   - 在用户偏好（DataStore）中保存选中的 `seedColor: Long`；
   - 在 `MainActivity.kt` 传入 `CarroMedTheme(seedColor = userPreferredColor)` 即可，**业务逻辑与所有 UI 界面零改动**。
