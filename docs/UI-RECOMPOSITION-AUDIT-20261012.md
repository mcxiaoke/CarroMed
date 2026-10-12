# 界面全屏刷新 / 闪烁问题 —— 全量排查与整改方案

- 日期：2026-10-12
- 范围：`app/src/main/kotlin/com/mcxiaoke/carromed/ui/` 下全部 14 个 Screen + 11 个 ViewModel + 导航层 + 主题层
- 性质：只读审计（本文档成文时未改动任何代码）
- 关联不变量：AGENTS.md §二「动手前必须知道的红线」、§三「任务验收标准」

---

## 一、结论摘要

用户报告的现象是「随便输入框一个数字就全屏闪烁刷新」。**这不是 loading 转圈与内容的来回切换**，
而是「单个输入字符 → 整个界面的 state 对象换新 → 整棵组合树失效重组」，
再叠加若干会把重组放大成**肉眼可见跳动**的因素。

排除性证据：全项目搜索 `isLoading = true` 只有 **1 处**
（`TodayViewModel.kt:244`，且仅在重试路径）。所有 ViewModel 的 `load()`
都不会把 `isLoading` 置回 true，因此「转圈 ↔ 内容」循环在结构上不存在。
**任何声称要"修 loading 闪烁"的方案都是修错了地方。**

真正的根因是 Compose snapshot 跟踪的粒度问题，见 §二。

---

## 二、根因：状态容器粒度过粗

全部 14 个 Screen 使用同一写法：

```kotlin
val uiState by viewModel.uiState.collectAsStateWithLifecycle()
```

Compose 的 snapshot 跟踪粒度是「这次读的是**哪个 `State` 对象**」，
而**不是**「读了它的哪个字段」。读 `uiState.x` 与读 `uiState.y` 落在**同一个 invalidation 域**：
任何字段变化，函数体内所有读点一起失效。

因此：输入一个字符 → `_uiState.value = _uiState.value.copy(草稿字段=新值)`
→ 整个 state 对象换新 → 整页重组。

字段规模（决定了无效化的波及面）：

| State | 字段数 | 位置 |
| :--- | ---: | :--- |
| `AddEditUiState` | 25 | `AddEditMedicationViewModel.kt:86-132` |
| `ReminderSettingsUiState` | 22 | `ReminderSettingsViewModel.kt:58-114` |
| `DoseEntryUiState` | 19 | `DoseRecordDetailViewModel.kt:93-143` |
| `InventoryUiState` | 17 | `InventoryViewModel.kt:33-57` |
| `MedDetailUiState` | 17 | `MedicationDetailViewModel.kt:31-66` |
| `TodayUiState` | 17 | `TodayViewModel.kt:65-104` |
| `SettingsUiState` | 16 | `SettingsViewModel.kt:23-53` |
| `MedHistoryUiState` | **4** | `MedHistoryViewModel.kt:60-65` |

全项目 `derivedStateOf` **零使用** —— 这个坑一次都没踩过。
`MedHistoryUiState` 只有 4 个字段且无输入框，是全项目最干净的参照。

---

## 三、把「重组」放大成「闪烁」的四个因素

### 3.1 `imePadding()` 套在 `LazyColumn` 上 —— 最直接的闪烁源

6 处命中：

| 文件 | 行 |
| :--- | ---: |
| `InventoryScreen.kt` | 181 |
| `AddEditMedicationScreen.kt` | 177 |
| `RefillScreen.kt` | 97 |
| `ReminderSettingsScreen.kt` | 174 |
| `ManualDoseScreen.kt` | 115 |
| `DoseRecordDetailScreen.kt` | 166 |

`Modifier.imePadding()` 让**整个列表**随键盘高度缩放。每敲一个字符触发一次
重新测量 + 布局，全屏内容上下跳。

正确做法：`imePadding()` 放在 `Scaffold` 的内容容器上，
或配合 `bringIntoViewRequester()` 只滚动到焦点元素。

### 3.2 `InventoryScreen.kt:91` —— `remember` 的教科书级反例

```kotlin
val isDirty = remember(uiState) { viewModel.isDirty() }
```

两个错误叠加：

1. **key 是整个 17 字段 data class** —— 任一字段变化就失效重算；
2. `viewModel.isDirty()`（`InventoryViewModel.kt:219-225`）内部读的是
   `_uiState.value` —— **StateFlow 的值，不是 snapshot state**，
   Compose 根本追踪不到这份读取。

结论：这个 `remember` 缓存是**纯负担**，不产生任何跳过效果。
直接调 `viewModel.isDirty()` 即可（它读的本来就是当前值）。

### 3.3 输入框双重过滤 —— 规则不一致

`InventoryScreen.kt:318`：

```kotlin
onValueChange = { viewModel.onMinStockAlertChange(it.filter { c -> c.isDigit() || c == '.' }) }
```

Screen 层滤一遍，`InventoryViewModel.onMinStockAlertChange`
（`:198-201`）内部 `DecimalInput.filter` 又滤一遍。

而 `DecimalInput.filter`（`DecimalInput.kt:41-55`）额外接受
全角 `'。'` / `'．'`，Screen 层这层**不接受**：
两层规则不一致，全角小数点会被静默吞掉。

### 3.4 LazyColumn item 内每行新建 `SimpleDateFormat`

| 位置 | 代码 |
| :--- | :--- |
| `InventoryScreen.kt:425` | `SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(tx.createdAt))` |
| `MedicationDetailScreen.kt:451` | `SimpleDateFormat("MM-dd HH:mm", ...)` |
| `MedicationDetailScreen.kt:520` | 同上 |
| `TodayScreen.kt:215` | `DateTimeFormatter.ofPattern(...)` |

`SimpleDateFormat` 构造要走 `DateFormatSymbols` + `Calendar` 初始化，
是 Compose 代码里公认最贵的对象之一。库存页流水一多，
每敲一键就是 N 次构造。

---

## 四、每敲一字触发 DB 往返的两处（最严重）

### 4.1 `CabinetViewModel.kt:112-136` —— 搜索框每敲一字跑 2 条 DAO 查询

`_keyword` 是 `combine` 的源之一，而 transform 块里有：

- `policyDao.getAllActivePolicies()`（`:122`）
- `policyDao.getTimesForPolicies(...)`（`:125`）

叠加问题：`CabinetUiState.filteredActive` / `filteredArchived`
（`:64-67`）是 **getter 不是 val**，每次访问都重跑 `filterBy()` + `sortedBy()`；
`CabinetScreen.kt:221` 每帧访问一次，`:257` 再访问一次 `.isEmpty()`。

另：`CabinetMedCard`（`CabinetScreen.kt:320`）每张卡片一个独立
`collectAsStateWithLifecycle` —— N 个药品 = N 个订阅者。

### 4.2 `ReminderSettingsViewModel.kt:593-594` —— 一个字符两轮全屏重组

```kotlin
_uiState.value = next      // 第 1 次发射
refreshPreview()           // 末尾 :577 又 _uiState.value = ...copy(preview=preview) —— 第 2 次
```

且 `ReminderSettingsScreen.kt:464` 是 `PreviewCard(uiState)` ——
把整个 22 字段 state 传下去，只为用 `preview` 一个字段。

---

## 五、全局探针导致的跨页误唤醒

`observeDecidedSlotCount()` / `observeRecordCount()` 的 SQL 已确认
**没有 `medication_id` 过滤**，是全库计数，却被三个页面当作本页探针使用：

| 位置 | 探针 | 代价 |
| :--- | :--- | :--- |
| `InventoryViewModel.kt:102` | `observeDecidedSlotCount()` | 任意药打卡 → 4 条查询 + 整体 copy 17 字段 |
| `MedicationDetailViewModel.kt:103-104` | 两个全局探针 | 任意药打卡 → 8 条查询，其中 `:171` `ensureDefaults` 是**写操作** |
| `StatsViewModel.kt:245-246` | 两个全局探针 | 任意药打卡 → 全量 `StatsEngine` 聚合 |

SQL 位置：`DoseSlotDao.kt:484-493`、`DoseRecordDao.kt:141-149`。

**正确范式已在项目中**：`MedHistoryViewModel.kt:94` 使用了按药过滤的
`observeRecordCountForMedication`（`DoseRecordDao.kt:156-157`），
`DoseSlotDao.kt:417` 也已有 `observeSlotStatusCountsForMedication`。

---

## 六、排查中被排除的「疑似问题」（避免后续误修）

以下是审计过程中核实过、**确认不是问题**的点，记录在此以免重复排查：

| 项 | 核实结论 |
| :--- | :--- |
| **loading 全页替换反复闪烁** | 结构上存在 7 处整页二选一，但 `isLoading` 仅在首次置 false，**实际反复闪烁的为 0 处**。隐患在于「一改就闪」 |
| **LazyColumn item key 不稳定** | 全部 13 处 key 均为稳定业务 ID 或常量，**无位置索引、无新建对象**。这是全项目做得最好的一项 |
| **输入触发写库** | 8 个页面的所有 `onValueChange` **零命中** DAO/领域层写操作，只做内存 copy |
| **Settings 页开关即时写库** | **正确**。设置项无草稿概念，即时持久化是对的 |
| **`LazyColumn` 内 `rememberSaveable`** | `CabinetScreen.kt:105` 排序菜单已用 `rememberSaveable`，**正确**（见 §八 踩过的坑） |
| **`PermissionCheckScreen` 的 `remember`** | `remember { mutableStateOf(PermissionFacts.query(context)) }` key 为空 = 正确，权限状态不该按重组重查 |
| **`CurrentDateHolder` 每分钟轮询** | 60s 间隔且 `refresh()` 只在日期真变时发射（`:104-108`），已节流 |

---

## 七、整改方案

### 第 1 批 · 零风险，收益最大

| # | 改动 | 位置 | 治什么 |
| ---: | :--- | :--- | :--- |
| 1 | 输入草稿从 uiState 移到 `rememberSaveable` 局部状态，提交时才回写 VM | 各输入框 | §二 根因 |
| 2 | `imePadding` 从 `LazyColumn` 移到 `Scaffold` 内容层 | 6 处 | §3.1 键盘全屏跳动 |
| 3 | 删除 `remember(uiState)`，直接调 `viewModel.isDirty()` | `InventoryScreen.kt:91` | §3.2 |
| 4 | `SimpleDateFormat` / `DateTimeFormatter` 提到文件顶层 `private val` | 4 处 | §3.4 |
| 5 | 去掉输入框双重过滤，统一走 `DecimalInput.filter` | `InventoryScreen.kt:318` | §3.3 |

**第 1 项的正确范式已在项目内**：`AddEditMedicationScreen.kt:439` 的
`customTag` —— 局部 `rememberSaveable`，不进 uiState。

### 第 2 批 · 需要改 DAO / ViewModel

| # | 改动 | 位置 |
| ---: | :--- | :--- |
| 6 | 新增按 `medication_id` 过滤的探针变体，三个页面改用 | `DoseSlotDao` / `DoseRecordDao` + 3 个 VM |
| 7 | `_keyword` 从 `combine` 摘出，改 `debounce(200)` 独立搜索流；`filteredActive/Archived` 从 getter 改 `val` | `CabinetViewModel` |
| 8 | `refreshPreview()` 移出 `mutate()`，仅在影响排班的字段变更时调用 | `ReminderSettingsViewModel` |

### 第 3 批 · 架构收敛（暂不实施）

大 state（`AddEditUiState` 25 / `ReminderSettingsUiState` 22 / `DoseEntryUiState` 19）
按职责拆成多个 `State`，Screen 分别 collect。

---

## 八、本项目已经踩过并记录在案的坑（供对照）

审计中反复看到这些注释，它们是「为什么现在长这样」的直接答案：

1. **草稿归属标记**（`InventoryViewModel.kt:85-90`）
   `expiryDraftDirty` / `alertDraftDirty` —— 后台探针触发的 `load()`
   不许用库值覆盖用户正在编辑的草稿，否则「编辑期间后台刷新吃掉输入」。
   > ⚠️ **第 1 批第 1 项拆分草稿时必须保留这个语义**，否则会把这个已修的 bug 改回来。

2. **`calibrateInput` 不许无条件回填**（`InventoryViewModel.kt:181-195`）
   判据用「用户还没动过」而不是无条件回填。

3. **`LazyColumn` item 内的 `remember` 会随滚出视口销毁**
   （`CabinetScreen.kt:102-105`、`AddEditMedicationScreen.kt:430-438`）
   > ⚠️ **拆草稿时不能把 `rememberSaveable` 写进 `item {}` 内**，
   > 必须写在顶层 Composable 作用域，否则滚一下输入就消失。

4. **视图模型工厂反射路径只有真机能覆盖**
   （AGENTS.md §二第 4 步）—— 第 1、2 批改完必须**亲自看图**。

---

## 九、验收标准

- [ ] `./gradlew clean assembleDebug` 通过
- [ ] **release 也能编译**（AGENTS.md §三：debug 能编不代表 release 能编）
- [ ] `./gradlew testDebugUnitTest` 全绿（涉及 `InventoryViewModelTest` 等 6 个 VM 单测）
- [ ] 涉及界面的，**亲自在模拟器上看过图** —— Compose 改完不看成图等于没改
- [ ] 输入一个数字后：键盘不再引起全屏跳动；列表不整体位移；输入框内容不被回填覆盖
- [ ] 切后台再回前台、在别处打卡后返回：草稿仍在（§八-1 的不变量）
- [ ] 变更记录追加到 `docs/CHANGES-20261012.md`