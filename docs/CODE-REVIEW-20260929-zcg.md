# 全项目代码审查报告（zcg，2026-09-29）

> **审查时间**：2026-09-29 09:17 (GMT+8)
> **审查方式**：静态逐文件精读（main 源集 69 个 Kotlin 文件全部过目，debug 源集播种器核对），
> 对照 `docs/REMINDER-DOMAIN-REDESIGN.md` §4 的 I1–I12 与 D1–D5、`AGENTS.md` §2 的四条架构坑，
> 以及 `docs/CHANGES-20260929.md` 上一轮 7 个已修缺陷 + 7 条"尚未处理"清单。
> **本轮未运行测试与模拟器** —— 所有结论均给出代码级证据链，修复前应先按 §5 的建议写出能失败的测试。
>
> **证据纪律（2026-09-29 当日补充，前提：作者承认注释/CHANGES/部分测试存在虚假）**：
> 本报告 19 条发现的证据**全部取自可执行代码**（控制流、`@Query` 中的 SQL 字符串、注解、grep 机械验证），
> docs 与 KDoc 仅用作背景线索与检查清单，**不构成任何一条结论的依据**；
> 恰恰相反，#3 与 #14 的发现形式就是"注释/文案与代码行为矛盾"。
> **既有测试未做逐文件审计**（见附录修正），在"测试可能造假"的前提下，
> 本报告**不为任何既有测试的守卫能力背书**；§4 的"仍未存在"核对均为当日对当前代码的直接阅读结果。

---

## 〇、总览

本轮在上一轮（7 缺陷修复）之后的代码上**新发现 19 个问题**：P1 × 1、P2 × 7、P3 × 11。
上一轮记录的 7 条"尚未处理"经核对**全部仍然存在**（见 §4）。

| # | 严重度 | 问题 | 位置 |
| :--: | :---: | :--- | :--- |
| 1 | **P1** | 同一计划内两个相同时点 ⇒ 槽位被静默吞并，剂量凭空丢失 | `MedicationAdminService` / `DoseSlotDao.insertAll` |
| 2 | P2 | 通知栏快捷操作的协程**没有 catch** ⇒ 任何 DB 异常直接崩溃进程 | `DoseActionReceiver` |
| 3 | P2 | 今日页事件反馈通道**从未接线** ⇒ 所有失败提示用户永远看不到 | `TodayViewModel.events` / `TodayScreen` |
| 4 | P2 | 归档药品不清理未来槽位 ⇒ 今日清单出现"未知药品"幽灵待办，且**可打卡扣库存** | `MedicationDetailViewModel.toggleArchive` |
| 5 | P2 | 逾期(EXPIRED)槽位的"确认服药"按钮**永远失败**，且失败文案撒谎 | `TodayScreen` / `DoseSlotDao.markCompletedIfOpen` |
| 6 | P2 | `setStockTracking` 的"从零建档"分支条件写成 `current <= 0` ⇒ 负余额时账面错（潜伏缺陷） | `DoseTrackingService` |
| 7 | P2 | 时区变更后已物化槽位的 `scheduled_ts` 不重算 ⇒ 最多 14 天的提醒按旧时区时刻响 | `DoseTrackingService.reconcileSchedule` |
| 8 | P2 | 进程跨午夜存活 ⇒ 今日页/进展页日期凝固在昨天，无任何时钟翻转监听 | `ProgressViewModel` / `TodayViewModel` |
| 9 | P3 | `full_screen_alert` 设置有开关有字段，但**通知层从不消费** | `Notifications` / `ReminderSettings` |
| 10 | P3 | `sound_mode` 设置只影响设置页自己的回显，通知声音从不随它变 | `SettingsViewModel` / `Notifications` |
| 11 | P3 | `KEY_LEAD_MINUTES` 是死常量（全工程零引用） | `ReminderSettings:20` |
| 12 | P3 | `validateBackup` 漏三类静默吞行检查：`dose_slots` 主键重复、`reminder_settings`/`app_settings` 键重复 | `DataExporter` |
| 13 | P3 | 提醒页"30 分钟"档位与"跟随全局(0)"哨兵互相吞掉：**本药固定 30 分钟不可表达** | `ReminderSettingsViewModel` |
| 14 | P3 | 提醒设置页保存时的暂停写回与自身注释矛盾（"本页只读"却写库） | `ReminderSettingsViewModel.save` |
| 15 | P3 | `DoseSlotDao.updateStatus` 与单条 `insert(REPLACE)` 是生产零调用的死代码，且**无状态守卫** | `DoseSlotDao` |
| 16 | P3 | 关机错过闹钟、开机后仍在 2 小时宽限内的槽位**不会补响**（设计取舍未文档化） | `AlarmReconciler` |
| 17 | P3 | 统计页不观察打卡事实 ⇒ 未追踪库存的药品打卡后统计页不刷新 | `StatsViewModel` |
| 18 | P3 | 服务层对负数/零剂量无防御，全靠 UI 输入过滤挡住（`logManualDose` / `refillStock` / `calibrateStock`） | `DoseTrackingService` |
| 19 | P3 | 若干 N+1 / 一次性查询不刷新的性能点 | `ProgressViewModel` / `CabinetViewModel` 等 |

---

## 一、P1 —— 会造成用户数据错误的问题

### 1. 同一计划内两个相同时点 ⇒ 槽位静默吞并（P1）

**证据链**（每一环都已逐行核实）：

1. 保存路径没有任何时点去重或查重：
   - `MedicationAdminService.kt:241-251`：`draft.times.sortedBy { it.time }.mapIndexed { ... }` 原样落库。
     对比同一函数里 `daysOfWeek = draft.daysOfWeek.distinct().sorted()`（`:225`）——**星期做了 distinct，时点没做**。
   - `ReminderSettingsViewModel.save()`（`:209-217`）与 `AddEditMedicationViewModel.save()`（`:286-298`）
     的前置校验只查"时点是否为空"，不查重复。UI 的时间选择器（`updateTimeSlot`）允许把任意一行改成与另一行相同的时刻。
2. `SlotProjectionEngine.projectSlots`（`:96-121`）对 `times` 逐条投影 ⇒ 两个 `PolicyTime` 同为 `"08:30"` 时，
   每个日期产出**两个 (date, "08:30") 相同、doseAmount 不同**的槽位。
3. `DoseTrackingService.reconcileSchedule` 的"插"分支（`:479-486`）把两条都交给 `slotDao.insertAll`。
4. `DoseSlotDao.insertAll` 是 `OnConflictStrategy.IGNORE`（`:31-32`），配合
   `(medication_id, scheduled_date, scheduled_time)` 的 UNIQUE 索引 ⇒ **第二条被静默丢弃，无任何日志与异常**。

**后果**：用户想表达"08:30 吃 A 药 1 片 + 08:30 加服 2 片"（两条时点），实际每天只排 1 条、
剂量固定为其中第一条的值。提醒只响一次、库存只扣一份，`policy_times` 里那行永远不物化。
`validateBackup` 对备份里的重复槽位键有专门拦截（`DUPLICATE_SLOT_KEY`），**生产写入路径却放行同类错误**——校验层和写入层对同一件事的判断再次相反（与上一轮 #6 缺陷同构）。

**修法方向**（二选一，都要在保存入口拦截而不是靠 IGNORE 兜底）：
- 保存时拒绝重复时点并给出明确报错（推荐：`saveReminderPolicy` 里 `require(times.distinctBy { it.time }.size == times.size)`）；
- 或语义化为"同刻合并剂量"，在投影前按 `timeOfDay` 聚合 `doseAmount` —— 但这改变了用户输入的语义，需要产品拍板。

**测试建议**：`PolicyDraft` 带两个相同时点调用 `saveReminderPolicy` ⇒ 断言抛出（或断言合并后剂量 = 之和）；
变异验证：把 require 删掉，测试必须变红。

---

## 二、P2 —— 特定条件下的错误行为或崩溃

### 2. 通知栏快捷操作协程没有 catch ⇒ 崩溃进程（P2）

`DoseActionReceiver.kt:35-97`：

```kotlin
CoroutineScope(Dispatchers.IO).launch {
    try {
        ... takeDose / snoozeDose / skipDose ...
    } finally {
        result.finish()
    }
}
```

**只有 `finally`，没有 `catch`**。同一文件族里的 `AlarmReceiver`（`:78-81`）与 `BootReceiver`（`:48-52`）
都写了 `catch (t: Throwable)`，唯独这个接收器没写。协程体内任何异常（DB 被占、`AppDatabase.getInstance`
失败、`takeDose` 抛错）都会成为未捕获异常走默认处理器 ⇒ **用户点一下通知栏按钮就能把整个 App 进程打崩**，
且 `result.finish()` 之后异常照抛，`goAsync` 的保护形同虚设。

**修法**：补 `catch (t: Throwable) { Log.e(...) }`，与 `AlarmReceiver` 同款。
**测试建议**：Robolectric 里让 DAO 抛错（传一个已关闭的 db），断言进程不崩、通知被取消。

### 3. 今日页事件反馈通道从未接线（P2）

`TodayViewModel` 专门建了 `_events` Channel 并在 KDoc 里写明
"原先 takeDose / skipDose 的返回值被直接丢弃，操作失败时**没有任何提示**"（`:241-250`），
`takeDose` / `skipDose` / `snoozeDose` / `undoDose` 共 6 处 `emitEvent(...)`。
**但全工程没有任何地方 collect `events`**（全源码 grep `\.events|receiveAsFlow` 仅命中定义处），
`TodayScreen` 没有 snackbar / toast / LaunchedEffect 接线。

后果：这条"修复"只完成了一半 —— 用户点 ✓ 后卡片不动、依然**既不知道成功也不知道失败**，
且当 #5（EXPIRED 按钮必失败）发生时，唯一的解释渠道也是哑的。

**修法**：`TodayScreen` 里 `LaunchedEffect(Unit) { viewModel.events.collect { snackbarHostState.showSnackbar(it) } }`。

### 4. 归档药品残留未来槽位 ⇒ 今日清单幽灵待办，且可打卡扣库存（P2）

- `MedicationDetailViewModel.toggleArchive`（`:172-180`）只做 `updateArchiveStatus` + `rescheduleAlarms`。
- `AlarmReconciler.rescheduleAll` 只会**撤掉**归档药的闹钟（`:149-160`，`medicationId in activeIds` 为 false），
  但它的第 2 步逐药 `reconcileSchedule` **只遍历 active 药**（`:135-143`）⇒ 归档药的
  PENDING / SNOOZED 槽位**没有任何代码路径会删**（对比：`deleteMedication` 有 FK 级联清空）。
- `TodayViewModel`（`:100-135`）：`medMap` 来自 `observeActiveOverviews()`（不含归档），
  槽位循环却不过滤 ⇒ 归档药的槽位以 `medication = null` 进入 `pendingItems`，
  `TodayScreen:474` 兜底渲染成"**未知药品**"（长按菜单 `:356` 兜底成"药品"）。
- `DoseTrackingService.takeDose`（`:98-138`）与 `skipDose` 都**不校验 `isArchived`** ⇒
  幽灵卡片上的确认按钮照常工作，照常扣库存。

**后果**：停药归档后最长 14 天内，今日清单每天挂着一条不知道是谁的待办，误触会继续污染台账与统计。

**修法**：归档时对该药调用 `reconcileSchedule(medicationId)`（投影判定 isActive=false ⇒ 空集 ⇒
既有"删"分支自动清掉窗口内开放槽位），或在 `TodayViewModel` 过滤掉 `med == null` 的槽位并查明归档来源。
**测试建议**：建药 ⇒ 推进到有未来槽位 ⇒ 归档 ⇒ 断言 `getOpenSlots()` 中该药槽位为 0。

### 5. EXPIRED 槽位的"确认服药"按钮永远失败，且文案撒谎（P2）

- `DoseSlotDao.markCompletedIfOpen` 的 SQL 守卫是 `status IN ('PENDING','SNOOZED')`（`:112-119`）——
  EXPIRED 不在其中；`markSkippedIfOpen` 却**允许** EXPIRED ⇒ SKIPPED（`:122-129`，注释还写着"补记跳过"）。
  也就是说产品语义是"逾期只能补记跳过，不能补记已服"。
- 但 `TodayScreen.PendingDoseCard` 把 EXPIRED 槽位照样渲染出可点的确认按钮（`:551-565`），
  徽标写着"已逾期…**尚未确认**"（`:538-545`）。
- 点击后 `takeDose` 返回 false，`TodayViewModel:173` 弹出的事件文案是
  **"该服药记录已处理过，未重复扣减库存"** —— 事实是"这条已逾期、从未被处理过"，文案把责任推给不存在的重复操作。
- 长按菜单的"推迟"同样必失败（`snoozeSlot` 守卫不含 EXPIRED），靠 #3 的哑通道"静默"失败。

**修法**：要么产品放开"补记已服"（`markCompletedIfOpen` 加 EXPIRED），要么 UI 对 EXPIRED 隐藏确认按钮、
只留"补记跳过"，并把 toast 文案改准确。当前组合是三种意图各取一半的混合体。

### 6. `setStockTracking` 的"从零建档"分支条件错误（P2，当前 UI 未触发的潜伏缺陷）

`DoseTrackingService.setStockTracking`（`:306-338`）：

```kotlin
if (target.milli > 0 && current <= 0) {
    appendLedger(changeAmount = target, ...)            // "从零建档"
} else if (target.milli != current) {
    appendLedger(changeAmount = Dose(target.milli - current), ...)  // 校准差额
}
```

第一分支的条件是 `current <= 0` 而不是 `current == 0`。台账余额可以是负数（D-9 明确支持，
且 `refillStock` / `calibrateStock` 不检查 `isStockTracked`，追踪关闭期间账面照样可能变负）。
**current = -2、initialStock = 30 时**：走第一分支追加 +30000，最终账面 = 28000 ≠ 用户声明的 30。

当前两条 UI 调用路径恰好都踩不中（`InventoryViewModel.setTracking` 传的是当前余额 ⇒ target == current；
`AddEditViewModel` 只在新药空账时调用 ⇒ current == 0），所以是**潜伏缺陷**——但它已经在另一处产生了实际症状：
`InventoryViewModel.setTracking`（`:164`）把**打开页面那一刻的快照余额**当 `initialStock` 传入，
若页面停留期间后台通知打卡扣了库存，重开追踪会走"校准"分支把**陈旧快照**写回账面，凭空造出一条差额流水。

**修法**：`setStockTracking` 的签名就别收"目标值"——要么只收 `enabled`（建档交给盘点校准），
要么第一分支条件改 `current == 0`、`InventoryViewModel` 停止传快照（传 null 走"沿用当前账面"）。
**测试建议**：`setStockTracking(id, true, 30f)` 在账面 -2 时断言最终余额 == 30；当前实现会得 28，测试变红。

### 7. 时区变更后 `scheduled_ts` 不重算（P2）

`reconcileSchedule` 的 diff 键是**日历** `(scheduled_date, scheduled_time)`（`:436-490`），
"留"分支只更新 `doseAmount` / `policyId`（`updateDerivedColumns`，`DoseSlotDao:64-71`），
**刻意不碰 `scheduled_ts`** —— `DoseSlotDstServiceTest` 明确把这个写成设计意图（"重复对账不会把时间戳改掉"）。
该意图在**同一时区**内是对的；但用户旅行换时区（`ACTION_TIMEZONE_CHANGED` 触发 `BootReceiver` → 重排）后：

- 重投影用**新**时区算 epoch，key 未变 ⇒ 全部命中"留"分支 ⇒ 槽位保留**旧**时区算出的 `scheduled_ts`；
- `AlarmReconciler` 第 4 步直接拿存储的 `scheduled_ts` 排闹钟（`:170,193-197`）。

**后果**：政策是"每天 08:00"，从北京飞纽约后最多 14 天内的闹钟仍按北京时间的 08:00 响（纽约 20:00）。
同一策略、同一墙钟字符串，因物化时机不同产生两种绝对时刻——自相矛盾。

**修法**：`updateDerivedColumns` 增加 `scheduled_ts` 一列（它同样是投影的纯函数输出，KDoc 里把它归为
"由其它命令管理"其实没有命令管它），或在时区变更广播时对开放槽位做一次"重算 ts 但保留 id"的定向更新。
注意保持 DstServiceTest 的原语义：**同时区**重复对账 ts 不变（重算结果相同，天然满足）。

### 8. 进程跨午夜存活 ⇒ "今日"凝固在昨天（P2）

- `ProgressViewModel.kt:74-75`：`private val today = LocalDate.now()`、`weekDates = remember7Days()`
  在 VM 构造时**一次性固定**，进 7 天矩阵与"今日时间线"的 `observeSlotsForDate(todayStr)` 观察的是**固定日期字符串**。
- `TodayViewModel.kt:74`：`_selectedDate = MutableStateFlow(LocalDate.now())` 同样固定；
  周条 ±3 天也由它派生。

用药 App 恰恰是**典型常驻后台跨夜使用**的场景：晚上挂后台、早上恢复，进展页矩阵整体后错一天、
今日页默认选中昨天且时间线 `flatMapLatest` 观察的还是昨天的日期——除非进程死亡重建，无自愈。
`StatsViewModel.buildState` 内部取 `LocalDate.now()`，每次重组合都会刷新，只有 Today/Progress 把日期钉死在了字段里。

**修法**：两页在 `RESUMED`（或对 `ACTION_DATE_CHANGED` / `TIME_TICK`）时检测日期翻转并重置
`_selectedDate` / 重算 `weekDates`；或把"今天"做成一个可刷新的 StateFlow 注入 combine。

---

## 三、P3 —— 设置不生效、校验缺口与一致性

### 9. `full_screen_alert` 开关无任何实现

`SettingsScreen:270` 有开关、`SettingsViewModel:88-93` 持久化、`ReminderSettings.resolve` 认真解析进
`Behavior.fullScreenAlert`（`ReminderSettings.kt:29,37,44`）——然后 `Notifications.showDoseNotification`
从头到尾**没有引用它**（全文只有 `snoozeMinutes` 与 `shouldSilence` 被消费）。
这正是 `ReminderSettings` 对象 KDoc 自我标榜要消灭的"设置写了没人读"那类缺陷，只是换了一个键。
要么实现全屏提醒（`USE_FULL_SCREEN_INTENT`），要么先把这个开关从设置页摘掉。

### 10. `sound_mode` 只影响设置页回显

`SettingsViewModel:61,84` 读写 `"sound_mode"`，`Notifications` 构建通知时不设置任何自定义声音/震动模式
（`setSound(null,null)` 只发生在夜间静音渠道上）。选"强提醒药铃"与"温和药铃"在通知层面零差异。

### 11. `KEY_LEAD_MINUTES` 死常量

`ReminderSettings.kt:20` 定义，全工程零写入零读取（全局 grep 确认）。要么删除，要么接上"全局默认提前提醒分钟数"（目前提前提醒只有药品级 `advance_minutes`）。

### 12. `validateBackup` 漏三类静默吞行检查

上一轮补齐了四张表的**主键重复**检查（`DUPLICATE_RECORD_ID` 等，`DataExporter.kt:486-489`），但同族检查仍有三处缺口：

| 缺口 | 回填策略 | 后果 |
| :--- | :--- | :--- |
| `doseSlots` **主键**重复（同一 id 出现两次、业务键不同） | `insertAll` IGNORE（`:592`） | 第二条被静默丢弃；现有检查只覆盖业务唯一键（`:463-471`），不覆盖 PK |
| `reminderSettings` 同一 `medicationId` 出现两行 | `insert` REPLACE（`:551`） | 后一行静默覆盖前一行 |
| `appSettings` 同一 `key` 出现两行 | `insertAll` REPLACE（`:637`） | 同上 |

三者的共同点与上一轮修复的四张表完全一致："手工编辑过的备份 + REPLACE/IGNORE ⇒ 恢复成功但少数据"。
修法就是给 `checkDuplicates` 再加三行调用（settings 按 medicationId、appSettings 按 key、slots 按 id）。

### 13. 提醒页"30 分钟"档位与"跟随全局(0)"哨兵互相吞掉

`ReminderSettingsViewModel`：加载时 `0 → 30`（`:122`），保存时 `30 → 0`（`:243`）。
组合效果：**"本药固定推迟 30 分钟"这个值不可表达**。全局默认被改成例如 20 分钟后，
某药页面显示 30、实际生效 20，显示与行为不符（用户没动过它也算不上"跟随"语义被尊重——显示值就是假的）。
修法要么给"跟随全局"一个显式档位（UI 上单独一项），要么把哨兵值改成 0/-1 并让 UI 如实显示"跟随全局"。

### 14. 提醒设置页保存时的暂停写回与自身注释矛盾

`ReminderSettingsViewModel.save()` 里 `:247` 注释写着"暂停归详情页的开关所有；提醒设置页只读展示，
**不在这里改**"，紧接着 `:248-253` 却在 `s.isPaused != wasPaused` 时调用 `setPausedUntil("")` / `resume(medId)`。
除注释撒谎外，`s.isPaused` 是进页快照：若页面停留期间暂停状态在别处被改（当前导航结构下可达性低），
保存频次会拿陈旧 UI 状态覆盖暂停状态。二选一：删掉写回（遵守注释），或删掉注释并把 `isPaused` 改成
实时读取后由显式开关动作触发。

### 15. `DoseSlotDao` 的两个死写入口

`updateStatus(slotId, status, actualTs)`（`:101-102`）与单条 `insert(slot)` REPLACE（`:19-20`）在生产代码零调用
（全源码 grep 确认，唯一插槽路径是 `insertAll` IGNORE）。`updateStatus` **没有状态守卫**，
与同文件四个条件更新命令的防御风格相悖——留着它就是给未来某次"顺手调用"预留了一条绕过幂等锚点的路。
建议删除（I11 的静态白名单校验也会因此更干净）。

### 16. 宽限期内关机错过的闹钟不会补响（建议文档化的取舍）

`AlarmReconciler` 第 3 步 `shouldKeep` 要求 `scheduledTs > now`（`:151-154`），第 4 步注册也要求 `mainAt > now`
（`:193`）。设备在触发时刻关机、开机时槽位仍 PENDING 且在 2 小时宽限内 ⇒ 闹钟被取消且不重排，
用户得不到那次提醒（槽位留在清单里等他手动处理）。作为设计取舍可以接受，但它是"提醒不漏"承诺的一个
未写进文档的边界，建议在 `AlarmReconciler` KDoc 里显式记下（或改为：宽限期内开机补响一次）。

### 17. 统计页对打卡事实不响应

`StatsViewModel` 的 combine 只依赖 `(period, overviews)`（`:86-95`），依从率用的
`getSlotStatusCounts` 是一次性 suspend 查询（`:110`）。未开启库存追踪的药品打卡/撤销不会引发
`observeActiveOverviews` 发射 ⇒ 统计页停留旧值，直到切周期或药品列表变化。与进展页（观察 Room Flow）不一致。
修法：把 `slotDao.observeSlotStatusCounts(...)` 变成 Flow 参与combine（与 Progress 同款）。

### 18. 服务层数值无防御

`logManualDose`（负数剂量会变成**加库存**：`-Dose.of(-5f)` = +5）、`refillStock`（负数入库即扣减）、
`calibrateStock` 的 `actualStock` 三者都不校验符号，只靠 UI 输入过滤（`filter { isDigit || '.' }`）挡住。
服务是未来任何入口（手表、快捷指令、恢复后的批处理）的公共下游，建议在领域层加
`require(amount > 0f)`（校准的差额语义除外）。

### 19. 性能点（不影响正确性）

- `TodayViewModel:118`：SKIPPED 槽位逐条 `getRecordBySlotId`（循环内查询）；
- `ProgressViewModel:129`：时间线对 COMPLETED 槽位逐条查询，且 SKIPPED 不取事实（与今日页口径不一）；
- `CabinetViewModel.buildItemUi`：每次 combine 发射对**每个**药品做两次 DAO 查询（`getActivePolicyForMedication` + `getTimesForPolicy`）；
- 上一轮已记录的 `getStaleOpenSlots` 缺复合索引（每 15 分钟全表扫）仍存在。

---

## 四、上一轮"尚未处理"清单核对（7/7 仍然存在）

| 上轮 # | 内容 | 本轮核对 |
| :--: | :--- | :--- |
| 1 | 覆盖式恢复前的孤儿闹钟撤不掉（快照在清库之后） | `SettingsViewModel.confirmRestore:240` 恢复后才 `rescheduleAll`，快照只含恢复后数据 ⇒ 成立 |
| 2 | `savePolicyWithTimes` 只 deactivate 不删旧 `policy_times` | `SchedulePolicyDao:54-61` 原样 ⇒ 成立（且本轮 #4 的归档问题会额外放大槽位残留） |
| 3 | `getStaleOpenSlots` 缺 `(status, scheduled_ts)` 复合索引 | `DoseSlotEntity` 索引列表无 ⇒ 成立 |
| 4 | `escapeCsv` 不处理 `\r`、不中和 Excel 公式前缀 | `DataExporter:247-250` 只有 `,` `"` `\n` ⇒ 成立 |
| 5 | `cycleOnDays=0` 时 CYCLE 静默退化 | `BackupFormat.kt:107` 默认 0 + `isScheduledOnDate` 的 `coerceAtLeast(1)` ⇒ 成立 |
| 6 | `precautions` 用 `\|\|\|` 分隔，含该串即被拆开 | `AppConverters.fromStringList` ⇒ 成立 |
| 7 | `StatsEngine.calculateStockRunwayBySchedule` KDoc 与实参矛盾；详情页 `CYCLE -> 5` 硬编码 | `StatsEngine:228-242`、`MedicationDetailViewModel:143` ⇒ 成立（库存页已按真实配置折算，两页口径不一致依旧） |

---

## 五、修复顺序建议与验收

建议按依赖关系分四步走，每步独立编译 + 测试通过（沿用 AGENTS §7 的分步提交纪律）：

1. **崩溃与静默失败**（#2、#3）：改动面最小、收益最直接。#3 顺手让 #5 的失败可见，为 #5 的产品决策铺路。
2. **数据正确性**（#1、#4、#6）：#1 需要先在 `saveReminderPolicy` 加校验（一条 require + 一条能失败的测试）；
   #4 复用现成的 `reconcileSchedule` 空集语义；#6 是一行条件修改 + 一条边界测试。
3. **时间类**（#7、#8、#16）：#7 动 `updateDerivedColumns` 前先补 schema 无关的行为测试
   （同区重对账 ts 不变 / 跨区重对账 ts 重算），`DoseSlotDstServiceTest` 现有断言必须保持绿。
4. **设置与校验补齐**（#9–#15、#17）：多为删代码或加三行校验，可合并处理。
   #9/#10/#11 需要产品拍板"实现还是摘除"，不建议直接按某一方猜测实现。

每项修复按 AGENTS §3 的要求先写**能失败**的测试并做变异验证。特别提醒两条容易写歪的测试：

- #1 的测试不要去数槽位条数（时钟敏感，AGENTS 已有教训），断言"重复时点被拒绝"或"合并后剂量之和"这类**不变量**；
- #7 的测试里"重算后 ts 相同"必须用**显式传入的 ZoneId** 构造两次投影，不要依赖 Robolectric 的默认时区，
  否则会重演"下午全绿早上全红"的时钟型脆弱测试。

---

## 附录：审查覆盖范围

| 层 | 文件 | 结论 |
| :--- | :--- | :--- |
| 领域 | `SlotProjectionEngine` / `StatsEngine` / `Dose` / `DoseTrackingService` / `MedicationAdminService` | 全读。I1–I4 / I6–I8 / I12 数学上成立；发现 #1/#6/#18 |
| 数据 | 全部 8 实体 / 7 DAO / `AppConverters` / `BackupFormat` / `DataExporter` | 全读。I9 / I11 的写路径隔离与追加-only 结构成立；发现 #12/#15 |
| 闹钟 | `AlarmScheduler` / `AlarmReconciler` / `AlarmReceiver` / `DoseActionReceiver` / `BootReceiver` / `ReconcileWorker` / `Notifications` / `ReminderSettings` | 全读。I10 / D1–D5 成立（同时区语义）；发现 #2/#7/#9/#10/#11/#16 |
| UI | 10 个 ViewModel + `AppNavigation` / `Screen` / `TodayScreen`（关键分支精读）/ `Quantity` | 发现 #3/#4/#5/#8/#13/#14/#17/#19 |
| 测试 | 34 个测试文件**仅列清单并与不变量表对号**；**未逐个审测试体、未运行、未做变异验证** —— 最初版本曾写"守点核对"，属表述过头，特此更正 | 本轮发现均无既有测试覆盖（就文件主题而言）；既有测试的真实守卫能力**未知** |
| debug | `DevDataReceiver` / `DevSampleDataSeeder` | 直插实体但补齐了 `ensureDefaults`，与 I9 无冲突 |
