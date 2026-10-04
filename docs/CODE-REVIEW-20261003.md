# CarroMed 全量代码审查报告（2026-10-03）

> **范围**：`app/src/main` 全部 93 个 Kotlin 文件（约 12,500 行）+ Manifest + 全部资源 +
> Gradle/CI 配置 + 22 个 `.maestro`/脚本走查资产。
> **方法**：逐行阅读 + 交叉核对 DAO SQL / 实体 / 调用方 / Android SDK 35 源码，
> 并**实际执行**构建、单测、Lint 与对比度计算来验证结论。
> **基调**：只报真实存在的问题。对每条结论标注证据等级；无法验证的一律进第 9 节，不混入结论。

## 证据等级说明

| 标记 | 含义 |
| :--- | :--- |
| **【实测】** | 本次实际执行命令/脚本得到的结果 |
| **【源码验证】** | 逐字读过相关代码，对照 AOSP/SDK 35 源码或调用方确认 |
| **【待实机】** | 静态分析无法定论，需真机/模拟器确认 → 全部收在第 9 节 |

---

## 0. 审查基线：实际跑出来的结果

| 项目 | 命令 | 结果 |
| :--- | :--- | :--- |
| 单元测试 | `./gradlew :app:testDebugUnitTest` | **全绿**（含 jqwik 属性测试 + Robolectric 真内存库） |
| Release 编译 | `./gradlew :app:assembleRelease` | **成功**，产出 `app-release.apk` 12.8 MB |
| **Android Lint** | `./gradlew :app:lintDebug` | **失败：6 errors + 76 warnings** |
| 界面调试 | `assembleDebug` | 成功，20.1 MB |

**Lint 从未在项目里跑过 —— 这是本次最重要的工程发现。**

`build.gradle.kts` / `app/build.gradle.kts` / `settings.gradle.kts` 中
**不存在任何 `lint {}` 块**，也没有 lint baseline；`.github/workflows/ci.yml` 只有
`assembleDebug` + `testDebugUnitTest`，**没有 lint 步骤、没有 release 编译步骤**。

后果是 AGENTS.md §三 自己写的验收标准「编译通过，**且 release 也能编译**」在 CI 里
从不被强制执行 —— 只有开发者本机记忆维系。Lint 一旦接线就失败，说明这 6 个 error
从未被人看过。【实测】

Lint 报出的 6 个 error：

| 位置 | 问题 | 判定 |
| :--- | :--- | :--- |
| `Notifications.kt:360` | `MissingPermission`：`nm.notify()` 前无权限检查 | **真实**，见 3.1 |
| `Notifications.kt:418` | 同上（聚合通知） | **真实**，同上 |
| `DownloadsLogExporter.kt:193` | `NewApi`：`MediaStore.Downloads.getContentUri` 需 API 29 | **误报**，调用点 `:166` 有 `SDK_INT >= Q` 守卫 |
| `DownloadsLogExporter.kt:251` | 同上 | **误报**，经 `:218` 从受守卫的 `writeViaMediaStore` 调入 |
| `DownloadsLogExporter.kt:323` | `downloadsCollection()` 无任何守卫 | **形式问题**：该 `internal fun` 只给测试用，但位于生产源集 |
| `DataExporter.kt:248` | `ByteOrderMark`：`private const val BOM = "\uFEFF"` | **误报**，是常量不是文件 BOM |

即：**6 个 error 里 3 个是真实问题、3 个是误报**。但无论真假，"lint 一接就红"
这件事本身说明它没有为接入 lint 做过任何准备。

76 个 warning 中值得处理的：`ExportedReceiver`（debug 源集，可接受但需抑制）、
`QueryPermissionsNeeded`（`VendorIntentHelper.kt:29`，`resolveActivity` 在包可见性
规则下可能永远返回 null）、`DataExtractionRules`（缺 `android:dataExtractionRules`）、
`DefaultLocale`×2（`ManualDoseScreen.kt:286,319`）、`ObsoleteSdkInt`×5（minSdk 26
下的死版本判断）、`UnusedResources`×20（20 个死字符串，含
`set_night_dnd_start_title` / `stats_export_failed` 等 —— 说明有已删功能残留的资源）。

---

## 1. 结论摘要

| 级别 | 条数 | 领域分布 |
| :--- | ---: | :--- |
| **P0 阻断** | 1 | 隐私 |
| **P1 严重** | 9 | 闹钟链路 3 · 领域不变量 3 · 数据/隐私 2 · 平台兼容 1 |
| **P2 一般** | 31 | 分布见第 5 节 |
| **P3 建议** | 26 | 分布见第 6 节 |

**领域层架构质量很高**：三层时序解耦成立、库存账本确实 append-only、
幂等锚点确实下沉到 SQL WHERE、`core/domain` 确实零 `android.*` 依赖、
`SlotProjectionEngine` 显式处理了 DST 空洞与重叠。这些不是客套话，是逐条核对过的。

**本轮问题有极其鲜明的共同形态：静默。**
P1 里 7 条的共同特征是「不抛异常、不写错误日志、数据错了但所有不变量测试仍然全绿」。
台账的 `SUM(change_amount) == balance_after` 守恒只能证明**账本自洽**，
证明不了**账本与用户输入一致** —— 后面两条 P1 正好落在这个盲区里。

---

## 2. P0

### P0-1 药名等健康数据在每次冷启动时自动外投到全局可读的 `Download/CarroMed/`

**位置**
- `core/alarm/AndroidLogging.kt:51` —— `AppLogging.install` 内**无条件**调 `DownloadsLogExporter.exportAll(...)`
- `core/data/LogFileSink.kt` —— 落盘**无级别门**（`grep minLevel|BuildConfig|includeInfo` 命中 0；对比 `LogcatSink` 在 `AndroidLogging.kt:36` 有 `includeInfo = BuildConfig.DEBUG`）
- `core/alarm/AlarmReceiver.kt:93` —— 生产代码确实把药名写进 INFO 日志：
  `AppLog.i("AlarmReceiver", "med loaded: ${med?.name}, paused=..., archived=...")`
- `core/data/DownloadsLogExporter.kt:198` —— `RELATIVE_PATH = "Download/CarroMed"`

**链路（已逐段确认）**：
```
AppLog.i(药名)  →  LogFileSink 无级别门，release 也落盘  →  filesDir/logs/app-YYYYMMDD.log
                 →  AndroidLogging.kt:51 每次启动自动 exportAll  →  Download/CarroMed/*.txt
```

**为什么是 P0**
1. **用户从未同意**。这是自动发生的，不是用户动作。`AndroidManifest.xml:20`
   自己写着「隐私基线：严禁申请 INTERNET」，`:24` 写着 `allowBackup="false"` ——
   项目在隐私上很克制，却在这里自我违反。
2. **`Download/` 在 API 29+ 对持有读存储权限的其他应用与媒体扫描器可见**，
   文件名可预测（`app-20261003.log`、`carromed-crash-<ts>.txt`）。
3. 对用药 App，**药名 = 用户在吃什么药**，是最敏感的数据类别。
4. 崩溃路径更重：`AndroidLogging.kt:147-152` 把 `AppLog.recentLines()`
   （含药名行）整段写进 `crash-*.txt`，`exportCrashBlocking` 也外投。

**注意**：崩溃外投的**动机本身是合理的** —— 内部 `filesDir/logs` 会被「清除数据」
抹掉，而本项目最典型的故障恢复动作恰恰就是清数据，外投是唯一能跨过这道坎的落点
（`AndroidLogging.kt:90-95` 有完整论证）。**问题不在"要不要外投"，而在
"每次冷启动都把运行期日志连同药名一起外投"，以及"零脱敏"。**

**建议修法**
1. `LogFileSink` 增加字段级脱敏：给 `AppLog.Event` 加 `sensitive: Boolean`，
   由 `AlarmReceiver.kt:93` 等调用点显式标注，`LogFileSink` 对该类事件只落 `tag`。
2. **去掉 `AndroidLogging.kt:51` 对 `app-*.log`（运行期日志）的自动外投**，
   只保留崩溃样本外投。崩溃样本同样需要脱敏，但那是"用户崩溃了才生成"，
   频率与场景都远低于"每次启动都拷一份"。
3. 补一条断言：`Download/CarroMed` 里不得出现药名。

---

## 3. P1

### P1-1 Manifest 里的时间变更广播 action 名写错，「改时自愈」整条能力从未生效

【源码验证 —— 已对照本机 Android SDK 35 源码逐字确认】

```xml
<!-- AndroidManifest.xml:70 -->
<action android:name="android.intent.action.TIME_CHANGED" />
```

本机 SDK 源码 `android-35/android/content/Intent.java`：
```java
public static final String ACTION_TIME_CHANGED = "android.intent.action.TIME_SET";
public static final String ACTION_TIMEZONE_CHANGED = "android.intent.action.TIMEZONE_CHANGED";
```

**常量的「名」是 `ACTION_TIME_CHANGED`，但它的「值」是 `android.intent.action.TIME_SET`。**
系统从不广播 `android.intent.action.TIME_CHANGED` 这个字符串。

**精确范围**（避免夸大）：
- `BootReceiver.kt:43` 用的是**常量** `Intent.ACTION_TIME_CHANGED`（= `TIME_SET`）—— **代码是对的**。
- 错的只有 **Manifest 里的字面量**。
- 结果：intent-filter 永远匹配不到 → `BootReceiver` 收不到时间变更广播
  → `BootReceiver.kt:48` 的 `ACTION_TIME_CHANGED` 分支（`CurrentDateHolder.refresh()` +
  `enqueue(replace=true)`）是**死代码**。

**后果**：切换 12/24 小时制、自动时间开关、手动改时、NTP 校时跳变都不触发即时对账，
`slot.scheduledTs` / `snoozeUntilTs` 与新挂钟的偏差要等最多 15 分钟（周期 Worker）
或下次 RESUMED。而 `BootReceiver.kt:12` 与 `AndroidManifest.xml:63` 的注释都在
描述一条不存在的能力。

**修法**：`AndroidManifest.xml:70` 改成 `android.intent.action.TIME_SET`（一行）。
`BootReceiver` 保持用常量。**建议补一条单测断言 manifest 中的 action 字符串
等于 `Intent.ACTION_TIME_CHANGED` 的运行时值** —— 这类错误编译期与人工 review 都抓不到。

### P1-2 `AlarmAlertActivity` 是 `singleTop` 却没有 `onNewIntent`，第二条全屏提醒会复用第一条的药品

【源码验证】

- `AndroidManifest.xml:49-50`：`taskAffinity=""` + `launchMode="singleTop"`
- `AlarmAlertActivity.kt:97`：`flags = FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_NO_USER_ACTION`
- `AlarmAlertActivity.kt:101`：只有 `onCreate`，**全类无 `override fun onNewIntent`**（已 grep 确认）
- `AlarmAlertActivity.kt:117`：所有数据（含 `medId` / `medName` / `doseText` / `scheduledTime`）**只在 `onCreate` 读一次**

系统契约：`singleTop` 下若栈顶已是同类实例，系统调 `onNewIntent` 而**不**调 `onCreate`，
且 `getIntent()` 仍返回**原始** Intent —— 必须自行 `setIntent()` 才更新。
`taskAffinity=""` 使该 Activity 独占一个 task，`ActivityStarter.findTask` 会复用它。

**后果 —— 这是「串改」**，正是项目要杜绝的那类事故：
08:00 环孢素的全屏提醒未被处理 → 08:30 二甲双胍的提醒弹出 → 用户看到的仍是
「环孢素」，点「确认已服」落库到 08:00 那条槽位（`DoseEntryActions.confirm` 会照常成功，
因为槽位仍是 PENDING）。用户和统计都以为吃了 08:30 那味药。
`AlarmReconciler.kt:400-401, 443-444` 还刻意把同批多药错开 5 秒，
**同批多药在秒级内连续触发 FSI 的概率不低**。

**修法**：加
```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)   // 关键：否则 getIntent() 仍返回旧 Intent
    recreate()
}
```
或改 `launchMode="standard"`，让每条提醒独立实例。

### P1-3 `currentPrecision()` 报告的档位在目标状态下是假的，「系统特权自检」页对用户说谎

【源码验证】

```kotlin
// AlarmScheduler.kt:278-282
fun currentPrecision(alarmManager: AlarmManager? = null): Precision {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return Precision.EXACT
    val am = alarmManager ?: return Precision.INEXACT
    return if (am.canScheduleExactAlarms()) Precision.EXACT else Precision.ALARM_CLOCK
}
```

标签文案（`res/values/strings_alarm.xml:5`）：**「闹钟时钟通道（到点必响，若遭激进系统限制可能退化）」**。

但 `setAlarmClock` 与 `setExactAndAllowWhileIdle` **需要同一张授权**
（`AlarmManager.java` 中 `setAlarmClock` 标注 `@RequiresPermission(SCHEDULE_EXACT_ALARM)`）。
即 `canScheduleExactAlarms() == false` 恰好就是 `setAlarmClock` 抛 `SecurityException`
的状态 —— 代码自己在 `AlarmScheduler.kt:232-233` 承认了这点
（「部分 ROM 上 setAlarmClock 同样要求精确闹钟权限」）并会真的落到
`setAndAllowWhileIdle`（+1h 窗口）。**排程路径是安全的，报出的档位是错的。**

**在本项目里不是理论分支**：`AndroidManifest.xml:12` 把 `SCHEDULE_EXACT_ALARM` 标了
`maxSdkVersion="32"`，所以 **API 31/32 设备上这张权限可被用户随时在系统设置里撤销**。
撤销后每条提醒都可能晚最多 1 小时，而自检页对用户宣称「到点必响」。

**修法**：`currentPrecision()` 必须反映「下一次实际排程会落到哪一档」，
`canScheduleExactAlarms() == false` 时直接返回 `INEXACT`（或按 API 33+ / 31-32 分档，
因为 `USE_EXACT_ALARM` 是 API 33 才引入的 normal 权限）。
同时把 `ALARM_CLOCK` 的文案改成不含「到点必响」的表述。

### P1-4 `saveReminderPolicy` 允许保存「零时点」计划 —— 该药永远不响，且全程零报错

【源码验证 —— 已端到端确认】

`MedicationAdminService.kt` 里三条守卫**对空集合全部空转通过**：

| 行 | 代码 | 空集合时的行为 |
| :--- | :--- | :--- |
| `:248` | `require(timeKeys.size == timeKeys.distinct().size)` | `0 == 0` → 通过 |
| `:263-271` | `require(draft.times.firstOrNull { 解析失败 } == null)` | `firstOrNull` 返回 null → 通过 |
| `:291` | `require(draft.times.firstOrNull { !Dose.isWithinRange(it.dose) } == null)` | 同上 → 通过 |
| `:302` | `require(draft.daysOfWeek.all { it in 1..7 })` | `all` 对空列表 = **true** → 通过 |

然后 `SlotProjectionEngine.kt:59-61`：
```kotlin
if (!policy.isActive || times.isEmpty()) {
    return emptyList()
}
```
以及 `:185-188` `policy.daysOfWeek.contains(dayOfWeek)` 对空列表恒 false。

**结果链**：`saveReminderPolicy` 返回新 `policyId` → UI 显示「保存成功」→
`reconcileSchedule` 日志打出 `projected=0` → 这味药**永远不会有任何槽位、任何提醒**。

这与产品第一承诺「提醒可靠 —— 到点一定响，不静默漏提醒」直接冲突。

**为什么没被拦住**：`ReminderSettingsViewModel.kt:353` 有 UI 前置校验，
但 `MedicationAdminService.kt:252-260` 的注释明确说这一层要保护
「**所有**调用方（备份导入、未来 Widget / 手表 / 快捷指令）」。而
`DataExporter.kt:936-946` 的恢复路径用 `insertTimes` **直写实体**、绕过服务层 ——
**「第三层防线」对备份这一条真实入口是失效的**。

**修法**：服务层加两条
```kotlin
require(draft.times.isNotEmpty() || draft.policyType == PolicyType.PRN) { ... }
if (draft.policyType == PolicyType.DAYS_OF_WEEK) require(draft.daysOfWeek.isNotEmpty()) { ... }
```
并在 `validateBackup` 补对应致命校验。

### P1-5 `Dose.of` 是静默钳制，而库存三条写入口只判符号 —— 越界数量被静默写成上界

【源码验证】

```kotlin
// model/Dose.kt:72-73 —— 钳制，不是校验
fun of(value: Float): Dose =
    Dose(Math.round(value * 1000f).coerceIn(-MAX_MILLI, MAX_MILLI))
```
`Dose.kt:61-62` 的 KDoc 自己写着：「服务层仍应对**原始输入**显式 `require(≤ MAX_MILLI)`：
**钳制是防御纵深，不是校验的替代品**（静默钳制会掩盖用户输入错误）」。

**这条纪律在剂量路径上落实了，在库存路径上没有**：

| 入口 | 守卫 | 是否过 `isWithinRange` |
| :--- | :--- | :--- |
| `takeDose` `DoseTrackingService.kt:183-185` | 双重 | ✅ |
| `editDose` `:629-631` | 双重 | ✅ |
| `logManualDose` `:760-762` | 双重 | ✅ |
| `saveReminderPolicy` `MedicationAdminService.kt:291` | 双重 | ✅ |
| **`calibrateStock` `:845`** | `require(actualStock >= 0f && isFinite())` | ❌ |
| **`refillStock` `:947`** | `require(addedAmount > 0f && isFinite())` | ❌ |
| **`setStockTracking` `:898`** | `initialStock?.let { Dose.of(it) }` | ❌ |

**后果**：
- 盘点输入 5000 → `Dose.of(5000f)` = `1_000_000` milli = **记录 1000**。
  `balanceAfter` 也按 1000 算，因此 **I2 守恒恒等式照样成立、台账自身完全自洽、
  没有任何测试会红** —— 但库存凭空少了 80%。
- `addedAmount = 0.0004f` 通过 `> 0f`，量化成 0，写进一条 **`change_amount = 0`**
  的 REFILL 流水；而 `:946` 的注释明确写着「0 同样拒绝：一条零额流水没有任何信息量」。
  `refillStock` 自己拒绝 0，量化后的 0 却畅通无阻。

**修法**：`calibrateStock` / `refillStock` 改用 `Dose.isWithinRange`；
`setStockTracking` 的 `initialStock` 同口径；`appendLedger`（`:127`）加
`changeAmount != 0` 兜底断言。

### P1-6 `restateSlot` 的目标状态校验发生在作废**之后** —— 返回 false 却真的改了数据

【源码验证】

```kotlin
// DoseTrackingService.kt:480-538
val restatedTs = slot.actualTakenTs ?: System.currentTimeMillis()

// :483-485 先作废
if (slot.status == COMPLETED || slot.status == SKIPPED) {
    if (!revertSlotInternal(slotId)) return@withTransaction false
}
when (target) {
    COMPLETED -> { ... }
    SKIPPED   -> { ... }
    else -> return@withTransaction false   // :538 ← 作废已经发生并提交了
}
```

`RecordStatus` 有三个值（`model/Enums.kt:28-32`），`REVERTED` 落到 `else`。
`withTransaction` **只在抛异常时回滚**，`return false` 是正常提交。

**结果**：`restateSlot(id, REVERTED)` → 槽位 `PENDING`、事实 `REVERTED`、
台账已冲正 → 返回 `false`（契约是「false 表示未改变」）。**且 `:538` 那行没有
`AppLog`，无任何日志。**

`:537` 的注释「REVERTED 不是"结论"，改判到它请走 undoDose」正是在说这个 case，
但代码没在作废**之前**挡住它。

**可达性诚实说明**：当前生产调用方 `DoseEntryActions.kt:153`（←
`DoseRecordDetailViewModel.kt:440,456`）只传 `COMPLETED`/`SKIPPED`，**线上不可达**。
但这是 public suspend API，契约与行为直接矛盾，下一个调用方就会踩中。

**修法**：把 target 白名单校验提到 `:474` 之前，与既成事实校验并列。

### P1-7 备份恢复绕过剂量量程校验 —— 负剂量可被直写，扣减变加药

【源码验证】

`DataExporter.kt:687-708` 的 `INVALID_DOSE` 只覆盖 `doseSlots` 与 `doseRecords`。
`policy_times.dose_amount`、`medications.default_dose`、`min_stock_alert`
**没有任何量程校验**，`restoreBackup`（`:936-946`）原样直写 ——
而正常写路径有 `require`（`MedicationAdminService.kt:291`）。

`DataExporter.kt:184-186` 的 KDoc 自己说明了后果：「溢出为负的剂量（扣减变加药）
都是全程静默的数据损坏」。

`policy_times.dose_amount` 是**未来所有槽位剂量的来源**。一份 `doseAmount = -5000`
的备份，恢复时校验全绿、事务成功，投影引擎物化成
`dose_slots.dose_amount = -5000`，之后每次打卡 `change_amount = +5000` ——
**扣减变成持续加库存**，且 `SUM(change_amount) == balance` 恒等式照样成立，
守恒检查拦不住。

**修法**：`validateBackup` 补三条阻断校验，复用已有的 `BackupProblemKind.INVALID_DOSE`。

### P1-8 `AppLog.dispatch` 在持有全局锁的情况下分发 sink，与代码自己的注释直接矛盾

【源码验证】

```kotlin
// core/domain/AppLog.kt:83-97
synchronized(lock) {
    if (ring.size >= CAPACITY) ring.removeFirst()
    ring.addLast(LogFormat.full(event))
    val snapshot = sinks.toList()
    // sink 异常与调用方、与彼此完全隔离（在锁外分发，避免持锁做 IO）
    for (sink in snapshot) {        // ← 实际在 synchronized 块内
        try { sink.log(event) } catch (t: Throwable) { ... }
    }
}
```

注释（`:89`）声称「在锁外分发」，代码却在锁内。**这是确定性缺陷，不是推测。**

后果分两层：
1. **今天还没爆**：`LogFileSink.log` 只是 `writeScope.launch`，不阻塞。
2. **崩溃路径**：`CrashLogging.buildReport`（`AndroidLogging.kt:147-152`）
   至少一次同步调 `AppLog.recentLines()`（`AppLog.kt:101`）必须拿同一把锁。
   若此刻另一线程正持锁做 logcat Binder IPC，崩溃线程会阻塞在这把锁上 ——
   **发生在 `exportCrashBlocking` 的 1.5s 有界超时之前**，所以那个有界等待设计
   对它完全无效。这正是 `DownloadsLogExporter.kt:42-43` 想避免的「把崩溃 hang 成 ANR」。

**修法**：锁内只做「写环形缓冲 + 取 sinks 快照」，分发循环移到锁外。
（约 6 行）

### P1-9 `POST_NOTIFICATIONS` 被永久拒绝后，提醒功能整体静默死亡，App 全程无提示

【源码验证 + Lint 佐证】

```kotlin
// MainActivity.kt:29-30
private val notificationPermissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        /* 结果不阻塞主流程，未授权仅影响横幅展示 */   ← 结果被完全丢弃
    }
```

Lint 的两个 `MissingPermission` error 就在 `Notifications.kt:360,418` 报到同一根因上。

Android 13+ 上用户连续两次拒绝（或勾选「不再询问」）后系统不再弹窗，
回调被丢弃且**未保留 `shouldShowRequestPermissionRationale` 判定**。
全工程检索 `ACTION_APP_NOTIFICATION_SETTINGS` 只有一处
（`PermissionCheckScreen.kt:268`）—— **只有用户自己找到「系统特权自检页」才会看到**。

之后：`showDoseNotification` 返回 false 只写一条 `AppLog.e` →
`AlarmReceiver:108-119` 跳过续期 → **全屏提醒也一并失效**
（`setFullScreenIntent` 必须依附通知，通知发不出去就没有 FSI）。

**结果：App 在没有任何界面提示的情况下，提醒功能整体静默死亡。**
这正是「必须如实上报投递失败」只做了一半 —— 上报做到了，让用户看见没做。

**修法**：回调里区分「拒绝」与「永久拒绝」（用 `shouldShowRequestPermissionRationale == false`），
后者用一个低频（仅 RESUMED 且每天一次）的一次性横幅引导到系统设置。

---

## 4. P2（按领域分组，含精确定位）

### 4.1 闹钟与平台链路

| # | 位置 | 问题 |
| :--- | :--- | :--- |
| P2-1 | `AlarmReconciler.kt` 12 处 `runCatching`（188/285/293/315/343/387/402/423/431/445/460/474） | 吞掉 `CancellationException`（JVM 上继承 `IllegalStateException`）。`ReconcileWorker.kt:74-76` 明确知道「取消必须往外抛」，而被调方正好吃掉它 → Worker 不可中断；叠加 `enqueueOneShot` 用 `ExistingWorkPolicy.KEEP`（`:157-164`），若上次卡在"已 RUNNING 但结束不了"，后续**所有** `AlarmReceiver` 触发的续期被静默丢弃，日志只写 `"oneshot reconcile enqueued (policy=KEEP)"`，看不出没入队 |
| P2-2 | `AlarmScheduler.kt:223-226` | 兜底档把 `setAlarmClock` 当批量通道。一轮对账会对 14 天 × N 药 × M 时点逐槽位调用，落这档就是成百上千条 AlarmClock 条目，各自独立唤醒设备；SDK 明确警告这类闹钟「extremely expensive on battery use and should only be used for their intended purpose」 |
| P2-3 | `AlarmScheduler.kt:228` vs `:188`（KDoc） | KDoc 定纪律「降级一律 WARN」，实现只对最末档 WARN，第一档降级写的是 `AppLog.i`。排查「闹钟没响」时按 KDoc 应能在 WARN 看到，实际只有 INFO |
| P2-4 | `AlarmReceiver.kt:80 → :127` + `DoseSlotDao.kt:318` | `updateReminderCountAndLastNotified` 是该文件**唯一**没有 `status IN (...)` 守卫的 UPDATE，而 `markExpired`/`snoozeSlot`/`revertToPending` 全都有（KDoc 把它写成铁律）。98 行弹通知 → 用户点「已吃」→ 127 行继续写，两个 Binder/DB 往返都在 goAsync 窗口内 |
| P2-5 | `AlarmAlertActivity.kt:115` | `FLAG_KEEP_SCREEN_ON` **无期限**。用户不操作时整夜亮屏 + 持 wakelock，是明确的耗电发热问题 |
| P2-6 | `Notifications.kt:435-445` | `areNotificationsReachable()` 只查两个 HIGH 渠道，不查聚合通知实际用的 `CHANNEL_DOSE_REMINDER_SILENT`；`showOverdueSummaryNotification`（`:405`）缺 `IMPORTANCE_NONE` 判断，`:417-421` 无论是否真的上屏都 `return true` |

### 4.2 领域层不变量

| # | 位置 | 问题 |
| :--- | :--- | :--- |
| P2-7 | `DoseTrackingService.kt:480` | `restatedTs = slot.actualTakenTs ?: System.currentTimeMillis()`。`markExpired`（`DoseSlotDao.kt:564`）明确 `SET actual_taken_ts = NULL`，所以**每条 EXPIRED 槽位必然走右支** → 把「昨天 08:00 漏服」改判为已服，`actual_ts` 是**此刻**。`:425-428` 的 KDoc 恰恰写着要防这个场景。污染当日统计与时间线 |
| P2-8 | `DoseTrackingService.kt:1003-1005`、`MedicationAdminService.kt:93,313` | `reconcileSchedule` 完全不用类里专门注入的 `todayProvider`（`:75-83` 的 KDoc 写得很清楚「一旦读挂钟，测试成败随日历漂移」），却它是判据最重的方法。跨年那天行为突变，注入固定日期的测试窗口与被测数据完全错位 |
| P2-9 | `DoseTrackingService.kt` 171/192/270/305/480/643/770 + `MedicationAdminService.kt:154` | 注入时钟只覆盖 3 处，其余 7 处仍读 `System.currentTimeMillis()`。且 `:193` 是 `<= now + 60_000`（60 秒容差）、`:643` 是零容差 —— 两个方法被注释声明为「同一条纪律」，实际一个放一个不放 |
| P2-10 | `MedicationAdminService.kt:132-161` | `saveProfile` 不检查 `updateProfile` 受影响行数。同文件 `:197-202` / `:219-221` 的 `saveReminderBehavior`/`setPausedUntil` 都写了 `check(... == 1)`，三条写入口只有它缺。可达：编辑页开着 → 别处删了这个药 → 保存 → UPDATE 命中 0 行 → 返回 `medId`，UI 显示「已保存」 |
| P2-11 | `DoseTrackingService.kt:790-792` | `logManualDose` 丢弃 `markCompletedIfOpen` 的受影响行数（`:217`/`:273` 都把「0 行 ⇒ 放弃记账」当硬纪律）。一旦 UPDATE 被拒（`actualTs` 允许 `now + 60s`，跨零点时 `actualDate` 已是次日而 `todayStr` 仍是今天），会落库一条绑定到仍开放槽位的 COMPLETED 事实 → 闹钟照响 → 再打卡 → **第二条事实 + 第二次扣库存** |
| P2-12 | `DoseTrackingService.kt:783-788` | 补录的「最近槽位匹配」**没有时间窗**：`minByOrNull { abs(scheduledTs - actualTs) }` 只要当天有任一开放槽位就必然返回非 null。某药 08:00 已打卡，用户晚上补录「昨天 08:00」→ 20:00 那条被标成已服，依从率显示 2/2，实际只吃了一片 |
| P2-13 | `DiagnosticExport.kt:111-113, 139-140` | `logFileName(dayOf(lastModified))` 目标名只含日期，而 `LogFileSink` 会产出 `app-20261003.log` 与 `app-20261003-1.log` → 同一天的两条 Plan 目标名完全相同 → **体积滚动出来的前一段日志静默丢失**，而那恰恰是崩溃前最老的现场 |
| P2-14 | `MedicationAdminService.kt:358` / `:148-150` | `category` / `form` / 时点 `label` 是裸 `String`，完全不校验是否在 `MedicationVocab` 词表内（`policyType` 有枚举类型保护，这三个没有）。导入路径同样不校验。用户会看到「该次服药：随便写的标签」 |
| P2-15 | `DataExporter.kt:626-825` | 恢复路径不校验 `startDate`/`endDate`/`timeOfDay`/`daysOfWeek`/`intervalDays` 格式。坏 `startDate` 被 `SlotProjectionEngine.kt:67-68` 当成"从今天开始"、坏 `timeOfDay` 回落到 `LocalTime.of(8,0)`（`:101-102`）—— **用户所有时点静默变成 08:00**，恢复报"成功"，闹钟照排 |

### 4.3 数据层与性能

| # | 位置 | 问题 |
| :--- | :--- | :--- |
| P2-16 | `DoseSlotDao.kt:456-465`、`DoseRecordDao.kt:141-149` | 两个变化探针是无界 `COUNT(*)`，随年限线性变慢，且被 `combine` 常驻订阅 —— 每点一次「确认已吃」就是一次全量计数。行数单调增长（永不删除） |
| P2-17 | `InventoryTransactionDao.kt:29-30` | `observeTransactionsForMedication` 无 `LIMIT`、无时间窗。Room 的 `Flow` 失效粒度是表级 → **任何一次写账都让整表失效并全量重建 List**。两个页面订阅它。3 次/天 × 3 年 ≈ 3300 行/药 |
| P2-18 | `DoseRecordDao.kt:168-175` + `MedicationDetailViewModel.kt:158` | 详情页取 20 条却先 `getRecordsForMedication` 加载**全部**历史，`take(20)` 在 Kotlin 侧做。应下推到 SQL `LIMIT` |
| P2-19 | `LogFileSink.kt:86-97, 100-123, 144-169` | ①总量无上限（`maxFileBytes` 只管单文件，滚动序号 `-N` 可无限增长）；②每行 `flush()`；③写队列无背压。`cleanupExpiredLogs` 只在启动时跑一次 |
| P2-20 | `DownloadsLogExporter.kt:98-112` | `plan.source.readText()` 把每个日志文件整读成 `String` 再 `toByteArray()`，峰值内存 ≈ 3 天日志总量 × 3 |
| P2-21 | `DataExporter.kt:734-747` | `checkDuplicates` 把 `@StringRes labelRes`（Int）当 `%1$s` 传给 `getString` → 四类主键重复时用户在二次确认框里看到 **"2131165234 #3 在备份中出现了 2 次"**。`strings_csv.xml:72-73` 的注释已承认这个隐患但没修 |
| P2-22 | `AppDatabase` `SUM()` 映射到 `Int`（`InventoryTransactionDao.kt:53,76,83` 等） | `MAX_MILLI = 1_000_000`，累计到 2148 条满量程记录即溢出 `Int` |

### 4.4 界面与交互

| # | 位置 | 问题 |
| :--- | :--- | :--- |
| **P2-23** | `TodayScreen.kt:807-815` | **【实测】深色模式下「已服」徽章文字对比度 1.50:1**（WCAG AA 需 4.5:1）—— 文字基本不可见。浅色模式 7.82:1 正常。根因：`SuccessGreen.copy(alpha=0.12f)` 作底色配 `OnSuccessGreenContainer`（`Color.kt:71` 固定深绿），而 `SuccessGreen*` / `WarningAmber*`（`Color.kt:68-78`）**只有一套固定色值、没有深色变体**。详见第 5 节 |
| P2-24 | `InventoryScreen.kt:528`、`MedicationDetailScreen.kt:543,912` | **【实测】`SuccessGreen` / `WarningAmber` 当正文色在浅色模式下仅 3.24:1 / 3.13:1**，未达 AA 的 4.5:1（13–14sp 非大字）。深色模式反而是 4.84:1 / 5.00:1 通过 |
| P2-25 | 9/15 页面 | `InventoryScreen.kt:119`、`AddEditMedicationScreen.kt:127`、`MedicationDetailScreen.kt:127`、`ManualDoseScreen.kt:90`、`ReminderSettingsScreen.kt:107`、`RefillScreen.kt:80`、`DoseRecordDetailScreen.kt:116`、`PermissionCheckScreen.kt:105`、`SettingsScreen.kt:195` 直接用原生 `TopAppBar` + 各自 `fontWeight`，**绕过 `CarroMedTopAppBar`**。而 `CarroMedTopAppBar.kt:40-43` 的 KDoc 明确说 `titleLarge` 22sp + `maxLines=1` 是「全应用 14 个页面标题字号一致的唯一前提」。这些页面的标题可换行撑高顶栏 |
| P2-26 | `DoseRecordDetailScreen.kt:315-317, 389, 659-660` | 同一页两种日期格式：槽位计划日走 `formatDayLabel` → `10月3日`，记录实际日走 `formatDay` → `2026-10-03`（ISO 原文） |
| P2-27 | 触摸目标 | `DoseRecordDetailScreen.kt:392-396`（`contentPadding vertical=4.dp`）、`:600-611`（`vertical=8.dp`）、`TodayStreakBadge.kt:53-65`（`vertical=6.dp` + 16dp icon ≈ 28dp 高）、`ManualDoseScreen.kt:93-95`（`TextButton` 作 `navigationIcon`）多处**低于 48dp 最小触摸目标**。而项目自己在 `TodayScreen.kt:628` 与 `ManualDoseScreen.kt:291-292` 引用了 `orsbf P1-15` 作为整改依据，说明标准已知但未全量落地 |
| P2-28 | 硬编码字号 33 处 | `ProgressScreen.kt:437` 出现 `fontSize = 9.sp`；`ManualDoseScreen.kt:358-366` / `SettingsScreen.kt:674-819` 等按钮文字直接写 `12.sp`。`Type.kt:52-53` 自己标注「大量小字仍硬编码 sp，见 UIUX V-03，先落槽位再逐处收敛」—— 槽位落了，收敛没做 |
| P2-29 | `TodayViewModel.kt:218-219` | **DST 缺陷**：`dayEndTs = dayStartTs + 24*60*60*1000L`。该值是 `DoseSlotDao.kt:157` 中 `snooze_until_ts` 的**开区间上界**。夏令时前进日（23h）上界偏大 1 小时 → 次日 00:00–01:00 的推迟剂会错误出现在当天；回退日（25h）上界偏小 1 小时 → 当天 23:00–24:00 的推迟剂**整段丢失**。正确写法：`date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()` |
| P2-30 | `TodayViewModel.kt:295-312, 173-177, 203-207` | `Flow.catch` 是**终止操作符**。主状态流一旦抛异常，`emit` 降级态后整个流**永久完成**，之后不再恢复 —— 一次瞬时 Room 异常就让今日页**永久冻结**在错误态，且错误卡（`TodayScreen.kt:330-353`）**没有重试按钮**，用户唯一的出路是离开页面。`streakDaysFlow` / `calendarDayStatesFlow` 同理：catch 后流已完成，`combine` 收不到后续更新 |
| P2-31 | `AppLog.kt` / `AndroidLogging.kt:147-152` | `buildReport` 对 `AppLog.recentLines()` 调用**两次**（一次取 `.size`、一次遍历）。应在锁外取一次快照 |

### 4.5 工程化

| # | 位置 | 问题 |
| :--- | :--- | :--- |
| P2-32 | `app/build.gradle.kts:64-67` | **`app/proguard-rules.pro` 文件不存在**，但被 `proguardFiles()` 引用。当前因 `isMinifyEnabled = false` 不校验，一旦有人打开混淆，构建立即失败 |
| P2-33 | `.github/workflows/ci.yml` | 不跑 lint、不跑 release 编译、不跑 Maestro/视觉回归。AGENTS.md §三 自定的验收标准在 CI 里无强制力 |
| P2-34 | `app/build.gradle.kts:63` | `isMinifyEnabled = false` + `proguardFiles` 是死配置。release APK 12.8 MB 未混淆未裁剪 |
| P2-35 | 5 处时区快照 | `ProgressViewModel.kt:128`（**companion object** → 类加载时定型，进程存活期内永不刷新）、`MedHistoryViewModel.kt:80`、`StatsViewModel.kt:97`（实例字段）。而 `BootReceiver` 明确处理 `TIMEZONE_CHANGED`（P1-1 修好后）→ 进程存活期内改时区（飞机上开自动时区是真实场景），流水页/单药历史/统计页的时间显示与 `CurrentDateHolder` 和已对账的闹钟**分叉**。`ProgressViewModel.kt:381` 的 `groupBy { toLocalDate() }` 会把记录归到错误日期分组 |
| P2-36 | 8 处绕过 `CurrentDateHolder` | `DoseRecordDetailViewModel.kt:364`（`refreshFromSlot` 用 `LocalDate.now()` 算 `isActionable`，而 `:129-135` 的 KDoc 明确说「状态类不读挂钟，判据用的今天由 ViewModel 从 CurrentDateHolder 灌进来」—— **同一文件自相矛盾**，且午夜翻面时若槽位不变流不发射，`isActionable` 永不刷新）、`MedicationDetailViewModel.kt:139,152`、`InventoryViewModel.kt:153`、`StatsViewModel.kt:110`、`ReminderSettingsViewModel.kt:161,243,534`、`AddEditMedicationViewModel.kt:112,254`、`ManualDoseViewModel.kt:161`、`TodayViewModel.kt:136,328-329`、`TodayScreen.kt:491,493`（`System.currentTimeMillis()` 直接在 composable 里判 `isOverdue`，无状态、无重组触发，跨过服药时刻时卡片不会变红，且不可测） |

---

## 5. 界面与可访问性专项（含实测数据）

### 5.1 深色模式语义色 —— 实测对比度

`Color.kt:68-78` 的 `SuccessGreen` / `SuccessGreenContainer` / `OnSuccessGreenContainer` /
`WarningAmber` / `WarningAmberContainer` / `OnWarningAmberContainer`
**全部是单套固定色值，没有深色变体**，被直接使用（不经过 `MaterialTheme.colorScheme`）。

实测（按各调用点的真实叠色链逐层 alpha 混合后计算 WCAG 相对亮度比：
`SuccessGreen@12%` 叠在 `surface@60%` 叠在 `background` 上；WCAG 公式
`L=0.2126R+0.7152G+0.0722B`，通道先做 sRGB→线性化，比值 `(L_hi+0.05)/(L_lo+0.05)`）：

| 用法 | 浅色模式 | 深色模式 | AA 要求 |
| :--- | --- | --- | --- |
| `TodayScreen.kt:807-815`「已服」徽章（`SuccessGreen@12%` 底 + `OnSuccessGreenContainer` 字） | 7.82:1 ✅ | **1.50:1 ❌** | 4.5:1 |
| `InventoryScreen.kt:528` / `MedicationDetailScreen.kt:543` `SuccessGreen` 作正文色 | **3.24:1 ❌** | 4.84:1 ✅ | 4.5:1 |
| `MedicationDetailScreen.kt:912` `WarningAmber` 作正文色 | **3.13:1 ❌** | 5.00:1 ✅ | 4.5:1 |
| `AlarmAlertActivity.kt:302` `SuccessGreen` 底 + `onPrimary` 字（18sp Bold） | 3.30:1 | 3.14:1 | 3:1（勉强过） |

**只有 `TodayScreen.kt:807-815` 一处是「配对使用」**（自己调 `SuccessGreen.copy(alpha=0.12f)`
当底、又用固定深绿当字），深色下底色被暗背景压到接近黑色，文字与底色几乎同明度 →
**实际不可见**。其余「自配对」用法（`DoseHistoryCalendarSheet.kt:227-228`、
`ProgressScreen.kt:432-436`、`DoseRecordDetailScreen.kt:225-226`）是
`SuccessGreenContainer` 配 `OnSuccessGreenContainer`，自身对比 7.8:1，两种模式都安全。

**修法**（二选一）：
1. 把这 6 个语义色收进 `MaterialTheme.colorScheme` 的自定义槽位并提供深色变体；
2. 或在 `TodayScreen.kt:807-815` 改用**成对**的 `SuccessGreenContainer` / `OnSuccessGreenContainer`
   （与 `ProgressScreen.kt:428-436` 已有的整改注释同一思路）。

### 5.2 字号与可读性

`Type.kt` 把 `bodyMedium` 收到 13sp、`labelSmall` 11sp。项目定位是中老年用户
（`TodayScreen.kt:162-164` 自己写着「中老年用户四个 Tab 翻遍也找不到…」）。
13sp 正文对老年用户偏小，虽会跟随系统字号缩放，但默认档位偏保守。
`Type.kt` 未定义 `displayLarge/Medium/Small`、`headlineSmall` 四档（目前无调用方，无害）。

### 5.3 交互正确性（这些做得对，值得保留）

- 未来槽位**不渲染** ✓ 按钮而是显示只读说明，且**不置灰** —— `TodayScreen.kt:618-669` /
  `DoseRecordDetailViewModel.kt:86-91` 的理由（置灰会让人以为"再等等就能用"）很到位
- 状态动作**不渲染**而非置灰 —— `DoseRecordDetailScreen.kt:518-521`
- 错误态与"空状态"严格区分 —— `TodayScreen.kt:330-386`（`loadError` 时
  `hasAnyMedication = true`，不显示"去添药"引导）
- 全工程**零 `runBlocking` / `GlobalScope` / `Thread.sleep` / `allowMainThreadQueries`**
  （已 grep 确认）；**零 `collectAsState()`**（全部 `collectAsStateWithLifecycle`）
- 20 个 ViewModel 中 4 个有连点守卫（`isSaving`），`TodayViewModel.kt:350` 的
  `takeDose` **没有** —— 快速连点会起两次 `actions.confirm`，
  幂等锚点在服务层（`markCompletedIfOpen`）挡住了重复扣减，但会多一次写与一次 snackbar

### 5.4 Compose 状态与生命周期

- **modifier 顺序**：`DoseRecordDetailScreen.kt:168-170`
  `padding(innerPadding).imePadding()` —— 顺序正确（`padding` 不消费 IME inset，
  `imePadding` 仍能看到完整 inset）。已核查非缺陷。
- **`AppNavigation.kt:107`**：`navBarHeight = 80.dp + navBarBottomInset` 硬编码 M3
  NavigationBar 高度而非实测；且只处理 `navigationBars`，**未处理 IME** ——
  主 Tab 页有输入框时（药箱搜索 `CabinetScreen.kt:116`）键盘弹起会盖住底栏。
- **`AppNavigation.kt:284-288`**：`onSavedSuccess` 里 `popBackStack()` 后立刻 `navigate()`，
  同一帧内两次导航；未防连点保存。
- **`AppNavigation.kt:39-44`**：import 块未排序、重复分组，纯风格问题。

---

## 6. P3（建议级，摘要）

<details>
<summary>展开 26 条 P3</summary>

**领域层 11 条**
1. `DoseTrackingService.kt:1154-1157` `fmtQty` 用 `Locale.getDefault()` 格式化，
   结果**持久化进 `inventory_transactions.note`**（数据载荷列）。`de-DE` 下得到
   `"1,50"` 写进库，CSV 导出解析受影响。持久化应用 `Locale.ROOT`。
2. `Dose.kt:92` `isWithinRange` 用 `until`（开区间）→ **1000 被拒绝**，而
   `DoseTrackingService.kt:184,293,630,761` 与 `MedicationAdminService.kt:293` 的
   提示都写「不超过 1000」。用户按提示输入 1000 会被拒且看不懂原因。
3. `StatsEngine.kt:201-203` `groupByUnit` 用 `Int.sum()`；`asFloat` 在 16,777,216 milli
   以上丢精度。改 `sumOf { it.toLong() }`。
4. `StatsEngine.kt:127` `calculateStockRunway` 未防 NaN —— `:118` 的
   `<= 0f` 对 NaN 返回 false（NaN 所有比较都为 false），NaN 穿透到 `.toInt()` →
   **「可用 0 天且不告警」**，恰是最危险的组合。`:522` 同理。改 `!x.isFinite() || x <= 0f`。
5. `SlotProjectionEngine.kt:171-174` `isScheduledOnDate` 在日期循环内重复 `LocalDate.parse(policy.endDate)`。
   调用方 `:69-71` 已解析出 `policyEnd`，14 天窗口 × N 药 × 每轮对账 = 大量无谓解析。
6. `SlotProjectionEngine.kt:192-194` `cycleOnDays + cycleOffDays` 无上界，
   和超 `Int.MAX_VALUE` 时 `totalCycle` 变负、`%` 语义崩坏 → 排班静默错乱。
   `intervalDays.coerceIn(1,30)` 已有先例。
7. `StatsEngine.kt:61-66` `adherenceOf` 与 `:279-283` `calculateAdherence`
   是同一公式的两份实现。文件头对「单一实现」纪律很强，这里却留了两份。
8. `DoseTrackingService.kt:783` `getSlotsForDate` 拉**全部药品**当日槽位再内存过滤
   （DAO 侧 `DoseSlotDao.kt:163-164` 无 `medication_id` 条件）。已有按药过滤的
   `getSlotsInRangeForMedication`，应新增 `getSlotsForDateForMedication`。
9. `DoseTrackingService.kt:1056` 注释把 `EXPIRED` 称作「既成事实」不准确 ——
   `markExpired` 不写任何 `dose_records` 行，EXPIRED **没有事实行**，也无路径能撤回。
   建议明确写下「刻意决策」或补处理。
10. `DoseTrackingService.kt:192` vs `:643` 容差不一致（60 秒 vs 0），见 P2-9。
11. `core/domain` 下仅 2 处 `import androidx.room.withTransaction` —— **架构铁律成立**，
    此处记录为「已核查」而非缺陷。

**数据层 8 条**
12. `DownloadsLogExporter.kt:22-25,297-312` + Manifest —— `WRITE_EXTERNAL_STORAGE`
    **未声明**，因此 `hasLegacyWritePermission()` 恒为 false，`legacy/public-Downloads`
    分支是死代码，类注释的权限表失真。
13. `DownloadsLogExporter.kt:133-134,145,253-255` `purgeStaleArtifacts` 用
    `RELATIVE_PATH LIKE '%CarroMed%'` 子串匹配 → 会命中用户自建的
    `Download/MyCarroMedNotes/`，并删掉其中的 `report (1).txt`。改前缀精确匹配。
14. `MedicationDao.kt:9` / `DoseSlotDao.kt:7` / `SchedulePolicyDao.kt:8` —— 3 处
    未使用的 `import androidx.room.Update`。
15. `DataExporter.kt:1389` `FileProvider.getUriForFile` 在 `runCatching` **之外**，
    传未覆盖路径会直接抛异常到调用方。
16. `DataExporter.kt:1122` + `SettingsViewModel.kt:272` `listLocalBackups` 是非 suspend
    同步磁盘 IO，调用方在主线程直接调。API 形状本身在诱导主线程 IO。
17. `DataExporter.kt:277-278` 导出时间戳只精确到毫秒但注释只提"同秒"，
    同毫秒两次导出会互相覆盖。
18. `DataExporter.kt:300,359` CSV **表头不经 `escapeCsv`**。当前只有 `values/` 无
    `values-xx/`，表头恰好 7/9 列与数据对齐；任何一次翻译引入逗号就会整表列数错位
    且 Excel 不报错。`strings_csv.xml:3` 的注释约束挡不住。建议改 `<string-array>` 逐列。
19. `AppConverters.kt:99-101` `toStringList` 遇到非法 JSON `return emptyList()`，
    **静默丢全部注意事项**，无日志（对比 `toIntList:119-121` 有 `AppLog.w`）。

**KDoc 失真 4 条（本项目 KDoc 是决策依据，错误的论证比没有论证更危险）**
20. `DataExporter.kt:52,84,731-733` 称 `insertAll` 是 `REPLACE` 会「外键级联删子行」
    /「静默覆盖丢行」，实际 `MedicationDao.kt:62`/`InventoryTransactionDao.kt:26`/
    `SchedulePolicyDao.kt:22,26` 都是 **ABORT**（事务崩+回滚）。四张表里只有
    `dose_records`（`DoseRecordDao.kt:319`）是 REPLACE。
21. `DoseSlotEntity.kt:24-26` 称 IGNORE 遇唯一键冲突「插入直接抛异常」——
    实际是静默跳过。同文件 `:29-30` 的表述才对，两处自相矛盾。
22. `DataExporter.kt:827-838` `RESTORE_CHUNK_SIZE` 的理由「Room `@Insert(List)` 生成
    多值 INSERT 会撞 999 变量上限」**不成立** —— 已核对 Room 2.6.1
    `EntityInsertionAdapter.insertAndReturnIdsList` 是逐行 `executeInsert`；
    且列数说明过期（`dose_slots` 实为 14 列不是 11，`medications` 实为 17 列不是 20）。
23. `SlotProjectionEngine.kt` 的 DST 处理 + `SlotProjectionDstPropertyTest` 覆盖 ——
    **已核查做得对**，此处记为对照面。

**UI/平台 3 条**
24. `Notifications.kt:285-290` `fullScreenPendingIntent` 无条件构造，但只在
    `:345` 的 `if (isEligibleForFullScreen && canUseFullScreen)` 内使用 →
    四种情况下留悬挂 PendingIntent 记录。挪进分支内。
25. `CompletionSoundPlayer.kt:126-127` `ToneGenerator` **从不 `release()`**。
    `CarroMedApp.kt:48` 的 `prepare()` 是异步 load，用户刚打开 App 立即打卡时
    `loadedSoundIds` 必不含该 id → **每次早打卡泄漏一个 AudioTrack**。
26. `ManualDoseScreen.kt:286,319` `String.format` 未指定 locale（Lint `DefaultLocale` ×2）。

</details>

---

## 7. 已核查、明确未发现问题的项

列出这些与列出问题同样重要 —— 它们划定了本次审查的实际覆盖边界。

**架构与领域不变量**
- `core/domain` 逐文件 grep，**零 `android.*` 依赖**，仅 2 处
  `androidx.room.withTransaction`（AGENTS.md 声明的唯一例外）
- **库存账本守恒**：append-only 成立；`balanceOf` 返回 `Int?` 区分「无流水」与
  「恰好抵消为 0」；`revertSlotInternal` 按**净额**冲正（手工推演
  「打卡→撤销→再打卡→再撤销」四步交错，净额归零正确）；`editDose` 的
  `delta = Dose(record.doseTaken - newDose.milli)` 符号正确；全程 `Int` 毫单位无浮点累加
- **幂等锚点全部下沉到 SQL WHERE**，不存在 lost update；**二次撤销安全**
  （冲正后净额为 0，`net < 0` 不再成立）；`revertToPending` 的状态守卫让第二次撤销拿到 0 行
- **「改计划只动 PENDING 槽位」三条写路径均正确**；结构上也不会误删历史 ——
  `DoseRecordEntity` 对 `slot_id` **无外键**，删槽位不级联删事实
- **`core/data` 全目录零 `@Update` / `@Delete`**，全部是带 `WHERE id = ...` 的局部 SET 列表 ——
  AGENTS §2「数据更新一律局部 UPDATE」在本层**已落实**
- **零 `strftime` / `date('now')` / `CURRENT_DATE`**（全项目 main 源集命中 0）。
  `DoseSlotDao.kt:232-233` 专门注明不用 SQL 日期函数（是 UTC）—— 判断正确
- **无 UTC / 本地时区混用**：所有 `LocalDate → epochMilli` 都经
  `LocalDateTime.of(...).atZone(zone)`
- **DST 空洞与重叠**：`SlotProjectionEngine.kt:128-138` 显式检测
  `getValidOffsets`，重叠取较早瞬间、空洞顺延，两方向都遵循
  「宁可早/晚响一次也不能凭空消失」，有 `SlotProjectionDstPropertyTest` 覆盖
- 领域层**无空 `catch {}`**；所有 `runCatching` 都有明确降级方向并在注释说明
  为什么选这个方向（符合「该响的不响比多响危险」的既定取舍）
- **无 Mutex 是安全的**：所有写路径都在 `withTransaction` 内（Room 单连接串行化）
- 领域层**无随机数**；单例均为无状态或纯缓冲，单测可用
- 空集合 / 除零 / 空值：`calculateAdherence(emptyList())`、`adherenceOf` 分母 0、
  `isLowStock`、`groupByUnit(emptyList())`、`PauseStatus.of`、`DiagnosticExport.plan(empty)`
  均已妥善处理
- `@Query` 的 `IN (:list)` 空列表：Room 生成的 `IN ()` SQLite 接受且求值为 FALSE
- **所有 DAO 方法均已成功生成 KSP 实现**，说明全部 SQL 通过 Room 解析与类型检查；
  JOIN 条件、别名、`@ColumnInfo` 映射逐条对齐
- 索引覆盖：`dose_slots` 5 个、`dose_records` 3 个、`inventory_transactions` 3 个，
  与实际查询逐条比对无缺失（除 P2-16 两处）
- 外键真实生效（`PRAGMA foreign_keys = ON`）；历史事实用 `RESTRICT` 防误删，
  派生数据用 `CASCADE`；`reminder_settings` 以 `medication_id` 兼主键与外键实现严格 1:1

**数据导入导出**
- CSV 转义覆盖逗号 / 双引号 / `\n` / `\r`，公式前缀判定用 `trimStart(' ','\t')`
  后前置单引号（防 ` =1+1` 绕过）；UTF-8 BOM 写出与读入剥离成对
- JSON：`ignoreUnknownKeys` + `coerceInputValues` + `encodeDefaults` + `explicitNulls`
  覆盖未知字段/未知枚举/缺字段/三态；`app` 标签校验阻止把任意 JSON 当备份；
  `formatVersion` 不识别即阻断
- 恢复顺序正确：**读 → 校验 → 快照 → 才开事务**，三重失败都发生在任何写操作之前
- 恢复幂等：显式主键 + 先清空 + 逐表回填；清库前撤全部闹钟 + 清托盘，恢复后立即重排
- FileProvider 路径与 `file_paths.xml` 精确匹配；`FLAG_GRANT_READ_URI_PERMISSION`
  同时加在 inner intent 与 chooser 上

**平台 API**
- **零 `PendingIntent.getService`**（Android O 之后闹钟必须走广播/Activity）
- **全部 7 处 PendingIntent 均为 `FLAG_IMMUTABLE`**。通知 Action 用 IMMUTABLE 是
  **正确且更安全**的选择 —— 它们无 `RemoteInput`，改成 MUTABLE 反而是安全降级
- **RequestCode 已改为内容寻址**（`alarmUri` / `actionUri`），`requestCode` 恒 0，
  身份完全由 `Uri` 参与 `filterEquals`。`AlarmScheduler.kt:32-71` 的碰撞分析
  （`10N+1` 与 `M ≡ 1 mod 10` 必相交）数学正确，旧缺陷确已消除
- `cancelAll`（`:298-319`）与 `schedule` 走同一构造函数，`forCancel` 只把
  `FLAG_UPDATE_CURRENT` 换成 `FLAG_NO_CREATE`（选择正确，`UPDATE_CURRENT`
  在无匹配项时会凭空创建记录），**不存在 mutable/immutable 混用导致取消失败**
- **`RTC_WAKEUP` 是唯一正确选择**（`ELAPSED_REALTIME` 重启后 epoch 归零会让排程全乱）；
  **零 `setRepeating`**（Doze 下会劣化到数小时），周期任务交给 WorkManager —— 决策正确
- **Android 14 全屏意图闭环完整**：`Notifications.kt:292-297` 调
  `canUseFullScreenIntent()` 并据此决定是否 `setFullScreenIntent`；
  `ReminderSettings.kt:14-23` 记录「假开关已摘除」；`PermissionCheckScreen.kt:246-249`
  复核同一事实并提供设置入口
- **`USE_EXACT_ALARM` 声明合规**（API 33+ 起可用，闹钟/日历类自动授予）；
  `SCHEDULE_EXACT_ALARM` 加 `maxSdkVersion="32"` 正确，避免 14+ 上默认拒绝
- **`canScheduleExactAlarms()` 前置检查 + 两处 `catch (SecurityException)` + 兜底
  `setAndAllowWhileIdle`** —— **降级链路本身完整**，问题只在 P1-3 的报告值
- **全层无自发起 `startActivity` 拉提醒页**；FSI 只经通知由系统代发，
  不受 Android 12+ 后台启动 Activity 限制影响
- **通知小图标合规**：`ic_stat_reminder.xml` 是 24dp viewport 白色实心矢量
  （`fillColor="#FFFFFFFF"`），符合「纯白单色 + alpha 着色」硬性要求
- `Notification.Action` 的 `icon=0` 无害 —— `Notification.java` 明确
  "As of Android N, action button icons will not be displayed"，且构造函数无零值校验
- **全层未使用 `RemoteInput`** → 不存在 RemoteInput 结果被伪造的通道
- 三个 Receiver 全部 `goAsync()` + IO；全量对账（实测 0.4–1.5s）移出广播窗口
  交给 `ReconcileWorker`，`BootReceiver` 只做毫秒级 enqueue
- `BootReceiver` 的 4 个 action **全是 protected broadcast**，第三方无法伪造，
  `exported=true` 是必要条件，**无权限缺口、无外部 DoS 面**
- **`MY_PACKAGE_REPLACED` 拼写正确**（`Intent.java` 确认值与 Manifest 一致）——
  真正拼错的是 `TIME_CHANGED`，见 P1-1
- **缺 `ACTION_LOCALE_CHANGED` 不是问题**：全部排程用 epoch millis + ISO 历法，
  locale 变化不改变任何槽位身份或触发时刻
- **重启 / 换包 / 清数据后恢复的三条路径齐全且有冗余**；
  `KEEP` vs `REPLACE` 的分工经得起推敲（冷启动 KEEP 避免自我饿死，
  换包 REPLACE 覆盖系统丢弃既有任务的场景）
- **对账幂等**：快照 → 重排 → 按快照清孤儿的顺序正确；同一 Uri 重复注册是替换非堆积；
  `presnap` 机制正确解决了「先删行后对账」的孤儿窗口
- **逐条推演四条触发路径（MAIN/ADVANCE/SNOOZE/REPEAT），未发现重复发通知路径**：
  `notifId` 相同 ⇒ `notify()` 是覆盖非叠加；三重判据确保补响只响一次
- **`DoseActionReceiver` 无伪造路径**：`exported="false"`，且有 action 白名单、
  业务键解析、`findOpenSlotId` 反查、`isStillOpen` 三重状态+日期校验
- **WorkManager 约束为空是显式决定且有 KDoc 论证**（物理断网，
  一旦误加 `NetworkType.CONNECTED` 会永远不执行且无报错 —— 这条风险已被
  `ReconcileWorkerTest` 断言守住）；15 分钟恰为名义下限；`doWork` 对
  `CancellationException` 重抛、其余 `Throwable` 返回 `Result.retry()`
- `key.properties` / `local.properties` **均未被 git 跟踪**，`.gitignore` 覆盖到位
- `gradle-wrapper.properties` 用第三方镜像但**锁了 `distributionSha256Sum`**，完整性有保障

---

## 8. 建议修复顺序

按「改动量 / 风险消除比」排序，前 5 条都是几行到十几行的改动：

| 序 | 项 | 理由 |
| :--- | :--- | :--- |
| 1 | **P1-1** Manifest `TIME_SET` | 改 1 行，恢复整条「改时自愈」，零风险 |
| 2 | **P1-6** `restateSlot` 目标校验前移 | 5 行，消除契约违反 |
| 3 | **P1-5** 库存三入口改 `isWithinRange` | 3 行，止住静默账实不符 |
| 4 | **P1-4** 零时点/零星期守卫 | 2 条 `require`，止住「提醒永不响」 |
| 5 | **P1-2** `onNewIntent` + `setIntent` | 6 行，消除「给错药发提醒」的串改风险 |
| 6 | **P2-30** `.catch` 终止符 + 错误卡加重试 | 今天页永久冻结是可感知的故障 |
| 7 | **P2-23/24** 深色对比度 | 实测 1.50:1，是可访问性硬缺陷 |
| 8 | **P1-8** `AppLog` 分发移出锁 | 6 行，修掉注释与代码的矛盾 |
| 9 | **P1-7 / P2-15** 补备份量程与格式校验 | 纯函数 + 断言，测试成本最低、风险最高 |
| 10 | **P1-3 + P2-3** `currentPrecision` 报告值与降级日志等级 | 让自检页对用户说真话 |
| 11 | **P0-1** 日志脱敏 + 砍掉运行期日志自动外投 | 唯一 P0，且涉及隐私承诺 |
| 12 | **P2-32/33** 补 `proguard-rules.pro` + CI 加 lint/release | 消除"一接就红"的工程隐患 |

**P2-1（全局替换 `runCatching`）建议单独一个 PR**，并配一次全量对账的真机走查。

---

## 9. 待实机验证项（不下结论）

以下几条静态分析无法定论，**本次未作为缺陷计入**，列出供后续实机确认：

| # | 事项 | 为什么无法定论 |
| :--- | :--- | :--- |
| 1 | `DatePickerDialog(...).apply { datePicker.minDate = … }.show()`（`ManualDoseScreen.kt:298,314`、`MedicationDetailScreen.kt:656-659`）与 `DoseRecordDetailScreen.kt:715` 的 `dialog.datePicker.maxDate = …` **在 `show()` 之前访问 `getDatePicker()`** | 我原本判断这会 NPE（`AlertDialog.show()` 才调 `onCreate` 构造 `mDatePicker`），**并写了 Robolectric 探针实测：不抛异常**。但 Robolectric 对 `DatePicker` 有 shadow（存储值不做 delegate 的按日截断），**无法据此推断真机行为**。需要真机点一次这三个入口。若真机 NPE，则 `setMinDate`/`setMaxDate` 的调用顺序都要改到 `show()` 之后 |
| 2 | API 31/32 上 `canScheduleExactAlarms() == false` 时 `setAlarmClock` 是否真的抛 `SecurityException` | 代码自己在 `:232-233` 做了 `catch (SecurityException)` 兜底并在失败后落到 INEXACT，所以**排程不会错**。但 P1-3 报告的档位错误在真机上具体显示成什么文案，需要看自检页截图 |
| 3 | `AlarmScheduler.kt:223` 兜底档位在真机上产生成百上千条 AlarmClock 条目，对电量与状态栏的实际影响 | 需要长周期真机观察（1 天以上），无法静态判断 |
| 4 | Lint 报的 3 个 `NewApi` 是否在 OEM ROM 上真会 `NoSuchMethodError` | 调用链有 `SDK_INT >= Q` 守卫，**静态判断是误报**；但 `DownloadsLogExporter.kt:322-323` 的 `downloadsCollection()` 确实无守卫，需确认它只被测试调用 |
| 5 | `FLAG_KEEP_SCREEN_ON` 整夜亮屏（P2-5）的实际耗电量级 | 需真机 overnight 实测 |

---

## 10. 本次审查的自限

诚实标注本次**没有**覆盖到的：

- **未跑过真机 / 模拟器**，全部结论来自静态阅读 + JVM 侧执行。
  AGENTS.md §二 第 3、4 步（「实测」「看图」）本次**未执行**，
  因此界面类结论（尤其 5.1 的叠色计算）虽经脚本验证，**未经过"亲眼看图"确认**。
- **未审查 `scripts/app_screenshots.py`（46 KB）** 与 `scripts/release.py` 的实现正确性，
  只核对了它们在 CI/走查体系中的位置。
- **未审查 22 个单测文件与 2 个快照测试的断言质量**，只确认它们当前全绿。
  值得一提：P1-4 / P1-5 / P1-6 / P2-7 这四条**当前没有任何测试会变红** ——
  不是测试写得不好，而是这些缺陷恰好落在
  「所有不变量仍然成立、只是数据悄悄错了」的死角。
- **未审查 `docs/` 下 15 份设计文档与实现的一致性**（仅发现
  `AGENTS.md` 声明 AGP 8.11.1，实际根插件为 8.13.0；Gradle 8.14 与 wrapper 一致）。

**最后一条纪律建议**（针对上面那个死角）：
台账的 `SUM == balanceAfter` 守恒只能证明**账本自洽**，证明不了**账本与用户输入一致**。
后者需要针对「输入 → 落库值」这个映射单独写断言，而这类断言当前是缺失的。

---

*审查执行：2026-10-03 21:57 (GMT+8) · 未修改任何生产代码，仅新增本报告*