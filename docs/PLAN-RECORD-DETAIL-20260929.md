# 统一「记录详情页」改造方案

- 日期：2026-09-29
- 范围：今日 Tab、进展 Tab 的所有 item 点击行为；`DoseRecordEdit` 页面的重构
- 不在范围：药箱 Tab（药品卡片仍跳药品详情）
- 状态：待实施（本文件只描述方案，未改任何代码）

---

## 0. 一句话

**除了药箱里的药品卡片，其它地方点开一条 item 都进入同一个「记录详情页」**；
页面按这条记录的**当前状态**渲染信息区与操作区，一处实现覆盖
待服 / 逾期 / 已服 / 已跳过 / 手动补录 五种形态。

---

## 1. 目标

1. 今日清单三个分区（待服药、今日已服、已跳过）的 item 点击，一律进入**这条记录的详情页**，
   不再跳药品详情。
2. 详情页是**全屏 page**，不是 sheet；底部操作按状态给：
   确认服用 / 推迟档位 / 跳过本次 / 撤销 / 备注。
3. 列表卡片上不再放低频操作按钮（撤销），低频操作统一收进详情页。
4. 进展 Tab 的流水 item、单药历史 item 走**同一个页面**，避免同一件事有两套界面。

## 2. 现状核对（代码事实）

| 位置 | 现在点击去哪 | 代码 |
| :--- | :--- | :--- |
| 今日 · 待服药 | 药品详情 | `TodayScreen.kt:303` |
| 今日 · 今日已服 | 药品详情 | `TodayScreen.kt:335` |
| 今日 · 已跳过 | 药品详情 | `TodayScreen.kt:354` |
| 进展 · 服药流水 | 记录编辑页 | `ProgressScreen.kt:536` |
| 进展 · 单药历史 | 记录编辑页 | `MedHistoryScreen` → `Screen.DoseRecordEdit` |

其它相关事实：

- 通知本体点击只打开 App 首页，**不存在"通知快捷操作页"**（`Notifications.kt:164` 的
  `contentIntent` 未带任何参数）；通知上只有三个 action 按钮
  （确认已吃 / 推迟 N 分钟 / 跳过本次）。
- App 内唯一的"快捷操作"是待服卡**长按**弹出的 `ModalBottomSheet`
  （`TodayScreen.kt:363-442`，5 档推迟 + 跳过本次）。
- 现有记录页 `DoseRecordEditScreen` 底部**只有两个按钮**（标记已跳过、撤销），
  而 `docs/UX-PLAN-20260929.md` §3.2 设计的是 `[确认] [跳过] [撤销]` 三个 ——
  **"确认"从未实现**。
- **服务层缺"改判"通路**：`DoseSlotDao.markCompletedIfOpen` / `markSkippedIfOpen`
  的守卫都是 `status IN ('PENDING','SNOOZED','EXPIRED')`，
  所以「已服 → 跳过」「已跳过 → 确认」现在**一律返回 false**。
- 既有缺陷（顺手修）：`DoseRecordEditViewModel.mutateStatus()` 对无槽位记录
  点「标记已跳过」时实际只执行了 `editDose(newNote)`（存了个备注），
  却 toast「已标记为跳过」——跳过没发生，提示说发生了。

## 3. 决策：做成**一个**页面，不做三个

理由：

1. 三种状态共享同一套信息区（药名 / 计划时点 / 实际时点 / 剂量 / 备注 / 库存余量），
   只有操作区与状态徽标不同。拆成三页会有三份布局代码，必然漂移。
2. 状态是**运行期变化**的：在详情页点「撤销」，同一条记录就从"已服"变成"待服"。
   多页面方案要么跳转、要么重载，而单页面只要数据源是 Flow，形态自动切换。
3. 项目已有 `DoseRecordEditScreen`，其剂量/时间/备注编辑与 2 天窗口规则必须保留 ——
   再新开一个页面等于把规则抄两遍（本项目明确反对影子实现）。

**所以：重构现有页面，而不是另建一个。** 页面名统一为**记录详情页**。

## 4. 统一数据模型

页面的唯一数据源是一个「记录条目」，由两个可选定位方式归一而来：

```
入口 A：slotId   （今日清单三分区、进展流水中有排班的记录）
入口 B：recordId （进展流水/单药历史中的手动补录记录，slot_id == null）

归一为：DoseEntry
  ├─ slot   : DoseSlotEntity?     存在的槽位（决定"计划"侧信息与状态）
  ├─ record : DoseRecordEntity?   当前有效事实（决定"实际"侧信息）
  └─ history: List<DoseRecordEntity>  该槽位的全部事实（含 REVERTED，用于显示撤销痕迹）
```

状态机（槽位状态即条目状态）：

```
        ┌──────────── 确认服用（扣库存） ───────────┐
        │                                          v
  PENDING / SNOOZED / EXPIRED  ──跳过（不扣）──>  SKIPPED
        ^            ^                             │
        │            └──────── 撤销（回补） ────────┤
        │                                          │
        └──────── 撤销（回补） ──── COMPLETED <────┘
                                     ^   确认（改判，扣库存）
```

两条新增迁移（虚线方向）：
- `SKIPPED → COMPLETED`：作废跳过事实 → 槽位回 PENDING → 补记已服事实 + 扣库存
- `COMPLETED → SKIPPED`：作废已服事实 → 按净额回补库存 → 槽位回 PENDING → 记跳过事实

### 4.1 相关领域事实：EXPIRED 是怎么产生的（实施时必须知道）

`AlarmReconciler.rescheduleAll()` 第 1 步：

- `cutoff = now - 2 小时`（`EXPIRE_WINDOW_MS = 2 * 60 * 60 * 1000L`）
- `PENDING` 按 `scheduled_ts`、`SNOOZED` 按 `snooze_until_ts` 与之比较，**严格小于**才结算为 `EXPIRED`
- 结算同时撤掉该槽位三种闹钟 + 撤掉托盘通知

**不是"过 0 点结算"**，而是"计划时间过去 2 小时"。而且结算只在某一轮对账跑过时才发生，
对账的四个触发点：冷启动 / 每次回到前台（`MainActivity` 的 `repeatOnLifecycle(RESUMED)`）/
开机 / 每次闹钟响后，外加 `ReconcileWorker` 每 15 分钟一轮（名义下限，实际由系统调度）。

**由此产生的边界**（不要当成 bug）：

- 昨晚 23:00 未处理的槽位，次日 01:00 之前仍是 `PENDING`，**仍挂在待服区**，
  确认 / 推迟 / 跳过都还能点；
- `EXPIRED` 槽位在今日清单里归入"待服药"区（`TodayViewModel` 把 EXPIRED 与 PENDING/SNOOZED
  一起放进 `pendingItems`），所以它的操作集合是「确认 / 跳过」，没有"撤销"（还没有结论）。

### 4.2 撤销 = 撤回事实，不是删除（I11）

`undoManualDose(recordId)` = `UPDATE dose_records SET status='REVERTED'` + 按净额补台账冲正。
事实行**保留在库里**：

| 位置 | 撤销后 |
| :--- | :--- |
| 进展 · 服药流水 | 仍显示一条，灰色 + 「已撤销」chip |
| 统计（依从率 / 消耗） | 不再计入（口径只取 `COMPLETED`） |
| 今日清单 | 本来就不显示手动补录（它没有槽位） |

不能物理删除的原因：台账 `inventory_transactions.record_id` 指向该事实，
删了就是悬空引用；`DoseRecordDao` 因此刻意不提供任何删除方法。

## 5. 页面设计

### 5.1 形态与操作矩阵

`—` = 不显示该按钮（**不置灰**：置灰会让人以为"再等等就能用"）。

| 条目形态 | 判定依据 | 确认服用 | 推迟档位 | 跳过本次 | 撤销 |
| :--- | :--- | :---: | :---: | :---: | :---: |
| 待服 | `PENDING` | 主按钮 | 有 | 有 | — |
| 已推迟 | `SNOOZED` | 主按钮 | 有 | 有 | — |
| 已逾期 | `EXPIRED` | 有（补记） | — | 有 | — |
| 已服 | `COMPLETED` | —（当前态） | — | 有（改判） | **仅当天** |
| 已跳过 | `SKIPPED` | 有（改判） | — | —（当前态） | **仅当天** |
| 手动补录 | `record.slotId == null` | — | — | — | 仅当天（见 §10） |

三条规则：

- **EXPIRED 不给「推迟」**：`DoseSlotDao.snoozeSlot` 的守卫是
  `status IN ('PENDING','SNOOZED')`，对逾期槽位必然失败。按钮存在却永远失败
  就是虚假承诺，所以不显示。
- **「撤销」与「改判」是两件事，时限不同**（2026-09-29 用户拍板）：

  | 操作 | 时限 | 语义 |
  | :--- | :--- | :--- |
  | 撤销（回未确认） | **仅当天** | 我不吃了 / 没带药 / 点错了，这事当没发生 |
  | 确认 / 跳过（含两者互相改判） | 无时限 | 给已经过去的一次用药定个结论 |
  | 剂量 / 时间编辑（仅手动补录） | 待定 | 修正事实本身，不涉及状态 |

- **为什么「过去不能撤销」是对的**：撤销会把槽位退回 `PENDING`，而该槽位的计划时间
  早已过去 ⇒ 下一次对账（`AlarmReconciler` → `getStaleOpenSlots`）立刻把它结算成
  `EXPIRED` ⇒ 用户翻到那一天会看到一个**永远清不掉**的"已逾期未确认"待办。
  也就是说撤销一条历史记录不是"取消"，而是把一条历史变成一条永久待办。
  新规则封住这条路径：**过去必须有结论，但结论可以在确认/跳过之间修正。**

- **非当天的形态与操作**：`EXPIRED` → 确认 / 跳过；`COMPLETED` → 跳过；
  `SKIPPED` → 确认。即任何非当天条目都**不能回到"待服"状态**。

- **收紧影响面**：现状是 **2 天窗口**（`EDITABLE_WINDOW_DAYS = 2`），本轮收紧为
  「当天」。需同步修改 `isWithinEditWindow()`、只读文案、以及
  `DoseRecordEditWindowTest`（改名后守新规则：当天可撤销、非当天不可撤销）。

### 5.2 页面结构

```
┌──────────────────────────────────┐
│ ←  记录详情            [已服]     │   ← 状态徽标
├──────────────────────────────────┤
│ 羟氯喹                            │
│ 计划 09:00 · 实际 09:04 · 1 片    │   ← 计划/实际分开显示，不共用一个时间
│ 剩余 6 片                         │   ← 仅开启库存追踪时
│ 备注  [随餐温水送服        ]      │
├──────────────────────────────────┤
│ [ 跳过 ]   [ 撤销 ]               │   ← 按矩阵渲染
└──────────────────────────────────┘
```

待服形态把按钮区换成：`确认服用`（主按钮，带备注一起落库）
→ 推迟档位 `10 / 15 / 30 / 60 / 120 分` → `跳过本次（不扣库存）` → 备注输入框。

**备注的落库语义**（已确认）：待服状态下还没有服药事实，备注**随「确认服用」一起落库**；
页面上写明"备注会随确认一起保存"，只填不确认则丢弃 —— 不使用"看起来保存了"的假提示。

## 6. 路由与入口映射

```kotlin
/** 统一记录详情页：两个查询参数二选一，都必须有 defaultValue 才能被路由匹配 */
data object DoseDetail : Screen("dose_detail?slotId={slotId}&recordId={recordId}") {
    fun forSlot(slotId: Long) = "dose_detail?slotId=$slotId"
    fun forRecord(recordId: Long) = "dose_detail?recordId=$recordId"
}
```

| 入口 | 定位方式 |
| :--- | :--- |
| 今日清单 · 待服药 / 已服 / 已跳过 | `forSlot(slot.id)` |
| 进展 · 服药流水 | `record.slotId?.let { forSlot(it) } ?: forRecord(record.id)` |
| 进展 · 单药历史 | 同上 |

旧路由 `dose_record/{recordId}` 删除（项目未发布，不需兼容）。

## 7. 分层改动清单

### 7.1 前置重构：抽 `DoseEntryActions`（必要）

现状是「槽位状态变更 + 闹钟编排 + 通知清理」这套逻辑**在 `TodayViewModel` 与
`DoseActionReceiver` 各写了一份**（各约 30-40 行）。新页面还需要第三份 ——
三份必然漂移，而漂移的后果是"某个入口打卡后闹钟没取消，到点又响一次"。

所以先抽：

```kotlin
// core/domain/service/DoseEntryActions.kt
class DoseEntryActions(private val db: AppDatabase) {
    suspend fun confirm(slotId: Long, note: String? = null, takenAmount: Float? = null): Boolean
    suspend fun snooze(slotId: Long, minutes: Int): Boolean
    suspend fun skip(slotId: Long): Boolean
    suspend fun undo(slotId: Long): Boolean
    suspend fun restate(slotId: Long, target: RecordStatus, note: String? = null): Boolean
}
```

每个方法内部固定顺序：**服务层事务 → 取消/重排闹钟 → 取消通知**，
调用方（三个 ViewModel + Receiver）只调用它，不再自己编排。
`AlarmScheduler.alarmUri` 的内容寻址与 `AlarmIdentityTest` 不受影响。

### 7.2 服务层：新增 `restateSlot`

```kotlin
/**
 * 改判：作废槽位当前的结论，再施加新结论（COMPLETED / SKIPPED），全过程一个事务。
 * 台账按**净额**口径冲正（与 undoDose 同一判据），因此不会重复扣减或漏回补。
 */
suspend fun restateSlot(slotId: Long, target: RecordStatus, note: String? = null): Boolean
```

实现要点：

1. 把 `undoDose` 中「槽位回退 + 事实标 REVERTED + 逐条净额冲正」抽成
   private `revertSlotInternal(slotId)`，`restateSlot` 复用（避免第二份净额算法）。
2. 幂等：状态已是 `target` 直接返回 false。
3. 顺序：`revertSlotInternal` → `markCompletedIfOpen/markSkippedIfOpen` → 插事实 → 扣账。
   回退到 PENDING 之后，两条 mark 的守卫必然命中。
4. 不删事实：旧事实一律标 `REVERTED`（I11：吃过的药永不丢失）。

**无 schema 变更** —— 不改 `@Entity`，因此**不需要动 `AppDatabase.version`**。

### 7.3 页面层

| 文件 | 动作 |
| :--- | :--- |
| `ui/screen/record/DoseRecordEditScreen.kt` | 重构为 `DoseRecordDetailScreen.kt` |
| `ui/screen/record/DoseRecordEditViewModel.kt` | 重构为 `DoseRecordDetailViewModel.kt`，状态扩为 `DoseEntryUiState` |
| `ui/navigation/Screen.kt` | 删 `DoseRecordEdit`，加 `DoseDetail` |
| `ui/navigation/AppNavigation.kt` | 注册新路由；三处跳转改为新入口 |

VM 约束（AGENTS.md §2 第 5 条）：**构造器只能有 `Application` 一个参数**，
`slotId` / `recordId` 通过 `load(...)` 传入；不得加构造参数，否则
`viewModel()` 默认工厂反射找不到单参构造器，编译与单测都过、真机一打开就崩。

数据源：`doseSlotDao.observeSlotById(slotId)` 为主（槽位被对账结算为 EXPIRED 时形态自动跟随），
事实用一次性读取 + 每次操作后重读；`recordId` 入口先读事实，若 `slotId != null` 则转为观察该槽位。

### 7.4 列表层

`TodayScreen.kt`：

- 三分区 `onClick` → `onOpenDose(slotId)`
- 待服药卡**保留**右侧 ✓ 快捷打卡（高频动作留在列表）
- 今日已服卡、已跳过卡的「撤销」按钮**删除**（按"统一"原则，低频操作只在详情页）
- 长按 sheet 与「长按可推迟或跳过」提示**删除**
- 「防误触随时可撤销」文案改为指向详情页的说法

`ProgressScreen.kt` / `MedHistoryScreen.kt`：行点击按 §6 归一后跳新页。

### 7.5 走查脚本

`tools/app_screenshots.py` 的 `PROGRAM` 必须登记新页（AGENTS.md §2 硬要求）：
今日页点待服 item → 截待服形态；点已服 item → 截已服形态。旧 key `dose_record` 相应更新。

### 7.6 测试

| 测试 | 守什么 |
| :--- | :--- |
| `DoseRestateTest`（新增） | 双向改判后 `SUM(change_amount)` 与事实净额一致；连点幂等；改判后再撤销仍守恒 |
| `DoseRecordDetailStateTest`（新增） | 状态 × 时间 → 可见操作的矩阵（当天 / 非当天两组） |
| `DoseRecordDetailWindowTest`（改名自 `DoseRecordEditWindowTest`） | **当天可撤销、非当天不可撤销**；非当天只能确认/跳过 |
| `DoseRecordEditTest`（保留） | 剂量 / 时间编辑与手动补录撤销不回归 |
| `AlarmIdentityTest`（回归跑） | 抽 `DoseEntryActions` 后闹钟身份仍内容寻址 |

每条新测试都要能失败：写完做一次变异验证（故意改坏实现，确认变红）。

## 8. 风险与不变量

1. **台账守恒**：`SUM(change_amount)` 与事实净额必须一致（I1/I2）。改判是本轮唯一
   会"回补已有扣减"的新路径，测试重点在这里。
2. **闹钟**：确认/跳过后必须 `cancelAll`（三种 kind）；撤销后必须 `rescheduleAll`。
   这条逻辑只能有 `DoseEntryActions` 一处实现。
3. **「当天」窗口**：日期选择器是 ±3 天，所以今日清单里的已服 / 已跳过 item
   经常就不是当天的 —— 撤销必须按 `actualTs` 的自然日严格判定，且
   **按钮渲染的判据与动作校验的判据必须同源**（AGENTS.md §2 第 7 条），
   否则会出现"按钮在、点了失败"或"按钮不在、其实能点"。
4. **槽位/记录双 id 空间**：`medications.id` 与 `dose_slots.id` 是两条独立自增序列，
   今日页 LazyColumn 的 `key` 已带类型前缀（`slot-` / `lowstock-`），新页面不要引入裸 id 比较。
5. **行尾**：本仓库行尾混存，改完必须 `git diff --stat` 确认没有整文件重写。

### 8.1 已知问题：跨午夜（本轮不修，记一笔）

撤销时限按 `actualTs` 的**自然日**判定，因此存在一个真实断点：

```
23:00 计划  →  23:05 打卡  →  次日 00:20 想撤销   ⇒ 被拒（actualTs 落在昨天）
```

而这次打卡距现在只有 1 小时 15 分，用户的语义（"点错了 / 出门发现没带药"）
完全成立 —— 这是个真实的可用性缺口，用户 2026-09-29 明确要求先记下。

备选修法（待定）：

| 方案 | 规则 | 代价 |
| :--- | :--- | :--- |
| A（倾向） | 自然日当天 **或** 距事实发生 ≤ K 小时（K = 4~6，覆盖睡眠时段） | 多一个判据，文案要说清"什么时候还能撤销" |
| B | 只按「距事实发生 ≤ K 小时」 | K 小 ⇒ 早上想吃药前发现昨晚记错了会被拒；K 大 ⇒ "过去"变得可撤销，绕回永久待办问题 |
| C | 按事实时间而非自然日 | 与 A 等价，只是表述不同 |

倾向 A：保留"当天"作为主判据，叠加一个跨午夜宽限，两个判据取**或**。

## 9. 验收

```powershell
./gradlew testDebugUnitTest
python tools\app_screenshots.py --clear --seed
```

1. 单测全绿（当前 420 项，门禁是不变量而非项数）。
2. 走查截图**逐张看图**：待服形态（含推迟档位与备注框）、已服形态（跳过 + 撤销两个按钮）、
   已跳过形态（确认 + 撤销），三张都要核对按钮与文案。
3. 手动验证一条闭环：确认 → 撤销 → 再确认，确认库存台账每次都守恒
   （`cmd /c "adb ... cat databases/carromed.db"` + `python temp\dbdump.py`）。

## 10. 决议与待定

### 已拍板（2026-09-29）

1. **已服卡、已跳过卡的「撤销」按钮一并删除** —— 统一到详情页。
2. **当前状态不放按钮**：已服时不显示「确认」，已跳过时不显示「跳过」。
3. **`REVERTED` 不出现在流水与历史里**（2026-09-29 追加决定）：
   撤销是**动作**不是状态 —— 用户撤销掉的那条不该继续占位置
   （实测撤销几次后流水里连着四条「已撤销」，用户明确要求去掉）。
   过滤落在 **SQL 层**（`DoseRecordDao` 的 `observeLatestRecords` /
   `getRecordsBefore` / `getRecordsForMedication`），不能在 UI 层 filter
   （页大小会不稳定、`hasMore` 判断失真）。事实仍在库里（I11），
   备份/导出走 `getAllRecords` 取全。
4. **撤销仅限当天**；非当天只能确认 / 跳过（含两者互相改判）。
5. 待服页推迟给**多档**（10/15/30/60/120 分）；备注**随「确认服用」一起落库**。

### 待定（实施前确认）

6. **手动补录记录的「撤销」是否也限当天**？它的撤销只是把事实标 `REVERTED`，
   不会把任何条目退回待服，因此**不产生永久待办**，可以比计划内记录宽松。
   **建议与本文件第 7 条统一为 2 天**（同一个判据，不必维护两套时限），待拍板。
7. **手动补录记录的剂量 / 时间编辑**：**保留现有 2 天**（2026-09-29 用户拍板）。
   它属于"修正事实"而非"改状态"，与计划内记录的撤销不是同一类。
8. **手动记录撤销后不显示已撤销痕迹**（承接第 3 条）：撤销后该条在流水里变灰，
   详情页不再额外提示 —— 这也是用户确认的口径。
