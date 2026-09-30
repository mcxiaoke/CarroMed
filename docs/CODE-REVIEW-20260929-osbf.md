# 全项目代码审查报告（osbf 轮，2026-09-29）

> **审查时间**：2026-09-29 19:20–21:05 (GMT+8)
> **审查基线**：`HEAD = 25aeab9 docs(review): round2 full-project code review (N1-N11)`，
> 工作区除一个未跟踪文档 `docs/PLAN-UI-TEST-20260929.md` 外**无源码改动**
> （`git status --short` 于 21:02 复核）。
> **审查方式**：逐文件精读 `core/domain` / `core/data` / `core/alarm` / `ui/**ViewModel` 全量源码
> （约 8 200 行），对每条结论回到当前代码逐行复核；对最高危项编写了一次性 Robolectric
> 测试**实测取证**（测完即删，工作区已复原）。
> **单测基线**：本轮实测 `./gradlew testDebugUnitTest --offline` ⇒ **455 项全绿（49 suites，0 失败 0 跳过）**。
> **本轮未修改任何源码，未运行模拟器，未跑 UI 走查。**

---

## 〇、一页结论

1. **一条 P0，且已在 Robolectric 上实测坐实**：宽限补响（M1-7 新增）与"响铃后续期"
   （`enqueueOneShot`）组合成**自维持循环** —— 只要某个槽位响了之后用户没在 2 小时内处理，
   提醒会**每约 31 秒重响一次**，最长持续 2 小时（约 230 轮唤醒 + 230 次全量对账）。
   这不是"多响几次"，是把"提醒"这一第一承诺变成了噪声源。
   详见 §一，含实测探针输出。
2. **四条 P1，全部是"用户看得见的错"**，且全部可在 3~5 步内复现：
   补录可写入 1970 年 → 该记录**永久不可改、不可撤销**；盘点校准**录不进 0**（"刚好用完"这个
   最常见场景被领域层允许、被 UI 禁止）；提醒设置页的"清除结束日"入口**无效**且界面自相矛盾；
   覆盖式恢复不撤托盘通知，通知栏按钮按 `slotId` 定位 ⇒ 可能给**别的药**记一次服药并扣错库存。
3. **五组 P2**：全量对账在 6 处跑在**主线程**；提醒页暂停开关会把"暂停至某日"悄悄升级成
   **无限期暂停**；二级页面返回不重载（round2 N1 仍开放）；备份不是一致性快照；
   跨 service 多步写无外层事务（round2 N5 仍开放）。
4. **本轮同时核销了一批旧结论**：`--only` 丢导航断言（N6）、`txType` 无默认值（N10）、
   `goAsync` 全量对账（N3）、`CarroMedApp` REPLACE 自饿死（N4）、
   `MIGRATION_1_2`（M5-9）在当前代码里**均已修复或不成立**，不再列入待办（§四）。
5. 上一轮报告的 12 条新发现中，**N1、N5、N7 仍开放**（已分别并入本报告 P2-3、P2-5、P3-9），
   其余 9 条已修或已失效。

---

## 一、P0：宽限补响 × 响铃后续期 = 每 31 秒一次的自维持通知风暴

### 1.1 位置

| 环节 | 位置 | 代码事实 |
| --- | --- | --- |
| 补响注册 | `core/alarm/AlarmReconciler.kt:287-308` | `if (mainAt > now) {排 MAIN} else if (mainAt >= graceFloor) {排 now+30s 的 MAIN}`，`graceFloor = now - EXPIRE_WINDOW_MS`（`:176`），`EXPIRE_WINDOW_MS = 2h`（`:62`） |
| 触发 | `core/alarm/AlarmReceiver.kt:86-92` | 弹出 `showDoseNotification(...)` |
| 续期 | `core/alarm/AlarmReceiver.kt:100` | `ReconcileWorker.enqueueOneShot(appContext)` —— **补响与准点响铃走的是同一条路径，不区分** |
| 再对账 | `core/alarm/ReconcileWorker.kt:65-80` | `doWork()` → `AlarmReconciler.rescheduleAll` |
| 再补响 | 回到第 1 行 | 槽位仍是 `PENDING`（**弹通知不改槽位状态**），`scheduled_ts` 仍落在 `(now-2h, now]` ⇒ 再次命中补响分支 |

### 1.2 机制

补响的触发时刻是 `now + 30s`（`GRACE_CATCHUP_DELAY_MS`，`:70`）—— 它是**相对量**，
每次对账都会算出一个新的值。`AlarmScheduler.schedule` 用 `FLAG_UPDATE_CURRENT`
（`AlarmScheduler.kt:170`），而 PendingIntent 身份是内容寻址的
`carromed://alarm/{medId}/{date}/{time}/{kind}`，**同一身份重复注册是"替换"**，
所以闹钟不会堆积 —— 但它会被**反复重新武装**到新的 30 秒后。

于是一个稳定的极限环：

```
补响闹钟响 → 弹通知（槽位仍 PENDING） → enqueueOneShot
   → 全量对账 → 该槽位仍落在 (now-2h, now] → 排 now+30s
   → 30 秒后再响 → ……  直到 now - scheduled_ts > 2h
```

代码里**没有任何一处**记录"这一槽位的宽限补响已经做过"：
`dose_slots` 没有这样的列，`takeDose` / `skipDose` 之外的路径不改状态，
`AlarmReconciler` 的补响分支也没有任何幂等锚点。

### 1.3 实测取证（本轮专为此写的一次性 Robolectric 测试，跑完已删除）

构造一条"`scheduled_ts` = 30 分钟前、状态仍 `PENDING`"的槽位（即"用户响铃后没点确认"这一
**最常见**的状态），连跑三次 `rescheduleAll`（第二次即模拟"补响响过之后 `AlarmReceiver`
enqueueOneShot 触发的对账"），打印槽位状态与系统里实际注册的闹钟：

```
PROBE first  status=PENDING ts=1790685041192
PROBE second status=PENDING ts=1790685041192
PROBE third  status=PENDING ts=1790685041192
PROBE alarmCount=16
PROBE alarm at=1790686871259 data=carromed://alarm/1/2026-09-28/03%3A17/main
PROBE alarm at=1790695800000 data=carromed://alarm/1/2026-09-29/23%3A30/main
   ...（其余 14 条均为 2026-09-30 ~ 10-13 的正常未来闹钟）
```

读法：

- 槽位 `ts = 1790685041192` = 30 分钟前；被补响的闹钟 `at = 1790686871259`
  = `ts + 1830067 ms` ≈ **对账时刻 + 30 秒**。即"每次对账都重新武装一次"。
- 三次对账后该闹钟**仍存在且触发时刻是最后一次对账 + 30s** ⇒ 第二轮、第三轮都会再响。
- 同一测试里，**所有正常的未来槽位（15 条）都不受影响**（`deltaMs` 全为正），
  说明问题**精确地落在"响了但未处理"这一个分支**上，不是我误读了正常路径。

### 1.4 后果

- **用户侧**：每 31 秒一次 heads-up 通知 + 声音 + 震动，持续到 2 小时窗口关闭
  （约 230 次）。`Notifications.setOnlyAlertOnce(false)`（`Notifications.kt:190`）
  保证每次都重新提示；通知 id 相同所以不会堆叠成 230 条，而是同一条反复抬头。
  这一条足以让用户卸载 App。
- **统计侧**：受影响期间该槽位一直 `PENDING`，`StatsEngine` 把 `PENDING/SNOOZED`
  算作 `pending` 且**不进依从率分母**（`StatsEngine.kt:61-66`）。用户被反复提醒却始终
  "没记上"，直到 2 小时后才被结算成 `EXPIRED`（漏服）。提醒越多，依从率越低。
- **电量侧**：230 轮 = 230 次精确闹钟唤醒 + 230 次 Room 打开 + 230 次 WorkManager
  入队与执行（每轮含一次全量对账，CHANGES 记录实测 0.365~0.504 s / 端到端 1.46 s / 67 闹钟）。
- **可达性**：不需要任何异常条件。**任何一个用户只要在 2 小时内没有确认某一剂药**（睡着、
  在开会、没带手机、忘了），就会完整经历这 230 轮。

### 1.5 为什么现有测试与走查都没抓到

- `AlarmPrecisionAndGraceTest`（`app/src/test/.../AlarmPrecisionAndGraceTest.kt:170-193`）
  只断言"宽限期内的槽位**被补响了一次**"—— 那是循环的**第一轮**，通过它是应该的。
  它没有第二、三轮的断言。
- 走查脚本 `--clear --seed` 的演示数据里，今天 08:00 / 09:00 两条槽位是**建库时直接
  `status = COMPLETED`**（`DevSampleDataSeeder.kt:225, 266`），永远不会走这条路。
- 走查在分钟级完成，30 秒一轮的循环在截图节奏里看不出来。

### 1.6 修法（必须引入"已补响"标记，纯函数化不足以终止循环）

**只把 `now + 30s` 改成 `scheduled_ts + 30s` 是不够的**：那样对账每 15 分钟会把
PendingIntent 重新注册到一个**已经过去**的时刻，而 `setExactAndAllowWhileIdle`
对过去的时刻是**立即投递** ⇒ 15 分钟一轮的"立即重投"，比现在慢一点但仍是循环。
所以需要持久化的幂等锚点。

**推荐方案（改 schema，1 列）**

1. `AppDatabase.version` **6 → 7**（AGENTS §2 红线，唯一必做项），并补
   `AppDatabaseRealTest` 的 `PRAGMA table_info` 断言：
   ```sql
   ALTER TABLE dose_slots ADD COLUMN catchup_done INTEGER NOT NULL DEFAULT 0;
   ```
2. `DoseSlotEntity` 加 `val catchupDone: Boolean = false`，`DoseSlotDao` 加**守卫型**命令：
   ```kotlin
   @Query("""UPDATE dose_slots SET catchup_done = 1
             WHERE id = :slotId AND status IN ('PENDING','SNOOZED') AND catchup_done = 0""")
   suspend fun markCatchupDone(slotId: Long): Int      // 0 = 已补响过，放弃
   ```
   与 `markCompletedIfOpen` / `markExpired` 同一套风格：**幂等锚点下沉到 SQL**，
   调用方不必也不能绕过（AGENTS §2 第 1 条）。
3. `AlarmReconciler.kt:291-308` 改为：进入宽限分支先 `if (db.doseSlotDao().markCatchupDone(slot.id) == 0) return@forEach`，
   再排 `now + GRACE_CATCHUP_DELAY_MS`（保留错开，避免锁屏上多条同时弹）。
4. **复位点必须逐个想清楚**（漏一个就是新缺陷）：
   - `revertToPending`（撤销）时复位 —— 用户说"我没吃"，槽位重新回到宽限期内，应当还有一次机会；
   - `snoozeSlot` **不复位**（推迟有自己的闹钟，补响不该再插一脚）；
   - 投影"留"分支的 `updateDerivedColumns` **不复位**（改个剂量不该重新惊动用户）；
   - 槽位被 `deleteByIds` 删除后重插 ⇒ 新行 `catchup_done` 自然为 0。
5. **顺带**：`AlarmReceiver` 在 `Kind.MAIN` 且 `slot.scheduledTs < now`（即补响场景）时
   **不再 `enqueueOneShot`**。补响不需要"触发后续期"——视野由 15 分钟周期 Worker 兜底即可。
   这一条把补响的开销从"全量对账"降到"一次通知"，也让 N3 的余量归零。

**验收（按 AGENTS §3 纪律，含变异验证）**

- 新增测试：造"响过 20 分钟仍 PENDING"的槽位，**连跑 5 次 `rescheduleAll`**，
  断言 `ShadowAlarmManager` 里该槽位身份的闹钟**至多 1 条**且 `triggerAtTime` 只在第一次变化。
  断言必须用 `PendingIntent` 集合（`PendingIntent.equals` 就是 Android 真实判重语义），
  **不能**写 `scheduledAlarms.size` 变大变小 —— 那是 AGENTS §3 点名的恒真断言。
- 变异验证：删掉 `markCatchupDone` 的 `AND catchup_done = 0` 守卫 ⇒ 测试必须变红。
- 撤销路径补一条：`takeDose → undoDose` 后该槽位在 2 小时宽限期内**应当**还能再补响一次。
- 模拟器实测：`adb shell dumpsys alarm | grep carromed` 观察 10 分钟内该槽位的唤醒次数
  为 1；`logcat -s AlarmReceiver` 里 `dose alarm fired` 在 10 分钟内出现 ≤ 2 次。

---

## 二、P1（四条）

### P1-1 补录的服药时间没有下界 ⇒ 一次误触产生**永久不可改、不可撤销**的记录

- **位置**：`ui/screen/manual/ManualDoseScreen.kt:263,275`（`DatePickerDialog` **未设 `minDate`**）、
  `ui/screen/manual/ManualDoseViewModel.kt:145-148`（只校验"不能是未来"）、
  `core/domain/service/DoseTrackingService.kt:513-555`（`logManualDose` 无下界校验）
- **证据**：
  - `ManualDoseScreen` 的两个 `DatePickerDialog` 都只设了日期回调，**没有 `datePicker.minDate`**
    （对比 `DoseRecordDetailScreen.kt:641` 设了 `maxDate = System.currentTimeMillis()`，
    `MedicationDetailScreen.kt:607` 设了 `minDate` —— 说明这个项目知道要设，只是这一处漏了）。
  - `ManualDoseViewModel.save()` 的唯一时间校验是
    `if (s.actualDateTime.isAfter(LocalDateTime.now().plusMinutes(1)))` ⇒ 过去任意早都放行。
  - `DoseRecordDetailViewModel.canUndo`（`:162-167`）判据是
    `isManual -> withinEditWindow`，而 `isWithinEditWindow`（`:42-47`）是
    `age in 0..EDITABLE_WINDOW_DAYS`（2 天）。`canEditDose` / `canEditTime` 同样绑定它。
- **复现**：手动补录 → 日期选择器一路往前拨到 1970-01-01 → 保存。
  该记录随后在进展流水的分页里永远排在最后、在"过去 1 年"统计之外，
  且**撤销按钮与剂量/时间编辑按钮全部不渲染**（`canUndo=false`、`canEditDose=false`）。
- **后果**：用户唯一的出路是删掉整个药品。服务端 (`logManualDose`) 与
  `DoseTrackingService.editDose`（只挡未来、不挡远古）都不拦 ⇒ 这不是 UI 层的孤例，
  未来任何入口（Widget / 手表 / 备份导入）都能造出同样的记录。
- **修法**（两条都要）：
  1. `ManualDoseScreen` 的 `DatePickerDialog` 补 `datePicker.minDate`：允许回溯 N 年
     （建议 5 年，或"首条记录时间"取更早者）。
  2. 领域层加下界 —— `logManualDose` 里 `require(actualTs in RETROSPECTIVE_FLOOR..now)`，
     其中 `RETROSPECTIVE_FLOOR` 取 `now - 5 年`，理由与"剂量必须 > 0"完全同源：
     **服务层是所有入口的公共下游，只靠 UI 过滤等于约定只有一种调用方**
     （与 `DoseTrackingService.kt:521-529` 现有的 M2-2 论证一致）。
  3. 顺带把"手动补录记录的撤销窗口"从 2 天放宽为"无下界"（时间可改、剂量可改，
     撤销**永远**可用）。`EDITABLE_WINDOW_DAYS` 的立论是"两三��前已进入历史统计"，
     但对一条**录错日期**的记录来说，"不能改"比"污染统计"严重得多。
- **验收**：补录一条 1970 年的记录必须被拒绝（表单给出台词）；域���层直调
  `logManualDose(actualTs = 0)` 必须抛 `IllegalArgumentException`；变异验证：删掉
  `require` 后测试变红。

### P1-2 盘点校准**录不进 0** —— 领域层允许、UI 禁止

- **位置**：`ui/screen/inventory/InventoryViewModel.kt:216-225` vs
  `core/domain/service/DoseTrackingService.kt:563-588`
- **证据**：
  - 领域层 KDoc 明确写着「实测库存可以是 0（用完了），但**不能是负数**」，
    `require(actualStock >= 0f && actualStock.isFinite())`（`:572`），
    且 `:576-577` 的 `delta` 计算对 0 完全成立（`target - current` = 负差额流水）。
  - UI 层用的是 `DecimalInput.parsePositive`（`> 0`，`DecimalInput.kt:63-66`），
    0 落在 `null` 分支，弹「请输入有效的实际库存数量（大于 0）」。
- **复现**：药盒里最后一粒已经吃掉，账面还显示 30 片 → 进库存管理 → 盘点校准 →
  输入 0 → **被拒绝**。用户无法把账面改成 0，只能先手打一个别的数字去"逼近"，
  或者干脆不盘点（然后账面永远与实物不符）。
- **这是本项目自己写下的一条纪律的违反**：`DecimalInput.parseNonNegative`（`:79-82`）
  的 KDoc 写着「用于那些'0 有明确业务含义'的字段」，而盘点校准恰恰就是这样一个字段
  —— 领域层已经明确表达了 `>= 0` 的语义，UI 层没跟上。
- **修法**：`InventoryViewModel.calibrate` 改用 `DecimalInput.parseNonNegative`，
  错误文案改为「请输入有效的实际库存数量（0 或正数）」。一行改动。
  顺带检查 `InventoryScreen` 的键盘与提示文案是否隐含「必须大于 0」。
- **验收**：`calibrate("0")` 成功并写入一条 `change = -余额` 的 `CALIBRATION_ADJUST`，
  账面变 0；负数仍被拒。

### P1-3 提醒设置页的"清除结束日"入口**无效**，且界面自相矛盾

- **位置**：`ui/screen/reminder/ReminderSettingsScreen.kt:313-321`、
  `ui/screen/reminder/ReminderSettingsViewModel.kt:224, 349-358, 379-380`、
  `core/domain/service/MedicationAdminService.kt:279-283`
- **证据链**：
  1. 页面在开关打开时渲染一个结束日期框，带一个清除按钮：
     `onClear = { viewModel.onEndDateChange(null) }`（`:319`）。
  2. `onEndDateChange` 只改 `endDate`，**不动 `hasEndDate`**（`VM:224`）。
  3. `save()` 于是得到 `hasEndDate = true` + `endDate = null`，
     传给服务层的是 `endDate = null, clearEndDate = !hasEndDate = false`（`VM:379-380`）。
  4. 服务层的三态判定：`clearEndDate` 优先，其次 `endDate.isNullOrBlank() -> previous?.endDate`
     （`MedicationAdminService.kt:279-283`）⇒ **沿用库里的旧结束日**。
  5. 界面上副标题写的是 `if (uiState.hasEndDate) "到该日期后自动停止提醒（抗生素疗程）" else "长期服用：无限期"`
     （`:306`）—— 开关开着、副标题说"到该日期停止"、日期框却是空的，三者互相矛盾。
- **复现**：给抗生素设「疗程至 2026-10-05」→ 保存 → 再进本页 → 点结束日期框旁的清除图标 →
  点保存 → **库里的 2026-10-05 原封不动**，重新进本页日期又出现了。
- **为什么这条特别值得记**：`clearEndDate` 这个标志位**就是为了解决"用户想清空但
  null 被当成没改"才引入的**（`MedicationAdminService.kt:86-109` 的 KDoc 把这条
  写得非常清楚，包括抗生素那个具体场景）。它只接了"关掉开关"这一条路径，
  **漏了"点清除图标"这一条** —— 于是同一份设计意图在两个入口上表现相反。
- **修法**：`onEndDateChange(null)` 应当**同时**置 `hasEndDate = false`（或在 `save()`
  里把 `clearEndDate = !s.hasEndDate || s.endDate == null`）。
  选后者更保守，因为它不会让"开关还亮着、日期框空着"这个自相矛盾的中间态存在更久。
  同时把 `:306` 的副标题判据从 `hasEndDate` 改成 `hasEndDate && endDate != null`。
- **验收**：设了结束日 → 点清除 → 保存 → 重新进入，日期框为空且开关为关；
  直接查库 `schedule_policies.end_date IS NULL`。
  再补一条**服务层**断言：`clearEndDate = true` 时 `endDate` 必为 null（现有实现已满足，
  但没有测试钉住它）。

### P1-4 覆盖式恢复不撤托盘通知；通知栏按钮按 `slotId` 定位 ⇒ 可能给**别的药**记服药并扣错库存

- **位置**：`core/data/DataExporter.kt:1023, 1041-1054`（`cancelAllAlarmsBeforeRestore`）、
  `core/alarm/Notifications.kt:207-209, 212-214`、`core/alarm/DoseActionReceiver.kt:35, 44-51`
- **证据链**：
  1. 恢复路径**只撤闹钟**（`cancelAllAlarmsBeforeRestore` 遍历 `getOpenSlots()` 调
     `AlarmScheduler.cancelAll`），**没有撤任何托盘通知**。
  2. 通知的身份是**裸 `slotId`**：`notify(slot.id.toInt(), notification)`（`Notifications.kt:208`）、
     `cancel(slotId.toInt())`（`:213`）。
  3. `DoseActionReceiver` 同样按 extras 里的 `slotId` 反查槽位
     （`val slotId = intent.getLongExtra(Notifications.EXTRA_SLOT_ID, -1L)`，`:35`；
     `getSlotById(slotId)`，`:44`），并直接对它 `takeDose` ⇒ 扣该槽位所属药品的库存。
  4. 恢复**会用备份里的 id 覆盖当前库**（`restoreBackup` 显式写 `id = s.id`，
     `DataExporter.kt:753-767`）。恢复一份**不同**的备份 ⇒ 同一个 `slotId`
     在新库里指向**另一味药**的槽位。
  5. 恢复后 `SettingsViewModel.confirmRestore` 调 `AlarmReconciler.rescheduleAll`
     （`SettingsViewModel.kt:232`），它只在**快照条目不该保留时**才撤通知
     （`AlarmReconciler.kt:241-248`）。恢复后的库里若存在一个 `shouldKeep` 的同 id 槽位，
     那条**属于旧库**的通知就不会被撤。
- **复现**：
  1. 药 A 08:00 的提醒弹出，**不要点任何按钮**，让它留在托盘。
  2. 从一份"药 A 不存在、药 B 的槽位 id 与 A 相同"的旧备份恢复。
  3. 点托盘上那条仍写着「该吃药了：药 A」的旧通知的「✅ 确认已吃」。
  4. 药 B 被记成已服，**药 B 的库存被扣减**，药 A 什么也没发生。
- **为什么这不只是"理论风险"**：项目已经把**闹钟**从"算术编码"改成"内容寻址"
  （`AlarmScheduler.alarmUri`，M5-1），并在 KDoc 里明确写过旧 id 空间交叠会导致
  "可能给错药发提醒"。**通知这条通道至今仍是按 `slotId` 寻址的** ——
  同一个根因只修了一半，而剩下的一半后果更重（闹钟只是响错，通知按钮会**改数据**）。
- **修法**（两条一起做，缺一不可）：
  1. **恢复前全量撤通知**。`cancelAllAlarmsBeforeRestore` 里同时对每个开放槽位调
     `Notifications.cancelDoseNotification(app, slot.id)`。更彻底的做法是**直接
     `NotificationManagerCompat.from(app).cancelAll()`** —— 恢复就是"整个库换掉"，
     托盘里任何一条提醒都必然过期，没有理由保留。
  2. **把通知 Action 也改成内容寻址**。给 `DoseActionReceiver` 的 Intent 也带上
     `setData(carromed://action/{medId}/{date}/{time}/{kind}/{action})`，
     用 `(medId, date, time)` + `findOpenSlotId` 反查（与 `AlarmReceiver` 同一套，
     `DoseSlotDao.findOpenSlotId` 已经现成）。这是根因修复，第 1 条只是止血。
  3. 顺带：`DoseActionReceiver` 应对"查到的槽位与 extras 里的 slotId 不一致"记一条
     `Log.w` —— 让这类错配在 logcat 里可见，而不是静默生效。
- **验收**：第 3 步复现路径必须**点不动**（托盘已空）。补一条 Robolectric 测试：
  恢复后 `NotificationManagerCompat` 活跃通知数为 0。

---

## 三、P2（五组）

### P2-1 全量对账在 **6 处**跑在主线程

- **证据**：`AlarmReconciler.rescheduleAll` 本身 `suspend`，其中的 Room 调用会被 Room
  派发到自己的 executor，但**外层循环体与 `AlarmScheduler.schedule` / `cancelAll`
  的 binder 调用在调用方的协程上下文中执行**。逐个调用点看：

  | 调用点 | 上下文 | 线程 |
  | --- | --- | --- |
  | `MainActivity.kt:42` | `launch(Dispatchers.IO)` | ✅ |
  | `ReconcileWorker.kt:69` | `CoroutineWorker` 默认 `Dispatchers.Default` | ✅ |
  | `BootReceiver` | 不再内联对账 | ✅ |
  | `DoseEntryActions.kt:90`（撤销） | `DoseRecordDetailViewModel.run()` → `viewModelScope` | ❌ **Main** |
  | `InventoryViewModel.kt:198`（开关追踪） | `viewModelScope` | ❌ **Main** |
  | `ReminderSettingsViewModel.kt:414`（保存提醒） | `viewModelScope` | ❌ **Main** |
  | `AddEditMedicationViewModel.kt:502`（保存药品） | `viewModelScope` | ❌ **Main** |
  | `MedicationDetailViewModel.kt:211`（暂停/恢复/归档/删除） | `viewModelScope` | ❌ **Main** |
  | `SettingsViewModel.kt:232`（恢复成功后重排） | `viewModelScope` | ❌ **Main** |

- **后果**：CHANGES 记录端到端 1.46 s / 67 个闹钟。每一次"撤销打卡""保存提醒设置"
  "开启库存追踪""暂停提醒"都会在主线程上跑一遍 67 次 binder 调用 + 一轮 SQL ——
  表现为点击后界面冻结 1 秒以上，且这 5 个动作全都在用户主动操作的关键路径上。
  讽刺的是 N3 刚刚把这条路径从两个 Receiver 里搬走，**却在 6 个 ViewModel 里留下了**。
- **修法**：`DoseEntryActions`（编排层，最合适的位置）内部一律
  `withContext(Dispatchers.IO) { AlarmReconciler.rescheduleAll(context, db) }`，
  一次改动覆盖 6 个调用点。`DoseEntryActions` 本来就是"数据 → 闹钟 → 通知"的
  编排层，让它自己负责线程是正确的职责划分。
  更好的做法是让 `AlarmReconciler.rescheduleAll` **自己在最外层**包
  `withContext(Dispatchers.IO)`，并加一条文档约定："本函数是 IO 密集的，
  调用方不必也不应自行切线程"——但这只能保护协程路径，保护不了
  `MainActivity` 已在 IO 上（重复切线程无害）。
- **验收**：在 `rescheduleAll` 入口加 `assert(!Looper.myLooper().isMainThread)`
  （仅 debug），跑一次走查点 5 个动作不崩；`Choreographer` 掉帧数下降。

### P2-2 提醒设置页的暂停开关会把"暂停至某日"悄悄升级成**无限期暂停**

- **位置**：`ui/screen/reminder/ReminderSettingsScreen.kt:172-181`、
  `ReminderSettingsViewModel.kt:308, 406-411`、`core/domain/service/MedicationAdminService.kt:195-200`
- **证据**：`isPaused` 是从 `rs.isPausedOn(today)` 派生的**布尔**（`VM:171`），
  页面**不显示恢复日期**（副标题只说"当前已暂停：不再产生新提醒与闹钟"）。
  保存时（`VM:406-411`）只要 `s.isPaused != wasPaused` 且为 true，就
  `adminService.setPausedUntil(medId, "")` —— `""` 的既定含义是**无限期**
  （`ReminderSettingsEntity` 的三态约定）。
- **复现**：详情页 →「暂停提醒」→「暂停到一周后」（`paused_until = 今天+6`）→
  两天后进「提醒设置」页 → 开关显示为开 → **关掉再打开**（一个非常自然的两下操作，
  用户想"确认一下这个开关是可控的"）→ 保存 → `paused_until` 变成 `""`
  ⇒ **这味药从此永久不再提醒**，而界面上没有任何地方写着"无限期"。
  唯一能救的入口在详情页，用户不会想到去那里。
- **性质**：AGENTS §2 第 6 条说的正是这一类 —— **给用户一个以为已经生效的开关**。
  这里给的是一个"暂停到某日"却被静默改写成"永久"的开关。
- **修法**（择一，推荐第一条）：
  1. 提醒设置页**不提供暂停开关**。暂停归详情页所有（`MedicationDetailViewModel`
     的 4 个选项 + 恢复按钮），这一页只读展示"当前已暂停至 X 月 X 日"。
     页面所有权单一化是本项目已经确立的纪律（`ReminderSettingsDao` 的 KDoc
     「屏幕拥有几列，就只给它几列的写命令」），这一页是第二个拥有者。
  2. 若要保留开关：状态从布尔升级为三态（不暂停 / 暂停至某日 / 无限期），
     并在切换到 true 时**回显 `pausedUntil` 的真实值**而不是 `""`。
- **验收**：设"暂停至 2026-10-05" → 进提醒页 → 开关 off→on → 保存 →
  查库 `paused_until` 仍为 `2026-10-05`。

### P2-3 二级页面返回不重载（round2 N1，本轮复核**仍开放**，并补充了新证据）

- **证据**：`init { loadData() }` 之后，`loadData()` / `load()` 的其余调用点全部是
  "本页自己写完之后"（`MedicationDetailViewModel.kt:176,184,193,203`；
  `InventoryViewModel.kt:210,241,291`）。全 `ui/` 目录 grep
  `Lifecycle|ON_RESUME|LifecycleEventObserver|DisposableEffect` 在这四个页面里
  **零命中**（唯一命中是 `PermissionCheckScreen.kt:88-96`，它做对了）。
  路由链 `med_detail → med_inventory → refill` 返回时 `NavBackStackEntry` 不销毁。
- **本轮补充的证据**：`MedHistoryViewModel.load` 更彻底 —— 它带
  `if (loadedMedId == medId) return` 的一次性守卫（`:76-77`），
  即**同一个 ViewModel 生命周期内第二次进入永远返回旧数据**，
  连"自己写完再 load"的自愈路径都没有。
- **具体可复现的错误**：库存页账面 30 → 进补药页入库 30 → 返回库存页，
  账面仍显示 30（真实值 60），流水列表也没有新记录。用户在"账面 = 实物"的前提下
  做的下一个判断全是错的。
- **修法**：`MedicationDetailViewModel` / `InventoryViewModel` / `RefillViewModel` /
  `MedHistoryViewModel` 改用 `androidx.lifecycle.compose.LocalLifecycleOwner` +
  `DisposableEffect` 监听 `ON_RESUME` 后调 `load()`，**照抄 `PermissionCheckScreen`
  已经写好的那 8 行**。或者更省事：把一次性读取换成 `Flow`（这几页的数据全都有
  `observe*` 版本，参考 `TodayViewModel` / `CabinetViewModel` 的写法），
  由 Room 的 InvalidationTracker 自动跟随写入 —— 这样连"写完自己 load"都不用写。
- **验收**：走查脚本里加一条断言：库存页入库返回后账面文本 = 60。

### P2-4 备份不是一致性快照，文件内可能自相矛盾

- **位置**：`core/data/DataExporter.kt:352-467`（`buildBackup`）、`:808-811`、`:821-828`
- **证据**：`buildBackup` 顺序读 **8 张表**（`getAllBalances` → `getAllMedications`
  → `reminderSettings` → `getAllPolicies` → `getAllTimes` → `getAllSlots` →
  `getAllRecords` → `getAllTransactions`），**全文件唯一的 `withTransaction` 在
  `:654` 的恢复路径**。也就是说，导出期间任何一次打卡/补录/入库都会让这份备份
  跨表不一致。
- **具体后果**：`medications[].stockMilli` 是**明确写了"仅供人工核对"**的字段
  （`BackupFormat.kt:70-76`），而它在**读流水之前**就被采样（`:355`）。
  用户打开备份文件核对时，会看到"账面 29 片"与文件里那条 `-1000` 的流水
  **对不上**——而这个字段存在的**唯一目的**就是让人能核对。核对功能在并发下必然
  给出错误答案。
  更隐蔽的一处：`inventory_transactions.balance_after` 是逐条追加时写死的快照。
  读到的两批流水若来自两个不同的时刻，最后一条的 `balance_after` 就可能
  ≠ `SUM(change_amount)`，恢复后库存页的"结余"列会显示错值
  （权威值 `SUM` 仍然正确，所以不影响账实，只是流水表的展示列说谎）。
- **修法**：`buildBackup` 整体包 `db.withTransaction { }`（Room 的读事务在
  WAL 下会给一个一致快照，成本是一次 `BEGIN DEFERRED`）。
  顺带在 `exportFullBackupJson` / `writeSafetySnapshot` 里也加一道同样的包裹。
  这是一处 3 行的改动，把"导出期间可能自相矛盾"变成"不可能"。
- **验收**：在 `buildBackup` 读表的过程中从另一个协程插入一条流水，
  断言 `stockMilli` 与文件内流水的 `SUM(change_amount)` 一致。

### P2-5 跨 service 的多步写没有外层事务（round2 N5，本轮复核**仍开放**）

- **位置**：`ui/screen/reminder/ReminderSettingsViewModel.kt:370-414`、
  `ui/screen/edit/AddEditMedicationViewModel.kt:450-503`、
  `ui/screen/refill/RefillViewModel.kt:102-129`
- **证据**：提醒页保存链是 4 步，每步各自 `withTransaction`：
  `saveReminderPolicy`（`:370`）→ `saveReminderBehavior`（`:394`）→
  暂停处理（`:408-411`）→ `reconcileSchedule`（`:413`）→ `rescheduleAll`（`:414`）。
  `runCatching`（`:366`）保证**失败会告诉用户**，但**已经写进去的那几步不会回滚**。
- **具体后果**：第 2 步 `check(...)` 失败（`reminder_settings` 无该 medId 的行，
  `MedicationAdminService.kt:180-185`）⇒ 用户看到「保存失败：…」，但
  **新计划已经写进库了**，提醒页的表单却还停在旧值。刷新一下页面，新计划生效 ——
  而用户刚刚被告知"保存失败"。补药页的 `refillStock` + `setStockTracking`
  两步（`RefillViewModel.kt:102-127`）同理：入库成功、开启追踪失败 ⇒
  用户以为没入库，实际账面多了 30 片（后一步是 `runCatching` 吞掉的，
  连提示都没有）。
- **修法**（推荐第一条）：
  1. 把编排层整体包进一个外层事务：
     `db.withTransaction { adminService.saveReminderPolicy(...); adminService.saveReminderBehavior(...); ... }`
     —— Room 的 `withTransaction` 支持嵌套（内层复用外层），`AlarmReconciler`
     那个大循环**留在事务外**（它不是数据写入）。
  2. 至少给 `RefillViewModel` 的第二步补日志与提示：现在 `runCatching { setStockTracking(...) }`
     完全没有 `onFailure`，失败是彻底静默的。
- **验收**：注入一个"第 2 步必失败"的 DAO 替身，断言第 1 步的写入也**没有**发生。

---

## 四、本轮核销的旧结论（不再列入待办）

以下 round2 / FIX-PLAN 条目，回到当前代码逐条复核后确认**已修复或不成立**：

| 旧编号 | 原结论 | 当前代码事实 |
| --- | --- | --- |
| N3 | `goAsync` 窗口内跑全量对账，ANR 风险 | **已修**：`AlarmReceiver.kt:100` / `BootReceiver.kt:53` 只做 `enqueueOneShot`，对账本体在 `ReconcileWorker` 里（见 P2-1：代价是转移到了 6 个 ViewModel） |
| N4 | `CarroMedApp` 用 `REPLACE` 自己饿死自己 | **已修**：`CarroMedApp.kt:56` `enqueue(this, replace = false)`，KDoc 已记 N4 |
| N6 | 走查脚本 `--only` 丢全部导航与断言 | **已修**：`tools/app_screenshots.py:494-522` 改为按 shot 的**下标区间**保留导航步骤 |
| N7 | 非恢复路径先删行后拍快照 → 幽灵闹钟 | **仍开放**，但后果有界（`AlarmReceiver` 找不到槽位即静默返回、槽位 id 不复用 ⇒ 不会错药）。已降级并入 P3-9，见该条对三个调用点的逐一核对 |
| N10 | `InventoryTransactionBackup.txType` 无默认值 | **已修**：`BackupFormat.kt:188` 已给默认值，`BackupResilienceTest` 钉住 |
| M5-7 | `precautions` 用 `\|\|\|` 分隔会被用户内容破坏 | **已修**：`AppConverters.kt:74-88` 改用 `JSONArray`，读侧保留旧格式兼容 |
| M5-8 | 旧备份 `cycleOnDays=0` 让「吃 21 停 7」变成每天吃 | **已修**：`DataExporter.kt:722-731` 有 CYCLE 专用回退 |
| M8-6 | 演示数据的 COMPLETED 槽位无对应流水 | **已修**：`DevSampleDataSeeder.kt:245-253, 280-288` 已补 `TAKEN_DEDUCT` |
| N2 | 疗程结束日早于起始日可保存 | **已修**：`ReminderSettingsViewModel.kt:349-358` 已加校验（但同族缺陷 P1-3 仍在） |
| §四 分歧 | `MIGRATION_1_2` 会不会执行 | **不再存在**：`AppDatabase.kt:90-113` 已删除该迁移，分歧自动消解 |

**但 N1（详情/库存页不重载）与 N5（多步写无外层事务）复核后仍开放**，
已分别并入本报告 P2-3、P2-5，并补充了本轮的新证据。

---

## 五、P3（低危 / 死代码 / 性能）

| 编号 | 内容 | 位置与证据 | 建议 |
| --- | --- | --- | --- |
| P3-1 | **19 个零生产调用的 DAO 方法 + 1 个整行覆盖命令**。`getPendingSlotsAfter`、`getPendingSlotsForMedicationAfter`、`getLatestTransaction`、`observeTransactionsForMedication`、`getSchedulableOn`、`getMedicationWithReminder`、`getActiveWithReminder`、`getAllForReconcile`、`observeRecordsInRange`、`getRecordsInRange`、`getActiveMedications`、`getSlotsForDate`、`getAllPoliciesForMedication`、`deleteForMedication`、`countCompletedSlotsForDate`、`countTotalSlotsForDate`、`getAllOverviews`、`observeArchivedOverviews`、`MedicationDao.update` | 逐个 `rg -c`（排除 test/debug 源集）全部返回空。`ReminderSettingsDao.getSchedulableOn` 的 KDoc 甚至自称"唯一判据"，而 `AlarmReconciler` 实际走的是 `MedicationDao.getActiveOverviews()` —— **文档与实现说的不是同一件事** | 删。`MedicationDao.update` 是整行覆盖命令，留着就是 P0-5「漏传型」的入口 |
| P3-2 | `PolicyDraft.medId` 是死字段：声明了 0 处读写 | `MedicationAdminService.kt:77`；`saveReminderPolicy(medicationId, draft)` 用的是**参数**，草稿里的 `medId` 无人读 | 删字段（两个构造点都靠命名参数传 medicationId） |
| P3-3 | 新建药品页的提醒校验与起始日推算已成死代码 | `AddEditMedicationViewModel.kt:366-388` 仍按 `policyRequired` 校验时点/剂量/重复时点，但 `:468-485` 已明确"新建时不再写计划"；`:443-447` 算出的 `effectiveStartDate` **算完就没人用** | 删 `effectiveStartDate` 与整段提醒校验（含 `DOW_ERROR` / `TIME_ERROR` / `DUPLICATE_TIME_ERROR` 分支），KDoc 里那句"将来若恢复新建页可配计划"保留为注释 |
| P3-4 | `inventory_transactions.record_id` 无索引，而撤销/改剂量都按它扫 | `InventoryTransactionEntity` 索引只有 `medication_id` 与 `created_at`；`getSumOfChangeByRecordId`（`InventoryTransactionDao.kt:76-77`）每次撤销/改剂量至少 1 次全表扫 | 加 `Index(value = ["record_id"])`。**注意：改 `@Entity` 必须 `AppDatabase.version` 6→7** |
| P3-5 | 备份不校验「同一 `policyId` 下 `timeOfDay` 重复」 | `validateBackup`（`DataExporter.kt:494-646`）只查主键重复。恢复后 `reconcileSchedule` 的 `insertAll(IGNORE)` 会静默吞掉一条槽位；而用户随后在提醒页点保存时，`saveReminderPolicy` 的 `require(timeKeys.distinct())` 会**拒绝**这份配置 ⇒ 用户被一份"自己 App 导出的备份"锁死在无法保存 | `validateBackup` 增加一类 `DUPLICATE_POLICY_TIME`（`blocksRestore = true`） |
| P3-6 | JSON 备份读取不剥离 BOM | `DataExporter.kt:870-877` 的两个 `readText` 都不剥 BOM，而 `BOM` 常量（`:188`）只用在 CSV（`:230, 266`）。外部编辑器给 JSON 加 BOM 后，`decodeBackup` 抛异常 ⇒ 用户看到"不是有效的 CarroMed 备份文件" | 读取时 `text.removePrefix("\uFEFF")`；`BOM` 常量写成 `"\uFEFF"` 而不是不可见字面量 |
| P3-7 | `reconcileSchedule` 每味药都把**整个窗口内全部药品**的槽位拉进内存再过滤 | `DoseTrackingService.kt:743-745`：`getSlotsInRange(from, to).filter { it.medicationId == medicationId }` | DAO 加 `medicationId` 条件。药品多时是 O(药数 × 窗口槽位) |
| P3-8 | `DevDataReceiver.ACTION_CLEAR` 只 `clearAllTables()`，不撤闹钟与通知（debug 源集） | `DevDataReceiver.kt:47-50` | 走查脚本 `--clear` 之后托盘里会残留上一次的僵尸通知（截图里看得见）。清库时一并 `cancelAll()` |
| P3-9 | **「拍快照」的责任仍在 `rescheduleAll` 内部 ⇒ 删行发生在它之前的三个调用点会留下幽灵闹钟**（round2 N7，本轮逐一复核后确认仍开放） | 快照在 `AlarmReconciler.kt:133`（重排**之前**拍），而三个调用点都是**先删后调**：① `MedicationDetailViewModel.deleteMedication`（`:202` 删药 → `:203` 重排，FK CASCADE 已把槽位删掉）；② `AddEditMedicationViewModel.saveInternal`（`:499` `reconcileSchedule` 会删不再被命中的槽位 → `:502` 重排）；③ `ReminderSettingsViewModel.save`（`:413` 同上 → `:414` 重排） | 后果**有界**：① `AlarmReceiver.kt:60-68` 查不到槽位即静默 return，不会错药；② `dose_slots.id` 是 `AUTOINCREMENT`，id 不复用，不会错配到新槽位。实害 = 每个被删/被改的槽位在其原定时刻空唤醒一次（含 `ADVANCE`/`SNOOZE` 两种），最多 14 天<br>**修法**：把"拍快照"上移为调用方责任 —— `rescheduleAll(context, db, presnap: Set<AlarmIdentity> = emptySet())`，三个调用点在删行**之前**用 `getOpenSlots()` 取一份传进去（与恢复路径的 `cancelAllAlarmsBeforeRestore` 同一形状）。不要试图在 `rescheduleAll` 内部修复——它看不到已经删掉的行 |

---

## 六、本轮核实为**无问题**的方面（同样重要，避免下轮重复劳动）

1. **库存守恒 I1/I2**：全 `app/src/main` grep 无任何 `UPDATE medications SET ..._stock`；
   6 条库存路径全部 `withTransaction`（`DoseTrackingService.kt:103, 153, 430, 520, 567, 600, 643`）；
   `appendLedger` 的 `balanceAfter` 恒等于 `balanceOf(id) + changeAmount.milli`，与权威值同源。
2. **幂等锚点全部下沉到 SQL**：`markCompletedIfOpen` / `markSkippedIfOpen` /
   `snoozeSlot` / `revertToPending` / `markExpired` / `updateDerivedColumns` 六条写命令
   都带 `status IN (...)` 守卫，且**返回值**都让调用方能判别是否生效。
   唯一的例外 `forceStatusForTest` 已按意图重命名，签名自带"这不是业务路径"。
3. **撤销按台账净额冲正**：`revertSlotInternal`（`DoseTrackingService.kt:218-249`）用
   `getCompletedRecordsBySlot` + `getSumOfChangeByRecordId` 的**净额**，
   天然幂等，且不读"药品当前是否追踪库存"这个会漂移的值。
4. **改剂量的差额方向正确**：`DoseRecordDetailEditTest` 覆盖的
   `delta = Dose(record.doseTaken - newDose.milli)`（`:482`），
   且 `net != 0` 才动台账 —— 当初没扣库存的记录改剂量不会凭空造账。
5. **暂停参与投影而非显示过滤**：`SlotProjectionEngine.projectSlots:84-91` 与
   `ReminderSettingsEntity.isPausedOn` 用**同一个**三态解析方向（解析失败按"未暂停"），
   判据同源；`AlarmReconciler.isPausedOn(slot)` 按**槽位自己的日期**判断（`:187-192`），
   不会把恢复日之后的提醒一并压掉。
6. **恢复的删表顺序与外键方向正确**：子表先删（`DataExporter.kt:656-663`），
   父表先插（`:666` 起），且全程在 `db.withTransaction` 内；`AppDatabase` 开了
   `fallbackToDestructiveMigration()`（`:146`）**已用醒目 KDoc 标注"发布前必须删除"**。
7. **备份校验覆盖了全部静默吞行的场景**：药品主键、槽位业务键、槽位主键、
   `dose_records` / `inventory_transactions` / `schedule_policies` / `policy_times` 主键、
   `reminder_settings.medication_id`、`app_settings.key`、同药品多条 active 计划 ——
   且判据是 `BackupProblemKind.blocksRestore`（枚举自带语义），
   预览与恢复两处用的是**同一个变量**（`:935` 与 `:1001`）。
8. **量纲纪律**：`Dose`（整数毫单位）在实体/DAO 边界是 `Int`，只在 UI 边界转 `Float`；
   跨单位求和被 `StatsEngine.groupByUnit` 收成一处（且测试共用同一份实现，非影子）。
9. **已修的架构纪律仍在位**：`viewModel()` 反射单参构造器的坑 —— `StatsViewModel` /
   `TodayViewModel` / `ProgressViewModel` / `DoseRecordDetailViewModel` 全部保持
   `AndroidViewModel(Application)` 单参，需要额外参数的用 `viewModelFactory { initializer { … } }`。
10. **量纲/时区测试纪律**：`SlotProjectionDstServiceTest` + DST 属性测试覆盖
    `LocalDate.plusDays` / 逐日 `atZone` 的时刻计算；`ReminderSettingsTest`、
    `FieldPreservationInvariantTest` 用相对日期而非硬编码日历（AGENTS 警告的时钟型脆弱测已清除）。

---

## 七、建议的修复顺序

| 批次 | 内容 | 理由 |
| --- | --- | --- |
| **第 1 批（阻断发布）** | **P0-1**（补响循环） | 唯一一条会让用户卸载 App 的缺陷；改动面 = 1 列 + 1 条 SQL + 1 处分支 + 1 处 `AlarmReceiver` 条件 |
| **第 2 批（数据正确性）** | P1-1、P1-2、P1-4 | 都会**静默改错或永久锁死数据**，且都是小改动（1~2 个文件） |
| **第 3 批（承诺一致性）** | P1-3、P2-2 | 两个"开关说谎"，同一族（`clearEndDate` 与暂停三态），建议一起改 |
| **第 4 批（体感）** | P2-1、P2-3 | 都是"让已有的正确数据及时显示 / 不冻结界面" |
| **第 5 批（一致性）** | P2-4、P2-5 | 备份快照与外层事务 |
| **第 6 批（卫生）** | P3-1 ~ P3-9 | 一次提交做完；P3-4 的 `record_id` 索引与 P0-1 的 `catchup_done` 都要改 `dose_slots` 的 schema，**合并成同一次 `AppDatabase.version` 6→7**，一次建库、一次补 `PRAGMA table_info` 断言 |

> ⚠️ **P0-1 与 P3-4 都要动 `dose_slots` 的 schema（加列 / 加索引）。**
> 合并成一次 `version 6 → 7`，一次建库、一次补 `AppDatabaseRealTest` 的
> `PRAGMA table_info` 断言，避免连续两次破坏性重建（AGENTS §2 第 1 条）。

---

## 八、局限与未复核项（诚实标注）

- **本轮是静态审查 + 一次 Robolectric 实测**，**未在模拟器上跑 UI 走查**，
  未做 `dumpsys alarm` 的长时间观测。P0-1 的 Robolectric 证据证明了
  "每次对账都会重新武装补响闹钟"这一环，而"响铃 → `enqueueOneShot` → 对账"这一环
  是从 `AlarmReceiver.kt:100` 与 `ReconcileWorker.kt:69` 直接读出来的代码事实，
  两者合起来构成闭环；建议修完后在模拟器上按 §1.6 的验收步骤实测确认。
- **未复核**：`docs/FINAL-PRODUCT.md` / `APP_DESIGN_SPEC.md` 里的产品承诺与实现的
  逐条对齐（上一轮 sbf P1-13"导出口径"至今未复核）；`tools/app_screenshots.py`
  的 `PROGRAM` 是否覆盖了全部 13 个全屏页（`DoseRecordDetailScreen` 是新增页，
  本轮未见其登记条目）。
- **未运行** `./gradlew compileReleaseKotlin`（本轮未改代码，故不构成门禁失败）。
- **本轮未修改任何源码、未 commit / push。** 唯一的写操作是创建本报告；
  中途为取证临时新增的 `ZzTempGraceLoopProofTest.kt` 已删除并确认
  `git status` 回到审查前状态。
