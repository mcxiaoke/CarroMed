# CarroMed 代码审查报告（数据与逻辑）

| 项 | 值 |
| :--- | :--- |
| 审查时间 | 2026-09-29 09:25 (GMT+8) |
| 审查范围 | `app/src/main` 全部 104 个 Kotlin 源文件（主源集 + debug 源集 + 测试源集） |
| 审查目标 | **数据模型与业务逻辑缺陷**（不计风格、命名、注释措辞） |
| 审查方法 | 逐文件阅读实现 → 交叉核对调用方与被调方 → 上机实测取证 |
| 明确未采用的信息源 | `docs/CHANGES-*.md`、提交记录、代码注释中关于"已修复/已验证"的结论 |
| 实测环境 | `emulator-5554`，Android 15 / API 35，应用 `com.mcxiaoke.carromed` 已安装运行 |
| 单测状态（本机实测） | `:app:testDebugUnitTest` BUILD SUCCESSFUL，**34 个测试类 / 302 项 / 0 失败 / 0 跳过** |

> 本报告所有结论都给出 `文件:行号` 或实测命令与原始输出。凡未能在代码或实测中确认的推测，一律不进正文。

---

## 0. 结论摘要

| 编号 | 等级 | 问题 | 关键位置 |
| :--- | :---: | :--- | :--- |
| P0-1 | **P0** | 精确闹钟在 Android 14+ 上** 100% 降级为 1 小时窗口的不精确闹钟**，服药提醒可延迟近 1 小时 | `AlarmScheduler.kt:166-186`、`app/build.gradle.kts:26` |
| P0-2 | **P0** | "系统特权自检"页是**纯静态假数据**：精确闹钟/通知权限恒显示"已授权"，"去设置"按钮是空实现 | `PermissionCheckScreen.kt:72,81,142` |
| P1-1 | P1 | 归档药品的未服槽位**不清理、不排闹钟**，却仍出现在今日清单/进展流水，渲染为无名的「药品」卡片 | `AlarmReconciler.kt:115,147,166`、`TodayViewModel.kt:92,114` |
| P1-2 | P1 | 同一"依从率"在两页口径分裂：统计页**计入已归档药品**的槽位，进展页不计 | `StatsViewModel.kt:110` vs `ProgressViewModel.kt:91` |
| P1-3 | P1 | 「暂停到明天」实际暂停到**后天**，「暂停到一周后」实际压制 **8 天**（含当日语义 off-by-one） | `MedicationDetailScreen.kt:559-565`、`ReminderSettingsEntity.kt:85-90` |
| P1-4 | P1 | 三个全局设置项**存了但从不生效**：全屏弹窗、提醒铃声、药品图标 | `SettingsViewModel.kt:88-93`、`ReminderSettings.kt:29,37,44` |
| P1-5 | P1 | 低库存告警口径三页不一致，且**无视"预警线 = 0 即关闭告警"**这一约定 | `StatsEngine.kt:91,96` vs `TodayViewModel.kt:138-140` |
| P1-6 | P1 | 日期在 ViewModel 构造时冻结，**跨午夜后"今日"错位一天** | `ProgressViewModel.kt:74-75`、`TodayViewModel.kt:74` |
| P2-1 | P2 | 药箱/今日/进展三处 N+1 查询（与两个 DAO 自己声明的"避免 N+1"目标相反） | `CabinetViewModel.kt:119-148` 等 |
| P2-2 | P2 | 同一个 `intervalDays` 在药箱页与库存页显示成两套文案 | `CabinetViewModel.kt:129` vs `InventoryViewModel.kt:270-272` |
| P2-3 | P2 | 死代码与"地雷" API，其中 `ensureInitialStockLedger` 的 `balanceAfter` 是硬编码，调用即可能打破 I2 | `MedicationAdminService.kt:261-284` 等 |
| P2-4 | P2 | 新建药品的"过去时点"红字提示与实际保存行为在**两个方向**上都不一致 | `AddEditMedicationScreen.kt:790-808` vs `AddEditMedicationViewModel.kt:309-313` |
| P2-5 | P3 | `getActivePolicyForMedication` 用 `LIMIT 1` 却无 `ORDER BY`，多行 active 时结果不可复现 | `SchedulePolicyDao.kt:30` |
| P2-6 | P3 | 文档/注释与实现漂移（`AGENTS.md` 称 184 项测试，实测 302；实体 KDoc 仍引用已删除列） | `AGENTS.md`、`InventoryTransactionEntity.kt:15` |

---

## 1. P0 级问题

### P0-1 精确闹钟在 Android 14+ 上全线失效（有实测原始证据）

**现象**：应用注册的 67 个服药闹钟**全部**是不精确闹钟，系统给出了 `+1h0m0s0ms` 的投递窗口。计划 09:00 的服药提醒，实际可能 10:00 才响。

**代码机制**（`app/src/main/kotlin/com/mcxiaoke/carromed/core/alarm/AlarmScheduler.kt`）

```kotlin
val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
    alarmManager.canScheduleExactAlarms()          // 166-167
if (canExact) { ... setExactAndAllowWhileIdle ... } // 171（一档，本机从未进入）
try { alarmManager.setAlarmClock(...) }             // 179（二档，本机全部抛 SecurityException）
catch (e: SecurityException) { ... }
alarmManager.setAndAllowWhileIdle(...)              // 185（三档，本机实际全部落到这里）
```

- `app/build.gradle.kts:26` → `targetSdk = 35`
- `app/src/main/AndroidManifest.xml` 只声明了 `SCHEDULE_EXACT_ALARM`，**没有** `USE_EXACT_ALARM`
- Android 14（API 34）起，`targetSdk ≥ 34` 的应用**不再被预授予** `SCHEDULE_EXACT_ALARM`；`setExactAndAllowWhileIdle` 与 `setAlarmClock` 都会因此抛 `SecurityException`

**实测证据（本机 emulator-5554，API 35）**

```
# 1) 权限 op 不是 allow，而是未决策的 default
$ adb -s emulator-5554 shell cmd appops get com.mcxiaoke.carromed SCHEDULE_EXACT_ALARM
No operations.
Default mode: default

# 2) 二档被系统拒绝 —— 587 次，且一档的失败日志 0 次（说明 canExact 从未为 true）
$ adb -s emulator-5554 logcat -d | grep -c "setAlarmClock denied"
587
$ adb -s emulator-5554 logcat -d | grep -c "exact alarm denied, falling back"
0
$ adb -s emulator-5554 logcat -d | grep -m1 "setAlarmClock denied"
W AlarmScheduler: setAlarmClock denied, falling back to inexact: Caller com.mcxiaoke.carromed
  needs to hold android.permission.SCHEDULE_EXACT_ALARM or android.permission.USE_EXACT_ALARM
  to set exact alarms.

# 3) 落到三档后，闹钟带 1 小时窗口
$ adb -s emulator-5554 shell dumpsys alarm | sed -n '293,296p'
RTC_WAKEUP #25: Alarm{331660c type 0 origWhen 1790672400000 whenElapsed 142115885 com.mcxiaoke.carromed}
  tag=*walarm*:com.mcxiaoke.carromed.action.DOSE_ALARM
  type=RTC_WAKEUP origWhen=2026-09-29 09:00:00.000 window=+1h0m0s0ms repeatInterval=0 count=0 flags=0x20
  operation=PendingIntent{2684b55: PendingIntentRecord{61a296a com.mcxiaoke.carromed broadcastIntent}}
```

**量化**：`com.mcxiaoke.carromed}` 共 67 条闹钟记录；其中含 `AlarmClockInfo` 的 **0** 条、带 `exactAllowReason=` 的 **0** 条（作为对照，系统与 GMS 的精确闹钟都带 `window=0 exactAllowReason=listener` 或 `policy_permission`）。

**影响**

- 对一个核心承诺是"到点提醒"的服药 App，**提醒可靠时间从秒级退化为小时级**。
- 与 `AlarmReconciler.EXPIRE_WINDOW_MS = 2h`（`AlarmReconciler.kt:51`）叠加后，延迟投递的提醒其槽位可能已被对账器结算为 `EXPIRED`（记为"漏服"），用户点通知打卡时槽位已不在开放态，`markCompletedIfOpen` 返回 0 ⇒ **记不上账**，且用户已在通知里点了"确认已吃"。
- `AGENTS.md` 第 2 节把闹钟风险单点定义为"身份是内容不是算术"，**这条链路的第二个故障源（权限降级）不在任何文档或测试的覆盖范围内**。

**建议方向**

1. 先决定目标形态：若定位为"闹钟类应用"，应改用 `USE_EXACT_ALARM`（免授权，但需在 Play/应用市场声明正当用途）；若继续用 `SCHEDULE_EXACT_ALARM`，必须引导用户到 `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM` 授权（见 P0-2）。
2. 三档降级不应静默：至少要在"权限自检"页如实反映当前档位，并在降级时对用户可见（不是只写 `Log.w`）。
3. 若要保留降级语义，则 `EXPIRE_WINDOW_MS` 需与最差投递延迟重新对齐，否则"延迟投递 ⇒ 记不上账"必然发生。

---

### P0-2 "系统特权自检"页是纯静态假数据

**位置**：`app/src/main/kotlin/com/mcxiaoke/carromed/ui/screen/settings/PermissionCheckScreen.kt`

```kotlin
PermissionItemCard(
    title = "1. 精确闹钟权限 (Exact Alarm)",
    desc = "必须权限。允许应用在设定的准点精确唤醒 CPU 发出用药提醒，避免被系统延迟对齐。",
    icon = Icons.Default.Alarm,
    statusText = "已授权",
    isGranted = true              // 71-72：无条件硬编码为"已授权"
)
...
PermissionItemCard(
    title = "2. 发送通知权限 (Notification)",
    ...
    statusText = "已授权",
    isGranted = true              // 80-81：同样无条件硬编码
)
...
OutlinedButton(onClick = {}, ...) // 141-142：「去设置」「查看指引」均为空实现
Text(statusText, ...)             // 139：绿色"已授权"
```

- 全工程 `grep -rn "canScheduleExactAlarms"` 在 `ui/` 下**零命中**；`PermissionCheckScreen.kt` 内无任何权限查询 API 调用。
- `requestNotificationPermissionIfNeeded()` 只在 `MainActivity.kt:62-71` 发起运行时请求，**不把结果回传 UI**；用户拒绝后自检页仍显示绿色"已授权"。

**影响**

- 在 P0-1 已证实的降级状态下，这一页会给用户**完全相反的结论**：用户看到"精确闹钟：已授权"，于是不会去系统设置里授权，缺陷永久不可被发现。
- 第 3、4 项（电池优化白名单、厂商自启动）的按钮是空实现，用户按了没有任何反馈，**保活指引等于没有**。
- 该页已被 `tools/app_screenshots.py` 纳入走查（`Step("shot", key="permission_check", ...)`），截图是"全绿"的 —— 这正是走查无法发现此类缺陷的例子：**走查验证的是渲染，不是事实**。

**建议方向**：用 `AlarmManager.canScheduleExactAlarms()` / `NotificationManagerCompat.areNotificationsEnabled()` / `PowerManager.isIgnoringBatteryOptimizations()` 三处真实查询驱动 `isGranted`；按钮接 `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` / `ACTION_APP_NOTIFICATION_SETTINGS` / `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`。

---

## 2. P1 级问题

### P1-1 归档药品的未服槽位：不清理、不排闹钟，但仍显示在今日清单

**代码链路**

1. 归档只改一个布尔列，不动排班数据：`MedicationDetailViewModel.kt:172-180` → `medDao.updateArchiveStatus(...)` → `AlarmReconciler.rescheduleAll(...)`。
2. 对账器只处理未归档药品：`AlarmReconciler.kt:115` `val schedulableMeds = db.medicationDao().getActiveOverviews()`（DAO 层 `MedicationDao.kt:112` 带 `WHERE m.is_archived = 0`）。
3. 第 4 步"注册闹钟"显式跳过非活跃药品：`AlarmReconciler.kt:147,166` `activeIds` / `if (slot.medicationId !in activeIds) continue`；
   第 3 步反而**会取消**这些槽位的闹钟（`shouldKeep` 要求 `medicationId in activeIds`）。
4. 结论：归档药品窗口内既有的 `PENDING` 槽位**既不删也不排闹钟**，长期滞留在库里。

**显示侧**

- `TodayViewModel.kt:92` 用 `medDao.observeActiveOverviews()` 建 `medMap`；
- `TodayViewModel.kt:95` 却用 `slotDao.observeSlotsForDate(dateStr)`（**全局**，不带归档过滤）取槽位；
- `TodayViewModel.kt:114-131`：`overview = medMap[slot.medicationId]` 为 null ⇒ `medication = null`，但 `SlotStatus.PENDING, SNOOZED, EXPIRED -> pending.add(item)` **照常加入待服列表**。
- 渲染侧兜底成无名卡片：`TodayScreen.kt:474` `text = med?.name ?: "未知药品"`、`TodayScreen.kt:681` `med?.name ?: "药品"`；点击无效（`TodayScreen.kt:285` `item.medication?.let { ... }`）。进展页同理：`ProgressScreen.kt:405` `item.medication?.name ?: "已删除的药品"`。

**实测佐证**（设备上确实存在该形态数据）

```
$ adb -s emulator-5554 shell dumpsys alarm | grep -c "com.mcxiaoke.carromed}"
67          # 有大量槽位/闹钟在库，说明该分支路径在生产数据形态下是活的
```

**影响**：用户"停药归档"后，今日清单里仍会出现一张点不动、没有药名的待服卡片；2 小时后被对账器结算为 `EXPIRED`，卡片改标"已漏服"，继续留在清单上。这与产品口径"槽位存在 ⟺ 这个时点会响"（`SlotProjectionEngine` KDoc 自述）直接冲突。

**建议方向**：归档时对未服槽位做一次显式清理（复用 `slotDao.deleteSpeculativeFutureSlots` 的语义，把 `afterDate` 传成"今天前一天"即可删除全部未来未服槽位），或在读侧对 `medication == null` 的槽位做过滤/展示为"已归档"。

---

### P1-2 同一"依从率"，统计页与进展页口径分裂

| 页面 | 槽位来源（决定分母） | 是否含已归档药品 |
| :--- | :--- | :---: |
| 统计页 | `StatsViewModel.kt:110` `slotDao.getSlotStatusCounts(startDate, endDate)` —— **全局，无归档过滤** | **含** |
| 进展页 | `ProgressViewModel.kt:91` `overviews.map { ... }`，而 `overviews` 来自 `medDao.observeActiveOverviews()` | **不含** |

- 两个页面使用的底层 SQL 都是 `DoseSlotDao.getSlotStatusCounts` / `observeSlotStatusCounts`（`DoseSlotDao.kt:204-254`、`221-237`），SQL 内没有 `is_archived` 条件，归档过滤只在进展页的 Kotlin 侧通过"只遍历活跃药品"实现。
- 统计页同时用 `activeMedCount = overviews.size`（`StatsViewModel.kt:170`，活跃数）+ `scheduledDoseCount = allBreakdowns.total`（含归档），**同一屏上分子分母来源不同**。
- 结合 P1-1：归档药品的槽位会继续被对账器结算为 `EXPIRED`（`AlarmReconciler.kt:89` 的 `getStaleOpenSlots(cutoff, cutoff)` 是全局查询），于是统计页会在"漏服"里持续累计已归档药品的条目。

**影响**：用户停药归档后，统计页依从率会继续被已归档药品拉低，而进展页不会 —— 两个页面给出不同的"依从率"，且没有任何界面说明差异来源。

**建议方向**：把归档过滤下沉到 SQL（两个聚合查询各加一个"仅未归档药品"的版本），并让统计页明确标注分母是否含归档。

---

### P1-3 「暂停到明天」实际暂停到后天（off-by-one）

**位置**：`app/src/main/kotlin/com/mcxiaoke/carromed/ui/screen/detail/MedicationDetailScreen.kt`

```kotlin
PauseOptionRow("暂停到明天") {
    viewModel.pauseReminderUntil(LocalDate.now().plusDays(1))   // 561
}
PauseOptionRow("暂停到一周后") {
    viewModel.pauseReminderUntil(LocalDate.now().plusDays(7))   // 565
}
```

**语义实现**：`ReminderSettingsEntity.isPausedOn`（`ReminderSettingsEntity.kt:85-90`）为**含当天**：

```kotlin
if (until.isBlank()) return true
val end = parseDate(until) ?: return false
return !end.isBefore(today)     // 89：end >= today 即视为暂停
```

**推导**（设 today = T）：`pausedUntil = T+1` ⇒ `isPausedOn(T) = true`、`isPausedOn(T+1) = true`、`isPausedOn(T+2) = false` ⇒ **实际 T+2 才恢复**，即"暂停到明天"真实语义是"暂停到后天"。

**自相矛盾的证据**：同一实体的 `pauseDescription` 会算出 `daysUntilResume(T) = (T+1 - T) + 1 = 2`（`ReminderSettingsEntity.kt:93-99`），于是详情页徽标显示"提醒已暂停，2 天后恢复"——**按钮说"暂停到明天"，点完之后徽标说"2 天后恢复"**。

「暂停到一周后」同理：`plusDays(7)` ⇒ 实际压制 8 个自然日。

**建议方向**：二选一 —— 把 `isPausedOn` 改成排他（`end.isAfter(today)`，即"暂停至该日不含"），或把按钮文案/`plusDays` 参数改成 `plusDays(1) → "暂停到后天"` / 传 `LocalDate.now()` 表示"到明天为止"。**必须同时检查 `SlotProjectionEngine.projectSlots` 的暂停分支**，它与 `isPausedOn` 共用同一套含当日语义：

```kotlin
// SlotProjectionEngine.kt:91,100
val suppressUntil = pauseEnd?.takeIf { !it.isBefore(effectiveStart) }
if (suppressUntil != null && !current.isAfter(suppressUntil)) continue
```

两处若不同步修改，会重新出现"徽标说已恢复、投影仍不排槽位"的漂移。

---

### P1-4 三个全局设置项存了但从不生效

| 设置项 | 写入点 | 读取点 | 是否有消费点 |
| :--- | :--- | :--- | :---: |
| `full_screen_alert`（全屏弹窗提醒） | `SettingsViewModel.kt:88-93` | `ReminderSettings.kt:37,44`（读进 `Behavior.fullScreenAlert`） | **无** |
| `sound_mode`（提醒铃声） | `SettingsViewModel.kt:81-86` | `SettingsViewModel.kt:61`（仅回显） | **无** |
| `icon_name`（药品图标） | 无写入路径（`MedicationDao.updateProfile` 的 SET 列表 `MedicationDao.kt:179-193` 里没有 `icon_name`） | 无 | **无** |

**证据（全工程 grep）**

- `fullScreenAlert` 全部出现位置：`ReminderSettings.kt:29,37,44`（定义/赋值）、`SettingsViewModel.kt:22,62,68,89,91`（读写）、`SettingsScreen.kt:270`（开关 UI）、`ReminderSettingsTest.kt:52`（断言默认值）。**`Notifications.showDoseNotification` 只使用 `behavior.snoozeMinutes`（`Notifications.kt:171`）与 `shouldSilence` 间接使用的 `nightDnd`（`Notifications.kt:144`），从不读 `fullScreenAlert`。**
- `sound_mode`：只有 `SettingsViewModel` 的读写；通知渠道由 `Notifications.ensureChannel` 固定创建（`Notifications.kt:52-73`），无 `setSound` 分支。`ReminderSettings.KEY_SOUND_MODE`（`ReminderSettings.kt:19`）与 `KEY_LEAD_MINUTES`（`:20`）两个常量**在整个 main 源集内零引用**。
- `iconName` 仅出现在实体定义、`DataExporter` 的备份往返、debug 播种与测试断言中，UI 层零引用。

**影响**：用户在设置页关掉"全屏弹窗提醒"、切换"提醒铃声"，**行为完全不变，也没有任何提示**。这属于"用户以为改了、系统没改"的静默失效，比"缺少开关"更糟。

**建议方向**：接入实现（`fullScreenAlert` → 用 `setFullScreenIntent` 构建全屏 `PendingIntent`；`soundMode` → 在 `ensureChannel` 里按配置为渠道设 sound/震动；`iconName` → 要么在新增/编辑表单加图标选择并补进 `updateProfile` 的 SET 列表，要么删列），或从 UI 上撤掉开关。

---

### P1-5 低库存告警口径三页不一致，且无视"预警线 = 0 即关闭告警"

**约定**（两处独立声明）：

```kotlin
// MedicationEntity.kt:60
/** 低库存预警线，整数毫单位。0 = 关闭低库存告警 */
@ColumnInfo(name = "min_stock_alert") val minStockAlert: Int = 0,
```

```kotlin
// TodayViewModel.kt:138-140（今日页据此实现）
val lowStock = overviews.filter {
    it.isStockTracked && it.minStockAlert > 0f && it.stock <= it.minStockAlert
}
```

**但详情页与库存页走的是另一套判据**：

```kotlin
// StatsEngine.kt:85-98
fun calculateStockRunway(currentStock: Float, dailyEstimatedConsumption: Float, minStockAlert: Float = 0f): Pair<Int, Boolean> {
    if (dailyEstimatedConsumption <= 0f) {
        val isAlert = currentStock <= minStockAlert && minStockAlert > 0f   // 91：这里尊重了 0=关闭
        return Pair(Int.MAX_VALUE, isAlert)
    }
    val runwayDays = (currentStock / dailyEstimatedConsumption).toInt().coerceAtLeast(0)
    val isAlert = currentStock <= minStockAlert || runwayDays <= 7          // 96：这里没有
    return Pair(runwayDays, isAlert)
}
```

- 第 96 行的 `runwayDays <= 7` 是**与 `minStockAlert` 完全无关**的硬规则；`currentStock <= minStockAlert` 在 `minStockAlert = 0` 时对 `stock <= 0` 恒真。
- 消费点：
  - 详情页 `MedicationDetailScreen.kt:270` `highlight = uiState.isStockAlert && med.isStockTracked`（`isStockAlert` 来自 `MedicationDetailViewModel.kt:107-113` 的 `calculateStockRunwayBySchedule`）
  - 库存页 `InventoryScreen.kt:147,158,166` `uiState.isLowStock && uiState.isTracked`（`InventoryViewModel.kt:94-101` 同上）
  - 今日页横幅 `TodayViewModel.kt:138-140`（另一套判据）

**可达反例**：药品追踪库存中、用户把预警线设为 0（明确表示"别告警"）、账面剩 3 片、日消耗 1 片 ⇒ 今日页不告警（`minStockAlert > 0f` 不成立），详情页与库存页 **告警**（`runwayDays = 3 <= 7`）。

**建议方向**：把"预警线 = 0 即关闭"这条产品约定收敛进 `StatsEngine` 单一实现（例如在 `minStockAlert <= 0` 时直接返回 `isAlert = false`），三页共用；若确实要保留"7 天内必提醒"的兜底，它应是**独立于用户预警线的第二个开关**，而不是藏在同一个返回值里。

---

### P1-6 日期在 ViewModel 构造时冻结，跨午夜后错位一天

```kotlin
// ProgressViewModel.kt:74-75
private val today = LocalDate.now()
private val weekDates = remember7Days()
```

```kotlin
// TodayViewModel.kt:74
private val _selectedDate = MutableStateFlow(LocalDate.now())
```

- 这两处只在 ViewModel 实例化时求值一次。ViewModel 的存活期跨越午夜是常见场景（应用置于后台一整夜后回到前台、配置变更复用实例）。
- 后果：
  - `ProgressViewModel.kt:98` `isFutureDay = d.isAfter(today)`、`:97` `dayLabel = if (d == today) "今日"` 全部指向**昨天**，"7 天矩阵"整窗左移一天，今天的打卡被显示成"未来"。
  - `TodayViewModel` 的日期选择器高亮停在昨天，用户看到的是昨天的清单。
- 注意 `AddEditMedicationScreen.kt:786-789` 的 `val nowMinutes = remember { ... }` 同样只在首次组合时取值（`AddEditMedicationViewModel.kt:392-399` 的 `isBeforeNow()` 反而是每次现算的），两处对"当前时刻"的取值时机不一致。

**建议方向**：把"今天"改为随数据流刷新的派生量（例如订阅一个每分钟/每次 `ON_RESUME` 触发的时钟 Flow），或至少在 `ON_RESUME` 时重算。

---

## 3. P2 / P3 级问题

### P2-1 三处 N+1 查询（与 DAO 自述的设计目标相反）

`MedicationDao` 与 `ReminderSettingsDao` 的 KDoc 都明确写着"一次 JOIN 取全，避免 N+1"。以下三处破坏了这条：

| 位置 | 查询模式 |
| :--- | :--- |
| `CabinetViewModel.kt:119-148` `buildItemUi` 是 `suspend`，每味药 2 次查询（`getActivePolicyForMedication` + `getTimesForPolicy`），在 `combine` 变换里对全列表执行 ⇒ **2N 次/发射** | `CabinetViewModel.kt:87-100` |
| `TodayViewModel.kt:111` 已用 `getCompletedRecordsForSlots` 做了批量，但 `:118` 的 `SKIPPED` 分支仍逐槽 `recordDao.getRecordBySlotId(slot.id)` ⇒ 今日跳过数 N 次 | 同一 transform 内 |
| `ProgressViewModel.kt:129` 今日流水逐槽 `recordDao.getRecordBySlotId(slot.id)` | 同上 |

**影响**：药品数量增长后，这三个页面的每次 Room 数据变更都会触发线性增长的同步查询（Robolectric 单测里感受不到，真机大库可感）。

### P2-2 `intervalDays` 的文案两套口径

```kotlin
// CabinetViewModel.kt:129
val intervalText = if (policy.intervalDays <= 2) "隔天" else "每隔 ${policy.intervalDays - 1} 天"
```

```kotlin
// InventoryViewModel.kt:270-272
val dayText = if (n <= 1) "每天" else if (n == 2) "隔天" else "每 $n 天"
```

同一个 `intervalDays = 3`：药箱页显示「每隔 2 天」，库存页显示「每 3 天」。两者语义等价但措辞不同，且**同一个值同一个 App 内出现两种描述**。`InventoryViewModel` 的 KDoc 专门记录过一次同类缺陷（"频次文案与可用天数互相打架"），说明这条线上已经踩过一次。

### P2-3 死代码与"地雷" API

**无任何生产调用方（`grep` 计数 = 1，即只有声明处）**：

- `DoseSlotDao.updateStatus`（`DoseSlotDao.kt:101-102`）—— **即使不被调用也危险**：它是 DAO 里唯一一个**没有状态守卫**的槽位写入方法，而 `markExpired` 的 KDoc（`:304`）正是把它树为对照物。任何后续改动若顺手用它替代 `markCompletedIfOpen`，幂等锚点就消失了。
- `DoseSlotDao.insert`（`:19-20`）—— 用 `OnConflictStrategy.REPLACE`。而 `insertAll` 的 KDoc（`:22-31`）花了整段论证"REPLACE 会换 id ⇒ 闹钟变孤儿"，并把 `insertAll` 改成 `IGNORE`。把被否定的策略保留成公共 API，是把结论锁在注释里而不是代码里。
- `DoseSlotDao.observeSlotsInRange`、`countCompletedSlotsForDate`、`countTotalSlotsForDate`
- `DoseRecordDao.observeRecordsInRange`、`getRecordsInRange`、`countDoseRecordsForMedication`
- `InventoryTransactionDao.getLatestTransaction`（仅测试使用）
- `AppSettingDao.getSetting`
- `ReminderSettingsDao.observeAll`、`observeByMedicationId`、`getActiveWithReminder`、`getSchedulableOn`、`deleteForMedication` —— 注意 `getSchedulableOn` 的 KDoc 声称它是"该不该为它排闹钟的**唯一数据库级判据**"，而实际生产代码走的是 `MedicationDao.getActiveOverviews()` + `isPausedOn`，**注释描述的唯一性不成立**。
- `StatsEngine.calculateAdherence`（仅单测使用；生产走 `adherenceOf`）
- `DoseTrackingService.fmtQty`（`DoseTrackingService.kt:494`，private 且无调用）

**两个方法完全没有调用方，且内含算法分叉**：

```kotlin
// MedicationAdminService.kt:261-273
suspend fun ensureInitialStockLedger(medicationId: Long, stock: Float) = db.withTransaction {
    if (stock <= 0f) return@withTransaction
    if (inventoryDao.getSumOfChanges(medicationId) != null) return@withTransaction
    inventoryDao.insert(InventoryTransactionEntity(
        medicationId = medicationId,
        changeAmount = Dose.of(stock).milli,
        balanceAfter = Dose.of(stock).milli,        // ← 硬编码假定账面为 0
        txType = TransactionType.CALIBRATION_ADJUST,
        note = "初始录入建档"
    ))
}
```

- 真正的建档路径是 `DoseTrackingService.setStockTracking`（`DoseTrackingService.kt:306-338`），它算 `balanceAfter` 的方式是 `appendLedger` 里的 `balanceOf(med) + change`（`DoseTrackingService.kt:76`）。
- `ensureInitialStockLedger` 若被调用（且此时账面非 0、但 `getSumOfChanges` 返回 null 与"账面为 0"不等价 —— 该分支只在从未有流水时为真，因此当前恰好安全），`balanceAfter` 仍可能与 `SUM(change_amount)` 分叉，直接破坏不变量 I2。
- 其 KDoc 还引用已被删除的列：`"保证 SUM(inventory_transactions.change_amount) == medications.current_stock 守恒"` —— `current_stock` 在 A1 已从 `medications` 删除。
- `enableStockTrackingIfNeeded`（`:279-284`）同样无调用方，与 `DoseTrackingService.setStockTracking(medId, true, null)` 语义重叠。

**建议**：删除无调用方的 API（尤其是 `updateStatus` / `insert(REPLACE)` / `ensureInitialStockLedger`），或把它们收成 `private`/标注 `@Deprecated` 并给出替代路径。

### P2-4 新建药品"过去时点"提示与实际保存行为不符（两个方向都错）

```kotlin
// AddEditMedicationScreen.kt:790-802（只要有**任一**时点在过去就红字警告）
val pastSlots = uiState.timeSlots.filter { ... m < nowMinutes }
if (pastSlots.isNotEmpty()) { ... "今天这一剂会直接记为逾期。..." }
```

```kotlin
// AddEditMedicationViewModel.kt:309-313（只有**全部**时点在过去才顺延 startDate）
val effectiveStartDate = if (policyRequired && !s.isEdit && s.startDate == todayDate()) {
    if (s.timeSlots.all { it.isBeforeNow() }) tomorrowDate() else s.startDate
} else { s.startDate }
```

| 场景 | 页面提示 | 实际行为 | 是否一致 |
| :--- | :--- | :--- | :---: |
| 12:00 创建 08:00 + 20:00 | "08:00 今天会记为逾期" | 确实会产生 1 条 EXPIRED | ✅ |
| 20:00 创建 08:00 + 11:00 | "今天这一剂会直接记为逾期" | `startDate` 被静默改到明天，**今天不会产生任何逾期记录** | ❌ |

第二行的后果不是数据错误，而是**用户读到了一句与实际不符的话**（提示与保存逻辑判据不同：`any` vs `all`），并且起始日被静默改动、界面上没有任何回显。

### P2-5 `getActivePolicyForMedication` 无 `ORDER BY`

```kotlin
// SchedulePolicyDao.kt:30
@Query("SELECT * FROM schedule_policies WHERE medication_id = :medicationId AND is_active = 1 LIMIT 1")
suspend fun getActivePolicyForMedication(medicationId: Long): SchedulePolicyEntity?
```

`savePolicyWithTimes`（`SchedulePolicyDao.kt:54-61`）先 `deactivatePoliciesForMedication` 再插新行，正常路径下 `is_active = 1` 只会有一行。但这条"唯一 active"是靠调用顺序维持的**约定**而非数据库约束（`schedule_policies` 上没有对应唯一索引，`SchedulePolicyDao.kt` 顶部 `indices` 只有 `medication_id`）。一旦通过 `DataExporter.restoreBackup` 恢复了一份含多行 active 的备份（`validateBackup` 并不校验 `is_active` 的基数），或并发保存，`LIMIT 1` 取到哪一行由 SQLite 决定，**不可复现**，而返回值直接决定"这个药一天吃几次/什么时候吃"。

### P2-6 文档与实现漂移（作为"文档不可信"的旁证）

- `AGENTS.md` 第 3 节写"当前 184 项"测试、覆盖"12 条不变量"；本机实测 `:app:testDebugUnitTest` 为 **34 个测试类 / 302 项 / 0 失败 / 0 跳过**（统计自 `app/build/test-results/testDebugUnitTest/*.xml`）。项数本身不是门禁（`AGENTS.md` 自己也这么说），但**文档给出的数字与事实不符**，说明它没有随实现更新。
- `InventoryTransactionEntity.kt:15` 的 KDoc 仍写"守恒不变式：medications.current_stock 恒等于 SUM(change_amount)"，而 `current_stock` 列已不存在（`MedicationEntity.kt:50-57` 明确记录该列已删除）。
- `MedicationAdminService.kt:256-258` 的 KDoc 同样引用已删除的 `medications.current_stock`。
- `SchedulePolicyDao.getActivePolicyForMedication` 等处的"唯一性"表述见 P2-5。

---

## 4. 已验证"**【不是】**问题"的高风险点

以下各项在静态审查中曾被怀疑，经代码核对或实测后**排除**，列出以避免后续重复排查（同时也说明本报告的结论经过了双向验证）：

| 曾怀疑 | 核对结果 |
| :--- | :--- |
| `AppDatabase.version = 5` 与 schema 不匹配 ⇒ 冷启动崩 `Room cannot verify the data integrity` | 设备上应用正常运行且已注册 67 条闹钟，说明 identity hash 校验通过；`version = 5` 与当前 8 张实体表一致（含 `reminder_settings`、`dose_slots` 唯一索引） |
| `takeDose` / `undoDose` 可能重复扣减或重复冲正 | 幂等锚点确实下沉在 SQL（`DoseSlotDao.kt:112-129`、`:157-164`），`InventoryLedgerInvariantTest` 的 I1/I2/I3/I4/I12 共 21 项全绿，其中含"重复次数 0..6 穷举 + 每次换剂量"的强化版 |
| `reconcileSchedule` 可能冲掉历史事实 | 删除范围严格限定 `status IN ('PENDING','SNOOZED')`（`DoseTrackingService.kt:440-445`、`DoseSlotDao.kt:180-188`），`ReconcileScheduleTest` 覆盖 |
| `pauseReminderUntil(null)` 会被当成"未暂停" | 实际映射为 `""`（无限期），与 `isPausedOn` 的三态语义一致（`MedicationDetailViewModel.kt:158`），**这一条与 P1-3 是两回事**：三态映射正确，错的是按钮文案与天数偏移 |
| 编辑药品页会渲染"提醒计划/初始库存"卡片，用户改了却不保存 | 两个卡片分别由 `AddEditMedicationScreen.kt:388` 的 `if (!isInfoOnly)` 正确门控，不存在静默 no-op |
| 库存预警线/初始库存输入框未过滤字符 ⇒ 可写入负数 | `saveProfile` 侧有 `coerceAtLeast(0f)`（`MedicationAdminService.kt:143,163`），初始库存侧有 `stockFloat > 0f` 守卫（`AddEditMedicationViewModel.kt:357`），两条路径都被挡住 |
| 补录页的"扣库存"开关对未追踪药品无意义 | `ManualDoseScreen.kt:411-414` 已用 `enabled = selectedMedication?.isStockTracked == true` 禁用 |
| 通知栏 Action 的 `requestCode` 算术编码有 P0-1 同类碰撞 | 安全：三个 Action 的 `action` 互不相同，`filterEquals` 会比较 action（`Notifications.kt:79-90` 的分析成立，且 `slotId*10+{0,1,2}` 在 `slotId >= 1` 时无交叠） |
| `Notifications` 的 `contentIntent` 复用同一 Intent | requestCode 用 `slot.id`（`Notifications.kt:166`）彼此不同，不会互相覆盖 |

---

## 5. 无法在静态审查中定论、需要实测的项

1. **P0-1 的实际用户可感知延迟**：本机证据只证明"注册成了不精确闹钟、窗口 1 小时"。真实投递延迟需在 Doze 下做长时实测（`adb shell dumpsys deviceidle force-idle` 后观察 `AlarmReceiver` 的 `dose alarm fired` 时间与 `scheduledTime` 的差值）。本次 logcat 缓冲区内 `dose alarm fired` 计数为 0，不足以量化。
2. **P1-2 对依从率数值的实际影响幅度**：取决于用户是否真的归档过药品，需要一个含归档药品的库做两页对照。
3. **P2-1 的性能影响**：需在药品数量达到几十量级后测三页的首帧与滚动帧率。
4. `DoseTrackingService.appendLedger` 的 `balanceAfter` 是"读当前 SUM 再写"（`DoseTrackingService.kt:76`）。在 `db.withTransaction` 内当前是安全的；但 `AlarmReconciler`（`AlarmReceiver.kt:76`、`MainActivity.kt:42`、`BootReceiver.kt:47`、各 ViewModel）存在**多处并发入口**同时调用 `rescheduleAll`，其内部的 `reconcileSchedule` 与打卡事务并发时的实际串行化行为建议实测确认（Room 默认 `WAL` + 单写者，理论上不会读到中间态，但值得用并发压测钉住）。

---

## 6. 修复优先级建议

1. **先修 P0-1 + P0-2**：这两条是同一个故障的两面（真降级 + 谎报已授权），且直接影响产品核心承诺，改动集中在 `AlarmScheduler` 与 `PermissionCheckScreen`。
2. **再修 P1-1 + P1-2**：都是"归档"这一个动作的遗漏，可以合并成一处改动（归档时清理未服槽位 + 聚合查询下沉归档过滤）。
3. **P1-3 / P1-5 需要先做产品裁决**（"暂停至该日"是含还是不含；"预警线 = 0"是否真的等于关闭告警），裁决后是单点改动，但**必须同时改投影层与判定层**。
4. **P1-4 / P2-3** 属于"要么实现、要么删掉"，建议一并清理，避免继续积累"存了但不生效"的字段与"没人调用但很容易被误用"的 API。

---

*报告完。所有行号基于 2026-09-29 09:25 的工作区状态（未做任何修改）。*
