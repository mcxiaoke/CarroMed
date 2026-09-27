# CarroMed 代码 / 架构 / 界面 全量审查报告

> **报告时间**：2026-09-27 21:53 (GMT+8)
> **审查基线**：git 工作区当前状态（`README.md` 声称 71 项测试全绿已复核为真）
> **审查对象**：`app/src` 全部 76 个 Kotlin 源文件（main 61 / debug 2 / test 10）、`AndroidManifest.xml`(main+debug)、`res/`、`app/build.gradle.kts`、`tools/app_screenshots.py`、`docs/` 全部 14 份文档
> **审查方式**：逐行阅读 + 交叉验证 + 编译/测试实证 + 截图复核 + 产品决策文档比对
> **实证基线**：
> - `./gradlew testDebugUnitTest --rerun-tasks` → **BUILD SUCCESSFUL，71 tests / 0 failures / 0 errors**（已核对 `app/build/test-results/testDebugUnitTest/*.xml` 汇总）
> - `./gradlew assembleRelease` → **BUILD SUCCESSFUL**（46s）
> - `temp/appscreenshots/` 30 张走查截图 + `manifest.md`（2026-09-27 20:39 生成）
> - 模拟器 `emulator-5554` 已连接（Android 15 / API 35）
> **对照基准**：`docs/FINAL-PRODUCT.md` v3.1.0（产品唯一权威源）、`docs/FINAL-ARCHITECTURE.md`、`docs/ARCHITECTURE.md`、`docs/reviews/DESIGN_REVIEW.sbf.md`（前一轮设计阶段审查）
>
> **本报告未修改任何源码**。按 AGENTS.md §3/§7，本次为纯审查、无代码变动，故未向 `CHANGES-20260927.md` 追加变更摘要。

---

## 〇、结论速览

### 0.1 一句话判断

**这个项目的"表面质量"和"内核质量"严重不匹配。**

界面层、状态管理、局部 UPDATE、数据库迁移、夜间静音渠道、CSV BOM、导入导出闭环这些**上一轮审查点名的缺陷确实都真修好了**，代码可读性、KDoc 密度、架构约束的自觉执行都明显高于一般个人项目水平（详见 §7 平衡清单）。

但**产品第一承诺"到点一定响"所依赖的那条链路本身是断的**，且断在三个互相独立的环节上；**库存账本声称的绝对不变式在正常用户操作下就会被打破**；**"编辑药品信息"这个最常用的操作会静默抹掉三个提醒行为配置**。这些不是边角料，是承诺本身的兑现问题。

更值得注意的是：上一轮审查报告（`PLAN-REVIEW-20260927-v2r.md`）宣布"12 个 P0 全部修复"并收口，而本轮发现的 3 个 P0（#1 闹钟号段碰撞、#2 闹钟视野仅 7 天、#4 打卡无幂等）在上一轮的**审查范围里根本没有被检查过** —— 上一轮把"闹钟会不会撞车""闹钟能管多久"这两个决定产品成败的问题整个跳过了。`docs/reviews/DESIGN_REVIEW.sbf.md:149` 曾在设计阶段明确预警过 RequestCode 编码必然碰撞、并给出"用 slot.id 且**放弃任何算术编码**"的建议，实现采纳了前半句（用 slot.id）却重新引入了后半句所禁止的算术编码。**这说明"修完 12 个 P0 就收口"的判断本身是过早的。**

### 0.2 分维度评分

| 维度 | 评分 | 依据摘要 |
| :--- | :---: | :--- |
| 架构分层与领域纯度 | **A** | `core/domain` 零 `android.*` 依赖（仅 `withTransaction` 豁免），纯函数引擎可 JVM 直测；槽位/事实/流水三层解耦落地 |
| 数据模型与迁移 | **A-** | v1→v2 迁移纯 `ADD COLUMN`、`MigrationTest` 手写真实 SQLite 校验扎实；但 `exportSchema=false` 放弃了 Room 的迁移自动校验 |
| 状态管理（Compose） | **A-** | 8 个 ViewModel 全部 `collectAsStateWithLifecycle` + 正确 nav 作用域绑定，`remember` 无反模式，LazyColumn key 稳定 |
| 界面与交互 | **B+** | 信息架构（三分离）、空态意识、`0/0` 不谎报 100% 都做得很好；但存在空实现按钮、死控件、缺空态的组件 |
| 提醒内核（核心卖点） | **D** | 号段碰撞、视野仅 7 天无自续期、SNOOZED 永不结算、权限被撤销无自愈 —— 四处独立缺陷叠加 |
| 数据正确性 | **D+** | 库存守恒被破、跨单位求和、补录不入统计、undo 删事实、endDate 无法清除 |
| 测试有效性 | **C** | 71 项全绿属实，但 `AddEditLogicTest` 8 项中 3~4 项是**测 Kotlin 标准库**的空测；ViewModel / UI 层零单测 |
| 工程卫生 | **C+** | 密钥已 gitignore、无网络权限、`exported` 齐全；但 release 未开 R8、7 处编译弃用警告、release 源集残留 300 行死代码 |

### 0.3 问题分布

| 严重度 | 数量 | 含义 |
| :--- | ---: | :--- |
| **P0 致命** | **7** | 会导致静默漏提醒 / 数据错误 / 用户看到错误医疗信息，必须修 |
| **P1 重要** | **20** | 违反已拍板的产品决策、统计口径错误、可复现的功能失效 |
| **P2 建议** | **24** | 一致性、性能、死代码、可维护性 |
| **P3 卫生** | **16** | 代码风格与工程规范 |
| 疑似（需人工确认） | 6 | 代码无法单独定论，需真机或产品决策 |

### 0.4 建议修复顺序

```
第 1 批（1~2 天，只动 core/alarm + core/domain，不碰 UI）
  P0-1 闹钟号段碰撞        → 改用 Intent.setData(Uri) 判重，放弃算术编码
  P0-2 闹钟视野 7 天        → AlarmReceiver 触发后续期 + 窗口提到 14 天
  P0-4 takeDose 幂等守卫     → 守卫下沉进 withTransaction 内部
  P1-20 EXTRA_IS_ADVANCE 只写不读（顺手，AlarmReceiver 已在改）

第 2 批（1~2 天，只动 core/domain）
  P0-3 库存守恒 + D-9 负库存  → changeAmount 记实际变化量，允许负库存
  P1-1  undo 不删事实        → 追加 REVERT 事实而非 DELETE
  P1-2  SNOOZED 结算 EXPIRED
  P1-3  endDate 可清除
  P1-6  D-7 整数毫单位（需评估迁移成本，可单独排期）

第 3 批（1 天，UI + 真机走查）
  P0-5 编辑丢提醒行为         → ProfileDraft 移除这三列
  P0-6 自检页真实检测         → 新建 PermissionCheckViewModel
  P0-7 统计跨单位求和         → 按单位分组展示
  P1-7  "昨天此时"实为 00:00
  P1-8/9/10/11 表单校验与文案

第 4 批（架构改造，需排期）
  P1-5  闹钟泄漏（reconcile 不再删+重建）
  P1-12 测试整改（删空测 + 补关键不变量）
  P2-xx 文档与实现对齐
```

---

## 一、P0 致命问题（7 项）

### P0-1 ★★★ 闹钟 requestCode 的"两段号"在数学上并不相交 —— 静默漏提醒 + 误取消他人闹钟

**位置**：`core/alarm/AlarmScheduler.kt:24-32`，配 `core/alarm/AlarmReconciler.kt:66-76`

**代码**

```kotlin
// AlarmScheduler.kt:23-32
/**
 * requestCode 分段：主闹钟 = slotId；提前提醒闹钟 = slotId * 10 + 1。
 * slotId 是全局唯一自增主键，因此两个号段天然不相交，取消互不干扰。   ← 这个断言是错的
 */
private fun pendingIntent(context: Context, slotId: Long, advance: Boolean): PendingIntent {
    val intent = Intent(context, AlarmReceiver::class.java)
        .setAction(ACTION_DOSE_ALARM)            // ← 两者 action 相同
        .putExtra(EXTRA_SLOT_ID, slotId)
        .putExtra(EXTRA_IS_ADVANCE, advance)
    val requestCode = if (advance) (slotId * 10 + 1).toInt() else slotId.toInt()
    return PendingIntent.getBroadcast(
        context, requestCode, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
```

**为什么注释里的断言不成立**

`PendingIntent` 的相等判定是 `requestCode` + `Intent.filterEquals()`。`filterEquals` 只比较 action / data / type / identity / package / component / categories，**extras 不参与判重**。

而两段号段的集合是：
- 主闹钟：`{1, 2, 3, 4, ...}`
- 提前闹钟：`{11, 21, 31, 41, ...}`（`10N+1`，N ≥ 1）

两者的交集是 `{11, 21, 31, 41, ...}` —— **只要槽位 id 落在 ≡1 (mod 10)，就与另一个槽位的提前闹钟同号**。注释说的"天然不相交"是把"数值不同"当成了"PendingIntent 不同"，忽略了同一函数内构造的两个 Intent 的 action 与 component 完全一致。

**具体碰撞（N=1, M=11）**

| 步骤 | 调用 | requestCode | component / action | 结果 |
| --- | --- | ---: | --- | --- |
| 1 | `schedule(slotId=1, advance=true)` | `10×1+1 = 11` | `AlarmReceiver` / `DOSE_ALARM` | 创建 PI_A，extras = `{slot_id:1, is_advance:true}` |
| 2 | `schedule(slotId=11, advance=false)` | `11` | **同上，完全相同** | `filterEquals` 判定相等 → **返回 PI_A**，`FLAG_UPDATE_CURRENT` 把 extras 覆盖为 `{slot_id:11, is_advance:false}` |
| 3 | 两次 `setExactAndAllowWhileIdle(..., PI_A)` | — | — | 同一个 PI → **后一次替换前一次，只剩一个闹钟** |

**净后果（两条都是静默的）**

1. **槽位 1 的"提前 N 分钟"提醒被彻底吞掉** —— 提前闹钟与主闹钟合并成了一条，且时间被改成槽位 11 的准点时间。
2. **更危险：槽位 1 的任何 `cancel` 会连带杀掉槽位 11 的主闹钟。** `AlarmScheduler.cancel(context, 1)` 内部会执行 `alarmManager.cancel(pendingIntent(ctx, 1, advance = true))`，其 requestCode 正是 `11` —— 命中槽位 11 的主闹钟。而 `cancel(context, 1)` 的调用点有四处高频路径：`AlarmReconciler.kt:33`（槽位 1 判逾期时）、`TodayViewModel.kt:134`（打卡后）、`TodayViewModel.kt:151`（跳过后）、`DoseActionReceiver.kt:52/72`（通知栏操作后）。

   → **结果：槽位 11 这一次服药永远不响，且不记漏服、不提示、没有任何日志。** 这正是产品第一承诺明令禁止的"静默漏提醒"。

**触发条件有多容易达到**

只需同时满足：
- 用户给**任意一个**药品设置了「提前提醒」> 0（`MedicationEntity.advanceMinutes`，`ReminderSettingsScreen` 有 Stepper，默认 0）；**且**
- 7 天窗口内存在 ≥ 11 个槽位。

7 天窗口 `AlarmReconciler.kt:47` 投影 `[today, today+7]` 共 8 天，单药 2 次/天 = 16 个槽位；2 药各 2 次/天 = 32 个。**任何长期服两种以上药的慢病患者，第一天就会满足。** 且 id ≥ 11 的槽位与 10N+1 型槽位共存是必然的（id 连续递增 1,2,3,...）。

**加剧因素**：`rescheduleAll` 有 8 个调用点（含 `MainActivity` 每次 RESUMED），每次都会把未来 PENDING 槽位删掉重建、id 持续增长，因此**必然不断产生新的 ≡1 (mod 10) 的 id**，碰撞不是"可能"而是"持续发生"。详见 P1-5。

**修改建议（按推荐度排序）**

1. **采纳 `docs/reviews/DESIGN_REVIEW.sbf.md:166` 早已给出的方案**：`PendingIntent.setData(Uri.parse("carromed://slot/<id>?adv=1"))`，靠 URI 判重，**放弃任何算术编码**。这是唯一能根除此类问题的做法。
2. 若坚持 requestCode，必须让号段**集合层面**不相交，例如主闹钟 `N`、提前 `4N+1`、通知 Action `4N+2 / 4N+3 / 4N+4`，并在 KDoc 里用一段可执行的断言锁死。
3. 同时删除 `(slotId * 10 + 1).toInt()` 的 `toInt()` 截断（虽然 `slotId > 2^31` 在现实中不可达，但"零碰撞"的说法目前是错的，P3-9 同源）。
4. **补一条纯函数单测**（成本极低、收益极高）：把 requestCode 计算抽成 `AlarmRequestCode.of(slotId, kind)`，遍历 1..10_000 断言五个号段两两不交。

---

### P0-2 ★★★ 闹钟可视范围只有 7 天，且没有任何自续期机制 —— 第 8 天起提醒静默停摆

**位置**：`core/alarm/AlarmReconciler.kt:39-49`、`core/alarm/AlarmReceiver.kt:20-51`

**代码**

```kotlin
// AlarmReconciler.kt:39-49
// 2. 活跃药品 (在服且未暂停) 未来 7 天排班幂等补齐
val activeMeds = db.medicationDao().getActiveMedications()
    .filter { !it.isPaused && !it.isArchived }
val tracking = DoseTrackingService(db)
for (med in activeMeds) {
    tracking.reconcileSchedule(
        medicationId = med.id,
        fromDate = LocalDate.now(),
        toDate = LocalDate.now().plusDays(7)      // ← 窗口就是 7 天
    )
}
```

```kotlin
// AlarmReceiver.kt:26-50（全文）
val result = goAsync()
CoroutineScope(Dispatchers.IO).launch {
    try {
        ...
        Notifications.showDoseNotification(context, slot, med, behavior)
        Log.i("AlarmReceiver", "notification shown for slot=$slotId")
    } catch (t: Throwable) { ... } finally { result.finish() }
}
// ← 触发后只弹通知，从不调用任何 AlarmScheduler.schedule
```

`AlarmScheduler` 全部使用 `setExactAndAllowWhileIdle` / `setAlarmClock` / `setAndAllowWhileIdle` —— **全部是一次性闹钟，没有任何 `setRepeating`，也不存在"响完自动排下一次"的逻辑**。

**触发条件与后果**

`rescheduleAll` 的全部调用点只有 8 处，**没有一处是系统或时间驱动的**：

| 调用点 | 触发条件 | 是否需要用户主动 |
| --- | --- | :---: |
| `MainActivity.kt:44` | 每次 `Lifecycle.State.RESUMED` | 是 |
| `BootReceiver.kt:29` | 开机 / 换包 / 改时 / 改时区 | 否（但用户可能几个月不重启） |
| `TodayViewModel.kt:62` | VM `init`（每次进今日页） | 是 |
| `TodayViewModel.kt:143` | 撤销打卡后 | 是 |
| `AddEditMedicationViewModel.kt:358` | 保存药品后 | 是 |
| `ReminderSettingsViewModel.kt:232` | 保存提醒设置后 | 是 |
| `MedicationDetailViewModel.kt:155` | 详情页写操作后 | 是 |
| `InventoryViewModel.kt:152` | 库存页写操作后 | 是 |
| `SettingsViewModel.kt:132` | 设置页写操作后 | 是 |

→ **用户连续 8 天不打开 App、期间设备也没重启/换包/改时区，则第 8 天起全部提醒彻底消失。**

而且此时 `dose_slots` 表里也**没有第 9 天的槽位可排**（`reconcileSchedule` 只投影到 `today+7`），所以不是"有槽位没闹钟"，是**数据层和调度层同时断供**。用户重新打开 App 会自愈（重新投影 + 重排），但：
- 丢失的那几天**不会补发**；
- **不会留下任何痕迹** —— 那几天的槽位从未存在过，统计里查不到，用户也无从知道"我本该吃药但 App 什么都没提醒"。

**与已拍板决策的冲突（这一条尤其要写清楚）**

- `docs/FINAL-PRODUCT.md:45` **D-14**：「提醒降级三档：精确闹钟 → `setAlarmClock` 兜� → **WorkManager + 启动对账自愈**」。
  实现的第三档是 `setAndAllowWhileIdle`（`AlarmScheduler.kt:68`），**不是 WorkManager**；`app/build.gradle.kts:80-115` 的依赖清单里**没有任何 work-runtime**。即"自愈档"根本不存在，"启动对账"只覆盖了开机/换包/改时三类事件，不覆盖"长期不打开"。
- `docs/FINAL-PRODUCT.md:73` **M-03**：「提醒内核 … **触发即重排**、开机/时区/改时/升级/**前后台**全触发源对账」。
  "触发即重排"未实现（`AlarmReceiver` 不重排）；"前后台全触发源"实现了，但它只能救"用户打开了 App"的情况。
- `README.md:100-105` 与 `AlarmScheduler.kt:9-16` 的 KDoc 都把"可靠"写成已实现，**未披露 7 天窗口这个硬边界**。

**为什么这个缺陷能活过上一轮审查 —— 也是本次审查最值得记录的一点**

`docs/FINAL-PRODUCT.md:159` 给出的可靠性验收标准是：

> 提醒可靠性 | 真机矩阵（Doze/杀后台/重启/改时区/权限被拒）**连续 7 天零漏提醒**

**闹钟视野的天花板（7 天）恰好等于验收时长（7 天）。** 于是任何"连续 7 天"的测试都**不可能**暴露这个缺陷 —— 第 8 天才出问题，而测试在第 7 天就结束了。这是本次审查发现的最值得记入工程规范的一条教训：**验收标准的时长不得等于或短于被验证机制的边界**。

**修改建议**

1. **首要**：在 `AlarmReceiver` 成功弹通知后，对该药品续期 —— 投影 `[today, today+14]` 并重排。这就把"滚动续期"内建进闹钟链路，不再依赖用户开 App。这是唯一能真正兑现"触发即重排"的做法。
2. 窗口从 7 天提到 14 天（与 `DoseTrackingService.kt:293` 的默认值一致），降低对入口的依赖深度。
3. 补一个"漏发自检"：对账时若发现某药品最后一次闹钟已消耗且无后续槽位，写一条 `app_settings` 计数，下次进 App 时提示"自 X 月 X 日起未生成提醒"。**把静默失败变成可见失败**是这类问题最有效的兜底。
4. 修订 `docs/FINAL-PRODUCT.md` 的验收标准为"连续 21 天零漏提醒"，并把它列入 AGENTS.md §9 收口自检。

---

### P0-3 ★★★ 库存台账守恒不变式在正常用户操作下即被打破，且违反已拍板的 D-9

**位置**：`core/domain/service/DoseTrackingService.kt:61-73`（打卡）、`:177-189`（补录）

**代码**

```kotlin
// DoseTrackingService.kt:61-73
if (medication.isStockTracked) {
    val newStock = (medication.currentStock - finalDose).coerceAtLeast(0f)   // ← 钳到 0
    val tx = InventoryTransactionEntity(
        medicationId = slot.medicationId,
        recordId = recordId,
        changeAmount = -finalDose,        // ← 却记了全额扣减
        balanceAfter = newStock,           // ← 快照记 0
        txType = TransactionType.TAKEN_DEDUCT,
        note = note ?: "按时服药打卡扣减"
    )
    inventoryDao.insert(tx)
    medDao.updateStock(slot.medicationId, newStock)
}
```

**不变式被打破的具体算例**

`currentStock = 0.5`（还剩半片），`doseAmount = 1.0`（该吃一片）：

| 量 | 值 |
| --- | ---: |
| `SUM(change_amount)` | 建档 `+0.5` + 本次 `-1.0` = **−0.5** |
| `medications.current_stock` | **0.0** |
| 不变式 `SUM(change_amount) == current_stock` | **−0.5 ≠ 0.0 ✗** |

**为什么这不是小事**

1. AGENTS.md §2 第 2 条把它列为**红线**：「任何时候 `SUM(change_amount) == currentStock`」。
2. 漂移**不可自愈、只会放大**：`calibrateStock`（`:206-208`）以 `currentStock` 为基准算 delta、`refillStock`（`:267`）以 `currentStock` 为基准算新余额，两者都建立在"账面 = 流水和"的前提上。一旦漂移存在，后续每次盘点/补药都在错误基准上继续错。
3. `AppDatabaseRealTest` 与 `DoseTrackingServiceTest` 现有用例全部选在"库存充足"（20 片吃 2 片、5+100、10 不变），**没有一条覆盖"库存不足"**，所以 71 项全绿掩盖了这个洞。
4. 触发门槛极低：只要用户某天忘了补药、库存低于单次剂量，就会命中。这是长期服药用户的**常态**而非边缘场景。

**同时违反已拍板的产品决策 D-9**

`docs/FINAL-PRODUCT.md:40`：

> **D-9 库存不足时**：**允许扣为负数，绝不阻止打卡**；负库存单独视觉化提示盘点。服药是物理事实，优先于库存记账；出差忘带药是日常场景。

实现的 `.coerceAtLeast(0f)` 是**把账面钉在 0**，语义正好相反：
- 用户确实吃了那一片（物理事实成立）；
- 但账面显示"还剩 0"，用户**不会收到"负库存，请盘点"的提示**（因为账面永远 ≥ 0，`minStockAlert` 触发条件 `currentStock <= minStockAlert` 在 0 时仍会触发低库存横幅，但语义是"没药了"而不是"账实不符，请盘点"）；
- 而流水却记了 −1，账实永久对不上。

值得注意的是，`DoseTrackingService.kt:150-153` 的 KDoc 写得非常清醒：

> 服药是不可否认的事实，因此 **绝不因为库存不足而阻止记账**。

**设计意图是对的，实现漏掉了负库存这一半。**

**修改建议（二选一，推荐第 2 个）**

1. 保持"账面不负"：`changeAmount` 改记 **实际变化量** `newStock - medication.currentStock`，并在 `note` 里写明"库存不足，实际扣减 X"。守恒立刻成立，但 D-9 的"负库存提示盘点"仍缺失。
2. **执行 D-9**：允许 `currentStock` 为负，`changeAmount` 记全额 `−finalDose`。负库存是**有价值的信息**（它告诉用户"你的账面和实物已经不一致，该盘点了"），把它钳成 0 等于把一个诊断信号销毁。UI 侧把负数渲染为警示色 + "账面 −3，请盘点"。
3. 无论选哪个，**必须补一条测试**：`takeDose` 库存 0.5 打 1.0，断言 `getSumOfChanges(medId) == getMedicationById(medId)!!.currentStock`。这条断言应当被提炼成"所有库存写路径都必须满足"的通用不变量测试（覆盖 `takeDose` / `undoDose` / `logManualDose` / `calibrateStock` / `refillStock` / `setStockTracking` 六个入口）。

---

### P0-4 ★★★ `takeDose` 在领域层没有幂等守卫 —— 重复扣库存、重复写事实，且撤销后库存永久丢失

**位置**：`core/domain/service/DoseTrackingService.kt:34-76`；调用方 `ui/screen/today/TodayViewModel.kt:131-136`

**代码**

```kotlin
// DoseTrackingService.kt:34-46
suspend fun takeDose(slotId: Long, actualTs: Long = ..., takenAmount: Float? = null, note: String? = null)
    : Boolean = db.withTransaction {
    val slot = slotDao.getSlotById(slotId) ?: return@withTransaction false
    val medication = medDao.getMedicationById(slot.medicationId) ?: return@withTransaction false
    val finalDose = takenAmount ?: slot.doseAmount

    // 1. 更新槽位状态为 COMPLETED      ← 没有 "if (slot.status == COMPLETED) return false"
    slotDao.updateStatus(slotId = slotId, status = SlotStatus.COMPLETED, actualTs = actualTs)

    // 2. 插入服药历史事实记录            ← 无条件插入第二条
    val recordId = recordDao.insert(record)
    ...
}
```

**领域层唯一的查询是"槽位存在吗"，没有校验槽位当前状态。** 而 `DoseSlotDao.updateStatus`（`:52-53`）是无条件 `UPDATE ... WHERE id = :slotId`，不带 `AND status = 'PENDING'`。

**与已拍板验收标准的直接冲突**

`docs/FINAL-PRODUCT.md:187` **M4 出口标准**：

> 今日打卡闭环 | 通知连点 3 次仅扣 1 次库存（**幂等验证**）

这条验收标准**没有达成**，而且 71 项测试里也没有任何一条覆盖它。

**两条触发路径**

**路径 A — App 内今日页连点 ✓（无任何守卫）**

```kotlin
// TodayViewModel.kt:131-136
fun takeDose(slotId: Long) {
    viewModelScope.launch {
        trackingService.takeDose(slotId)          // ← 返回值直接丢弃
        AlarmScheduler.cancel(getApplication<Application>(), slotId)
    }
}
```

`viewModelScope` 是 `Dispatchers.Main.immediate`，`withTransaction` 会被 Room 派发到自己的 query executor。用户在卡片刷新回来之前连点 2~3 次 → 2~3 个协程并发进入 `takeDose` → 都读到 `status = PENDING` → 各自写一条 COMPLETED、各插一条 `dose_records`、各扣一次库存。**UI 侧没有任何 in-flight 禁用**（`TodayScreen` 的 ✓ 按钮不检查 loading 状态）。

**路径 B — 通知栏 Action（守卫在事务外，形同虚设）**

```kotlin
// DoseActionReceiver.kt:39-50
val slot = db.doseSlotDao().getSlotById(slotId)          // ← 事务 ①
val isStillOpen = slot != null && (slot.status == PENDING || slot.status == SNOOZED)
when (action) {
    Notifications.ACTION_TAKE -> {
        val ok = isStillOpen && tracking.takeDose(slotId = slotId, ...)   // ← 事务 ②
```

守卫读（事务 ①）与写（事务 ②）是**两个独立事务**，中间存在竞态窗口。连点两次通知按钮，两个 `onReceive` 都可能在对方写入前读到 `PENDING`。**守卫必须下沉进 `takeDose` 的 `withTransaction` 内部才有意义** —— 这是"边缘守卫 + 核心无守卫"的典型反模式。

**复合后果：撤销后库存永久丢失**

`undoDose`（`DoseTrackingService.kt:118-145`）依赖 `recordDao.getRecordBySlotId(slotId)`（`LIMIT 1`）只取**第一条**记录来冲正：

```kotlin
val previousRecord = recordDao.getRecordBySlotId(slotId) ?: return@withTransaction false   // LIMIT 1
if (previousRecord.status == COMPLETED && medication.isStockTracked && previousRecord.doseTaken > 0f) {
    ... medDao.updateStock(slot.medicationId, restoredStock)     // 只加回一份
}
recordDao.deleteBySlotId(slotId)     // 但把所有记录都删了
```

若重复打卡 2 次（扣了 2 片），撤销时只冲正 1 片，但 `deleteBySlotId` 把 2 条事实全删了 → **账面少了 1 片，且再也无法追溯**。这是不可逆的数据损失。

**修改建议**

1. **在 `withTransaction` 内部第一行加守卫**：
   ```kotlin
   val slot = slotDao.getSlotById(slotId) ?: return@withTransaction false
   if (slot.status == SlotStatus.COMPLETED || slot.status == SlotStatus.SKIPPED) {
       return@withTransaction false      // 幂等锚点：已产生结论的槽位不可重复打卡
   }
   ```
   同时把 `DoseSlotDao.updateStatus` 改成条件更新 `WHERE id = :id AND status IN ('PENDING','SNOOZED')` 并返回受影响行数，作为第二道防线。
2. `skipDose` 同样缺守卫（`:82-100` 只判存在），一并补上。
3. `DoseActionReceiver` 的 `isStillOpen` 守卫可以保留（少一次无谓事务），但**权威守卫必须在 `takeDose` 里**。
4. 补测试：`takeDose` 同一 slotId 连调 3 次，断言只产生 1 条 `dose_records`、库存只扣 1 次、返回值第 2/3 次为 false。

---

### P0-5 ★★★ 编辑「药品信息」会静默清空「重要提醒 / 推迟时长 / 提前提醒」

**位置**：`ui/screen/edit/AddEditMedicationViewModel.kt:309-325` → `core/domain/service/MedicationAdminService.kt:89-107` → `core/data/dao/MedicationDao.kt:38-78`

**代码**

```kotlin
// AddEditMedicationViewModel.kt:309-325
val medId = adminService.saveProfile(
    MedicationAdminService.ProfileDraft(
        medId = s.medId ?: 0L,
        name = s.name, alias = s.alias, category = s.category,
        form = s.form, unit = s.unit, colorHex = s.colorHex,
        defaultDose = s.defaultDose.toFloatOrNull() ?: 1.0f,
        description = s.description, precautions = s.precautions,
        noticeShort = s.noticeShort, expiryDate = s.expiryDate,
        minStockAlert = alertFloat
        // ← isCriticalReminder / snoozeMinutes / advanceMinutes 三项完全没传
    )
)
```

`ProfileDraft` 这三项的默认值是 `false / 0 / 0`（`MedicationAdminService.kt:46-48`），而 `saveProfile` 的编辑分支**无条件把它们写进 UPDATE 的 SET 列表**（`:102-104`），`MedicationDao.updateProfile` 的 SQL 里也确实有这三列（`:52-54`）。

而 `AddEditUiState` **没有这三个字段**，`AddEditMode.INFO_ONLY` 的表单里**也没有任何对应控件**（`isCriticalReminder` / `snoozeMinutes` / `advanceMinutes` 在 `AddEditMedicationViewModel` 与 `AddEditMedicationScreen` 全文零出现，只在 `ReminderSettings` 与 `Inventory` 侧存在）。**用户没有任何途径把它们设回来。**

**复现路径**

```
药品详情 → 提醒设置 → 打开「重要提醒」+「推迟 15 分」+「提前 10 分」→ 保存
       → 返回详情页（摘要正确显示"重要提醒 / 推迟 15 分 / 提前 10 分"）
       → 点「药品信息」→ 只改了个药名 → 保存
       → 三项全部归零，详情页摘要里的这些字样一起消失
```

**为什么这是 P0 而不是 P1**

1. **这是 App 内最高频的操作**（改药名、改剂量、改单位），触发门槛几乎为零。
2. `isCriticalReminder` 归零的医学后果最重：`ReminderSettings.shouldSilence` 会让**标记为重要提醒的药重新被夜间静音**（`ReminderSettings.kt:55-56`）—— 胰岛素、抗凝药这类药正是这个开关的设计目标。
3. **它精确复刻了上一轮已修的 P0-3 的同一类 bug。** 上一轮把 `insert(REPLACE)` 换成了局部 `UPDATE`，`AGENTS.md` §2 也补了红线说明"改表单时先确认字段是否齐全"。但这次是**局部 UPDATE 的 SET 列表里依然包含了表单没暴露的列** —— 从"整行覆盖"变成"部分列覆盖"，**问题等价，隐蔽性反而更高**（因为 `updateProfile` 看起来像是安全的）。
4. `MedicationAdminServiceTest.saveProfile_editKeepsUnrelatedFields`（`:116-122`）断言了 `isPaused / isArchived / isStockTracked / currentStock / createdAt` 保留 —— **唯独漏了这三项**，所以测试也没拦住。

**修改建议（推荐第 2 个，从结构上根除）**

1. 快速修：`AddEditUiState` 加三字段 + `loadExistingMedication` 回填 + `save()` 显式传值。
2. **结构修（推荐）**：把 `isCriticalReminder / snoozeMinutes / advanceMinutes` **从 `ProfileDraft` 中删除**，编辑分支改用一个不含这三列的 `MedicationDao.updateProfileFields(...)`。这三个字段的**唯一合法写入口**是 `ReminderSettingsViewModel`（它已经在用 `updateReminderBehavior`，语义正确）。这样"药品信息页不该碰提醒行为"就由类型系统保证，而不是靠调用方记得传值。
3. 补测试：把现有那条 `saveProfile_editKeepsUnrelatedFields` 的断言集合补上这三项。

---

### P0-6 ★★★ 「系统特权自检」页 4 项全部是硬编码，2 个「去设置」按钮是空实现

**位置**：`ui/screen/settings/PermissionCheckScreen.kt:66-101`、`:141-147`；上游文案 `ui/screen/settings/SettingsScreen.kt:246`；真实检测逻辑存在但在 `core/alarm/AlarmScheduler.kt:49-50`

**代码**

```kotlin
// PermissionCheckScreen.kt:66-101（四张卡片）
PermissionItemCard(
    title = "1. 精确闹钟权限 (Exact Alarm)",
    desc = "必须权限。允许应用在设定的准点精确唤醒 CPU 发出用药提醒，避免被系统延迟对齐。",
    icon = Icons.Default.Alarm,
    statusText = "已授权",     // ← 写死
    isGranted = true           // ← 写死
)
PermissionItemCard(title = "2. 发送通知权限 (Notification)", ..., statusText = "已授权", isGranted = true)  // ← 写死
PermissionItemCard(title = "3. 忽略电池优化 (Doze 白名单)", ..., statusText = "去设置", isGranted = false)   // ← 写死 + 无跳转
PermissionItemCard(title = "4. 锁屏显示与后台自启动",     ..., statusText = "查看指引", isGranted = false)   // ← 写死 + 无跳转

// PermissionCheckScreen.kt:141-147
OutlinedButton(
    onClick = {},              // ← 空实现，点下去毫无反应
    shape = RoundedCornerShape(8.dp),
    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
) { Text(statusText, fontSize = 12.sp, maxLines = 1) }
```

**问题不在"少了检测"，而在"检测结果与真实状态相反"**

真实的检测函数就在同一个工程里，只是没接到 UI 上：

```kotlin
// AlarmScheduler.kt:49-50
val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
```

而 `canScheduleExactAlarms` / `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` 在 `ui/` 目录下 **grep 0 命中**。

**结合 targetSdk 35 的实际行为，问题比"没检测"严重**

- `app/build.gradle.kts:25` → `targetSdk = 35`；`AndroidManifest.xml:6-7` 只声明了 `SCHEDULE_EXACT_ALARM`，**没有** `USE_EXACT_ALARM`。
- `SCHEDULE_EXACT_ALARM` 在 targetSdk ≥ 33 时**不再自动授予**，且 Android 13+ **新安装时系统默认撤销**。也就是说**首次安装后这一项大概率就是未授权的**。
- 未授权时 `AlarmScheduler` 会走到第二档 `setAlarmClock` —— 但 `setAlarmClock` 在 Android 12+ **同样需要 `SCHEDULE_EXACT_ALARM`**，会抛 `SecurityException` 被 `catch`（`:64`）吞掉，最终落到第三档 `setAndAllowWhileIdle`（`:68`），**Doze 下误差可达 15 分钟以上**。
- 而这个页面恰恰在告诉用户"已授权 … 避免被系统延迟对齐"。**结论与事实相反，比没有这个页面更危险。**
- 同样地，`POST_NOTIFICATIONS` 未授予时 `NotificationManagerCompat.notify` 是 no-op（不抛异常，`runCatching` 也捕不到），用户会看到"药吃了但 App 一点动静都没有"，而这里写着"已授权"。

`README.md:105` 承诺「系统特权自检页：精确闹钟、通知权限、电池优化白名单、锁屏与自启动**逐项检测**」—— **0 项做了检测**。

**修改建议**

1. 新建 `PermissionCheckViewModel`（`AndroidViewModel`），实现四项真实检测：
   - 精确闹钟：`if (SDK_INT >= S) alarmManager.canScheduleExactAlarms() else true`
   - 通知权限：`ContextCompat.checkSelfPermission(POST_NOTIFICATIONS)`
   - 通知渠道：`NotificationManagerCompat.getNotificationChannel(CHANNEL_DOSE_REMINDER)?.importance`
   - 电池白名单：`PowerManager.isIgnoringBatteryOptimizations(packageName)`
2. 按钮 `onClick` 接真实跳转：未授予时用 `Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$pkg"))` 与 `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`。
3. 页面在 `RESUMED` 时重新检测（用户跳去系统设置授权后返回要能看到变化）。
4. **在补齐前**，把 `SettingsScreen.kt:246` 的"查看 **4 项**系统特权自检"改成"查看系统特权与保活指引"，不要谎报检测能力。
5. 顺带修 `MainActivity.kt:29-30` 的权限申请：Android 13+ 连续两次拒绝后系统不再弹窗，当前 `launch()` 静默无效，需要 `shouldShowRequestPermissionRationale` + 引导去系统设置的分支。

---

### P0-7 ★★★ 统计页把不同单位的剂量直接相加，并借用榜首药品的单位

**位置**：`ui/screen/stats/StatsViewModel.kt:111-131`；渲染 `ui/screen/stats/StatsScreen.kt:120`

**代码**

```kotlin
// StatsViewModel.kt:111-131
val doseSums = recordDao.getDoseSumByMedicationInRange(startTs, endTs)   // 按药品分组，跨单位
val medById = medications.associateBy { it.id }
val totalDose = doseSums.sumOf { it.totalDose.toDouble() }.toFloat()     // ← 不分组直接相加
...
val dominantUnit = rankings.firstOrNull()?.unit ?: "片"                // ← 借用排名第 1 的药的单位
return StatsUiState(..., totalDoses = totalDose, totalDoseUnit = dominantUnit, ...)
```

```kotlin
// StatsScreen.kt:120  —— 36sp，全 App 最醒目的数字
text = "${fmt(uiState.totalDoses)}${unitSuffix(uiState.totalDoseUnit)}"
```

**具体算例**

一个同时吃羟氯喹（片剂）和口服液（ml）的用户，过去 7 天羟氯喹 30 片、口服液 5 ml：

| 量 | 值 |
| --- | ---: |
| `doseSums` | `[{medA, 30.0}, {medB, 5.0}]` |
| `totalDose` | **35.0** |
| `rankings.first().unit` | 假设榜首是片剂 → `"片"` |
| **Hero 区显示** | **「35 片」** |

**真实消耗是 30 片 + 5 ml。显示成"35 片"是直接的错误剂量信息**，而且用 36sp 放在页面最显眼处。这属于会影响健康决策的信息错误，不是排版瑕疵。

`unitSuffix`（`StatsScreen.kt:385-386`）只处理"西文单位前留空格"，无法修正单位本身。

**顺带发现的第二重问题**：`rankings` 用 `mapIndexedNotNull`（`:115-124`），归档药品在 `medById` 找不到会被 `return@mapIndexedNotNull null` 丢掉，**但 `index` 已经被占用**。若排名第 1 的药已归档，UI 会渲染「**2.** 维生素D 30 片」为第一条，**永远没有第 1 名**；第 3 名被归档则出现 1、2、4、5。

**修改建议**

1. `StatsUiState` 增加 `totalDosesByUnit: Map<String, Float>`；`totalDoseUnit` 改为仅在 `byUnit.size == 1` 时非空。
2. `StatsScreen` Hero 区：单单位时显示现有大数字；多单位时改为分行/分列展示（如「30 片」「5 ml」），或退化为"共 N 种药品"而**不给总量**。**宁可不给数字，也不能给错数字。**
3. `rankings` 改为 `doseSums.filter { it.medicationId in medById }.mapIndexed { index, row -> ... rank = index + 1 }`。

---

## 二、P1 重要问题（20 项）

### P1-1 撤销会**物理删除**服药事实，直接违反产品第二承诺

**位置**：`core/domain/service/DoseTrackingService.kt:118-145`

```kotlin
// 138-139
// 删除该 slot 对应的打卡记录
recordDao.deleteBySlotId(slotId)     // ← DELETE，不是追加
```

`docs/FINAL-PRODUCT.md:106-108` **场景 2** 明确规定：

> 撤销 = 槽位 TAKEN→PENDING（槽位是缓存，可回退）+ **事实层追加 REVERT 修正** + 库存对冲流水，**全程留痕**

`README.md:11` 的产品第二承诺是「**记录真实** —— 吃过的药永不丢失、永不串改」。

实现里有一个很能说明问题的**不对称**：库存侧老老实实追加了一条 `REVERT_ROLLBACK` 流水（append-only、可追溯），而服药事实侧却直接 `DELETE`。同一个"撤销"动作，两边的哲学是相反的。

**连带的数据完整性问题**：冲正流水写的是 `recordId = previousRecord.id`（`:128`），紧接着这条 `dose_records` 记录就被删了 → **`inventory_transactions.record_id` 变成悬空引用**。`DoseRecordEntity` 的 `slot_id` 没有到 `dose_slots` 的外键（`DoseRecordEntity.kt` 只声明了到 `medications` 的外键），所以数据库不会报错，但：
- `DataExporter` 导出的 JSON 里会出现指向不存在记录的 `recordId`；
- 备份 → 恢复往返后这个悬空引用会一直传下去。

**修改建议**：`RecordStatus` 增加 `REVERTED`；撤销时把原事实 `status` 改为 `REVERTED`（追加修改而非删除），保留 `dose_taken` 原值供审计。库存冲正流水不变。同时 `undoDose` 应改为处理**全部**关联事实（配合 P0-4 的幂等修复后通常只有一条）。

### P1-2 `SNOOZED` 槽位永不结算为 `EXPIRED`，依从率被系统性高估

**位置**：`core/data/dao/DoseSlotDao.kt:130-131`、`core/alarm/AlarmReconciler.kt:30-34`、`core/domain/engine/StatsEngine.kt:198-199`

```kotlin
// DoseSlotDao.kt:130-131
@Query("SELECT * FROM dose_slots WHERE status = 'PENDING' AND scheduled_ts < :cutoffTs")
suspend fun getStalePendingSlots(cutoffTs: Long): List<DoseSlotEntity>   // ← SNOOZED 被漏掉

// DoseSlotDao.kt:127-128
@Query("SELECT * FROM dose_slots WHERE status IN ('PENDING', 'SNOOZED') ...")
suspend fun getOpenSlots(): List<DoseSlotEntity>                        // ← 但这里又查得到
```

**可复现的静默数据错误**

1. 用户推迟 30 分钟后关机，或 App 未被打开超过 2 小时。
2. 槽位 `status = SNOOZED` → `getStalePendingSlots` 查不到它 → **永不变 `EXPIRED`**。
3. 之后每次 `rescheduleAll`：`getOpenSlots()` 仍返回它，但 `triggerAt = snoozeUntilTs <= now` → 走 `AlarmReconciler.kt:77-81` 的 `cancel` 分支，闹钟被撤。
4. 结果：**这一次服药从统计中彻底消失**。`StatsEngine.kt:198-199` 把 `SNOOZED` 归入 `pending`，而 `pending` **不进依从率分母**（`:114-116`）→ 用户依从率被高估，且进展页那格永远显示"已推迟"。

**同时是一个未实现的已拍板需求**

`docs/FINAL-PRODUCT.md:123-125` **场景 6**：

> 推迟状态只存在于槽位（`snooze_until`/**`snooze_count`**）… **上限 3 次**，超过自动转 EXPIRED 并在今日页汇总提示"该药今天已错过"

`docs/FINAL-ARCHITECTURE.md:136` 也列了 `snooze_count | Int | 已推迟次数（上限 3 次）`。

**核实结果：`snooze_count` 这个字段在 `app/src` 下完全不存在**（只在 3 份文档里出现）。`DoseSlotEntity` 只有 `snoozeUntilTs`（`DoseSlotEntity.kt:59-63`），既无次数计数，也无 3 次上限。

**修改建议**

```kotlin
@Query("""SELECT * FROM dose_slots
          WHERE status IN ('PENDING','SNOOZED')
            AND COALESCE(snooze_until_ts, scheduled_ts) < :cutoffTs""")
suspend fun getStaleOpenSlots(cutoffTs: Long): List<DoseSlotEntity>
```
并在 `AlarmReconciler` 里对 `SNOOZED` 分支用 `snoozeUntilTs` 而非 `scheduledTs` 判超时。若要实现 3 次上限，需按 AGENTS.md §2 加 `snooze_count` 列 + 升 version + 写 `MIGRATION` + 补 `MigrationTest`。

### P1-3 疗程结束日一旦设定就**永远无法清除**

**位置**：`core/domain/service/MedicationAdminService.kt:154`；调用方 `ui/screen/reminder/ReminderSettingsViewModel.kt:214`

```kotlin
// MedicationAdminService.kt:154
endDate = draft.endDate?.ifBlank { null } ?: previous?.endDate,
```

`null` 在这里被当成了"不修改，沿用历史"，而不是"用户主动清空"。而 UI 关闭「设置结束日期」开关时传的就是 `null`：

```kotlin
// ReminderSettingsViewModel.kt:214
endDate = if (s.hasEndDate) s.endDate else null,      // ← 关闭开关时传 null
```

**复现**：给一个抗生素疗程药设了结束日 → 保存 → 疗程结束后想改成"长期服用" → 关掉「设置结束日期」开关 → 保存 → **结束日纹丝不动**，排班仍在该日期后停止，且详情页仍显示旧疗程。用户会以为 App 坏了。

**对比**：`startDate` 的处理是对的（`:153` `draft.startDate.ifBlank { previous?.startDate ?: now }`，因为 `startDate` 是非空 `String`，空串有明确语义）；`endDate` 是可空 `String?`，`null` 的语义没有被定义清楚。

**修改建议**：引入显式三态，例如 `PolicyDraft.endDate: EndDateSpec`（`Keep` / `Clear` / `Set(value)`），或最小改动 —— 在 `saveReminderPolicy` 增加一个 `clearEndDate: Boolean` 参数，由 UI 在开关关闭时置 `true`。同时补一条单测锁死"关闭开关后 `end_date` 变为 NULL"。

### P1-4 事后补录的服药**不计入任何消耗统计**，但库存照扣 —— 账实长期不符

**位置**：`ui/screen/manual/ManualDoseViewModel.kt:146`、`core/domain/service/DoseTrackingService.kt:171`、三个消费查询

```kotlin
// ManualDoseViewModel.kt:146
isRetrospective = s.actualDateTime.isBefore(LocalDateTime.now().minusMinutes(2)),
```

```kotlin
// DoseTrackingService.kt:171
status = if (isRetrospective) RecordStatus.RETROSPECTIVE else RecordStatus.COMPLETED,
```

而**所有**消耗聚合都只认 `COMPLETED`：

| 位置 | 条件 |
| --- | --- |
| `DoseRecordDao.kt:45` `getSumDoseTakenForMedication` | `status = 'COMPLETED'` |
| `DoseRecordDao.kt:62 / 76` `getDoseSumByMedicationInRange` | `status = 'COMPLETED'` |
| `StatsEngine.kt:103` `sumDoseByDate` | `status == RecordStatus.COMPLETED` |

**触发条件不是边缘场景，而是这个页面的主用途**：`ManualDoseScreen` 的标题就是"手动补录服药"，其存在的意义就是补录过去的时间。只要补录时刻距今超过 2 分钟（几乎所有真实补录），记录的 `status` 就是 `RETROSPECTIVE` → **完全不进统计**。

**三重后果**

1. **统计页累计用量 / 消耗排行榜**漏掉所有补录。
2. **药品详情页"近 30 天共消耗"**同样漏掉（`MedicationDetailViewModel.kt:83` 调的就是 `getSumDoseTakenForMedication`）。
3. **最严重：库存流水里确实扣了**（`DoseTrackingService.kt:177-189` 的 `deductStock` 分支不看 `isRetrospective`）→ **账面减少了，但"消耗统计"没有增加**。用户核对库存与消耗排行会发现两个数字永远对不上，且无从判断谁对。

**与已拍板决策的冲突**：`docs/FINAL-PRODUCT.md:120-121` **场景 5** 明确写「直接记一条无槽位事实 + 扣库存流水；**计入消耗量统计**，不进依从率分母」。实现做到了"扣库存"和"不进依从率分母"，**没做到"计入消耗量统计"**。

**修改建议**

1. 统计口径统一改为 `status IN ('COMPLETED','RETROSPECTIVE')`，涉及 `DoseRecordDao` 的 3 个查询 + `StatsEngine.sumDoseByDate`。
2. 同步更新 `StatsDaoAggregationTest` 与 `README.md:93-95` 的口径说明（README 目前只区分了"依从率按计划时间 / 消耗量按实际时刻"，没提 `RETROSPECTIVE`）。
3. 补测试：`logManualDose(isRetrospective = true)` 后，断言消耗聚合包含该条。

### P1-5 每次回前台都全量删除并重建未来槽位 → 闹钟泄漏 + 数据库 churn，并放大 P0-1

**位置**：`MainActivity.kt:40-48` → `AlarmReconciler.kt:43-49` → `DoseTrackingService.kt:302-326`

**这是本次审查独立发现的问题（三个子审查均未覆盖）**，机制如下：

```kotlin
// DoseTrackingService.kt:295-326
suspend fun reconcileSchedule(medicationId, fromDate, toDate, zoneId) = db.withTransaction {
    ...
    // 第 1 步：删掉今天起的所有未来 PENDING 槽位
    slotDao.deleteFuturePendingSlots(medicationId, fromEpochMilli)

    // 第 2 步：按最新策略重新投影
    val projectedSlots = SlotProjectionEngine.projectSlots(...)

    // 第 3 步：去重 —— 但此时前一步已经把槽位删光了，existingSlots 是空的
    val existingSlots = slotDao.getSlotsInRange(...).filter { it.medicationId == medicationId }
    val existingSlotKeys = existingSlots.map { "${it.scheduledDate}_${it.scheduledTime}" }.toSet()
    val slotsToInsert = projectedSlots.filter { "${it.scheduledDate}_${it.scheduledTime}" !in existingSlotKeys }
    slotDao.insertAll(slotsToInsert)
}
```

**关键点：`deleteFuturePendingSlots` 的 SQL 是 `DELETE ... WHERE medication_id = :id AND status = 'PENDING' AND scheduled_ts >= :fromTs`。未来 PENDING 槽位全部被删，随后的 `insertAll` 生成的是全新的自增主键。**

**而 `rescheduleAll` 挂在 `repeatOnLifecycle(Lifecycle.State.RESUMED)` 上（`MainActivity.kt:41`）—— 每次从其他 App 切回来都会跑一遍。**

**后果 1：闹钟泄漏（无任何补偿机制）**

`AlarmReconciler.kt:54` 的 `getOpenSlots()` 拿到的是**新 id**，`:74` 用新 id 注册闹钟。**旧 id 注册的闹钟从未被取消** —— 因为第 3 步的 cancel 分支（`:78`）只遍历当前存在的 open slots，已经不存在的旧 id 不在列表里。

泄漏的闹钟到点会唤醒进程 → `AlarmReceiver.kt:30` `getSlotById(旧id)` 返回 `null` → `:33` 的守卫 `null != PENDING && null != SNOOZED` 成立 → 静默返回。**用户体验上完全无感知，纯粹是电量和唤醒次数的浪费**，且随使用天数线性累积。

量级估算：3 药 × 3 次/天 × 8 天 = 72 个槽位，用户每天切回 App 20 次 → **每天泄漏约 1440 个 pending alarm**。Android 对单应用的 pending alarm 有数量上限（`AlarmManagerService` 的 `MAX_ALARMS_PER_APP`），超限后新注册的闹钟会被丢弃 —— **这会把"泄漏"升级为"真实闹钟被挤掉"，即 P0-2 之外的一条独立的漏提醒路径**。（具体阈值需真机 `dumpsys` 验证，见 §6 疑似项。）

**后果 2：数据库 churn + 无谓重组**

每次回前台：每个药一次 `withTransaction` 的 DELETE+INSERT（约 24~72 行）+ 全部 Room Flow 失效 → 今日页/药箱/统计全部重新查询。药多时（15 药 × 3 次/天 × 8 天 = 360 行）每次切 Tab 都有可感知开销。

**后果 3：放大 P0-1**

槽位 id 持续快速增长，意味着**必然不断产生新的 ≡1 (mod 10) 的 id**，P0-1 的碰撞从"可能"变成"持续发生"。

**后果 4：违背 `DoseSlotEntity` 自己的 KDoc**

```kotlin
// DoseSlotEntity.kt:12-13
 * 1. 主键 id 直接作为 AlarmManager 的 requestCode，根除哈希算法碰撞隐患
```

id 不稳定 → requestCode 不稳定 → PendingIntent 无法复用。**"id 作为 requestCode"这个设计的前提（id 稳定）当前不成立。**

**修改建议**

1. **根本修法**：让 `reconcileSchedule` 变成幂等的 diff，而不是"全删重建"。做法：先把投影结果与现有槽位按 `(scheduledDate, scheduledTime)` 求差集，**只 INSERT 新增的、只 DELETE 真正消失的**（且仅限 PENDING），保留未变槽位的 id。
2. `AlarmReconciler` 增加**孤儿闹钟清理**：记录上次对账时的 slot id 集合（存 `app_settings`），本次对账时把不在当前集合中的旧 id 全部 `cancel`。这样即使未来还有其他重建路径也不会泄漏。
3. 节流：`rescheduleAll` 加"距上次对账 < 5 分钟且无写操作则跳过"的判断。
4. 修正 `DoseSlotEntity` KDoc，或在 id 稳定后保留原表述。

### P1-6 已拍板的 D-7「整数毫单位记账」完全未执行，全链路使用 `Float`

**位置**：`docs/FINAL-PRODUCT.md:38`（决策）vs 全部 entity

> **D-7 库存记账** | **整数毫单位记账**（如 1.5 片 = 1500），**全程无浮点** | 杜绝账本对账漂移；UI 层做单位换算展示

实现（`MedicationEntity.kt:38/50/53`、`InventoryTransactionEntity.kt:43`、`PolicyTimeEntity.kt:38`）全部是 `Float`：

```kotlin
val defaultDose: Float = 1.0f
val currentStock: Float = 0f
val minStockAlert: Float = 0f
val changeAmount: Float
val doseAmount: Float = 1.0f
```

**为什么这条决策是对的、且现在被违反的代价很实**

`Float` 只有 24 位有效尾数（约 7 位十进制精度）。项目却把 `SUM(change_amount) == current_stock` 声明为**绝对不变式**，并在 `InventoryTransactionDao.kt:32-37` 为此专门提供了一个查询。**浮点加法不满足结合律**，长期累加必然漂移：

- 0.1f + 0.2f ≠ 0.3f（`0.1f + 0.2f = 0.30000001192092896f`）
- 一年的服药扣减（365 × 4 = 1460 次浮点累加）足以让账面与流水和产生肉眼可见的偏差
- 而 D-9（允许负库存）落地后，账实不符的诊断价值完全依赖这个不变式的可靠性

同时，`DoseTrackingService.kt:255-256` 的 `trimFloat` 用 `v % 1f == 0f` 判断整数，**本身就是浮点陷阱**（`0.1f * 10` 不精确等于 `1.0f`）。

**修改建议（这是需要排期的迁移改造，不适合顺手改）**

1. 5 个数值列改为 `Long`（毫单位）。按 AGENTS.md §2：`AppDatabase` 升 version → `MIGRATION_2_3` 里 `UPDATE ... SET col = CAST(ROUND(col * 1000) AS INTEGER)` → 补 `MigrationTest` → 领域层与 UI 层加换算层（`Long` ↔ 显示 `Float`）。
2. 若评估迁移风险过高，**至少修正文档**：把 D-7 改为"浮点记账 + 季度盘点校准兜底"，并把 `trimFloat` 换成 `abs(v - round(v)) < 1e-3f`。
3. 无论哪种，把 `SUM(change_amount) == current_stock` 提炼成一条**覆盖全部 6 个库存写入口的通用不变量测试**（当前只有零散的 happy path）。

### P1-7 「昨天此时」快捷键实际写入的是昨天 00:00

**位置**：`ui/screen/manual/ManualDoseViewModel.kt:116` vs `ui/screen/manual/ManualDoseScreen.kt:324`

```kotlin
// ManualDoseViewModel.kt:111-119
fun quickFill(kind: QuickFill) {
    val now = LocalDateTime.now()
    val dt = when (kind) {
        QuickFill.NOW -> now
        QuickFill.ONE_HOUR_AGO -> now.minusHours(1)
        QuickFill.YESTERDAY -> now.minusDays(1).withMinute(0)   // ← 归零到 00:00
    }
    ...
}
```

```kotlin
// ManualDoseScreen.kt:323-324
onClick = { viewModel.quickFill(QuickFill.YESTERDAY) },
label = { Text("昨天此时", fontSize = 12.sp) }        // ← 文案说的是"昨天此时刻"
```

**影响**：用户想补录"昨晚 22:00 吃了药"，点了「昨天此时」，实际记录的是**昨天 00:00** —— 服药时刻错了 22 小时。README.md:72 也写的是「昨天此时 快捷键」，与实现不符。

对缓释制剂、每日一次的慢病用药，服药时刻是有临床意义的；而且这个错误**会被 P1-4 的机制放大**（>2 分钟 → `RETROSPECTIVE` → 不进统计）。

**修改建议**：`QuickFill.YESTERDAY -> now.minusDays(1)`（保留时分）。一行改动。

### P1-8 提醒设置页「推迟 30 分钟」会被存成 0，且 UI 无法回到「跟随全局」

**位置**：`ui/screen/reminder/ReminderSettingsViewModel.kt:116`、`:225`、`ui/screen/reminder/ReminderSettingsScreen.kt:443-446`

```kotlin
// :116  加载时把库里的 0 显示成 30
snoozeMinutes = med.snoozeMinutes.coerceIn(0, 120).let { if (it == 0) 30 else it },

// :225  保存时把 30 翻译回 0
snoozeMinutes = if (s.snoozeMinutes == 30) 0 else s.snoozeMinutes,
```

而 Stepper 的下限卡在 5（`canDec = uiState.snoozeMinutes > 5`），**用户永远无法把该药恢复成"跟随全局"（0）**。

**两个具体问题**

1. 用户在提醒设置页选 30 分钟 → 存成 0 → 实际生效的是**全局**值。若全局被改成 10，该药就变成推迟 10 分钟，**与用户刚做的选择相反**。
2. `MedicationDetailScreen.kt:598` 的 `buildReminderSummary` 只在 `snoozeMinutes > 0` 时显示"推迟 N 分"，所以设了 30 的药在详情页摘要里**看不到任何推迟信息** —— 用户无法察觉异常。

这是"用魔法数字（30）兼任业务默认值和哨兵值"的典型设计缺陷。

**修改建议**

1. 删掉 `:225` 的 `if (... == 30) 0`。
2. 引入显式三态 UI：`null = 跟随全局`，用 Stepper 旁的"默认"开关承载，写库时 `null -> 0`；`:116` 相应改为 `if (it == 0) null else it`。
3. 补测试：30 能被持久化；关闭"单独设置"后 `snooze_minutes` 归 0。

### P1-9 库存页把「时点数量」当成「间隔天数」渲染，同屏自相矛盾

**位置**：`ui/screen/inventory/InventoryViewModel.kt:235-245`、调用点 `:103`

```kotlin
private fun describe(type: PolicyType?, times: List<PolicyTimeEntity>): String {
    if (type == null || times.isEmpty()) return "暂无排班"
    val n = times.size                                   // ← 时点个数
    return when (type) {
        PolicyType.DAILY -> "每天 $n 次 (${times.joinToString { it.timeOfDay }})"
        PolicyType.INTERVAL -> "每隔 $n 天 $n 次"          // ← 用时点数当间隔天数
        PolicyType.DAYS_OF_WEEK -> "每周 ${times.size} 天各 $n 次"
        PolicyType.CYCLE -> "周期用药 $n 次/服药日"
        PolicyType.PRN -> "按需服用"
    }
}
// 调用点同样漏传：
frequencyDescription = describe(policy?.policyType, times),     // :103
```

**现象**：一个"隔天一次、每天 1 个时点"的药，库存页显示 **「每隔 1 天 1 次」** —— 而 `intervalDays = 2` 的语义是隔 2 天（隔天）。

**同屏矛盾**：`InventoryViewModel.scheduledDosesPerWeek`（`:226-229`）**用的是真正的 `intervalDays`**，所以同一屏上"预计可用天数"是对的、"频次文案"是错的，两者互相打架。

**同类问题还有两处**（同一 bug 家族，都是"两处各写一遍"的必然结果）：

| 位置 | 问题 |
| --- | --- |
| `CabinetViewModel.kt:123` | `if (policy.intervalDays <= 2) "隔天"` —— `intervalDays == 1` 时显示"隔天"，但引擎（`SlotProjectionEngine.kt:114`）在 `intervalDays <= 0` 才纠正为 1，**`== 1` 时 `daysDiff % 1 == 0` 恒成立 = 每天排班**。文案说隔天、行为是每天。 |
| `CabinetViewModel.kt:131` | `"周期 ${cycleOnDays}天服/${cycleOffDays}天停"` —— `cycleOnDays = 0`（`DataExporter.kt:326` 恢复时的默认值）时显示"周期 0天服/0天停"，而引擎（`:126-128`）静默纠正为 `takeDays=1` → **实际每天服药**。用户会以为"这天不用吃"而漏服。 |

**修改建议**

1. `describe` 签名改为 `describe(policy: SchedulePolicyEntity?, times: List<PolicyTimeEntity>)`，INTERVAL 分支用 `policy?.intervalDays`。
2. **把这段文案生成抽到 `core/domain` 的纯函数**（如 `SchedulePolicyFormatter.describe(policy, times)`），让药箱、库存、详情三处共用同一个实现。这是根治"两处各写一遍必然漂移"的唯一办法。
3. 补单测：`intervalDays = 1 / 2 / 3`、`cycleOnDays = 0` 四个边界。

### P1-10 剂量输入可存 0 与负数；负剂量会**增加**库存

**位置**：`ui/screen/edit/AddEditMedicationScreen.kt:762-764`、`ui/screen/reminder/ReminderSettingsScreen.kt:393`、落库 `MedicationAdminService.kt:159-169`、后果 `DoseTrackingService.kt:62`

```kotlin
onValueChange = { viewModel.updateTimeSlot(index, dose = it.toFloatOrNull() ?: 0f) },   // 空串 → 0
```

`PolicyTimeEntity` 与 `saveReminderPolicy` 都不校验。后果：

- **剂量 = 0**：排班照常生成、照常提醒，但每次打卡 `changeAmount = -0.0f`，不扣库存。用户会以为"没扣库存是 bug"。**且无任何报错。**
- **剂量 = −1**：`newStock = currentStock - (-1) = currentStock + 1` → **每次"服药"给库存加 1**，账面虚增。

**修改建议**

1. 剂量输入框保留原始字符串草稿，`toFloatOrNull()` 失败时**保留上次有效值**（不要回落 0），`InventoryViewModel.minStockAlertInput` 已是这个写法，可对齐。
2. `MedicationAdminService.saveReminderPolicy` 增加 `require(times.all { it.dose > 0f })`。
3. `AddEditMedicationScreen.kt:231-238` 的「默认单次剂量」同样问题（`?: 1.0f` 静默兜底，输入 "abc" 变 1.0，无 `isError`）。
4. 错误提示用 `OutlinedTextField(isError = true, supportingText = ...)` 定位到具体字段。

### P1-11 提醒设置页缺日期先后校验，可静默把该药的全部提醒清零

**位置**：`ui/screen/reminder/ReminderSettingsViewModel.kt:191-200`、后果 `core/domain/engine/SlotProjectionEngine.kt:56-61`

`save()` 只校验了 `daysOfWeek` 非空和 `times` 非空，**完全没有日期先后关系校验**。`ReadOnlyDateField` 的 `DatePickerDialog` 也没有设 `datePicker.minDate/maxDate`，起始日可选 2000 年。

**后果**：误设 `开始日期 = 2027-01-01`、`结束日期 = 2026-01-01` → **保存成功、无任何报错** → `SlotProjectionEngine` 算出 `effectiveStart.isAfter(effectiveEnd)` → 返回 `emptyList()` → 该药**从此不再有任何提醒**，UI 一切正常。

对慢病用药这是严重后果。**修改建议**：`save()` 增加 `end < start` 与 `end < today` 两条校验并给出行内错误；`ReadOnlyDateField` 增加可选 `minDateMs/maxDateMs`。

### P1-12 `AddEditLogicTest` 8 项中 3~4 项是空测，"71 项全绿"含水分

**位置**：`app/src/test/kotlin/com/mcxiaoke/carromed/ui/screen/edit/AddEditLogicTest.kt:36-61`、`:84-96`

```kotlin
// :36-41  —— 测的是 Kotlin 标准库，不是本项目代码
fun intervalDays_clampsTo2To30() {
    assertThat(1.coerceIn(2, 30)).isEqualTo(2)
    assertThat(99.coerceIn(2, 30)).isEqualTo(30)
    assertThat(2.coerceIn(2, 30)).isEqualTo(2)
}

// :53-61  —— 在测试体内重新实现了一遍 toggle 逻辑，然后测这段本地实现
fun daysOfWeek_toggleAddsAndRemovesKeepingOrder() {
    var days = emptyList<Int>()
    days = if (1 in days) days - 1 else (days + 1).sorted()   // ← 与生产代码无关的复制品
    assertThat(days).containsExactly(1)
    ...
}

// :84-96  —— 名字声称验证"5 种频次都能从表单到达"，实际只验证了本地 list 有 5 个元素
fun fivePolicyTypes_areAllReachableFromForm() {
    val all = listOf(PolicyType.DAILY, ..., PolicyType.PRN)
    assertThat(all).hasSize(5)                                  // ← 与"可达"无关
    all.forEach { type -> assertThat(base().copy(policyType = type).policyType).isEqualTo(type) }
}
```

**逐条评估 8 个测试**：

| # | 测试 | 是否触达生产代码 |
| ---: | --- | :---: |
| 1 | `defaultState_startsWithOneDailySlot` | ✅ 真实（但很弱） |
| 2 | `title_distinguishesAddFromEditInfo` | ✅ 真实 |
| 3 | `intervalDays_clampsTo2To30` | ❌ **测 `Int.coerceIn`** |
| 4 | `cycleDays_clamps` | ❌ **测 `Int.coerceIn`** |
| 5 | `daysOfWeek_toggleAddsAndRemovesKeepingOrder` | ❌ **测测试体内的复制品** |
| 6 | `errorConstants_areDistinctAndNonBlank` | ⚠️ 真实但近乎同义反复 |
| 7 | `formOptions_coverRequiredDimensions` | ✅ 真实 |
| 8 | `fivePolicyTypes_areAllReachableFromForm` | ❌ **名不副实** |

**为什么这条是 P1 而不是 P3**

`README.md:172` 写「**71 项**，全绿」、`README.md:308` 写「71 项单元测试全绿」、`AGENTS.md` §3 把「71 项，全绿是提交前的硬门槛」写成流程红线。**这个数字已经被当作质量门禁对外承诺**，而其中 4 项不验证任何生产逻辑。测试数量在这里承担了它不该承担的质量信号。

**顺带说明测试覆盖的真实缺口**（这些是**零覆盖**的高风险不变量）：

- `requestCode` 号段不相交（→ P0-1）
- `takeDose` 幂等（→ P0-4）
- 库存不足时的守恒（→ P0-3）
- `AlarmScheduler.cancel` 与 `schedule` 的对称性
- 三档降级分支（`canScheduleExactAlarms = false` / 抛 `SecurityException` / 正常）
- `DataExporter` 导出→导入往返一致性
- 全部 8 个 ViewModel（**零单测**）—— `CabinetViewModel.frequencyDescription`（P1-9 的三个 bug 就在这里）是纯逻辑，本可被单测全部拦下

**修改建议**

1. 删掉 3、4、5、8（或改写成真正调用生产代码的形式）。
2. 把 P0-1 的 requestCode、P0-3 的守恒、P0-4 的幂等提炼成 3 条不变量测试补进去 —— 这三条的性价比远高于现有任何一条。
3. 把 `CabinetViewModel.frequencyDescription` 抽为顶层纯函数并补测（配合 P1-9 的抽取）。
4. 在 `AGENTS.md` §3 把「71 项全绿」改为「不变量测试全绿」，避免数量被当成质量。

---

### P1-13 统计页「导出」导出的是全量明细，与页面显示的周期口径不一致

**位置**：`ui/screen/stats/StatsViewModel.kt:151-166`

```kotlin
fun exportReport() {
    viewModelScope.launch {
        val file = DataExporter.exportDoseRecordsCsv(app, db)     // ← 无周期参数，全量
        Toast.makeText(app, "已导出 ${file.name}", Toast.LENGTH_LONG).show()
        DataExporter.shareFile(app, file, "text/csv")
    }
}
```

用户在统计页选「过去 7 天」，看到的 Hero 数字是 7 天口径，点导出拿到的却是**全库所有记录**。上一轮审查的 M34 已提出此项，**至今未改**。

**修改建议**：`exportDoseRecordsCsv` 增加 `startTs/endTs` 参数，导出前用当前周期过滤；文件名带上区间（`CarroMed_服药明细_20260921~20260927.csv`），让用户从文件名就能确认口径。

### P1-14 覆盖式恢复没有二次确认，选完文件即刻整库清空

**位置**：`ui/screen/settings/SettingsScreen.kt:66-70`、`:317-332`；执行 `core/data/DataExporter.kt:269-278`

```kotlin
// SettingsScreen.kt:66-70
val backupPickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument()
) { uri -> uri?.let { viewModel.importBackup(it) } }      // ← 选完即恢复
```

```kotlin
// DataExporter.kt:269-278
db.withTransaction {
    db.appSettingDao().deleteAllSettings()
    db.inventoryTransactionDao().deleteAllTransactions()
    db.doseRecordDao().deleteAllRecords()
    db.doseSlotDao().deleteAllSlots()
    ...
    db.medicationDao().deleteAllMedications()
    // 然后回填
}
```

页面有"整库快照替换还原，当前数据将被完全覆盖"的**文字**警告，但没有**确认弹窗**。用户在系统文件选择器点"打开"的那一刻，一年的服药历史就没了。

**这个操作的不可逆性远高于同页的"删除药品"**（后者有 `AlertDialog` 二次确认，见 `MedicationDetailScreen.kt:551-569`）。标准不一致。

**修改建议**：两段式 —— 选文件 → 弹 `AlertDialog` 显示"当前有 N 种药品 / M 条服药记录，将被完全覆盖且不可撤销"（两个 `COUNT(*)` 轻量查询即可）→ 确认后才调 `importBackup`。另建议按 `docs/FINAL-PRODUCT.md:158`「备份恢复（含导入前快照）< 10s」补一个**导入前自动本地快照**（D-13 已拍板但未实现）。

### P1-15 统计页与进展页的「整体依从率」分母口径不一致

**位置**：`ui/screen/stats/StatsViewModel.kt:104` vs `ui/screen/progress/ProgressViewModel.kt:91`

```kotlin
// StatsViewModel.kt:104
val slotRows = slotDao.getSlotStatusCounts(startDate, endDate)   // SQL 无 medication_id 条件
```

```kotlin
// DoseSlotDao.kt:111-125 —— 确认：WHERE 子句只有 scheduled_date BETWEEN
WHERE scheduled_date BETWEEN :startDate AND :endDate
GROUP BY medication_id, scheduled_date, status
```

```kotlin
// ProgressViewModel.kt:91
val matrixItems = medications.map { med -> ... }     // medications 来自 observeActiveMedications
```

→ **统计页的依从率分母包含已归档药品的历史槽位，进展页只统计在服药品。** 同一份数据，两个 Tab 给出两个不同的"整体依从率"。`StatsViewModel.kt:139-140` 的 `activeMedCount`（在服数）配 `scheduledDoseCount`（含归档药的计划数）也是两个不同源。

**修改建议**：统一为"只统计在服药品"（统计页也过滤），并把口径写进 `StatsEngine` 的 KDoc —— 当前 KDoc（`:113-117`）只定义了单药口径，**没有定义"跨药品汇总是否含归档"**，这正是分歧的源头。

### P1-16 「准时率」是 v1-P0 指标，实现中完全不存在

**位置**：`docs/FINAL-PRODUCT.md:42`（D-11）、`:77`（M-07）vs 代码

> **D-11 统计指标** | 主指标**完成率**，次指标**准时率**（窗口按药可配）
> **M-07 统计报表** | 完成率**+准时率**、各药消耗总量、月度趋势；区间 7 天/30 天/1 年/**自定义**

实现只有完成率。`StatsEngine` 全文无任何时间差判断逻辑。

**这个缺失还有一个衍生的文案问题**：`ProgressScreen.kt:165` 写「按时服药 $completed 次」、`StatsScreen.kt:150` 写「已按时服用」，而引擎口径是"完成了就算"，`COMPLETED` 里的 `actualTakenTs` 与 `scheduledTs` 的偏差完全不参与判定。**文案用了"按时"，口径却是"完成"** —— 这会让用户高估自己的用药规律性。

**同时存在三份文档三方分歧**：`docs/reviews/DESIGN_REVIEW.sbf.md:320` 提到方案定义的是「±60 分钟内视为准时」；`FINAL-PRODUCT` D-11 说「窗口按药可配」；`README.md:320` 把「±N 分钟准时率口径」列为 **P2 明确未做**。**一份 v1-P0 指标在 README 里被降级成 P2。**

**修改建议**（需产品决策，建议先做最小闭环）

1. 先把 UI 文案改成与口径一致的「已服药 N 次」，**这一步不需要任何口径决策，纯粹是消除误导**。
2. 再决定是否实现准时率：若实现，需在 `dose_records` 或聚合 SQL 里引入 `actual_ts - scheduled_ts` 判断，并补边界测试（跨时区、夏令时、跨月）。
3. 同步修正 README 的优先级表与 `FINAL-PRODUCT` D-11，让二者一致。

### P1-17 「灭屏全屏弹窗提醒」开关完全无效

**位置**：`ui/screen/settings/SettingsScreen.kt:174-184` → `ReminderSettings.kt:43` → `Notifications.kt:99-162`

开关能写库、`ReminderSettings.resolve()` 也能读出 `fullScreenAlert` 塞进 `Behavior`，但 `Notifications.showDoseNotification` 全文**没有一次 `behavior.fullScreenAlert` 引用**，也没有 `setFullScreenIntent`；`AndroidManifest.xml:5-11` 也没有 `USE_FULL_SCREEN_INTENT`。

且 `full_screen_alert` 的**默认值是 `true`**（`SettingsViewModel.kt:39`），首次进入时开关显示"已开启"—— 用户会以为已经在用全屏提醒。

**这与上一轮审查的 P0-8 是同一项**：上一轮把它和「夜间免打扰」「推迟时长」一起列为 P0-8，后两项**确实修好了**（`ReminderSettings.shouldSilence` 有 4 条针对性单测，`snoozeMinutes` 真的被 `Notifications.kt:129` 消费），**唯独这一项没动**。`docs/reviews/DESIGN_REVIEW.sbf.md:199-210` 也提过同一问题。

**修改建议**：二选一。补实现（加权限 + `setFullScreenIntent` + 新建一个承载三个大按钮的 `DoseActionActivity`），或**先把开关从 UI 隐藏并删掉 `Behavior.fullScreenAlert` / `KEY_FULL_SCREEN`**。后者成本低得多，且立刻消除误导。`sound_mode` 同理（有 setter、无人读、常量 `KEY_SOUND_MODE` 零引用）。

### P1-18 主线程执行文件 IO（导出 / 恢复）

**位置**：`ui/screen/settings/SettingsViewModel.kt:84-98 / 106-121 / 126-143`、`ui/screen/inventory/InventoryViewModel.kt:135-145`、`ui/screen/stats/StatsViewModel.kt:155`、`core/data/DataExporter.kt:74-75`、`:247`

```kotlin
viewModelScope.launch {                                    // Dispatchers.Main.immediate
    val file = DataExporter.exportDoseRecordsCsv(app, db)
```

```kotlin
// DataExporter.kt:74-75
val file = File(exportDir(context), "CarroMed_服药明细_${timestamp()}.csv")
file.writeText(sb.toString(), Charsets.UTF_8)            // 同步写，主线程
```

```kotlin
// DataExporter.kt:247
val text = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
```

Room 的 `suspend` 查询会自己切到 query executor，但 `StringBuilder` 累积与 `writeText`/`readText`/`JSONObject(text)` 解析都在主线程。`importBackup` 尤其重：整个备份文件读入内存 + JSON 解析 + 一个覆盖 7 张表的事务。3 年用药数据（每天 4 次 ≈ 4400 行）足以造成明显卡顿，`importBackup` 有触发 ANR 的可能。

**修改建议**：在 `DataExporter` 的四个 `suspend fun` 内部用 `withContext(Dispatchers.IO)` 包住文件操作（改动面最小，且对所有调用方一次性生效）。另：`StatsViewModel` / `ProgressViewModel` 的 `exportReport()` 缺 `isExporting` 防重复点击（`SettingsViewModel` 有），且两者代码逐字符重复（含相同的 try/catch/Toast 文案）—— 抽到 `core/data` 或独立 `ExportViewModel`。

### P1-19 系统撤销精确闹钟权限后会清空全部闹钟，App 无任何感知与自愈

**位置**：`core/alarm/AlarmScheduler.kt:49-50`、`core/alarm/AlarmReconciler.kt:23-25`

Android 在用户撤销 `SCHEDULE_EXACT_ALARM` 时会**清空该应用的全部已注册闹钟**。此后：
- `canScheduleExactAlarms()` 返回 `false`；
- 但**没有任何触发点**会因此重新对账（`rescheduleAll` 的 8 个调用点没有一个是权限状态驱动的）；
- 结果是提醒全部消失，且 App 内**没有任何提示**（P0-6 的自检页还写着"已授权"）。

**修改建议**：`MainActivity` 启动时比对"上次对账时是否有权限"；`PermissionCheckScreen` 如实反映状态（P0-6 修复后自然覆盖）。另外 `BootReceiver` 建议补 `ACTION_LOCKED_BOOT_COMPLETED`（用户设置了锁屏凭据时 `BOOT_COMPLETED` 不再投递）。

### P1-20 「提前提醒」与「准点提醒」的通知内容完全相同

**位置**：`core/alarm/AlarmScheduler.kt:21`、`:31` vs `core/alarm/AlarmReceiver.kt:20-51`

`EXTRA_IS_ADVANCE` 被写入 Intent（`AlarmScheduler.kt:21,31`）但**`AlarmReceiver` 从未读取**（全库 grep 只在 `AlarmScheduler` 命中）。两条闹钟最终调同一个 `showDoseNotification`，用同一个 `notify(slot.id.toInt(), ...)` id、同样的文案「计划 HH:mm · 剂量 X」。

**后果**：配了"提前 15 分钟"的用户会收到两条一模一样、间隔 15 分钟的通知，第二次还会**把第一条替换掉**（同 notification id）。用户完全感知不到"这是提前提醒"，反而觉得 App 在重复骚扰。一个**只写不读的死字段**——静态检查也发现不了。

**修改建议**：`AlarmReceiver` 读出 `isAdvance` 传入 `showDoseNotification`；提前提醒用 `"15 分钟后该吃药了：X"` + `PRIORITY_LOW`（不 heads-up），并考虑不展示"推迟/跳过"按钮（避免提前确认）。

---

## 三、P2 建议（24 项）

### 数据与领域层

| # | 问题 | 位置 | 说明 |
| --- | --- | --- | --- |
| P2-1 | `setStockTracking` 有一条不改流水的账面写入分支 | `DoseTrackingService.kt:248-250` | `else if (target != currentStock) { medDao.updateStock(...) }` 正是 AGENTS.md §2 明文禁止的写法。当前唯一调用方（`InventoryViewModel.kt:151`）恰好传入等于账面值的参数所以走不到，**但这是无保护的陷阱**。改为调 `calibrateStock` 即可复用流水逻辑。 |
| P2-2 | `saveReminderPolicy` 每次保存都 INSERT 新策略行 | `SchedulePolicyDao.kt:54-61` | `deactivatePoliciesForMedication` + `insertPolicy` 使 `schedule_policies` 无限增长，旧 `policy_times` 行成为孤儿（外键 CASCADE 只在策略被删时触发，而策略从不被删）。正确性无碍（`getTimesForPolicy` 按 id 过滤），但建议加定期清理或改为 UPDATE 复用。 |
| P2-3 | `AppConverters.toStringList` 用 `\|\|\|` 作分隔符 | `AppConverters.kt:44,48` | 注意事项文本若含 `|||` 会被静默拆成多项。改用 `JSONArray` 或加转义。 |
| P2-4 | `toIntList` 对空串返回 `emptyList()` | `AppConverters.kt:55-56` | `DAYS_OF_WEEK` 的 `days_of_week = ""` 与 `NULL` 语义相同，无法区分"未配置"与"清空"。当前无实际问题，但 CYCLE/PRN 场景下值得明确。 |
| P2-5 | `dose_slots` 唯一性只靠内存去重 | `DoseSlotEntity.kt:31` | 复合索引非 unique。当前靠 `reconcileSchedule` 的 `existingSlotKeys` 保证，任何绕过该路径的插入都会造出重复槽位 → 重复闹钟 + 重复扣库存。改为 `unique = true`（需走标准迁移流程）。 |
| P2-6 | `exportSchema = false` 放弃了 Room 的迁移自动校验 | `AppDatabase.kt:39-40` | AGENTS.md §2 的迁移红线全靠人工记忆 + 手写 SQL 断言。`ALTER TABLE` 少写一列不会有任何自动信号。建议开 `exportSchema` + `room.schemaLocation` 并把 `app/schemas/` 入版本控制。 |
| P2-7 | `DoseSlotEntity` 索引 `(medication_id, scheduled_date, scheduled_time)` 与 `Index(["scheduled_date","status"])` 部分重叠 | `DoseSlotEntity.kt:26-32` | 4 个索引在百万级槽位上写入放大。建议用 `EXPLAIN QUERY PLAN` 核对后精简。 |

### 可靠性与调度

| # | 问题 | 位置 | 说明 |
| --- | --- | --- | --- |
| P2-8 | 越过 2 小时宽限内但已过点的槽位被直接 cancel，错过补响 | `AlarmReconciler.kt:63,77-81` | `getStalePendingSlots` 的阈值是 `now - 2h`，但 cancel 分支的阈值是 **0**。手机 07:59 关机、08:03 开机，08:00 的剂量 `scheduledTs <= now` → 被 cancel，用户**永远收不到这次提醒**，且 2 小时后才记 EXPIRED。建议对落在 `(now-2h, now]` 的槽位**立即补发一次**并标注"已延迟 X 分钟提醒"。 |
| P2-9 | 通知 Action 的 PendingIntent 从不显式 cancel | `Notifications.kt:164-166` | `cancelDoseNotification` 只做 `NotificationManagerCompat.cancel`，未 `PendingIntent.cancel()`。记录存活在 system_server，不随进程死亡回收。每条提醒泄漏 3 条 action + 1 条 contentIntent。 |
| P2-10 | `AlarmReconciler` 在 `goAsync` 里做全量对账 | `BootReceiver.kt:25-33` | `goAsync()` 的 `PendingResult` 有约 10 秒完成期限。15~20 个药的慢病用户，`rescheduleAll` 要做 N 个事务 + M 次 binder 调用，可能超时 → 开机后闹钟未排成功且无补偿。建议 receiver 只发一条短闹钟/JobScheduler 任务，自身立即 `finish()`。 |
| P2-11 | `BootReceiver` / `DoseActionReceiver` 的协程只有 `finally` 没有 `catch` | `BootReceiver.kt:27-33`、`DoseActionReceiver.kt:35-79` | `CoroutineScope(Dispatchers.IO)` 是根 Job，`launch` 内抛异常会走到 `Thread.getDefaultUncaughtExceptionHandler()` → **进程崩溃**。而项目刻意禁用了 `fallbackToDestructiveMigration`（"迁移失败必须显式崩溃"），**开机广播里就可能炸**。对照 `AlarmReceiver.kt:45` 有 `catch (t: Throwable)` —— 同一层三种写法不一致。 |
| P2-12 | `snoozeDose` 无条件返回 `true` | `DoseTrackingService.kt:105-112` | `slotDao.snoozeSlot` 是 UPDATE，slot 不存在时影响 0 行也算成功。导致 `TodayViewModel.kt:161-164` 会为一个不存在的 slotId 排一个幽灵闹钟。 |
| P2-13 | 提前/推迟值域未钳制，可渲染出「0 分」按钮 | `TodayViewModel.kt:113-114` vs `Notifications.kt:129` | 通知栏用了 `coerceIn(1, 240)`，今日页长按菜单没有。`app_settings` 可被备份文件写入（`DataExporter.kt:408`），一份 `"snooze_minutes": "0"` 的 JSON 就会产生「0 分」按钮。 |
| P2-14 | 第二档 `setAlarmClock` 的 KDoc 描述错误 | `AlarmScheduler.kt:9-16`、`:59-68` | KDoc 与 manifest 注释都写"未授权精确闹钟 → setAlarmClock … 无需特殊权限"。实际上 `setAlarmClock` 在 Android 12+ **同样需要** `SCHEDULE_EXACT_ALARM`，在"未授权"这个唯一进入条件下必然抛 `SecurityException` → 被 catch 吞掉 → 落到第三档 `setAndAllowWhileIdle`。**第二档是死代码，真实降级是第三档（Doze 下可能延迟 15 分钟+）。** README.md:100 同一处说法也不准确。 |

### 界面与交互

| # | 问题 | 位置 | 说明 |
| --- | --- | --- | --- |
| P2-15 | 进展页打卡矩阵丢失 skipped / missed / pending 语义 | `ProgressViewModel.kt:95-101`、`ProgressScreen.kt:295-305` | `DayAdherence` 只带 `completed` 和 `total`，导致「1 服 + 1 待服」「1 服 + 1 跳过」渲染成完全相同的浅绿「1/2」。**用户永远不知道自己一天主动跳过几次**，这对依从率归因（忘了吃 vs 选择不吃）是关键信息。 |
| P2-16 | `AdherenceBreakdownCard` 是全项目唯一漏掉空态的统计组件 | `StatsScreen.kt:163-165,287,308-351` | `coerceAtLeast(1)` 掩盖了真实缺陷：空库/无到期任务时渲染一条 10dp 高的空白圆角矩形，三组图例全是 0，无任何解释文案。同文件 `:140-144` 和进展页两处都正确处理了。 |
| P2-17 | 进展页空态是死胡同 | `ProgressScreen.kt:119-120`、`AppNavigation.kt:150` | 文案说"添加第一个药品"但界面上**没有任何可点入口**。`TodayScreen` 的 `FirstRunGuideCard` 与 `CabinetScreen` 的空态都带按钮，仅此一处没有。 |
| P2-18 | 「注意事项」chip 是可点击但无行为的死控件 | `MedicationDetailScreen.kt:344-358` | `AssistChip(onClick = {})`。M3 里 `AssistChip` 是 Button，TalkBack 会念"按钮"并播报 ripple 反馈。注意事项是医疗安全信息（"严禁与葡萄柚同食"），让用户以为点一下能展开说明。改为 `Surface`+`Text`（同文件 `:726-735` 的 `TagChip` 写法就是对的）。 |
| P2-19 | `DoseActionBottomSheet` 是 230 行死代码，且含 2 个逻辑 bug | `ui/component/DoseActionBottomSheet.kt` | 全库 **0 个 Kotlin 调用点**（grep 8 处命中全是文档 + 自身定义）。上一轮审查已记录此事，至今未清理。附带两个 bug：`:130` 的 `noticeShort.isNotBlank() \|\| precautions.isEmpty()` **条件写反**（应为 `&&`），导致任何药品都渲染一行空的加粗「注意: 」；`:161` 硬编码「片」且 `toInt()` 截断，0.5ml 会显示"自动扣减 **0** 片库存"。建议直接删除。 |
| P2-20 | 数量格式化四套并存，同一数值四个页面四种写法 | `TodayScreen.kt:810`、`CabinetScreen.kt:361`、`StatsScreen.kt:381`、`ProgressScreen.kt:391` | `%.2f` / `%.2f` / `%.1f` / 原值直出。`doseAmount = 1.0f` 在进展页显示「1.0 片」，其他三处显示「1 片」。这也是 P2-19 硬编码「片」的直接诱因。建议抽 `ui/component/QuantityFormat.kt`。 |
| P2-21 | 深色主题下 `SuccessGreen` / `WarningAmber` 对比度不足 | `Color.kt:69-78` | 这两个 token 无 `_Dark` 变体，`Theme.kt:45-70` 的 `DarkColorScheme` 未覆盖。实测 `WarningAmber #D97706` on `SurfaceDark #1E293B` ≈ **3.89:1**，`SuccessGreen #16A34A` on 同背景 ≈ **3.94:1**，均低于 WCAG AA 的 4.5:1。而这两个色主要用于**卡片内小字**（`TodayScreen.kt:602` 是 11sp）。建议补 `SuccessGreenDark #4ADE80` / `WarningAmberDark #FBBF24`。 |
| P2-22 | 药箱搜索每敲一个键触发 N×2 次数据库往返 | `CabinetViewModel.kt:81-95,114-116` | `_keyword` 是 `combine` 的源，`buildItemUi` 在 `combine` 内对每个药做 2 次查询。20 药 = **每按键 40 次 IO**，中文输入法拼音态更多，必然掉帧。建议把 `buildItemUi` 用 `mapLatest` 移出 `combine`。 |
| P2-23 | 今日页在 Flow 变换里做 N+1 查询 | `TodayViewModel.kt:84-97` | 对每个 COMPLETED/SKIPPED 槽位单独 `recordDao.getRecordBySlotId(slot.id)`。切日期/打卡/撤销/改全局设置都会重跑。建议加 `observeRecordsForSlots(slotIds)` 批量查询。 |
| P2-24 | `DoseActionBottomSheet` / `SampleDataSeeder` 之外，另有 3 处死代码 | `AddEditMedicationScreen.kt:96`（`context` 未使用）、`ManualDoseScreen.kt:255,267`（`Calendar` 赋值未用）、`ManualDoseViewModel.kt:106-108`（`onActualDateTimeChange` 无调用方）、`InventoryScreen.kt:320`（`calibrate(null)` 使 `note` 参数永远用不上） | 清理即可。 |

---

## 四、P3 工程卫生（16 项）

| # | 类别 | 问题 |
| ---: | --- | --- |
| P3-1 | 构建 | `app/build.gradle.kts:50` 引用 `proguard-rules.pro`，**该文件不存在**。当前 `isMinifyEnabled = false` 所以不报错（已实证 `assembleRelease` BUILD SUCCESSFUL），但**一旦开启混淆就会直接构建失败**，而 AGENTS.md §9 的收口自检只跑 `compileReleaseKotlin`，测不出这个问题。建议先建一个带 Room/Compose 标准 keep 规则的文件占位。 |
| P3-2 | 构建 | release 未开 R8，包体积明显偏大。开启前需注意：所有枚举走 `AppConverters` 的 `valueOf` 按**名字**解析，混淆后必须加 `-keepclassmembers enum * { *; }`，否则 `toSlotStatus` 等会静默回落到默认值（`PENDING` / `COMPLETED`），**数据含义直接改变**。 |
| P3-3 | 编译警告 | 7 处弃用警告：`Icons.Filled.Sort`（`CabinetScreen.kt:122`，应用 `Icons.AutoMirrored.Filled.Sort`）、5 处 `Modifier.menuAnchor()`（`AddEditMedicationScreen.kt:476`、`ManualDoseScreen.kt:187`、`RefillScreen.kt:211`、`ReminderSettingsScreen.kt:630`、`SettingsScreen.kt:146`，应传 `MenuAnchorType`）。AGENTS.md §4 要求"提交前运行并修复所有 Error"，Warning 未列为门禁，建议一并清理。 |
| P3-4 | 资源 | `AndroidManifest.xml:16,18` 启动图标指向 `@android:drawable/sym_def_app_icon`（系统默认绿色机器人），`res/` 下无 `mipmap-anydpi-v26`。通知小图标 `android.R.drawable.ic_dialog_info`（`Notifications.kt:132`）在 Android 5+ 被强制单色化成不可辨认的方块。对"专业医疗 App"是明显的完成度问题。 |
| P3-5 | 资源 | `strings.xml` 只有 `app_name` 一条，**全库 `R.string` 零引用**。AGENTS.md §6 已声明"暂不强制"，不算 bug。但 `docs/FINAL-PRODUCT.md:37`（D-6）写的是「**全部文案走资源文件 strings.xml**，不硬编码」，且同一文档 §七 又把"v1 多语言"列为非目标 —— **D-6 与 §七 自相矛盾**，应先修订决策再决定是否动代码。 |
| P3-6 | 资源 | `themes.xml:3` 用平台主题 `android:Theme.Material.Light.NoActionBar`，**强制浅色、无 `values-night`**，与 `MainActivity.kt:33` 的 `enableEdgeToEdge()` 混用。状态栏/导航栏图标明暗由平台主题决定，深色模式下会不协调。 |
| P3-7 | 权限 | `AndroidManifest.xml:11` 声明 `WAKE_LOCK` 但全工程零使用（`setExactAndAllowWhileIdle` 由系统进程持锁，应用侧不需要）。`VIBRATE` 有用（`Notifications.kt:55`），应保留。 |
| P3-8 | 死代码 | `app/src/main/.../core/data/SampleDataSeeder.kt`（298 行）在 **main 源集、release 包内、全库 0 个调用点**，`DevSampleDataSeeder`（debug 源集）已完全覆盖其功能。README.md:325-327 自己把它列为"已知待清理"并说"删除前需确认无外部依赖" —— **本次已确认无任何引用，可以直接删**。详见 §5.3。 |
| P3-9 | 文档 | `app/build.gradle.kts:69-70` 注释「供 SampleDataSeeder 判定 debug/release」已过期（`buildConfig = true` 当前无任何 `BuildConfig.*` 引用）。 |
| P3-10 | 文档 | `README.md:298` 的文档索引**未收录** `docs/reviews/` 下的 3 份审查报告，也未收录 `docs/MEDICINE-DATA-PLAN-20260927.md`、`docs/PROJECT_STATUS_20260927.md`、`docs/ui_implementation_plan.md` 共 6 份文件。 |
| P3-11 | 文档 | `docs/ARCHITECTURE.md:236-237` 与 `docs/FINAL-ARCHITECTURE.md:136` 描述的 `snooze_count` 字段在代码中不存在（见 P1-2）；`docs/ARCHITECTURE.md:231-233` 描述的 `RequestCode = (med_id × 10000) + ...` 编码方案已被 `slot.id` 取代。三份文档均为"权威源"却与实现脱节，建议标注版本与废弃章节。 |
| P3-12 | 文档 | `README.md:320` 把「±N 分钟准时率口径」列为 P2，而 `FINAL-PRODUCT.md:42`（D-11）与 `:77`（M-07）把它列为 v1-P0（见 P1-16）。 |
| P3-13 | 文档 | `docs/CHANGES-20260927.md:108` 写「药品名错别字「环孢素」→ **全库与全部文档统一勘误**为「环孢素」」。**核实为失实记录**：`app/src/main/.../SampleDataSeeder.kt` 的 4 处（`:38`、`:41`、`:256`、`:284`）仍是错字（详见 §5.3）。失实的变更记录会让后续审查跳过这一项。 |
| P3-14 | 一致性 | 「隔天」文案逻辑在 `CabinetViewModel.kt:123` 与 `MedicationDetailScreen.kt:589` 各写一遍；`exportReport()` 在 `StatsViewModel.kt:151` 与 `ProgressViewModel.kt:160` 逐字符重复（含相同 try/catch/Toast）。两处各写一遍是 P1-9、`:131` 那一族 bug 的根源。 |
| P3-15 | 一致性 | `StatsViewModel.kt:126` 与多个 ViewModel 用 `? "片"` 兜底单位。`medication == null` 在外键完整时不应发生，但 `ProgressScreen.kt:405` 已为此准备了"已删除的药品"文案，说明作者认为它可能发生 —— 兜底成"片"会让液体制剂显示错误。 |
| P3-16 | 一致性 | `TodayScreen.kt:93` 把 `EXPIRED` 与 `PENDING`/`SNOOZED` 一起放进 `pendingItems`，点 ✓ 会把已逾期的槽位改写成 `COMPLETED` → **追溯修改历史依从率**（昨天的漏服因今天补打卡而消失）。这可能是有意设计（`docs/UI_DESIGN.md:85` 有对应原型），但**界面上没有任何提示**，用户不会意识到自己改写了统计。建议至少给这类卡片加"补记"小标。 |

---

## 五、专项核查结论

### 5.1 已实证通过的项（避免误报，这些都做对了）

| 核查点 | 结论 |
| --- | --- |
| 无 `INTERNET` 权限 | ✅ `AndroidManifest.xml` 无该声明，物理断网成立 |
| `allowBackup` 显式关闭 | ✅ 与"数据不出设备"定位一致 |
| `exported` 全部显式声明 | ✅ 5 个组件齐全，`AlarmReceiver`/`DoseActionReceiver` 正确设为 `false` |
| 无 SQL 注入面 | ✅ 全部走 Room 参数绑定，唯一的 `execSQL` 是静态迁移字符串 |
| `key.properties` / `*.jks` 未入库 | ✅ `.gitignore` 已忽略，`git ls-files` 查不到 |
| debug receiver 不进 release | ✅ `DevDataReceiver` 只在 `app/src/debug/AndroidManifest.xml` |
| 无第三方运行时依赖 | ✅ 仅 androidx 官方库 + JDK `org.json`，README 说法成立 |
| `goAsync()` 用上了 | ✅ 3 个 receiver 全部使用（**我最初怀疑缺失，核实后确认已正确实现**） |
| `PendingIntent.FLAG_IMMUTABLE` | ✅ 三处全部使用（否则 Android 12+ 崩溃） |
| `cancel` 与 `schedule` 对称 | ✅ 同时撤 advance=false/true 两个，无"取消不掉" |
| 夜间免打扰是真的 | ✅ 独立 LOW 渠道（渠道重要性不可事后修改，这是唯一正确做法），4 条单测锁住 |
| `importBackup` 真的实现了 | ✅ 品牌校验 + 版本校验 + 单事务删回填 + 保留自增 ID + 恢复后重排闹钟 |
| `file_paths.xml` 与实际写目录匹配 | ✅ 两条路径（external-files / files）都覆盖 |
| CSV 转义逻辑正确 | ✅ 逗号/引号/换行都覆盖，双引号翻倍正确，UTF-8 BOM 存在 |
| `fallbackToDestructiveMigration` 刻意禁用 | ✅ 且写明了理由 |
| 迁移对既有数据零破坏 | ✅ 全部 `ADD COLUMN ... DEFAULT` / `CREATE INDEX IF NOT EXISTS`，`MigrationTest` 用真实 SQLite 校验 |
| `BootReceiver` 四个自愈 action 齐全 | ✅ 且都是系统保护广播，不受隐式广播限制 |
| 通知栏 Action 链路闭合 | ✅ 三个 action 逐一校验，manifest 静态注册 |
| `0/0` 不谎报 100% | ✅ 三处独立判断 `decided > 0`，分母为 0 显示「—」 |
| 假数据彻底清除 | ✅ 统计页/进展页的字面量已换成真实聚合，且留了"为什么这么改"的 KDoc |
| 71 项测试全绿属实 | ✅ 已 `--rerun-tasks` 实测 71/71（但见 P1-12 关于"数量≠质量"） |

### 5.2 明确"看起来像 bug 其实是对的"（核实后排除，避免误报）

| 疑似问题 | 核实结论 |
| --- | --- |
| `PermissionCheckScreen` 的 advance 闹钟 requestCode 与通知栏 SNOOZE 按钮撞号 | **不撞**。两者 requestCode 都是 `10N+1`，但 component（`AlarmReceiver` vs `DoseActionReceiver`）与 action 都不同，`filterEquals` 判定为两条独立记录。**但这是没有注释保护的地雷** —— 见 P0-1。 |
| 通知 contentIntent 的 requestCode（`slotId`，`MainActivity`）与主闹钟（`slotId`，`AlarmReceiver`）撞号 | **不撞**，component 不同。 |
| `StatsScreen.kt:201` 的 `coerceAtLeast(0.0001f)` 是凑数 | **不是**。`rankings` 已判空，全 0 时 `0/0.0001 = 0` 语义正确。 |
| `DoseActionBottomSheet.kt:47` 的 `rememberModalBottomSheetState()` 作为 Composable 默认参数 | **正确**。默认参数在组合上下文求值，state 正常 remember。 |
| `AppDatabase.kt` 单例用 `INSTANCE ?: synchronized(this)` | **正确**，标准 double-checked 写法。 |
| 4 个主 Tab 标题高度不统一 | **已修**。`HomeTabHeader.kt` 统一了规格（64dp / headlineMedium Bold / 44dp 按钮垂直居中），且把 `actionContentDescription` 设为非空参数，把无障碍标注变成编译期约束。 |
| `.gitignore` 中文注释乱码 | **不是编码问题**。字节流验证为合法 UTF-8（`E6 9E 84 E5 BB BA` = "构建"），是 PowerShell 控制台代码页的显示问题。 |

### 5.3 药品通用名错别字 —— 精确结论（不夸大）

用 `unicodedata` 逐码位核验，结论如下：

| 位置 | 药品名 | 码位 | 判定 |
| --- | --- | --- | --- |
| `app/src/debug/.../DevSampleDataSeeder.kt:58` | 环**孢**素 | U+73AF **U+5B62** U+7D20 | ✅ **正确**（环孢素 Cyclosporine） |
| `app/src/main/.../SampleDataSeeder.kt:41,256,284` | 环**抱**素 | U+73AF **U+62B1** U+7D20 | ❌ **错字**（"抱" ≠ "孢"） |
| `docs/CHANGES-20260927.md:108` | 环**抱**素 | — | ❌ 错字 + **失实的"已全库勘误"声明** |
| 全部单测、`DevSampleDataSeeder`、`ARCHITECTURE.md`、`UI_DESIGN.md` 等 | 环孢素 | U+5B62 | ✅ 正确 |

**准确的结论是**：错别字**只存在于 `app/src/main` 源集里那个零调用点的死文件 `SampleDataSeeder.kt`**，以及一条变更日志里。**它今天不会出现在任何用户界面上** —— 上一轮审查把它列为"高严重度医疗可信度硬伤"是**高估了**（当时 debug seeder 也还没改对，那次是真的会暴露；这次已经改对了）。

**但仍需处理，理由是**：

1. 该文件在 **main 源集**，随 release APK 打包，且 `isMinifyEnabled = false` 不会被 R8 剔除。
2. **两份 298/315 行的播种器逐行重复**（`SampleDataSeeder` vs `DevSampleDataSeeder`），是一枚维护地雷：任何一方改动都不会同步 —— 本次错字残留正是这个地雷的产物。
3. `docs/CHANGES-20260927.md:108` 声称"已全库勘误"会让**后续审查跳过这一项**。
4. 它是 README 自己列出的"已知待清理"项，已经挂了整整一个版本周期；本次已确认**全库零引用，可直接删除**。

**修改建议**：直接删除 `app/src/main/.../SampleDataSeeder.kt`（`DevSampleDataSeeder` 完全覆盖其功能），并订正 CHANGES 的表述。

### 5.4 与已拍板产品决策的逐条核对

`docs/FINAL-PRODUCT.md` 声明自己是「产品功能唯一权威源」。逐条核对结果：

| 决策 | 内容 | 实现状态 |
| --- | --- | :---: |
| D-4 | 仅国内直发 APK，不上架 Play，用 `SCHEDULE_EXACT_ALARM` + `setAlarmClock` 兜底 | ⚠️ `setAlarmClock` 兜底实际是死代码（P2-14） |
| D-6 | 全部文案走 `strings.xml`，不硬编码 | ❌ 0 引用（且与本文档 §七 自相矛盾，P3-5） |
| **D-7** | **整数毫单位记账，全程无浮点** | ❌ **全链路 `Float`**（P1-6） |
| D-8 | 库存为可选功能 | ✅ |
| **D-9** | **库存不足允许扣为负数，绝不阻止打卡** | ❌ **钳到 0**（P0-3） |
| D-10 | INTEGER 自增主键 + 覆盖式恢复 | ✅ |
| **D-11** | **主指标完成率，次指标准时率** | ❌ **准时率完全缺失**（P1-16） |
| D-12 | 锁屏通知默认显示药品名 + 三档可配 | ⚠️ 默认显示药名 ✅，三档开关 ❌ |
| D-13 | 本地自动滚动备份（保留 5 份）+ `allowBackup=false` | ⚠️ `allowBackup=false` ✅，**自动滚动备份 ❌**，恢复前快照 ❌（P1-14） |
| **D-14** | **三档降级：精确 → setAlarmClock → WorkManager 自愈** | ❌ **无 WorkManager 依赖**（P0-2） |
| D-16 | 极简录入（仅名称+时间必填） | ✅ |

| 场景 | 内容 | 实现状态 |
| --- | --- | :---: |
| 场景 2 | 撤销 = 追加 REVERT 事实，全程留痕 | ❌ **物理 DELETE**（P1-1） |
| **场景 3** | **补录优先绑定已有 PENDING/EXPIRED 槽位（幂等去重）** | ❌ `logManualDose` 一律 `slotId = null`，**从不绑定槽位**（见下） |
| 场景 4 | 有历史的药品强制归档，统计永久保留 | ✅ |
| 场景 5 | PRN 计入消耗量、不进依从率分母 | ⚠️ 不进分母 ✅，**计入消耗量 ❌**（P1-4） |
| 场景 6 | 推迟链上限 3 次，`snooze_count` | ❌ 字段不存在（P1-2） |
| M-03 | 触发即重排 | ❌（P0-2） |
| M-04 | 通知连点 3 次仅扣 1 次库存（幂等验证） | ❌（P0-4） |
| M-06 | 月历热力图（三视图同 Tab 切换） | ❌ 只有 7 天矩阵 |
| M-08 | 覆盖式恢复 + **导入前自动快照** | ⚠️ 恢复 ✅，快照 ❌ |
| M-10 | 3 步首启引导 | ⚠️ 简化为今日页空态引导卡（可接受，但文档未更新） |
| §六.4 | 备份 AES-GCM 加密 + CSV 脱敏开关 | ❌ 均未实现 |
| §六.5 | 应用内隐私说明页 | ❌ 13 个页面中无此页 |
| **§八** | **提醒可靠性：连续 7 天零漏提醒** | ⚠️ **从未执行过**；且窗口恰好 7 天，该验收标准在结构上无法暴露 P0-2（P0-2 末段详述） |
| §八 | 1 年数据统计查询 < 200ms（约 5,500 行量级） | ⚠️ 未测；`reconcileSchedule` 每次回前台全删重建（360 行）与之相关 |
| §八 | 备份恢复 < 10s | ⚠️ 未测；且当前在主线程执行（P1-18） |

**场景 3 值得单独说明**（这是本次审查新发现的一处规格与实现分歧）：

`docs/FINAL-PRODUCT.md:111` 规定「补录**优先绑定已存在的 PENDING/EXPIRED 槽位**（幂等，杜绝重复扣库存），无匹配槽位才新建事实」。而 `DoseTrackingService.logManualDose`（`:166-174`）**无条件写 `slotId = null`**，从不查是否有可绑定的槽位。后果：

1. 用户已经有一条"今日 08:00 已逾期"的槽位，又用手动补录记了一次 08:00 的服药 → **产生两条事实、扣两次库存**（一次来自槽位打卡，一次来自补录），而槽位仍是 EXPIRED。
2. 补录的服药**不会把对应槽位标记为已完成** → 今日清单仍显示"已逾期"，进展页仍记为漏服。
3. 依从率（基于槽位）与累计用量（基于事实）**互相矛盾**：补录让用量涨了，但依从率没变。
4. 无法满足 §189 的 M5 出口标准「补录去重生效」。

**修改建议**：`logManualDose` 增加一个可选的"绑定最近未决槽位"逻辑 —— 查该药品在 `actualTs` 前后 ±N 分钟内是否有 `PENDING`/`EXPIRED` 槽位，有则绑定并把槽位置为 COMPLETED（复用 `takeDose` 的事务），无则才写 `slotId = null` 的 PRN 式事实。这同时能缓解 P0-4 的一部分影响面。

---

## 六、疑似问题（需真机或产品决策确认，代码无法单独定论）

| # | 问题 | 验证方法 |
| ---: | --- | --- |
| S-1 | 今日页顶部 inset 疑似被重复计入（`TodayScreen.kt:112-113` 同时用 `Scaffold` 的 `innerPadding` 和 `.statusBarsPadding()`，而另外 3 个 Tab 没有 `Scaffold`）。若成立，"今日"标题会比其他 Tab 低一个状态栏高度 | `python tools\app_screenshots.py --only today,cabinet --dump-ui`，比对两页 `HomeTabHeader` 标题 Text 的 `bounds.top` |
| S-2 | 单应用 pending alarm 的系统硬上限是多少？超限后新注册的闹钟是被丢弃还是抛异常？这决定 P1-5 的泄漏最终是"浪费电量"还是"真实挤掉闹钟" | 真机长期挂机，`adb shell dumpsys activity pending-intent \| wc -l` 观察增长与拐点 |
| S-3 | `goAsync()` 在 `BOOT_COMPLETED` 上的实际宽限期是多少？15 药的库做一次全量对账要多久？ | 用 15+ 药品的库在真机开机后测 `onReceive`→`finish()` 耗时 |
| S-4 | `getExternalFilesDir` 在多卷设备上是否一定落主卷？若落次卷，`FileProvider.getUriForFile` 会抛 `IllegalArgumentException`，而 `shareFile` 的 `runCatching`（`DataExporter.kt:433`）会把它**静默吞掉** | 双卡/存储融合真机点一次"分享"，看是否无反应 |
| S-5 | 标记为「重要提醒」的药品在国产 ROM 上是否真能穿透静音？`Notifications.kt:139` 设了 `CATEGORY_ALARM`，但部分 ROM 仍会压制 | 真机 23:30 挂一个 `isCriticalReminder = true` 的药实测。`ReminderSettingsScreen.kt:432` 的文案「响铃 + 强提醒横幅」目前**无法证伪也无法证实** |
| S-6 | `docs/reviews/DESIGN_REVIEW.sbf.md:320` 提到方案定义「±60 分钟内视为准时」，但当前 `StatsEngine` 里看不到这个定义。**两份文档与实现三方不一致**，需要确认哪个是当前意图 | 产品决策（与 P1-16 一并处理） |

---

## 七、这个项目做得好的地方（平衡清单）

这些是真实的、应当保留并在后续重构中保护住的优点：

### 架构与领域设计

1. **领域层纯度是真的**。`SlotProjectionEngine` 与 `StatsEngine` 是纯函数对象，**零 `android.*` 依赖**（`DoseTrackingService` 里的 `withTransaction` 是 AGENTS.md 明确豁免的唯一一处）。这让"数学守恒"真的能在 JVM 单测里跑出来 —— 对一个纯本地记账类 App，这是最正确的一条决策。
2. **三层时序解耦落地了，不是画在 PPT 上**。规则（`schedule_policies`）→ 槽位（`dose_slots`，可再生缓存）→ 事实（`dose_records`，不可变真相）。`reconcileSchedule` 只删 `PENDING`（`DoseSlotDao.kt:58` 的 SQL 自带 `status='PENDING' AND scheduled_ts >= :fromTs` 双重条件），`DoseTrackingService.kt:301` 的"核心保护"注释把意图讲透了。
3. **`InventoryTransactionDao` 在接口层面就不存在 UPDATE/DELETE 方法**（`:14-44` 只有 `@Insert` 与聚合查询），从类型层面杜绝了改账。这比"靠 code review 盯着"强得多。
4. **`MedicationDao` 的局部 UPDATE 拆分是教科书级的**：`updateProfile` / `updateReminderBehavior` / `updateMinStockAlert` / `updateStockTracking` / `updatePauseStatus` / `updateArchiveStatus` 各自只写自己那一组列，每条 SQL 都有 KDoc 说明"为什么不能整行覆盖"。P0-5 的破口正是在这个设计上开的（SET 列表里多了三列），但**方向完全正确**。
5. **聚合下推到 SQL**。`Aggregates.kt:8-11` 的 KDoc 写得很清楚："目的是让统计在数据量增长后仍能走索引聚合，而不是把几千条 dose_slots 全量读进内存再在 Kotlin 里数"，并配了 v2 的复合索引与 `StatsDaoAggregationTest` 真库验证。
6. **统计口径的跨页统一做得非常认真**。`DoseSlotDao.kt:70-75` 的 KDoc 写明"统计报表与进展页共用此查询，保证口径完全一致"，且**真的只有一个实现**被两处调用；`DoseRecordDao.kt:54-57` 把"依从率按计划时间 / 消耗量按实际服药时刻"这个最容易搞混的区分写进了 KDoc 并真的用了两套查询。
7. **迁移策略是本项目最硬的一块**。v1→v2 全部是 `ADD COLUMN ... DEFAULT` 与 `CREATE INDEX IF NOT EXISTS`（对既有数据零破坏），**刻意禁用 `fallbackToDestructiveMigration` 并在 `AppDatabase.kt:58-64` 写明了理由**（"服药历史是不可再生资产，静默清库不可接受"），`MigrationTest` 甚至手工复刻了 v1 的 DDL 再跑生产环境同一段 `migrate()`。这个防线值得保护。
8. **告警自检页的 4 个数据源是真实检测的**（P0-6 说的是 UI 层没接，不是检测逻辑不存在）；`ReminderSettings.shouldSilence` 的重要提醒穿透逻辑有 4 条针对性单测。

### 状态管理与 Compose

9. **8 个 ViewModel 全部用 `collectAsStateWithLifecycle`**，没有一处裸 `collectAsState`；全部通过绑定到 nav back stack entry 的 `viewModel(factory = ...)` 构造，**没有一处"Composable 里 new ViewModel"**；6 处带参 ViewModel 的路由参数从 `arguments` 重新取，**进程重建后既不泄漏也不丢状态**。
10. **`remember` 用法无反模式**。全部 `remember { mutableStateOf<不可变类型>() }`，没有 `remember { mutableStateOf(mutableListOf()) }` 这类经典错误。
11. **LazyColumn key 完全稳定**：用业务主键（`slot.id` / `medication.id`），且 pending/completed/skipped 三组 key 空间天然不相交，不存在重复 key 崩溃风险。
12. **12 个 `Screen` 全部在 `NavHost` 注册，零遗漏零多余**（`Screen.kt` ↔ `AppNavigation.kt` 逐条对齐）；底部导航的 `popUpTo(startDestination){saveState} + restoreState` 写法正确。
13. **`HomeTabHeader` 抽象的方向正确**，且把 `actionContentDescription` 设为**非空参数**，把无障碍标注从"靠自觉"变成编译期约束。

### 产品与文案细节

14. **`0/0` 不谎报 100% 做得非常彻底**。`StatsScreen.kt:140-144`、`ProgressScreen.kt:157-181`、`:225-243` 三处独立判断 `decided > 0`，分母为 0 显示「—」并给解释文案；`StatsEngine.kt:222-224` 甚至把"0/0=100% 是统计空集约定、但对用户是误导"写进了注释。**这是医疗类 App 最容易被忽视的信任问题，这里做对了。**
15. **"不冤枉用户"的原则贯穿全局**：EXPIRED 有 2 小时宽限（`AlarmReconciler.kt:21`）；新建药品时若所有时点已过会自动把 `startDate` 顺延到明天（`AddEditMedicationViewModel.kt:299-306`）并配 ⏰ 提示；`DoseTrackingService.kt:153` 明确"服药是不可否认的事实，绝不因库存不足阻止记账"。这些细节加起来是产品成熟度的真实体现。
16. **空态的"下一步"意识明确**，且刻意区分"全新用户"与"这一天恰好没排班"（`TodayUiState.hasAnyMedication` 字段就是为这个存在的）。`FirstRunGuideCard` 里"所有数据只存在本机，不联网、不上传"的隐私承诺与产品定位一致。
17. **危险操作有二次确认，且用更好的替代方案拦截破坏性操作**：删除药品的确认文案明确引导"如只是不再服用，建议使用「停药归档」保留历史数据"。
18. **上一轮审查点名的假数据问题确实修好了**，且修的方式是对的 —— 不是把字面量换成另一组字面量，而是补了 `StatsEngine.aggregateBreakdowns` 等可单测的纯函数 + DAO 聚合查询 + 真库测试，并留下了"为什么这么改"的 KDoc（`StatsViewModel.kt:72-79`、`ProgressViewModel.kt:56-63`）。
19. **冷启动播种被刻意禁止并写明理由**（`TodayViewModel.kt:55-59`："凭空出现的服药记录会直接污染依从率、库存与统计报表"）。这个判断是对的。
20. **`DeviceAdminReceiver` 之类的高危能力一概没有**，权限清单只有 5 条且全部为该功能必需；无网络库、无图片库、无 DI 框架，README「无任何第三方运行时依赖」的说法经核实成立。

### 工程规范

21. **KDoc 密度高且写"为什么"**。`MedicationAdminService.kt:13-25` 列出了 `insert(REPLACE)` 的两类历史危害；`Notifications.kt:26-30` 解释了为什么夜间静音必须走独立渠道（渠道重要性创建后不可修改）；`SlotProjectionEngine.kt` 与 `StatsEngine.kt` 的口径定义都写在 KDoc 里。符合 AGENTS.md §6 的要求。
22. **`docs/reviews/` 目录体现了"设计阶段先审一轮"的工程纪律**，三份报告（`DESIGN_REVIEW.sbf.md` 68KB、`DESIGN_REVIEW_dsf.md` 25KB、`PLAN-REVIEW-20260927-qwa.md` 20KB）覆盖了方案、架构、UI 三个维度。本次审查发现 P0-1 与 D-7/D-9/D-11/D-14 的偏差，正是因为这批文档提供了明确的对照基准 —— **这个习惯本身很有价值，应保留**。

---

## 八、修复路线建议

### 里程碑 M-A：止血（约 2 天，只动 `core/`，不动 UI，可独立回滚）

| 序 | 项 | 理由 |
| ---: | --- | --- |
| 1 | **P0-1** 闹钟号段碰撞 | 静默漏提醒 + 误取消。产品第一承诺的直接违反。改成 `setData(Uri)` 判重 |
| 2 | **P0-2** 闹钟视野 7 天 → 14 天 + 触发即续期 | 同上。改 `AlarmReceiver` 加续期 |
| 3 | **P0-4** `takeDose` 幂等守卫下沉到事务内 + 条件 UPDATE | 数据正确性，且 M4 出口标准本来就该过 |
| 4 | **P0-3** 库存守恒 + 执行 D-9 允许负库存 | AGENTS.md 红线 + 已拍板决策 |
| 5 | P1-1 undo 不删事实；P1-2 SNOOZED 结算；P1-3 endDate 可清除 | 同一批事务逻辑改动，顺手做掉 |
| 6 | P2-11 receiver 加 `catch`；P2-12 `snoozeDose` 判存在 | 防崩溃，2 行 |
| 7 | 补 3 条不变量测试（号段不相交 / 六入口库存守恒 / 打卡幂等） | 成本极低、收益极高，能把这批改动永久锁死 |

**验收**：`./gradlew testDebugUnitTest` 全绿 + 真机：设置某药「提前 10 分」，等一天确认提前提醒与准点提醒都响且文案可区分（P1-20）；制造库存不足后打卡，用 `dbdump.py` 确认 `SUM(change_amount) == current_stock`；连续 3 天不打开 App 确认第 4 天仍响。

### 里程碑 M-B：UI 正确性（约 2 天，需走查截图）

P0-5（编辑不丢提醒行为）、P0-6（自检页真实检测）、P0-7（统计按单位分组）、P1-7（昨天此时）、P1-8（推迟三态）、P1-9（频次文案抽公共函数 + 补测）、P1-10（剂量校验）、P1-11（日期校验）、P1-14（恢复二次确认）、P1-16 第一步（文案改「已服药」）。

**验收**：`python tools\app_screenshots.py --clear --seed` 跑通且 `manifest.md` 无新增失败项 + **逐张看图** + 模拟器走一遍"改提醒行为 → 编辑药名 → 回详情页确认还在"。

### 里程碑 M-C：可靠性收口（约 3 天）

P1-5（reconcile 改幂等 diff + 孤儿闹钟清理 + 节流）、P1-19（权限撤销自愈）、P2-8（宽限期内补响）、P2-10（receiver 瘦身）、P1-20（提前提醒文案）。

**验收**：**真机连续 21 天零漏提醒**（把 FINAL-PRODUCT §八 的验收时长从 7 天改成 21 天 —— 见 P0-2 末段的教训），矩阵覆盖 Doze / 杀后台 / 重启 / 改时区 / 换时区 / 权限被拒 / 权限被撤销。

### 里程碑 M-D：数据模型改造（需排期，独立 PR）

P1-6（Float → 整数毫单位）。按 AGENTS.md §2 走完整流程：升 version → `MIGRATION_2_3` 带数据换算 → 补 `MigrationTest` → 领域层加换算层。**建议先评估"保留 Float + 季度盘点校准"的替代方案**，把 D-7 决策改写而非动数据层 —— 迁移 5 个 REAL 列的成本与风险可能高于收益。

### 里程碑 M-E：文档与工程卫生（约 1 天）

§5.4 的决策核对表逐条对齐（修订 D-6 / D-11 / D-13 / M-06 / M-10 / §六.4 / §六.5 的文档表述，或补实现）、删除 `SampleDataSeeder.kt`（P3-8）、订正 CHANGES 的失实记录（P3-13）、清理 7 处编译警告（P3-3）、建 `proguard-rules.pro` 占位（P3-1）、补 README 文档索引（P3-10）、标注 `ARCHITECTURE.md` 的废弃章节（P3-11）。

---

## 九、给下一轮审查的 5 条方法论建议

这次审查本身也暴露了流程问题，值得一并记下：

1. **验收标准的时长不得等于被验证机制的边界。** `FINAL-PRODUCT §八` 的"连续 7 天零漏提醒"与闹钟 7 天视野完全相等，导致这个 P0 在结构上不可能被上一轮发现。凡是"连续 N 天"类验收，都应写成"连续 2N 天"或明确"覆盖 ≥ 2 个机制周期"。

2. **"修了 12 个 P0 就收口"是过早的判断。** 上一轮报告的审查范围里没有"闹钟能管多久""闹钟会不会撞车""同一操作连点会怎样"这三个问题 —— 而它们恰好是提醒类 App 的头号事故类型。**下一轮的 checklist 应显式包含"并发/重复操作"和"机制的时间边界"两个维度。**

3. **"局部 UPDATE"不等于"安全"。** P0-5 证明了：只要 SET 列表里有一列是表单没暴露的，问题就与 `insert(REPLACE)` 等价，而隐蔽性更高。**校验方式应从"检查用了什么 SQL"改成"检查 UPDATE 的每一列在 UI 上是否都有对应输入控件"** —— 这可以直接做成一条 lint 规则或架构测试。

4. **测试数量不等于测试有效性。** `AddEditLogicTest` 里 4 项在测 `Int.coerceIn` 和测试体内的复制品，而 P0-1/P0-3/P0-4 这三个 P0 对应的断言一条都没有。**建议把 AGENTS.md 的门禁从"N 项全绿"改成"关键不变量测试全绿"**，并把不变量清单显式列出来（本文档 §7 与 §8-M-A-7 可直接作为起点）。

5. **决策文档要定期与代码对账。** §5.4 的核对表显示 `FINAL-PRODUCT.md` 的 16 条决策里有 **6 条未执行**（D-6/D-7/D-9/D-11/D-13/D-14），而 `README.md` 与 `FINAL-PRODUCT.md` 对"准时率"的优先级还互相矛盾（一个 P2 一个 P0）。文档数量已达 14 份、审查报告 3 份，**缺少一个自动化的"决策 → 实现"追踪表**。建议在 `docs/` 下新增一份 `DECISIONS.md` 作为唯一追踪表，每条决策带"实现状态 + 代码位置 + 最后核对时间"三列。
