# CarroMed 项目当前开发进度与状态全景报告

> **记录时间**：2026-09-27 15:40 (GMT+8)  
> **文档版本**：v1.0.0  
> **记录目的**：系统化盘点当前项目的真实实现进度、已验证能力、已发现的体验/功能缺陷及原因定位，为后续迭代提供明确指引，防止记忆遗漏。

---

## 一、项目整体完成度全景一览

| 分层 / 模块 | 完成度 | 当前实际状态简述 |
| :--- | :---: | :--- |
| **1. 核心数据与数据库层 (Room)** | **100%** | 7 张实体表、不可变库存台账系统、6 个 DAO 接口全量实现，外键级联与类型转换器完整。 |
| **2. 领域计算与业务服务层** | **100%** | `SlotProjectionEngine` 排班投影、`DoseTrackingService` 核心追踪（打卡/撤销冲正/补录/改计划重排班）、`StatsEngine` 统计引擎全部实现。 |
| **3. 单元测试体系** | **100%** | 19 项基于真实内存 SQLite 数据库的业务与数学守恒单元测试，100% 持续通过。 |
| **4. 构建与 Release 签名打包** | **100%** | 已关联正式 keystore，开启 V1+V2 签名，生成已签名的正式版 APK（11.2MB），真机模拟器运行通过。 |
| **5. 主导航与 4 大主 Tab 界面** | **85%** | 今日清单、我的药箱、进展追踪、统计报表 4 大页面流程与交互打通。**存在标题间距不一致缺陷**。 |
| **6. 药品录入与编辑流程 (AddEdit)** | **70%** | 基础录入、时间时点增删、多时段剂量、初始库存入库已跑通。**存在频次子配置项未展开缺陷**。 |
| **7. 专属详情与全屏二级页** | **85%** | 药品详情（说明书/注意事项/出入库流水账）、手动补录页、补药入库页、系统特权自检页已就绪。 |
| **8. 数据本地导出与备份** | **40%** | UI 入口已绘制（CSV 导出、JSON 全量备份），但尚未接入 Android 存储访问框架（SAF）写出实际文件。 |
| **9. 系统闹钟唤醒与通知保活层** | **0%** | 原定规划排在最末阶段，`AlarmManager` 精确闹钟注册、广播接收器唤醒、通知栏常驻/横幅提醒、灭屏全屏 Sheet 尚未编写代码。 |

---

## 二、已 100% 落地并充分验证的模块

### 1. 核心数据层 (Core Data Layer)
- **7 张核心表**：
  1. `medications`（药品主表，支持剂型、颜色、警戒线、停药归档状态）；
  2. `schedule_policies`（用药方案策略，包含策略类型、间隔天数、每周哪几天、周期用药等）；
  3. `policy_times`（策略对应的具体时间点与单次剂量）；
  4. `dose_slots`（排班时间槽，由投影引擎生成的具体待服/已服时间点）；
  5. `dose_records`（服药事实记录表，记录实际服药时刻、操作事实）；
  6. `inventory_transactions`（不可变库存流水台账，记录每一次服药消耗、撤销冲正、采购入库、盘点校准，确保数学守恒）；
  7. `app_settings`（系统级键值对配置表）。
- **不可变库存台账原则**：
  - 严格禁止在业务中随意 `UPDATE currentStock = X`，必须写入流水，通过 `balanceAfter = balanceBefore + changeAmount` 保持双向可追溯。

### 2. 领域服务与算法引擎 (Domain Engines & Services)
- **`SlotProjectionEngine`**：
  - 纯函数式前向排班投影引擎，支持 `DAILY`（每天）、`INTERVAL`（隔天/每隔 N 天，自动规避跨月与闰年2月29日断裂）、`DAYS_OF_WEEK`（每周特定几天）、`CYCLE`（周期用药）及 `PRN`（按需）。
- **`DoseTrackingService`**：
  - `takeDose(slotId)`：打卡核销待服槽位，扣减库存，写入事实记录与库存流水；
  - `revertDose(recordId)`：误触打卡撤销冲正，恢复槽位待服状态，反向回补库存流水；
  - `skipDose(slotId)`：标记跳过用药，不扣减库存；
  - `recordManualDose(...)`：漏打卡或按需临时用药补录；
  - `refillStock(...)`：补药入库，生成批次流水；
  - `reconcileSchedule(medId)`：修改方案后平滑对齐排班——**历史事实记录绝对不可变，仅重排未来待执行槽位**。
- **`StatsEngine`**：
  - 依从率计算公式、库存可用剩余天数（Runway Days）测算、低库存预警算法。

### 3. 100% 真实单元测试
- 位于 `app/src/test/kotlin/com/mcxiaoke/carromed/core/`：
  - `DatabaseSanityTest`：验证外键级联删除、不可变库存台账数学守恒（`SUM(change_amount) == currentStock`）；
  - `SlotProjectionEngineTest`：验证每日、隔日、每隔 3 天、每周一三五等复杂边界条件与跨月投影；
  - `DoseTrackingServiceTest`：验证服药打卡扣减、误触撤销精准冲正、跳过、改计划历史保护与排班重投影。
- 全部测试持续 100% PASSED（19/19）。

### 4. Release 签名配置与构建
- 配置正式 keystore（`androidnew.jks` / BigCato）；
- 密钥与属性隔离于 `key.properties`（已被 `.gitignore` 忽略保护）；
- 成功打出 11.2MB 正式版 APK，通过 `apksigner` 权威验证，在真机模拟器冷启动与运行正常。

---

## 三、当前已排查出的明确问题与缺陷定位

### 缺陷 1：几个界面的 ActionBar Title 明显高度间距不一致（今日清单比我的药箱靠上）
- **现象描述**：
  - 在主界面下方 4 个 Tab 间切换时，“今日清单”标题垂直位置明显偏高，而“我的药箱”、“进展追踪”、“统计报表”的标题明显偏低约 8~10dp；进入二级页（如药品详情）又是标准的居中高度。
- **根本原因定位**：
  1. **未统一使用组件**：4 大主 Tab 未统一使用 Material 3 的 `TopAppBar`，而是在各个页面的 `LazyColumn` 第 0 个 item 中各自拼装了 `Row`。
  2. **内边距设置差异**：
     - `TodayScreen` 外层挂了 `Scaffold(contentWindowInsets = WindowInsets.statusBars)`，`LazyColumn` 的 `top` 内边距设为 `12.dp`；
     - `CabinetScreen`、`ProgressScreen`、`StatsScreen` 则是对 `LazyColumn` 直接挂了 `.statusBarsPadding()`，且 `top` 内边距设为 `16.dp`；
  3. **文本排版高度不同**：
     - `TodayScreen` 的 Header 是两行文本构成的 `Column`（包含大标题 + 日期副标题，总高约 60dp），在与右侧 44dp 按钮垂直居中（`CenterVertically`）对齐时，首行大标题被动上提；
     - 其余 3 个 Tab 只有单行大标题，文本中心与 44dp 按钮中心对齐，视觉位置偏下。
- **后续优化方案**：
  - 规范化 4 个主 Tab 的顶部 Header，统一顶边距规格与基准线对齐逻辑（或抽象统一的 `HomeTabHeader` 组件），确保 Tab 切换时标题稳如磐石。

---

### 缺陷 2：添加/修改药品页的“提醒频次与时间方案”未完成子选项展开
- **现象描述**：
  - 在“设置用药与提醒”页面（`AddEditMedicationScreen`）中，点击频次分段按钮的“隔天/隔N天”或“每周特定天”时，按钮虽然高亮切换，但下方界面没有任何反应，未出现对应的“隔几天”或“每周哪几天”的设置项。
- **根本原因定位**：
  1. **UI 逻辑缺失**：底层数据库和排班引擎完全支持多频次，但 Compose UI 代码在渲染完 `SingleChoiceSegmentedButtonRow` 后，直接写死了“设定每日提醒时点”，完全遗漏了 `when (uiState.policyType)` 的动态条件分支；
  2. **缺失组件**：
     - 缺失选中 `INTERVAL` 时的“每隔 [ N ] 天一次” 步进/数字选择器；
     - 缺失选中 `DAYS_OF_WEEK` 时的“周一至周日 7 天多选胶囊 Chip”；
  3. **ViewModel 缺少交互方法**：ViewModel 中尚未暴露 `onIntervalDaysChange` 和 `onToggleDayOfWeek` 供界面调用。
- **后续优化方案**：
  - 在 `AddEditMedicationScreen` 中补充 `AnimatedVisibility` 或 `when` 条件分支，动态呈现对应频次类型的配置控件；在 ViewModel 中接入双向绑定与数据校验。

---

### 待补全细节 3：药品详情页与药箱操作缺少二次确认
- **现象描述**：
  - 药品详情页底部的“停药归档”与“删除药品”按钮点击后直接执行，缺少 Material 3 标准的危险操作二次确认对话框（`AlertDialog`）。
- **后续优化方案**：
  - 增加“确认归档该药品？”与“确认删除药品及排班？”的确认弹窗。

---

### 待补全细节 4：数据导出与备份尚未落地文件写出
- **现象描述**：
  - 进展页和统计页右上角有“导出”图标、统计页有“导出完整报告为 CSV”按钮、设置页有“导出服药明细报表 (CSV)”与“备份全量数据库 (JSON)”按钮，目前点击仅为空事件或占位 Toast。
- **后续优化方案**：
  - 接入标准协程后台导出逻辑，生成 CSV / JSON 文本并通过 Android 系统分享（`Intent.ACTION_SEND`）或保存到本地 Downloads 目录。

---

### 完全未启动的模块 5：系统级定时提醒与通知保活（原定最后一期）
- **涵盖内容**：
  1. `AlarmManager` 精确时钟定时器注册与重排；
  2. `BroadcastReceiver` 接收定时唤醒并发送通知；
  3. 系统通知渠道（常驻提醒通知、Heads-up 浮动横幅通知）；
  4. 锁屏灭屏唤醒全屏快捷操作页（`DoseActionBottomSheet` / Activity）；
  5. 开机自启监听（`BOOT_COMPLETED`）自动恢复闹钟。

---

## 四、后续推进分步实施路线图

1. **第一阶段（界面与表单缺陷修复）**：
   - 彻底拉平 4 个主 Tab 与二级页面的 Title 间距与顶部基准线；
   - 补齐 `AddEditMedicationScreen` 的“隔天/隔 N 天”步进选择器与“每周特定天”多选 Chip 控件及数据绑定；
   - 补齐药品详情页的停药/删除确认弹窗。
2. **第二阶段（数据导出与备份完善）**：
   - 实现服药历史与库存流水的 CSV 格式序列化与导出分享；
   - 实现全量数据库的 JSON 备份导出与导入校验。
3. **第三阶段（系统闹钟与通知调度落地）**：
   - 落地 `AlarmScheduler`、`AlarmReceiver`、通知栏构建与快捷操作响应。
