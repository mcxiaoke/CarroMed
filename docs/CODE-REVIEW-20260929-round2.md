# 全项目代码审查报告（round2，2026-09-29）

> **审查时间**：2026-09-29 11:55–12:10 (GMT+8)
> **审查方式**：静态精读 + grep/机械核验。本人负责数据/领域层，另以 3 个并行只读子代理分审
> 闹钟层（`core/alarm/`）、UI 层（`ui/`）、备份导出与设置（`DataExporter`/`Settings*`）；
> **子代理与本人的全部论断均回到当前代码逐条复核**，过时项剔除见 §二。
> **本轮未改任何源码、未运行模拟器**；单测基线：本会话早间实测 302 项全绿（34 suites），
> `CHANGES-20260929.md` 记录 103caed 后 308 项全绿。
>
> ## ⚠️ 审查基线（并发改动声明）
>
> 审查期间**另一会话正在同一工作区实施 FIX-PLAN 的 M1 批次**，报告写作时存在 9 个未提交文件
> （`git status` 快照 11:54）：`AndroidManifest.xml`、`AlarmReconciler.kt`、`AlarmScheduler.kt`、
> `ReminderSettings.kt`、`DoseSlotDao.kt`、`PermissionCheckScreen.kt`、`SettingsScreen.kt`、
> `SettingsViewModel.kt`、`TodayScreen.kt`（`git diff --stat` 合计 +482/−99）。
> 本报告基线 = **HEAD `68bf451` + 上述工作区改动**，每条结论标注其在基线中的状态。
> 子代理部分结论读取的是修复前代码，已逐条回查，见 §二。

---

## 〇、一页结论

1. 今天已有的 4 份报告（sba/zcg/ds/DB）+ `TEST-AUDIT` + `FIX-PLAN-20260929.md`（62 项去重、54 项开放）
   已经覆盖了绝大部分问题。**本报告不重复已收录项**，产出为：
   - **新发现 12 组**（FIX-PLAN 未收录），含 2 个 P1（§一）；
   - **证实 FIX-PLAN §六 的 1 条"待复核"**（sbf P1-11 结束日先后校验，属实在开放）（§三）；
   - **剔除 10 条过时的子代理结论**（103caed 修复后已不成立，或已在工作区修复）（§二）；
   - **对 FIX-PLAN 勘误 6 的一处分歧**（MIGRATION_1_2 是否会执行，两说相反且当前无法实证）（§四）。
2. 我核验的 FIX-PLAN 已收录项（M5-5 孤儿 policy_times、CYCLE→5、`cycleOnDays=0`、
   `escapeCsv` 不处理 `\r`、`(status,scheduled_ts)` 无索引等）**全部属实、仍然开放** —— 与 FIX-PLAN 一致。
3. 修复正在滚动进行：M1-1 / M1-2 / M1-3 / M1-5 / M1-6 / M1-7 在写作时的工作区里已有未提交实现
   （详见 §二），收口时需要重跑全量测试与走查。

---

## 一、新发现（FIX-PLAN 未收录）

### P1

#### N1 详情页/库存页一次性加载：从子页面返回后显示与库不一致的陈旧数据

- **位置**：`ui/screen/detail/MedicationDetailViewModel.kt:68,71,160,168,178`；
  `ui/screen/inventory/InventoryViewModel.kt:69-73`
- **证据**：
  - `init { loadData() }`（:68），`loadData()` 其余调用点只有自身写操作之后（:160/:168/:178）；
    `InventoryViewModel` 同构（`init { load() }`，`load()` 仅在 :170/:188/:214 自写后调用）。
  - 全 `ui/` 目录 grep `ON_RESUME|LifecycleEventObserver|LaunchedEffect…resume|reload|refresh()` ——
    **唯一命中是 `PermissionCheckScreen.kt:89`**（且该文件正是本轮工作区在改的），没有任何
    详情/库存页在导航返回时触发重载。
  - 路由链 `med_detail → med_edit / med_reminder / med_inventory → refill` 返回时
    `NavBackStackEntry` 不销毁，ViewModel 与其 state 原样保留。
- **后果**：改完药名/预警线/提醒计划回详情页仍是旧值；补药入库返回库存页余额与流水不变 ——
  展示与库不一致，直接误导用户对数据的信任。
- **级别**：**P1**。建议并入 M4（口径统一）或新增 M7 工单。

#### N2 sbf P1-11 证实：结束日早于起始日可以保存，保存后该药零提醒且无任何提示

- **位置**：`ui/screen/reminder/ReminderSettingsViewModel.kt:208-217`（save 的全部校验）、`:298-300`
- **证据**：`save()` 只校验星期（:210-213）与时点个数（:214-217），**无任何 `endDate >= startDate` 比较**；
  :298 的 `pastEnd` 只作用于 `refreshPreview` 的预览文案，不阻断保存。日期来自系统 `DatePickerDialog`
  （格式合法但先后不受控）。
- **后果**：结束日早于起始日 ⇒ `SlotProjectionEngine.projectSlots` 的
  `effectiveStart.isAfter(effectiveEnd)` 命中空投影（SlotProjectionEngine.kt:77-79）⇒
  该药没有任何槽位、不响任何提醒，保存却显示成功。
- **级别**：**P1**（原 sbf 定级）。FIX-PLAN §六把它列为"待复核"——本报告完成复核，**属实仍开放**，
  建议移入 M7 批（与 M7-1 同文件，顺手修）。

### P2

#### N3 闹钟触发路径上用 `goAsync` 跑全量对账，有 ANR 风险（与本项目自己的文档矛盾）

- **位置**：`core/alarm/AlarmReceiver.kt:44`（`goAsync()`）+ `:76`（`rescheduleAll`）；
  `BootReceiver.kt:37` 同构；对照 `ReconcileWorker.kt:36`
- **证据**：
  - `AlarmReceiver` 协程体内每次都跑 `AlarmReconciler.rescheduleAll(appContext, db)`——含 Room 首次打开的
    identity 校验、每药品一次 14 天投影事务、每个开放槽位若干 AlarmManager binder 调用。
  - `ReconcileWorker.kt:36` 自己的 KDoc 写着：「不需要自己在 `BroadcastReceiver` 里
    `goAsync` + 起协程（**易被系统判为 ANR**）」——闹钟/开机两个 Receiver 恰恰这么做了。
- **说明**：耗时随 14 天 × 药品数 × 时点数线性放大；本报告**未实测**耗时，按风险计级。
  工作区在修的宽限补响（M1-7）会再增加每次对账的注册量，建议同批量化（见 §五 建议）。
- **级别**：**P2（需量化）**。FIX-PLAN 未收录。

#### N4 `CarroMedApp.onCreate` 每次进程启动都 `REPLACE` 周期任务，可能自己掐掉自己

- **位置**：`CarroMedApp.kt:40`（`ReconcileWorker.enqueue(this, replace = true)`）；
  `ReconcileWorker.kt:131`（`ExistingPeriodicWorkPolicy.REPLACE`）
- **证据/机制**：`REPLACE` 的语义是"取消并删除同名既有任务再入队"。Android 进程启动顺序固定为
  `Application.onCreate` → 组件；当 WorkManager 为执行该 Worker 而冷启动进程时，`onCreate` 会先跑，
  于是取消"正要执行的那次任务"，同时把 15 分钟周期计时重置为现在。而 `ReconcileWorker` 的定位是
  「前两层都失效时的最终兜底」（`ReconcileWorker.kt:28`），它最需要生效的场景恰是"进程已死"。
  `BootReceiver.kt:44` 用 REPLACE 有明确理由（升级/清数据后任务会丢），`CarroMedApp` 的无条件
  REPLACE 与 `ReconcileWorker.kt:124-126` KDoc 的"常态走 KEEP"相悖。
- **诚实标注**：子代理明确声明"取消是否真的掐断已派发的那次 JobService 未在设备上验证"。
  本报告复核了代码引用无误，但同样未实测。验证方法：`adb shell dumpsys jobscheduler | grep carromed`
  观察周期任务的 deadline 是否每次冷启动被重置。
- **级别**：**P2（需实测）**；若成立则升 P1（第三层兜底实际失效）。FIX-PLAN 未收录。

#### N5 跨 service 的多步写没有外层事务：中途失败产生部分写入

- **位置**：`ui/screen/edit/AddEditMedicationViewModel.kt` `save()`（约 :316-374）；
  `ui/screen/reminder/ReminderSettingsViewModel.kt` `save()`（约 :219-267）
- **证据**：保存链为 `adminService.saveProfile` → `adminService.saveReminderPolicy` →
  `trackingService.setStockTracking` → `trackingService.reconcileSchedule`（:371）→
  `rescheduleAll`（:374）；每个 service 内部各自 `withTransaction`，**四步之间没有共同事务**，
  UI 层也没有 `db.withTransaction` 包裹。提醒页同构（:262/:263）。
- **后果**：第 2 步抛异常 ⇒ 药品档案已保存、提醒计划未保存，且用户无提示。
  FIX-PLAN **M2-3 只收录了"无 try/catch（崩/卡 isSaving）"这一半**，"部分写入"这一半未收录。
- **级别**：**P2**。建议与 M2-3 同批修（同一处代码，统一 `runCatching` + 外层事务或失败回滚策略）。

#### N6 走查脚本 `--only` 把全部导航与断言步骤过滤掉，manifest 会谎称"全部通过"

- **位置**：`tools/app_screenshots.py:408`
- **证据**：
  ```python
  steps = [s for s in PROGRAM if not only or (s.action == "shot" and s.key in only)]
  ```
  `--only` 非空时，只保留 `action == "shot"` 的行，**所有跳转步骤与 `expect` 断言全被丢弃**：
  脚本停在当前页直接截图，既不导航也不校验；`manifest.md` 的断言段因此没有任何失败记录。
  这击穿了 AGENTS §4.1 约定 2「每次跳转带断言，不会静默截到走错页面的图」——
  `--clear` 首启路径尤其危险：本该跳过的页面会被截成"当前页"并登记为成功。
- **级别**：**P2**（门禁工具可信度）。修法：`--only` 只过滤"产出截图"，导航/断言/回顶步骤保留
  （或至少保留目标页所需的导航链）。FIX-PLAN 未收录。

### P3（低）

#### N7 非恢复路径的"先删行、后拍快照" ⇒ 幽灵闹钟（与 M5-1 同根因，但调用点未覆盖）

- **位置**：快照在 `rescheduleAll` 第一步（HEAD `68bf451`:79 / 工作区:111）；调用点先删后调：
  `AddEditMedicationViewModel.kt:371→374`、`ReminderSettingsViewModel.kt:262→263`、
  `MedicationDetailViewModel.kt:185（deleteById）→194（rescheduleAll）`
- **证据**：孤儿清理只遍历快照（`AlarmReconciler.kt:220-239`）；上述三处的删行发生在快照之前，
  被删槽位的身份不在快照里 ⇒ 其闹钟**永不 cancel**。
- **后果（已收窄）**：`AlarmReceiver.kt:50-53` 对 `slot == null` 静默 return ⇒ **不会错报通知**；
  Room `autoGenerate = true` 生成 `INTEGER PRIMARY KEY AUTOINCREMENT`，行 id 不复用 ⇒ 不会错配到新槽位。
  实害 = 每个被删/改的槽位在其原定时刻空唤醒一次（含 advance 种类），最多 14 天，白开库查询、耗电。
  **恢复路径的错药风险（id 空间交叠）是 M5-1 的 P1，本条只是同类根因的轻后果。**
- **修法建议**：把"拍快照"责任上移到任何删库动作之前（或 `rescheduleAll` 接受外部预拍快照），
  与 M5-1 的修法①一次覆盖全部调用点。
- **级别**：**P3**（低-中）。FIX-PLAN 的 M5-1 只写了恢复路径。

#### N8 `AddEditLogicTest` 仍含 4 条恒真/空测

- **位置**：`app/src/test/.../ui/screen/edit/AddEditLogicTest.kt`
- **证据**：`intervalDays_clampsTo2To30`、`cycleDays_clamps`、`daysOfWeek_toggle…`、`fivePolicyTypes…`
  断言的是 Kotlin 标准库 `coerceIn` / `distinct()` / enum `values()`（实现怎么坏都绿）。
  zcg 报告已点名 P1-12，未整改；FIX-PLAN **M6-2 收录的 4 处恒真不含本文件**（那是 I6 属性、
  `isAtMost`、`flowEmitsOnChange`、`ReminderSettingsTest:52`）。
- **级别**：**P3**。建议并入 M6-2（删除或改写成变异验证能红的断言）。

#### N9 README 的测试项数过期 5 处

- **位置**：`README.md:172,243,262,308`（另 `:308` 附近共 5 处写"71 项"）
- **证据**：当前实际为 308 项（`CHANGES-20260929.md:20`，103caed 后门禁）；本会话早间实测 302 项全绿。
  FIX-PLAN **M8-4 只列了 AGENTS.md（184 → 308）**，README 未列。
- **级别**：**P3**。随 M8-4 一并改。

#### N10 `InventoryTransactionBackup.txType` 没有默认值，`coerceInputValues` 对该字段的承诺失效

- **位置**：`core/data/BackupFormat.kt:163`（`val txType: TransactionType,` 无默认）
  vs `:103/:136/:150`（`policyType`/`status`/`status` 均有默认）
- **证据**：`DataExporter.kt:162` 配置 `coerceInputValues = true`，KDoc（:154）承诺
  「读到未知枚举值时降级为默认值……宁可少一个字段，也不能恢复不成功」。
  `coerceInputValues` 只处理**值无法识别**，救不了**键缺失**：缺 `txType` 的备份会以
  `MissingFieldException` 整份解码失败，落到 `inspectText` 的"不是有效的 CarroMed 备份文件"。
  （该字段何时进入格式本报告未考证；风险是手工编辑/旧格式备份的恢复失败。）
- **级别**：**P3**。与 M5-3 同批（校验/解码健壮性）。

#### N11 低危杂项（逐条核验过，均为当前代码事实）

| 项 | 位置与证据 | 说明 |
| :-- | :--- | :--- |
| a) 取消闹钟会凭空创建 PendingIntent | `AlarmScheduler.kt:213` 取消路径用 `FLAG_UPDATE_CURRENT`，无匹配项时系统会创建一个；应 `FLAG_NO_CREATE` | 不产生幽灵闹钟，仅残留记录 |
| b) 通知 id 算术可能溢出 | `Notifications.kt:104` `(slotId * 10 + requestCode).toInt()`；`:43` KDoc 的"唯一"只在 `slotId < 2.14e8` 成立 | 三按钮 action 已天然区分，这段算术本不必要 |
| c) 提前 return 跳过"触发后续期" | `AlarmReceiver.kt:50-53、:61` 两处 `return@launch` 跳过 `:76` 的 `rescheduleAll`；`:27` KDoc 称"每次响铃后就地续期" | 有周期 Worker 兜底，仅冗余缺口 |
| d) 推迟时长两套读口径 | 通知按药品级优先（`ReminderSettings.resolve`），今日页长按菜单只读全局（`TodayViewModel.kt:151` `globalSnoozeMinutes`） | 与 M7-5 同簇，建议同批 |
| e) 永不执行的扩展函数 | `MedicationDetailScreen.kt:832` `private fun Quantity.fmt(v: Float)`；6 处调用写成 `Quantity.fmt(...)`（:436/:493/:652/:682/:684/:793），Kotlin 成员优先于扩展 ⇒ 全部解析到 `Quantity.kt:23` 的成员 | 死代码反误导，随 M8-1 清扫 |
| f) 进程回收丢表单 | 全 `ui/` grep `SavedStateHandle\|rememberSaveable` 零命中 | 进程被回收后返回，新增/编辑表单全丢 |
| g) 闹钟对账异常静默 | `MainActivity.kt:43`、各 VM `runCatching { rescheduleAll }` 无 `onFailure` 无日志 | 与 AGENTS"闹钟必须可排查"冲突；建议统一 `.onFailure { Log.e }` |
| h) `record_id` 无索引 | `InventoryTransactionEntity` 索引仅 `medication_id`、`created_at`；`deleteDoseRecordLike` 按 `record_id` 回溯 | 小表可接受，随 §六 性能实测一并看 |
| i) `buildBackup` 无事务 | `DataExporter.kt:257` 起连续读 7 张表；全文件唯一 `withTransaction` 在 `:510`（恢复路径） | 导出期间若发生打卡/对账写入，备份跨表不一致 |
| j) 恢复成功后设置页不重读 | `SettingsViewModel.confirmRestore` 成功后不重读 `app_settings` | snooze/铃声等回显停在恢复前的值 |
| k) debug 播种/CLEAR 卫生 | `DevSampleDataSeeder.kt:41-45`（`isNotEmpty` 守卫、无事务）；`DevDataReceiver.kt:48` `clearAllTables()` 不撤闹钟/通知 | 仅 debug 源集，影响走查 |
| l) SAF 未持久授权 | `SettingsScreen.kt:68-72` 无 `takePersistableUriPermission` | 一次性读取，影响有限 |
| m) BOM 处理 | `DataExporter.kt:143` BOM 为不可见字面量常量；`readText`（:709-716）不剥离 BOM | 外部编辑器给 JSON 备份加 BOM 后解码失败；建议常量写 `"\uFEFF"` 并在读取时剥离 |
| n) 导出文件名秒级精度 | `DataExporter.kt:171` `timestamp()` 秒级；`exportFullBackupJson:648` / `writeSafetySnapshot:661` 同名即 `writeText` 截断覆盖 | 同秒两次导出覆盖前一份 |

---

## 二、核验后剔除的过时结论（子代理报告 vs 当前基线）

子代理部分结论读取于 103caed（11:01）之前或工作区 M1 改动之前，逐条回查后**以下 10 条不再成立或已收录**：

| 子代理原结论 | 核验结果（当前代码） |
| :-- | :--- |
| 闹钟#4 `DoseActionReceiver` 无 catch 即崩 | **已修**（103caed）：`DoseActionReceiver.kt:94` 有 `catch (t: Throwable)`，:97 注释同口径 |
| 闹钟#9 EXPIRED 槽位打卡永远失败 | **已修**（103caed）：`DoseSlotDao.kt:134` `markCompletedIfOpen` 已含 `EXPIRED` |
| 闹钟#1 宽限期内"已到点未投递"的闹钟被对账撤掉 | **工作区已修（未提交）**：`AlarmReconciler.kt:225` `withinGrace` 进入 `shouldKeep`、`:276-289` 宽限补响（即 M1-7/决策点 C）。子代理所引"HEAD 无 withinGrace"对 `68bf451` 成立 |
| 闹钟#2 `markExpired` 无状态守卫 | **工作区已修（未提交）**：`DoseSlotDao.kt:337-344` `AND status IN ('PENDING','SNOOZED')`（即 M1-3） |
| 闹钟#3 归档药槽位既不重排也不清理 | **已修两步**：103caed 的 `toggleArchive` 删槽 + 工作区 `AlarmReconciler.kt:205-214` 把归档药喂入投影清扫（2b） |
| 闹钟#6 Android 14+ 精确闹钟降级 | **已收录**为 FIX-PLAN M1-1（ds P0-1，P0）；`AlarmScheduler.kt`/`AndroidManifest.xml` 正在工作区修改 |
| 闹钟#10 逾期结算不撤托盘通知 | **工作区已修（未提交）**：`AlarmReconciler.kt:101-104,128` `cancelNotificationOf`（即 M1-6/DB B-05） |
| UI-H1 `events` 通道无人收集 | **已收录** M1-5；工作区 `TodayScreen.kt:113-115` 已接 snackbar（未提交） |
| UI-H2 自检页状态硬编码 | **已收录** M1-2（P0）；`PermissionCheckScreen.kt` 正在工作区修改 |
| UI-M2/M3/M6/M7/M8/M9/M10、闹钟#8/#11 | 分别对账到 FIX-PLAN **M4-1 / M7-5 / M2-1 / 决策点 F(DB B-02) / M3-2 / M7-9 / M7-1 / M3-1 / M1-4**，均已收录，不再单列 |

> 换言之：闹钟子代理的 12 条中严重以上结论里，**6 条是已修/在修/已收录**，
> 真正新增的是 goAsync（N3）、REPLACE（N4）、先删后快照（N7）与低危条目。

---

## 三、证实 FIX-PLAN §六 的"待复核"项

| 项 | 原状态 | 本报告结论 |
| :-- | :--- | :--- |
| sbf P1-11 结束日早于开始日 | §六"未复核" | **属实、仍开放**，见 §一 N2（含完整证据链）——可移入 M7 批 |
| sbf P1-13 导出口径（统计页导出全量 vs 页面周期） | §六"未复核" | **本轮未核**（超出分工范围），维持待复核 |

---

## 四、与 FIX-PLAN 的一处分歧（需实测定论）

**`MIGRATION_1_2` 到底会不会执行？**

- **本报告 + 备份子代理的读法**：`AppDatabase.version = 5`，迁移链只有 1→2（`AppDatabase.kt:94`）。
  Room 的 `MigrationContainer.findMigrationPath(1, 5)` 要求**完整链路**，缺 2→3/3→4/4→5 即返回 null
  ⇒ 走 `fallbackToDestructiveMigration()` 整库重建 ⇒ **MIGRATION_1_2 从未执行**，其自身缺陷（非 UNIQUE
  索引与实体 UNIQUE 索引同名冲突）不可达。
- **FIX-PLAN 勘误 6 的说法**：「v1 老库升级仍会执行 MIGRATION_1_2 并因同名非 UNIQUE 索引炸 Room schema 校验」
  （即认为部分链路会执行）。
- **裁决**：两说相反，且**当前无法实证**——`exportSchema = false` 没有 v1 schema JSON，无法在真库上重放。
  两说的**修复动作相同**（删掉 `MIGRATION_1_2`，发布前按纪律重建迁移，即 M5-9），
  分歧只影响"风险是否可达"的表述。建议发布前用一次性测试（构造 version=1 的库文件）定论。
  **级别**：P3（项目未发布、无 v1 用户，风险锁定在发布前）。

---

## 五、已核查、确认无问题的方面（本报告实证）

1. **闹钟身份不变量（P0-1）**：`AlarmScheduler.alarmUri` 内容寻址、`requestCode` 恒 0，
   `schedule`/`cancelAll` 同一构造；`AlarmIdentityTest` 强制走生产 `alarmIntent` 而非测试内影子。
2. **库存台账守恒（I1/I2）**：业务代码无任何 `UPDATE medications SET current_stock`；
   `takeDose`/`skipDose`/`undoDose`/`logManualDose`/`reconcileSchedule` 全部 `withTransaction`
   （`DoseTrackingService.kt:103,146,187,246,404`）；`undoDose` 按记录净额冲正。
3. **DST/闰年/跨午夜投影**：`SlotProjectionEngine.kt:96-121` 用 `LocalDate.plusDays`/`ChronoUnit.DAYS`，
   逐日 `atZone(zoneId)` 计算瞬时；DST 空洞日 `LocalDateTime.atZone` 自动顺延（如 02:30 → 03:30 同一真实时刻），
   重叠日取第一次出现，`DoseSlotDstServiceTest` + DST 属性测试覆盖。
4. **备份校验覆盖**：重复 `medications.id`（级联毁数据）、重复槽位唯一键、`dose_records`/
   `inventory_transactions`/`schedule_policies`/`policy_times` 主键重复均被 `validateBackup` 拦截
   （`DataExporter.kt:454-489`）；**但 `doseSlots.id` 本身与 `appSettings.key` 重复不在其中**——
   该项已被 FIX-PLAN M5-3 收录，本轮复核属实。
5. **重复时点防护**：`MedicationAdminService.kt:224-225` `require(timeKeys.distinct())` 已在位
   （103caed），`DuplicatePolicyTimeTest` 断言拒绝语义，测试全绿。
6. **SNOOZED 结算 SQL**：`getStaleOpenSlots` 对 PENDING 按 `scheduled_ts`、SNOOZED 按 `snooze_until_ts`
   且显式 `IS NOT NULL`，括号分组正确（`DoseSlotDao.kt:291-299`）。
7. **广播导出面**：`AlarmReceiver`/`DoseActionReceiver` `exported="false"`；`BootReceiver` 仅收系统保护广播。
8. **导航与量纲**：路由参数全 `LongType`；全 UI 未发现手写 `*1000`/`/1000`；
   依从率 0/0 三处均 `if (decided > 0)` 才算，无假 100%。
9. **仓库卫生**：`key.properties`/`local.properties` 未被 git 跟踪，工作区无敏感文件。

---

## 六、审查方式与局限

- **静态审查**，未运行模拟器、未跑 UI 走查（本轮未改源码，按 AGENTS 无测试可跑；
  单测基线见报告头）。
- **并发风险**：另一会话正在改工作区，行号以 11:54–12:10 快照为准；
  §二列出的工作区修复**尚未提交**，若被回滚，相应条目重新成立。
- **未复核项**：sbf P1-13 导出口径；N3/N4 两条"需实测"项；`MIGRATION_1_2` 分歧（§四）。
- 本轮只输出文档，**未修改任何源码，未 commit/push**。

---

## 七、建议并入 FIX-PLAN 的增补工单

| 增补 | 级别 | 建议批次 | 来源 |
| :-- | :--: | :--- | :--- |
| N1 详情/库存页返回不重载 | P1 | M4（或 M7 新工单） | 本轮 UI 子代理 + 本人核验 |
| N2 sbf P1-11 结束日先后（§六 移入） | P1 | M7（与 M7-1 同批） | 本轮证实 |
| N3 `goAsync` 全量对账 ANR 风险 | P2（需量化） | M1（与 M1-7 同批测量） | 闹钟子代理 |
| N4 `CarroMedApp` REPLACE 自饿死 | P2（需实测） | M1（`dumpsys jobscheduler` 验证） | 闹钟子代理 |
| N5 多步写无外层事务 | P2 | M2（与 M2-3 同批） | 本轮 UI 子代理 |
| N6 脚本 `--only` 丢导航断言 | P2 | M6 | 本人 |
| N7 先删行后快照（非恢复路径） | P3 | 并入 M5-1 修法①（快照责任上移） | 闹钟子代理 + 本人 |
| N8 `AddEditLogicTest` 恒真 | P3 | 并入 M6-2 | 本人 |
| N9 README 项数过期 | P3 | 并入 M8-4 | 本人 |
| N10 `txType` 无默认 | P3 | 并入 M5-3 | 本人 |
| N11 a–n 低危杂项 | P3 | 随 M8-1/M7-5 等同批 | 三人合并 |
