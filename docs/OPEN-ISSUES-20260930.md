# OPEN-ISSUES-20260930 — 五份 Review 开放问题清单（逐条核对当前代码）

> **⚠️ 状态更新（2026-09-30 13:00）**：本文档的 H1–H6、M1–M11 与 L 系列的大部分
> 已在当天修复并分批提交（见 `CHANGES-20260930.md` 的对应段落与 commit
> 88d6a47 / a3116cf / a35af2a / bac8aa8 / ad43094）。**仍然开放**的：
> L1 中"死但测试在用"的 DAO 方法删除、L3 的 I6 恒真性质测试清理、
> L3 附带的备份 File 路径测试、`Type.kt` 缺槽位（L8）、osbf P0-1 的
> 10 分钟 `dumpsys alarm` 复测（§五）、以及 D1（待产品拍板）。
> 各条目正文保留原始核验结果，作为修复依据的存档。
>
> 2026-09-30 生成。对以下五份 review 逐条对照工作区当前代码核验后的**真实未修项**汇总：
>
> - `docs/CODE-REVIEW-20260929-osbf.md`（19 条：已修 2、部分修 1、未修 16）
> - `docs/CODE-REVIEW-DB.md`（49 条：已修 25、部分修 4、未修 20）
> - `docs/CODE-REVIEW-20260929-zcg.md`（26 条：已修 23、部分修 2、未修 1）
> - `docs/CODE-REVIEW-20260929-ds.md`（14 条：已修 11、部分修 3、未修 0）
> - `docs/CODE-REVIEW-20260929-sba.md`（约 50 条：P0 5/6 已修、P1 11/15、P2 17/18、P3 约一半）
>
> 判定标准：以**当前代码**为准（行号为核验当日行号，后续可能漂移，按内容定位），
> CHANGES / FIX-PLAN 记录仅作旁证。跨 review 重复项已合并，标注来源编号。
> 无误报：五份 review 的事实描述经逐条回代码核对全部成立
> （仅 DB C-16 的"溢出为负"后果被 FIX-PLAN 勘误 3 修正为"饱和为 Int.MAX_VALUE"，实害有限）。

---

## 一、高优先级（用户可见缺陷 / 发布阻断）

### H1. `fallbackToDestructiveMigration()` 无条件启用 — 发布阻断
- 来源：sba P0-6、DB B-09
- 现状：`core/data/AppDatabase.kt:146` 裸调用，KDoc 自记 "TODO(发布前删除)"，无任何机制保证。release 版本不匹配即静默清库。
- 修法：`if (BuildConfig.DEBUG) { fallbackToDestructiveMigration() }`。一行改动。
- 验收：release 变体反编译/单测确认无 destructive 回退路径。

### H2. 盘点校准录不进 0
- 来源：osbf P1-2
- 现状：`InventoryViewModel.kt:223` 用 `DecimalInput.parsePositive`，文案「请输入有效的实际库存数量（大于 0）」；领域层 `calibrateStock`（`DoseTrackingService.kt:733` 起 KDoc）明确允许 0（清空库存是合法盘点结果）。
- 修法：改 `DecimalInput.parseNonNegative`（`DecimalInput.kt:79`，其 KDoc 写明用于"0 有明确业务含义"的字段），文案改「0 或正数」。顺带检查 InventoryScreen 的键盘/提示是否隐含"必须大于 0"。
- 验收：盘点录 0 能保存，台账余额归 0。

### H3. 提醒设置页"清除结束日"无效
- 来源：osbf P1-3
- 现状：`ReminderSettingsViewModel.kt:224` 的 `onEndDateChange(null)` 只清 `endDate` 不动 `hasEndDate`；`:380` `clearEndDate = !s.hasEndDate` ⇒ 点清除后仍为 false，服务层沿用旧结束日。`ReminderSettingsScreen.kt:306` 副标题判据 `uiState.hasEndDate` 造成"开关开着 + 日期框空 + 副标题说到该日期停止"的矛盾三态。
- 修法：`save()` 里 `clearEndDate = !s.hasEndDate || s.endDate == null`；`:306` 副标题判据改 `hasEndDate && endDate != null`。
- 验收：服务层断言 `clearEndDate=true ⇒ end_date IS NULL`。

### H4. 补录服药时间无下界，可录 1970 年
- 来源：osbf P1-1
- 现状：`ManualDoseScreen.kt:263-267`、`:275-279` 两处 `DatePickerDialog` 无 `minDate`；`ManualDoseViewModel.kt:145` 只有 `isAfter(now+1min)` 上界；`logManualDose`（`DoseTrackingService.kt:678-725`）无时间下界。且 `DoseRecordDetailViewModel.kt:185` 对 manual 记录绑 2 天 `withinEditWindow`，老记录永久不可撤。
- 修法：
  1. 两个 DatePicker 补 `minDate = now - 5 年`；
  2. `logManualDose` 加 `require(actualTs >= now - 5 年 && actualTs <= now + 容差)`（服务层是公共下游，所有入口同守）；
  3. manual 记录的撤销窗口放开下界（`canUndo` 的 manual 分支不受 `EDITABLE_WINDOW_DAYS` 约束）。
- 验收：`logManualDose(actualTs = 0)` 抛 `IllegalArgumentException`，做变异验证。

### H5. 恢复备份不撤托盘通知 + 通知 Action 仍按 extras slotId 寻址
- 来源：osbf P1-4
- 现状：`DataExporter.kt:1052-1066` `cancelAllAlarmsBeforeRestore` 只撤闹钟，全工程无 `cancelAll()` 通知撤销；`Notifications.kt:208,213` 通知身份是裸 `slot.id.toInt()`；`DoseActionReceiver.kt:44,53` 按 extras `slotId` 反查。恢复是整库替换，托盘旧提醒必然过期，但用户点"服用"会按 slotId 反查到**别一个药的新槽位**扣库存（`SlotActionPolicy` 日期守卫挡不住 id 空间交叠、计划日 ≤ 今天的情形）。注意：`AlarmReceiver` 主路径已改内容寻址（`findOpenSlotId`），唯独通知 Action 没改。
- 修法（两条缺一不可）：
  1. 恢复事务后 `NotificationManagerCompat.from(app).cancelAll()`；
  2. `DoseActionReceiver` 三个 Action Intent 改内容寻址 `setData(carromed://action/{medId}/{date}/{time}/{kind}/{action})` + `findOpenSlotId` 反查（AGENTS §2 坑 4 "身份是内容"的另一半）；错配时 `Log.w`。
- 验收：Robolectric 断言恢复后活跃通知数为 0；错配 Action 落空并留日志。

### H6. 新建药品静默写入 `min_stock_alert = 10`（核验新发现）
- 来源：DB 核验补充发现
- 现状：`AddEditUiState.minStockAlert` 默认 `"10"`（`AddEditMedicationViewModel.kt:109`），新建页**没有**该输入框，`saveProfile`（`:438,464` → `MedicationAdminService.kt:148`）随档案写回——每个新药被悄悄设 10 片预警线，与"0=关闭告警"约定及 `:216-219` KDoc 立场相悖。
- 修法：默认改 `"0"`；或把 `minStockAlert` 从 `ProfileDraft` / 编辑快照中去掉（预警线归库存页独占，同 C-14 的所有权划分）。
- 验收：新建药品后 DB 中 `min_stock_alert = 0`。

---

## 二、中优先级（性能 / 一致性 / 数据严谨性）

### M1. `rescheduleAll` 全量对账跑主线程（6 个调用点全在 Main）
- 来源：osbf P2-1
- 现状：`AlarmReconciler.kt:145` `rescheduleAll` 无 `withContext(Dispatchers.IO)`；调用点 `DoseEntryActions.kt:149`、`InventoryViewModel.kt:205`、`ReminderSettingsViewModel.kt:414`、`AddEditMedicationViewModel.kt:502`、`MedicationDetailViewModel.kt:211`、`SettingsViewModel.kt:240` 全部在 viewModelScope（Main）。
- 修法：`rescheduleAll` 最外层自包 `withContext(Dispatchers.IO)`，KDoc 注明"IO 密集，调用方不必自行切线程"。一处改动覆盖全部现有与未来调用点。
- 验收：debug 加 `assert(!Looper.myLooper().isMainThread)` 走查。

### M2. `reconcileSchedule` 逐药全窗口拉取再内存过滤（O(药数×槽位)）
- 来源：osbf P3-7、sba P1-6
- 现状：`DoseTrackingService.kt:941-943` `getSlotsInRange(from, to).filter { it.medicationId == medicationId }`；`DoseSlotDao.kt:140-144` SQL 无 `medication_id` 条件。
- 修法：DAO 加 `WHERE medication_id = :medId` 版本（`getPendingSlotsForMedicationAfter` 已是同款形状），走现有 `Index(medication_id)`。与 M1 同文件可一起修。

### M3. 提醒设置页暂停开关：注释与代码直接矛盾
- 来源：osbf P2-2、zcg #14、sba 4.2（三份同报）
- 现状：`ReminderSettingsViewModel.kt:405` 注释写「暂停归详情页的开关所有；提醒设置页只读展示，不在这里改」，:406-411 紧接着 `if (s.isPaused != wasPaused)` 调 `setPausedUntil(medId, "")`（= **无限期**暂停，把"暂停至某日"升级成无限期）/ `resume(medId)`。`ReminderSettingsScreen.kt:172-181` 有可写开关但不显示恢复日期。zcg 核验补充：`wasPaused` 已改为保存时实时读库（`:406-407`），但 `s.isPaused` 仍是进页快照，页面停留期间别处改了暂停会被陈旧 UI 值覆盖回去。
- 修法（二选一，先消灭撒谎注释）：
  1. 按注释言明：删该开关（Screen:169-183）与 VM 的 `isPaused`/`onPausedChange`/save 内 :405-411 整段，页面只读展示"当前已暂停至 X"；
  2. 升级三态（暂停至某日/永久/不暂停），保存落库如实描述。

### M4. 备份不是一致性快照
- 来源：osbf P2-4
- 现状：`DataExporter.kt:355` `buildBackup` 顺序读 8 张表无事务（WAL 下读到的是每个查询各自的快照）；`exportFullBackupJson`（:811-813）、`writeSafetySnapshot`（:825-827）同样无包裹。全文件唯一 `withTransaction` 在恢复路径 :657。
- 修法：`buildBackup` 整体包 `db.withTransaction { }`（读事务给一致快照，约 3 行），两处调用方同样包裹。
- 验收：并发插流水，断言文件内 `SUM(change_amount)` 与 `stockMilli` 一致。

### M5. 多步保存无外层事务 + Refill 第二步彻底静默
- 来源：osbf P2-5
- 现状：`ReminderSettingsViewModel.kt:366-414` 四步保存链各自事务，:367-369 注释自认 N5 但"已写入的步骤不回滚"原样存在（表单停旧值、库里已是新计划）。`RefillViewModel.kt:127` `runCatching { setStockTracking(...) }` 无 `onFailure`，第二步失败零感知。
- 修法：编排层包 `db.withTransaction { saveReminderPolicy; saveReminderBehavior; 暂停处理 }`（`reconcileSchedule`/`rescheduleAll` 留事务外）；Refill 补 `AppLog.w` + UI 错误提示。
- 验收：注入第 2 步必失败的替身，断言第 1 步写入未发生。

### M6. 枚举转换器未知值静默降级
- 来源：DB B-08、sba P2-12
- 现状：`AppConverters.kt:22-44` 四个读路径 `runCatching { valueOf(it) }.getOrDefault(PENDING/...)`，无日志。枚举改名会让历史行静默变形。
- 修法：至少 `AppLog.w` 带原始串；更好：读路径抛错（失败得很响），降级只放导入路径。

### M7. N+1 查询残留两处
- 来源：ds P2-1、zcg #19
- 现状：
  1. `CabinetViewModel.kt:105-106,127-130` `buildItemUi` 每次 combine 对**每个**药品做 2 次 DAO 查询（`getActivePolicyForMedication` + `getTimesForPolicy`）；
  2. `TodayViewModel.kt:160` SKIPPED 分支逐槽 `getRecordBySlotId`（COMPLETED 已批量 :150-153，SKIPPED 漏了；且 id ASC LIMIT 1 会取到"跳过→撤销→再跳过"后最早的 REVERTED 记录，仅展示瑕疵）。
- 修法：① 药箱页做一条 active policy+times 的 JOIN，或 `getAllPoliciesWithTimes()` 一次取全建 map；② `getCompletedRecordsForSlots` 的 SQL（`DoseRecordDao.kt:116`）扩成 `IN ('COMPLETED','SKIPPED')`，SKIPPED 分支查 `recordsBySlot`。

### M8. 统计页对手动补录不刷新
- 来源：sba P1-1 残留
- 现状：`StatsViewModel.kt:229-233` 探针 `observeDecidedSlotCount()` 只盯 `dose_slots`；`logManualDose` 只写 `dose_records`（无槽位），补录后"累计用量/排行榜"不刷新。
- 修法：探针改成 `dose_slots` 与 `dose_records` 两个 COUNT Flow 的 combine。

### M9. 二级页面返回不重载
- 来源：osbf P2-3、DB C-27
- 现状：MedHistoryViewModel（`loadedMedId` 一次性守卫，:70,76-77）、InventoryViewModel（:79）、MedicationDetailViewModel（:71）均为进页一次性 `load()`；全 `ui/` 仅 `PermissionCheckScreen.kt:87-96` 有 ON_RESUME 重载。
- 修法：四个 VM 改 Flow 数据源（`observe*` 变体现成，参照 `CabinetViewModel.kt:99`），由 Room InvalidationTracker 自动跟随，比逐页贴 ON_RESUME 可靠。
- 验收：走查加断言"库存页入库返回后账面即时正确"。

### M10. 备份恢复链路两个洞
- 来源：osbf P3-5、P3-6
- 现状：
  1. `validateBackup`（`DataExporter.kt:494-649`）无"同一 `policyId` + 相同 `timeOfDay`"检查（:586 现存的 `DUPLICATE_POLICY_TIME_ID` 查的是主键 id 重复）——恢复后 `insertAll(IGNORE)` 静默吞槽位，用户被自己导出的备份锁死在 `require(timeKeys.distinct())` 上；
  2. JSON 读取（:875-882）不剥 BOM，:191 `BOM` 常量只用于 CSV——记事本另存的备份必挂。
- 修法：① 加 `DUPLICATE_POLICY_TIME (blocksRestore=true)`：`policyTimes.groupBy { it.policyId to it.timeOfDay }.filterValues { it.size > 1 }`；② 读取处 `text.removePrefix("\uFEFF")`，常量改 `"\uFEFF"`。

### M11. 删药/改计划先删行、后拍闹钟快照 ⇒ 幽灵闹钟
- 来源：osbf P3-9、DB C-23
- 现状：`AlarmReconciler.kt:149-150` 快照在 `rescheduleAll` 内部拍（签名 `(context, db)`）；`MedicationDetailViewModel.kt:202-203`（删药）、`AddEditMedicationViewModel.kt:499→502`、`ReminderSettingsViewModel.kt:413→414` 均先删行后重排，已删槽位的闹钟不在孤儿清理范围。`AlarmReceiver` 的 `findOpenSlotId == null` 守卫兜成空唤醒、不误发通知，危害有界。
- 修法：`rescheduleAll` 加 `presnap: Set<AlarmIdentity> = emptySet()` 参数，三个调用点在删行**之前**用 `getOpenSlots()` 取快照传入（函数内部修不了，它看不到已删的行）。
- 备注：备份恢复路径的同类问题已修（`cancelAllAlarmsBeforeRestore`），唯独删药路径没修。

---

## 三、低优先级（清理与卫生，可一个批次收尾）

### L1. 死 DAO 方法 ~18 个 + 两个整行覆盖入口
- 来源：osbf P3-1（部分修复）、DB C-03/04/06/22、ds P2-3、zcg #15
- 现状：19 个点名方法只删了 `getAllOverviews` 一个。仍在的（当前 grep 确认 main 源集零生产调用）：
  - `DoseSlotDao`：`insert`（:19，REPLACE，**debug 播种器在用**，需先给播种器换 IGNORE 或加标注）、`update`（:34）、`getSlotsForDate`（:138）、`getPendingSlotsAfter`（:147）、`getPendingSlotsForMedicationAfter`（:150）、`countCompletedSlotsForDate`/`countTotalSlotsForDate`（:299,:302）、`observeSlotsInRange`（:141）
  - `DoseRecordDao`：`observeRecordsInRange`/`getRecordsInRange`（:203,:206）、`countDoseRecordsForMedication`（:228）
  - `InventoryTransactionDao`：`getLatestTransaction`（:36）等（:30）
  - `ReminderSettingsDao`：`observeAll`/`observeByMedicationId`/`deleteForMedication`（:47,:44,:84）、`getActiveWithReminder`（:152，其 KDoc 自称"唯一判据"实际生产走 `getActiveOverviews`，文档谎言）、`getAllForReconcile`（:173，同自称"唯一判据"且零调用）
  - `MedicationDao`：`update`（:64，**整行覆盖，漏传型缺陷入口，必删**）、`getActiveMedications`（:160）、`observeArchivedOverviews`（:139）
  - `SchedulePolicyDao`：:71
  - `AppSettingDao.getSetting`（:20）
  - `StatsEngine.calculateAdherence`（:35，生产走 `adherenceOf`）、`sumDoseByDate`（:207）
- 修法：按 M8-1 清单删除；`forceStatusForTest` 按其 KDoc"测试不再需要时删"处理；随删修正上列失真 KDoc。

### L2. `record_id` / `policy_id` 无索引
- 来源：osbf P3-4、DB C-30
- 现状：`InventoryTransactionEntity` 索引仅 `medication_id`、`created_at`；`DoseSlotEntity:60-61` `policy_id` 无索引无设计说明。
- 修法：`@Entity` 加 `Index(...)`。**⚠️ 改 @Entity 必须升 `AppDatabase.version` + 补 `AppDatabaseRealTest` PRAGMA 断言**（AGENTS §2 第 1 条红线）。当前无其他 schema 需求，单独升一版即可。

### L3. 测试恒真断言 / 日期敏感断言残留
- 来源：sba P1-14
- 现状：
  - `SlotProjectionEngineProperties.kt:83-94` I6"同参数重复投影结果相同"纯函数恒真性质测试；
  - `StatsDaoAggregationTest.kt:133-151` `.first()` 前后各查一次的假响应式；
  - `AlarmReconcilerIdempotencyTest.kt:319` 旧 `isAtMost(before)` 未删（:336-348 已有强断言替代）；
  - ⚠️ **`ReminderSettingsIsolationTest.kt:83/144/242` 硬编码 `"2026-12-31"` 并断言 `isPausedOn(LocalDate.now())`（:184/:251/:260）——2027-01-01 起假红**。
- 修法：逐条删/改；硬编码日期改相对日期（`LocalDate.now().plusDays(...)`）。
- 附带：sba P1-15——`importLocalBackup`/`inspectLocalBackup`/快照覆盖等 File 路径测试 0 引用，建议随 M4/M10 一起补 Robolectric 覆盖。

### L4. 数字键盘无小数点（5 处）
- 来源：DB C-18 残留
- 现状：`AddEditMedicationScreen.kt:257`（默认剂量）、`:724`（初始库存）、`RefillScreen.kt:197`、`ManualDoseScreen.kt:359`、`InventoryScreen.kt:279,331` 仍 `KeyboardType.Number`，0.5 片打不出（值模型 `DecimalInput` 已支持小数）。
- 修法：统一换 `KeyboardType.Decimal`。顺带：AddEdit 默认剂量无字符过滤、非法保存时静默回落 1.0（`AddEditMedicationViewModel.kt:459`），且无单位后缀（sba 4.4 残留）。

### L5. `daysOfWeek` 0/8 可落库、`toIntList` 静默丢 token
- 来源：DB C-20
- 现状：`AppConverters.kt:94-96` `mapNotNull { toIntOrNull() }`；`MedicationAdminService.kt:288` 对 `daysOfWeek` 只 `distinct().sorted()`。
- 修法：领域层 `require(all { it in 1..7 })`；`toIntList` 未知 token 记 `AppLog.w`。

### L6. debug `ACTION_CLEAR` 不撤通知/闹钟
- 来源：osbf P3-8、DB C-35
- 现状：`DevDataReceiver.kt:55-58` 只 `db.clearAllTables()`（不复位 sqlite_sequence，已判定不影响功能）。托盘僵尸通知让走查截图带噪。
- 修法：清库前 `NotificationManagerCompat.cancelAll()`；清库后 `rescheduleAll()`（空库自然全撤旧闹钟）。

### L7. KDoc 失真四处
- 来源：ds P2-6 残留、sba 4.2、DB C-33 残留
- 现状：
  - `InventoryTransactionEntity.kt:15` 仍写"medications.current_stock 恒等于 SUM(change_amount)"（该列已删，应为"账面余额 := SUM(change_amount)"）；
  - `MedicationEntity.kt:66` 误称 `MedicationOverview` 为"视图"（实为 JOIN POJO）；
  - `ReminderSettingsDao:154-159` / `:176` `getAllForReconcile`/`getSchedulableOn` 自称"唯一判据"，实际零调用/生产走别的路径；
  - `DoseSlotDao.kt:43-44` `updateDerivedColumns` KDoc 里"`[update] 从不调用`"的失效结论与自身描述矛盾。
- 修法：改写或随 L1 死代码一并删除。

### L8. 杂项（无行为变化，顺手清）
- 来源：sba 4.1/4.4、DB C-08/09/16/29/31/39
- `AddEditMedicationViewModel.kt:443-447` `effectiveStartDate` + `TimeSlotDraft.isBeforeNow` 死代码（:483-485 注释自认，删除并留一句 KDoc）
- `ReminderSettingsScreen.kt:521-524` PreviewCard PRN 死分支；`ManualDoseScreen.kt:262/274` 未用的 `val c = Calendar.getInstance()`
- `SettingsScreen.kt:511` 硬编码 `"CarroMed v1.0.0"` → 读 `BuildConfig.VERSION_NAME`；`SettingsScreen.kt:232` `snoozeExpanded` 改 `rememberSaveable`
- `ProgressScreen.kt:514/525`、`ManualDoseScreen.kt:363`、`RefillScreen.kt:194` 硬编码 `"片"` 兜底
- `MedicationOverview.kt:41` 死默认参数 `reminderSettings = ReminderSettingsEntity(...)`
- `Dose.kt:52` `Dose.of` 无业务上界（如 ≤10000 片；勘误后实害有限）；`Dose.kt:38/46` `isNegative`/`equalsWithin` 仅测试引用
- `DoseTrackingService.kt:846-858` `refillStock` 绕开 `appendLedger` 自算 `balanceAfter`（结果正确，纯 DRY 债）
- `ReminderSettingsEntity.kt:96` `daysUntilResume` 过期暂停返回 0 而非 null（UI 已拦住，语义含糊）
- `MedicationAdminService.kt:287` `intervalDays.coerceIn(1, 30)` 对直传 >30 静默钳制（UI 已钳 2..30）
- `AddEditMedicationViewModel.kt:546-547` guessLabel 孤儿 KDoc；`AGENTS.md` §3 过期缺陷记录（DoseTrackingServiceTest"未建台账"一条）未更新
- `Type.kt` 缺 `bodySmall` / `labelMedium` M3 槽位（`titleSmall` 已补）
- `InventoryScreen.kt:193-200` 注释说"统一走 Quantity 格式化"但 :200 预警线仍走本文件私有 `fmt`（影子实现）

---

## 四、需要产品拍板（不建议直接修）

### D1. 手动补录是否回填当日槽位
- 来源：DB B-02（DB review B 级唯一未动项，FIX-PLAN 决策点 F 标"待拍板"）
- 现状：`logManualDose` 只写 `slotId = null` 的独立事实，不命中当日开放/过期槽位。用户"补打卡"后依从率仍算漏服；同时 M8 的探针也因同一原因不刷新。
- 两个语义都成立，先定产品行为：
  1. **补录 = 补记漏打卡**：按 `(medicationId, 当天, 时间最近的开放槽位)` 命中并 `markCompletedIfOpen` + 挂 `recordId`；
  2. **补录 = 额外服药**：维持现状，但补录页文案明确区分，避免用户以为打卡已补上。

---

## 五、验收补充（非缺陷）

- osbf P0-1（补响循环）已用"托盘判据"方案修复（`AlarmReconciler.kt:302/336` + `Notifications.isDoseNotificationShown`），但 CHANGES 未记录模拟器实测——建议补一次 `dumpsys alarm` 观察 10 分钟内单槽位唤醒次数 ≤1。
- zcg 遗留 `(status, scheduled_ts)` 复合索引（`DoseSlotEntity:42-51` 无）：结算线改造后查询已是 `OR` 连接的两个截止条件，原索引形态收益有限，建议 perf 实测后再定形态（如再加 `(status, snooze_until_ts)`）。

---

## 六、建议修复批次

| 批次 | 内容 | 说明 |
| :--- | :--- | :--- |
| ① | H1、H2、H3 | 三处一两行改动，先发 |
| ② | H4、H5、H6 | 输入下界 + 通知/Action 身份主题 |
| ③ | M1、M2、M3、M4、M5 | 对账线程 + 事务主题，多在同一批文件 |
| ④ | M6–M10 | 一致性 + 备份三项（M4/M10 连同 L3 附带的备份测试一起做） |
| ⑤ | M11 + L1–L8 | 幽灵闹钟 + 清理批次收尾 |
| 拍板后 | D1 | 产品定语义后排期 |
