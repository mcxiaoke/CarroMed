# OPEN-ISSUES-20261001 —— 全部历史 Review 核对后的唯一开放问题清单

> **2026-10-02 更新**：批次①②③与卫生批次已实施（commit `62b2e42`、`a1d7fee`），
> 修复条目在标题行以 ✅ 标记，部分修复标 ◐，决议不修标 ⏸；未标记的条目仍然开放。
> 同日晚些的 UI 走查批次收掉了 §一-7、§二-24、§二-26 与 L-22 menuAnchor 迁移，
> 修复详情见 `CHANGES-20261002.md`。
>
> **生成时间**：2026-10-01 19:09 (GMT+8)，基于工作区 HEAD `97612a7`（含 9/30–10/1 全部修复批次）。
>
> **本文档取代此前所有 review 与问题清单，是唯一有效的开放问题清单。**
> 核对范围（旧文档删除后以本文档为准，无需再回查）：
>
> - `CODE-REVIEW-20260927-sbf.md`（73 条核对项）
> - `CODE-REVIEW-20260929-sba.md` + `CODE-REVIEW-20260929-ds.md`（58 条）
> - `CODE-REVIEW-20260929-zcg.md` + `CODE-REVIEW-DB.md` + `TEST-AUDIT-20260929-zcg.md`（81 条）
> - `CODE-REVIEW-20260929-osbf.md` + `CODE-REVIEW-20260929-round2.md` + 旧 `OPEN-ISSUES-20260930.md`（约 60 条）
> - `CODE-REVIEW-20260930-ocsbf.md` + `CODE-REVIEW-20260930-xdsf.md`（53 条）
> - `CODE-REVIEW-20261001-orsbf.md` + `CODE-REVIEW-20261001-bda.md`（76 条）
> - `REVIEW-PM-UX-20261001-wdsp.md` + `UIUX-REVIEW-20261001-dbp.md` + 三份 `ANALYSIS-*`（66 条）
>
> **核对方法**：每条问题逐条对照当前代码重新验证（按符号定位，行号为 2026-10-01 实测行号），
> 高优先级结论经第二遍人工抽查确认。判定口径：
> 已修复 / 部分修复 / 仍未修复 / 因重构失效（不再适用）/ 待产品拍板 / 经核对不成立。
> 已修复与失效项见文末附录；CHANGES-20260927~20261001 与 git log 保留修复过程记录。
>
> **重要更正（旧 review 中的误报，勿再修）**：
> - UIUX-REVIEW I-05「库存页/单药历史返回不刷新」——已修（`InventoryViewModel.kt:95` 探针
>   `drop(1)` 重载、`MedHistoryViewModel` 订阅式加载），UIUX review 成文早于该修复。
> - round2 N11-d「今日页长按推迟两套口径」——今日页长按菜单已移除，不再适用。
> - orsbf/bda「恢复重复时点丢提醒」——保护早已存在（`DataExporter.kt:164` `DUPLICATE_POLICY_TIME`）。
> - sbf L8「guessLabel 孤儿 KDoc」——有真实调用，不成立。

---

## 一、高优先级（建议近期修复，均为真实存在的代码缺陷）

### 1. 补药页「开启库存追踪失败」提示被导航丢弃 ✅ 已修复（2026-10-02，62b2e42）
- 来源：ocsbf P0-3
- 现状：`RefillViewModel.kt:144-152` 中 `setStockTracking` 失败虽然写了 `error` 并留日志，
  但随后无条件 `isSaving=false; onSuccess()`；`RefillScreen.kt:308` 的成功回调直接
  `onNavigateBack`——错误提示随页面销毁被丢弃。用户以为入库后自动扣库存已开启，实际没有。
- 修法：失败时不回调 `onSuccess()`，留在原地显示错误并允许重试（入库已成功，重试只补 `setStockTracking`）。
- 验收：注入追踪失败，断言页面不返回且错误可见。

### 2. Streak 首日「★ 0 天」徽章 + 月历零状态文案失实 ⏸ 决议不修（2026-10-02：设计如此——隐藏后用户无法查看历史状态，徽章恒显示）
- 来源：ocsbf P0-1B（bda/wdsp 修复 streak 熔断时的遗留半边）
- 现状：`StatsEngine.kt:373-375` 今天尚有未服时 streak 从 0 起算（首日用户恒为 0）；
  `TodayScreen.kt:136-139` 无条件渲染徽章，0 天也显示「★ 0 天」灰态；
  `DoseHistoryCalendarSheet.kt:327-331` 零状态文案是「今天也是新的起点，按时服药吧！」——
  当日已服 2 次时这句话是事实错误。
- 修法：徽章加「无历史」判别不渲染（或显示引导态）；日历零状态文案改为
  「完成今天的服药，点亮第一颗星」一类与状态相符的表述。

### 3. 编辑药品保存仍把进页快照写回 `min_stock_alert` ✅ 已修复（2026-10-02，62b2e42）
- 来源：ocsbf P1-2、DB C-14（预警线编辑已移到库存页，但写回路径没拆干净）
- 现状：AddEdit 页已无预警线输入框，但 `AddEditMedicationViewModel.kt:524,541` 仍把进页快照
  传进 `ProfileDraft`，`MedicationDao.kt:211` `updateProfile` 的 SET 列表含 `min_stock_alert`。
  「进编辑页 → 在库存页改预警线 → 回编辑页保存」会静默把预警线改回旧值。
- 修法：从 `updateProfile` 的 SET 列表与 `ProfileDraft` 中移除 `min_stock_alert`
  （预警线唯一写入口 = 库存页 `updateMinStockAlert`），同步更新字段保全测试。

### 4. 库存页有效期/预警线草稿会被后台刷新覆盖 ✅ 已修复（2026-10-02，62b2e42）
- 来源：ocsbf P1-1 残余
- 现状：`InventoryViewModel.kt:163,165` 在探针触发的 `load()` 中无条件用库值覆盖
  `expiryDate` 与 `minStockAlertInput` 草稿（盘点框 `calibrateInput` 已保护，这两个没保护）。
  现有 `isDirty()` + 返回键拦截只防「没保存就返回」，不防「编辑期间后台刷新吃掉输入、
  保存时把被覆盖后的旧值写回」。
- 修法：三个草稿统一 dirty 标记，`load()` 只更新非 dirty 字段。

### 5. 保存药品后闹钟重排失败静默 ✅ 已修复（2026-10-02，62b2e42）
- 来源：round2 N11-g 残留（10/1 UI 反馈批次修了「保存/删除提醒计划、备份恢复」路径，本路径漏网）
- 现状：`AddEditMedicationViewModel.kt:582` `runCatching { rescheduleAll(...) }` 无 `onFailure`——
  药品和计划已保存，但精确闹钟没排上，用户不知情，提醒可能不响。
- 修法：与 6553081 批次同口径——失败时 Toast/错误条明确告知「已保存，但提醒可能不响」，
  并落 ERROR 日志 + `ReconcileWorker.enqueueOneShot` 兜底重试。

### 6. 统计页/进展页导出 CSV 仍在主线程做文件 IO ✅ 已修复（2026-10-02，62b2e42）
- 来源：sbf P1-18 残余（设置页导出已改 `Dispatchers.IO`，这两处漏网）
- 现状：`StatsViewModel.kt:264`、`ProgressViewModel.kt`（exportReport）用裸
  `viewModelScope.launch`（Main）直接调 `DataExporter.exportDoseRecordsCsv`；
  `DataExporter` 的导出函数内部没有任何 `withContext(Dispatchers.IO)`（全文件 0 命中）。
  记录量大时导出卡顿/ANR。
- 修法：在 `DataExporter` 各导出函数内部自包 `withContext(Dispatchers.IO)`，一处覆盖所有调用方。

### 7. 详情页 INTERVAL 频次文案与排班语义相反 ✅ 已修复（2026-10-02，UI 走查批次）
- 来源：ocsbf P1-5
- 现状：`MedicationDetailScreen.kt:754-755`：`intervalDays <= 2` 显示「隔天」
  （`mdetail_sum_freq_interval_short`），否则显示「每隔 intervalDays-1 天」。
  即 `intervalDays==1`（每天）被显示成「隔天」，`intervalDays==3` 显示成「每隔 2 天」，
  与库存页/药箱页的正确口径（n≤1 每天 / n==2 隔天 / 其余每 n 天）相反。
- 修法：抽领域层单一 `intervalLabel(n)`，三页共用；详情页切换到该实现。
- **实施（2026-10-02）**：domain 新增 `IntervalCadence`（唯一语义裁决）+ `ui.component.intervalLabel`
  （唯一文案实现，共用 `freq_interval_*` 字符串），详情/库存/药箱三页统一切换，
  删除 8 个失效字符串；debug/release 编译 + 单测全绿 + 实机走查（n=3 详情页显示「每 3 天」）通过。

### 8. 进展页首屏流与触底加载写-写竞态 ✅ 已修复（2026-10-02，62b2e42）
- 来源：ocsbf P1-8 残余（防连点/失败重试已修，竞态本体未修）
- 现状：`ProgressViewModel.kt:257-262` `firstPageFlow.onEach` 无条件整表替换
  `_timelineRecords`；`loadMoreTimeline` 在 `:283` 取快照、挂起后在 `:303-307` 用旧快照覆盖写回。
  用户翻到第 3 页时若首屏流重发射（新打卡/撤销），loadMore 恢复后会丢掉新记录。
- 修法：revision 单调计数比对、过期追加直接丢弃；或合并为单一分页数据源。

### 9. 今日页/药箱页无错误态 ✅ 已修复（2026-10-02，62b2e42）
- 来源：orsbf P1-17
- 现状：`TodayViewModel` 与 `CabinetViewModel` 全文无 `error` 字段（rg 零命中）。
  数据库打不开等 Room 异常时列表表现为「空状态」，诱导用户去「添加药品」。
- 修法：主查询流补 `.catch` 降级 + error 态 UI（今日屏 streak/月历流已有同款 `.catch` 可参照）。

### 10. Streak 365 天聚合在主线程高频执行 ✅ 已修复（2026-10-02，62b2e42）
- 来源：ocsbf P1-10
- 现状：`TodayViewModel.kt:155-166` `streakDaysFlow` 对 `today.minusDays(365)` 的全量槽位
  状态做查询 + 内存聚合，无 `.flowOn(Dispatchers.Default)`（全文件无 flowOn），
  在 `viewModelScope`（Main）上收集；每次打卡/撤销/跨午夜都重算。
- 修法：整条流 `.flowOn(Dispatchers.Default)`（一行）；顺带评估把 365 天窗口收敛。

### 11. 通知身份仍用 `slot.id` + 恢复前 cancelAll 失败不阻断 ✅ 已修复/决议收口（2026-10-02，62b2e42，见文末决议）
- 来源：xdsf P1-1、ocsbf P2-9
- 现状：闹钟与通知 Action 已改内容寻址，但通知本体仍是裸 `slot.id.toInt()`
  （`Notifications.kt:191,242,248-250,334-337`）。槽位 id 在恢复/重排后指向别的药时，
  补响判据 `isDoseNotificationShown(slotId)` 与 `cancelDoseNotification` 会作用于错误对象。
  另外 `DataExporter.kt:1191-1192` 恢复前 `runCatching { cancelAll() }` 失败仅 WARN 不阻断，
  旧通知可能残留并被错误判据放行补响。
- 修法：通知 id 改为业务键派生（与闹钟 identity 同哲学）；cancelAll 失败升级为阻断恢复或至少计入恢复问题清单。
- **【2026-10-02 决议：以托盘双清收口，id 内容寻址降级为不做的防御纵深】**
  经场景核对：同进程内 notify 与 cancel/isShown 用的是同一代 slot id（一致），
  改计划删行路径有 presnap 旧身份兜底（一致），重启不重映射 id —— id 交叠的
  **唯一受害者场景是恢复备份**，而恢复路径本就有 `cancelAll()` 一步清场。
  据此实施：恢复成功后、重排闹钟前**再清一次托盘**兜底第一次失败；
  两次都失败时 `RestoreResult.Success.trayCleanupFailed = true`，
  恢复成功提示明确告知用户手动清通知。通知 id 维持 `slot.id` 派生不变。

### 12. 点通知销毁重建 Activity、无深链落点 ◐ 部分修复/决议收口（2026-10-02，62b2e42：singleTask 已加；深链经用户决策暂缓，见文末决议）
- 来源：orsbf P1-12、bda 三.4、ocsbf P1-7
- 现状：`Notifications.kt:189-192,268-271` contentIntent 用
  `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TOP` 打开裸 MainActivity；
  manifest 无 `launchMode`（standard，已有实例时销毁重建）；MainActivity 不读 intent，
  通知上没有「查看这条提醒」的落点，DoseRecordDetail 路由存在但无人从通知进入。
- 修法：Intent 带 `carromed://dose/{medId}/{date}/{time}` 深链 + `singleTask`/`onNewIntent` 处理，
- **【2026-10-02 决议：只修销毁重建，深链暂不做】**
  manifest 已加 `android:launchMode="singleTask"` —— 已有实例时点通知走
  `onNewIntent` 复用，不再销毁重建（状态不丢）。通知点击维持「打开首页今日页」
  的行为不变；深链落点（导航到记录详情）经用户决策暂缓，需要时再从本条恢复。

### 13. `getStaleOpenSlots` OR 双分支无适配索引
- 来源：ocsbf P1-9、zcg 上轮遗留 #3
- 现状：`DoseSlotDao.kt:480-488` `WHERE status='PENDING' AND scheduled_ts < ? OR status='SNOOZED' AND snooze_until_ts < ?`；
  `DoseSlotEntity.kt:42-51` 现有 `(scheduled_date,status)`、`scheduled_ts` 等索引，
  但 `snooze_until_ts` 不在任何索引中，PENDING 分支也吃不到精确复合索引。
  每轮对账、每次闹钟触发都全表扫；表量受 14 天窗口约束，随使用年限增长。
- 修法：先零风险拆成两条单分支查询；若仍需要，加 `(status, scheduled_ts)` / `(status, snooze_until_ts)`
  索引——**改 @Entity 必须升 `AppDatabase.version` + 补 `AppDatabaseRealTest` PRAGMA 断言**。

### 14. 恢复备份后设置页不重读 ✅ 已修复（2026-10-02，62b2e42）
- 来源：round2 N11-j
- 现状：`SettingsViewModel.kt:62-71` init 一次性读 `app_settings`；`confirmRestore`（:237）成功后不重读。
  恢复了一个设置项不同的备份后，推迟时长/夜间免打扰的回显停留在恢复前。
- 修法：恢复成功后重跑一次设置加载（或改 observe Flow）。

---

## 二、中优先级（一致性 / 性能 / 发布准备）

### 15. release 未开 R8，且 `proguard-rules.pro` 文件不存在
- 来源：orsbf P0-6、sbf P3-1/P3-2
- 现状：`app/build.gradle.kts:49` `isMinifyEnabled = false`；build 文件引用的
  `proguard-rules.pro` 实际不存在（Test-Path = False）。当前不报错只是因为没开混淆；
  将来开混淆之日若无 `-keepnames` 枚举规则，Room 枚举转换会把历史数据静默改写。
  另：CHANGES 中三处「通过 R8 检查」的记录与事实不符。
- 修法：创建 proguard-rules.pro（枚举 keep 规则先写好），发布前开 minify 并跑全量回归。
  **发布阻断项。**

### 16. `exportSchema = false`，schema 无落盘
- 来源：ocsbf P2-8 残余、sbf P2-6
- 现状：`AppDatabase.kt:74`。当前处于「删库重装」阶段风险被政策对冲，但发布前重建迁移
  需要历史 schema 作为真相起点。版本史注释（:30-42）已补到 v9。
- 修法：打开 exportSchema 落盘 `app/schemas/`。**发布前。**

### 17. DST 切换时刻投影无检测
- 来源：orsbf P1-10
- 现状：`SlotProjectionEngine.kt:116` 裸 `atZone(zoneId).toInstant()`，夏令时空洞/重叠时刻
  无检测，切换日可能顺延/跳过一小时。中国无 DST，短期实害为零，属正确性债。
- 修法：检测 `ZoneRules` 的 gap/overlap 并显式选边 + 注释。

### 18. 恢复路径直写 `doseAmount` 无量化校验；`Dose.of` 无上界
- 来源：orsbf P0-4 残留
- 现状：三个业务入口（savePolicy/takeDose/logManualDose）已有量化后 `milli > 0` 守卫，
  但 `DataExporter.kt:860-867` 恢复回填直写 `doseAmount`，`validateBackup` 无剂量正性/上界校验；
  `Dose.kt:52` 本体仍无上界钳制（新代码直接调 `Dose.of` 仍可能溢出）。
- 修法：恢复校验加剂量 `> 0`（blocksRestore 或告警按既有分级）；`Dose.of` 加业务上界。

### 19. `takeDose` 服务层无补记时间窗
- 来源：orsbf P1-6 残留、bda 一.4 残留
- 现状：UI 层已加 14 天历史下界并钳制，`logManualDose` 服务层有上下界，但
  `takeDose` 服务层对过去时刻一律放行（`SlotActionPolicy.kt:54`）——防御纵深缺最后一层。
- 修法：服务层加与补录同源的时间窗 require。

### 20. 语义强调色小字 4 处不达 AA 对比度 ✅ 已修复（2026-10-02，a1d7fee；可选的对比度断言测试未做）
- 来源：ocsbf P0-2/P2-3 残余
- 现状：`TodayScreen.kt:261`（「去添药」WarningAmber on WarningAmberContainer ≈2.86:1）、
  `:547`（EXPIRED 徽章）、`:741`（「已服」徽章 ≈3.3:1）、`InventoryScreen.kt:270`。
  正文色 outline→onSurfaceVariant 已修（10 处），这 4 处是小字+语义色组合残留。
- 修法：小字改 `OnWarningAmberContainer` / `OnSuccessGreenContainer`；
  可选：补 JVM 对比度断言测试。

### 21. 通知 smallIcon 仍是系统资源 ✅ 已修复（2026-10-02，62b2e42）
- 来源：xdsf P1-4 残余、UIUX V-01
- 现状：`Notifications.kt:199,279` 仍 `setSmallIcon(android.R.drawable.ic_dialog_info)`——
  部分 ROM 可能静默不显示通知图标，属功能性风险；应用启动图标已落地。
- 修法：补纯白单色 VectorDrawable 作 smallIcon。

### 22. `rescheduleAll` 每次 onResume 无节流 ✅ 已修复（2026-10-02，a1d7fee）
- 来源：xdsf P2-1
- 现状：`MainActivity.kt:40-48` 每次 RESUMED 都跑全量对账（0.4–1.5s/次，内部已切 IO），
  频繁前后台切换时线性叠加。
- 修法：AlarmReconciler 内置 lastFullReconcileAt 节流（如 5 分钟），显式路径（保存/恢复）不受节流。

### 23. 备份导出全量驻留内存
- 来源：xdsf P2-4
- 现状：`DataExporter.kt:939,956` `file.writeText(encodeBackup(buildBackup(db)))` 整对象编码；
  CSV 仍 StringBuilder 全量拼接。恢复侧已分块（chunked(50)）但导出侧未流式化；
  `writeSafetySnapshot` 在恢复链路中 OOM 会中断恢复。
- 修法：JSON 按表流式写入；CSV 分批 append。保留事务包裹。

### 24. 详情页不解析「0=跟随全局」推迟哨兵 ✅ 已修复（2026-10-02，UI 走查批次）
- 来源：ocsbf P1-6
- 现状：`MedicationDetailScreen.kt:767` `if (snoozeMinutes > 0)` 才显示推迟标志；
  药品级 0（跟随全局）时详情页不显示任何推迟信息，而通知实际按全局值生效。
- 修法：复用 `ReminderSettings.resolve` 渲染「跟随全局（N 分钟）」。
- **实施（2026-10-02）**：`MedDetailUiState` 新增 `globalSnoozeMinutes`（直读 `app_settings`
  的 `KEY_SNOOZE_MINUTES`，与 resolve 全局分支同源）；snoozeMinutes==0 时渲染
  「推迟 跟随全局（N 分）」。实机走查通过。

### 25. 详情页 4 处 46dp 固定高度按钮 ✅ 已修复（2026-10-02，a1d7fee）
- 来源：xdsf 4.4、UIUX A-01 残留
- 现状：`MedicationDetailScreen.kt:326,567,579,599`（暂停/恢复/永久删除等**写操作**按钮）
  仍 `.height(46.dp)`，低于 48dp 触摸目标；目标用户正是放大字号人群。
- 修法：改 `defaultMinSize(minHeight = 48.dp)`。

### 26. 今日页顶栏标题恒为「今日用药」、日期条随点平移 ✅ 已修复（2026-10-02，UI 走查批次）
- 来源：ocsbf P2-2、ocsbf P2-1/P2-8、wdsp U-8
- 现状：`TodayScreen.kt:134` 标题与 selectedDate 无关（选中非今日时顶栏事实错误）；
  `TodayViewModel.kt:265,299` 日期条固定 `(-3L..3L)` 以选中日为中心，点远处日期窗口跟着飘，
  无「回到今天」快捷入口、无翻周控件。
- 修法：标题三态；选中日≠今天时显示「今天」按钮。
- **实施（2026-10-02）**：标题三态（今日用药 / 「M月d日 用药计划」 / 「M月d日 用药记录」，
  新增 `today_title_future` / `today_title_past` / `today_back_to_today`）；选中日≠今天时
  顶栏显示「今天」TextButton 一键复位。日期条窗口居中逻辑维持不变（有复位入口后可接受）。
  实机走查：三态标题 + 回到今天均验证通过。

### 27. 导出失败 Toast 抛原始异常文本（5 处） ✅ 已修复（2026-10-02，62b2e42，实为 7 处）
- 来源：orsbf P1-16 残留
- 现状：`StatsViewModel.kt:280`、`SettingsViewModel.kt:109/136/226`、`ProgressViewModel.kt:410`、
  `SettingsScreen.kt:531`、`InventoryViewModel.kt:228` 均把 `e.message` 拼进用户文案
  （如「导出失败： %1$s」），底层异常原文直接暴露给用户。
- 修法：用户文案固定为「导出失败」，异常细节只进 AppLog。

### 28. 「服药建议」三名一义 + 两处文案与现行为不一致
- 来源：wdsp U-3、U-1 残留、U-4 残留
- 现状：
  - `strings_rem.xml:69`「服药建议」与 `strings_vocab.xml:25` `slot_generic`「服药时段」同框矛盾；
  - `strings_prog.xml:9` 仍引导「在今日清单点『添加』」，而 FAB 已改名「补记服药」（U-1 残留）；
  - `MedicationDetailScreen.kt:884-896`「近 30 天用药」卡在 `decided==0` 时显示「暂无计划」，
    有计划未投影时自相矛盾（U-4 残留，汇总区已改 `policy == null` 判据，此处没改）。
- 修法：统一词汇表；两处文案按现行为改写。

### 29. `core/domain` 层 android 依赖残留（铁律）
- 来源：orsbf P2-4 残留
- 现状：铁律只豁免 Room `withTransaction`，但 `DoseEntryActions.kt:3` 仍 import
  `android.content.Context`，`CurrentDateHolder.kt:3-6` 仍 import
  `android.app.Application` + `androidx.lifecycle.*`——两个类居 `core/domain` 且带 android 依赖。
- 修法：要么移出 domain（如 `core/alarm` / `ui`），要么在 AGENTS/ARCHITECTURE 里显式扩写豁免清单并说明理由。

### 30. 测试硬编码远期日期 `2026-12-31` ✅ 已修复（2026-10-02，a1d7fee）
- 来源：xdsf P3-7（OPEN-ISSUES-20260930 L3 中 `ReminderSettingsIsolationTest` 已改相对日期，这批漏网）
- 现状：`AppDatabaseRealTest.kt:359,399,466` `setPausedUntil(medId, "2026-12-31")`；
  `MedicationAdminServiceTest.kt:154,173` `endDate = "2026-12-31"`；另有两处 `"2027-12-31"`。
  当前只做往返断言不假红，但 2027 年起若加 `isPausedOn(now)` 类断言会静默假红。
- 修法：统一改 `LocalDate.now().plusMonths(3).toString()`。

### 31. 无 `values-night`，平台主题钉死浅色 ✅ 已修复（2026-10-02，a1d7fee）
- 来源：sbf P3-6、xdsf P2-6
- 现状：`res/values/themes.xml` 仍 `android:Theme.Material.Light.NoActionBar`，res 下无
  `values-night/`。Compose 内部已用 `isSystemInDarkTheme()`，但深色模式下冷启动窗口白闪。
- 修法：加 `values-night/themes.xml`（DayNight 父主题，零依赖）。

### 32. `expiryDate` 注释承诺「临期提醒」但无任何消费方
- 来源：ocsbf P1-11
- 现状：`MedicationEntity.kt:81` KDoc 写「用于库存临期提醒」，`core/alarm` 全目录
  无 expiry 消费；唯一展示是库存页 NoticeBar。低库存主动通知同样缺失（xdsf P2-2）。
- 修法：见 §五-7 产品拍板（补实现或改注释并把能力记入未实现清单）。

### 33. 诊断日志写药品名 ✅ 已修复（2026-10-02，62b2e42）
- 来源：orsbf P2-18
- 现状：`MedicationAdminService.kt:156,180` 日志含 `name=$name`，日志文件随分享功能外发时泄漏用药隐私。
- 修法：日志只记 medId。

### 34. 导出文件名秒级精度，同秒覆盖 ✅ 已修复（2026-10-02，62b2e42）
- 来源：round2 N11-n
- 现状：`DataExporter.kt:261-262` `timestamp()` 为 `yyyyMMdd_HHmmss`，同秒两次导出同名互相覆盖。
- 修法：加毫秒或序号后缀。

---

## 三、低优先级（卫生 / 清理，一个批次可收尾）

### 死代码（全部生产零调用，逐条 rg 验证过）
- **L-1**（zcg #15 / DB C-06）：`DoseSlotDao.kt:19-20` 单条 `insert(REPLACE)` 仍是公开 API，
  与 `insertAll(IGNORE)` 结论相悖（REPLACE 换 id 会致闹钟身份漂移）——建议删或收 private。
  ✅ 已按注释方式收口（2026-10-02，a1d7fee）：23 处测试调用依赖它，删除成本大于收益，
  已加「仅限测试种子数据、生产零调用」警示注释。
- **L-2**（DB C-22）：`DoseRecordDao.insert/insertAll(REPLACE)`（:29/:316）、
  ✅ 已修复（2026-10-02，a1d7fee）：四处均已补「REPLACE 有意」理由注释。
- **L-3**（L1 残余 / DB C-08）：「死但测试在用」DAO 方法待专门批次删除：
  `InventoryTransactionDao.getLatestTransaction`、`DoseSlotDao.getPendingSlotsForMedicationAfter`、
  `ReminderSettingsDao.getMedicationWithReminder`/`getActiveWithReminder`、
  `MedicationDao.observeArchivedOverviews`、`SchedulePolicyDao.getAllPoliciesForMedication`、
  `AppSettingDao.getSetting`；`StatsEngine.calculateAdherence`(:35)/`sumDoseByDate`(:211)。
- **L-4**（DB C-09）：`DoseTrackingService.kt:943-956` `refillStock` 绕过 `appendLedger` 自算
  `balanceAfter`（结果正确，纯 DRY 债）。
- **L-5**（sba 4.1 残余）：仅测试引用/不可达代码：`Dose.isNegative`/`equalsWithin`/`Doses.ONE`/`Doses.UNLIMITED`；
  `Theme.kt:75` `dynamicColor` 不可达分支；`Color.kt:76/78` `OnWarningAmber`/`WarningAmberBorder` 零引用；
  `ReminderSettingsScreen.kt:641` PreviewCard PRN 死分支；`ManualDoseViewModel.onActualDateTimeChange` 零调用；
  `InventoryScreen.kt:387` `calibrate(null)` 使 note 参数走不到；
  `getAllPoliciesForMedication`/`observeArchivedOverviews` 缺「测试专用」标注。
- **L-6**（DB C-31）：`MedicationOverview.kt:42` 走不到的 `reminderSettings` 默认参数。

### 一致性 / 注释失真
- **L-7**（L5 / DB C-20 残留）：`AppConverters.kt:113-114` `toIntList` 仍 `mapNotNull { toIntOrNull() }`
  静默丢未知 token，无日志（服务层 `require(1..7)` 已加，此为读路径残留）。
- **L-8**（ocsbf P2-4 / xdsf P3-4）：`Type.kt` 仍缺 `bodySmall`/`labelMedium` M3 槽位
  （约 60 处硬编码 sp，见 UIUX V-03）。
- **L-9**（ocsbf P1-4 / P1-3 残余）：星期标签 6 套实现并存（today/rem/cabinet/java.time SHORT/NARROW/
  日历硬编码中文），周起始不一致（今日 ±3 天按选中日 vs 月历周一起）——抽 `WeekLabels` 单一实现；
  `DoseHistoryCalendarSheet.kt:133` 硬编码 `listOf("周一",…)` 应改 `DayOfWeek.getDisplayName`。
- **L-10**（ocsbf P2-6）：`SchedulePolicyDao.kt:96` 注释称空 id 集合「必须由调用方短路」，
  实际 `IN ()` 由 Room 处理且唯一调用方未短路也正常——注释与事实相反。
  ✅ 已修复（2026-10-02，a1d7fee）：注释已更正。
- **L-11**（ocsbf P2-7）：三处缩进错乱误导事务边界：`ReminderSettingsViewModel.kt:416-457`
  （withTransaction 内两步缩进错乱）、`InventoryViewModel.kt:119-182`、
  `MedicationDetailViewModel.kt:176`（`}    /**` 挤同一行）。
- **L-12**（ocsbf P3-3）：`DoseRecordDetailViewModel.kt:50-68,139-144` `isWithinEditWindow`/
  `isSameLocalDay` 在 getter 里实时读挂钟，跨午夜 UI 不重组（同文件 :109-116 KDoc 声明的纪律相反）。
- **L-13**（xdsf 4.5）：`AlarmReceiver.kt:67` 日志中英混排（`"skip: no open slot for $key (已打卡/…)"`）。
  ✅ 已修复（2026-10-02，62b2e42）：改纯英文。
- **L-14**（orsbf P3）：文档注释失真三处：`BackupFormat.kt:31` 引用不存在的 `schemaVersion` 字段；
  `DataExporter.kt:634` 注释仍写「insertAll 的 REPLACE」；`app/build.gradle.kts` buildConfig 注释
  仍指向已删除的 SampleDataSeeder（sbf P3-9）。
  ✅ 已修复（2026-10-02，62b2e42）：三处注释均已更正。

### Schema / 索引（均需升 version + PRAGMA 断言，或明确记录不加的理由）
- **L-15**（sbf P2-7）：`DoseSlotEntity.kt:42-51` `Index(medication_id)` 与 UNIQUE 复合索引最左前缀重叠。
- **L-16**（DB C-30）：`dose_slots.policy_id` 无索引无设计说明（当前无按它过滤的查询，可记录「有意不加」）。
- **L-17**（orsbf P3）：`DoseRecordEntity` 缺 `(medication_id, actual_ts)` 复合索引
  （keyset 分页查询现在只有主键序扫描可用）。

### 测试卫生（L3 残余 + TEST-AUDIT 残余）
- **L-18**：`SlotProjectionEngineProperties.kt:83-94` I6「同参数重复投影结果相同」对纯函数是恒真性质测试。
- **L-19**：`StatsDaoAggregationTest.kt:133-151` `slotStatusCounts_flowEmitsOnChange` 是
  `.first()` 前后各查一次的假响应式断言（Room cold Flow 下恒绿）。
- **L-20**：`AlarmReconcilerIdempotencyTest.kt:319` 旧 `isAtMost(before)` 未删
  （:336-348 已有强断言并存，冗余容忍缺陷行为）。
- **L-21**（TEST-AUDIT #4）：`DoseTest.kt:71` 演示型 `isAtMost` 断言（审计自评可不改）。
- **L-22**（sbf P3-3）：弃用警告 7 处：`Icons.Default.Sort`（CabinetScreen.kt:140）+ 5 处裸 `.menuAnchor()`。
  ✅ 已修复（2026-10-02）：Sort 图标已改 AutoMirrored（a1d7fee）；menuAnchor 全部 8 处
  （SettingsScreen 4 处 + AddEdit/ManualDose/Refill/ReminderSettings 各 1 处）迁移到
  `menuAnchor(MenuAnchorType.PrimaryNotEditable)`，均为 readOnly 下拉字段、类型逐一核对，
  实机验证提醒设置/系统设置/补录页下拉展开与选择正常（UI 走查批次）。

### 杂项（P4）
- **L-23**（round2 N11-l）：SAF `OpenDocument()` 未 `takePersistableUriPermission`（一次性读取场景，影响有限）。
- **L-24**（DB C-35）：`DevDataReceiver` `clearAllTables()` 不复位 sqlite_sequence（debug-only）。
- **L-25**（round2 N9 / sbf P3-10 / P3-11 / P3-12）：文档债——README 测试数写 420（实际 526+）、
  README 文档索引未收录新文档、`FINAL-ARCHITECTURE.md` 仍写「id 作为 RequestCode」与不存在的
  `snooze_count`、README/FINAL-PRODUCT 准时率优先级矛盾。

---

## 四、测试与流程待办

- **T-1**（orsbf P1-19）：`escapeCsv`（含公式注入防护）零测试；`ReminderSettingsViewModel`/
  `ProgressViewModel`/`InventoryViewModel`/`ManualDoseViewModel`/`RefillViewModel`/
  `DoseRecordDetailViewModel` 六个核心 VM 零直接测试。
- **T-2**（sba P1-15 残留）：本机备份 File 链路（`importLocalBackup`/`listLocalBackups`/
  `inspectLocalBackup`/`writeSafetySnapshot`）零测试覆盖。
- **T-3**（osbf P0-1 验收，悬置 2 天）：补响循环修复后未做 10 分钟 `dumpsys alarm` 实测
  （观察单槽位唤醒次数 ≤1）。
- **T-4**（orsbf P0-8 残留）：Roborazzi 快照基线停在 `99254c1`（09-30），其后 10+ 个 UI 提交未重录；
  走查词表硬编码在脚本、`DoseHistoryCalendarSheet`/`TodayStreakBadge` 未登记走查 PROGRAM；
  `.maestro/smoke-seeded.yaml` 断言硬编码中文、与 strings.xml 无关联。
- **T-5**（orsbf P2-21/22/23 + P3）：无 `robolectric.properties`（34 个测试类未钉 SDK）、
  无 CI、无 version catalog、`.gitignore` 仍忽略 jqwik 反例库、签名未配 V3/V4、wrapper 无 sha256 校验。
- **T-6**（UIUX A-02/A-03）：TalkBack 全量审计未做（33 处 `contentDescription = null` 待逐项定性）；
  大字号 130–200% 缩放未验证。

---

## 五、需产品拍板（不建议直接修，先定语义）

1. **重复提醒直到确认**（xdsf P1-2 + 决策 A）：关键药无 MAIN_REPEAT 链，现有兜底是
   「超 2h 未服发一条聚合通知」（bda #6）——方向一致但不等价。需定分级方案后实施。
2. **锁屏全屏闹钟**（UIUX I-01）：无 `setFullScreenIntent`，与「到点一定响」第一承诺的行为学
   兜底有落差（`ReminderSettings.kt:19` 注释自承）。需拍板是否做 + 厂商适配范围。
3. **一把药批量确认 + 合并通知**（wdsp M-03 / UIUX F-02/F-03）：多药用户逐点 N 次、逐槽独立通知。
4. **通知隐私三档 / 应用锁**（xdsf P1-3 / wdsp D-12）：通知明文拼药名，无 `setVisibility`。
5. **铃声与震动模式 / 推迟多档**（wdsp B-5/B-4 / UIUX I-02）：通知 Action 只有一档推迟。
6. **snooze_count 推迟上限**（sbf P1-2 残留）：「推迟上限 3 次」无实现，但 `FINAL-PRODUCT.md`/
   `FINAL-ARCHITECTURE.md` 仍承诺——补实现或修订文档。
7. **低库存/临期主动通知**（xdsf P2-2 + §二-32）：补 ReconcileWorker 巡检 + 静默渠道，
   或降级注释记入未实现清单。
8. **Streak 熔断阈值**（orsbf P1-8 / bda 一.2 残留）：现固定 7 天空档熔断，
   `INTERVAL ≥ 8` 的低频方案（如隔 30 天长效针）第一个排班空档即断签、streak 恒 1。
   按排班周期推导还是维持 7 天，需定口径。
9. **准时率指标**（sbf P1-16/P3-12/S-6）：数据齐备（scheduled_ts/actual_ts），README/FINAL-PRODUCT
   三方优先级矛盾，需先写口径规范。
10. **补录 UI 意图区分**（wdsp B-2 / UIUX F-05 残留）：数据层已自动结清当日槽位（补打卡语义已定），
    但补录页无「命中槽位/计划外」提示，用户无法区分补打卡与额外服药。
11. **新建药品默认无提醒的引导**（wdsp F-01）：创建流无内联提醒，仅详情页入口高亮兜底。
12. **今日页提醒健康入口**（bda 三.5 / wdsp B-1 / UIUX U-03）：闹钟精度/通知权限/电池白名单
    降级对用户不可见，唯一入口是设置页深处的自检页。
13. **打卡 5 秒撤销 Snackbar**（UIUX F-04）：列表内撤销按钮已移除，Snackbar 无 action。
14. **Onboarding 三步引导 + 冷启动权限前置说明 + 「数据仅本机请备份」首启提示**
    （wdsp M-10 / UIUX O-01/O-02/O-03）。
15. **Android 12/12L 是否补 `SCHEDULE_EXACT_ALARM`**（orsbf P1-11 / bda 三.3）：
    现仅 `USE_EXACT_ALARM`，API 31/32 走 `setAlarmClock` 降级档（仍准点，有 48dp 去授权引导）——
    `AlarmScheduler` KDoc 记录为有意取舍，确认或推翻。
16. **扫码/OCR 录入 vs 本地词表输入加速**（xdsf P2-3）。
17. **`BackupFormat.unit` 默认「片」的 i18n 边界**（xdsf P3-2 + 决策 B）。
18. **进展页视图归属**（UIUX U-04 / wdsp M-06）：月历在今日页 Sheet、进展页两 Tab，与文档三视图
    规划不一致，二选一对齐。
19. **进程回收表单保持**（round2 N11-f）：8 屏已 `rememberSaveable`，`SavedStateHandle` 仍零使用。
20. **导出面向医生的一页式报告**（wdsp B-6，v2 排期）、**药箱卡今日服药状态**（wdsp U-6）、
    **触觉反馈**（UIUX F-06）、**完成态庆祝**（UIUX I-06）、**主题开关**（UIUX I-04）、
    **排序记忆**（wdsp B-7）——增强建议池。

---

## 六、附录：核对结果统计与已修复项一览

### 各文档处置统计

| 来源文档 | 核对项 | 已修复 | 部分 | 仍开放 | 失效/不成立/拍板 |
| :--- | ---: | ---: | ---: | ---: | :--- |
| CODE-REVIEW-20260927-sbf | 73 | 45 | 12 | 13 | 3 失效 / 4 拍板待验证 |
| CODE-REVIEW-20260929-sba + ds | 58 | 51 | 5 | 0 | 2 失效 |
| CODE-REVIEW-20260929-zcg + DB + TEST-AUDIT | 81 | 68 | 5 | 8 | 1 失效 |
| CODE-REVIEW-20260929-osbf + round2 + 旧 OPEN-ISSUES | ~60 | 41 | 7 | 13 | 2 失效 / 1 拍板落地 / 1 不成立 |
| CODE-REVIEW-20260930-ocsbf + xdsf | 53 | 6 | 10 | 32 | 1 不成立 / 4 拍板 |
| CODE-REVIEW-20261001-orsbf + bda | 76 | 41 | 13 | 19 | 2 拍板 / 1 不成立 |
| PM-UX / UIUX / ANALYSIS 五份 | 66 | 17 | 13 | 27 | 4 失效 / 4 建议 / 1 不成立 |

### 已确认修复的主线（细节见 CHANGES-20260927~20261001 与 git log）

- **闹钟链路**：requestCode 号段碰撞、内容寻址、14 天视野 + WorkManager 续期、补响循环
  （托盘判据 + 可达性前置）、USE_EXACT_ALARM、幽灵闹钟 presnap、开机对账、超 2h 聚合提醒。
- **库存账本**：current_stock 列删除（SUM 权威）、负库存合法化、量化后毫单位三道守卫、
  追踪/校准全走流水、盘点录 0。
- **编辑丢配置**：提醒四列迁出 medications 表、ProfileDraft 刻意不含提醒字段。
- **删除机制**：两阶段（在服仅归档/归档后永久粉碎）、外键 RESTRICT、permanentlyDelete 拓扑清理。
- **备份恢复**：一致性快照事务、恢复前撤闹钟+清通知、分块回填、DUPLICATE_POLICY_TIME/BOM/
  悬空 recordId 校验、恢复后重排、安全快照。
- **补录**：上下界（7 天）、自动结清当日槽位（D1 已拍板落地）、retrospective 布尔化。
- **Streak**：7 天空档熔断、跳过不断签、跨零点推迟槽位并入今日。
- **UI/UX**：标题栏统一 + 防回归断言、i18n 全面抽取、文案标准化、type-safe 导航、
  错误滚动定位、加载态、防连点、返回拦截、48dp 触摸目标、outline 前景色、FAB 文案。
- **杂项**：CurrentDateHolder 跨午夜、探针式返回重载、批量查询消 N+1、DAO 死方法两批共删 15+、
  枚举降级留痕、CSV 公式注入防护、Locale.ROOT、复合游标分页、日志 2MB 轮转、
  应用图标落地、debug CLEAR 卫生、destructive migration 仅 DEBUG。

### 已判定失效/不成立的旧条目（防止未来重复核对）

| 条目 | 结论 |
| :--- | :--- |
| sbf P3-7 / S-2 / S-3 | WorkManager 引入后 WAKE_LOCK 不再是死权限；闹钟泄漏与 BOOT 宽限期前提已消失 |
| sbf P2-9 | 通知 Action 内容寻址后 PendingIntent 泄漏量级大幅缩小（本体仍在 §一-11） |
| round2 N11-b / N11-c | requestCode 算术与提前 return 跳续期已随重构消失 |
| round2 N11-d | 今日页长按推迟菜单已移除 |
| sba P2-9 / ds P2-4 | 新建页已移除提醒计划配置段 |
| orsbf P1-13 / bda 二.3 | 恢复重复时点校验早已存在 |
| TEST-AUDIT #6 | 时区重算已实现，「已知限制」测试随新语义更新 |
| sbf L8 guessLabel | 有真实调用，不成立 |
| UIUX I-05 | 返回刷新已修（UIUX review 成文早于修复） |
| xdsf P3-8 | LogFileSink 2MB 上限与轮转健全 |
| xdsf P3-6 / sbf P3-8 / P2-19 / P2-20 等 | 对应文件已删除或已收敛 |

---

## 七、建议修复批次

| 批次 | 内容 |
| :--- | :--- |
| ① 快赢（一两个提交） | §一-1、5、7、14、27（文案/回调/一行 IO）；§三 L-13、L-14 |
| ② 竞态与覆盖主题 | §一-3、4、8、9 + §二-20 |
| ③ 通知身份与落点主题 | §一-11、12 + §二-21 |
| ④ 性能主题 | §一-6、10、13 + §二-22、23 |
| ⑤ 测试与走查补课 | §四 T-1～T-4 |
| ⑥ 卫生收尾 | §三全部 + §二-28、30、31、33、34 |
| 发布前 | §二-15、16 + §五-15 确认 |
| 拍板后排期 | §五全部 |
