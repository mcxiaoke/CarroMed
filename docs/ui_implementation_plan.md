# UI 表现层设计与落地实施方案 (Jetpack Compose + Material 3)

根据用户需求、产品架构文档及 `temp/demo/screenshots` 中的 13 张完整原型截图，本方案旨在构建**高保真、纯原生控件、遵循 Material 3 设计规范、无写死硬编码颜色、清晰易维护**的 Android Compose 表现层。

---

## 一、核心原则与依赖规划

### 1. 原生控件与 Material 3 规范
- **禁止硬编码色彩**：所有界面元素一律使用 `MaterialTheme.colorScheme.*`（如 `primary`, `onPrimary`, `surfaceVariant`, `outline`, `errorContainer` 等）及语义透明度（`.copy(alpha = ...)`），确保深色模式（Dark Mode）与动态取色（Material You / Monet）自适应无缝切换。
- **纯原生 Material 3 组件**：
  - 顶部栏：`TopAppBar`, `CenterAlignedTopAppBar`
  - 底部导航：`NavigationBar`, `NavigationBarItem`
  - 卡片与容器：`ElevatedCard`, `OutlinedCard`, `Surface`
  - 选项与分段：`PrimaryTabRow`, `SingleChoiceSegmentedButtonRow`, `FilterChip`, `AssistChip`
  - 交互浮窗与弹窗：`ModalBottomSheet`, `DatePickerDialog`, `TimePicker`
  - 列表与滑动：`LazyColumn`, `LazyRow`
- **Icon 库**：引入 Compose BOM 托管的 `androidx.compose.material:material-icons-extended`，提供丰富且标准的原生矢量图标（如 `MedicalServices`, `Pill`, `Schedule`, `Undo`, `CheckCircle`, `Tune`, `Add`, `Inventory`, `ChevronRight` 等）。

### 2. 依赖项规划（待方案批准后写入 `app/build.gradle.kts`）
```kotlin
// 导航与生命周期（与现有 Kotlin 2.0.21 和 Compose BOM 2024.10.01 完全对齐）
implementation("androidx.navigation:navigation-compose:2.8.3")
implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
// 官方 Material 3 扩展图标库（由 Compose BOM 统一定义版本）
implementation("androidx.compose.material:material-icons-extended")
```

---

## 二、页面与路由架构规划

采用单 Activity (`MainActivity`) + `NavHost` 架构。主界面保留底部导航栏，所有二级页面均为独立全屏页面（二级页面自动隐藏底部导航栏，提供标准返回操作）。

```
AppNavHost
├── 【主导航栏页面 (Top-Level Destinations)】
│   ├── Route.Today ("today")                // 今日清单：日期选择条、待服药列表、已服撤销区、低库存横幅、FAB
│   ├── Route.Cabinet ("cabinet")            // 我的药箱：在服/归档切换、极简药品列表卡片、添加药品入口
│   ├── Route.Progress ("progress")          // 进展追踪：7天打卡矩阵、时间轴服药流水
│   └── Route.Stats ("stats")                // 统计报表：月度/年度汇总、服药依从率、药品消耗榜单、CSV导出
│
└── 【二级全屏页面 (Full-Screen Sub-Screens)】
    ├── Route.MedicationDetail ("med_detail/{medId}")  // 药品详情：说明书、提醒计划、注意事项、闭环出入库流水
    ├── Route.AddEditMedication ("med_edit?medId={id}")// 新建/修改药品与提醒：支持每日/隔日/周选/周期等多时段
    ├── Route.ManualDose ("manual_dose?medId={id}")    // 手动补录服药：历史补录、按需临时服药、自动平抑库存
    ├── Route.Refill ("refill/{medId}")                // 补药入库：快速盒数/自定义片数、批次、入库台账生成
    ├── Route.Settings ("settings")                    // 系统设置：推迟时长、音效、全屏提醒、夜间免打扰、备份与恢复
    └── Route.PermissionCheck ("permission_check")     // 保活自检：精确闹钟、电池优化、通知权限指引

└── 【浮层组件 (Modal Overlay)】
    └── DoseActionBottomSheet (快捷操作弹窗)            // 模拟或通知栏拉起的快速打卡/推迟/跳过 Sheet
```

---

## 三、各页面详细设计与组件对照（对照截图）

### 1. 今日清单页 (`TodayScreen`) — 对照截图 1
- **顶部日期滚动条**：
  - 使用 `LazyRow` 承载当天前后 7 天卡片。
  - 选中项高亮背景 (`primaryContainer`)，圆点标记打卡完成度（全完成为绿色指示点）。
- **低库存预警横幅**：
  - 当存在药品库存低于警戒线时显示，采用 `tertiaryContainer` 或 `errorContainer` 配色。
  - 点击「去补药 >」直接导航到 `Route.Refill(medId)`。
- **待服药区域 (`待服药 (N)`)**：
  - `ElevatedCard`：左侧附药品主题色装饰条、药丸 Icon、药品名称、类别 Chip、计划时间、单次剂量、剩余库存（告急时标注红色告警色）。
  - 右侧圆环 Checkbox / CheckButton：点击触发 `takeDose`，附带微交互动画。
- **已服药打卡区域 (`今日已服任务 (N)`)**：
  - `OutlinedCard`：半透明/沉静浅色背景，呈现打卡完成时间（如 `09:00 完成 · 库存已平账`）。
  - 右侧「↩ 撤销打卡」按钮：点击触发 `DoseTrackingService.undoDose`，支持随时反悔，即刻冲正库存并恢复待服槽位。
- **悬浮按钮 (Extended FAB)**：
  - `ExtendedFloatingActionButton`：`+ 手动补录`，位于右下角，点击跳转至 `Route.ManualDose`。
- **顶部右上角齿轮图标**：点击直达 `Route.Settings`。

### 2. 我的药箱页 (`CabinetScreen`) — 对照截图 2
- **分段控制器**：`正在服用 (4)` 与 `已停药归档 (1)`。
- **操作指引**：轻量 `surfaceVariant` 提示条（“点击任意药品卡片，进入专属详情页”）。
- **药品卡片列表**：
  - 极简化设计：左侧色圆标、药品名称、类别徽标（如 `处方药 · 免疫`、`慢病处方`）、每日频次时间（如 `每天 2 次 · 10:30, 22:00`）。
  - 右侧库存徽章：正常库存为 `surfaceVariant`，低库存呈现醒目告警胶囊（`⚠️ 剩 6 片`），右侧 Chevron 箭头指示可点击。
- **右上角添加按钮**：点击跳转至 `Route.AddEditMedication`（创建模式）。

### 3. 药品专属详情页 (`MedicationDetailScreen`) — 对照截图 7
- **顶部基础信息卡**：大标题、剂型、单位、类别徽标、彩色药丸、当前状态（“● 状态: 正在提醒中”）。
- **提醒计划卡片**：
  - 显示频次与具体时点（如 `🔔 上午 10:30 单次 1 片`、`🔔 睡前 22:00 单次 1 片`）。
  - 按钮组：`✏️ 修改计划`（跳转编辑页）与 `⏸️ 暂停提醒 / ▶️ 恢复提醒`。
- **详细说明与医嘱描述卡片**：医嘱文本与注意事项。
- **禁忌与注意事项高亮卡**：警示色标签组（`AssistChip`，如“整粒吞服禁嚼碎”、“严禁与葡萄柚同食”）。
- **闭环库存与出入库流水卡**：
  - 当前结余库存量大号数字显示、警戒线、预计可用天数预警。
  - 右上角 `+ 补药入库` 按钮（跳转 `Route.Refill`）。
  - 下方倒序展示最近双向流水账（服药扣减 `-1 片`、采购补药 `+30 片`、撤销冲正 `+1 片`）。

### 4. 设置用药与提醒页 (`AddEditMedicationScreen`) — 对照截图 8
- **低门槛提示条**：“只需填写【药品名称】和选择【提醒频次/时间】，其余均为可选扩展项”。
- **基本信息（必填）**：药品名称（`OutlinedTextField`）、类别下拉选择、剂型下拉选择。
- **频次与时间方案（必填）**：
  - 频次类型切换：`每天固定` | `隔天/隔N天` | `每周特定天`。
  - 动态时点列表：每个时点一行，包含时间选择器触发按钮（弹出 Material 3 `TimePicker`）与单次剂量输入框。
  - `+ 添加一个提醒时点` 动态增行按钮。
- **库存追踪（可选）**：当前库存量与预警阈值。
- **说明与医嘱（可选）**：注意事项长文本输入。
- **保存逻辑**：自动事务写入数据库并调用 `reconcileSchedule` 重新平滑排班。

### 5. 手动补录服药页 (`ManualDoseScreen`) — 对照截图 9
- 专门应对“漏带手机、事后补记、按需服药 (PRN)”场景。
- 药品下拉选择器（显示剩余库存）。
- 实际服药时间选择（默认当前，支持选过去任意日期时间）。
- 剂量与单位。
- 备注说明。
- 开关：`自动扣减对应库存台账`（默认开启，保持真实平账）。
- 底部大按钮：`保存并平账记录`。

### 6. 补药入库页 (`RefillScreen`) — 对照截图 13
- 药品当前库存与警戒线提示。
- 快捷补给 Chip 组：`+ 1 盒 (30片)`、`+ 2 盒 (60片)`、`+ 100 片`。
- 自定义数量输入框。
- 可选采购批次与渠道信息（如“同仁堂实体药房”、生产批号、有效期）。
- 底部大按钮：`确认入库上架 (生成流水账)`。

### 7. 进展追踪页 (`ProgressScreen`) — 对照截图 3、12
- Tab 切换：`7 天打卡矩阵` | `时间轴服药流水`。
- 7天打卡矩阵：每个在服药品一张卡片，显示过去 7 天每天的完成打卡绿圈及 7 天完成率百分比。
- 时间轴服药流水：按日期分组倒序展示今日及以往的服药流水（已服/跳过/待服）。

### 8. 统计报表页 (`StatsScreen`) — 对照截图 4、5
- 时间跨度切换：`过去 1 个月` | `过去 1 整年 (年度汇总)`。
- Hero 聚合卡片：累计总服药片数、服药依从率百分比、在服药物品种数。
- 药品消耗排行榜：各药品实际服药片数排行榜。
- 数据导出按钮：调用系统 Share Intent 导出 CSV 明细。

### 9. 系统设置页 (`SettingsScreen`) — 对照截图 6
- 提醒与通知选项：推迟时长选择（15/30/60分钟）、铃声音效选择、灭屏全屏弹窗开关、夜间免打扰开关。
- 保活自检入口：跳转 `Route.PermissionCheck`。
- 本地数据备份与恢复：导出 CSV 明细、全量 JSON 备份与导入。
- 关于 CarroMed：纯本地离线单机运行说明。

### 10. 通知快捷操作弹窗 (`DoseActionBottomSheet`) — 对照截图 10、11
- `ModalBottomSheet` 原生组件。
- 呈现当前药品的详细信息与注意事项高亮。
- 快捷按钮矩阵：
  - `✓ 确认吃药 (自动扣减 1 片库存)`（主操作蓝底大按钮）
  - `⏰ 推迟 30 分钟` / `⏰ 推迟 1 小时`
  - `⏭️ 跳过本次`
  - `关闭窗口`

---

## 四、数据流与首次启动预置 (Seed Data)

为了确保用户进入 App 后即可完整体验与原型截图 1:1 的真实效果，在首次启动时将自动检测并预填充真实演示数据：
- 4 种常用典型药品：**环抱素**（免疫处方药，低库存警戒）、**羟氯喹**（慢病处方）、**醋酸泼尼松**（隔日服用）、**钙和维生素D**（营养保健每日1次）。
- 今日槽位：包含已服药的羟氯喹与醋酸泼尼松（可即时测试撤销），以及待服药的环抱素与钙片（可即时测试打卡）。
- 历史台账流水：真实出入库记录，验证库存平账与统计报表。

---

## 五、分步落地计划

1. **Step 1: 依赖配置与主题系统**
   - 在 `app/build.gradle.kts` 中添加 `navigation-compose`、`material-icons-extended` 和 `lifecycle-compose`。
   - 创建 `com.mcxiaoke.carromed.ui.theme` 包（Color, Theme, Typography, Shape），实现全套 Material 3 动态色彩与深色模式自适应。
2. **Step 2: 基础脚手架、路由与数据预置**
   - 创建 `AppNavigation` 路由体系，配置底部导航栏联动与全屏子页面隐藏。
   - 实现 `SampleDataSeeder`，在首次冷启动时初始化真实演示数据。
3. **Step 3: 核心主页面实现 (Today & Cabinet)**
   - 实现 `TodayScreen` 及 `TodayViewModel`（日期滚轮、待服打卡、已服撤销、低库存提醒、FAB）。
   - 实现 `CabinetScreen` 及 `CabinetViewModel`（药品分类、极简列表、状态标识）。
4. **Step 4: 二级全屏子页面实现**
   - 实现 `MedicationDetailScreen`（详情、计划、医嘱、闭环出入库明细）。
   - 实现 `AddEditMedicationScreen`（动态时段配置、表单验证、改计划平滑对齐）。
   - 实现 `ManualDoseScreen` 与 `RefillScreen`（补录、补药入库）。
5. **Step 5: 进展、统计与设置页面**
   - 实现 `ProgressScreen`（7 天打卡矩阵与时间轴）。
   - 实现 `StatsScreen`（年度/月度统计与消耗排行）。
   - 实现 `SettingsScreen` 与 `DoseActionBottomSheet`。
6. **Step 6: 构建与交互验证**
   - 运行 `./gradlew assembleDebug`，编译生成可用 APK。
   - 全面验证页面跳转、打卡、撤销、出入库联动及全屏体验。

---

## 六、待用户确认事项

> [!IMPORTANT]
> 当前阶段严格遵守“先不动代码”指令。本方案已全面覆盖所有 13 张原型截图与业务功能点。
> 请审阅本方案，确认后即可开始分步落地实施。
