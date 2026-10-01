# CODE-REVIEW-20260930-ocsbf —— 数据 / 业务逻辑 / UI-UX 全面审阅

> 审阅人：ocsbf　生成时间：2026-09-30 22:53 (GMT+8)
> 审阅对象：工作区当前代码（`ed1d380`），**不是**任何历史 review 的复述。
> 审阅范围：`app/src/main` 全量（85 个 Kotlin 文件，约 8.7k 行主源码）+ 23 个
> `res/values/strings_*.xml` + `core/{data,domain,alarm}` 全部契约 + 现有单测。
>
> **基线核对（本次实际执行）**
> - `./gradlew compileDebugKotlin` —— BUILD SUCCESSFUL，工作区干净（`git status` 空）
> - `./gradlew testDebugUnitTest --rerun-tasks` —— **518 项全绿**
> - 走查产物 `temp/appscreenshots/`（2026-09-30 21:10，39 张，导航断言全通过）
>
> 本轮**没有发现"数据被静默抹除"级别的 P0**（前几轮把这类问题基本清干净了：
> 整行覆盖、幂等锚点、闹钟身份、台账净额、字段所有权都已有结构性防线，且多数有
> 变异验证过的测试在守）。下面列出的是**当前仍然真实存在**的问题，按"用户能否
> 感知 + 是否违背产品承诺"排序。

---

## 目录

- [零、先说结论](#零先说结论)
- [一、P0：直接违背产品承诺 / 用户可感知的错误](#一p0直接违背产品承诺--用户可感知的错误)
- [二、P1：真实缺陷（数据、逻辑、可用性）](#二p1真实缺陷数据逻辑可用性)
- [三、P2：体验债与一致性债](#三p2体验债与一致性债)
- [四、P3：清理与卫生](#四p3清理与卫生)
- [五、值得保持的既有设计（勿在后续"优化"中破坏）](#五值得保持的既有设计勿在后续优化中破坏)
- [六、建议修复批次](#六建议修复批次)
- [附录 A：对比度实测数据](#附录-a对比度实测数据)
- [附录 B：本次核验方法与不确定项](#附录-b本次核验方法与不确定项)

---

## 零、先说结论

| 级别 | 数量 | 领域分布 | 一句话 |
| :--- | :---: | :--- | :--- |
| **P0** | 3 | 业务逻辑 2 / UI 1 | 连续天数会说谎；"已跳过"的原因行几乎不可见；补药后追踪开启失败**永远看不到提示** |
| **P1** | 13 | 数据 4 / 业务 4 / UI 5 | 库存页草稿被后台刷新吃掉；表单写了它没有的字段；i18n 已回退 3 处；星期标签 4 套实现；频次文案两套口径；通知点击不进详情 |
| **P2** | 9 | UI 5 / 性能 3 / 文档 1 | 今日页没有"回到今天"；顶栏标题恒为"今日用药"；两次全表扫描在主线程 |
| **P3** | 3 | 卫生 | 通知 id 仍是算术身份；注释与事实矛盾；缩进错乱 |

**最值得注意的一条元观察**：这个项目的 KDoc 里写着大量"旧实现错在哪 / 为什么这么改 /
刻意排除了什么"，写得非常好，**但其中已经出现了若干条与当前代码不符的陈述**（见
P3-1、P3-2）。本项目的历史教训恰恰是"注释比代码活得更久会误导后来的读者"
（`MedicationAdminService.kt:364-365` 自己就记着这一条）。建议把"注释与代码一致性"
纳入收口检查。

---

## 一、P0：直接违背产品承诺 / 用户可感知的错误

### P0-1　连续服药天数（Streak）会说谎：停药数月后仍显示历史连续天数；今天做对了也显示 0 天

**位置**：`core/domain/engine/StatsEngine.kt:347-398`（`calculateStreak`）
**影响页面**：今日页顶栏徽章（`TodayStreakBadge`）、月度打卡日历（`DoseHistoryCalendarSheet`）

#### 缺陷 A：把"这天没有任何槽位数据"当成"这天没有排班"，于是跨越任意长的空档不断签

```kotlin
// StatsEngine.kt:380-395
while (daysChecked < maxLookbackDays && !currentDate.isBefore(earliestDate)) {
    val b = dailyBreakdowns[currentDate.toString()]
    if (b == null || b.isEmpty()) {          // ← 缺陷在这
        currentDate = currentDate.minusDays(1); daysChecked++; continue
    }
    if (b.completed == b.total && b.missed == 0 && b.skipped == 0) { streak++; ... }
    else break
}
```

`b == null` 这一支的 KDoc 意图是"隔日服药 / 周末无排班的间隙日，跳过不中断"，
**但它无法区分下面三种完全不同的情况**：

| 现实原因 | 该不该断签 | 当前行为 |
| :--- | :---: | :---: |
| 隔日用药，今天本就无排班 | 不该断 | 不断 ✅ |
| **疗程已结束 / 药品已归档 / 已删除**（不再投影槽位） | **该断** | **不断** ❌ |
| **长时间暂停提醒**（暂停参与投影 ⇒ 该段没有槽位） | 该断 | 不断 ❌ |
| **用户很久没打开 App / 数据缺失** | 该断（或不显示） | 不断 ❌ |

**可复现的失败场景（真实、非构造）**：用户按医嘱吃 30 天抗生素，疗程结束后停药，
三个月后（`dose_slots` 里 6-01 之后不再有任何行）打开 App。
- `todayBreakdown` 为空 ⇒ `startLookbackDate = 昨天`，`streak = 0`
- 回溯到 6-01 那天有数据且全服 ⇒ `streak++`，再往前 5-31、5-30……一路加到 30
- 循环在 `currentDate.isBefore(earliestDate)` 处终止

**结果：顶栏显示「★ 30 连续天数」，而用户已经三个月没吃过药。**
这是一个**会误导健康决策的假指标**，而且它常驻在首页顶栏、是激励性文案
（`today_streak_praise_good` = "你做得很好！保持良好节奏"）。

> 这不是"实现与 KDoc 不符"，KDoc 明确写了"跳过不中断也不增加"——问题在于
> **这条规则本身的判据太宽**。真正能表达"隔日用药"的信息不在 `dose_slots` 里，
> 而在 `schedule_policies.policy_type / interval_days`（领域层完全拿得到）。

**建议修法**（按代价从低到高）：

1. **最小可行**：空档连续超过 `policyType` 能解释的最大间隔就断签。领域层已有
   `StatsEngine.scheduledDaysPerWeek`，用它算出一个"理论最长排班间隔"，
   超过即断。
2. **更正确**：在回溯时按当天的排班类型判断"这天本该有槽位吗"——
   用 `SlotProjectionEngine.isScheduledOnDate(policy, date, policyStart)` 逐日判定。
   这是唯一能把"隔日用药的合法空档"与"疗程结束的空档"区分开的办法。
3. **兜底护栏**（无论选 1 还是 2 都该做）：`earliestDate` 之前 7 天没有任何槽位，
   就不要显示徽章（见 P0-1 的缺陷 B）。

#### 缺陷 B：今天部分完成时恒显示「0 天」，而月度日历会说"今天也是新的起点"

`calculateStreak` 的第二分支（`StatsEngine.kt:366-375`）：今天 `pending > 0` 且无
missed/skipped 时，`streak` 从 0 起算、只继承"截止昨天"的回溯结果。
若用户是**第一天使用**（或历史无数据），结果恒为 0。

**实测证据**：`temp/appscreenshots/01_today.png` —— 顶栏显示「★ 0 天」，
而同一屏"今日已服 (2)"已经完成 2 次服药。这是**截图里可见的用户第一印象**。

连带的问题在月度日历浮层里：`DoseHistoryCalendarSheet` 用 `streakDays == 0` 选文案
`today_streak_praise_zero` = **"今天也是新的起点，按时服药吧！"** —— 而用户此刻
已经按时服了 2 次药。**文案在事实上是错的**。

**建议修法**：
- `streakDays` 增加"是否已有历史"的判别（`earliestDate` 距今 ≤ 7 天且有数据）。
  首次使用时**不渲染徽章**（或渲染为不带数字的引导态），而不是常驻一个「★ 0 天」。
- 日历浮层的零状态文案改为不预设用户"还没开始"：例如"完成今天的服药，点亮第一颗星"。

**变异验证建议**：给 `calculateStreak` 加一条"疗程结束后 3 个月"的 case，
当前实现会返回 30 —— 这条测试能立刻抓到。

---

### P0-2　`colorScheme.outline` 被当作正文色使用，对比度 1.18–1.23:1（深色 1.93:1）

**位置**（全部是**正文/标签文本色**，不是描边）：

| 文件:行 | 内容 | 背景 | 实测对比度 |
| :--- | :--- | :--- | :---: |
| `ui/screen/today/TodayScreen.kt:806` | `SkippedDoseCard` 的 `today_skipped_detail`（「已跳过 · 未扣减余量」） | `surfaceVariant.copy(alpha=0.4f)` ≈ `#F8FAFC` | **1.18 : 1** |
| `ui/screen/progress/ProgressScreen.kt:607-608` | 流水状态芯片 `SKIPPED` / `REVERTED` 的文字 | `outline.copy(alpha=0.14f)` 底 | **≈1.2 : 1** |
| `ui/screen/progress/MedHistoryScreen.kt:221,223` | 历史页状态芯片 `SKIPPED` / `REVERTED` | 同上 | **≈1.2 : 1** |
| `ui/screen/detail/MedicationDetailScreen.kt:176` | 「已归档」状态文字 | `surface` `#FFFFFF` | **1.23 : 1** |
| `ui/screen/detail/MedicationDetailScreen.kt:901-902` | 记录状态芯片 `REVERTED` / `SKIPPED` | `surface` | **1.23 : 1** |

**根因**：`Color.kt:56` 的 `OutlineLight = #E2E8F0` 是 M3 里给**边框**用的槽位
（M3 规范：`outline` 用于 divider / 描边，**不是**文字色）。
但这 5 处把它当成了低强调文字色。深色主题同理：`OutlineDark = #475569`
在 `SurfaceDark = #1E293B` 上只有 **1.93 : 1**。

**为什么这条是 P0 而不是 P2**：被隐藏的不是装饰性文字，而是
**"这次服药被跳过了、库存没有扣减"**——一条与用药安全直接相关的解释。
用户看不到它，就不会知道"跳过 = 不扣库存"，下一次会以为漏扣了药而去手动补。

**建议修法**：
1. `Color.kt` 新增两个语义色槽（与 `SuccessGreen` / `WarningAmber` 同级）：
   `SubduedTextLight = #64748B`（`onSurfaceVariant` 的低强调版）、
   `SubduedTextDark = #94A3B8`。实测 `#64748B` on `#FFFFFF` = **4.76:1（过 AA）**，
   `#94A3B8` on `#1E293B` = **6.13:1（过 AA）**。
2. 5 处调用点改用 `MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = …)`
   或新槽位；`outline` 归还给描边专用。
3. **加防回归门禁**：在 `ComponentSnapshotTest` 之外加一条纯 JVM 的
   **对比度断言**（对 `Color.kt` 的语义色两两算 WCAG 比值，< 4.5 直接红）。
   这类"颜色选错"缺陷快照测试看不见（Robolectric 无真实渲染），
   也不该靠人眼每次复核。

> 顺带修（同一批）：`WarningAmber`（`#D97706`）与 `SuccessGreen`（`#16A34A`）
> 在白底上分别是 **3.19:1 / 3.30:1**，作为 11–13sp 小字**不达 AA**。
> 受影响：`TodayScreen.kt:252`（"去添药 >" 按钮文字，`bodyMedium` 13sp Bold，
> 底色 `WarningAmberContainer` ⇒ 实测 **2.86 : 1**）、
> `InventoryScreen.kt:171,221`、`MedicationDetailScreen.kt:178,853,905`。
> 修法：文字用 `OnWarningAmberContainer`（实测 6.37:1），
> 把 `WarningAmber` 留给图标/描边/进度条底（3:1 门槛即可）。

---

### P0-3　补药入库后"开启库存追踪失败"的提示被立即返回的导航丢弃 —— 静默降级

**位置**：`ui/screen/refill/RefillViewModel.kt:140-148` + `RefillScreen.kt:301`

```kotlin
// RefillViewModel.kt:140-148
runCatching { trackingService.setStockTracking(medId, true, initialStock = null) }
    .onFailure { t ->
        AppLog.w(TAG, "enable stock tracking after refill failed med=$medId", t)
        _uiState.value = _uiState.value.copy(
            error = app.getString(R.string.refill_error_tracking_failed)   // ← 写进状态
        )
    }
_uiState.value = _uiState.value.copy(isSaving = false)
onSuccess()          // ← RefillScreen.kt:301: onSuccess = onNavigateBack
```

`RefillScreen.kt:301` 把 `onSuccess` 直接接到 `onNavigateBack`。
于是 `error` 刚写进 `_uiState`，页面**立刻 pop**，用户**永远看不到**这条提示。

**为什么是 P0**：`AppLog.w` 只进私有日志文件（用户要主动去设置页导出才能看到）。
用户的实际体验是——补了 30 片药，看到"已入库"的成功返回，此后每次打卡
**库存都不再自动扣减**，而他从未被告知。这与项目反复强调的
「静默降级 = 给用户虚假的保证」（`AGENTS.md` §2 坑 6）是同一类缺陷：
**代码里"修好了"，UI 上"等于没修"**。

值得注意的是 `docs/OPEN-ISSUES-20260930.md` M5 把这条记为
"Refill 第二步彻底静默 → 补 `AppLog.w` + UI 错误提示"并标记为已修。
按当前代码，**只有 `AppLog.w` 真正生效，UI 错误提示这一半没生效**。

**建议修法**（二选一，推荐前者）：
1. **入库成功但追踪开启失败 ⇒ 不返回**，页面留在原地，`error` 正常渲染
   （`RefillScreen.kt:147-155` 已有 `errorContainer` 的 NoticeBar 消费点），
   并在按钮上给出"重试开启追踪"的次要操作。
2. 若坚持返回，则把提示改成**离开前**可见的形式（`Toast` / `onSuccess` 携带文案
   交给上一级页面），不能只写进被丢弃的 `UiState`。

**变异验证**：把 `onSuccess = onNavigateBack` 改回 `onSuccess = {}`，
断言 `uiState.error == refill_error_tracking_failed` —— 当前实现下这条断言会失败，
正好证明提示确实不可达。

---

## 二、P1：真实缺陷（数据、逻辑、可用性）

### P1-1　库存页的两个草稿字段会被后台刷新静默吃掉（M7-3 只保护了 1/3）

**位置**：`ui/screen/inventory/InventoryViewModel.kt:163,165`（`loadOnce`）

```kotlin
// loadOnce() —— 三个草稿字段，两个被无条件覆盖
calibrateInput = if (_uiState.value.calibrateInput.isBlank()) {      // ← 有 M7-3 保护 ✅
    if (stock > 0f) fmt(stock) else ""
} else { _uiState.value.calibrateInput },
minStockAlertInput = fmt(Dose(med.minStockAlert).asFloat),           // ← 无保护 ❌
expiryDate = med.expiryDate,                                          // ← 无保护 ❌
```

而 `init`（`InventoryViewModel.kt:89-98`）的探针在
`medDao.observeMedicationById` / `inventoryDao.observeTransactionsForMedication` /
`observePolicyCountForMedication` / `doseSlotDao.observeDecidedSlotCount`
任一变化时都会 `load()`。两个草稿在 `InventoryScreen.kt:277-278`（预警线）与
`:247-249`（有效期）都是**可编辑输入框**。

**可复现路径**：用户在库存页把预警线从 `10` 改成 `30`（还没点保存）→
此时通知栏那条 22:00 的提醒被点「确认已吃」→ 写入 `dose_records` +
`inventory_transactions` → 探针发射 → `load()` → **输入框里的 "30" 静默变回 "10"**。
用户点保存，写进去的是旧值，且界面全程无任何提示。

**这正是 M7-3 记录在 `calibrateInput` 上那条 KDoc（`InventoryViewModel.kt:167-180`）
所描述的缺陷本身**——同一个函数里，一条被修好了，另两条没修。

**建议修法**：把"用户还没动过的才回填"的判据**收敛成一处**，
对 `calibrateInput` / `minStockAlertInput` / `expiryDate` 三个草稿统一适用；
更彻底的做法是给草稿字段加一个 `dirty: Boolean` 标记（`onXxxChange` 置位，
`save` 成功或显式重置时清零），避免"用内容判是否被用户改过"这种启发式。

---

### P1-2　"编辑药品信息"表单写入了一个它根本没有的字段（`min_stock_alert`）

**位置**：`core/data/dao/MedicationDao.kt:198`（`updateProfile` 的 SET 列表）
+ `ui/screen/edit/AddEditMedicationViewModel.kt:126,276,505,522`
+ `ui/screen/inventory/InventoryViewModel.kt`（库存页才是真正的所有者）

`AddEditUiState.minStockAlert`（`AddEditMedicationViewModel.kt:126`）的 KDoc
自己写着"**本表单没有预警线输入框**（预警线归库存页独占），这里只是随档案写回的快照"。
实测 `AddEditMedicationScreen.kt` 全文 grep `minStockAlert` = **0 命中**，确认无输入框。

于是形成**同一列的两个写入方**：

| 页面 | 写入口 | 字段所有权 |
| :--- | :--- | :---: |
| 库存管理 | `MedicationDao.updateMinStockAlert` | ✅ 独占，有输入框 |
| 编辑药品信息 | `MedicationDao.updateProfile`（顺带 SET） | ❌ 无输入框，持进页快照 |

`InventoryViewModel.saveSettings` 的 KDoc（`:297-307`）写得很清楚：
"复用宽命令会迫使调用方手工重传全部档案字段 —— 那正是 P0-5 的第二种症状"。
`MedicationDao.updateExpiryDate` 的 KDoc（`:228-234`）也是同一个理由才单独拆出一条命令。
**`min_stock_alert` 是这条纪律下最后一个漏网的字段。**

除了字段所有权不一致，还有 `AddEditMedicationViewModel.kt:505` 的
`s.minStockAlert.toFloatOrNull() ?: 0f` —— 用**默认值兜底**而不是
`DecimalInput.parseNonNegative` 报错，与同文件 `:517` 的 `defaultDose`、
`:459` 的剂量校验口径不一致。

**建议修法**：
1. 从 `updateProfile` 的 SET 列表删除 `min_stock_alert`；
2. `ProfileDraft` 去掉 `minStockAlert` 字段（`MedicationAdminService.kt:65`），
   与它 2026-09-30 刚被修好的默认值（`"10"` → `"0"`）一起收口；
3. `FieldPreservationInvariantTest` 的穷举式字段清单同步更新（该测试会直接抓到）。

---

### P1-3　i18n 在抽取完成之后被后续提交回退了 3 处

`docs/CHANGES-20260930.md` 记录 i18n 抽取在 **16:30** 完成
（"755 条 → 25 条，`scan_hardcoded_strings.ps1` 校验无残留"），
并明确决定"**星期标签不进资源**，改用 `java.time` 的 `getDisplayName`"。
但 **20:29** 的 streak 徽章与月度日历提交（`cacfd00`）重新引入了硬编码：

| 位置 | 硬编码内容 | 判定 |
| :--- | :--- | :---: |
| `ui/screen/today/DoseHistoryCalendarSheet.kt:133` | `listOf("周一","周二",…,"周日")` —— **7 个中文串进 UI 列表** | 直接违反上条决策 |
| `ui/screen/today/TodayStreakBadge.kt:77` | `if (streakDays > 0) "$streakDays 天" else "0 天"` —— **量词"天"是用户可见文案** | 未走 `stringResource` |
| `AlarmReceiver.kt:66` | 日志文案 | 可接受（非 UI） |

**为什么这条不是"吹毛求疵"**：项目现在只有一个 `res/values/`（无 `values-en/`）。
一旦加英文翻译，`DoseHistoryCalendarSheet` 与 `TodayStreakBadge` 会**留在中文**，
而 `ProgressViewModel`（`java.time`）会自动变英文 —— 同一个 App 里
月历表头是"周一"、进展矩阵是"Mon"。这正是 `DoseHistoryCalendarSheet.kt:132`
注释自己写的"（周一至周日）"所对应的那个决定被绕过后的结果。

**建议修法**：
1. `DoseHistoryCalendarSheet` 改用
   `DayOfWeek.of(i).getDisplayName(TextStyle.SHORT, locale)`，
   与 `ProgressViewModel.dayLabelOf` 同一实现（**再抽一次就更好了**，
   见 P1-4）；
2. `TodayStreakBadge` 的 `"$streakDays 天"` 走资源 + 位置占位符
   （`today_streak_days_fmt = "%1$d 天"`，零状态单独一条）；
3. 把 `scan_hardcoded_strings.ps1` 纳入**提交前**门禁（目前它只在
   `CHANGES` 里被"校验确认"过一次，不是常设门禁）。

---

### P1-4　同一个"星期几"在项目里有 4 套实现、3 种写法、2 种周起始日

| # | 位置 | 来源 | 中文输出 | 周起始 |
| :--- | :--- | :--- | :--- | :---: |
| 1 | `TodayScreen.kt:827-835` | `today_weekday_1..7` 资源 | `一 二 三…` | **周日**（`dayOfWeek.value` 1→周一，但数组顺序是 1..7=周一..周日；截图显示首格是"日"） |
| 2 | `ReminderSettingsScreen.kt:255-261` / `ReminderSettingsViewModel.kt:561-569` | `rem_weekday_1..7` 资源 | `周一…周日` | 周一 |
| 3 | `CabinetViewModel.kt:181-191` | `cabinet_freq_dow_1..7` 资源 | `一…日` | —（仅列举） |
| 4 | `ProgressViewModel.kt:363-364` | `java.time` `TextStyle.SHORT` | `周一…周日` | 周一 |
| 5 | `InventoryViewModel.kt:400-406` | `java.time` `TextStyle.NARROW` | `一…日` | —（仅列举） |
| 6 | `DoseHistoryCalendarSheet.kt:133` | 硬编码 | `周一…周日` | 周一 |

**同一个"周三"，今日页日期条显示"三"、进展矩阵显示"周三"** —— 截图
`01_today.png` 顶部条是「日 一 二 **三** 四 五 六」，而 `17_progress.png` 矩阵列头
是「周一…周日」。这是**同屏内跨页面的术语不一致**，
且和 P1-3 一起构成"i18n 做了一半"的证据。

另外，`DoseHistoryCalendarSheet.kt:154`（`firstDayOfWeek - 1` 推 leading empty days，
周一起）与 `TodayScreen.kt:238`（`(-3..3).map { selectedDate.plusDays(it) }`，
今天在中间）**周起始日不一致**：月历从周一起始，今日条把周日放在最左。
用户从今日条看到"日 一 二 三"，切到月历看到"一 二 三 四…"，需要重新定位。

**建议修法**：
1. 抽 `ui/component/WeekLabels.kt` 单一实现，暴露
   `short(dayOfWeek)`（`java.time`，随 locale）与 `narrow(dayOfWeek)`；
   删除 `today_weekday_*` / `rem_weekday_*` / `cabinet_freq_dow_*` 共 21 条资源；
2. 统一周起始日为**周一**（与 `java.time` 默认、`isScheduledOnDate` 的
   `dayOfWeek.value` 1..7 语义一致），今日条改成"以选中日所在周为中心"。

---

### P1-5　详情页的 `INTERVAL` 频次文案仍是旧口径（药箱页/库存页已修，详情页漏改）

项目已两次修过这个 bug，两次都只改了一半：

- `CabinetViewModel.kt:161-179` 的 KDoc 明确记录了旧 bug：
  `if (n <= 2) "隔天" else "每隔 ${n-1} 天"` 有两个问题 ——
  ① `n == 1`（**每天**）被标成「隔天」，与引擎语义**相反**；
  ② 与库存页措辞不统一。已改为 `n<=1 每天 / n==2 隔天 / 其余 每 n 天`。
- `InventoryViewModel.kt:366-397` 同样口径（`inv_freq_day_every_n(n)`）。

但**第三处**仍然用旧公式：

```kotlin
// MedicationDetailScreen.kt:721-723
PolicyType.INTERVAL ->
    if (policy.intervalDays <= 2) stringResource(R.string.mdetail_sum_freq_interval_short)
    else stringResource(R.string.mdetail_sum_freq_interval, policy.intervalDays - 1)
```

后果：
- `intervalDays == 1`（引擎语义 = **每天**）在详情页被显示成「隔天」
  —— 与实际排班**相反**；
- `intervalDays == 3` 显示 `3-1 = 2`，靠"每隔 2 天 ≈ 每 3 天"的中文歧义蒙对，
  一旦改成英文翻译（"every 2 days"）就**变成事实错误**。

**同一屏上"频次文案"与"预计可用天数"互相打架**——而
`StatsEngine.scheduledDaysPerWeek`（两页共用的正确实现）就在同一次调用里被用到。

**建议修法**：把三处的 `when (policyType)` 抽成领域/组件层单一实现
（与 `StatsEngine.scheduledDaysPerWeek` 并列，同样理由：重复的公式就是下一次漂移的起点）。
**验收**：三个页面用同一个 `intervalDays` 渲染出来的文字必须逐字一致。

---

### P1-6　详情页摘要不解析"0 = 跟随全局"的推迟时长，文案与通知行为不一致

**位置**：`ui/screen/detail/MedicationDetailScreen.kt:735`

```kotlin
if (s.reminderSettings.snoozeMinutes > 0)
    add(stringResource(R.string.mdetail_sum_flag_snooze, s.reminderSettings.snoozeMinutes))
```

`snoozeMinutes == 0` 的既定含义是**跟随全局**（`ReminderSettings.resolve:48`：
`snoozeMinutes = if (medSnooze > 0) medSnooze else globalSnooze`）。
于是"跟随全局"的药在详情页**不显示任何推迟标志**，
而它在通知栏的推迟按钮上**确实生效**（用的是全局值）。

`ReminderSettingsViewModel` 那边刚刚把这个哨兵修对过
（`ui/screen/reminder/ReminderSettingsViewModel.kt:87-94` 的 KDoc：
"旧实现 `if (it == 0) 30 else it` 把哨兵翻译成了 30，于是「本药固定 30 分钟」
不可表达，且全局改 20 后这一行仍显示 30"）——**详情页还没跟上**。

用户读到的详情页摘要说"没有推迟配置"，通知栏却按 30 分钟（全局值）工作。

**建议修法**：详情页复用 `ReminderSettings.resolve` 的解析结果，
`snoozeMinutes == 0` 时渲染为「跟随全局（N 分钟）」。

---

### P1-7　点通知只打开 App 到今日页，不落到那条服药记录

**位置**：`core/alarm/Notifications.kt:178-183`

```kotlin
val contentIntent = PendingIntent.getActivity(
    context,
    slot.id.toInt(),
    Intent(context, MainActivity::class.java)
        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
    ...
)
```

Intent **没有 extra、没有 data**，而 `MainActivity`（`:32-59`）也完全不读 `intent`。
所以点"环孢素 10:30 服用 1 片"的通知横幅 → 打开 App → 停在今日页，
用户要在清单里自己找那一条。

项目在别处对"身份是内容"这件事极其严格（闹钟 Uri、通知 Action 都已内容寻址），
**唯独通知点击落点这条最影响体验的链路是空的**。

**建议修法**：
1. `Intent` 带 `setData(carromed://dose/{medId}/{date}/{time})`；
2. `MainActivity` 读取后 `navController.navigate(ScreenRoute.DoseDetail(slotId))`，
   导航就绪后消费（用一个 pending intent 暂存，避免 `setContent` 前导航）；
3. 顺带修 `DoseRecordDetailScreen` 已有的 `ScreenRoute.DoseDetail` 复用路径
   （`AppNavigation.kt:313-320` 已经在用它，只是没人从通知跳过来）。

**验收**：`tools/app_screenshots.py` 加一步"发通知 → 点 → 断言落到记录详情页标题"。

---

### P1-8　进展页分页与首屏响应式流互相覆盖：丢记录 + 静默回退到第一页

**位置**：`ui/screen/progress/ProgressViewModel.kt:248-299`

```kotlin
private val firstPageFlow = recordDao.observeLatestRecords(FIRST_PAGE_SIZE)
    .onEach { items ->
        _timelineRecords.value = items                                   // ← 无条件"替换"
        _hasMoreTimeline.value = items.size >= FIRST_PAGE_SIZE
    }
init { viewModelScope.launch { firstPageFlow.collect { } } }            // ← 常驻订阅

fun loadMoreTimeline() {
    val current = _timelineRecords.value                                  // ← 快照
    val cursor = current.minOf { it.record.actualTs }
    viewModelScope.launch {
        val older = recordDao.getRecordsBefore(cursor, FIRST_PAGE_SIZE)   // ← 挂起点
        ...
        _timelineRecords.value = (current + fresh).sortedByDescending {   // ← 用挂起前的旧快照覆盖
            it.record.actualTs
        }
        _hasMoreTimeline.value = older.size >= FIRST_PAGE_SIZE             // ← 同样可能覆盖新值
    }
}
```

**两个缺陷**：

1. **丢更新（lost update）**：用户在第 3 页时，通知栏来一条「确认已吃」→
   `observeLatestRecords` 重发射 → `firstPageFlow.onEach` 把列表换回第一页 →
   此时 `loadMoreTimeline` 从挂起点恢复，用**挂起前的旧 `current`**（第 3 页快照）
   覆盖回去 ⇒ **刚刚新增的那条记录从列表里消失**，且用户毫不知情。
2. **分页状态回退**：`_hasMoreTimeline` 被两个写入方无序覆盖，
   可能在还有更多记录时被置 false（"没有更早的记录了"），也可能反过来反复加载。

KDoc（`:238-246`）承认了"首屏重新发射会回到列表顶部"这个取舍，
但**没有意识到它会与 `loadMoreTimeline` 产生写-写竞态**。

**建议修法**（二选一）：
- **A（推荐，最小）**：给 `_timelineRecords` 加一个单调递增的 `revision`，
  `loadMoreTimeline` 恢复后先比对 `revision`，不一致就丢弃本次追加并重置游标；
- **B（正确）**：把"首屏 + 分页"合并成**单一数据源**（如 `PagingSource` 或
  一个显式的 `TimelineRepo` 持有 `List + cursor`），`observeLatestRecords`
  只作为"有新事实"的探针（像 `StatsStateBuilder` 那样），不直接写列表。

---

### P1-9　`getStaleOpenSlots` 全表扫描；`snooze_until_ts` 无任何索引

**位置**：`core/data/dao/DoseSlotDao.kt:472-480`，调用点 `AlarmReconciler.kt:197-198`

```sql
SELECT * FROM dose_slots
WHERE (status = 'PENDING' AND scheduled_ts < :pendingCutoffTs)
   OR (status = 'SNOOZED' AND snooze_until_ts IS NOT NULL
       AND snooze_until_ts < :snoozeCutoffTs)
```

`DoseSlotEntity.kt:42-51` 的索引：`medication_id` / `(scheduled_date, status)` /
`scheduled_ts` / UNIQUE `(medication_id, scheduled_date, scheduled_time)`。
**`snooze_until_ts` 不在任何索引里。**

- 查询无 `scheduled_date` 谓词 ⇒ `(scheduled_date, status)` 的前导列无法 seek；
- SQLite 的 OR 优化要求**每个分支各自可索引**，第二分支无可用索引
  ⇒ 整条查询退化为**全表扫描**；
- 这条查询每轮对账都跑（`ReconcileWorker` 15 分钟一次 + **每次闹钟触发**
  经 `enqueueOneShot` 续期 + 每次 `MainActivity` RESUMED）。

**量级估算**：`dose_slots` 只增不删（除对账删投机区），按 4 药 × 2 次/天
≈ 2920 行/年，5 年 ≈ 1.5 万行。每次闹钟唤醒全表扫一遍，
在低端机上是一次可测量的 IO 与 CPU 开销，且**随使用时间单调变差**。

**建议修法**：
1. 拆成两条单分支查询（`getStalePendingSlots` / `getStaleSnoozedSlots`），
   Kotlin 侧合并 —— OR 结构是全表扫的直接成因；
2. 给 `snooze_until_ts` 加索引（`@Entity` 加 `Index` ⇒ **必须升
   `AppDatabase.version` + 补 `AppDatabaseRealTest` 的 `PRAGMA index_list` 断言**，
   见 `AGENTS.md` §2 第 1 条红线）；
3. 二者独立可做，先拆查询（零 schema 风险）拿到全部收益，再排索引。

**顺带修正失真 KDoc**：`DoseSlotDao.kt:159` 说 `getSlotsInRangeForMedication`
"走现有 `Index(medication_id)`"——真正对口的是 UNIQUE 复合索引
`(medication_id, scheduled_date, scheduled_time)`。文档指错了索引。

---

### P1-10　`TodayViewModel` 每一次槽位变化都在主线程重跑 365 天聚合

**位置**：`ui/screen/today/TodayViewModel.kt:143-150`

```kotlin
private val streakDaysFlow: Flow<Int> = CurrentDateHolder.today.flatMapLatest { today ->
    val startDate = today.minusDays(365).format(...)
    slotDao.observeSlotStatusCounts(startDate, endDate).map { rows ->   // ← 365 天 GROUP BY
        StatsEngine.calculateStreak(today, StatsEngine.aggregateDailyOverallBreakdowns(rows))
    }
}
```

问题有三层：

1. **触发频率**：`observeSlotStatusCounts` 挂在 `dose_slots` 上，而
   `dose_slots` **在每一次打卡 / 跳过 / 结算 / 每轮对账时都变**
   ⇒ 每次服药动作都触发一次 365 天区间的
   `GROUP BY medication_id, scheduled_date, status` 全量聚合。
2. **执行线程**：`stateIn(viewModelScope)` ⇒ 上游在 `Dispatchers.Main.immediate`
   收集。Room 只把**查询本身**放在查询线程，**下游的 `map` 在主线程**，
   所以 `aggregateDailyOverallBreakdowns` + `calculateStreak`
   （365 次循环）在主线程跑。
3. **叠加**：`uiState` 还 `combine` 了 `calendarDayStatesFlow`（另一个按月聚合），
   所以一次打卡 = 2 次额外聚合 + 2 次主线程计算。

**量级**：4 药 × 2 次/天 × 365 天 ≈ 2920 行 → 聚合成约 365×N_med×4 个桶。
单次不算巨大，但它是**主线程 + 高频 + 随历史线性增长**的组合。

**建议修法**：
1. `streakDaysFlow` 加 `.flowOn(Dispatchers.Default)`（聚合是纯 CPU 计算，
   移到默认调度器，一行改动拿掉主线程风险）；
2. 365 天回溯改为**只查需要的窗口**：`calculateStreak` 实际最多回溯到"第一个
   未达成日"，可以让 SQL 带 `LIMIT` 或先取近 30 天、不够再扩；
3. 复用 `calendarDayStatesFlow` 的当月结果，避免同一天被聚合两次。

---

### P1-11　`expiryDate` 的实体注释承诺"提醒"，全库无任何通知消费它

**位置**：`core/data/entity/MedicationEntity.kt:81` 的 KDoc 写
「有效期，格式 `yyyy-MM-DD`。用于库存**临期提醒**」。

实际消费点只有 `InventoryViewModel.kt:142-164` → `InventoryScreen.kt:259-262`
的一个**页内 `NoticeBar`**。**没有任何闹钟、通知、或 `AlarmReconciler` 逻辑读它**。

这正是本项目有明确前例的缺陷类型（`ReminderSettings.kt:14-23` 记录的
`full_screen_alert`：写库 ✓、读库 ✓、一路传进 `Behavior` ✓，然后**从不消费**，
Manifest 里也没有对应权限）。区别是这次还没做成开关，所以危害小一档 ——
但**注释在承诺一个不存在的能力**，后来者会照着它去实现或去相信。

**建议修法**：三选一，**不要拖**：
- (a) 补实现：`ReconcileWorker` 每日对账时检查 `expiry_date`，进入临期窗口
  （如 ≤30 天）就发一条低优先级通知，**只发一次**（记在 `app_settings`）；
- (b) 降级承诺：把注释改成"用于库存页临期提示"，并考虑把临期提示也放到
  今日页/药箱页（现在只在库存页，等于**用户不主动进那个页就永远看不到**）；
- (c) 明确列为"未实现"并在 `FINAL-PRODUCT` 里记一条待办。

> 顺带：(b) 本身也是个体验缺口 —— **唯一能看到有效期的地方是库存页**，
> 而药箱列表/详情页都没有临期标识。

---

## 三、P2：体验债与一致性债

### P2-1　今日页没有"回到今天"的入口，日期条随选中日平移

`TodayViewModel.kt:238`：`weekDates = (-3L..3L).map { selectedDate.plusDays(it) }`。
日期条以**选中日**为中心。用户往回翻 5 天后，"今天"完全移出可视范围，
只能靠反复点最右格一格格挪回来。截图 `01_today.png` 里今天在正中（`30`），
一旦选中 `27`，条变成 `24..30`——用户失去了"今天"的视觉锚点。

**建议**：① 选中日 ≠ 今天时，在日期条上方或标题行右侧显示一个「今天」快捷按钮；
② 或者把日期条锚定在"今天所在周"，选中日用高亮而非平移表达。

### P2-2　顶栏标题恒为「今日用药」，翻看历史日或未来日时是错的

`TodayScreen.kt:134`：`title = stringResource(R.string.today_title)` = "今日用药"，
与 `selectedDate` 无关。但同一个页面上方那行日期文本（`:163-172`）显示的是
`2026年9月30日 星期三`，下方的"待服药"区在非今天时提示
"未来用药预览 · 到达当天方可记录"。

**结果**：用户翻到 10 月 3 日，标题仍写"今日用药"，而内容是三天后的预览。
项目在别处非常在意这件事（`TodayScreen.kt:352-363` 就专门为
"今日已服"标题区分了 `today_completed_title` / `today_completed_title_date`，
KDoc 写"翻到 9-30 时写'今日已服'是**事实错误**"）—— 同一个页面的标题行漏了。

**建议**：标题按 `selectedDate == today` 三态取值：
今天 → "今日用药"；未来 → "用药预览"；过去 → "某日用药"（或直接显示日期）。

### P2-3　语义强调色作小字文本时不达 WCAG AA（3.19:1 / 3.30:1）

见 P0-2 的"顺带修"。完整数据见 [附录 A](#附录-a对比度实测数据)。
`WarningAmber`/`SuccessGreen` 目前**同时**承担"图标 tint"（3:1 门槛，合规）
与"小字文本"（4.5:1 门槛，不合规）两种角色，槽位设计缺了"文字变体"这一层。

### P2-4　`Type.kt` 缺 `bodySmall` / `labelMedium` 槽位，而 UI 已在使用

`Type.kt` 定义了 9 个槽位，**未定义** `bodySmall` 与 `labelMedium`，
但两处都在用：`TodayScreen.kt:648`（`bodySmall`）、
`TodayStreakBadge.kt:78`（`labelMedium`）、`StatsScreen.kt:163`（`bodySmall`）、
`AppNavigation.kt:398` 附近。它们静默回落到 M3 默认值（12sp）。

本项目对 `titleSmall` 做过专门的 KDoc 说明（`Type.kt:52-62`）：
"之前本项目的 Type 槽位'差一个' titleSmall……但它是**默认值而不是决定**"。
同样的道理适用于这两个槽位：项目把 `bodyLarge` 16→15、`bodyMedium` 14→13
都手调过，而 `bodySmall` 停在 M3 默认 12sp，**字号阶梯 15/13/12 的后两档
不是本项目决定的**。

**建议**：补上两个槽位（并按 15/13/11~12 的既有阶梯显式定值 + 一句 KDoc 说明），
或明确接受 M3 默认值并把"未覆盖"写进注释。

### P2-5　进展页每次翻页都全表拉药品

`ProgressViewModel.loadMedMap`（`:301-307`）：`medDao.getAllMedications()` 取全表
再 `.filter { it.id in ids }`。每翻一页调一次。药箱页已经在 `OVERVIEW_SELECT`
里解决了同类问题。**低影响**（药品数少），但与"消除 N+1"的既定纪律不一致。

### P2-6　`SchedulePolicyDao` 的注释与事实相反

`SchedulePolicyDao.kt:96`：「多条计划的时点一次取回；空 id 集合**必须由调用方短路**
（`IN ()` 不是合法 SQL）」。

**事实**：Room 2.6.1 生成代码对空集合展开为字面量 `IN ()`（见
`app/build/generated/ksp/debug/.../DoseRecordDao_Impl.java:790-806` 的
`StringUtil.appendPlaceholders(_stringBuilder, _inputSize)` + 循环不执行），
SQLite 把 `IN ()` 视为合法的"恒假"。而**唯一调用方 `CabinetViewModel.kt:117`
并没有短路**，且工作正常（有 `ScreenSnapshotTest.todayScreen_empty` 的空库
端到端实证：空 `slotIds` 的 `getActiveRecordsForSlots` 成功返回）。

这条注释会诱导后来者加一段**不必要的短路**，更重要的是它传递了一个错误的
SQLite/Room 心智模型。同款错误断言也出现在
`DoseTrackingService.kt:1021` 对 `deleteByIds` 的 `isNotEmpty()` 短路（无害冗余）。

**建议**：改写注释，说明"空集合安全"并给出依据；或统一加一个
`DoseAsserts`-式的 DAO 契约测试把这条行为钉住。

### P2-7　`ReminderSettingsViewModel.save` 的缩进让事务边界"看起来"在错误的位置

`ui/screen/reminder/ReminderSettingsViewModel.kt:408-449`：

```
408  db.withTransaction {
409      adminService.saveReminderPolicy(
...
433      )                      ← 缩进 20 空格，看起来像"withTransaction 内的一层"
434      )                      ← 实际是 saveReminderPolicy 的收尾
436      // 提醒行为写回 ...
438      adminService.saveReminderBehavior(
...
448      )
449      }                      ← 实际是 withTransaction 的收尾
```

**括号配平是正确的**（两步确实在同一个事务里，N5 的修复是有效的），
但**缩进让第 434 行的 `}` 看起来像事务的结束**。任何人照着视觉缩进做后续修改，
把 `saveReminderBehavior` 挪到 434 之下，就会**静默地**把 N5 的修复改回去
（回到"计划已保存、行为未保存，表单停在旧值而库里已是新计划"）。

同样的缩进错乱在 `MedicationDetailViewModel.kt:106-169` 与
`InventoryViewModel.kt:109-182`（`loadOnce` 整体多缩进一级，
且 `MedicationDetailViewModel.kt:169` 是 `}    /**` 挤在同一行）。

**建议**：格式化这三个函数（项目有 `ktlint`/IDEA 可用），并在 §9 收口清单里
加一条"改动跨事务边界时，核对缩进与括号是否同时正确"。

### P2-8　`AppDatabase` 的版本史表缺 v8 行；`exportSchema = false` 会在发布时变成阻碍

`AppDatabase.kt:34-42` 的版本史表列到 **7**，而 `version = 8`（`:71-73`）。
v8（`note_key` 列）的变更只写在 `@Database` 上方的行内注释里，没进表。
这类"表格与代码不同步"正是本项目自己在 `DEVGUIDE` 里反复记录的失败模式。

**更重要**：`exportSchema = false`（`:74`）⇒ 没有任何 schema JSON 落盘。
KDoc（`:109-116`）写着"发布前按 §2 的纪律重建 `MIGRATION_*` 并逐条验证"，
但**重建迁移需要 v7 的真实 schema 作为起点**——而它没有被记录过，
只能靠"改回旧代码跑一次"来反推。项目当前有 8 个版本、8 张表、20+ 列，
这个反推成本很高且极易出错。

**建议**：
1. 版本史表补 v8 行（1 分钟）；
2. **立刻**把 `exportSchema` 打开并在 `app/schemas/` 落盘
   （Room 会自动生成 JSON；需加 `.gitignore` 外的目录约定 + 一条
   `AppDatabaseRealTest` 断言"schema 目录非空"）。这样发布前重建迁移
   才有真相可用，而不是靠记忆。

### P2-9　通知 id 仍是算术身份（内容寻址体系里的最后一处例外）

`Notifications.kt:222`（`notify(slot.id.toInt())`）、`:227`（`cancel`）、
`:247`（`isDoseNotificationShown`）。项目已把**闹钟**（`AlarmScheduler.alarmUri`）
与**通知栏 Action**（`Notifications.actionUri`）都改成内容寻址，
**唯独通知本身的 id 仍是 `slot.id`**。

**当前危害有界**（已核实）：
- `dose_slots.id` 是单表自增主键，正常运行期不会撞；
- 恢复路径在清库**之前**执行了 `NotificationManagerCompat.cancelAll()`
  （`DataExporter.kt:1150`），旧 id 空间在换库前就不存在了；
- 所以**正常路径不会撤错、也不会留残留**。

**残留缺口**：那次 `cancelAll()` 被 `runCatching` 吞异常、失败不阻断恢复
（`:1150-1151`）。一旦失败，旧通知带着旧 id 存活；恢复后新库若有同号 slot，
`AlarmReconciler.kt:332/365` 的 `isDoseNotificationShown` 会**误判"已提醒过"
从而跳过补响** ⇒ 漏提醒（第一承诺受损）。

**建议**（低成本，闭合体系）：
1. 通知 id 改用 `(medId shl 16) xor (date+time 的 hash)` 这类**内容派生** id，
   与闹钟/Action 的哲学一致；
2. 或者至少把那次 `cancelAll()` 的失败**升级为阻断恢复**
   （它是"旧通知会误伤新库"的前置条件，不是可选的保险），
   并把 `writeSafetySnapshot` 的"失败不阻断"与它区分开 —— 前者是保险，
   后者是安全前提，两者不该同一种处理。

---

## 四、P3：清理与卫生

### P3-1　`DoseSlotDao.updateDerivedColumns` 的 KDoc 自相矛盾

`DoseSlotDao.kt:43-44`（`update` 已删除处的占位注释）声称
"`[update] 从不调用`"——而 `[update]` 本身已被删除，这个论断指向一个不存在的符号。
`OPEN-ISSUES-20260930.md` L7 记过同一条，尚未清理。

### P3-2　`DoseSlotEntity` 的索引清单与实际用途说明不完全对应

`DoseSlotEntity.kt:42-51` 的四条索引没有任何一条说明**为什么**是这四条、
以及哪条为哪条查询服务。结合 P1-9（`snooze_until_ts` 缺失）看，
索引是"想到就加"而非"按查询反推"。建议补一张
"查询 → 索引"对照表进 KDoc，改索引时才有依据。

### P3-3　`isWithinEditWindow` / `isSameLocalDay` 挂在包级、且在 getter 里读挂钟

`DoseRecordDetailViewModel.kt:50-67`：两个顶层函数直接 `LocalDate.now()` /
`System.currentTimeMillis()`，被 `DoseEntryUiState` 的
`isToday`（`:139-140`）与 `withinEditWindow`（`:143-144`）在 **getter 里**调用。

而同文件 `:109-116` 的 KDoc 明确写着相反的纪律：
> 「由 ViewModel 在刷新时按 `SlotActionPolicy` 算一次并**存下来**，
> 而不是在 getter 里读挂钟：getter 里读挂钟会让这个纯状态类的测试
> 随运行日期变色」。

`isActionable` 遵守了，`isToday` / `withinEditWindow` 没遵守。后果：
App 跨午夜时 UI 不会重组（无状态变化），`canUndo` 仍返回旧值 ——
**跨午夜后撤销按钮的可用性会错到下一次任意重组才纠正**。

**建议**：与 `isActionable` 一致，把这两个也在 `refreshFromSlot` 里算一次存进状态。
（`isContradictoryRecord` 用的是槽位与事实的相对比较，不读挂钟，是对的。）

---

## 五、值得保持的既有设计（勿在后续"优化"中破坏）

审阅过程中确认以下设计**成立且有测试/变异验证守护**，列出来以免被误当作"过度设计"
在后续清理中被删掉：

| 设计 | 为什么不能删 |
| :--- | :--- |
| 库存账本 append-only、余额 = `SUM(change_amount)`、`medications` 不存 `current_stock` | 让 5/6 库存路径退化成单条 INSERT，并发打卡不再可能丢更新 |
| 幂等锚点下沉到 SQL 的 `WHERE`（`markCompletedIfOpen` / `snoozeSlot` / `markExpired` / `revertToPending`） | 判据落在数据上，任何调用方（通知栏 / 手表 / 未来 Widget）都绕不过 |
| 闹钟与通知 Action 的**内容寻址** Uri | `filterEquals` 不看 extras；`slot.id` 会因备份恢复而变 |
| 暂停参与**投影**而不是显示过滤 | 槽位存在 ⟺ 这个时点会响，统计/闹钟/今日清单三处自动一致 |
| 「当日结束」结算线与「2 小时」补响线**拆成两条** | 曾共用一个常量，让"几点算逾期"冤枉了所有上午没吃药的人 |
| 负库存被支持、不 `coerceAtLeast(0f)` | 钳制正是"账面钉在 0、流水记全额"的成因 |
| 手动补录与「未来槽位不可表态」共用 `SlotActionPolicy`，一份判据两处使用 | 避免"UI 放行、服务层拒绝"的分叉 |
| `Quantity` / `DecimalInput` / `StatsEngine.scheduledDaysPerWeek` / `isLowStock` 四个"单一实现" | 五处各写一份的口径漂移是这个项目反复付出代价换来的教训 |
| `rescheduleAll(presnap)`：先删行的调用点在删行**前**拍快照 | 内部快照看不到已删的行，那些闹钟会变成到点空唤醒的孤儿 |

---

## 六、建议修复批次

| 批次 | 内容 | 理由 |
| :--- | :--- | :--- |
| **① 立刻** | P0-3（Refill 提示不可达）、P2-8（版本史补 v8 + 打开 `exportSchema`） | 一个是"修了等于没修"，一个是给发布前留真相。都是零风险小改 |
| **② UX 关键** | P0-1（Streak 算法 + 首日徽章）、P0-2（outline 文本色 + 强调色文字变体） | 两个都是用户直接看见的"信息不可信 / 信息看不见" |
| **③ 数据一致性** | P1-1（库存页草稿）、P1-2（`min_stock_alert` 字段所有权）、P1-11（`expiryDate` 承诺） | 同一类"表单写了它没有的字段 / 界面盖掉了用户输入"，一起改一起验 |
| **④ i18n 收口** | P1-3、P1-4 | 把 4 套星期实现收敛成 1 套、删掉 21 条重复资源、并把扫描脚本变成门禁 |
| **⑤ 文案一致性** | P1-5（频次三处口径）、P1-6（跟随全局哨兵）、P2-2（顶栏标题） | 都是"同一件事在两处说得不一样"，抽单一实现最省事 |
| **⑥ 交互闭环** | P1-7（通知落点）、P1-8（分页竞态）、P2-1（回到今天） | 需要走查截图与交互验证 |
| **⑦ 性能** | P1-9（拆 OR 查询 + 索引 + 升 version）、P1-10（Streak 主线程）、P2-5 | 拆查询零风险先做；索引那条要按红线升 version + 补 PRAGMA 断言 |
| **⑧ 卫生** | P2-6、P2-7、P3-1、P3-2、P3-3 | 注释与事实对齐、格式化、缩进不再骗人 |

---

## 附录 A：对比度实测数据

用 WCAG 2.1 相对亮度公式计算（sRGB → 线性 → `0.2126R+0.7152G+0.0722B`，
比值 `(L1+0.05)/(L2+0.05)`）。**浅色主题**（默认）：

| 前景 | 背景 | 比值 | 门槛 | 判定 | 使用位置 |
| :--- | :--- | :---: | :---: | :---: | :--- |
| `outline` `#E2E8F0` | `surface` `#FFFFFF` | **1.23 : 1** | 4.5 | ❌ **严重** | 归档状态字、已撤销/已跳过芯片 |
| `outline` `#E2E8F0` | `surfaceVariant`40% ≈ `#F8FAFC` | **1.18 : 1** | 4.5 | ❌ **严重** | 「已跳过 · 未扣减余量」 |
| `WarningAmber` `#D97706` | `WarningAmberContainer` `#FEF3C7` | **2.86 : 1** | 4.5 | ❌ | 今日页「去添药 >」按钮文字 |
| `WarningAmber` `#D97706` | `surface` `#FFFFFF` | 3.19 : 1 | 4.5 | ❌（图标 3:1 达标） | 库存页余量数字、详情页依从率 |
| `SuccessGreen` `#16A34A` | `surface` `#FFFFFF` | 3.30 : 1 | 4.5 | ❌（图标达标） | 已服状态字、流水金额 |
| `onSurfaceVariant` `#475569` | `surface` `#FFFFFF` | 7.58 : 1 | 4.5 | ✅ | 主流次要文字 |
| `OnWarningAmberContainer` `#92400E` | `WarningAmberContainer` `#FEF3C7` | 6.37 : 1 | 4.5 | ✅ | 低库存横幅正文（已正确） |

**深色主题**：

| 前景 | 背景 | 比值 | 判定 |
| :--- | :---: | :---: | :---: |
| `outline` `#475569` | `surface` `#1E293B` | **1.93 : 1** | ❌ |
| `onSurfaceVariant` `#94A3B8` | `surface` `#1E293B` | 6.13 : 1 | ✅ |
| `onSurface` `#F8FAFC` | `surface` `#1E293B` | 13.4 : 1 | ✅ |

**建议新增的两个槽位（已验算）**：
`SubduedTextLight = #64748B` on `#FFFFFF` = **4.76 : 1** ✅；
`SubduedTextDark = #94A3B8` on `#1E293B` = **6.13 : 1** ✅。

---

## 附录 B：本次核验方法与不确定项

**实际执行过的核验**（不是"读代码推测"）：

| 结论 | 核验方式 |
| :--- | :--- |
| 编译通过、单测 518 项全绿 | `./gradlew compileDebugKotlin` / `testDebugUnitTest --rerun-tasks` 实跑 |
| Streak 徽章显示「★ 0 天」而当日已服 2 次 | 看图 `temp/appscreenshots/01_today.png` |
| 顶栏「今日用药」与历史/未来日不匹配 | 读 `TodayScreen.kt:134` + `:163-172` + `:352-363` |
| `outline` 用作正文色的 5 处调用点 | `rg` 全量定位 + 逐处读上下文确认是 `Text(color=…)` 而非描边 |
| 对比度数值 | 按 WCAG 公式实算（脚本见正文命令） |
| 空 `IN ()` 安全 | 读生成代码 `DoseRecordDao_Impl.java:790-806` + `ScreenSnapshotTest.todayScreen_empty` 端到端实证 |
| `getStaleOpenSlots` 走全表扫 | 读 `DoseSlotEntity` 索引清单 + 生成 DDL + SQLite OR 优化规则 |
| 恢复后 `dose_slots.id` 原样回填、`cancelAll()` 已前置 | 读 `DataExporter.kt:1144-1188` + `:836-850` |
| 8 个关键字段的写/读/备份三处齐全 | 逐字段追三条链路（`DoseSlotDao`/`ReminderSettingsDao`/`MedicationAdminService`/`Notifications`/`AlarmReconciler`/`BackupFormat`） |
| 硬编码中文 35 处（其中 UI 9 处） | Python 脚本逐行扫（排除注释行），结果见 P1-3 |
| 资源文件编码 | 逐文件验 UTF-8 + BOM + 行尾（**全部合法**，无 mojibake 风险） |

**未能实证、标注为推断的**：

1. `getStaleOpenSlots` 的"全表扫描"是**基于 SQLite 查询规划规则的推断**，
   未跑 `EXPLAIN QUERY PLAN`。要落定建议实测一次：
   ```sql
   EXPLAIN QUERY PLAN SELECT * FROM dose_slots
   WHERE (status='PENDING' AND scheduled_ts < 0)
      OR (status='SNOOZED' AND snooze_until_ts IS NOT NULL AND snooze_until_ts < 0);
   -- 看是否出现 "SCAN dose_slots"
   ```
2. P0-1 的"停药三个月仍显示 30 天"是**按代码路径推演**得到的，未在模拟器上
   造这份数据实测。建议用一条 `SlotProjectionDstServiceTest` 风格的
   Robolectric 用例把它钉住（构造：6-01 前每天全服、之后无槽位、`today` = 9-30）。
3. `StatsEngine.calculateStreak` 的"跨空档不断签"在**隔日用药**场景下是
   预期行为、但在**疗程结束**场景下是缺陷——这两者用现有字段能否无歧义区分，
   取决于产品对"停药后是否保留历史 streak"的口径，**需要产品拍板**（见下）。

**需要产品拍板的两点**（不宜由实现侧直接决定）：

- **Q1**：停药 / 归档 / 疗程结束后，顶栏的连续天数徽章应该
  (a) 继续显示历史值（现状，且有误导风险）、
  (b) 归零、还是 (c) 隐藏徽章？
  选定后 P0-1 的修法 1 与缺陷 B 的修法才能定稿。
- **Q2**（`OPEN-ISSUES-20260930.md` D1 仍开放）：手动补录是否回填当日槽位。
  补录当前只写 `dose_records`（`slotId = null`），不命中当日开放/过期槽位，
  所以"补打卡"之后依从率仍算漏服、槽位仍挂在待服清单里。
  本轮审阅未改变该结论，但它与 P0-1 的 streak 算法**直接相关**
  （补录能否让某天变成"全服"，取决于这条口径）。
