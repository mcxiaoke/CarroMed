# CarroMed 代码审查报告（sba）

> 审查时间：2026-09-29 09:07 (GMT+8)
> 审查范围：`app/src/main`（66 个 kt 文件，约 12 400 行）、`app/src/debug`、`app/src/test`（33 个测试文件，约 7 000 行）、`AndroidManifest.xml`
> 审查方式：通读全部生产代码 + 全部测试代码，逐条核对实现与 KDoc/注释/相邻页面口径的一致性
> 本轮**未改动任何代码**

---

## 0. 总体评价

架构层面质量明显高于同规模项目，以下几点做得非常扎实，本报告不再重复：

- **库存余额的派生化**（`medications.current_stock` 已删，余额 = `SUM(change_amount)`）把守恒从"靠纪律维持的约束"变成恒等式，是本项目最有价值的一次重构。
- **写命令粒度对齐屏幕所有权**（`ReminderSettingsDao` 只提供细粒度命令、`updateProfile` 不碰提醒列）真正消灭了 P0-5 的两条写路径。
- **闹钟身份内容寻址**（`AlarmScheduler.alarmUri` + `requestCode ≡ 0`）是正确解法，且 `AlarmIdentityTest` 先证反例真实存在再验实现，方法论 exemplary。
- **毫单位（Int）记账** 已在实体/领域/DAO 边界统一，`DoseAsserts.kt` 提供了统一的断言辅助。

本轮发现的问题集中在**跨页面口径漂移**、**边界守卫缺失**、**测试守不住不变量**三类。
其中 P0 级 6 条、P1 级 14 条、P2 级 18 条、P3 级 13 条。

需要特别指出：项目文档（`CHANGES-20260928/29.md`）记录的多轮修复质量很高，但**修复往往只落到一半**——
同一个 bug 在两个 ViewModel 里改了一处漏了一处（见 P1-3 / P1-5 / P2-3），而单测恰好只覆盖了改过的那一侧，
所以门禁全绿。这比"从未发现"更危险，因为它给出的是虚假的完成信号。

---

## 1. P0 —— 严重缺陷（会写坏数据 / 给出虚假保证 / 门禁失效）

### P0-1 `markExpired` 缺状态守卫，`AlarmReconciler` 误以为它是幂等锚点

**位置**：`core/data/dao/DoseSlotDao.kt:313-314`、`core/alarm/AlarmReconciler.kt:89-96`

```kotlin
// DoseSlotDao.kt:313  —— WHERE 子句只有 id，没有任何 status 守卫
@Query("UPDATE dose_slots SET status = 'EXPIRED', actual_taken_ts = NULL, snooze_until_ts = NULL WHERE id = :slotId")
suspend fun markExpired(slotId: Long): Int
```
```kotlin
// AlarmReconciler.kt:90-92
val expiredCount = staleSlots.count { stale ->
    // 幂等锚点：受影响行数为 0 说明已被别的路径结算过，不重复撤闹钟
    if (db.doseSlotDao().markExpired(stale.id) == 0) return@count false
```

KDoc 自称"0 表示已被别的路径结算过（天然幂等）"，但 SQL 的 `WHERE` 只有 `id = :slotId`，
而 `staleSlots` 又是刚从 `getStaleOpenSlots` 查出来的 ⇒ **返回值恒为 1**。
这道"幂等锚点"是虚构的，与同文件 `markCompletedIfOpen` / `markSkippedIfOpen` / `snoozeSlot` / `revertToPending`
四条都把 `status IN (...)` 下沉到 SQL 的风格完全不一致。

**可达路径（真实存在，非理论）**：
1. `AlarmReceiver` 弹出通知后立刻调 `rescheduleAll`；
2. 用户在通知栏点「✅ 确认已吃」→ `DoseActionReceiver` → `takeDose` 事务提交，槽位 `COMPLETED`、事实入库、库存已扣；
3. 与此同时 `MainActivity` onResume / `ReconcileWorker` / 另一次 `AlarmReceiver` 正在跑 `rescheduleAll`，
   其 `getStaleOpenSlots` 在第 2 步之前已把该槽位读进内存；
4. `markExpired` 执行 ⇒ 槽位被改回 `EXPIRED`。

**后果（全部是永久性的）**：
- `dose_records` 里那条 `COMPLETED` 事实仍在 → 消耗统计照算；
- `dose_slots.status = EXPIRED` → 依从率把它算成**漏服**；
- `undoDose` 的守卫是 `status != COMPLETED && status != SKIPPED`（`DoseTrackingService.kt:190`）⇒
  **用户无法撤销这条已扣库存的服药**；
- 库存已经扣了，但界面上是一次"漏服"，用户会去盘点校准，反而把账改错。

**建议**：加 `AND status IN ('PENDING','SNOOZED')`，与 `markCompletedIfOpen` 对称。

---

### P0-2 "灭屏全屏弹窗提醒"开关端到端无效

**位置**：`core/alarm/ReminderSettings.kt:18/29/37/44`、`ui/screen/settings/SettingsViewModel.kt:88-93`、
`ui/screen/settings/SettingsScreen.kt:262-271`、`core/alarm/Notifications.kt:117-205`

写入端与读取端都存在，唯独**消费点不存在**：

| 环节 | 位置 | 状态 |
| :-- | :-- | :-- |
| 写库 | `SettingsViewModel.kt:88-93` | ✅ |
| 读库 | `ReminderSettings.kt:37,44` → `Behavior.fullScreenAlert` | ✅ |
| 传入 | `AlarmReceiver.kt:64-71` | ✅ |
| **使用** | `Notifications.showDoseNotification` 只用到 `shouldSilence`(`:144`) 与 `snoozeMinutes`(`:171`) | ❌ |

全工程 grep `setFullScreenIntent` / `USE_FULL_SCREEN_INTENT` / `FullScreenIntent`：**0 匹配**。
`AndroidManifest.xml` 也没有声明 `USE_FULL_SCREEN_INTENT` 权限（该权限在 Android 14+ 还需要单独申请）。

**后果**：`SettingsScreen` 承诺"锁屏亮屏时弹出全屏服药操作界面"，该行为永不发生。
开关打开与关闭，通知表现完全一致。这是**对用户的功能承诺落空**，而 UI 上没有任何提示。

唯一相关的测试 `ReminderSettingsTest.kt:52` 断言的是 `ReminderSettings.kt:29` 自己声明的默认值（见 P1-14）。

---

### P0-3 "系统特权自检"页全部是硬编码，且按钮是空实现

**位置**：`ui/screen/settings/PermissionCheckScreen.kt:66-101, 142`

```kotlin
// :71-72  精确闹钟
statusText = "已授权", isGranted = true
// :80-81  通知
statusText = "已授权", isGranted = true
// :89-90  Doze 白名单
statusText = "去设置", isGranted = false
// :98-99  厂商自启/锁屏
statusText = "查看指引", isGranted = false
// :141-142
OutlinedButton(onClick = {}, ...)
```

四项状态全部写死；全工程唯一的 `canScheduleExactAlarms()` 出现在 `AlarmScheduler.kt:167`，只用于打日志，
**没有任何 UI 读取**；`isIgnoringBatteryOptimizations` 全工程 0 次出现。

`SettingsScreen.kt:334` 的入口文案是"查看 4 项系统特权自检与保活指引"，而用户点进去看到的是一份静态说明书，
且第 1、2 项**恒显示"已授权"**——即使用户在系统设置里关掉了精确闹钟或通知。

**后果**：漏提醒的整条兜底链路（本项目最严重的用户可见故障方向）恰恰靠这四项，
界面却给出"已授权"的虚假保证，且"去设置"按钮点了没反应。
结合 P0-2，用户在锁屏场景下收不到任何提醒，而 App 告诉他一切正常。

**建议**：第 1 项接 `alarmManager.canScheduleExactAlarms()`，第 2 项接
`NotificationManagerCompat.from(ctx).areNotificationsEnabled()`，两个"去设置"按钮用
`Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM` / `ACTION_APP_NOTIFICATION_SETTINGS` 跳转；
无法程序化检测的厂商开关，把 `isGranted` 语义改成"需手动确认"而不是伪装成检测结果。

---

### P0-4 提醒时点的"剂量"输入框可写入 0 与负数，负数会翻转台账符号

**位置**：`ui/screen/edit/AddEditMedicationScreen.kt:760-769`、`ui/screen/reminder/ReminderSettingsScreen.kt:391-398`

```kotlin
OutlinedTextField(
    value = if (t.dose % 1f == 0f) t.dose.toInt().toString() else t.dose.toString(),
    onValueChange = { viewModel.updateTime(index, dose = it.toFloatOrNull() ?: 0f) },
    label = { Text("剂量") },
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
)
```

这是全 App **唯一没有字符过滤**的数字输入框（对比 `InventoryViewModel.kt:138`、
`ManualDoseViewModel.kt:86`、`RefillViewModel.kt:61` 都有 `filter { it.isDigit() || it == '.' }`）。三个后果：

1. **清空即写 0**：`""` → `?: 0f` → 显示被重写为 `"0"`，输入框无法清空，
   且 0 静默落库（`MedicationAdminService.kt:247` `doseAmount = Dose.of(t.dose).milli` 无 `> 0` 校验）。
   之后通知写「该服用 **0 片** 了」、打卡照常计入依从率。
2. **粘贴负数翻转台账**：`dose = -5f` → `doseAmount = -5000` →
   `DoseTrackingService.takeDose` 的 `changeAmount = -finalDose` 变成 **+5000**，
   即"打卡吃药反而给账面加 5 片"。这直接违反 I1 的语义（I1 是恒等式，守不住符号约定）。
3. **小数剂量在 UI 不可达**：`KeyboardType.Number` 不弹小数点键；即使软键盘能输入，
   `value` 由 `Float` 反向格式化，输 `"0."` 立刻被重写为 `"0"`，小数点消失。
   而 `Dose` 全链路是支持 500 毫单位的（`DoseTest.kt:26`）。

**建议**：`dose` 草稿改为 `String`（与 `defaultDose` / `minStockAlert` 一致），输入层加字符过滤，
提交前 `toFloatOrNull()?.takeIf { it > 0 } ?: 报错`。

---

### P0-5 `StatsUnitGroupingTest` 在测试里复刻了生产逻辑——守的是影子

**位置**：`test/.../StatsUnitGroupingTest.kt:33-37`

```kotlin
/** 与 StatsViewModel 中的分组逻辑保持一致（纯函数，无 Android 依赖） */
private fun groupByUnit(rows: List<Row>): Map<String, Float> =
    rows.groupBy { it.unit }
        .mapValues { (_, items) -> items.sumOf { it.milli } }
        .mapValues { (_, milli) -> Dose(milli).asFloat }
```

生产实现在 `StatsViewModel.kt:137-139`，是 `buildState` 里的**内联代码**，没有任何可调用的生产函数。

**后果**：把 `StatsViewModel` 改回该文件 `:14-18` KDoc 里描述的原 bug
（`doseSums.sumOf { it.totalDose }` 跨单位求和 + 拿 `rankings.first().unit` 当主导单位），
`:40-74` 这 5 条测试**全部保持绿色**。它自称守 P0-7，实际守不住。

这违反了项目自己的规矩——`AlarmIdentityTest.kt:176-181` 明确写着
"必须走生产代码，不能在测试里重写一遍 Uri 构造……守的是一个测试里的影子，不是真正的实现"。

**建议**：把 `byUnit` 的计算从 `StatsViewModel.buildState` 抽成
`StatsEngine.groupByUnit(rows: List<Pair<String, Int>>): Map<String, Float>`，测试改为调它。

---

### P0-6 `fallbackToDestructiveMigration()` 仍在生产代码里（发布阻断项）

**位置**：`core/data/AppDatabase.kt:143`

```kotlin
.fallbackToDestructiveMigration() // TODO(发布前删除)：见上方 KDoc
```

KDoc 里的论证本身是成立的（项目未公开发布，开发期数据可一键重建）。
但它是一个**静默的删库开关**：任何一次 schema 变更忘记升 `version` 或忘记加迁移，
用户的历史服药记录与库存台账会被无声清空，而 App 照常启动、UI 上看不出任何异常。

`AppDatabase.kt:41-55` 的 KDoc 已经把这条风险写得很清楚（`IllegalStateException` / 破坏性回退的覆盖范围），
却和 `:143` 的 `fallbackToDestructiveMigration()` 并存。**同一份代码里既论证"破坏性回退很危险"又打开了它。**

**建议**：在 `build.gradle.kts` 里加一条 release 构建的静态检查（禁止 `fallbackToDestructiveMigration`
出现在 release 变体），或改为 `if (BuildConfig.DEBUG) fallbackToDestructiveMigration() else throw`，
让"发布前必须删掉"这条约定从注释变成**构建期强制**。

---

## 2. P1 —— 高优先级缺陷

### P1-1 统计页不随"跳过"与"非追踪药品打卡"刷新

**位置**：`ui/screen/stats/StatsViewModel.kt:86-95, 110, 117`

```kotlin
val uiState = combine(_selectedPeriod, medDao.observeActiveOverviews()) { period, overviews ->
    buildState(period, overviews)          // 内部是一次性 suspend 查询，不是 Flow
}
```

`buildState` 读的 `slotDao.getSlotStatusCounts`(`:110`) 与 `recordDao.getDoseSumByMedicationInRange`(`:117`)
都是**非 Flow** 的一次性查询。被订阅的 `observeActiveOverviews` 覆盖三张表
（`OVERVIEW_SELECT` 的 `medications` / `inventory_transactions` / `reminder_settings`），
**不含 `dose_slots` / `dose_records`**。

因此：
- `DoseTrackingService.skipDose`(`:143-165`) 从不写台账 → **跳过后统计页不刷新**；
- `takeDose` 的 `if (medication.isStockTracked)`(`:127`) → **对未开启库存追踪的药打卡也不刷新**；
- `logManualDose(deductStock = false)` → 同样不刷新。

对比同类的 `ProgressViewModel.kt:80-83` 用的是 `observeSlotStatusCounts`（真 Flow），两页行为不一致。

**无任何测试**：`StatsViewModel` 在测试目录出现 0 次（唯一命中是 `StatsUnitGroupingTest.kt:33` 的一句注释）。

---

### P1-2 进展页把"今天"冻结在 ViewModel 的构造时刻

**位置**：`ui/screen/progress/ProgressViewModel.kt:74-75`

```kotlin
private val today: LocalDate = LocalDate.now()
private val weekDates = remember7Days()      // :147-148，同样是构造期算一次
```

两者是 VM 的**构造期属性**，而 ViewModel 生命周期跨配置变更与后台常驻。跨零点后：
- `:85` `observeSlotsForDate(today...)` 绑定固定日期 → "今日服药流水"永远停在昨天；
- `:97` `if (d == today) "今日"` 不再有"今日"高亮；
- `:98` `isFutureDay = d.isAfter(today)` 判定失真。

对照 `StatsViewModel.kt:101` 在 `buildState` 内部 `LocalDate.now()` 是正确写法。
两个 VM 对同一件事的处理不一致。

---

### P1-3 详情页的"预计可用天数"算法与库存页不同，且部分配置下退化为无穷大

**位置**：`ui/screen/detail/MedicationDetailViewModel.kt:135-145`

```kotlin
private fun scheduledDosesPerWeek(policy: SchedulePolicyEntity?, timesCount: Int): Int =
    when (policy?.policyType) {
        PolicyType.DAILY -> 7
        PolicyType.INTERVAL -> {
            val n = (policy.intervalDays ?: 2).coerceAtLeast(1)
            Math.round(7.0 / n).toInt()                 // ⚠️ 没有 coerceAtLeast(1)
        }
        PolicyType.DAYS_OF_WEEK -> policy.daysOfWeek.size
        PolicyType.CYCLE -> 5                          // ⚠️ 写死
        PolicyType.PRN, null -> 0
    }.let { days -> if (timesCount == 0) 0 else days.coerceAtLeast(0) }
```

对照已修好的 `InventoryViewModel.kt:238-243`（其 KDoc `:235-237` 明确写着"原先写死 5 …会把日均消耗高估 20 倍"）：

| 缺陷 | 触发条件 | 后果 |
| :-- | :-- | :-- |
| `CYCLE -> 5` 写死 | "吃 2 停 6"这类配置 | 详情页把日均消耗高估 20 倍 → "约可用 2 天"（假告警），库存页给的是正确值 |
| `Math.round(7.0/n)` 无下界 | `intervalDays ≥ 15`（UI 允许到 30） | 返回 0 → `calculateStockRunwayBySchedule`(`StatsEngine.kt:236`) 落入 `calculateStockRunway(stock, 0f, ...)` → 返回 `Int.MAX_VALUE` 且 `isAlert = stock <= minStockAlert && minStockAlert > 0` ⇒ **账面为负都不告警** |

**同一个 bug 在两个 VM 里只修了一半**，而 `InventoryViewModel` 那侧有 KDoc 却没有把结论同步过来。

---

### P1-4 "低库存"在 4 个屏幕上是 4 套口径，补药页还缺两个守卫

| 屏幕 | 判据 | 位置 |
| :-- | :-- | :-- |
| 今日页 | `isStockTracked && minStockAlert > 0f && stock <= minStockAlert` | `TodayViewModel.kt:139` |
| 药箱页 | `stock <= alert && alert > 0f` | `CabinetScreen.kt:365` |
| 详情页 / 库存页 | 引擎 `currentStock <= minStockAlert \|\| runwayDays <= 7` | `StatsEngine.kt:96` |
| 补药页 | `stock <= Dose(med?.minStockAlert ?: 0).asFloat` | `RefillScreen.kt:110` |

补药页这条**两个守卫都没有**：对未开启库存追踪的药（`minStockAlert` 默认写 10、账面 0）
会渲染成 `error` 红色并显示"预警线: 10 片"——对一个根本没在追踪库存的药说"低于预警线"。

而"剩 3 片、每天 1 片"的药，详情页/库存页判"告急"（琥珀色 + "仅够约 3 天"），今日页与药箱页不显示任何提示。

根因是 `StatsEngine.calculateStockRunway` 的两条分支本身就不一致（见 P2-3），页面又各自复制了一遍。

---

### P1-5 药箱页的 INTERVAL 频次文案与引擎、库存页互相矛盾

**位置**：`ui/screen/cabinet/CabinetViewModel.kt:129`

```kotlin
val intervalText = if (policy.intervalDays <= 2) "隔天" else "每隔 ${policy.intervalDays - 1} 天"
```

引擎语义（`SlotProjectionEngine.kt:145-149`，不变�� I7）是 **`intervalDays` = 周期天数**：
2 = 每 2 天（隔天），3 = 每 3 天。于是：
- `intervalDays = 3` → 药箱页显示"每隔 **2** 天"，引擎实际每 3 天一次；
- `intervalDays = 1`（合法输入，UI 允许 `coerceIn(2,30)` 但备份导入可写入 1）→ 药箱页显示"隔天"，引擎按**每天**。

`InventoryViewModel.kt:258-283` 已经把同一件事修好并写了 KDoc 说明（"语义统一到引擎口径（不变��� I7）"），
`CabinetViewModel` 漏改。两个页面显示的频次互相对不上。

---

### P1-6 `reconcileSchedule` 逐药全表扫，整体退化为 O(N²)

**位置**：`core/domain/service/DoseTrackingService.kt:432-434`、`core/alarm/AlarmReconciler.kt:135-143`

```kotlin
// AlarmReconciler：循环每个药调一次
for (med in schedulableMeds) { tracking.reconcileSchedule(medicationId = med.id, ...) }

// DoseTrackingService.reconcileSchedule：每次都把窗口内**所有药**的槽位全查出来再过滤
val existing = slotDao.getSlotsInRange(startDate = fromStr, endDate = toStr)
    .filter { it.medicationId == medicationId }
```

`getSlotsInRange`（`DoseSlotDao.kt:92-93`）**没有 `medication_id` 过滤**。
50 个药 × 14 天 × 3 时点 = 2100 行，`reconcileSchedule` 被调 50 次 ⇒ 单次 `rescheduleAll`
反序列化 10.5 万行。

而 `rescheduleAll` 的触发点极其频繁：
- `MainActivity.kt:40-48` **每次 onResume**；
- `AlarmReceiver.kt:76` **每次闹钟响铃后**（响铃本身又会产生新的闹钟）；
- `ReconcileWorker` 每 15 分钟；
- `BootReceiver` 开机；
- 外加 6 个 ViewModel 的每个写操作后。

**叠加风险**：`AlarmReceiver` / `BootReceiver` 用 `goAsync()` 拉起协程，广播的
pending-result 预算约 10 秒；`DoseActionReceiver` 同样。O(N²) 的重排在药量增长后
可能超出预算，被系统判定为广播未及时 `finish()`。

**建议**：`getSlotsInRange` 增加 `medication_id` 参数（或加一条
`getSlotsInRangeForMedication(medId, from, to)` 走现有 `Index(medication_id)`）。

---

### P1-7 已逾期（EXPIRED）槽位在今日页无法补打卡，提示语还写错了

**位置**：`ui/screen/today/TodayViewModel.kt:131`、`ui/screen/today/TodayScreen.kt:551-565`

```kotlin
// TodayViewModel：EXPIRED 与 PENDING 一起进 pendingItems
SlotStatus.PENDING, SlotStatus.SNOOZED, SlotStatus.EXPIRED -> pending.add(item)
```
今日页对 `pendingItems` 里的每一项都渲染「✓ 确认服药」按钮 →
`takeDose` → `markCompletedIfOpen` 的守卫是 `status IN ('PENDING','SNOOZED')`
（`DoseSlotDao.kt:116`）⇒ 受影响行数 0 ⇒ 返回 false ⇒ 弹出
**「该服药记录已处理过，未重复扣减库存」**。

这条消息完全误导：槽位不是"已处理过"，而是"过了 2 小时宽限期后不允许再补打卡了"。
用户点 ✓ 没反应、拿到一句驴唇不对马嘴的提示，只能改走"手动补录"。

（对比：同页的「跳过」走 `markSkippedIfOpen`，守卫含 `EXPIRED`（`DoseSlotDao.kt:126`），
所以跳过可以、确认服药不行——这个不对称在 UI 上没有任何体现。）

---

### P1-8 5 个 ViewModel 的保存路径无 `try/catch` ⇒ 崩溃或按钮永久卡死

**位置**：`ManualDoseViewModel.kt:149-164`、`InventoryViewModel.kt:181-189/203-216`、
`ReminderSettingsViewModel.kt:219-260`、`AddEditMedicationViewModel.kt:300-369`、`RefillViewModel.kt:91-114`

```kotlin
// ManualDoseViewModel.kt:153-160  launch 内无任何异常处理
trackingService.logManualDose(medicationId = med.id, ...)
```
而领域层是会抛的：
- `DoseTrackingService.kt:247-248` `throw IllegalArgumentException("Medication not found")`；
- `MedicationAdminService.kt:180-185 / 197-199 / 205` 三处 `check(...)`；
- `MedicationAdminService.kt:119` `require(name.isNotEmpty())`。

`ManualDoseUiState.medications` 是 init 时的一次性快照（`:57`），表单开着的时候在详情页删掉该药
是完全可达的 ⇒ 此后点"保存服药记录" ⇒ 未捕获异常从 `viewModelScope.launch` 抛到主线程 ⇒ **直接崩溃**。

较轻的一条：`isSaving` 停在 `true` ⇒ 对应屏幕的保存按钮永久禁用且无任何错误提示
（`InventoryScreen.kt:101`、`ReminderSettingsScreen.kt:115`）。

**建议**：统一 `runCatching { } catch (e: Exception) { _uiState.value = ...copy(isSaving = false, error = e.message) }`。

---

### P1-9 疗程结束日：开关打开但不选日期 ⇒ 开关自己弹回 / 旧值静默保留

**位置**：`core/domain/service/MedicationAdminService.kt:232-236`、
`ui/screen/reminder/ReminderSettingsViewModel.kt:153-155, 231-232`

```kotlin
endDate = when {
    draft.clearEndDate -> null
    draft.endDate.isNullOrBlank() -> previous?.endDate   // ← 沿用旧值
    else -> draft.endDate
}
```
```kotlin
// ViewModel
endDate = if (s.hasEndDate) s.endDate else null,
clearEndDate = !s.hasEndDate,
```

`onHasEndDateChange(true)`(`:153-155`) 只置位 `hasEndDate`，**不给默认日期**。于是：

- 原本**没有**结束日：保存后 `endDate` 仍为 null → `load()` 的 `hasEndDate = policy?.endDate != null`
  读回 `false` ⇒ **开关自己弹回去**，用户以为没保存成功；
- 原本**有**结束日：字段显示"未设置"（因为 `:115 hasEndDate` 已被 UI 清空），
  但库里仍按旧日期停提醒 ⇒ **UI 与库状态不一致，且无任何提示**。

---

### P1-10 覆盖式恢复后旧闹钟不取消；Receiver 以 extras 的 `slotId` 为准 ⇒ 可能给错药发提醒

**位置**：`core/alarm/AlarmReconciler.kt:79, 146-160`、`core/alarm/AlarmScheduler.kt:117-128`、
`core/alarm/AlarmReceiver.kt:35-53`、`core/data/DataExporter.kt:509-642`

`rescheduleAll` 的孤儿清理**只能覆盖"DB 里存在的槽位"**：
```kotlin
val snapshot = db.doseSlotDao().getOpenSlots().map { it.identity() }.toSet()   // :79 快照来自 DB
...
for (id in snapshot) { val slot = stillOpen[id.slotId]; if (!shouldKeep) id.cancelAll(context) }  // :149
```
而闹钟身份是 `(medId, date, time, kind)`，**不是 `slot.id`**。
一次覆盖式恢复把 `dose_slots` 整表清空再按备份回填，**恢复前的闹钟全部不在快照里 ⇒ 永远不会被取消**。

这些残留 PendingIntent 到点会触发 `AlarmReceiver`。此时：
```kotlin
val slotId = intent.getLongExtra(AlarmScheduler.EXTRA_SLOT_ID, -1L)   // :35 取 extras，不是 Uri
val slot = db.doseSlotDao().getSlotById(slotId)                       // :48
if (slot == null || status not in (PENDING, SNOOZED)) return@launch
```

Room 用 `AUTOINCREMENT`，`sqlite_sequence` 不会被 `DELETE` 重置 ⇒ 新 id 恒大于历史最大 ⇒
`slot == null`，只是**白白唤醒设备**，无用户可见影响。

但存在一条**真实可达**的错配路径：恢复的备份来自更早的时点，其 slot id 空间是当前库的**子集**。
残留闹钟的 extras 里的 `slotId = 150` 在恢复后**确实存在**，但指向的是另一个 `(medId, date, time)`。
只要那个槽位恰好还是 `PENDING`，就会在错误的时间为错误的药品弹出一条服药提醒。

**根因**：`AlarmScheduler` 的 KDoc（`:70`）说得很清楚——"用业务身份而不是 `slot.id`，因为 `slot.id` 恰恰是会变的那个"——
但 `AlarmReceiver` 却仍然以 `slot.id` 为准去查库，**身份定义与身份使用不一致**。

**建议**：`AlarmReceiver` 从 `intent.data`（Uri）解析 `(medId, date, time, kind)`，
按业务键反查槽位（新增一条 `getOpenSlotByKey(medId, date, time)`），extras 只作快速路径与校验。

---

### P1-11 `setStockTracking` 的"从零建档"分支把 target 当 delta 写

**位置**：`core/domain/service/DoseTrackingService.kt:314-335`

```kotlin
val current = balanceOf(medicationId)                    // 可以为负（D-9 明确允许）
val target = initialStock?.let { Dose.of(it) } ?: Dose(current)
if (target.milli > 0 && current <= 0) {
    // 从零建档：一条流水即可，账面随之成立
    appendLedger(..., changeAmount = target, ...)         // ⚠️ 写的是 target，不是 target - current
} else if (target.milli != current) {
    appendLedger(..., changeAmount = Dose(target.milli - current), ...)
}
```

第一个分支的注释与条件都写的是"从零建档"，但条件是 `current <= 0` 而不是 `current == 0`。
当 `current = -2000`（超扣，D-9 允许的状态）且调用方给了 `initialStock = 30` 时：
账面变成 `-2000 + 30000 = 28000`，而用户要的是 30000 ⇒ **账面凭空少 2000**。
`else` 分支算的是 `target - current`，两条分支口径不一致。

**当前可达性**：生产代码里两处调用都恰好绕开了它——
`InventoryViewModel.setTracking` 传的是 `currentStock`（= 账面本身，`target == current`），
`RefillViewModel` 传 `null`，`AddEditMedicationViewModel` 只对新建药（账面恒 0）调用。
所以**目前不可达，是潜伏缺陷**。但它守的是"负库存"这条刚被 P0-1 那类竞态污染过的状态，
一旦将来有第二条 `setStockTracking(enabled, initialStock)` 调用点就会立刻变成数据错误。

**建议**：把条件改成 `current == 0`，负余额一律走 `else` 分支的 `target - current`。

---

### P1-12 `getActivePolicyForMedication` 无 `ORDER BY`，备份可恢复出多条活跃策略

**位置**：`core/data/dao/SchedulePolicyDao.kt:30-31`

```kotlin
@Query("SELECT * FROM schedule_policies WHERE medication_id = :medicationId AND is_active = 1 LIMIT 1")
suspend fun getActivePolicyForMedication(medicationId: Long): SchedulePolicyEntity?
```

`LIMIT 1` 无 `ORDER BY` ⇒ 命中哪一行由 SQLite 自行决定。
正常路径下 `savePolicyWithTimes`(`:54-61`) 会先 `deactivatePoliciesForMedication` 再插新的，
所以"同一药品至多一条 is_active = 1"是**只由调用顺序维持的隐式不变量**，没有任何数据库层保障。

而 `DataExporter.validateBackup`（`:400-502`）**不检查"同一药品是否有多条 is_active=1 的策略"**。
一份手工编辑过、或来自历史缺陷版本的备份可以带进两条活跃策略 ⇒
`getActivePolicyForMedication` 的返回**不确定** ⇒ 每次 `reconcileSchedule` 可能选中不同的策略
⇒ 同一个药在两次打开 App 之间排班不同，且**没有报错**。

**建议**：SQL 加 `ORDER BY version DESC, id DESC LIMIT 1`（版本号已存在，`SchedulePolicyEntity.version`），
并在 `validateBackup` 里加一条"同药品多条活跃策略"的检查。

---

### P1-13 库存页：`load()` 会静默清空用户已输入的盘点值；非法预警线静默回退仍提示"已保存"

**位置**：`ui/screen/inventory/InventoryViewModel.kt:128, 170, 205, 214`

```kotlin
calibrateInput = if (stock > 0f) fmt(stock) else ""    // :128 load() 里重建
...
val alert = s.minStockAlertInput.toFloatOrNull() ?: s.minStockAlert   // :205 非法输入静默回退
medDao.updateMinStockAlert(medId, Dose.of(alert).milli)
_uiState.value = ...copy(message = "已保存")                          // :213 仍然提示成功
load()                                                               // :214 顺带清空盘点框
```

- 用户在盘点框填好 30 → 点顶栏「保存」或拨一下库存追踪开关（`:170` 也会 `load()`）→ **输入被清空**，
  且没有任何提示。同屏三种持久化时机（有效期=显式保存 / 追踪开关=立即生效 / 盘点=立即生效）混用加剧了这点。
- `minStockAlertInput` 的过滤器（`InventoryScreen.kt:263`）允许多个小数点（`"1.2.3"`），
  `toFloatOrNull()` 返回 null ⇒ 静默写旧值，同时**提示"已保存"**，且卡片上显示的也是旧值
  ——输入框显示 `1.2.3`、卡片显示旧值、还提示成功。

对比同页的盘点框（走 `calibrate`）至少有 `InventoryViewModel.kt:177-179` 的显式报错。

---

### P1-14 测试门禁的恒真断言 / 假响应式（4 处）

这一组单独立项，因为它们共同的效果是**门禁看似全绿、实际守不住任何东西**。

| # | 位置 | 问题 |
| :-- | :-- | :-- |
| a | `SlotProjectionEngineProperties.kt:83-94` | I6 断言"同参数两次调用结果相同"。`projectSlots` 是**纯函数**（无 I/O、无状态、无时间依赖），这条性质实现怎么坏都通过。真正有价值的 I6 在 `ReconcileScheduleTest.kt:86-99`（slot id 逐一相同）与 `AlarmReconcilerIdempotencyTest.kt:97-111` |
| b | `AlarmReconcilerIdempotencyTest.kt:317-324` | `assertThat(openSlotsOf(medId).size).isAtMost(before)` —— **若归档过滤被整个删掉**，`size == before` ⇒ `isAtMost` 成立 ⇒ 全绿。同文件 `:326` 的 `isNotEmpty()` 才是正确写法；`:201` 的 `assertThat(getAllSlots().any { it.medicationId != medId }).isTrue()` 在两味药固定夹具下也恒真 |
| c | `StatsDaoAggregationTest.kt:138/148` | 名为 `slotStatusCounts_flowEmitsOnChange`，实现是 `.first()` 前后各查一次。Room 的 cold Flow 每次 `first()` 都**重新执行查询** ⇒ 即使 `InvalidationTracker` 被彻底禁用也必然绿。**讽刺之处**：这正是 P1-1 那个"统计页不随跳过刷新"缺陷本该被拦住的地方——缺陷存在，而这条测试是绿的 |
| d | `ReminderSettingsTest.kt:52` | `assertThat(ReminderSettings.Behavior().fullScreenAlert).isTrue()` —— 断言的是 `ReminderSettings.kt:29` 自己声明的默认值。只能捕获"有人改了 data class 默认值"，无法捕获"这个字段根本没被消费"——而 P0-2 正是后一种 |

**建议**：c 应改为 `first { predicate }` 或 turbine 式的挂起收集；
b 改为 `isEmpty()`；a 删除或明确标注为冒烟；d 改为断言"消费点存在"（例如
`Notifications.showDoseNotification` 产出的 Notification 上 `fullScreenIntent != null`）。

**另有一类恒真断言**（影响较小，一并列出）：
`ReconcileScheduleTest.kt:155`（找到 id 相等的元素再断言 id 相等）、
`BackupResilienceTest.kt:190` 与 `BackupRoundTripTest.kt:296`（断言自己的夹具非空）、
`DaoUnitScaleTest.kt:136-137`（上一行已精确断言）、`AlarmIdentityTest.kt:65`（对常量表做算术自证）、
`AddEditLogicTest.kt:36-50/52-61/84-96`（测 `Int.coerceIn`、在测试里重写 toggle 逻辑、
"五种频次都可达"其实只做了 `copy(policyType = type)`）。

---

### P1-15 备份的关键承诺与本机备份链路零测试

| 承诺 | 位置 | 测试覆盖 |
| :-- | :-- | :-- |
| "覆盖前会自动保存一份当前数据的快照" | `SettingsScreen.kt:94`、`SettingsViewModel.kt:241-243` | `snapshotFile` 在测试目录 **0 命中** |
| "从本机备份恢复"整条入口 | `SettingsViewModel.kt:183-210` → `DataExporter.listLocalBackups`(`:734`) / `inspectLocalBackup`(`:760`) / `importLocalBackup`(`:813`) | **0 命中**。`importBackup`（Uri 路径）测得很充分，`File` 路径完全没测 |
| 全局 `snooze_minutes` → 通知栏按钮的连线 | `ReminderSettings.resolve`(`:33-46`) → `Notifications.kt:171` | `resolve` 这个 IO 函数**零覆盖**——而它的 KDoc（`:9-12`）正是为"设置没被消费"这个历史缺陷而写的 |

`ReminderSettingsIsolationTest.kt:83/144/242` 用**硬编码的 `"2026-12-31"`** 当暂停结束日，
随后断言 `isPausedOn(LocalDate.now())` 为 true。今天是 2026-09-29，**2027-01-01 起这三条会假红**。
同类但安全的写法在同项目里已有先例（`FieldPreservationInvariantTest.kt:85-91` 特意注释说明"必须取一个已经过去的日子"）。

---

## 3. P2 —— 中优先级

### P2-1 剂量的双真相：`medications.default_dose` 与 `policy_times.dose_amount`

`default_dose` 与 `policy_times.dose_amount` 各存一份"单次剂量"，**没有任何同步关系**：
- 编辑药品信息页改 `defaultDose`（`AddEditMedicationViewModel.kt:325`）不会更新已有的 `policy_times`；
- 改提醒设置页的时点剂量（`:247`）也不会更新 `defaultDose`；
- `ManualDoseViewModel.kt:68/79` 用 `defaultDose` 预填补录表单；
- `AddEditMedicationViewModel.spreadTimes`(`:241`) 用 `defaultDose` 铺排新时点，
  而 `ReminderSettingsViewModel.spreadTimes`(`:190`) **写死 `dose = 1.0f`**。

结果：同一屏里"默认单次剂量 2 片"，点"每天三次"铺排出 1 片的时点（提醒页），
"添加药品"页却铺排出 2 片（`:241`）。两处都自称是"一键铺排"。

**建议**：`defaultDose` 明确降级为"新时点的默认剂量"并在两处共用同一取值来源；
或者直接删掉 `defaultDose`，铺排时统一读当前首个时点的剂量。

---

### P2-2 提醒时点允许重复，`insertAll(IGNORE)` 静默丢弃 + 日均消耗翻倍

**位置**：`core/domain/engine/SlotProjectionEngine.kt:98-118`、`core/data/dao/DoseSlotDao.kt:31-32`、
`ui/screen/inventory/InventoryViewModel.kt:89`

`saveReminderPolicy`（`MedicationAdminService.kt:241-251`）对 `draft.times` 只做 `sortedBy { it.time }`，
**不去重**；`projectSlots` 也不去重，直接为每个 `time` 生成一个 `DoseSlotEntity`。
用户不小心录入两个相同 `timeOfDay`（UI 无校验）会：

1. `projectSlots` 产出两个 `(medId, date, time)` 相同的槽位 →
   `insertAll` 的 `OnConflictStrategy.IGNORE` **静默丢弃一个**，无任何提示；
2. `InventoryViewModel.kt:89` `times.sumOf { it.doseAmount }` 把重复项算两次 ⇒ **日均消耗翻倍 ⇒ 可用天数减半**；
3. 药箱页显示「每天 2 次 (08:00, 08:00)」。

槽位唯一性（I5）本身是**守住的**（DB 唯一约束兜底），但静默丢弃 + 展示与计算不一致是真实的。

**建议**：`saveReminderPolicy` 对 `times` 按 `time` 去重（保留最后一条），
`InventoryViewModel` 改用 `times.distinctBy { it.timeOfDay }`。

---

### P2-3 `StatsEngine.calculateStockRunway` 两条分支的告警口径不一致

**位置**：`core/domain/engine/StatsEngine.kt:90-97`

```kotlin
if (dailyEstimatedConsumption <= 0f) {
    val isAlert = currentStock <= minStockAlert && minStockAlert > 0f
    return Pair(Int.MAX_VALUE, isAlert)
}
val runwayDays = (currentStock / dailyEstimatedConsumption).toInt().coerceAtLeast(0)
val isAlert = currentStock <= minStockAlert || runwayDays <= 7
```

- `minStockAlert = 0`（本项目语义是"关闭低库存告警"）时，`> 0f` 守卫让第一条分支**永不告警**，
  第二条分支却仍会因为 `stock <= 0`（`0 <= 0`）告警 —— **开不关闭由"有没有消耗"决定**；
- 日消耗为 0 时跳过 7 天规则，日消耗 > 0 时启用 —— **切换一下排班就改变告警行为**；
- 这正是 P1-4"四套口径"里详情页/库存页那一套的来源。

---

### P2-4 统计页的 `buildState` 忽略入参、回头读共享状态

**位置**：`ui/screen/stats/StatsViewModel.kt:157`

```kotlin
selectedPeriod = _selectedPeriod.value.ordinal,   // 而不是入参 period.ordinal
```

`period` 入参（`:98`）只被用于 `:103` 算 `startDate`。`buildState` 是 suspend 且中间夹两次 DB 查询，
用户在查询期间切换周期 ⇒ **数字按旧周期、标题与分段按钮选中态按新周期**。修法一行。

---

### P2-5 统计页"依从率"与"计划次数/在服药品数"来自不同药品集合

- 依从率来自 `slotDao.getSlotStatusCounts`（`DoseSlotDao.kt:240-254`），SQL **不过滤 `is_archived`**
  ⇒ 包含已归档药品的历史槽位；
- 排行榜在 `StatsViewModel.kt:122-123` 过滤到 `medById`（只含 active）；
- `activeMedCount` 在 `:170` = `overviews.size`（只含 active）。

`StatsScreen.kt:154` 把它们并列成一句 `"共 N 次计划 · Y 种在服药品"`。归档一个药之后，
这句话的两个数字来自不同集合，不再自洽。

---

### P2-6 药箱/详情页的"库存由少到多"排序把不追踪库存的药排到最后

**位置**：`ui/screen/cabinet/CabinetViewModel.kt:70-72`

```kotlin
CabinetSortOrder.STOCK_LOW -> sortedBy { if (it.medication.isStockTracked) it.stock else Float.MAX_VALUE }
```

用 `Float.MAX_VALUE` 当哨兵，在**负库存**（D-9 允许）场景下语义尚可，但这是哨兵值而非真实排序键，
且 `Float.MAX_VALUE` 与真实余额无法区分。建议改为 `sortedBy` 配 comparator + `null` 分组。

---

### P2-7 `pauseDescription(LocalDate.now())` 依赖墙上时钟，但没有任何时间驱动的刷新

**位置**：`ui/screen/cabinet/CabinetScreen.kt:342`、`ui/screen/detail/MedicationDetailScreen.kt:146`

`isPausedOn` 是按当天日期**派生**的，而 `CabinetViewModel`（`:87-101`）的 StateFlow 只在 DB 变化时发射。
App 跨过恢复日边界后不重启，药箱/详情页会一直显示"提醒已暂停，明天恢复"，而闹钟其实已经恢复。
**派生量的显示需要时间源驱动重算，这条在全项目没有统一机制。**

---

### P2-8 `remember` 写在 `LazyColumn` 的 `item {}` 里，滚出视口即丢状态

| 位置 | 状态 |
| :-- | :-- |
| `AddEditMedicationScreen.kt:331` | `var customTag by remember { mutableStateOf("") }` —— **用户输入到一半的"自定义注意事项"标签，滚一下再回来就没了** |
| `CabinetScreen.kt:91` | `var sortExpanded by remember {...}` |
| `AddEditMedicationScreen.kt:461` / `ReminderSettingsScreen.kt:621` | 下拉菜单的 `expanded`（正展开时滚一下就消失） |
| `SettingsScreen.kt:220-221` | `snoozeOptions` 每次重组新建 + `remember` 写在 item 内 |

Compose 官方明确不建议在 lazy item 里用普通 `remember` 承载用户输入；`rememberSaveable` 至少能过配置变更。

---

### P2-9 新增药品的"逾期警告"文案与 ViewModel 实际行为相反

**位置**：`ui/screen/edit/AddEditMedicationScreen.kt:790-802` vs `AddEditMedicationViewModel.kt:309-313`

```kotlin
// 屏幕：任一时点已过就提示
val pastSlots = uiState.timeSlots.filter { ... m < nowMinutes }
if (pastSlots.isNotEmpty()) { Text("...已早于当前时间，今天这一剂会直接记为逾期...") }
```
```kotlin
// ViewModel：全部时点已过才顺延到明天
if (s.timeSlots.all { it.isBeforeNow() }) tomorrowDate() else s.startDate
```

FULL 模式下表单不渲染起始日字段（`startDate` 恒为今天）。最常见场景——晚上 22:00 用默认 08:30 录药——
命中的正是"全部已过"分支：**VM 会静默把起始日推到明天，今天这一剂根本不会被排出来**，
而屏幕正在告诉用户"今天这一剂会直接记为逾期"。用户按提示去改时间点，方向完全反了。

（"任一 vs 全部"在 3 个时点 1 过 2 未过时是对的：VM 不顺延，08:30 确实会逾期。文案只在全过时这一种情况下错。）

---

### P2-10 提醒行为的上界在 UI 与 ViewModel 之间不一致

| 项 | UI 约束 | ViewModel | 领域层 |
| :-- | :-- | :-- | :-- |
| `advanceMinutes` | `canInc = ... < 60`（`ReminderSettingsScreen.kt:454`） | `coerceIn(0, 120)`（`:203`） | `coerceAtLeast(0)`（`MedicationAdminService.kt:184`） |
| `snoozeMinutes` | 步长 5、`canDec = > 5`（`:443`） | `coerceIn(0, 120)`（`:202`） | 同上 |

经备份导入（`BackupFormat.kt:91`）写入 90 分钟后，本页显示 90 且「+」永久禁用，领域层却认为 120 合法。
`snoozeMinutes` 还有一个映射陷阱：`:243` 把 30 存成 0（"跟随全局"），一旦值被压到 0，
`load()` 的 `:122` `if (it == 0) 30 else it` 会又变回 30 ⇒ **用户设的 5 分钟会跳回 30**。

---

### P2-11 设置页把两个取值域不一致的"推迟时长" UI 放在一起

- 全局：`SettingsScreen.kt:220` `listOf(5, 10, 15, 30, 60, 120)`（6 个离散值）
- 药品级：`ReminderSettingsScreen.kt:443-446` 步进 5、范围 `5..120` ⇒ 可写入 25/35/45/55…

药品级能写入全局下拉里**一个都没有**的值。此时 `SettingsScreen.kt:229` 显示"25 分钟"，
下拉展开后没有任何项处于选中态，用户会以为自己没设置成功。

---

### P2-12 `AppConverters` 对未知枚举值静默降级为默认值

**位置**：`core/data/converter/AppConverters.kt:18-40`

```kotlin
fun toSlotStatus(value: String?): SlotStatus? =
    value?.let { runCatching { SlotStatus.valueOf(it) }.getOrDefault(SlotStatus.PENDING) }
```

枚举一旦改名，库里所有历史行**静默变成 `PENDING`** ⇒ 已完成的槽位变回待服 ⇒ 闹钟重新排上、
今日清单出现幽灵待办、且无任何报错。`AGENTS.md` §6 只要求"检查 `when` 是否穷尽"，没覆盖这条读路径。

**建议**：至少 `Log.w` 出来；更好的做法是读路径不做降级（让问题响），降级只放在导入路径上。

---

### P2-13 `MIGRATION_1_2` 建的索引与当前 schema 冲突，且实际是死代码

**位置**：`core/data/AppDatabase.kt:104-107`

```kotlin
db.execSQL("CREATE INDEX IF NOT EXISTS index_dose_slots_medication_id_scheduled_date_scheduled_time " +
    "ON dose_slots (medication_id, scheduled_date, scheduled_time)")
```

`DoseSlotEntity.kt:47-50` 声明的是**同名 UNIQUE 索引**。同名不同定义 ⇒ 一旦这条迁移被执行，
Room 的 schema 校验必然失败。且因为 `version = 5` 且只注册了 `MIGRATION_1_2`，
2 → 5 会走 `fallbackToDestructiveMigration` ⇒ **这条迁移实际上永远不会被执行**（死代码）。

留着它的风险是：将来发布前补齐迁移时，如果照抄这段 SQL，就会造出一个"看起来在保护数据、
实际与 schema 校验冲突"的假保障。建议直接删除。

---

### P2-14 进展页每槽位单发一次查询，而结果根本没人用

**位置**：`ui/screen/progress/ProgressViewModel.kt:124-132`

```kotlin
record = if (slot.status == COMPLETED) { recordDao.getRecordBySlotId(slot.id) } else null
```

`TimelineItem.record`（`:45`）在 `ProgressScreen.kt` 中**从未被读取**（grep 确认，
`TodayTimelineCard` 只用 `slot` 与 `medication`）。而 `combine`（`:77-87`）有 4 个上游，
任一变化都会重跑这段 N 次查询。

顺带：`getRecordBySlotId` 是 `ORDER BY id ASC LIMIT 1`（`DoseRecordDao.kt:42`），
在"打卡 → 撤销 → 再打卡"之后取到的是**最早那条已 `REVERTED`** 的记录。
`TodayViewModel.kt:111` 已经改用 `getCompletedRecordsForSlots` 修好了这个问题，
`ProgressViewModel` 漏改（当前因 `record` 无人使用而未暴露）。

---

### P2-15 CSV 导出未防公式注入

**位置**：`core/data/DataExporter.kt:247-250`

```kotlin
private fun escapeCsv(value: String): String =
    if (value.contains(',') || value.contains('"') || value.contains('\n')) { ... } else value
```

`note` / `batchNumber` / 药品名都是用户可输入字段。以 `=` / `+` / `-` / `@` 开头的值
会被 Excel / WPS 当作公式执行（CSV Injection）。这是导出的服药明细与库存流水，
用户会在电脑上打开。

**建议**：对非数字列，若首字符是 `= + - @` 则前置单引号。

---

### P2-16 `BackupProblemKind` 的 KDoc 错位

**位置**：`core/data/DataExporter.kt:82-101`

`MISSING_REMINDER_SETTINGS` 的 KDoc（`:82-87`，"某个药品缺 `reminder_settings` 行…"）
被写在了 `DANGLING_SLOT_REF`（`:101`）的正上方，而 `MISSING_REMINDER_SETTINGS` 本身
在 `:116` 没有任何文档。结果是 `DANGLING_SLOT_REF` 顶着另一个枚举的说明 ——
读代码的人会把"药品缺提醒设置放行，因为恢复时会补"错当成"悬空 slot 引用放行"的理由。
纯文档缺陷，但正好落在这个文件的判据表上。

---

### P2-17 `AppConverters` 的 `String` 列表分隔符可被用户输入破坏

**位置**：`core/data/converter/AppConverters.kt:43-48`

```kotlin
fun fromStringList(list: List<String>?): String? = list?.joinToString(separator = "|||")
fun toStringList(data: String?): List<String> =
    if (data.isNullOrEmpty()) emptyList() else data.split("|||")
```

`precautions` 来自 `AddEditMedicationViewModel.onPrecautionAdd`（`:199-205`）的**任意用户文本**。
用户输入含 `|||` 的注意事项会在往返（导出 → 恢复、Room 写 → 读）后被拆成多条。
建议改用 JSON 数组或转义分隔符。

---

### P2-18 四个 Receiver 缺统一的异常兜底

| 位置 | 现状 |
| :-- | :-- |
| `AlarmReceiver.kt:78-82` | `catch (t: Throwable)` ✅ |
| `BootReceiver.kt:48-52` | `catch (t: Throwable)` ✅ |
| `DevDataReceiver.kt:54-58` | `catch (t: Throwable)` ✅ |
| `DoseActionReceiver.kt:94-96` | **只有 `finally { result.finish() }`，无 catch** ❌ |

`DoseActionReceiver` 里 `tracking.takeDose` / `snoozeDose` / `skipDose` 都可能抛（Room 事务、
`DoseTrackingService.kt:248` 的 `throw`）。异常逃出协程 ⇒ 广播无 `finish` 之外的清理路径，
用户点「确认已吃」后既没有 Toast 反馈，通知也不会被取消（`:51` 在异常点之后）。

---

## 4. P3 —— 文档失真与死代码

### 4.1 死函数 / 死常量

| 位置 | 说明 |
| :-- | :-- |
| `DoseTrackingService.kt:494` `fmtQty` | 全工程零调用。且正是项目反复标记过的 `v % 1f` 写法 |
| `ReminderSettings.kt:20` `KEY_LEAD_MINUTES` | 声明后从未使用 |
| `ReminderSettings.kt:19` `KEY_SOUND_MODE` + `SettingsViewModel.kt:81-86` | `sound_mode` **写库但无 UI 入口**（`SettingsScreen` 全文不调用 `onSoundModeChange`）**也无消费点**（`resolve` 不读它）。与 `ReminderSettings.kt:9-12` KDoc 声称的"本对象是这些设置唯一的消费入口"直接矛盾 |
| `MedicationAdminService.kt:279-284` `enableStockTrackingIfNeeded` | 零调用 |
| `Dose.kt:59/62/38/46` `Doses.ONE`/`UNLIMITED`/`isNegative`/`equalsWithin` | 仅测试引用 |
| `Color.kt:70/71/76/78` | 4 个色值全工程零引用（`SuccessGreenContainer` / `OnSuccessGreenContainer` / `OnWarningAmber` / `WarningAmberBorder`） |
| `Theme.kt:75-82` | `dynamicColor: Boolean = false`，唯一调用点 `MainActivity.kt:51` 写的是 `CarroMedTheme {` 不传参 ⇒ 动态色分支**不可达** |
| `ReminderSettingsScreen.kt:482-485` | `if (policyType == PRN)` 死分支（调用方 `:325` 已保证非 PRN 才渲染 `PreviewCard`） |
| `ManualDoseScreen.kt:261, 273` | 两个完全未使用的 `Calendar.getInstance()` |

### 4.2 生产代码零调用的 DAO 方法（11 个）

`DoseSlotDao`：`countCompletedSlotsForDate`(`:191`)、`countTotalSlotsForDate`(`:194`)、
`getPendingSlotsAfter`(`:96`)、`getPendingSlotsForMedicationAfter`(`:99`)、
`observeSlotStatusCountsForMedication`(`:233`)、`update`(`:35`)
｜`DoseRecordDao`：`getRecordsInRange`(`:82`)、`observeRecordsInRange`(`:79`)
｜`ReminderSettingsDao`：`getMedicationWithReminder`(`:138`)、`getActiveWithReminder`(`:152`)、
`getSchedulableOn`(`:176`)、`observeAll`(`:47`)、`deleteForMedication`(`:84`)

其中 `DoseSlotDao.update`(`:35`) 值得特别注意：它的 KDoc（`DoseSlotDao.kt:43-46`）写着
"[update] 在生产代码里从不调用 —— 也就是槽位的 `dose_amount` 在其整个生命周期内被冻结在创建时的值"，
**这个结论在 A3 之后已经不成立了**（`updateDerivedColumns` 就是为此新增的），但注释没跟上。
"注释描述的缺陷已被修复、注释本身没删"是这类文件最常见的维护陷阱。

### 4.3 KDoc 与实现不符

| 位置 | 问题 |
| :-- | :-- |
| `MedicationAdminService.kt:256-259` | `ensureInitialStockLedger` 的 KDoc 写"保证 `SUM(...) == medications.current_stock` 守恒"，而 **`medications.current_stock` 这一列已在 A1 被删除**。该方法现在只被测试调用，生产零引用 |
| `DoseSlotDao.kt:43-46` | 见 4.2，结论已过期 |
| `ReminderSettingsViewModel.kt:247` | 注释写"暂停归详情页的开关所有；提醒设置页只读展示，不在这里改"，但 `ReminderSettingsScreen.kt:171-180` 就是可写开关，且保存时（`:250-253`）会以 `setPausedUntil(medId, "")` 写入**无限期**暂停 ⇒ 提醒页只能"无限期暂停"，拿不到详情页那套"暂停到明天/一周后/指定日期" |
| `InventoryScreen.kt:188-190` | 注释声称"统一走 Quantity 格式化…与全库其他页面的规则一致"，但同文件 `:506` 有私有 `fmt` 遮蔽 |
| `MedicationDetailScreen.kt:832-841` | 私有成员扩展 `fun Quantity.fmt(v: Float)` **遮蔽**了 `Quantity.fmt`（`Quantity.kt:23`），于是 `:436/493/652/778` 全部走这份重复实现。当前行为恰好等价，但它是与 `QuantityTest` 无关的**影子实现**——`Quantity.fmt` 规则一改本页不跟随 |
| `AddEditMedicationViewModel.kt:409-410` | 连续两个 KDoc 块叠在同一个 `guessLabel` 上，前一个已成孤儿 |
| `AGENTS.md` §3 表格 | "DoseTrackingServiceTest 三项测试从未建立库存台账，却断言'库存未被改变'"—— **该历史记录已过期**，`:72-80` 确实建了 20 片的账，`InventoryLedgerInvariantTest` 也已覆盖负库存。文档说"无测试则跳过并注明"，过期的缺陷记录会让后来人误判当前状态 |

### 4.4 其余

- `ProgressScreen.kt:263-265` 用**显示字符串**判断"是不是今天"（`day.dayLabel == "今日"`），
  而 `DayAdherence.date` 已经是 `LocalDate`（`ProgressViewModel.kt:27`）。`dayLabelOf`（`:150-153`）
  一旦本地化或新增含"今日"的取值，样式判断会静默失效且不报错。
- `ProgressScreen.kt:392` / `ManualDoseScreen.kt:362` / `RefillScreen.kt:178` 硬编码单位 `"片"` 兜底
  —— `Quantity.kt:10-13` 的 KDoc 刚刚点名批评过"硬编码单位直接诱因"，同一问题在 3 处复发。
- `Type.kt` 只覆盖 8 个 M3 槽位，`bodySmall` / `titleSmall` / `labelMedium` 落回默认值，
  导致 `StatsScreen.kt:325/397` 用 `titleSmall`(14sp) 排"依从率构成"标题，
  **比正文 `bodyMedium`(13sp) 还大**。
- `SettingsScreen.kt:472` 硬编码 `"CarroMed v1.0.0 (Native Compose)"`，不读 `BuildConfig.VERSION_NAME`。
- `ProgressScreen.kt:297-307` `PARTIAL` 态用 `SuccessGreen.copy(alpha = 0.45f)` 配 `Color.White`：
  浅色主题下混色约 `#96D5AD`，白字对比度 ≈ **1.69:1**（9sp 正文需 4.5:1）。
  同一 `when` 的另外三态都用不透明底色。
- `AddEditMedicationScreen.kt:833-841` 的"库存预警阈值"既无单位也无字符过滤，
  `AddEditMedicationViewModel.kt:304` `toFloatOrNull() ?: 0f` 静默存 0，而 0 的语义是"**关闭**低库存告警"
  ⇒ 用户打错字就把告警关了。`:231-238` 的"默认单次剂量"同样无过滤无单位。
- `InventoryScreen.kt:246` `已于 ${-d} 天前过期` 在 `d == -1` 时输出"已于 1 天前过期"（应为"昨天"）。

---

## 5. 建议的修复顺序

**第一批（写坏数据 / 虚假保证 / 门禁失效）**
1. P0-1 `markExpired` 加状态守卫 —— 一行 SQL，消除"打卡被结算成漏服且不可撤销"的竞态
2. P0-4 剂量输入框改 `String` 草稿 + 过滤 + `> 0` 校验 —— 消除"打卡吃药加库存"
3. P0-2 接入 `fullScreenIntent` 或下掉那个开关
4. P0-3 权限自检接真实 API，空按钮接真实跳转
5. P0-5 抽 `StatsEngine.groupByUnit` 并让测试调它
6. P0-6 `fallbackToDestructiveMigration` 加构建期约束

**第二批（跨页面口径漂移，逐条都有明确的"两处不同"证据）**
7. P1-3 + P1-4 + P2-3：把低库存/可用天数统一回 `StatsEngine`，删掉 `MedicationDetailViewModel` 的 `CYCLE -> 5` 与无下界的 `Math.round`
8. P1-5 药箱页 INTERVAL 文案对齐 I7
9. P1-1 统计页订阅 `dose_slots`/`dose_records`（改用已有的 `observeSlotStatusCounts` / `observeDoseSumByMedicationInRange`）
10. P1-2 `ProgressViewModel` 的 `today` 移进 flow
11. P1-9 疗程结束日：`hasEndDate = true` 时给默认日期，或让 `clearEndDate` 覆盖所有"开关关"的情况
12. P1-6 `getSlotsInRange` 加 `medication_id` 过滤

**第三批（健壮性与测试）**
13. P1-8 五个 ViewModel 补 `try/catch`
14. P1-11 `setStockTracking` 的 `current <= 0` 改 `current == 0`
15. P1-12 `getActivePolicyForMedication` 加 `ORDER BY version DESC` + 备份校验
16. P1-10 `AlarmReceiver` 改用 Uri 反查槽位
17. P1-14 修 4 处恒真/假响应式断言；P1-15 补 `resolve` / 本机备份链路 / 快照的测试
18. P1-7 EXPIRED 槽位的补打卡入口与提示语

**第四批（清理）**
19. 4.1 / 4.2 / 4.3 的死代码与失真 KDoc。**建议同一次提交做掉，并同步更新 `AGENTS.md` §3 里已过期的历史缺陷记录** —— 过期的"已修复"记录和过期的"未修复"记录一样会误导后来人。

---

## 6. 复核清单（改动后请逐条验证）

- [ ] `markExpired` 加守卫后，`AlarmReconcilerIdempotencyTest` 与 `SnoozedSlotSettlementTest` 仍绿
- [ ] 剂量输入改 `String` 后，`AddEditLogicTest` 需同步（它现在的 `dose: Float` 断言会失效）
- [ ] 统一低库存口径后，**今日页 / 药箱 / 详情 / 库存 / 补药五处**的判定值必须一致（含未追踪库存的药）
- [ ] 统计页改造后需验证"跳过一次 → 切到统计页"数字立刻变化（当前不刷新）
- [ ] `AlarmReceiver` 改 Uri 反查后，`AlarmIdentityTest` 必须仍走生产 `alarmIntent`
- [ ] 疗程结束日：建一个药 → 保存（开关开、无日期）→ 重新进入，确认开关状态与库内一致
- [ ] 负库存药 + `INTERVAL ≥ 15` 的组合，详情页不应显示"可用天数 ∞"
- [ ] `python tools\app_screenshots.py --clear --seed` 跑通且 `manifest.md` 无新增失败项
- [ ] 改动涉及的页面**亲眼看过图**（尤其 `PermissionCheckScreen` 与统计/进展两页）
- [ ] 每一处新增测试做一次变异验证：故意改坏实现，确认对应测试变红
