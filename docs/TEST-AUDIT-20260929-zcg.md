# 测试审计报告（zcg，2026-09-29）

> **审计时间**：2026-09-29（接续 [CODE-REVIEW-20260929-zcg.md](CODE-REVIEW-20260929-zcg.md)）
> **审计前提**：作者承认注释 / CHANGES / 部分测试可能存在虚假。
> **审计方法**：① 逐文件精读 `app/src/test` 全部 34 个文件（33 个测试类 + 1 个断言辅助）；
> ② 强制重跑全量套件拿真实基线；③ **变异验证 ×4**——故意改坏生产代码跑全量，看哪些测试真的变红。
> **证据纪律**：结论只基于测试体本身的可失败性 + 变异实验结果，不基于任何 KDoc 或 CHANGES 的陈述。

---

## 〇、总裁定

**这套测试的核心部分是真的，且有真实的杀伤力。** 33 个测试类中 30 个为真测试；
唯一的"充数"集中在 `AddEditLogicTest` 的 4 项（恰是项目文档自己点名的 P1-12 反例，至今未清理），
另有两处"影子实现"式弱点。四个变异全部被杀，I1/I3/I4/I9/I10 的守卫网络经实测有效。

| 验证项 | 结果 |
| :--- | :--- |
| 真实基线（`--rerun-tasks`，非缓存） | **302 项 / 0 失败 / 0 跳过**，49s 跑完，与门禁宣称一致 |
| 逐文件精读 | 33 个测试类全部读完；30 真 / 1 半假 / 2 有影子弱点 |
| 变异 M1：撤销冲正判据恒否（I4 失效） | **被抓住**：10 项变红（UndoRoundTrip 6 + InventoryLedger I4 3 + DoseTrackingService 1） |
| 变异 M2：`markCompletedIfOpen` 去掉状态守卫（I3 失效） | **被抓住**：3 项 I3 变红（含 0..6 穷举） |
| 变异 M3：`updateProfile` 越权写 `is_archived`（I9 失效） | **被抓住**：3 项变红（DAO 层 + 哨兵穷举 C1 + 服务场景，三层都在） |
| 变异 M4：`alarmUri` 去掉 kind 段（I10 失效） | **被抓住**：AlarmIdentityTest 4 项变红 |

**对 CHANGES-20260929 所称"新增 5 个测试文件共 43 项"的核对**：五个文件均在，
实测项数 9 + 7 + 11 + 5 + 11 = 43（宣称的分文件数 10 与 12 两处各差 1，总数一致）。
这些测试**存在、真实、且各自针对文档所述缺陷写了能失败的回归**——CHANGES 这一部分不是虚假的。

---

## 一、变异验证详情（4/4 全灭）

| # | 变异 | 改动点 | 变红的测试 | 判定 |
| :--: | :--- | :--- | :--- | :---: |
| M1 | `undoDose` 冲正判据改为恒否（`if (false && net < 0)`） | `DoseTrackingService.kt:210` | `打卡与撤销往返两轮后账面精确回到原值`、`往返三轮`、`每轮往返都留下一条冲正流水`、`第二轮用不同剂量时`、`追踪开启时打卡扣了`、`不重新打卡时重复撤销`、`跳过的槽位也可以撤销`（UndoRoundTrip ×7）+ `I4 撤销后账面精确回到原值`、`I4 重复撤销不会二次冲正`、`I4 台账里留下冲正流水`（InventoryLedger ×3）+ `takeDoseAndUndoDose_preservesLedgerInvariantAndRestoresState` | ✅ 杀 |
| M2 | `markCompletedIfOpen` 的 WHERE 去掉 `status IN (...)` | `DoseSlotDao.kt:116` | `I3 同一槽位连打卡 3 次只扣一次库存`、`I3 打卡后重复调用不产生第二条事实`、`I3 任意重复次数下…以首次剂量为准`（0..6 穷举，每次换剂量） | ✅ 杀 |
| M3 | `updateProfile` 的 SET 加一行 `is_archived = 0` | `MedicationDao.kt:190` | `updateProfile_preservesStatusFlagsAndCreatedAt`（AppDatabaseReal）+ `C1 档案编辑不触碰任何非档案列`（哨兵穷举）+ `saveProfile_editDoesNotWipeUnrelatedFields` | ✅ 杀 |
| M4 | `alarmUri` 不再追加 `kind.code` 段 | `AlarmScheduler.kt:105` | `同一槽位的三种闹钟种类互不覆盖`、`Uri 唯一编码四个维度`、`遍历 medId 1 到 10⁴ 身份绝不重复`、`Uri 不含需要转义的字符` | ✅ 杀 |

值得强调的两点：
- **M1 有 10 项独立测试同时变红**——上一轮"二次撤销取错代表事实 / 读当前 isStockTracked"两个 P0 缺陷的修复，被 6 项多轮往返 + 3 项 I4 + 1 项端到端真正钉死，不是文档叙事。
- **M3 的三层防线各断各的**：DAO 层断言、`FieldPreservationInvariantTest` 的逐列哨兵、服务层场景测试互为冗余——删掉任何一层，其余两层仍能抓住同一回归。

实验后所有变异均已 `git checkout --` 还原，工作区 `app/src/main` 已验证干净。

---

## 二、逐文件裁定（33 个测试类）

**裁定口径**：真 = 建立真实被测状态、断言存在可失败路径；充数 = 断言不依赖被测代码（测标准库/测自己）；影子 = 断言逻辑在测试里复刻，不绑定生产代码。

| 测试类 | 项数 | 裁定 | 备注 |
| :--- | --: | :---: | :--- |
| `InventoryLedgerInvariantTest` | 20 | ✅ 真 | I1×4 / I2×2（含前缀和）/ I3×4（含 0..6 穷举换剂量）/ I4×3 / I12×4；余额断言走真库 SUM |
| `UndoRoundTripLedgerTest` | 9 | ✅ 真 | 多轮往返、变剂量冲正、追踪开关中途变化；固定日期，无时钟依赖 |
| `FieldPreservationInvariantTest` | 8 | ✅ 真 | 逐列哨兵 + 编译期强制配对；C1–C8 覆盖全部写命令 |
| `AppendOnlyFactTableTest` | 7 | ✅ 真 | 行为半（逐列相等）+ 静态半（扫源码、剥注释、白名单集合相等）；KDoc 自述初版反射扫描是恒真、已重写——与现状相符 |
| `DoseTrackingServiceTest` | 4 | ✅ 真 | AGENTS 记录的"恒真断言事故"确已修复：现在断言具体余额 18→20 |
| `DoseChangeUpdatesSlotsTest` | 7 | ✅ 真 | 改剂量传播 + 既成槽位不动 + 幂等回归，断言钉死 18/2000/500 |
| `ReconcileScheduleTest` | 13 | ✅ 真 | I5/I6、id 稳定性、投机区、既成事实保护；固定日期 |
| `AlarmReconcilerIdempotencyTest` | 11 | ✅ 真（1 项弱） | 时钟翻转防御真实有效；但"归档不再参与排班"用 `isAtMost(before)` 弱断言，**恰好放过了归档残留槽位**（见 §三.4） |
| `AlarmIdentityTest` | 8 | ✅ 真 | 走生产 `alarmIntent` 而非测试内重写（明确防"影子"）；含旧实现反例与 10⁴ 穷举 |
| `DoseSlotDstServiceTest` | 5 | ✅ 真 | 含一条「换时区不改写既有槽位（**已知限制 非正确性断言**）」——把代码审查 zcg 报告 P2#7 显式钉为已知取舍 |
| `SlotProjectionDstPropertyTest` | 5 | ✅ 真 | jqwik D1–D5，与不变量表对应 |
| `SlotProjectionEngineProperties` | 4 | ✅ 真 | jqwik I5/I6/I7/I8；生成器设计合理（PRN 被刻意排除防恒真） |
| `SlotProjectionEngineTest` | 6 | ✅ 真 | 闰年 2/29、跨月、CYCLE、endDate 截断 |
| `SlotProjectionPauseTest` | 11 | ✅ 真 | 三态 + 与 `isPausedOn` 逐日交叉验证 + 降级方向 |
| `ReminderPauseTest` | 14 | ✅ 真 | 含当天/次日恢复/闰年/跨年/解析失败降级 |
| `ReminderSettingsIsolationTest` | 13 | ✅ 真 | I9 隔离 + `ensureDefaults` 幂等 + 读模型兜底 |
| `ReminderSettingsTest` | 5 | ✅ 真 | 纯函数边界 |
| `MedicationAdminServiceTest` | 13 | ✅ 真 | startDate/endDate 保全、times 替换、`deductStock` 透传 |
| `AppDatabaseRealTest` | 11 | ✅ 真 | PRAGMA 断言列不存在；AGENTS 记录的第二起假断言事故的结构性修复属实 |
| `BackupRoundTripTest` | 13 | ✅ 真 | 字节级往返、PK 保全、三态 pausedUntil、手写 V1、事务回滚 |
| `BackupFieldPreservationTest` | 3 | ✅ 真 | 手写期望（防"对称遗漏"往返失效），全列非默认值 |
| `BackupResilienceTest` | 5 | ✅ 真 | 全 kind 遍历 + 预览/恢复判据同源 |
| `DaoUnitScaleTest` | 5 | ✅ 真 | 毫单位量纲 + 双查询口径一致 |
| `StatsDaoAggregationTest` | 6 | ✅ 真 | 直插槽位后验证 GROUP BY |
| `StatsEngineAggregationTest` | 15 | ✅ 真 | runway 钉死 24/70 天等精确值 |
| `StatsEngineTest` | 4 | ✅ 真 | — |
| `DoseTest` | 11 | ✅ 真（1 项演示） | `长期累加不漂移` 的浮点对照断言 `isAtMost(547.5+ε)` 恒真，属演示性质，不影响前半的真实断言 |
| `MedicationOverviewTest` | 7 | ✅ 真 | 量纲契约 |
| `QuantityTest` | 11 | ✅ 真 | KDoc 诚实记录了"凭想象写断言当场变红"的教训 |
| `ReconcileWorkerTest` | 7 | ✅ 真 | workSpec 参数断言；KDoc 诚实解释为何不测 `doWork()` |
| `StatsUnitGroupingTest` | 6 | ⚠️ 影子 | `groupByUnit` 是测试内**复刻**的生产逻辑副本（注释自称"与 StatsViewModel 保持一致"）——`StatsViewModel` 真实分组坏了它照样绿。断言本身可失败，但守的是影子 |
| `AddEditLogicTest` | 9 | ❌ 4 项充数 | `intervalDays_clampsTo2To30` / `cycleDays_clamps` 断言的是 **Kotlin 标准库 `coerceIn`**，未调 ViewModel 的 `onXxxChange`；`daysOfWeek_toggle…` 在测试里**内联复刻**开关逻辑；`fivePolicyTypes_areAllReachableFromForm` 只断言枚举自身。恰是文档自认的"**P1-12 的 4 项反例**"，至今仍在套件里计入 302 项 |

水分合计：**约 4 项恒真/影子 + 2 处局部弱点**，占 302 项的 1.3%，且**全部不在承重区**
（承重的 I1/I3/I4/I9/I10/I11 与备份、对账、闹钟身份全部是真测试）。

---

## 三、审计发现的问题

### 1. `AddEditLogicTest` 的 4 项充数测试仍在计入门禁（P2，门禁诚信）

文档（AGENTS / REMINDER-DOMAIN-REDESIGN）把"P1-12 的 4 项反例"当历史教训引用，
但这 4 项测试**至今没有被重写或删除**，仍在为"302 项"贡献数字。
其中两项连被测对象都没有（`1.coerceIn(2,30) == 2` 测的是标准库）。
这正是"项数=门禁"这一做法的反面教材：删掉无用测试会降项数，于是没人删。

**修法**：重写为调用 `AddEditMedicationViewModel.onIntervalDaysChange / onCycleDaysChange / onToggleDay`
的真实状态断言；或直接删除并把项数降下来。门禁的口径应当是"守的不变量清单"，不是项数。

### 2. `StatsUnitGroupingTest` 守的是影子副本（P3）

测试内私有的 `groupByUnit` 复刻了 `StatsViewModel.buildState` 的分组逻辑。
`AlarmIdentityTest` 的 KDoc 自己写过这条铁律："测试自己拼一份实现，守的是测试里的影子"。
修法：把分组逻辑提成 `StatsEngine` 的纯函数（如 `groupByUnit`），UI 与测试共用同一份。

### 3. `AlarmReconcilerIdempotencyTest` 的归档断言过弱（P3，与代码审查 P2#4 互证）

`归档的药品不再参与排班` 用 `openSlotsOf(medId).size isAtMost before`——归档后**残留的旧槽位**
（今日清单幽灵待办）完全在断言容差内。这条测试事实上把缺陷行为固化成了"通过"。
修法：归档后断言 `openSlotsOf(medId)` 按日期集合收窄到空（前提是先修代码审查 P2#4 的归档清理）。

### 4. `DoseTest` 一条演示型断言（P4，可不改）

`长期累加不漂移` 后半的 `floatBalance isAtMost(547.5f + 0.0001f)` 是恒真演示
（1.5f 是可精确表示的浮点，365 次累加实际无误差）。建议删去后半或改成真正的对照实验。

### 5. 测试盲区与代码审查发现的对应关系（承重缺口确认）

以下代码审查（zcg 报告）发现的缺陷，**确认无任何既有测试覆盖**——即它们不是"有测试但假"，而是"没有测试"：

| 代码审查发现 | 测试盲区证据 |
| :--- | :--- |
| P1：同一计划重复时点静默吞并 | `SlotProjectionEngineProperties` 生成器只造单一时点；`MedicationAdminServiceTest` / `PolicyEndDateTest` 均无重复时点用例 |
| P2#2：`DoseActionReceiver` 协程无 catch | 全工程无该类的任何测试 |
| P2#3：今日页 `events` 通道未接线 | 无 UI 层测试（本就无 UI 测试基建） |
| P2#4：归档残留槽位 | 见 §三.3，现有断言反而放行 |
| P2#5：EXPIRED 确认按钮必失败 | 无对应测试 |
| P2#6：`setStockTracking` 负余额条件错 | `InventoryLedgerInvariantTest` 只测 `current == 0` 建档与正账校准，未测负账 + 初值 |
| P3#12：备份校验三类重复键缺口 | `BackupResilienceTest` 的 dirty map 遍历的是**已存在的 kind**，"备份里有重复槽位主键但无 kind 覆盖"自然不在遍历内 |

### 6. 对代码审查报告 P2#7 的定性修正

`DoseSlotDstServiceTest` 有一条显式命名为「**当前行为** 换时区重算不会改写既有槽位（**已知限制 非正确性断言**）」
的测试，KDoc 写明"这个行为有一个尚未处理的产品后果……将来决定处理出差跨时区，这条会红"。
即代码审查报告的 P2#7（时区变更后 `scheduled_ts` 陈旧）**在测试层已被如实标注为已知限制**，
只是未进入 `CHANGES` 的"尚未处理"清单。缺陷本身成立，但"作者隐瞒"的嫌疑可以排除——这条是诚实的技术债标注。

---

## 四、结论

1. **"测试可能是假的"在这一项目里部分成立，但假的部分不在要害**：
   可确认的水分是 `AddEditLogicTest` 的 4 项（文档自己承认过的 P1-12 反例，未清理）
   加两处影子实现/弱断言。承重的库存守恒、幂等、撤销、字段保全、闹钟身份、备份往返、对账幂等
   全部由真测试把守。
2. **四个变异全部被杀**，且每次变红的正是"应该红"的那些测试——这不是巧合能造出来的，
   说明这些测试与其守的实现之间有真实的因果绑定。
3. **302 这个数字含约 1.3% 水分**，但门禁的实质强度（不变量覆盖 + 变异可杀性）是真的。
   建议把 AGENTS 的门禁表述从"项数"切换为"§4 不变量清单 + 变异抽查"，与文档已有立场对齐。
4. 后续动作建议：① 清理/重写 `AddEditLogicTest` 的 4 项；② `StatsUnitGroupingTest` 与
   `StatsViewModel` 共用一份纯函数；③ 为代码审查报告的 P1 与 P2#2/#4/#5/#6 各补一条能失败的测试
   （修复前先红、修复后转绿，作为验收凭据）。

---

## 五、追加记录（2026-09-29 当日）：建议 ①③ 已落地

**当前套件状态：307 项 / 5 红（5 红全部是"修复前先红"的预期状态，其余 302 全绿）。**

### 已重写：`AddEditLogicTest`（充数 4 项 → 真测试 4 项）

整个文件转为 Robolectric，四项充数测试改为调用 `AddEditMedicationViewModel` 真实 setter
（`onIntervalDaysChange` / `onCycleDaysChange` / `onCycleOffDaysChange` / `onToggleDayOfWeek` /
`onPolicyTypeChange`）。其中 `daysOfWeek_toggle` 重写时还当场抓到一个"影子测试看不到的事实"：
表单初始自带 `[1,3,5]`，旧影子测试从空列表自娱自乐，根本测不到真实的加选排序行为。

### 新增：5 条"修复前先红"测试（修复后应转绿，作为验收凭据）

| 测试 | 守的缺陷 | 当前红因（实测） |
| :--- | :--- | :--- |
| `DuplicatePolicyTimeTest.同一计划里两个相同时点 必须被拒绝而不是静默吞并` | 代码审查 P1 | 保存静默成功，`error == null` |
| `DoseActionReceiverTest.通知栏快捷操作的协程体必须兜住 Throwable` | P2#2 | 源码无 `catch (...: Throwable)`（源码扫描模式，同 `AppendOnlyFactTableTest` 静态半） |
| `AlarmReconcilerIdempotencyTest.归档后 该药名下不得残留任何开放槽位` | P2#4 | 归档后残留整窗 15 条 PENDING |
| `DoseTrackingServiceTest.逾期槽位允许补记已服` | P2#5 | `takeDose(EXPIRED)` 返回 false |
| `InventoryLedgerInvariantTest.开启追踪时账面为负 给出初值必须补差额而不是追加全额` | P2#6 | 终账 28.0 ≠ 30.0（追加全额 +30 而非差额 +32） |

注意事项：
- P2#2 用形状扫描而非行为注入，是因为逼出该异常必须污染 `AppDatabase` 静态单例（AGENTS §3 明令避免）；
  修法必须与 `AlarmReceiver` 同款 `catch (t: Throwable)`，用 `Exception` 会让测试继续红（有意为之）。
- P1 与 P2#5 的测试各自**钉了一种修法**（P1 = 拒绝重复时点；P2#5 = 放开 EXPIRED 补记已服）。
  若产品拍板选另一方案（P1 = 同刻合并剂量；P2#5 = UI 隐藏按钮），请按测试 KDoc 的指引**改写**断言，
  不要删测试。
- 5 红期间门禁不绿。修复对应缺陷后各自转绿；全部修复后恢复"全绿"基线（届时 307 项 / 0 红）。

---

## 六、收尾记录（2026-09-29 10:36）：5 个缺陷已修复，门禁恢复全绿

**当前套件状态：308 项 / 0 失败**（修复 + 新增 1 条 `BackupFieldPreservationTest.归档位原样导出`）。
`compileReleaseKotlin` 通过；**模拟器定点实测已完成**（归档↔恢复双向、重复时点报错、逾期补记均按预期工作，
详见 `CHANGES-20260929.md` 的"模拟器实测"表）。

5 条红测试全部转绿，验收路径与"红→绿"对照：

| 测试 | 红因（修复前） | 绿因（修复后） | 修法落点 |
| :--- | :--- | :--- | :--- |
| `DuplicatePolicyTimeTest` | 重复时点静默成功 | `require` 拒绝，事务整体回滚 | `MedicationAdminService.saveReminderPolicy`（+ 两个表单 VM 前置校验 `DUPLICATE_TIME_ERROR`，防 UI 崩溃） |
| `DoseActionReceiverTest` | 源码无 `catch(Throwable)` | 协程体补齐异常围栏 | `DoseActionReceiver` |
| `AlarmReconcilerIdempotencyTest.归档后…` | 残留 15 条 PENDING | 归档药按"无计划"投影 + 对账器归档清扫 | `DoseTrackingService.reconcileSchedule` + `AlarmReconciler.rescheduleAll` 2b |
| `DoseTrackingServiceTest.逾期槽位允许补记已服` | `takeDose(EXPIRED)` 返回 false | 守卫加入 EXPIRED，补记/撤销/记账全链路可用 | `DoseSlotDao.markCompletedIfOpen` |
| `InventoryLedgerInvariantTest.开启追踪时账面为负…` | 终账 28.0 ≠ 30.0 | 分支条件 `== 0`，负账走校准差额（+32） | `DoseTrackingService.setStockTracking` |

**修复过程的旁证**：归档语义修复让 2 个旧测试当场失败（`BackupFieldPreservationTest` ×2、
`FieldPreservationInvariantTest` C5/C8）——它们当年把 `isArchived=true` 当"非默认哨兵值"随手种在
fixture 里，如今"归档药不再物化排班"这一新语义立刻被它们抓到。fixture 已按新语义修正
（`BackupFieldPreservationTest` 另补归档位专测；C8 取消归档并显式传哨兵 `updated_at` 保持逐列可比）。
这正是本轮"测试要有真实杀伤力"的最好注脚：行为真的变了，旧断言真的会叫。
