# UI 操作无反馈审查与修复计划（2026-10-01）

> 审查范围：全部 14 个屏幕 + 对应 ViewModel 的每条 onClick 链路（含长按、开关、菜单项、Dialog 按钮）。
> 审查方法：逐控件追踪 `onClick → ViewModel → 服务/DAO` 链路，核对错误返回值、异常分支、
> Snackbar/Toast/Dialog/导航/状态变化等可见反馈，以及 loading / 防重复点击闸门。
> 行号以当日 master（49049c6）为准，修复时以符号定位为准。

## 一、总体结论

「点击后什么都不发生」的完全死控件已基本清干净（仅 1 处展示性 Chip 误用）。
剩余缺陷集中在四类模式：

| 模式 | 典型表现 |
| :--- | :--- |
| A. 报成功但数据层后半段失败 | 闹钟重排失败被 `runCatching` 吞掉，Toast 报"已保存/恢复成功"，但闹钟一个没排 |
| B. 错误提示存在但渲染在视口外 | 表单错误 banner 固定在 LazyColumn 顶部，用户在底部点保存，表现为"点了没反应" |
| C. 静默回落 / 静默丢弃 | 非法输入被静默改成合法值保存；Boolean false 未区分语义被当成功 |
| D. 慢操作缺 loading / 防重复 | 导出、备份解析期间界面静止，可连点重复触发 |

## 二、问题清单（按严重程度）

### 高（直接威胁"到点一定响"承诺）

| # | 位置 | 问题 |
| :--- | :--- | :--- |
| H1 | ReminderSettingsViewModel.kt:461（save） | 保存计划后 `rescheduleAll` 失败被 runCatching 吞掉，Toast 报成功 |
| H2 | ReminderSettingsViewModel.kt:496（deletePolicy） | 删除计划后重排失败同样静默 |
| H3 | SettingsViewModel.kt:241（confirmRestore，配合 DataExporter.kt:1201） | 恢复备份后重排失败静默，报"恢复成功"但提醒全失 |
| H4 | ReminderSettingsViewModel.kt:400 | `snapshotOpenAlarms` 在 runCatching 之外，抛异常未捕获崩溃 |

### 中

| # | 位置 | 问题 |
| :--- | :--- | :--- |
| M1 | AddEditMedicationScreen.kt:175-191 | 保存校验错误只在表单顶部 banner，无滚动定位/无 Snackbar 兜底 |
| M2 | ManualDoseScreen.kt:137-153 + :449 | 同 M1，保存按钮在页面最底部，错误区域正好在底部 |
| M3 | InventoryScreen.kt:139-144 | 保存/盘点提示条固定在顶部，滚动到下方操作时不可见 |
| M4 | InventoryViewModel.kt:192-194 | 库存页改有效期只写草稿，不点"保存"直接返回则静默丢失，无 dirty 提示/返回拦截 |
| M5 | SettingsViewModel.kt:233 | 恢复备份在主线程做全量 JSON 解析 + 快照文件写，冻结/ANR 风险，无进度 UI |
| M6 | SettingsViewModel.kt:150/196 + SettingsScreen | 备份解析（SAF/本机）无 loading：`isInspectingBackup` 在 Screen 零引用 |
| M7 | StatsScreen.kt:280-291 + StatsViewModel.kt:255-273 | 统计导出无 isExporting 闸门，可连点重复导出 |
| M8 | AddEditMedicationViewModel.kt:518 | 默认剂量填 "0"/清空被静默回落 1.0f 保存，与表单显示不符（假成功） |
| M9 | MedicationDetailViewModel.kt:215-259 | pause/resume/toggleArchive/delete 四个写操作无 runCatching（服务层 check() 抛异常→崩溃）、无 loading 防连点 |
| M10 | ProgressViewModel.kt:299-304 + ProgressScreen.kt:657-661 | 分页加载失败被置 `_hasMoreTimeline=false`，伪装成"到底了"，无重试 |

### 低

| # | 位置 | 问题 |
| :--- | :--- | :--- |
| L1 | MedicationDetailScreen.kt:407 | 注意事项标签用 `AssistChip(onClick = {})`，有 ripple 但无效果 |
| L2 | InventoryViewModel.kt:236-239 | `setStockTracking` 返回 false（药品已删）被当成功，显示"已开启追踪" |
| L3 | InventoryViewModel.kt:276-292 | `calibrateStock` 返回 false 混淆"差额为 0"与"药品不存在"，后者谎报"账面与实物一致" |
| L4 | TodayViewModel.kt:283-293 | 日期 clamp 到下界时左端日期格点击无反馈（高亮不动、列表不刷新） |
| L5 | TodayViewModel.kt:144-177 | 两个 flatMapLatest 无 catch，DB 查询异常导致列表静默冻结 |
| L6 | TodayViewModel.kt:295-307 | takeDose 无 runCatching，异常沿协程崩溃而非降级提示 |
| L7 | InventoryViewModel.kt:197-211 + InventoryScreen.kt:367-378 | 导出流水 CSV 无 loading/防重复 |
| L8 | SettingsScreen.kt:487-507 | 导出诊断日志无防重复 |
| L9 | AddEditMedicationViewModel.kt:505, 550-552 | 初始库存无效输入静默按 0 处理、不建库存档，无提示 |
| L10 | InventoryViewModel.kt:55 等 | 成功 message 永不清除（滞留）；RefillViewModel.kt:64-69 error 只在改数量时清除 |
| L11 | DataExporter.kt:1288 | shareFile 的 startActivity 失败被吞，而成功 Toast 已先行弹出 |
| L12 | PermissionCheckScreen.kt:237-245 | openAppDetails 最终兜底失败完全静默 |

### 做得好（作为对齐样板，不动）

今日屏打卡三态反馈、记录详情页 Snackbar 全覆盖、补货页对 `refillStock` false 的独立分支
（RefillViewModel.kt:132-135）、设置页导出 isExporting 闸门、权限页逐级降级跳转。

## 三、修复计划（按改动小→收益大→风险低排序，分批实施，每批独立提交）

每批完成后：`gradlew assembleDebug` + `testDebugUnitTest` 通过 → git commit（英文 message）。

### Batch 1 — 闹钟重排失败降级提示（H1-H4）｜收益最高，改动最小
- ReminderSettingsViewModel save/deletePolicy：捕获 `rescheduleAll` 结果，失败时以
  "已保存，但提醒调度失败，请检查闹钟权限" 类文案经现有 error/Toast 通道提示；
  顺带把 `snapshotOpenAlarms` 纳入 runCatching。
- SettingsViewModel confirmRestore：恢复结果与重排结果分开报告，重排失败给降级 Toast。
- 不改 AlarmReconciler 本身，只改上层对结果的消费。

### Batch 2 — MedicationDetail 屏（M9 + L1）
- VM 四个写操作补 runCatching + error Toast 通道（复用现有 Toast 接线）+ isSaving 防连点。
- 注意事项 AssistChip 改为非可点击 Surface/Text（对齐同屏 TagChip 写法）。

### Batch 3 — 表单错误可见性（M1 + M2 + M3）
- AddEdit / ManualDose：错误出现时 `LazyListState.animateScrollToItem(0)` 定位到 banner。
- Inventory：同样滚动定位（成功/失败提示共用）。
- 不引入新反馈通道，只让已有提示可见，风险最低。

### Batch 4 — 库存屏语义与防丢失（M4 + L2 + L3 + L7 + L10 库存部分）
- setStockTracking false → 报错文案；calibrateStock false 需先在服务层或调用处区分语义
  （改动最小方案：调用处先查药品存在性，或服务层改为抛异常/返回 sealed 结果，取改动小者）。
- 有效期草稿：改值即显示"未保存"提示 + BackHandler 拦截确认（不做自动保存，避免改变语义）。
- exportLedger 补 isSaving 闸门；message 在新操作前清除。

### Batch 5 — 设置屏备份链路（M5 + M6 + L8 + L11 设置侧）
- 备份解析期间消费 isInspectingBackup：按钮转圈/禁用。
- confirmRestore 切 Dispatchers.IO + 恢复期间禁用确认按钮（对齐 inspectBackup 的写法）。
- 诊断日志导出防重复；shareFile 启动失败时给"分享失败"反馈（改 DataExporter 返回值，调用方提示）。

### Batch 6 — 统计与进度（M7 + M10）
- StatsViewModel 补 isExporting 闸门（照抄 SettingsViewModel）。
- Progress 分页失败：独立 error footer + 重试按钮，不再伪装"到底了"。

### Batch 7 — Today 屏健壮性（L4 + L5 + L6）
- 日期 clamp 到当前值时给 Snackbar 提示（或把窗口外日期格禁用置灰，取实现简单者）。
- flatMapLatest 补 `.catch { }` 降级（保持列表 + 错误事件）。
- takeDose 包 runCatching → 失败 Snackbar。

### Batch 8 — 低危杂项（M8 + L9 + L10 其余 + L12）
- defaultDose "0"/空 → 校验错误文案（不再静默回落 1.0）。
- 初始库存非法输入 → 校验错误文案。
- Refill error 在改任意字段时清除；Inventory 成功提示在离开页面/新操作时清除。
- PermissionCheck openAppDetails 失败补 Toast。

## 四、验收标准

- [ ] 全部批次 `assembleDebug` + `testDebugUnitTest` 通过（release 编译在最后一批后统一验证）
- [ ] 界面相关改动跑 tools/app_screenshots.py 走查并看图
- [ ] 每批一条独立 commit（英文 message），本文档与 CHANGES-20261001.md 同步更新
