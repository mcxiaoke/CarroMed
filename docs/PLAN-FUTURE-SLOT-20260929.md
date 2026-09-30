# 未来槽位可操作缺陷：分析与修复方案

- 日期：2026-09-29 22:15 (GMT+8)
- 状态：**已实施**（2026-09-30 10:18 完成；实施记录与对本文 4 处修正见文末「实施记录」）
- 触发场景：用户 2026-09-29 提出 —— 今日 tab 点日期条切到 9-30 或任意更远的未来，每条待服卡的 ✓ 竟然可以点、可以确认
- 产品口径（用户 2026-09-29 确认）：**过去与今天可操作（已有别的限制），未来不可操作**；判据是「槽位计划日 > 当前自然日 ⇒ 拒绝」
- 相关：`docs/PLAN-EXPIRE-WINDOW-20260929.md`（结算线改「当日结束」）、`docs/PLAN-RECORD-DETAIL-20260929.md`（记录详情页五形态）、`docs/CHANGES-20260929.md`、`docs/CHANGES-20260930.md`

---

## 0. 一句话

`scheduled_date` **从来没有参与过"用户能不能对这个槽位表态"的判据** ——
服务层的幂等守卫只看 `status`，UI 层的按钮可用性也只看 `status`。
于是未来的槽位被当成普通的"待服"，点一次 ✓ 就凭空生成一条"未来已服"事实、
扣一次库存，并**静默取消那天的闹钟**。

---

## 1. 实证

### 1.1 复现

模拟器时间 `2026-09-29 22:04`，App 停在 `2026年9月30日` 视图，4 张待服卡片 ✓ 全部可点
（`temp/probe_today.png`）。实点「9-30 环孢素 10:30」的 ✓（`temp/probe_after_tap.png`）：

| 证据 | 操作前 | 操作后 |
| :--- | :--- | :--- |
| 今日清单「待服药」 | (4) | (3) |
| 环孢素库存横幅 | 仅剩 6 片 | 仅剩 5 片 |
| `dose_slots` slot 41 | `PENDING`, `actual_taken_ts=NULL` | `COMPLETED`, `actual_taken_ts=1790690797411`（= **今天 22:06**） |
| `dose_records` | — | 新增 id=4 `COMPLETED`（`slot_id=41`） |
| `inventory_transactions` | — | 新增 id=10 `TAKEN_DEDUCT -1000` |
| `dumpsys alarm` 中 `origWhen=2026-09-30` | **16 条** | **15 条**（10:30 全部消失） |
| 记录详情页 | — | 「计划 10:30 · 实际 22:06」，仍提供「跳过(改判)/撤销」 |

一次点击同时造成三件事：

1. **凭空生成一条"未来已服"事实** —— `actual_ts` 是今天，`slot.scheduled_date` 是明天，
   两者互相矛盾（记录详情页把这种矛盾原样显示成「计划 10:30 · 实际 22:06」）；
2. **扣一次库存** —— 今天的实物没少，账面少了 1 片；
3. **静默取消明天的闹钟** —— `dumpsys alarm` 里 10:30 的记录整体消失，用户明天不会再收到提醒。

第 3 条最恶劣：属于 AGENTS.md §2 第 6 条那一类「给用户一个以为已经生效的开关 / 承诺」。
用户以为自己在提前处理，实际上**把明天的提醒弄丢了**，且毫无察觉。

### 1.2 不是脏数据偶发

`CLEAR` + `SEED` 重建演示数据后触发一轮对账，9-30 的 4 条 `PENDING` 槽位必然被重新投影出来
（slot 74 / 95 / 109 / 110），实测库里 `scheduled_date > today` 的开放槽位覆盖到 10-02 及以后
（`AlarmReconciler.HORIZON_DAYS = 14`）。**任何正常安装 100% 复现。**

### 1.3 现有库无遗留坏数据

```
scheduled_date > today AND status IN ('COMPLETED','SKIPPED','EXPIRED')  →  count = 0
```

（唯一一条是本次调查时手动点出来复现的，已撤销。）
**所以本轮不需要数据迁移**，但仍需预留修复路径 —— 见 §4.4。

---

## 2. 根因

同一个判据（`scheduled_date <= today`）在 5 个地方**同时缺席**，任何一处补上都能止血，
但只有 §4.1 那层能挡住所有入口。

### 2.1 日期窗口以「选中日」为中心，未来方向无上界

`TodayViewModel.kt:168`
```kotlin
val weekDates = (-3L..3L).map { selectedDate.plusDays(it) }
```

选中 30 号时整条窗口变成 `27..3`（截图可见，"今天"的小圆点已经跑到第 3 格）。
点最右一天 → 整条窗口再右移一天 → **可以无限往未来走**。

### 2.2 日期格无条件可点

`TodayScreen.kt:705` `.clickable { onSelectDate(date) }`。
`isToday`（`TodayScreen.kt:692`）只用来画底部 5dp 小圆点（`:732`），
**完全不参与可点性判断**，它是个纯装饰变量。

### 2.3 跨午夜"夹回今天"对本问题无效

`TodayViewModel.kt:91-100` 的 M3-2 逻辑只在 `CurrentDateHolder.today` 发射新值
（即真的跨过午夜）时才把选中的未来日期夹回今天。同一天之内它一次都不会触发，
所以对这个缺陷毫无作用 —— 它防的是"跨夜后停在昨天"，不是"选到未来"。

### 2.4 服务层根本没有日期判据（核心）

`DoseTrackingService.takeDose`（`:98-145`）全文只做两件事：

```kotlin
require(takenAmount == null || (takenAmount > 0f && takenAmount.isFinite()))   // 剂量为正
if (slotDao.markCompletedIfOpen(slotId, actualTs) == 0) return@withTransaction false
```

而 `markCompletedIfOpen`（`DoseSlotDao.kt:189-196`）的守卫是：

```sql
WHERE id = :slotId AND status IN ('PENDING', 'SNOOZED', 'EXPIRED')
```

**没有 `scheduled_date` 条件。** `skipDose`（`DoseTrackingService.kt:150-172`）与
`snoozeSlot`（`DoseSlotDao.kt:227-228`）是同一套写法，同样没有。

`takeDose` 拿到的 `slot` 里有 `scheduledDate`，但**从头到尾没有一处拿它和今天比过**。

### 2.5 放大伤害的一环：失败也撤闹钟

`DoseEntryActions.confirm`（`:43-55`）：

```kotlin
val ok = tracking.takeDose(...)
cancelAlarmsAndNotification(slotId)   // ⚠️ 无论 ok 与否都撤
return ok
```

这是"槽位已经不该再提醒了"的合理设计（防止确认过了还响）。
但一旦 §2.4 放开未来槽位，它就变成"点一下未来卡片 → 明天的闹钟被撤掉"。
**所以 §4.1 与 §4.2 必须同批改，只加守卫会造出更隐蔽的新缺陷。**

### 2.6 第二、第三条入口同样缺判据

| 位置 | 判据现状 |
| :--- | :--- |
| `DoseRecordDetailViewModel.canConfirm/canSkip/canSnooze`（`:128-154`） | 只读 `slotStatus` |
| `DoseActionReceiver.isStillOpen`（`:50-51`） | 只读 `status IN ('PENDING','SNOOZED')` |

第一条：从进展页 / 药箱历史点进未来槽位的详情页，照样能确认、跳过、推迟。
第二条：现在靠"通知只对当天闹钟弹出"侥幸不出事 —— 那是**约定**，不是保证。

### 2.7 结构性对照：同仓库里三种写法的质量差异

| 路径 | 服务层判据 | UI 层判据 | 结论 |
| :--- | :--- | :--- | :--- |
| `editDose` 改时间到未来 | ✅ `require(newActualTs <= now)`（`DoseTrackingService.kt:439`） | ✅ | 正面样本 |
| `logManualDose` 补录未来 | ❌ 无 | ✅ 仅 `ManualDoseViewModel.kt:145` | 违反 M2-2 纪律 |
| **`takeDose` 打卡未来** | ❌ 无 | ❌ 无 | **两头都空，漏到用户手上** |

`logManualDose` 那一行已经违反了 `DoseTrackingService.kt:521-526` 自己写下的注释
（"服务层是**所有**入口的公共下游 …… 只靠 UI 过滤挡等于'约定只有一种调用方'"）。
打卡路径两头都空，所以它第一个漏到了用户手上。

### 2.8 测试覆盖为零

`app/src/test/kotlin` 下**没有任何 `TodayViewModel` / `TodayScreen` 测试文件**。
日期选择逻辑、窗口中心、跨午夜夹回，全部无测试守护。
当前 446 项测试守着 12 条不变量，**没有一条是"未来不可操作"**。

### 2.9 顺手发现的文案缺陷

`TodayScreen.kt:313` 硬编码 `"今日已服 (${uiState.completedItems.size})"`。
翻到 9-30 视图时它仍然写"今日"（截图里 9-30 页面下方就是「今日已服」）。
与本缺陷同源 —— 页面文案假定"正在看的就是今天"，而代码并不这么保证。

---

## 3. 判据定义

> **槽位可表态 ⇔ `scheduled_date <= 当前自然日`**

| 槽位计划日 | 可打卡 / 跳过 / 推迟 | 依据 |
| :--- | :---: | :--- |
| 今天 | ✅ | 正常服药；`EXPIRED` 的今日补记同样允许（`markCompletedIfOpen` 守卫含 `EXPIRED`） |
| 过去 | ✅ | 「静默待办」补记，见 `AlarmReconciler` 类 KDoc：*槽位开放、无徽标、无闹钟，用户随时可从今日清单补记* |
| 未来 | ❌ | 服药是**已发生**的事实；同一句理由已经写在 `DoseTrackingService.kt:437-438`（`editDose` 的 `require`） |

**"未来"是唯一的禁区，过去的既有能力一条都不动。** 这样就不必碰
`markCompletedIfOpen` 守卫里的 `EXPIRED` 分支，也不用改
`docs/PLAN-EXPIRE-WINDOW-20260929.md` 确立的「当日结束」结算语义。

---

## 4. 方案

原则（AGENTS.md §2 第 7 条）：**校验判据要和兜底判据同源**。
服药是已发生的事实 ⇒ **服务层必须拒未来**（唯一不可省的一层）；
UI 负责让用户点不到；两层用**同一个判据函数**，不允许各写一份。

### 4.1 第 1 层：SQL 守卫下沉（必须）

判据下沉到 `dose_slots` 的写操作 WHERE，与既有 `status` 守卫**并列**：

```sql
-- DoseSlotDao.markCompletedIfOpen / markSkippedIfOpen
WHERE id = :slotId
  AND status IN ('PENDING', 'SNOOZED', 'EXPIRED')
  AND scheduled_date <= :todayStr        -- 新增

-- DoseSlotDao.snoozeSlot
WHERE id = :slotId
  AND status IN ('PENDING', 'SNOOZED')
  AND scheduled_date <= :todayStr        -- 新增
```

**为什么下沉到 SQL 而不是"先查后写"**（与本文件既有的 `markCompletedIfOpen` /
`markSkippedIfOpen` / `snoozeSlot` / `markExpired` / `revertToPending` 同一套纪律）：

1. 读与写合成一个原子操作，不存在"查完还没写"的竞态窗口；
2. **所有**调用方（今日清单 / 记录详情页 / 通知栏 Action / 将来的 Widget /
   手表 / 快捷指令 / 备份导入）自动受益，且**无法绕过**。

`todayStr` 由 `DoseTrackingService` 现算后传入，**不要读 `CurrentDateHolder`**：
它最多滞后 60 秒（`CurrentDateHolder.TICK_INTERVAL_MS`）且在单测里不 `install`。
方向性也安全 —— 服务层用新鲜的 `LocalDate.now()` 只可能比 UI 更严，
不会出现"UI 放行而服务层拒绝"的诡异不一致。

**`snoozeDose` 同样要拦**（`snoozeUntilTs = now + N 分钟`）：
对一条明天的槽位"推迟 30 分钟"，算出来的是**今天**的唤醒时刻 ——
等于凭空造出一个今天就响的"未来服药提醒"。

**必须同批改 `DoseEntryActions`**：`confirm` / `skip` 现在是"失败也撤闹钟"（§2.5）。
被未来判据拒绝时**绝不能**走到 `cancelAlarmsAndNotification`，否则只加守卫会引入
"点一下未来卡片 → 明天不响"的新缺陷。建议给 `DoseEntryActions` 一个可区分的结果
（见 §4.5 的 `SlotActionResult`），让调用方能说出**真话**而不是"该提醒已处理过"
（后者正是 `DoseSlotDao.kt:184-186` KDoc 里点名要避免的"按钮存在却永远失败、toast 只能撒谎"）。

### 4.2 第 2 层：UI 禁掉未来入口（必须）

1. **`TodayUiState` 增加 `isActionable`**：由 `selectedDate <= today` 得出，
   跟着 `CurrentDateHolder.today` 一起进 `combine`（该源已在 `:116` 参与）。
2. **`PendingDoseCard` 在未来日不渲染 ✓**（`TodayScreen.kt:471-485`），
   换成一条只读文案，如「明天 10:30 服用」/「9月30日 10:30 服用」。
   按本项目既有约定 **不置灰** —— 理由写在 `DoseRecordDetailViewModel.kt:75`：
   置灰会让人以为"再等等就能用"，而这件事永远不会变可用。
   卡片仍可点开进详情页预览。
3. **`DateSelectorRow` 锚定今天 + 未来置灰**：
   - `weekDates` 改为围绕 **今天** 的固定窗口（当前是以选中日为中心，导致窗口会跟着往前漂）；
   - 未来那天灰化（降低对比度 + 去掉可点性），"今天"小圆点保持在窗口内；
   - **未来日期仍可选**（预览未来排班这个功能本身是对的，用户要能看"明天要吃哪些"），
     只是不可操作 —— 选中后展示只读清单。
4. **`TodayViewModel.selectDate`（`:191-193`）加兜底** `require(date <= today)`，
   防止将来新增的入口绕过 UI（widget / 快捷指令）。

### 4.3 第 3 层：补齐另外两条入口 + 文案（建议同批）

| 位置 | 改动 |
| :--- | :--- |
| `DoseRecordDetailViewModel.canConfirm / canSkip / canSnooze`（`:128-154`） | 加同一个 `isActionable` 判据；未来槽位**不渲染**按钮 |
| `DoseRecordDetailScreen` | 顶部信息卡补上**计划日期**（现在只有「计划 10:30 · 实际 22:06」，从别处点进来分不清是哪天） |
| `DoseActionReceiver.isStillOpen`（`:50-51`） | 加同一个判据（当前靠约定不出事） |
| `TodayScreen.kt:313` | `"今日已服 (n)"` → 按选中日显示「9月30日已服」 |
| `TodayScreen.kt:235` | 「待服药 (n)」保持不变（措辞已与日期无关） |

### 4.4 明确**不拦**的路径

| 路径 | 处理 | 理由 |
| :--- | :--- | :--- |
| `undoDose` / `revertSlotInternal` / `revertToPending` | **不拦** | 撤销是**修复**通道。已存在的坏记录必须能清掉，否则用户被这个缺陷产生的垃圾数据永久锁死 |
| `markExpired`（结算） | 不拦 | 结算只处理**过去**的开放槽位（cutoff 是当地当日 0 点），与未来无交集 |
| `reconcileSchedule` 的投机区清理 | 不拦 | 已有逻辑 |
| 库存补录 / 盘点 / 校准 | 不拦 | 与槽位无关 |

**同时要修 `canUndo` 的一个盲区**：现有判据是 `isSameLocalDay(record.actualTs, now)`
（`DoseRecordDetailViewModel.kt:113-115`）。
一条在 9-29 误建的、`scheduled_date = 2026-09-30` 的坏记录，
到 9-30 就**不再可撤销**（`actual_ts` 停在 9-29）—— 用户被永久锁死。

建议补一个自相矛盾的判据：**槽位来源的记录，若 `record.actualTs` 的自然日
早于 `slot.scheduledDate`，则视为坏数据，允许撤销**。
这与"事实不能改到未来"（`DoseTrackingService.kt:437`）是同一条原则的两面 ——
事实既不许在未来，也不许早于它自称归属的计划日。

### 4.5 判据同源与坏数据

- **比较方式**：用**字符串比较** `scheduled_date <= :todayStr`，**不要用 `LocalDate.parse`**。
  `scheduled_date` 是 `DoseSlotEntity` 的非空 `String`，由 `SlotProjectionEngine` 按
  `DATE_FORMATTER`（`SlotProjectionEngine.kt:21`，`"yyyy-MM-dd"`）写入；
  零填充的 `yyyy-MM-dd` 字典序 == 时序。字符串比较**永不抛异常**，
  而 `LocalDate.parse` 会 —— 这正是 AGENTS.md §2 第 7 条那个坑
  （"列表里有一条 `time = ""` 时前者返回 false、后者失败，两边对'什么算坏数据'的理解不一致"）。
- **非规范 `scheduled_date`（如 `2026-9-30`）**：字典序下 `>` `2026-09-29` ⇒ **判为未来 ⇒ 拒绝**，
  即 **fail-closed**，方向安全；且 Kotlin 层与 SQL 层用**同一个**字符串比较，
  判定必然一致，不存在"一层放行一层拒绝"。
- **要落的单一判据函数**（供 UI 与服务层共用，避免各写一份）：

  ```kotlin
  // core/domain/engine/SlotActionPolicy.kt
  object SlotActionPolicy {
      /** 槽位是否允许被用户表态：过去与今天允许，未来拒绝。 */
      fun isActionableOn(scheduledDate: String, todayStr: String): Boolean =
          scheduledDate <= todayStr
  }
  ```
  SQL 守卫用同样的两个字符串参数，Kotlin 层调这个函数 —— 一处定义，两处使用。
- **服务层失败要能区分两种原因**：`false` 目前同时表示"槽位不存在/已被处理过"与
  "未来槽位"。建议 `takeDose` / `skipDose` 内部先判未来并返回一个
  `SlotActionResult`（`Applied` / `AlreadyHandled` / `FutureSlot`），
  `DoseEntryActions` 据此决定撤不撤闹钟、说什么话。

---

## 5. 影响面与回归风险

| 风险 | 说明 | 缓解 |
| :--- | :--- | :--- |
| 撤销路径被误伤 | 若把守卫也加到 `revertToPending`，用户清不掉本缺陷产生的坏数据 | 守卫**只加在三个"施加结论"的方法**上（§4.4） |
| 通知栏 toast 变成假话 | 未来槽位被守卫拒后，原路径会 toast「该提醒已处理过」 | 引入 `SlotActionResult`，给出「未来的服药时间不能提前确认」 |
| 窗口锚定改动影响历史浏览 | `weekDates` 改成锚定今天后，**翻到很久以前的日期时窗口不再跟着走**，用户看不到所选日期 | 窗口取 `min(选中日, 今天-3) .. max(今天+3, 选中日+3)` 之类的动态区间，或加左右翻页；**这一项单独走查** |
| 跨午夜 | `CurrentDateHolder` 最多滞后 60 秒 ⇒ 00:00 后 1 分钟内 UI 仍把"今天"当昨天 | 影响极小且方向安全（服务层用新鲜日期，只会提前放行） |
| `SlotSettlementGuardTest` / `DoseActionReceiverTest` 等既有测试 | 若 fixture 造的是"未来 PENDING 槽位再打卡"，会被新守卫拦下 | 改造 fixture 用**今天/过去**的槽位；这是**预期**的测试变更，不是回归 |

---

## 6. 测试计划

守的不变量：**未来槽位不能被施加任何新结论，且不产生任何副作用。**

### 6.1 服务层（`ServiceSignGuardTest` 同目录新建，或扩该文件）

结构照 `ServiceSignGuardTest.kt`（Robolectric + 真实内存 SQLite + `Truth`）：

| 用例 | 断言 |
| :--- | :--- |
| 未来 `PENDING` 槽位 `takeDose` | 返回"未来"；`dose_slots.status` 仍 `PENDING`；**`dose_records` 零新增**；**`inventory_transactions` 零新增** |
| 未来 `PENDING` 槽位 `skipDose` | 同上 |
| 未来 `PENDING` 槽位 `snoozeDose` | 返回 false；`status` 仍 `PENDING`；`snooze_until_ts` 仍 `NULL` |
| 今天 / 昨天的槽位 | 全部照常成功（**防"修过头"**） |
| 未来的**已 COMPLETED** 槽位可撤销 | `undoDose` 成功（修复通道未被误伤） |
| 非规范 `scheduled_date`（`2026-9-30`） | 与规范串判定一致（fail-closed），Kotlin 层与 SQL 层同结论 |
| 边界：`scheduled_date == today` | 允许 |

⚠️ **不能只断言返回值。** 本项目已有两例"测试全绿但实现是错的"，
其中 `DoseTrackingServiceTest` 的恒真断言就是这么来的。
"零新增行" 才是守得住的断言。

### 6.2 闹钟不被取消

用 Robolectric `ShadowAlarmManager`：
对未来槽位走完 `DoseEntryActions.confirm` 之后，**该槽位的闹钟仍存在**。
这条单独列出来 —— §4.1 漏掉"失败也撤闹钟"那半句时，它就是唯一能报警的测试。

### 6.3 UI 状态（`DoseRecordDetailStateTest` 同目录扩）

- `canConfirm / canSkip / canSnooze` 在未来槽位上全为 `false`
- 同一判据在今天/过去槽位上为 `true`
- `canUndo` 的坏数据盲区：`actualTs` 早于 `scheduledDate` 的槽位记录仍可撤销

### 6.4 变异验证（必做）

改坏实现，确认对应测试变红：

1. 去掉 `markCompletedIfOpen` 的 `scheduled_date <= :todayStr` → 6.1 第 1 条变红
2. 去掉 `DoseEntryActions.confirm` 的"未来不撤闹钟"分支 → 6.2 变红
3. 把守卫误加到 `revertToPending` → 6.1 第 5 条变红
4. 把守卫写成 `<` 而不是 `<=` → 6.1 边界用例变红

### 6.5 UI 走查

```powershell
python tools\app_screenshots.py --clear --seed --only today
```

并给 `tools/app_screenshots.py` 的 `PROGRAM` 补 Step：

| Step | 断言 |
| :--- | :--- |
| 切到未来日 | 页面标题 = 未来日期；**无 `确认服药` 节点**；待服卡渲染只读文案 |
| 切到今天 | 有 `确认服药` 节点（防"修过头"） |
| 点未来日的槽位进详情 | 页面显示**计划日期**；**无**「确认/推迟/跳过」按钮 |

必须**亲眼看图**（AGENTS.md §4）—— 只读文案与灰化日期格的区别，断言测不出来。

---

## 7. 验收清单

- [ ] `./gradlew clean assembleDebug testDebugUnitTest` 全绿
- [ ] `./gradlew compileReleaseKotlin` 通过
- [ ] 模拟器实测：切到 9-30，卡片**无 ✓**，进详情页**无**操作按钮
- [ ] 模拟器实测：切回 9-29，✓ 照常可用，打卡后**库存扣减 + 卡片移入已服**
- [ ] 模拟器实测：未来日点开槽位，`dumpsys alarm` 中**该槽位闹钟仍在**
- [ ] 未来日「待服药」卡片数正确（只是不可操作，不是被过滤掉）
- [ ] `docs/CHANGES-20260929.md` 顶部追加摘要
- [ ] 走查 `manifest.md` 无新增失败项，改动页面**亲眼看过图**
- [ ] 数据库守恒：过去/今天的打卡 `SUM(change_amount)` 不变（未来操作零写入）

---

## 8. 分步实施计划

每步独立可回滚，编译 + 测试通过才进下一步。

| 步 | 内容 | 门禁 |
| :--- | :--- | :--- |
| 1 | `SlotActionPolicy` + 三个 DAO 查询加 `scheduled_date <= :todayStr` + `DoseTrackingService` 传 `todayStr` + `SlotActionResult` | 6.1 全绿 + 变异验证 1/4 |
| 2 | `DoseEntryActions` 区分"未来"与"已处理"，未来不撤闹钟 | 6.2 变红→修复后绿 + 变异验证 2 |
| 3 | `TodayUiState.isActionable` + `PendingDoseCard` 未来不渲染 ✓ + `DateSelectorRow` 锚定今天 + `selectDate` 兜底 | 走查 today 页，看图 |
| 4 | `DoseRecordDetailViewModel` / `DoseRecordDetailScreen` / `DoseActionReceiver` 补齐 + `今日已服` 文案 | 6.3 绿 + 走查 detail 页，看图 |
| 5 | `canUndo` 坏数据盲区（§4.4） | 6.1 第 5 条 + 6.3 第 3 条 |
| 6 | `tools/app_screenshots.py` 补 Step + 全量走查 | `manifest.md` 无新增失败项 |

⚠️ **第 3 步的窗口锚定单独走查**：翻到很久以前的日期时，
`weekDates` 改成锚定今天后用户会看不到所选日期。这一项与本缺陷无关，
但由本缺陷引出，**不能顺手改坏而不验证**。

---

## 附：现状与目标对照

| 槽位计划日 | 现状 | 目标 |
| :--- | :--- | :--- |
| 未来（如 9-30） | 可 ✓ 打卡 / 可跳过 / 可推迟 / 详情页可操作；打卡后**闹钟被静默取消** | 只读展示，可预览排班，**零写操作** |
| 今天 | 可操作 | 不变 |
| 过去 | 可补记 | 不变 |
| 未来日期在选择器里 | 可点，且窗口会往前漂 | 可点（用于预览），灰化，窗口锚定今天 |

---

## 实施记录（2026-09-30 10:18 GMT+8）

分 6 步实施完成，`testDebugUnitTest` 478 → 502 项全绿，
`compileReleaseKotlin` / `verifyRoborazziDebug` / `connectedDebugAndroidTest` 均通过；
模拟器 39 张走查截图 `manifest.md` 无失败项。明细见 `docs/CHANGES-20260930.md`。

### 与本文方案的 4 处偏差（均为实现中发现的问题）

| # | 本文写法 | 实际做法 | 原因 |
| :--- | :--- | :--- | :--- |
| 1 | §4.2 第 4 条：`selectDate` 加 `require(date <= today)` | **不加** | 与同段第 3 条「未来日期仍可选（用于预览）」直接矛盾；`require` 在 UI 线程抛异常即崩溃。判据只加在"动作"上 |
| 2 | §5 用 `min(选中, 今天-3) .. max(今天+3, 选中+3)` 动态窗口兜底锚定 | **不做窗口锚定**（用户 2026-09-30 拍板） | 该区间会产出任意长度列表，而 `DateSelectorRow` 是固定 7 格布局；且窗口锚定与本缺陷正交，代价是"翻不到 3 天以前"的产品回归。保留滑动窗口，仅让未来日只读 |
| 3 | §4.1/§4.5：服务层"现算 `LocalDate.now()`" | **注入时钟** `DoseTrackingService(db, todayProvider)` | 库里已有把"今天"写死的 fixture（`DoseTrackingServiceTest` 锁 2026-10-01），读挂钟会让这些测试随真实日历变红、过几天又自己变绿（AGENTS.md §3 的"时间炸弹"）。实测确有 1 条因此变红 |
| 4 | §4.1：结果区分只提"给 `DoseEntryActions` 一个可区分的结果" | `SlotActionResult` 落在 **`DoseEntryActions`**（`DoseActionResult`），服务层仍返回 Boolean | 改服务层返回值会波及 40+ 处测试调用点；而"要不要撤闹钟"的分支本来就在编排层。追加：**`restateSlot` 必须前置未来判据**（本文 §4.5 未提）——否则"先作废、再施加"会因施加被守卫拒绝而 `return false` **提交**已完成的作废（`withTransaction` 只在抛异常时回滚），留下半截状态 |

### 实施中额外发现（已一并处理）

1. **Room 拒绝未使用的查询参数**（`Unused parameter: todayStr`）⇒ 守卫无法被悄悄删掉，变异必须显式保留参数使用。
2. **`DoseActionReceiver.isStillOpen`** 的日期判据此前只靠"通知只为当天闹钟弹出"这条约定撑着，已补上同一判据。
3. **走查脚本两个真 bug**：① `dumpsys window grep mCurrentFocus` 的 adb 写法被 `dumpsys` 当成窗口过滤条件（回 `Bad window command`），导致"App 是否在前台"恒为假、`collapse_shade_if_open` 的自愈分支是死代码；② 未来详情页在根路由上，其后用 `back` 会把 App 退到桌面，桌面上的 swipe 会一路拉下通知栏。
4. **单行长文案会折出孤儿行**：「明天 10:30 服用」在 44dp 位宽里折成两行且断在"服用"，走查看图后改为显式两行（「明天」/「10:30 服用」）。
5. **`canUndo` 的坏数据逃生口**（本文 §4.4 提出）落地时发现三处既有测试 fixture 是"计划日=今天、事实日=三天前"——那正是坏数据形态，已改成计划日与事实日对得上，否则"较早记录不可撤销"这条断言会在新规则下变绿/变红无常。

### 已知边界（未改，刻意保留）

- 跨午夜时 `TodayViewModel` 的 M3-2（`current > realToday` ⇒ 夹回今天）会把"未来选中日"拉回今天：开着 App 过夜会退出预览。影响极小、方向安全。
- 非规范 `scheduled_date`（如 `2026-9-30`）的**可达性**由入口校验负责；判据层不加格式校验，以保持 SQL 与 Kotlin 同源（排序在今天的坏串 ⇒ 拒绝；排在前面 ⇒ 按"过去"放行）。
