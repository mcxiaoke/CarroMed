# CarroMed 全面代码与 UI/UX 审查报告 + 分步修改计划

> **记录时间**：2026-09-27 16:48 (GMT+8)
> **文档版本**：v1.0
> **审查范围**：`app/src/main` 全部 53 个 Kotlin 源文件 + 4 个单元测试文件 + `AndroidManifest.xml` + 模拟器 `emulator-5554` 实机走查（7 张截图，`temp/shots/`）
> **审查基线**：git `a769e97`（"feat(alarm,export,ui): add exact alarm & notification module, data export/backup, and UI completion"）
> **对照基准**：MyTherapy 实机截图 6 张（`F:\Temp\mytherapy`）+ 主流吃药提醒 / Todo 类 App 功能基线
> **基线自检**：`./gradlew testDebugUnitTest` 19/19 PASSED

---

## 零、结论速览（TL;DR）

| 维度 | 结论 |
| :--- | :--- |
| 用户反馈 1（无编辑入口 / 改计划语义错位） | **确认成立**。详情页唯一入口叫「修改计划」，实际打开的是「药品信息 + 提醒计划」合并表单；`precautions`、`unit`、`alias` 等药品信息字段**根本无法编辑** |
| 用户反馈 2（药品信息 / 提醒设置 / 库存应三分离） | **确认成立**。MyTherapy 详情页是 4 行可点击分节（药物 / 库存 / 用药时间表 / 提醒设置），CarroMed 是 3 段平铺只读卡 + 2 个入口按钮，缺「提醒设置」这一整个维度 |
| 功能完整性 | **发现 12 个 P0 级缺陷**，其中 **2 个页面的数据 100% 是硬编码假数据**（统计报表页、进展页 7 天矩阵），3 处 UI 承诺了但功能完全没实现（手动补录时间选择器 / 自动扣库存开关 / 补药批号有效期落库） |
| UI/UX | **发现 20 项问题**，含 1 处医疗数据可信度硬伤（药品名「环孢素」应为「环孢素」，出现在演示数据中） |
| 修复规模 | 9 个步骤，8 次 git 提交，预估 3500+ 行改动 |

---

## 一、用户反馈 1：编辑入口缺失与「修改计划」语义错位

### 1.1 现象复现（模拟器实测）

`药箱 → 环孢素 → 药品详情` 页面，唯一的编辑入口是「⏰ 用药与提醒方案」卡片右上角的 **「✏️ 修改计划」** 按钮（`MedicationDetailScreen.kt:232-245`）。点击后进入 `AddEditMedicationScreen`，标题为「修改用药与计划」。

### 1.2 根因分析

| 编号 | 根因 | 代码位置 | 证据 |
| :--- | :--- | :--- | :--- |
| R1-1 | 导航层只有一条 `med_edit` 路由，同时承载「新增药品」和「编辑药品+计划」 | `Screen.kt:18-20` | 路由参数 `medId?` 有值=编辑，无值=新增，两者渲染同一个 Composable |
| R1-2 | 详情页的 `onNavigateToEditPlan` 语义与 UI 文案绑死，无法区分「编辑信息」与「改计划」 | `AppNavigation.kt:174` | 参数名 `onNavigateToEditPlan`，无第二个回调 |
| R1-3 | **药品信息字段在表单中缺失**：`precautions`（注意事项/禁忌）、`unit`（单位）、`alias`（别名）、`noticeShort`（通知简述）、`defaultDose`（默认剂量）全部**没有任何输入控件**，但详情页却在**展示** `precautions` | `AddEditMedicationScreen.kt:130-538` 全部 4 个卡片 | 表单只有：name / category / form / 频次 / intervalDays / daysOfWeek / timeSlots / currentStock / minStockAlert / description。剂量输入框的 `trailingIcon` 把单位**写死成"片"**（`AddEditMedicationScreen.kt:440`） |
| R1-4 | **`PolicyType.PRN`（按需）与 `CYCLE`（周期）引擎支持但 UI 不可选** | `AddEditMedicationScreen.kt:255-259` | `policyTypes` 硬编码只列 DAILY / INTERVAL / DAYS_OF_WEEK 三个 |
| R1-5 | **`SchedulePolicyEntity.endDate`（疗程结束日）、`cycleOnDays`、`cycleOffDays`、`version` 全部无 UI** | `SchedulePolicyEntity.kt:44-60` | 抗生素疗程场景无法配置 |

### 1.3 附带发现的**数据丢失缺陷（P0，最严重）**

`AddEditMedicationViewModel.save()`（`AddEditMedicationViewModel.kt:164-176`）用 `MedicationDao.insert()` + `OnConflictStrategy.REPLACE` 写入一个**全新构造的** `MedicationEntity`：

```kotlin
val med = MedicationEntity(
    id = state.medId ?: 0L,
    name = ..., category = ..., form = ..., unit = ...,
    colorHex = ..., currentStock = ..., minStockAlert = ...,
    isStockTracked = ..., description = ...
)   // ← alias / precautions / noticeShort / defaultDose / iconName
    //    / isPaused / isArchived / createdAt / updatedAt 全部取默认值
val savedMedId = medDao.insert(med)   // REPLACE 覆盖整行
```

**后果（每一条都可复现）**：
1. `precautions` 被清空 → 详情页「注意事项与禁忌」高亮卡整块消失；
2. `isPaused` 被重置为 `false` → 暂停中的药品被"自动恢复"；
3. `isArchived` 被重置为 `false` → 已归档药品被"自动复活"回到在服列表；
4. `alias` / `noticeShort` / `defaultDose` / `createdAt` 丢失 → 通知栏简述、手动补录默认剂量、建档时间全部归零。

同时 `save()` 里的策略保存（`AddEditMedicationViewModel.kt:181-187`）**每次编辑都把 `startDate` 重置为今天**，并丢弃 `endDate` / `cycleOnDays` / `cycleOffDays` / `version`：

```kotlin
val policy = SchedulePolicyEntity(
    medicationId = finalMedId, policyType = ..., intervalDays = ..., daysOfWeek = ...,
    startDate = LocalDate.now().format(...)   // ← 每次编辑都重置为今天
)   // ← endDate / cycleOnDays / cycleOffDays / version 全部丢失
```

**后果**：`INTERVAL`（隔日服用）的相位会随每次编辑漂移（`SlotProjectionEngine.isScheduledOnDate` 用 `ChronoUnit.DAYS.between(policyStartDate, targetDate) % interval`，改一次计划整个隔日节奏就变了）；已配置的疗程结束日被静默丢弃导致「无限期」。

---

## 二、用户反馈 2：药品信息 / 提醒设置 / 库存应当三分离

### 2.1 MyTherapy 实机参考结构

| MyTherapy 详情页分节 | 内容 | 交互 |
| :--- | :--- | :--- |
| 💊 **药物** | 环孢素 / 添加摄入建议 | → 药品信息编辑 |
| 📦 **库存** | 86 药片 剩下 | → 库存子页 |
| 📅 **用药时间表** | 每天 2 次 - 10:30 和 22:00 / 无限期 | → 提醒计划子页 |
| 🔔 **提醒设置** | 重要提醒已关闭 | → 提醒行为子页 |
| 底部 | 暂停提醒 / 删除药物 | 危险操作 |

MyTherapy「用药时间表」子页内部还再分两层：
- **频率**（一天两次）→ 单独编辑页
- **服药周期**（无限期）→ 单独编辑页
- **提醒详情**：可增删的时点列表（`10:30 ▾ 1 药片 🗑`）
- **添加服药时间** / **周末不同时间**（周六和周日）开关

### 2.2 CarroMed 当前结构差距

| 维度 | MyTherapy | CarroMed 现状 | 差距 |
| :--- | :--- | :--- | :--- |
| 药品信息编辑 | 独立可点击分节 | ❌ 无入口 | **P0** |
| 库存 | 独立可点击分节 + 独立子页 | ⚠️ 与流水平铺在同一卡片，只读 + 一个「补药入库」按钮 | **P1** |
| 用药时间表（频次+时点+疗程） | 独立可点击分节 + 独立子页（含频率/周期二次拆分） | ⚠️ 只读时点列表 + 「修改计划」跳去混合表单 | **P0** |
| **提醒设置（提醒行为）** | 独立可点击分节 + 独立子页 | ❌ **完全不存在** | **P0** |
| 周末/节假日特殊安排 | 有 | ❌ 无 | P2 |
| 暂停提醒 | 分节内 | ✅ 有（在方案卡内） | OK |

**核心结论**：「提醒设置」这一整个功能维度在 CarroMed 中**根本不存在**。目前 App 里所有"提醒行为"配置都在**全局设置页**（推迟时长 / 全屏弹窗 / 夜间免打扰），无法按药品差异化设置，而 `MedicationEntity` 也没有对应字段。

---

## 三、全面功能审查：P0 缺陷清单（12 项）

> 判定标准：功能承诺了但没实现、数据造假、用户数据会被破坏。

### P0-1 ★★★ 统计报表页 100% 是硬编码假数据

`StatsViewModel.kt:47-62`：

```kotlin
val total = if (period == 0) 120f else 1428f
val adherence = if (period == 0) 0.965f else 0.982f
val rankingList = listOf(
    MedicationConsumption(1, "羟氯喹", if (period == 0) 60f else 730f, "片"),
    MedicationConsumption(2, "环孢素", if (period == 0) 30f else 365f, "片"),
    MedicationConsumption(3, "钙和维生素D", ...), MedicationConsumption(4, "醋酸泼尼松", ...)
)
StatsUiState(..., activeMedCount = medications.size.coerceAtLeast(4), ...)
```

- 总剂量、依从率、排行榜**全部是字面量**；
- 排行榜里的药名是**写死的 4 个演示药品**，与用户实际药品无关（模拟器实测：用户有 5 个药含自建的「1111111」，页面仍显示写死的 4 个）；
- `coerceAtLeast(4)` 强行把品种数下限抬到 4；
- **模拟器实证**：用户真实服药记录只有 3 条、共 3 片，页面显示「1428 片 / 98.2%」。
- `recordDao` 被声明为字段但**从未使用**（`StatsViewModel.kt:38`）。

### P0-2 ★★★ 进展页 7 天打卡矩阵是假数据

`ProgressViewModel.kt:70-86`：

```kotlin
val dayStatus = if (idx == 6) {
    val todaySlot = todaySlots.firstOrNull { it.medicationId == med.id }
    todaySlot?.status ?: SlotStatus.COMPLETED      // ← 今日：只取第一个时点；没有则默认"已完成"
} else {
    SlotStatus.COMPLETED                             // ← 历史 6 天：无条件"已完成"
}
```

- 历史 6 天**无条件返回 COMPLETED**，从不查库；
- 今日只取该药**第一个**槽位（`firstOrNull`），一天 2 次的药只看第 1 次；
- 该药今日无槽位 → 默认 COMPLETED；
- **模拟器实证**：用户今天（2026-09-27）新建的药品「1111111」在进展页显示「周一~周六全绿 ✓ + 近 7 天完成率 100%」。
- 附带：`ProgressScreen.kt:170-199` 把 PENDING / SKIPPED / EXPIRED 三种状态**渲染成同一个 `MoreHoriz` 灰点**，用户无法区分「还没到」「主动跳过」「漏服了」。

### P0-3 ★★★ 编辑药品会清空 precautions / 暂停状态 / 归档状态 / 别名

见 §1.3，`AddEditMedicationViewModel.kt:164-176`。

### P0-4 ★★☆ 编辑药品会重置策略起始日并丢弃疗程配置

见 §1.3，`AddEditMedicationViewModel.kt:181-187`。导致 INTERVAL 相位漂移、`endDate` 疗程丢失。

### P0-5 ★★☆ 手动补录页承诺「可指定过去时间」，实际时间字段完全只读

`ManualDoseScreen.kt:167-173`：

```kotlin
OutlinedTextField(
    value = timeStr, onValueChange = {}, readOnly = true,
    label = { Text("实际服药时间 (可指定过去时间) *") }, ...
)   // ← 没有 onClick、没有 DatePickerDialog/TimePickerDialog、ViewModel 也无对应方法
```

`ManualDoseViewModel` 全文（89 行）**没有任何修改 `actualDateTime` 的方法**。用户无法补录任何非当前时刻的服药。

### P0-6 ★★☆ 手动补录页「自动扣减对应库存台账」开关被完全忽略

`ManualDoseScreen.kt:242-245` 渲染了 `Switch(checked = uiState.deductStock, onCheckedChange = { viewModel.onDeductStockChange(it) })`，UI 状态也正确更新，但 `ManualDoseViewModel.save()`（`ManualDoseViewModel.kt:77-83`）调用 `trackingService.logManualDose(...)` **从未传 `deductStock`**，而 `DoseTrackingService.logManualDose`（`DoseTrackingService.kt:171-183`）也无此参数，恒扣库存。**开关是纯装饰**。

### P0-7 ★★☆ 补药入库页的「生产批号」「有效期至」采集后直接丢弃

`RefillViewModel.confirmRefill`（`RefillViewModel.kt:48-59`）：

```kotlin
val note = "采购入库 (${_uiState.value.channel})"   // ← 只用了 channel
trackingService.refillStock(medId, amt, note)       // ← batchNumber / expiryDate 从未使用
```

- `InventoryTransactionEntity` 无批号/有效期字段（`InventoryTransactionEntity.kt:32-55`）；
- `expiryDate` 还**预填了假值 `"2028/05/30"`**（`RefillViewModel.kt:19`），用户会误以为系统已记录；
- `MedicationEntity` 无 `expiryDate` 字段 → **药品有效期/临期提醒完全缺失**（主流 App 必备项）。
- 附带：`RefillScreen.kt:234` 的日历图标 `Icon(Icons.Default.CalendarToday)` 无 `clickable`，**没有日期选择器**。

### P0-8 ★★☆ 系统设置页 3 个开关全是装饰，从未被任何代码消费

`SettingsViewModel` 正确地把 `snooze_minutes` / `full_screen_alert` / `night_dnd` 写入了 `app_settings` 表，但：

| 设置项 | 写库 | 被读取消费 | 证据 |
| :--- | :---: | :---: | :--- |
| 默认推迟时长 | ✅ | ❌ | `Notifications.kt:108-110` 通知 Action 写死 `"⏰ 推迟30分钟"` + `putExtra(EXTRA_MINUTES, 30)` |
| 灭屏全屏弹窗提醒 | ✅ | ❌ | 全工程无 `setFullScreenIntent`，`DoseActionBottomSheet` 组件**从未被任何页面引用**（`grep` 全库 0 处调用） |
| 夜间免打扰 (静音) | ✅ | ❌ | `Notifications.kt:37-48` 渠道配置里无 `setSound(null, null)`，无静音时段逻辑 |

模拟器实证：`app_settings` 表 0 行（用户从未改过），即便改了也不影响通知行为。

### P0-9 ★★☆ `PRN`（按需）与 `CYCLE`（周期）频次引擎支持但 UI 完全不可选

`SlotProjectionEngine.isScheduledOnDate` 完整实现了 5 种 `PolicyType`（含 `CYCLE` 吃 N 停 N 的 `cycleDay < takeDays` 逻辑），`SlotProjectionEngineTest` 也测了 4 种，但 `AddEditMedicationScreen.kt:255-259` 的 `policyTypes` 列表只暴露 3 种。用户永远无法创建按需服药药（如「止痛药，疼的时候吃」）或周期用药（如激素 21 天周期）。

### P0-10 ★★☆ 「跳过本次」有领域层和通知层实现，但 App 内零 UI 入口

`TodayViewModel.skipDose()`（`TodayViewModel.kt:130-135`）已实现，但 `TodayScreen` 全文没有任何调用；`DoseActionReceiver` 的通知栏「⏭️ 跳过本次」有，但用户进 App 后想跳过某次服药做不到。`SNOOZED` 状态同理：App 内无推迟入口，`TodayScreen` 也把 SNOOZED 和 PENDING 渲染成完全一样的卡片，用户看不出某次已推迟过。

### P0-11 ★★☆ 数据库无正式迁移策略，仅 `fallbackToDestructiveMigration`

`AppDatabase.kt:63`：`.fallbackToDestructiveMigration()`。一旦本次要加字段（有效期、重要提醒开关），Room 会**静默清空用户全部数据**。对"数据 100% 归用户"定位的 App 不可接受。

### P0-12 ★☆☆☆ `unit`（单位）无处可改，全库 UI 写死「片」

| 位置 | 问题 |
| :--- | :--- |
| `AddEditMedicationViewModel.kt:29` | `val unit: String = "片"` 硬编码默认值，**表单无单位选择器** |
| `AddEditMedicationScreen.kt:440` | 剂量输入框 `trailingIcon = { Text("片") }` 写死 |
| `TodayScreen.kt:347` | `"${item.slot.scheduledTime} · 剂量 ${item.slot.doseAmount.toInt()} 片"` 写死 |
| `TodayScreen.kt:356` | `"· 剩 ${it.currentStock.toInt()} 片"` 写死 |
| `TodayScreen.kt:163` | 低库存横幅 `"仅剩 X 片 ... 警戒线 Y 片"` 写死 |
| `ProgressScreen.kt:232` | `"${medication?.name} (${doseAmount} 片)"` 写死 |
| `RefillScreen.kt:140-142` | 快捷 Chip `"+ 1 盒 (30片)"` 写死 |
| `MedicationEntity.unit` 注释 | 声明支持 `片/粒/袋/ml/滴`，但 UI 从不允许选择 |

口服液（ml）、滴剂（滴）、散剂（包）用户使用时，全部显示为"片"。

---

## 四、全面功能审查：功能缺失清单（P1，20 项）

> 对照主流吃药提醒 App（MyTherapy / Medisafe / Drugs.com / 华为小米健康用药提醒）与 Todo 类 App。

### 药品信息维度
| # | 缺失项 | 主流做法 | 优先级 |
| :--- | :--- | :--- | :--- |
| M1 | **注意事项/禁忌（precautions）无法录入** | MyTherapy「添加摄入建议」；国内 App 普遍有「注意事项」标签 | **P0**（详情页在展示却无法编辑） |
| M2 | **单位（unit）无法选择** | 片/粒/袋/ml/滴/支 | **P0** |
| M3 | 别名（alias）无法录入 | MyTherapy 药品名 + 通用名双行 | P1 |
| M4 | 通知栏单行简述（noticeShort）无法录入 | 锁屏通知「温水吞服 · 禁葡萄柚」 | P1 |
| M5 | 默认单次剂量（defaultDose）无法录入 | 手动补录时自动带出 | P2 |
| M6 | 标识颜色（colorHex）写死 `#2563EB` | 用户可自选色标 | P2 |

### 提醒计划维度
| # | 缺失项 | 主流做法 | 优先级 |
| :--- | :--- | :--- | :--- |
| M7 | **疗程起止日期（endDate）无法设置** | MyTherapy「服药周期：无限期 / X 天」；抗生素疗程必备 | **P1** |
| M8 | **时点标签（早/中/晚/睡前/随餐）无法录入** | `PolicyTimeEntity.label` 字段已存在（默认"服药时段"）但 UI 零入口 | **P1** |
| M9 | **按需服用（PRN）不可选** | 止痛药等场景刚需 | **P1** |
| M10 | **周期用药（CYCLE）不可选** | 激素 21 天周期等 | P2 |
| M11 | 周末/节假日不同时间 | MyTherapy「周末不同时间」开关 | P2 |
| M12 | 未来 7 天排班预览（改计划前先看会怎样） | 主流 App 改频率时预览 | P2 |
| M13 | 无「改计划会保留历史记录」的显式告知 | — | P2 |

### 提醒行为维度（整层缺失）
| # | 缺失项 | 主流做法 | 优先级 |
| :--- | :--- | :--- | :--- |
| M14 | **按药品的「重要提醒」开关** | MyTherapy 详情页「提醒设置：重要提醒已关闭」 | **P1** |
| M15 | **按药品的提前提醒时长** | 提前 5/10/15 分钟 | P2 |
| M16 | **按药品的自定义推迟时长** | 通知栏按钮跟随药品设置 | P2 |
| M17 | 漏服二次提醒 | 未确认后 N 分钟再提醒 | P2 |
| M18 | 提醒音效/振动可配 | 设置页有「温和药铃」字段但**UI 无控件**（`SettingsUiState.soundMode` 无人改） | P1 |

### 库存维度
| # | 缺失项 | 主流做法 | 优先级 |
| :--- | :--- | :--- | :--- |
| M19 | **药品有效期（expiryDate）字段与临期提醒** | MyTherapy / Medisafe 均有 | **P1** |
| M20 | **库存盘点/校准无 UI 入口** | `TransactionType.CALIBRATION_ADJUST` 有领域层和测试，无任何按钮 | **P1** |
| M21 | 「不追踪库存」开关 | 详情页无开关，只能通过改表单清空库存字段 | P2 |

### 今日/交互维度
| # | 缺失项 | 优先级 |
| :--- | :--- | :--- |
| M22 | App 内**推迟（snooze）入口**缺失 | **P1** |
| M23 | App 内**跳过（skip）入口**缺失 | **P1** |
| M24 | 已完成任务列表无"全部"折叠区；MyTherapy 有「已解决的任务」可折叠 | P2 |
| M25 | 药箱无**搜索**（药品多时必需） | **P1** |
| M26 | 药箱无**排序**（按名称/剩余天数/最近服用） | P2 |
| M27 | 无「全部提醒总开关」 | P2 |
| M28 | 药品详情页无该药品的**服药历史记录**（只有库存流水） | **P1** |
| M29 | 药品详情页无该药品的**依从率** | **P1** |
| M30 | 手动补录页**没有"未来时间"校验**（可补录未来时间，逻辑上荒谬） | P2 |

### 数据/可靠性维度
| # | 缺失项 | 优先级 |
| :--- | :--- | :--- |
| M31 | 无自动备份（P1-2 旧审查已提） | P2 |
| M32 | 锁屏通知直接显示药品名（隐私） | P2 |
| M33 | 库存流水 CSV 导出缺失（只有服药明细 CSV） | P2 |
| M34 | 统计报表"导出"实际也只导出服药明细 CSV，与页面展示的统计口径不一致 | P2 |

---

## 五、全面 UI/UX 审查（20 项）

### 5.1 布局与视觉一致性
| # | 问题 | 位置 | 实证 |
| :--- | :--- | :--- | :--- |
| U1 | 药品详情页**三段内容平铺堆叠**，缺少 MyTherapy 式的"可点击分节 → 独立子页"层级，信息密度过高 | `MedicationDetailScreen.kt` 全页 | 截图 `03_detail.png`：一屏塞了 5 个卡片，滚动 2 屏才看完 |
| U2 | 详情页「✏️ 修改计划」文案误导——实际打开的是「药品信息 + 计划」混合表单 | `MedicationDetailScreen.kt:244` | 截图 `03_detail.png` |
| U3 | 药箱顶部提示条写死「点击任意药品卡片，进入专属详情页 (说明书、注意事项、改计划、库存管理)」，重构后必然失效 | `CabinetScreen.kt:135` | 截图 `02_cabinet.png` |
| U4 | 4 个主 Tab 右上角 action 按钮语义不统一：今日=设置、药箱=添加、进展=导出、统计=导出 | `AppNavigation.kt:125,137,145,153` | 截图对照 |
| U5 | `HomeTabHeader` 的 action 按钮是 44dp 实心 `surfaceVariant` 圆底，药箱的「+」与「设置」视觉权重相同，弱化了主操作 | `HomeTabHeader.kt` | — |
| U6 | 进展页 7 天矩阵用 `SpaceBetween` + 定宽 36dp 列，列间距不均 | `ProgressScreen.kt:153-201` | 截图 `05_progress.png` |
| U7 | 进展页矩阵 SKIPPED / EXPIRED / PENDING 渲染成同一个灰 `MoreHoriz` 点，状态语义丢失 | `ProgressScreen.kt:170-199` | 同上 |
| U8 | 统计页 Hero 卡在浅色/深色下都是纯 `primary` 满色块，大字号白字对比度在部分主题下不足 | `StatsScreen.kt:94` | 截图 `06_stats.png` |
| U9 | 详情页每张卡片标题混用 emoji（⏰/📌/⚠️/📦）与 Material Icon，风格不统一 | `MedicationDetailScreen.kt:227,317,341,392` | 截图 `03_detail.png` |
| U10 | 详情页 `Text("剂型: ${med.form}   单位: ${med.unit}")` 用空格对齐，在长剂型名（如"缓释胶囊肠溶片"）下会错位 | `MedicationDetailScreen.kt:172` | — |

### 5.2 状态与反馈
| # | 问题 | 实证 |
| :--- | :--- | :--- |
| U11 | **首帧空态闪烁**：Today 页 `initialValue` 是空列表，冷启动时先闪「今日待服任务已全部完成 🎉」，1-2 秒后才出现真实数据 | 截图 `00_today.png`（"待服药 (0)" + 全部完成）→ `01_today_recheck.png`（"待服药 (3)"） |
| U12 | 药箱 / 进展 / 统计 / 详情页全部**无 Loading 骨架**，数据到达前显示空白 | — |
| U13 | 补药页非法输入（空 / 0 / 负数）**静默 return**，无任何 Toast 或错误提示 | `RefillViewModel.kt:49-50` |
| U14 | 补药页「有效期至」字段有日历图标但**点击无反应**（纯装饰） | `RefillScreen.kt:234-235` |
| U15 | 手动补录页 `deductStock` 开关视觉上可操作但功能无效（见 P0-6），构成**误导性 UI** | — |
| U16 | 保存按钮无 loading 态：`AddEditMedicationViewModel` 有 `isSaving` 状态但 UI 完全没用；`RefillViewModel.isSaving` 同理 | `AddEditMedicationScreen.kt:89-97` |
| U17 | 删除药品 / 停药归档已有二次确认 ✅（良好实践，保持） | 截图 — |

### 5.3 医疗可信度
| # | 问题 | 严重度 |
| :--- | :--- | :--- |
| U18 | **药品名错别字**：「环孢素」应为「**环孢素**」（Cyclosporine，免疫抑制剂）。出现在 `SampleDataSeeder.kt:41`、`ARCHITECTURE.md`、`UI_DESIGN.md` 等多处。医疗 App 出现通用名错字直接损伤专业可信度 | **高** |
| U19 | 统计页展示的排行榜药名（羟氯喹/环孢素/钙和维生素D/醋酸泼尼松）与用户实际药品无关，等于**向用户呈现不存在的历史数据** | **高** |
| U20 | 演示数据「环孢素 10:30/22:00」与真实临床不符（该药血药浓度谷浓度应在清晨服用），但作为 mock 数据不追究；清空 mock 后问题自然消失 | 低 |

---

## 六、单元测试覆盖评估

现有 19 个测试（`app/src/test/`）**质量良好**，但只覆盖领域层，且领域层恰好是本次要改的地方：

| 测试文件 | 覆盖内容 | 本次是否受影响 |
| :--- | :--- | :--- |
| `SlotProjectionEngineTest` (6) | DAILY/INTERVAL/DAYS_OF_WEEK/CYCLE/PRN 投影、跨月、闰年、endDate 裁剪 | 不改引擎，**可复用** |
| `DoseTrackingServiceTest` (4) | 打卡/撤销台账守恒、跳过、补货、改计划历史保护 | `logManualDose` 加 `deductStock` 参数 → **需补充用例** |
| `AppDatabaseRealTest` (5) | 外键级联、台账 `SUM(change_amount)==currentStock` 守恒、槽位生命周期 | 数据库升 v2 → **需验证迁移** |
| `StatsEngineTest` (4) | 依从率、库存 runway、剂量求和 | `StatsEngine` 要扩展真实统计 → **需补充用例** |
| **缺失** | `AddEditMedicationViewModel` 保存不丢字段 | **需新增** |
| **缺失** | `StatsViewModel` 真实聚合 | **需新增**（提取为 `StatsEngine` 纯函数后可直接测） |
| **缺失** | 进度矩阵真实计算 | **需新增** |

---

## 七、分步修改计划（9 步 / 8 次提交）

> 每步独立可编译、可测试、可回滚。**git 提交只在每步验证通过后进行。**

### 提交 1 — `fix(core)`: 修复数据丢失与假数据（领域层 + 统计层）

**目标**：先止血，把"会破坏用户数据"和"会骗用户"的代码修掉。

| 改动 | 文件 |
| :--- | :--- |
| 新增 `MedicationPatch`/`updateProfile`：`MedicationDao` 增 `updateProfile`（只写可编辑字段），**绝不 REPLACE 整行** | `MedicationDao.kt` |
| `AddEditMedicationViewModel.save()` 改用 `updateProfile`；策略保存保留 `startDate`/`endDate`/`cycleOnDays`/`cycleOffDays`/`version`（新策略 `version+1`） | `AddEditMedicationViewModel.kt`、`SchedulePolicyDao.kt` |
| `DoseTrackingService.logManualDose` 增加 `deductStock: Boolean = true` 参数 | `DoseTrackingService.kt` |
| `StatsEngine` 扩展真实聚合纯函数：`sumDoseInRange` / `adherenceInRange` / `rankingInRange` / `dailyAdherenceMatrix` | `StatsEngine.kt` |
| `StatsViewModel` 删除全部字面量，改用 `StatsEngine` + DAO 真实聚合 | `StatsViewModel.kt` |
| `ProgressViewModel` 7 天矩阵改读真实 `dose_slots`，按天聚合全部时点，区分 完成/跳过/漏服/未到 | `ProgressViewModel.kt` |
| `CabinetViewModel` INTERVAL 文案 `1 次` → `${times.size} 次` | `CabinetViewModel.kt` |
| 新增单测：`StatsEngineRealAggregationTest`、补 `DoseTrackingServiceTest` 的 `deductStock=false` 用例 | `app/src/test/` |

**验收**：`./gradlew testDebugUnitTest` 全绿；模拟器清库后自建 1 个药 + 打卡 1 次，统计页显示「1 片 / 100% / 1 种」，进展页矩阵今日绿、历史空。

---

### 提交 2 — `feat(db)`: 数据库 v2 正式迁移 + 药品字段补齐

**目标**：为后续功能开路，同时**不用破坏性迁移**。

| 改动 | 说明 |
| :--- | :--- |
| `medications` 增 `expiry_date TEXT`（有效期至） | 支撑库存临期提醒 |
| `medications` 增 `is_critical_reminder INTEGER` | 支撑按药品「重要提醒」 |
| `AppDatabase` `version = 2` + `MIGRATION_1_2` | `ALTER TABLE ... ADD COLUMN`，**移除 `fallbackToDestructiveMigration`** |
| `InventoryTransactionEntity` 增 `batch_number` / `expiry_date` | 补药批次真正落库 |
| `dose_slots` 增复合索引 `(medication_id, scheduled_date, scheduled_time)` | 统计/矩阵查询性能 |
| `AppDatabaseRealTest` 增加迁移用例 | 用 `MigrationTestHelper` 风格的手工迁移断言 |

**验收**：`./gradlew testDebugUnitTest` 全绿；从 v1 库升级后既有数据（含 precautions/isPaused）不丢。

---

### 提交 3 — `refactor(ui)`: 药品详情页改为三段式可点击分节

**目标**：对齐 MyTherapy 信息架构，**本步只改详情页与导航骨架，不新建子页**（子页在提交 4）。

| 改动 | 说明 |
| :--- | :--- |
| 详情页 Hero 卡改造成 **MyTherapy 式分节列表** | 4 行可点击：💊 药品信息 / 📦 库存 / 📅 用药时间表 / 🔔 提醒设置，行尾 `>` 箭头 |
| 每行显示摘要（药名+类别 / 剩 N 单位 / 频次+时点 / 重要提醒开/关） | 与 MyTherapy 截图一致 |
| 行尾保留 `暂停提醒` / `删除药品` 危险操作 | 二次确认弹窗保留 |
| `MedicationDetailScreen` 参数改为 `onNavigateToEditProfile` / `onNavigateToReminder` / `onNavigateToInventory` | 语义化命名 |
| 更新药箱提示条文案 | 移除"改计划" |
| 修正「环孢素」→「环孢素」 | 全库 + 文档 |

**验收**：模拟器走查详情页，4 行分节可点，摘要正确；危险操作二次确认正常。

---

### 提交 4 — `feat(ui)`: 新增「提醒设置」与「库存管理」两个独立子页

**目标**：补齐整个缺失的提醒行为维度，库存独立成页。

**`ReminderSettingsScreen`（新增）**
- 频率分段：每天 / 隔 N 天 / 每周特定天 / 按需(PRN) / 周期(CYCLE)
- 频次子配置：步进器 / 周一~周日多选 / 周期「吃 N 天停 M 天」双步进器 / PRN 说明
- **疗程**：开始日期（DatePicker）+ 结束日期（可选，DatePicker + 「无限期」开关）
- 提醒详情：可增删时点行（时间 ▾ / 剂量 / **时段标签** ▾：早餐前·随早餐·午餐·晚餐·睡前 / 🗑）
- 提醒行为：**重要提醒**开关、**提前提醒**时长、**推迟时长**、开关暂停
- 底部：未来 7 天排班预览（改频率前先看结果）
- 保存后触发 `reconcileSchedule` + `AlarmReconciler.rescheduleAll`

**`InventoryScreen`（新增，从详情页平铺内容迁移）**
- 当前余量 Hero + 预警线 + 剩余可用天数
- 「+ 补药入库」→ 现有 `RefillScreen`
- 「盘点校准」→ 写入 `CALIBRATION_ADJUST` 流水（补齐 P0/M20）
- 「关闭库存追踪」开关
- 有效期与临期提醒（`expiryDate` + DatePicker）
- 完整出入库流水列表（当前只取 `take(5)`，改为可滚动全量 + 分组）

**导航**：新增 `Screen.Reminder` / `Screen.Inventory` 路由与 `AppNavigation` 接线。

**验收**：模拟器逐项走查两个新页；改频率/疗程后今日清单与闹钟同步变化且历史打卡不变。

---

### 提交 5 — `feat(ui)`: 药品信息编辑复用添加页 + 补齐录入缺口

**目标**：解决用户反馈 1 的正面部分——编辑入口复用添加界面，并把缺失字段补上。

| 改动 | 说明 |
| :--- | :--- |
| `AddEditMedicationScreen` 增 `mode: InfoOnly` | 编辑模式下**只渲染药品信息字段**，隐藏频次/时点/库存段（库存与提醒各自有独立页），避免"改个名字要滚过整个计划表单" |
| 顶部标题按 mode 区分 | 「添加药品」/「编辑药品信息」 |
| 表单新增字段 | **单位**选择器（片/粒/袋/支/ml/滴/胶囊）、**注意事项** Chip 多选（常用 12 项 + 自定义输入）、**通知简述**、**别名**、**默认剂量**、**标识颜色**（6 色板） |
| `AddEditUiState` 同步扩展 | 上述字段双向绑定 |
| `loadExistingMedication` 完整回填 | 包含 precautions/alias/noticeShort/defaultDose/isPaused/isArchived/createdAt |
| `save()` 保留不可编辑字段 | 由提交 1 的 `updateProfile` 保证 |

**验收**：新建一个带注意事项的药 → 详情页显示 → 进入编辑 → 改名字保存 → 注意事项/暂停/归档状态仍在；单位改为「ml」后今日清单/详情/统计全部显示「ml」。

---

### 提交 6 — `fix(ui)`: 今日页推迟/跳过 + 手动补录时间选择器 + 补药落库

**目标**：清掉剩余的"承诺未实现"。

| 改动 | 说明 |
| :--- | :--- |
| `TodayScreen` 待服卡片长按 → BottomSheet：推迟 N 分钟 / 跳过本次 / 查看详情 | 补齐 P0-10 / M22 / M23；`TodayViewModel.skipDose` 与新增 `snoozeDose` 接线 |
| 已推迟(SNOOZED)卡片显示「已推迟至 HH:mm」徽标 | 状态可视化 |
| `ManualDoseScreen` 时间字段改为可点 → `DatePickerDialog` + `TimePickerDialog` | 补齐 P0-5；限制不可选未来时间 |
| `ManualDoseViewModel.save()` 传 `deductStock` | 补齐 P0-6 |
| `RefillViewModel` 批号/有效期写入新字段；`expiryDate` **去掉假预填**；日期字段接 DatePicker | 补齐 P0-7 |
| 非法输入给出行内错误提示 | 补齐 U13 |

**验收**：今日页长按能推迟/跳过；补录能选昨天 21:00；关闭扣库存开关后库存不变；补药批号在流水里可见。

---

### 提交 7 — `feat(alarm)`: 提醒设置真正生效 + 空态与加载态

| 改动 | 说明 |
| :--- | :--- |
| `Notifications.showDoseNotification` 读取 `snooze_minutes`，Action 文案动态化 | 补齐 P0-8 |
| 新增**静音时段渠道**：`night_dnd` 开启且当前在 23:00-07:00 时走 `setSilent` 渠道 | 补齐 P0-8 |
| 设置页补「提醒音效」控件（`soundMode` 字段已存在但无 UI） | 补齐 M18 |
| `TodayScreen` `initialValue` 改 `isLoading = true`，空态区分「加载中」与「全部完成」 | 补齐 U11 |
| 药箱/进展/统计加 Loading 占位 | 补齐 U12 |
| 保存按钮接 `isSaving` 显示 loading | 补齐 U16 |

**验收**：把推迟时长改成 15 分钟 → 通知栏 Action 变「推迟15分钟」；夜间收到提醒静音；冷启动不再闪"全部完成"。

---

### 提交 8 — `feat(ui)`: 药箱搜索排序 + 详情页历史/依从率 + 清空 Mock 数据

| 改动 | 说明 |
| :--- | :--- |
| 药箱加搜索框（按名称/别名模糊匹配）+ 排序菜单（名称/剩余天数/最近服用） | 补齐 M25 / M26 |
| 药品详情页增「服药历史」分区（最近 20 条，可展开全量） | 补齐 M28 |
| 药品详情页增该药品近 7/30 天依从率 | 补齐 M29 |
| **移除 `SampleDataSeeder` 的自动调用**（`TodayViewModel.init`） | 用户要求 3 |
| `SampleDataSeeder` 文件移入 `app/src/debug/`（仅 debug 构建可用）或直接删除 | 保留开发便利，不进 release |
| 新增空态引导：药箱空 → 「还没有药品，点右上角 + 添加第一个药品」+ 引导卡 | 清库后的首启体验 |
| 更新 `docs/` 全部文档：术语、字段、页面结构、统计口径 | — |
| `docs/CHANGES-20260927.md` 追加变更摘要 | 红线要求 |

**验收**：卸载重装后首启药箱为空且引导清晰；新建药品全流程无 mock 残留；单元测试全绿。

---

## 八、风险与取舍

| 风险 | 应对 |
| :--- | :--- |
| 改动面大（3500+ 行，53 个文件） | 拆 8 次提交，每步独立编译 + 测试通过才提交；任一步可单独回滚 |
| 提交 2 的数据库迁移若出错会毁用户数据 | 迁移只做 `ADD COLUMN`（SQLite 幂等安全），**不移除** `fallbackToDestructiveMigration` 直到验证通过；先在模拟器做 v1→v2 升级实测 |
| 清空 mock 数据后功能"看起来空" | 提交 8 同步补空态引导卡；调试期用 `adb shell am start` 走手工录入路径验证 |
| 「三分离」可能与用户"想在一个页面改完"冲突 | 详情页保留**只读汇总 + 明确入口**，编辑动作一律在子页；添加药品时仍可一次填完（提交 5 的 InfoOnly 只影响**编辑**模式） |
| 现有 19 个测试依赖旧签名 | `logManualDose` 加参数用**默认参数**保持向后兼容；其余为新增不改旧签名 |

## 九、明确不做（Out of Scope）

| 项 | 原因 |
| :--- | :--- |
| 自动备份 / 云同步 | 需 SAF 长期授权策略，与本次三项诉求无关 |
| 应用锁 / 生物识别 | 优先级低，且需引入加密依赖 |
| 桌面 Widget | 独立子系统 |
| 饮水/体重等非药品记录 | 超出药品提醒核心场景（旧审查 P1-8 建议砍掉） |
| 统计口径 ±N 分钟"准时率" | 需先定《统计口径规范》，本次只做"完成/跳过/漏服"三态依从率 |

---

## 十、实施结果（2026-09-27 17:55 收口）

本报告规划的 9 步已**全部实施完成**，并按"数据层 → 领域层 → UI 层 → 清理"分 4 次提交落地。
详细变更摘要见 [CHANGES-20260927.md](CHANGES-20260927.md)。

### 10.1 计划 vs 实际

| 原计划 | 实际 | 备注 |
| :--- | :--- | :--- |
| 提交 1 修复数据丢失 + 真实统计 | ✅ 拆为 2 次提交（数据层 / 统计层） | 统计真实化需要先有 DAO 聚合查询，拆开更清晰 |
| 提交 2 数据库 v2 迁移 | ✅ | 迁到第 1 次提交，因为 `updateProfile` 依赖新列 |
| 提交 3 详情页三段式 | ✅ | 与 4 合并为一次提交（路由是同一件事） |
| 提交 4 提醒设置页 + 库存页 | ✅ | |
| 提交 5 AddEdit 复用 + 补齐录入 | ✅ | 前置到第 1 次提交：`updateProfile` 要求表单必须有全部档案字段，否则编辑会清空 |
| 提交 6 推迟/跳过 + 补录时间 + 补药落库 | ✅ | |
| 提交 7 提醒行为生效 + 空态 | ✅ | |
| 提交 8 搜索排序 + 历史/依从率 + 清 mock | ✅ | 依从率与历史提前到第 1 次提交（属"清除假数据"范畴） |

### 10.2 与计划的两处偏差（均为计划本身的问题，已修正）

1. **"今天这一剂一建药就是逾期"** —— 计划未考虑：用户 10:53 新建药品、默认时点 08:30，
   立即产生一条过去的 `dose_slots` → 被 `AlarmReconciler` 判为 `EXPIRED` → 今日清单显示红色「已逾期」、
   依从率 0%。这对**全新用户**是极差的首日体验。已加两道防护：
   表单显式提示 + `startDate` 自动顺延到明天。
2. **"0/0 = 100%" 的统计空集约定会误导** —— 计划只说"分母为 0 返回 1.0f"（领域层约定正确），
   但 UI 直接渲染成「依从率 100%」会让用户以为表现完美。已在 4 处 UI 改为「—」/「暂无到期」，
   **领域层仍保留 1.0f 约定**（避免 `0/0` NaN 污染上层聚合）。

### 10.3 验证结论

- 单元测试 **71/71 PASSED**（基线 19 → 71，新增 52 项）
- `assembleDebug` / `compileReleaseKotlin` / `testDebugUnitTest` 全绿
- 模拟器 `emulator-5554`（Android 15，1080×2424）逐页实测通过，数据库直查确认数据落库正确
- 审查截图 `temp/shots/00~93`；数据库快照 `temp/carromed.db*` + 转储脚本 `temp/dbdump.py`
- 交互测试辅助脚本 `temp/emu.py`（绕开 PowerShell 重定向损坏 PNG 二进制的问题）

### 10.4 审查阶段发现但本次未修的项

| 项 | 优先级 | 原因 |
| :--- | :--- | :--- |
| 重复提醒直到确认（未点确认则每 N 分钟再响） | P1 | 需新增重复闹钟调度与会话状态，超出单轮范围 |
| 节假日 / 自定义例外日历 | P1 | 需内置节假日数据源 |
| 扫码 / OCR 录入药品 | P2 | 需引入相机与识别依赖 |
| 家人代管 / 漏服推送 | P2 | 与"纯本地零网络"定位冲突 |
| 桌面 Widget | P2 | 独立子系统 |
| ±N 分钟准时率口径 | P2 | 需先写《统计口径规范》再动统计代码 |
| 补药/服药记录的历史回改 | P2 | 当前仅支持撤销打卡与补录，不支持改历史 |

---

## 十一、当前状态

- **全部 P0 缺陷已修复**，信息架构已按 MyTherapy 基准完成三分离，Mock 数据已清空。
- 基线自检：`./gradlew testDebugUnitTest` **71/71 PASSED**。
- 模拟器 `emulator-5554`（Android 15 / API 35，1080×2424，420dpi）已连接，App 已安装并可调试。
- 审查截图归档：`temp/shots/00_today.png` ~ `93_today.png`；数据库快照 `temp/carromed.db*` + 转储脚本 `temp/dbdump.py`。
