# CarroMed 全面代码审查报告（2026-10-25）

> 审查时间：2026-10-25 19:39
> 审查方式：静态代码审查（逐文件通读 + 交叉验证调用契约），未运行测试与真机走查。
> 审查范围：`app/src/main` 全部 55 个主源码文件（约 1MB Kotlin）+ `AndroidManifest.xml` + 构建配置；
> 重点维度：代码质量、业务逻辑、数据逻辑、提醒功能、UI/UX 易用性。
> 问题分级：**P0**（会崩溃 / 漏提醒 / 数据损坏）→ **P1**（功能错误或明显易用性伤害）→ **P2**（边界场景）→ **P3**（低概率 / 建议）。

---

## 一、总体结论

**未发现 P0 级问题。** 三条产品承诺（提醒可靠、记录真实、数据自主）的核心链路——
幂等锚点下沉 SQL、库存事件溯源、备份先校验后清库、闹钟内容寻址、精确闹钟三档降级——
经逐条核验均成立，且大部分历史缺陷的修复都带有 KDoc 与测试锚点，防回归纪律明显高于平均水平。

共确认 **2 个 P1、12 个 P2、35 个 P3**。P1 全部集中在 UI 层交互防护，
P2 集中在竞态窗口、输入校验缺口与跨天/时区边界，
P3 多为文档漂移、死代码与低概率边界。没有任何一条会静默漏提醒或损坏台账守恒。

| 维度 | 评价 |
| :--- | :--- |
| 提醒功能 | **最扎实的一层**。requestCode 冲突、权限降级、开机对账、Doze、通知动作、推迟链、结算守卫逐项核验通过；仅存两处竞态窗口（P2），方向都是「多响」而非「漏响」 |
| 数据逻辑 | 台账守恒、事实不可变、局部 UPDATE 三条铁律在代码与 DB 约束两层落实；备份校验覆盖 19 类结构性错误。仅备份字段保真度有一处缺口（P3-1） |
| 业务逻辑 | 服务层校验三层防线（UI→VM→Service）完整；时间窗、剂量量程、未来槽位判据全链路同源 |
| UI/UX | 状态管理与刷新机制成熟；但 **2 个 P1 都在「防护做了一半」**（打卡连点、返回拦截绕过），另有面向老年用户的字号/触达问题（P2） |
| 代码质量 | 注释-代码同步度高；遗留 6 处死代码与 3 处文档-代码矛盾（P3），是下次重构的绊脚点 |

---

## 二、P1 —— 需要优先修复（2 项）

### P1-1 双击「✓ 确认服药」会吞掉撤销窗口，并弹出一条莫名其妙的技术性报错

**位置**：`ui/screen/today/TodayScreen.kt:427-454`（onTakeDose 回调）、`:133-139`（事件收集器）、`:680-687`（无防抖的 IconButton）；`ui/screen/today/TodayViewModel.kt:437-459`（takeDose）

**证据与链路**：
1. 确认按钮无 per-slot in-flight 防抖；点击后到 Room 流重新发射（槽位移入已服区）有 100–300ms 窗口，期间按钮仍可点；
2. `onTakeDose` **先播成功音、先发「已确认服药」Snackbar**，再等动作结果；
3. 二次点击走到 `DoseTrackingService.takeDose` 的幂等守卫（`markCompletedIfOpen` 返回 0，数据不坏）→ `ALREADY_HANDLED` → VM 发出 `today_err_already_handled` 事件；
4. 事件收集器（`TodayScreen.kt:133-139`）收到**任何**事件都会 `undoJob?.cancel()` 并撤掉撤销 Snackbar，换成「该服药记录已处理过，未重复扣减库存」。

**影响**：数据正确（幂等守卫防住了重复扣库存），但老年用户最高频的连点动作会导致：撤销窗口被摧毁 + 两遍成功音后紧跟一条对正常连点而言莫名其妙的「错误」——失败与成功不可区分，正是本项目文档自己批判过的反模式。

**修复建议**：VM 层为 `takeDose`/`undoDose` 增加 per-slotId in-flight 防抖；`ALREADY_HANDLED` 不走错误事件通道（或区分「幂等忽略」与「用户可见错误」）；成功音移到结果回调按 `APPLIED` 播放。

### P1-2 库存页顶栏返回箭头绕过「未保存修改」拦截，草稿静默丢失

**位置**：`ui/screen/inventory/InventoryScreen.kt:110`（BackHandler 只拦系统返回）、`:162-165`（顶栏箭头直接 `onNavigateBack`）；`ui/navigation/AppNavigation.kt:260`

**证据**：该页 M4 注释明确声明「有未保存修改时拦截返回，让用户明确选择」，但 `BackHandler` 只覆盖系统返回键/手势；左上角返回箭头（老年人最常用的退出方式）直接 `popBackStack()`，有效期/预警线草稿无提示丢弃。

**修复建议**：箭头 onClick 改为 `if (viewModel.isDirty()) showDiscardDialog = true else onNavigateBack()`。同款问题请顺带检查其余带 dirty 拦截的页面（P2-9 的「取消」按钮是同族问题）。

---

## 三、P2 —— 边界场景问题（12 项）

### 提醒链路（core/alarm）

**P2-1 补响判据只在排程时刻评估一次，闹钟触发时不复核 —— 竞态下同一条提醒响两次**
`core/alarm/AlarmReconciler.kt:457-475`（主提醒补响）、`:413-431`（推迟补响）；`core/alarm/AlarmReceiver.kt:74-83`。
对账侧的四个补响条件（`lastMainNotifiedTs == null`、托盘无同款通知等）都在**排程那一刻**判定；30 秒后闹钟触发时 `AlarmReceiver` 只查槽位状态、不查「是否已经提醒过」。当一轮对账（周期 Worker / BootReceiver / RESUMED / undo 等入口）恰好与响铃窗口重叠时，用户会收到两条间隔约 30 秒的有声通知。方向是「多响」不是「漏响」，概率低但真实。**建议**：`AlarmReceiver` 在 `showDoseNotification` 前对 MAIN/REPEAT 补一次 `lastMainNotifiedTs`/托盘复核。

**P2-2 重复提醒链可跨过当日 0 点继续响，与「当日结束」结算线不一致**
`core/alarm/AlarmReceiver.kt:125-144`。
结算规则是「跨过当地当日 0 点即结算 EXPIRED」，对账侧的重复分支有 `isToday` 门槛（`AlarmReconciler.kt:476`），但 `AlarmReceiver` 续排 REPEAT 只看 `newCount < max`。23:50 的准点提醒（间隔 30 分钟）会在次日 00:20 续排响铃；若首轮对账被 Doze 拖延，用户在凌晨收到**昨天欠账**的提醒。**建议**：续排前加 `scheduledDate == 今日` 判断。

### 今日页 / 药箱页

**P2-3 归档 Tab 完全不响应排序选择，排序控件却可见可点**
`ui/screen/cabinet/CabinetViewModel.kt:76-78`：`filteredActive` 有 `.sortedBy(sortOrder)`，`filteredArchived` 没有。归档 Tab 选「按名称排序」后毫无反应（高亮还照常变化）。以本仓库「偏离必写注释」的惯例判断是遗漏而非决策。**建议**：补排序，或在归档 Tab 隐藏排序入口。

**P2-4 「已逾期」判据在组合期读挂钟，屏幕静置时永不更新**
`ui/screen/today/TodayScreen.kt:543-547`：`isOverdue` 用 `System.currentTimeMillis()` 在组合期求值，无分钟级 ticker 驱动。用户停在一条 10:00 的待服药上等它到期：卡片不变红、徽标不出现，最长滞后 15 分钟（对账周期）——对「到点一定响」为核心承诺的 App，逾期可视性滞后是边界伤害。**建议**：引入每分钟 tick（可复用 CurrentDateHolder 的节拍）驱动该判据。

**P2-5 Streak 徽章触摸目标约 28dp，且是打开日历的唯一入口**
`ui/screen/today/TodayStreakBadge.kt:50-62`。低于 Material 48dp 最低标准（同页 FAB 64dp、确认按钮 48dp 是刻意为老年用户放大的，此处漏网）。**建议**：视觉不变，扩展点击边界至 40–48dp。

**P2-6 关键服药信息承载在 11–13sp 小字号上**
`ui/theme/Type.kt:45-51, 92-98`（bodyMedium 13sp / labelSmall 11sp）；实际承载的是**时点+剂量行、已推迟/已逾期徽标、库存提示**——错过会直接漏服药的信息。`Type.kt` 注释自认这是已知的未决项（UIUX V-03）。**建议**：至少把时点+剂量行与状态徽标提升一档。

### 表单类屏幕

**P2-7 数量类输入缺上界校验：默认剂量被静默钳制（显示与落库不一致）**
`ui/component/DecimalInput.kt:63-82`（`parsePositive`/`parseNonNegative` 无上界）；`ui/screen/edit/AddEditMedicationViewModel.kt:466-469, 521`；`core/domain/service/MedicationAdminService.kt:153, 178`（`Dose.of()` 静默钳制，无 require——与 `Dose.kt:66-73` 自己写的「钳制不是校验的替代品」相悖）。输入 `5000` 保存「成功」后表单仍显示 5000、库里是 1000。其余字段（时点剂量/补录/补药/盘点）超上界时服务层清晰报错被 VM 吞成通用「保存失败」。**建议**：`saveProfile` 对原始输入补 `require(isWithinRange)`；VM 层增加上界校验并透出服务层的具体文案。

**P2-8 新增页「初始库存」字段：无字符过滤、负数静默忽略不建档**
`ui/screen/edit/AddEditMedicationViewModel.kt:376, 473-476, 552`。输入 `-5`：校验只挡 `toFloatOrNull() == null`（-5 能解析）→ `stockFloat > 0f` 为 false → **不建档、不报错、显示保存成功**。用户以为库存已记录，实际追踪没开启。同页 `defaultDose` 有两层防线，唯独此字段没有。**建议**：走 `DecimalInput.filter` + 显式拒绝非正数。

**P2-9 提醒页时点选择器用 `Locale.getDefault()` 格式化时点（写库串）**
`ui/screen/reminder/ReminderSettingsScreen.kt:399-404`。全代码库其余 4–5 处同类代码都钉了 `Locale.ROOT` 并写明理由（ar-EG 等locale 产出阿拉伯-印度数字 → 服务层 `TIME_FORMATTER` 校验必拒 → 该 locale 下**永远无法保存任何提醒**）。此处是明确漏改。**建议**：改 `Locale.ROOT`。

**P2-10 提醒页保存失败时错误 banner 可能在视口外（M1 问题本页没修）**
`ui/screen/reminder/ReminderSettingsScreen.kt:179-200`。编辑页与补录页都已修（错误出现时 `animateScrollToItem(0)`），提醒页是三个表单页里**最长**的、保存按钮却在顶栏——滚到底部点保存遇校验失败，表现为「点了没反应」。

**P2-11 推迟时长步进器：「跟随全局」档位下减号被禁用；全局=120 时加减全禁死路**
`ui/screen/reminder/ReminderSettingsScreen.kt:504-519`。`canDec` 用内部哨兵值 0 判断（恒 false），而 `canInc` 用显示值——注释声称的「从跟随全局第一次减落到专属 5」是死代码。全局=120 时两键同时禁用，用户无法给该药设任何专属值。**建议**：`canDec` 改用与 `canInc` 同款的显示值判断。

**P2-12 新增药品/补录页「取消」无未保存确认，误触即全丢**
`ui/screen/edit/AddEditMedicationScreen.kt:127-130`、`ui/screen/manual/ManualDoseScreen.kt:94-97`。新建药品是全 App 最长表单，「取消」与「保存」同在顶栏，误触后几分钟输入全部蒸发，而字段少两个数量级的库存页反而有 dirty 拦截（M4）——防护标准倒挂。

---

## 四、P3 —— 低概率缺陷与建议（35 项）

### 提醒链路（9 项）

| # | 问题 | 位置 |
| :-- | :--- | :--- |
| P3-1 | 槽位被并发结算后仍会续排 REPEAT 闹钟：`updateReminderCountAndLastNotified` 的受影响行数被忽略（守卫兜底，只多一次空唤醒） | `AlarmReceiver.kt:126-137` |
| P3-2 | `ReminderSettings` KDoc 声称 full_screen_alert「字段已删除」，实际仍在读取与消费——按 KDoc 清理会静默关掉全屏提醒 | `ReminderSettings.kt:14-23` vs `:36,:58,:71,:84`、`Notifications.kt:285` |
| P3-3 | Android 14+ 全屏意图权限被拒后无任何引导恢复路径（降级为普通横幅，用户无从得知） | `Notifications.kt:279-285`、`MainActivity.kt:127-144` |
| P3-4 | `notificationIdOf` 的 `% 89000` 映射理论上可碰撞（两开放槽位 id 恰差 89000 整数倍时通知互相顶掉） | `Notifications.kt:384-387` |
| P3-5 | 未监听 `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`（仅影响 Android 12/12L 手动授权场景，重建闹钟要等 ≤15 分钟） | `AndroidManifest.xml` |
| P3-6 | `BootReceiver` 对 TIME_SET/TIMEZONE_CHANGED 也用 REPLACE 重排周期任务（打断正在运行的对账 + 重置 15 分钟周期） | `BootReceiver.kt:60` |
| P3-7 | `AlarmAlertActivity` 乐观反馈不等待真实结果：全屏页谎报「已服用」而 Toast 说「已处理过」 | `AlarmAlertActivity.kt:251-294` |
| P3-8 | 对账孤儿清理按快照旧 slotId 撤通知，备份恢复换 id 后落空（恢复路径已有两次 cancelAll 兜底） | `AlarmReconciler.kt:368` |
| P3-9 | `AlarmReconciler` 类 KDoc 承诺「划掉通知会再补响」，实际补响要求 `lastMainNotifiedTs == null`——文档与行为相反，排查时误导 | `AlarmReconciler.kt:47-51` vs `:457-460` |

### 今日页 / 药箱页（6 项）

| # | 问题 | 位置 |
| :-- | :--- | :--- |
| P3-10 | 打卡成功音与「已确认」提示在动作结果未知前发出（乐观反馈无回滚；失败场景用户先被误导一次） | `TodayScreen.kt:427-436` |
| P3-11 | 撤销窗口仅 5 秒，对老年用户偏短；跨午夜的推迟只显示 `HH:mm`，无法区分当天/次日 | `TodayScreen.kt:441, 1054-1058` |
| P3-12 | 月历查询失败后强制回跳当月（用户翻看历史时一次瞬时异常丢失选择；降级应保留当前月） | `TodayViewModel.kt:212-216` |
| P3-13 | 全部药品归档后，今日页显示「药箱还是空的」首启引导（数据源不含归档药，「从未有药」与「无在服药」未区分） | `TodayViewModel.kt:336`、`TodayScreen.kt:392-393` |
| P3-14 | 「点按可推迟或跳过」提示与实际交互不符（点按实际进详情页，推迟/跳过在详情页里） | `strings_today.xml:18` |
| P3-15 | Streak 正向状态使用 error 红色系，违背项目自定语义色映射（正向应 primary 系），可能被误读为警告 | `TodayStreakBadge.kt:44-46`、`DoseHistoryCalendarSheet.kt:334-348` |

### 表单类屏幕（7 项）

| # | 问题 | 位置 |
| :-- | :--- | :--- |
| P3-16 | `ManualDoseViewModel.quickFill` KDoc 双重漂移（注释写「30 分钟前」实现是 1 小时；YESTERDAY 清零分钟） | `ManualDoseViewModel.kt:130-139` |
| P3-17 | 补录页药品下拉用 `remember` 而非 `rememberSaveable`（本页其余 4 处都按 M7-6 纪律，此处漏网） | `ManualDoseScreen.kt:88` |
| P3-18 | 补录页日期选择器只设 `minDate` 不设 `maxDate`（未来日期可选，保存才报错） | `ManualDoseScreen.kt:305-309` |
| P3-19 | `RefillViewModel` init 无 loading 态，药品名异步加载期间头部卡闪烁占位名 | `RefillViewModel.kt:58-68` |
| P3-20 | AddEdit/Reminder 的 `load()` 数据库读取无 runCatching（保存链有 M2-3 防护，加载链裸奔；触发条件苛刻） | `AddEditMedicationViewModel.kt:222-234`、`ReminderSettingsViewModel.kt:147-157` |
| P3-21 | `RefillViewModel.setQuickAmount` 极大值输入产生科学计数法串回显（校验能拦住，仅显示怪异） | `RefillViewModel.kt:77-83, 187` |
| P3-22 | AddEditViewModel 残留整套已无 UI 调用方的提醒计划草稿状态与校验（死代码负载，注释自认） | `AddEditMedicationViewModel.kt:106-114, 297-372` |

### 领域 / 数据层（7 项，本轮主审发现）

| # | 问题 | 位置 |
| :-- | :--- | :--- |
| P3-23 | **备份字段保真度缺口**：`DoseSlotBackup` 缺 `reminderCount` / `lastMainNotifiedTs` / `lastSnoozeNotifiedTs` 三列，`buildBackup` 也不导出。恢复后提醒计数清零——已提醒过 N 次的待服槽位可能再补响一次（多响方向，不漏响） | `core/data/BackupFormat.kt:141-154`、`DataExporter.kt:571-585` |
| P3-24 | 备份解码 `coerceInputValues = true` 会把未知枚举值静默降级（`SlotStatus`→PENDING、`RecordStatus`→COMPLETED、`PolicyType`→DAILY），与「失败要响」的工程哲学相悖；`validateBackup` 不校验枚举名。手工编辑/损坏的备份可能把 COMPLETED 槽位静默变成 PENDING（恢复后重响） | `DataExporter.kt:298-305`、`BackupFormat.kt:150,164,193` |
| P3-25 | 备份「混淆」是 GZIP+固定 XOR key+Base64（key 硬编码于源码），不是加密；无完整性校验（损坏文件会在 GZIP/JSON 层失败，可接受）。建议在文档与设置页文案中明示「非加密」，避免用户误以为有保密性 | `DataExporter.kt:621-663` |
| P3-26 | 恢复失败的具体原因被丢弃：`SettingsViewModel.showRestoreError` 收到带完整校验明细的异常（如「药品 id 重复 ×2」）却只弹通用「恢复失败」——明细文案已备好但不可达（`confirmRestore` 的 Invalid 路径反而显示 reason，两条路径不一致） | `SettingsViewModel.kt:405-412` vs `DataExporter.kt:1359-1361` |
| P3-27 | 本机备份选择对话框内容不可滚动，且导出目录无清理上限（手动导出 + 恢复前快照同目录累积），备份多了会被裁切 | `SettingsScreen.kt:170-201`、`DataExporter.kt:1308-1315` |
| P3-28 | `MedicationDao.countHistoricalRecords` 零调用方（死代码）；「永久删除」对话框只有药名没有具体数量（对比恢复对话框「含 4 种药品、2 条记录」的标准） | `MedicationDao.kt:64-73`、`MedicationDetailScreen.kt:704-721` |
| P3-29 | 进展页 `exportReport` 无 `isExporting` 闸门（统计页/设置页都有），连点导出两份文件两个分享面板 | `ProgressViewModel.kt:417-435` |

### 工程与文档（6 项杂项，并入上表之外单列）

| # | 问题 | 位置 |
| :-- | :--- | :--- |
| P3-30 | README 技术栈表漂移：写 Kotlin 2.0.21 / Room 2.6.1 / Navigation 2.8.3，实际 2.2.10 / 2.7.0 / 2.8.5；`build.gradle.kts` 里 Roborazzi 注释仍提「Kotlin 2.0.21」 | `README.md`、`app/build.gradle.kts:224` |
| P3-31 | `DoseTrackingService.reconcileSchedule` 默认参数用 `LocalDate.now()` 而非注入的 `todayProvider`（生产行为一致，时钟来源分叉） | `DoseTrackingService.kt:1044-1045` |
| P3-32 | `editDose` 的未来时刻校验无 60 秒容差（`takeDose`/`logManualDose` 都有 +60s），口径不一致 | `DoseTrackingService.kt:664` vs `:200, 813` |
| P3-33 | `saveReminderPolicy` 对 `intervalDays`（1..30）/`cycleOnDays` 静默钳制而非拒绝（与「非法输入必须报错」的第三层防线哲学不一致，已有注释但只防了时点/剂量/日期） | `MedicationAdminService.kt:355-358` |
| P3-34 | `Dose.of(Int)` 重载语义是「单位×1000」却与 `Dose(milli)` 直构只差一个括号，零生产调用方——语义陷阱 API，建议删除 | `Dose.kt:76-80` |
| P3-35 | release 构建未开 minify（`isMinifyEnabled = false`），未在文档中说明取舍；另全 App 仅中文资源（856 条，无 values-en），i18n 架构已就绪但未落地——个人应用可接受，列为知悉项 | `app/build.gradle.kts:63`、`res/values/` |

---

## 五、已核实无问题的要点（按维度）

### 提醒功能（重点核验）
1. **requestCode 无冲突**：闹钟身份 `carromed://alarm/{medId}/{date}/{time}/{kind}` 内容寻址、requestCode 恒 0，`filterEquals` 含 data，构造上不可能碰撞；同槽位重排即同 PendingIntent 替换；取消用 `FLAG_NO_CREATE`。
2. **精确闹钟降级链**（`setExactAndAllowWhileIdle → setAlarmClock → setAndAllowWhileIdle`）三档全部捕获 SecurityException；`USE_EXACT_ALARM`(33+) + `SCHEDULE_EXACT_ALARM maxSdk=32` 的 Manifest 组合正确。
3. **Doze**：精确 API 选型正确；`AlarmReceiver` 内 10s 超时 PARTIAL_WAKE_LOCK + goAsync + try/finally 双层释放。
4. **开机/改时/改时区对账**四分支齐全且幂等（`markExpired` 行数锚点、Uri 重排天然替换、reconcileMutex 互斥）。
5. **通知渠道**：夜间静音独立 LOW 渠道、重要提醒穿透；`areNotificationsReachable` 口径正确。通知动作 PendingIntent 全部 `FLAG_IMMUTABLE`，接收端业务键反查 + SQL 状态守卫双保险，连击不重复扣库存。
6. **推迟链**：连续推迟同 Uri 替换不堆积；跨天推迟的结算线与补响判定自洽。
7. **暂停语义**：全部走 `isPausedOn(槽位日期)`，不踩 `pausedUntil != null` 的「到期静默消失」坑。
8. **DST/时区**：投影层显式选边（重叠取早、空洞顺延）；时区变更经 `updateDerivedColumns` 重算 scheduled_ts。

### 数据逻辑
9. **台账守恒**：余额唯一权威 = `SUM(change_amount)`；`medications` 无余额列；写入口径全部经 `appendLedger` 单条 INSERT；`balanceAfter` 快照有 I2 不变量校验。撤销/改判/改剂量按**净额**冲正，天然幂等（`getSumOfChangeByRecordId` 口径）。
10. **事实不可变**：`DoseRecordDao` 无任何按行删除方法（仅恢复路径整表清空）；REVERTED 只改状态。
11. **局部 UPDATE 铁律**：`@Update` 整行覆盖命令全部删除，替代者都是带状态守卫的窄命令且校验受影响行数。
12. **幂等锚点下沉 SQL**：`markCompletedIfOpen` / `markSkippedIfOpen` / `snoozeSlot` / `markExpired` 全部带 `status IN (...) + scheduled_date <= :today` 守卫；「撤销不受未来守卫限制」是刻意的修复通道。
13. **备份校验**：19 类结构性错误（悬空外键、五类主键重复、唯一键重复、多条 active 计划、非法剂量、零时点计划、坏日期/时点/星期），判据按后果分类而非文案关键词；恢复前先校验→先快照→先撤闹钟→再清库，失败路径也重建闹钟。
14. **CSV 导出**：BOM、公式注入防护（`=`/`+`/`-`/`@`/Tab/CR 前缀）、`\r` 处理、Locale.ROOT。
15. **恢复分批**（50/批）规避 SQLite 999 变量上限；`insertAll(IGNORE)` 与 UNIQUE 索引配合幂等。
16. **AutoBackupManager**：SAF 权限校验、按日去重、滚动保留 10 份、失败不写 last-date（次日重试）。
17. **日志管线**：AppLog 环形缓冲 + 锁外分发 + 崩溃钩子；LogFileSink 单写者、按事件时间滚动；Downloads 外投双路径 + 崩溃有界等待 + 垃圾清理判据收窄（不误删用户文件）；隐私边界（运行日志不带药名外投）明确。

### 业务逻辑
18. **未来槽位不可表态**判据一处定义（SlotActionPolicy），SQL 与 Kotlin 两侧同源；字符串字典序比较与零填充日期等价。
19. **剂量量程**：`Dose.isWithinRange` 一份判据七个写入口共用；量化归零/溢出/负数三类路径全堵。
20. **7 天补录窗**在 Screen（选择器 minDate）、VM（自然日校验）、Service（require）三层同源；「撤销仅当天 + 坏数据逃生口」设计自洽。
21. **统计口径**全链路一致：依从率基于 dose_slots 按计划日归属、分母=已服+跳过+漏服、归档不进分母（INNER JOIN 一处定义）；消耗基于 dose_records 按 actual_ts；分母 0 显示「—」而非 100%（StatsScreen:163-176 已核）；单位分组防跨单位求和、整数毫单位防浮点漂移。
22. **streak 算法**：单日达成标准、跳过豁免、空档熔断、今日三态分支均正确；「今天」跨午夜刷新走 CurrentDateHolder。

### UI/UX
23. LazyColumn key 全部带类型前缀防撞号；探针 Flow 按药品过滤避免跨页冗余重载；跨天处理单一时间源；日界计算 DST 安全。
24. 服务层异常在 5 个表单 VM 全部被 runCatching 捕获转用户可见文案，isSaving 不会卡死；保存双击保护（Main.immediate 同步闸门）齐全。
25. ViewModel 工厂全部显式 `viewModelFactory` 构造，无默认参数反射陷阱（StatsViewModel 的教训已有注释与测试）。
26. 覆盖式恢复二次确认带真实数字与警告；药品删除强制先归档；暂停时长三选一无隐式档位；「暂停到明天」语义修复（含当天）三处同源。

---

## 六、覆盖范围与局限

- **逐文件通读**：`core/domain`（服务/引擎/模型）、`core/data`（DAO/实体/转换器/导出备份/日志）、`core/time`、`core/alarm`（含 Manifest、App 入口、全屏提醒页）、`ui/screen/today|cabinet|edit|reminder|inventory|refill|manual` 的 Screen+VM、`ui/navigation`、`ui/component`、`ui/theme`。
- **VM 层通读 + Screen 层定向抽查**：`detail`、`record`、`stats`、`progress|medhistory`、`settings|permission` 五组（Screen 组合函数层按对话框/列表 key/哨兵值/挂钟读取等高风险点抽查，未逐行通读）。
- **未覆盖**：`test/` 与 `androidTest/` 源码本身（仅核对了若干测试锚点的存在性）、`tools/` 走查脚本、Maestro 流程。
- 本报告为静态审查结论；竞态类问题（P2-1/P2-2）的可达性未经真机复现，建议修复前先写针对性测试钉住。

## 七、修复优先级建议

1. **立即**：P1-1、P1-2（均为小改动：防抖 + 返回箭头走同一拦截）。
2. **随后一批**：P2-7/P2-8（输入校验）、P2-9（一处 Locale.ROOT）、P2-10/P2-11（表单页已修问题的补齐）、P2-3（一行排序）。
3. **计划一批**：P2-1/P2-2（提醒链竞态，先补测试再修）、P2-4/P2-5/P2-6（逾期可视化与老年可读性，可并一次走查）、P2-12。
4. **随手清理**：P3 中所有文档漂移（P3-2/P3-9/P3-16/P3-30）与死代码（P3-22/P3-28/P3-34），防止按过时注释「清理」出真问题。
