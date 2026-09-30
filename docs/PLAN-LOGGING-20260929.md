# PLAN-LOGGING-20260929 —— 日志门面与落地方案（已自审）

> 2026-09-29。起因：全项目排查"哪些关键节点缺 logcat 记录"。
> 结论先行：闹钟链路日志已基本补齐（P0-1 修复那轮），真正的盲区是
> **核心记账服务（0 条日志）、AlarmScheduler 成功路径（0 条）、ViewModel 吞异常、
> 全局没有任何崩溃留痕**。
> 方案：**不引第三方日志库**，自建一个 ~150 行的纯 Kotlin 薄门面 `AppLog`，
> 双出口（logcat + 应用私有日志文件）+ 崩溃钩子 + 设置页导出入口。

---

## 1. 现状盘点（缺口清单，按严重度）

现状：约 36 条日志，全部裸 `android.util.Log`，无第三方库；
tag 风格不统一（`"AlarmReceiver"` 字面量 / `TAG` 常量 / 全限定 `android.util.Log` 混用）。
分布极度集中：`core/alarm` 包 33 条，其余 3 条（App 入口 1、DataExporter 2）。

| # | 位置 | 缺什么 | 后果 |
| :--- | :--- | :--- | :--- |
| G1 | `DoseTrackingService`（**全文件 0 条日志**） | 打卡/跳过/撤销/改判/改剂量/补录/盘点/补货/`reconcileSchedule` 全部静默；**幂等锚点 return false 的分支无记录**（`markCompletedIfOpen == 0`） | 用户"点了没反应"时无法区分：槽位不存在 / 已被处理 / `require` 抛异常。台账出问题（历史踩过"扣两次""差 1000 倍"）时，库里只有结果没有时间线 |
| G2 | `AlarmScheduler` 成功路径 | `schedule()` 只在 `SecurityException` 时 log；成功排了哪个 uri、什么触发时刻、**实际落在三档的哪一档**，零记录；`cancelAll` 也不记 | 排查"闹钟没响"没有第一现场。撞上 §2 坑 6 自己立的纪律"档位降级必须可查可见"——自检页能查**当前**档位，但每一次排程实际用了哪档无处可查 |
| G3 | 全局 | 没有 `UncaughtExceptionHandler` | 未捕获异常直接崩，死前无留痕。用户白天用、晚上才发现崩了，logcat 早滚没了。**这是"落地到文件"最强的一条理由** |
| G4 | ViewModel catch 块 | `ProgressViewModel.kt:283` 注释写"不静默"，实际吞掉 `Throwable` 只置 `_hasMoreTimeline=false`，**一行 log 都没有**；Stats/Settings/Progress/Inventory 四处导出/备份的 catch 只 Toast `e.message`（可 null），异常栈全丢 | 正是 §2 坑 6 的"静默降级 = 给用户虚假的保证" |
| G5 | `DoseActionReceiver` | 只有失败日志；通知栏打卡/推迟的**成功动作**无记录 | 通知栏直接写库是并发风险最高的入口（进程可能刚被拉起） |
| G6 | `DataExporter`（约 1000 行 / 2 条日志） | `restoreBackup` 写了多少行、`importBackup` 失败在哪、安全快照写没写成 | 数据安全路径全靠返回值猜 |
| G7 | `MedicationAdminService`（0 条） | `saveReminderPolicy` / `setPausedUntil` / 归档等写路径 | I9 类不变量出问题时没有第一现场 |

不算缺口：`ReconcileWorker` 已把 `Result.retry()` 前的真实异常 log 出来，避开了
AGENTS.md "Worker 吞异常"那个坑；`AlarmReceiver` / `AlarmReconciler` / `BootReceiver`
链路完整，保持现状即可。

---

## 2. 方案总览

```
调用方 (alarm / domain / ui / data)
   │  AppLog.i/w/e(TAG, msg, throwable?)
   ▼
AppLog (object, 纯 Kotlin, 零 android.* import)          ← core/domain 可直接用
   │  ① 同步 append 进内存环形缓冲 (512 行, 丢旧)          ← 崩溃时可同步捞出来
   │  ② 同步分发给各 sink（sink 自己决定是否异步）
   ▼
┌─────────────────────┬──────────────────────────────┐
│ LogcatSink          │ FileSink                     │
│ 转发 android.util.Log│ 单线程 dispatcher 追加写      │
│ (debug 全量,        │ filesDir/logs/app-YYYYMMDD.log│
│  release 只 WARN+)  │ 按天滚动, 保留 7 天,          │
│                     │ 启动时清理过期文件            │
└─────────────────────┴──────────────────────────────┘

崩溃钩子: UncaughtExceptionHandler
   → 同步把环形缓冲 + 堆栈写 filesDir/logs/crash-YYYYMMDD-HHmmss.txt
   → 交回系统默认 handler（绝不吞崩溃）
```

- 文件放 `filesDir/logs/`（App 私有，跟随卸载删除；不走 `getExternalFilesDir`，
  避免与导出文件混淆，也避免用户在文件管理器里误碰）。
- 导出入口：设置页"导出诊断日志"→ 打包最近日志为一个文件 → 复用
  `DataExporter.shareFile(context, file, mime, title)`。**只在设置页放**，
  不做自动上传——本项目无任何网络栈，也不应该有。

---

## 3. 关键决策点自审

每条都是"方案定型前被质疑过、并给出排除理由"的点。
自审方法：对每个决策问一遍"实现坏成什么样它救不了？"（与 §3 单测纪律同源）。

### D1 为什么不引 Timber / logback-android / XLog

引 Timber 的实际收益只有 tag 管理与可插拔 Tree，而本项目真正需要的三样东西——
**文件滚动、UncaughtExceptionHandler、日志导出**——它一个都不提供，还是得自己写。
等于引了依赖活没省。logback/XLog 是完整日志框架，与项目"轻依赖手工装配"
（无 Hilt、无网络栈、80 个文件）的体量不匹配。
**排除。自建门面，规模 ~150 行，全部可读可测。**

### D2 为什么 AppLog 必须是纯 Kotlin（零 `android.*` import）——不是洁癖，是会炸

两个独立理由，第二个是硬的：

1. 规范：领域层（`core/domain`）无 `android.*` 依赖。`DoseTrackingService`（G1 最需要
   补日志的地方）在 domain 层。
2. **硬故障**：`app/build.gradle.kts` 的 `testOptions.unitTests` **没有开**
   `returnDefaultValues`。纯 JVM 测试（jqwik `@Property`、非 Robolectric 的单测）
   里任何代码路径碰到 `android.util.Log` 会直接抛
   `RuntimeException: Method i in android.util.Log not mocked`。
   `SlotProjectionEngine` 的属性测试就是纯 JVM 的——如果投影引擎或它的调用链
   里埋了裸 `Log` 调用，属性测试全红，而且是**与被测逻辑无关的红**。

**决定**：`AppLog` 零 android import；输出通道抽象为 `LogSink` 接口，
Android 实现在 `CarroMedApp.onCreate` 时 `AppLog.install(...)` 挂上
（与 `CurrentDateHolder.install(application)` 同一模式，仓库有先例）。
**未 install 时默认 sink = no-op + `println` 到 stdout**，所以：

- 纯 JVM 测试天然安全（no-op）；
- Robolectric 测试走 stdout/捕获，不炸；
- 任何组件在 `Application.onCreate` 之前打日志（理论上不存在，但防御）也不崩。

失败模式自查：如果有人未来在 `AppLog` 里"顺手"加回 android import，
jqwik 测试全红会立刻暴露（这正是 D2-2 的故障转成了发现机制）。

### D3 崩溃瞬间会丢最近几条日志吗？——会，用环形缓冲兜住

FileSink 走异步 dispatcher 是为了不卡主线程（D4），代价是**崩溃时未落盘的
缓冲行全部丢失**——而崩溃前的最后几行恰恰最有价值。
异步写与"崩溃前留痕"在同一个机制里不可兼得，所以加一层：

- `AppLog` 维护一个 **512 行的内存环形缓冲**，每条日志同步 append（内存操作，纳秒级）；
- 崩溃钩子里**同步**（阻塞、不走 dispatcher）把环形缓冲整体 dump 进 crash 文件，
  再写堆栈，然后交回默认 handler。

失败模式自查：

- *环形缓冲是可变共享状态，崩溃发生在 append 中途怎么办？*
  dump 侧只读，最多读到半行文本，可接受（crash 文件标注"可能包含半行"）。
  append 用 `synchronized`（512 行数组 + 下标，争用可忽略）。
- *崩溃钩子自己再抛异常怎么办？* 钩子整体 try/catch 包住，
  任何失败都保证**最终调用默认 handler**——留痕失败不能变成二次故障。
- *`defaultHandler.uncaughtException` 递归触发怎么办？*
  钩子入口先判 `installed` 幂等，且 install 时保存原 handler 一次性替换。

### D4 主线程 IO——BroadcastReceiver 的 `onReceive` 也在主线程

`AlarmReceiver.onReceive` / `DoseActionReceiver.onReceive` 在主线程打日志，
同步写文件会触发 StrictMode 且拖慢广播窗口（ANR 预算 10s，但没必要花）。

**决定**：`AppLog` 的调用路径只做两件同步事——环形缓冲 append + 分发给 sink；
`LogcatSink` 的 `android.util.Log` 本身是内核 ring buffer 写入（微秒级，主线程安全）；
`FileSink` 把格式化后的行 `launch` 到**自己的单线程 dispatcher**（`Dispatchers.IO.limitedParallelism(1)`，
或等价 `newSingleThreadContext`），保证文件内行序与调用序一致（单写者，无需锁文件）。

失败模式自查：*进程被系统杀（非崩溃）时缓冲丢失？* 接受。这类丢失没有崩溃可循，
且 FileSink 的写入延迟是毫秒级，实际窗口极小。不为它上同步写（会重新引入 D4 的问题）。

### D5 文件滚动与保留策略——按天 + 上限双保险

- 文件名 `app-YYYYMMDD.log`，跨天首条日志时切换新文件（写前比对当天日期，无需定时器）。
- 保留 7 天：**只在 `CarroMedApp.onCreate` 清理一次**（列目录删过期文件），
  不在写路径上做——写路径多一次目录扫描就是把 IO 问题请回来。
- 单日体积护栏：单文件超过 **2 MB** 就滚动成 `app-YYYYMMDD-N.log`。
  理由见 D7 的量级估算：正常使用一天 INFO 量 < 100 KB，超 2 MB 必然是
  某个循环在刷屏——护栏本身就是异常信号（写一条 WARN 提醒自己）。

失败模式自查：*清理删错文件？* 只删 `filesDir/logs/` 目录内
名字匹配 `app-*.log` / `crash-*.txt` 且修改时间早于 7 天的文件，
目录本身不删、不匹配模式的不碰。crash 文件**不按天数删**，
只保留最近 5 个——崩溃样本比日志稀缺得多。

### D6 隐私——日志里记不记药名、剂量？

会记（药名 + 剂量 + 时点），这是排查"账不对"的最低必需信息。
**这是有意识的接受**，依据：

- 文件在 `filesDir/logs/`，App 私有存储，其他 App 不可读，`allowBackup=false`
  （已核实 Manifest）不会进云备份；
- 导出是用户显式动作，且导出的是用户自己的数据；
- 绝不记录：备注原文（`note` 是自由文本，可能含病情描述）——日志里用
  `note=<长度>N` 代替；不记录任何位置、联系人、设备标识。

若未来接入任何云端上报，此条必须重新评审（当前无网络栈，不适用）。

### D7 级别策略与量级预算

| 级别 | 语义 | logcat(debug) | logcat(release) | 文件 |
| :--- | :--- | :--- | :--- | :--- |
| `e` | 失败了且业务受损 | ✓ | ✓ | ✓ |
| `w` | 降级/吞异常/幂等拒绝 | ✓ | ✓ | ✓ |
| `i` | 关键事务时间线（排程/打卡/撤销/备份） | ✓ | ✗ | ✓ |

- release 的 logcat 关 INFO 只是为了省系统缓冲，**文件不关**——离线排查全靠它。
- 量级预算：全项目关键事务一天约几十到几百条（闹钟排程 67 个/轮 × 若干轮 +
  打卡几条），每行 < 200 字节，一天 < 1 MB、常态 < 100 KB。7 天保留 < 5 MB，
  对现代设备可忽略。
- **高频路径纪律**：`AlarmReconciler.rescheduleAll` 这类遍历几十个槽位的循环，
  只在**循环外打汇总**（"本轮排了 N 个、取消了 M 个、降级 K 个"）与**异常分支**打日志；
  循环体内禁止 INFO。这一条写进 code review 检查项。

### D8 与现有 36 条裸 `Log` 调用的关系——分步替换，不搞一刀切

`AppLog.i` 内部转发 `android.util.Log`（LogcatSink），所以替换调用点后
logcat 行为不变、`tools/` 下解析 logcat/dumpsys 的脚本不受影响
（`alarmcheck.py` 解析的是 `dumpsys alarm`，与 logcat 无关，已核实不受影响）。
替换按第 5 节步骤分批提交，每批独立编译+测试，符合 §7 分步提交纪律。
顺手把 tag 统一成各文件的 `private const val TAG = "<SimpleName>"`。

### D9 多进程——已确认单进程，无需跨进程文件锁

已核实 Manifest 全文无 `android:process`，WorkManager/Receiver/Activity 同进程。
FileSink 单写者模型因此成立。**若未来引入多进程（如独立 :push 进程），
此方案必须重新评审**——把这条写进 `AppLog` 的 KDoc 作为护栏。

### D10 测试策略——门面自己也要能被测，且要能失败

- `AppLog` 单测（纯 JVM，无需 Robolectric）：
  - 环形缓冲满 512 后丢最旧（变异验证：把容量改成 511 测试就该红）；
  - 未 install 时 no-op 不抛异常；
  - sink 分发顺序与异常隔离（一个 sink 抛异常不影响另一个、不影响调用方）。
- `FileSink` 的滚动/清理逻辑抽成**可注入时钟与文件系统根目录**的纯函数，
  单测直接造临时目录断言文件名与删除集合（变异验证：日期比对写错时变红）。
- 崩溃钩子无法在单测里真崩，验证靠**模拟器实测**（第 5 节 Step 1 的验收项，
  debug 构建给 `DevDataReceiver` 加一个 `CRASH` 广播专门触发）。

### D11 ViewModel 构造器——导出入口不许碰 ViewModel 参数（§2 坑 5）

"导出诊断日志"放设置页。实现**只用 `LocalContext` + 直接调 AppLog/DataExporter 的
静态入口**，不给任何 ViewModel 加参数、不加构造器注入——
`AndroidViewModelFactory` 反射单参构造器的坑（StatsViewModel 崩溃事故）不再重演。

### D12 拒绝的备选方案存档

| 备选 | 排除理由 |
| :--- | :--- |
| 全量同步写文件（不要异步） | 崩溃不丢日志，但主线程 IO（D4），广播路径硬伤 |
| 只用 logcat 不落文件 | G3 不成立：崩溃即失忆，用户侧永远拿不到 |
| crash 上报 SDK（Bugly/Crashlytics） | 引入网络栈与隐私合规，项目无网络且是健康数据，D6 直接否决 |
| `println` 替代 logcat | release 下 tag/级别全丢，`alarmcheck` 类工具链路断裂 |
| 在 domain 层用接口注入 Logger（构造器参数） | 改 5 个服务的构造器签名，传染所有测试 fixture；object + install 与仓库 `CurrentDateHolder` 先例一致，侵入最小 |

---

## 4. 日志点位规范（补点时照此执行）

- 文案**英文**（AGENTS.md §6），格式 `<action> <subject> <key facts>`，
  例如 `takeDose completed slot=12 med=3 dose=1.5 balanceAfter=28.5`。
- 每条关键事务日志必须带**可串联的键**：slotId / medId / recordId / alarm uri。
- **幂等拒绝与校验失败必须打 `w`**：这是"点了没反应"类工单的唯一线索。
  返回 false 的每个分支一行，含原因（`slot-missing` / `already-settled` / `not-open`）。
- `AlarmScheduler.schedule` 成功路径一条 `i`：
  `scheduled uri=<alarmUri> triggerAt=<ts + hh:mm> precision=<EXACT|ALARM_CLOCK|INEXACT>`；
  `cancelAll` 一条 `i`（含 kinds）。
- `DoseTrackingService.appendLedger` 一条 `i`：
  `ledger med=3 record=45 tx=TAKEN_DEDUCT change=-1500 balanceAfter=27000`——
  **这是台账审计线**，出问题时与 DB 对账的桥梁。
- ViewModel catch 块一律 `AppLog.w(TAG, "<what> failed", t)`（带异常对象，不只 message），
  再走原有的 Toast/状态降级。

---

## 5. 实施步骤（每步独立编译 + 测试通过，可单独回滚）

| 步骤 | 内容 | 验证 |
| :--- | :--- | :--- |
| **S1 门面** | `core/domain/AppLog.kt`（纯 Kotlin：环形缓冲 + sink 分发）+ `core/alarm/AppLogAndroid.kt`（LogcatSink/FileSink/CrashHandler，android 侧）+ `CarroMedApp.onCreate` install + 清理逻辑 + `DevDataReceiver` 加 debug-only `CRASH` 广播 | 单测（D10）全绿；`clean assembleDebug testDebugUnitTest` + `compileReleaseKotlin`；模拟器实测：`am broadcast ... dev.CRASH` → 确认 crash 文件生成、App 正常崩、重启后日志文件含崩溃前最后 N 行 |
| **S2 替换 alarm 包** | `core/alarm` 现有裸 `Log` → `AppLog`；补 `AlarmScheduler.schedule/cancelAll` 成功路径（G2）、`DoseActionReceiver` 成功动作（G5）；tag 统一 | 全绿；模拟器走查闹钟链路，`adb logcat -s CarroMed` 可看到排程/触发/取消完整时间线 |
| **S3 记账服务** | `DoseTrackingService` 关键事务出入口 + 幂等 false 分支 + `appendLedger` 审计线（G1）；`MedicationAdminService` 写路径（G7） | 全绿；模拟器：打卡→撤销→改判→补录各一次，导出日志核对时间线与 DB 一致 |
| **S4 UI/Data 层** | ViewModel catch 块补 `w`（G4）；`DataExporter` 备份/恢复结果（G6）；设置页"导出诊断日志"入口（D11，不碰 ViewModel 构造器） | 全绿；UI 走查 `--clear --seed` 无新增失败项 + 亲眼看图；导出日志用系统分享面板拿到文件人工核对 |
| **S5 收口** | `docs/CHANGES-20260929.md` 追加摘要；AGENTS.md §8 坑表补一行"纯 JVM 测试碰 android.util.Log 会 not mocked" | §9 收口自检全过 |

新路由注意：设置页导出入口是**既有页面内的动作**，不新增 Screen，无需登记
`tools/app_screenshots.py` 的 `PROGRAM`。

---

## 6. 验收清单

- [ ] `./gradlew clean assembleDebug testDebugUnitTest` 全绿，项数变化仅来自新增的 AppLog 单测
- [ ] `./gradlew compileReleaseKotlin` 通过
- [ ] release 日志策略实测：`assembleRelease` 装模拟器，确认 logcat 无 INFO、文件里有
- [ ] 崩溃演练：CRASH 广播 → 崩溃 → 重启 → crash 文件存在且含环形缓冲与堆栈
- [ ] 排程档位可见：日志能回答"昨天 20:00 的那个闹钟落在哪一档、几点排的"
- [ ] 记账审计线：任意一次打卡能在日志里串起 `slot → record → ledger` 三跳
- [ ] 日志文件 7 天保留与 2 MB 护栏生效（临时把时钟/阈值注小验证）
- [ ] 导出入口在设置页可用，分享拿到的是完整文本文件
- [ ] 权限模型不变：无新增 Manifest 权限、无网络请求

---

## 7. 风险与回滚

| 风险 | 缓解 |
| :--- | :--- |
| 门面引入后某纯 JVM 测试变红 | D2-2 机制反向暴露：说明有人把 android import 带回了 domain，修 import 而不是退方案 |
| 日志刷屏拖慢 reconciler | D7 高频纪律 + 2 MB 护栏告警 |
| crash 钩子自身故障 | 全 try/catch + 必交回默认 handler（D3） |
| 回滚 | S1 是纯新增文件 + `CarroMedApp` 三行 install，git revert 即可；S2-S4 每步独立提交，单独 revert |
