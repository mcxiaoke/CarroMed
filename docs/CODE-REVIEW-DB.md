# CarroMed 数据正确性与业务逻辑静态审查报告（CODE-REVIEW-DB）

- 审查日期：2026-09-29（Asia/Shanghai，UTC+8）
- 审查对象：CarroMed（Android，Kotlin + Jetpack Compose + Room）用药提醒 App
- 审查性质：**只读静态审查**。未修改任何业务代码，未执行 git 操作，未触碰 `.git`。

---

## 1. 审查范围与方法

### 1.1 范围

- 主代码：`app/src/main/kotlin/com/mcxiaoke/carromed/` 下全部 67 个 Kotlin 文件（约 1.35 万行），覆盖
  数据层（8 实体、AppDatabase、Converters）、7 个 DAO、领域引擎（SlotProjectionEngine、StatsEngine）、
  领域服务（DoseTrackingService、MedicationAdminService）、闹钟通知层（8 文件）、14 个 ViewModel、
  导出/备份（DataExporter、BackupFormat）。
- 调试源集：`app/src/debug/`（DevDataReceiver、DevSampleDataSeeder）。
- 测试代码：`app/src/test/` 下 35 个测试文件（仅用于排查恒真断言与覆盖缺口，未作为"代码正确"的证据）。

### 1.2 方法论（本次结论的可信度基础）

1. **不采信 `docs/` 下的 changes 文档、代码注释/KDoc、git 提交记录**。它们只作为"作者意图"的线索，
   凡报告中的结论一律以逐行读到的实际代码为准，并追踪完整调用链
   （UI → ViewModel → Service → DAO → Entity；Alarm → Receiver → Service）。
2. 每个问题均给出：①文件相对路径；②行号/区间；③代码原文短引用；④触发所需的具体输入/操作/时序；
   ⑤实际后果；⑥为什么是 bug（对照其它代码路径或数据约束证明）。
3. 分级口径：
   - **A = 确认的 bug**：代码路径闭合，能自证必然/高概率出错；
   - **B = 高风险隐患**：逻辑确有缺陷，但需要特定时序/条件，或需产品确认意图；
   - **C = 健壮性/规范建议**：死代码、口径不一致、注释失真、极端边界。
4. 经"8 个分片深读 + 2 个对抗性第二轮验证 + 组织者逐条复核"三轮交叉。

### 1.3 总体结论

| 等级 | 数量 |
| :-- | :-- |
| A（确认 bug） | **0** |
| B（高风险隐患） | **9** |
| C（健壮性/规范建议） | **40** |

**未发现路径闭合、可自证必然出错的 A 级数据损坏 bug**；库存守恒等核心不变量在当前代码上由结构保证
（见第 4 节）。但发现 9 条 B 级隐患，集中在：并发结算守卫缺失、补录/剂量为 0 的口径漏洞、
时区变更不重算、通知残留、REVERTED 误展示等，建议在发布前处理。

---

## 2. 问题汇总表

### 2.1 B 级（高风险隐患）

| 编号 | 所在层 | 标题 | 文件:行号 |
| :-- | :-- | :-- | :-- |
| B-01 | DAO/闹钟 | `markExpired` 无状态守卫且结算段无事务，可把已打卡槽位覆写成 EXPIRED | `core/data/dao/DoseSlotDao.kt:313-314`；`core/alarm/AlarmReconciler.kt:89-96` |
| B-02 | 服务/VM | 手动补录不回填当日槽位，补记漏打卡后依从率仍算漏服 | `core/domain/service/DoseTrackingService.kt:239-272`；`ui/screen/manual/ManualDoseViewModel.kt:153-160` |
| B-03 | UI/VM | 时点剂量框清空静默归 0 且可保存，打卡扣 0、库存永不减少 | `ui/screen/edit/AddEditMedicationScreen.kt:763`；`ui/screen/edit/AddEditMedicationViewModel.kt:280-298` |
| B-04 | DAO/服务/闹钟 | 跨时区或改时后未来槽位 `scheduled_ts` 永不重算，闹钟按错误的本地时刻响 | `core/data/dao/DoseSlotDao.kt:64-71`；`core/domain/service/DoseTrackingService.kt:463-475`；`core/alarm/BootReceiver.kt:47` |
| B-05 | 闹钟/VM | 过期结算、暂停、归档时不撤已弹出的托盘通知 | `core/alarm/AlarmReconciler.kt:89-96`；`ui/screen/detail/MedicationDetailViewModel.kt:156-180` |
| B-06 | VM/UI | 已撤销（REVERTED）服药在详情"服药历史"显示为绿色"已服" | `ui/screen/detail/MedicationDetailViewModel.kt:104`；`ui/screen/detail/MedicationDetailScreen.kt:815-820` |
| B-07 | VM | "本机备份"检查在主线程做文件读取 + JSON 全量解析，可能 ANR | `ui/screen/settings/SettingsViewModel.kt:195-210` |
| B-08 | 数据层 | 枚举转换器遇未知值静默替换为默认枚举，Room identity 校验不覆盖枚举名 | `core/data/converter/AppConverters.kt:17-40` |
| B-09 | 数据层 | `fallbackToDestructiveMigration()` 实际处于启用状态，与项目规则声称相反 | `core/data/AppDatabase.kt:143` |

### 2.2 C 级

见第 3 节（40 条，表格化）。

---

## 3. C 级问题（健壮性 / 规范建议）

| 编号 | 所在层 | 标题 | 文件:行号 |
| :-- | :-- | :-- | :-- |
| C-01 | 服务 | `setStockTracking` 建档判据 `current <= 0` 过宽，应为 `current == 0`；负余额重开且给正初值时会少记差值（当前生产 UI 不可达，详见注） | `DoseTrackingService.kt:317` |
| C-02 | DAO | `updateStatus` 无状态守卫、不清 snooze，生产零调用，属复活型 footgun | `DoseSlotDao.kt:~101` |
| C-03 | DAO | `DoseSlotDao.update`（@Update 全实体）生产零调用，陈旧快照可整行覆盖 | `DoseSlotDao.kt:~34` |
| C-04 | DAO | `MedicationDao.update`（@Update 全实体）生产零调用，是整行覆盖旧入口残留 | `MedicationDao.kt:~64` |
| C-05 | DAO | `SchedulePolicyDao.updatePolicy`（@Update）绕过 version 递增与失活语义，零调用 | `SchedulePolicyDao.kt:~27` |
| C-06 | DAO | `DoseSlotDao` 单条 `insert(REPLACE)` 零调用；命中唯一键会换 id 致闹钟身份漂移 | `DoseSlotDao.kt:~19` |
| C-07 | 服务 | `ensureInitialStockLedger` / `enableStockTrackingIfNeeded` 为死代码，KDoc 引用已删的 `current_stock` | `MedicationAdminService.kt:261-284` |
| C-08 | 引擎 | `StatsEngine.calculateAdherence`（旧双套实现）无生产调用方；`sumDoseByDate` 名实不符且零调用 | `StatsEngine.kt:34-75, 109-110` |
| C-09 | 服务 | `refillStock` 绕开 `appendLedger` 内联重算 balanceAfter（结果当前正确，DRY 维护债） | `DoseTrackingService.kt:352-364` |
| C-10 | VM | 四个表单保存协程主体无 try/catch，DB 异常直接闪退（仅闹钟重排包了 runCatching） | 各 ViewModel `launch{}` |
| C-11 | VM/UI | 防抖靠异步 isSaving，同一帧双击可重复提交，新增药品可能插两行 | `AddEditMedicationViewModel.kt:301`；`AddEditMedicationScreen.kt:111` |
| C-12 | VM/UI | "全部时点过期则起始日顺延明天"，但黄条文案称"今天这剂会记逾期"，行为与文案相反 | `AddEditMedicationViewModel.kt:309-313`；`AddEditMedicationScreen.kt:795-808` |
| C-13 | VM | 库存页预警线直调 DAO、无 `coerceAtLeast(0)`；非法输入静默回退旧值仍提示"已保存" | `InventoryViewModel.kt:205-213` |
| C-14 | VM | INFO_ONLY 模式预警线字段隐藏，但其进页面快照仍随保存写回（陈旧 read-modify-write） | `AddEditMedicationViewModel.kt:176,330` |
| C-15 | VM | PRN 是否需要时点，AddEdit（要求非空）与提醒设置（允许空）两入口口径不一致 | `AddEditMedicationViewModel.kt:295`；`ReminderSettingsViewModel.kt:214` |
| C-16 | 模型/VM | 数字输入无上限，`Dose.of` 的 `Math.round(value*1000)` 可 Int 静默溢出为负 | `Dose.kt:52` |
| C-17 | DAO | `savePolicyWithTimes` 不清理旧策略的 policy_times，留孤儿行并随导出累积 | `SchedulePolicyDao.kt:54-61` |
| C-18 | UI | 剂量/库存框用 `KeyboardType.Number` 无小数点，0.5 片等半剂量无法录入（与补货页允许小数不一致） | `AddEditMedicationScreen.kt:237,768`；`RefillViewModel.kt:61` |
| C-19 | 数据层 | 字符串列表转换器 round-trip 裂缝：`|||` 分隔符可冲突、单空串元素丢失 | `AppConverters.kt:43-48` |
| C-20 | 数据层 | `toIntList` 静默丢弃不可解析 token；实体不约束星期取值范围（0、8 可落库） | `AppConverters.kt:55-56` |
| C-21 | VM | UI 用 `getRecordBySlotId`（id ASC LIMIT 1）取展示事实，撤销后重打会取到最早 REVERTED（仅展示瑕疵） | `TodayViewModel.kt:118`；`ProgressViewModel.kt:129` |
| C-22 | DAO | DoseRecord/Medication/ReminderSettings 仍公开 `insert(REPLACE)` 入口，当前调用图安全但未来复用可复活整行覆盖 | 各 DAO |
| C-23 | VM | 删除药品不主动取消已注册闹钟（级联删行先于对账快照），到点空唤醒、不误发通知 | `MedicationDetailViewModel.kt:182-189`；`AlarmReconciler.kt:79` |
| C-24 | VM | 进展页"今天/近 7 天"在 VM 构造时固化，跨午夜不刷新 | `ProgressViewModel.kt:74-75,85` |
| C-25 | VM | 手动补录 save 无同步重入守卫（UI 按钮已禁用，残余一帧竞态可写两条双扣） | `ManualDoseViewModel.kt:132-150` |
| C-26 | VM | 今日页对 SKIPPED 槽位循环内逐条 `getRecordBySlotId`（N+1，且 UI 未使用该值） | `TodayViewModel.kt:116-120` |
| C-27 | VM | 详情页为一次性非响应式读取，他页改动后返回本页不自动刷新 | `MedicationDetailViewModel.kt:71-132` |
| C-28 | VM/引擎 | 库存可用天数对 INTERVAL 用 `round(7/n)`、CYCLE 硬编码 5，与真实排班偏差约 ±14%（仅展示） | `MedicationDetailViewModel.kt:135-145`；`StatsEngine.kt:230-242` |
| C-29 | 实体 | `daysUntilResume` 对已过期暂停返回 0 而非 null，语义含糊（pauseDescription 已拦住，UI 安全） | `ReminderSettingsEntity.kt:96` |
| C-30 | 实体 | `dose_slots.policy_id` 无外键、无索引、无设计说明（与有意悬空的 slot_id 不同） | `DoseSlotEntity.kt:60-61` |
| C-31 | 模型 | `MedicationOverview` 的 `reminderSettings` 默认参数是走不到的死默认值，建议删除 | `MedicationOverview.kt:41` |
| C-32 | 数据层 | `MIGRATION_1_2` 目标列在 v4 已迁出、且 2→5 无迁移会被重建，属死代码；KDoc"旧安装不崩"与实际清库不符 | `AppDatabase.kt:94-109` |
| C-33 | 实体 | KDoc 引用已删列 `current_stock`、误称 MedicationOverview 为"视图"（实为 JOIN POJO） | `InventoryTransactionEntity.kt:15`；`MedicationEntity.kt:57` |
| C-34 | 调试 | 演示数据 med2/med3 今日 COMPLETED 但无对应 TAKEN_DEDUCT 流水，"打卡了库存不减" | `DevSampleDataSeeder.kt:219-267` |
| C-35 | 调试 | CLEAR 用 `clearAllTables()`，是否复位 sqlite_sequence（自增 id）待核实；不影响功能 | `DevDataReceiver.kt:48` |
| C-36 | 数据层 | `coerceInputValues` 注释称未知枚举降级，但 `txType` 无默认值会整份拒绝（拒绝安全，注释不符） | `DataExporter.kt:154`；`BackupFormat.kt:163` |
| C-37 | 数据层 | 校验未覆盖 reminder_settings / app_settings 主键重复，associateBy/REPLACE 静默保留后者（仅手工编辑触发） | `DataExporter.kt:548-560` |
| C-38 | 引擎 | runway 两分支对 `minStockAlert=0` 口径不一致（零消耗路径多一道 `>0` 守卫） | `StatsEngine.kt:91,96` |
| C-39 | 服务 | `intervalDays.coerceIn(1,30)` 对 >30 的输入静默钳为 30，无提示 | `MedicationAdminService.kt:224` |
| C-40 | 引擎 | 时间点解析失败回退 08:00 计算 epoch，但 `scheduled_time` 仍保留坏串，键与 Uri 携带坏值 | `SlotProjectionEngine.kt:101-112` |

> **C-01 注（与 B-01 原始评估的关系）**：建档分支在 `current=-5000、target=10000` 时只追加 +10000、
> 终值 5000（期望 10000），算术缺陷属实；但第二轮独立追踪三个生产调用点
> （AddEdit 仅新建 current=0、Inventory 回传当前显示余额 target==current、Refill 不传初值），
> 当前无任何 UI 路径能在负余额时传入正初值，故定为 C（潜伏）而非 B。

---

## 4. B 级问题详细分析（每条含 6 要素）

### B-01 `markExpired` 无状态守卫且结算段无事务，可把"刚打卡成功"的槽位覆写成 EXPIRED 且不可撤销

1. **位置**：`core/data/dao/DoseSlotDao.kt:313-314`；调用点 `core/alarm/AlarmReconciler.kt:89-96`。
2. **代码原文**：
   ```kotlin
   // DoseSlotDao.kt:313
   @Query("UPDATE dose_slots SET status = 'EXPIRED', actual_taken_ts = NULL, snooze_until_ts = NULL WHERE id = :slotId")
   suspend fun markExpired(slotId: Long): Int
   ```
   ```kotlin
   // AlarmReconciler.kt:89-96（本段不在任何 withTransaction 内）
   val staleSlots = db.doseSlotDao().getStaleOpenSlots(cutoff, cutoff)
   val expiredCount = staleSlots.count { stale ->
       if (db.doseSlotDao().markExpired(stale.id) == 0) return@count false
       stale.identity().cancelAll(context)
       true
   }
   ```
3. **触发时序（并发交错）**：
   - T0：对账协程 `getStaleOpenSlots` 取快照，槽位 X 为 PENDING 且 `scheduled_ts < now-2h`；
   - T1：用户（或通知按钮/手表）对 X 打卡，`takeDose` 事务提交：X=COMPLETED、写 dose_record、库存已扣；
   - T2：对账协程恢复，逐条执行 `markExpired(X)`——SQL **无 `status IN (...)` 守卫**，无条件把
     COMPLETED 改写成 EXPIRED 并清空 `actual_taken_ts`。快照读取与逐行结算之间穿插了
     `cancelAll` 的 AlarmManager 调用（真实挂起点），窗口非微秒级。
4. **实际后果**：
   - 用户确实服药，X 却显示 EXPIRED（漏服），依从率把该次计为漏、已服时刻被抹空；
   - 此时 dose_record 仍为 COMPLETED，造成"累计消耗（按 record）"与"依从率（按 slot）"对同一动作口径矛盾；
   - **无法撤销**：`revertToPending` 守卫为 `status IN ('COMPLETED','SKIPPED')`（DoseSlotDao.kt:~160），
     X 已是 EXPIRED ⇒ `undoDose`（DoseTrackingService.kt:190）直接返回 false。库存已扣、误打卡不可回退。
5. **为什么是 bug**：同文件 `markCompletedIfOpen`/`markSkippedIfOpen`/`snoozeSlot`/`revertToPending`/
   `updateDerivedColumns` 全部把幂等锚点下沉到 `WHERE status IN (...)`，唯独 `markExpired` 没有；
   其 KDoc（DoseSlotDao.kt:311）声称"受影响行数 0 表示已被别的路径结算过（天然幂等）"，
   该保证只对"上一次 markExpired"成立，对快照之后的 takeDose/skipDose 写入不成立。
6. **定级与修复**：需并发时序，定 B。修法：WHERE 补 `AND status IN ('PENDING','SNOOZED')`；
   或将快照+结算整体包进 `db.withTransaction`。

### B-02 手动补录不回填当日槽位，补记漏打卡后依从率仍算漏服

1. **位置**：`core/domain/service/DoseTrackingService.kt:239-272`；调用 `ui/screen/manual/ManualDoseViewModel.kt:153-160`。
2. **代码原文**：
   ```kotlin
   val record = DoseRecordEntity(slotId = null, medicationId = medicationId, actualTs = actualTs,
       doseTaken = Dose.of(doseAmount).milli, status = RecordStatus.COMPLETED,
       isRetrospective = isRetrospective, note = note)
   val recordId = recordDao.insert(record)
   if (deductStock && medication.isStockTracked) { appendLedger(... -Dose.of(doseAmount) ...) }
   ```
   全程**不查询、不更新该药品当日的 PENDING/EXPIRED 槽位**。
3. **触发时序**：某药 08:00 有 PENDING 槽位，用户 11:00 才想起来 → 进"手动补录"选药、时间选 08:00 保存：
   - 一条 `slotId=null` 的 COMPLETED 事实写入（库存照扣）；
   - 08:00 那个 PENDING 槽位原封不动，到 08:00+2h 被 `markExpired` 结算为 EXPIRED；
   - 而 `markCompletedIfOpen` 只放行 PENDING/SNOOZED，EXPIRED 后也无法再从槽位补打。
4. **实际后果**：这条已补记的服药在进展矩阵/详情依从率里仍计 missed（漏服），用户明明补了、依从率反被压低。
5. **为什么是 bug**：补录页文案为"忘记打卡或未带手机？补记过去任意时刻"，语义覆盖"该打卡没打卡"场景，
   实现却把它当作与计划正交的临时加服（PRN），两者未做槽位匹配。
6. **定级与修复**：**待核实产品意图**——若手动补录定义为纯额外服药则行为正确、需在文案上区分；
   若要支持补记漏打卡，应按 `(medicationId, 当天, 最接近时间的开放/过期槽位)` 命中并 `markCompletedIfOpen`
   并挂上 recordId。在意图确认前定 B。

### B-03 时点剂量框清空静默归 0 且可保存，打卡扣 0、库存永不减少

1. **位置**：`ui/screen/edit/AddEditMedicationScreen.kt:760-769`；校验缺口 `ui/screen/edit/AddEditMedicationViewModel.kt:280-298`。
2. **代码原文**：
   ```kotlin
   OutlinedTextField(
       value = if (slot.dose % 1f == 0f) slot.dose.toInt().toString() else slot.dose.toString(),
       onValueChange = { viewModel.updateTimeSlot(index, dose = it.toFloatOrNull() ?: 0f) },
       keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
   ```
   保存校验只查药名/星期/时点列表非空，**从不校验每个 slot.dose > 0**。
3. **触发时序**：新建/编辑药品 → 某时点卡片"剂量"框全选删除（框内立即显示 0）→ 保存。
4. **实际后果**：`TimeDraft(dose=0)` → `Dose.of(0f).milli=0` → PolicyTimeEntity.doseAmount=0 →
   reconcileSchedule 把未来 14 天该开放槽位剂量刷成 0 → 之后每次打卡追加 `-0` 流水。
   **闹钟照响、打卡记 COMPLETED，但库存永不扣减**，低库存预警与剩余天数全部失真，且因提醒在响用户极难察觉。
5. **为什么是 bug**：同屏"默认单次剂量"解析失败兜底 `1.0f`（AddEditMedicationViewModel.kt:325），
   而时点剂量兜底 `0f`，两处口径不一致；`Dose(0)`（Doses.UNLIMITED）是给"按需/不追踪"的哨兵，
   出现在固定时点上语义错误。对照手动补录页有 `amount<=0` 守卫（ManualDoseViewModel.kt:140），此路径缺失。
6. **定级与修复**：库存守恒恒等式（SUM）本身不被破坏（扣的是 0），但业务语义层数据长期错误，定 B。
   修法：保存前校验每个时点 dose>0，或将空输入回退为默认剂量而非 0。

### B-04 跨时区或改时后未来槽位 `scheduled_ts` 永不重算，闹钟按错误的本地时刻响

1. **位置**：`core/data/dao/DoseSlotDao.kt:64-71`（updateDerivedColumns 只写 dose_amount, policy_id）；
   `core/domain/service/DoseTrackingService.kt:463-475`（diff"留"分支）；`core/alarm/BootReceiver.kt:47`。
2. **代码原文**：
   ```sql
   UPDATE dose_slots SET dose_amount = :doseMilli, policy_id = :policyId
   WHERE id = :slotId AND status IN ('PENDING','SNOOZED')
   ```
   全工程除投影 insert（SlotProjectionEngine.kt:113）与备份恢复回放外，**没有任何 UPDATE 写 scheduled_ts**。
3. **触发时序**：用户在 UTC+8 建每日 08:00 的药（scheduledTs=08:00+08）→ 飞抵其他时区，系统时区变更
   → BootReceiver 收 `TIMEZONE_CHANGED` → `rescheduleAll` → reconcileSchedule 用新 ZoneId 投影出**新 epoch**，
   但业务键 `(scheduled_date, scheduled_time)` 不变 → diff 走"留"分支，仅可能更新剂量/策略（未变则不调）
   → 库内 scheduled_ts 仍是旧 epoch → 注册闹钟（AlarmReconciler.kt:170/194）用旧值。
   `TIME_CHANGED`（手动改时钟）同理，本地墙钟语义下绝对时刻也应随之改变而未重算。
4. **实际后果**：新时区本地 08:00 的药在错误时刻响（旧 epoch 映射到新时区的另一本地时刻），
   可能造成漏服或错误时间服药。Manifest 既显式注册 `TIMEZONE_CHANGED`/`TIME_CHANGED`，用户预期是"改后自愈"，
   实际只对新插入槽位生效、对已存在未来槽位为 no-op。
5. **为什么是 bug**：时区/改时自愈链路被声明，但其对已存在开放槽位的绝对时刻不纠偏；
   投影算出的新 epoch 在"留"分支被丢弃。
6. **定级与修复**：需跨时区/改时条件，**若产品采用"绝对时刻锁定"语义则为设计取舍**（待核实）；
   用药提醒的用户心智通常是"本地 8 点"，建议在 tz/time 变更时对开放 PENDING 槽位按 `(date,time)` 重算 scheduled_ts。定 B。

### B-05 过期结算、暂停、归档时不撤已弹出的托盘通知

1. **位置**：`core/alarm/AlarmReconciler.kt:89-96`（过期结算）；`ui/screen/detail/MedicationDetailViewModel.kt:156-180`
   （暂停/恢复/归档）。
2. **代码原文**：过期结算仅 `stale.identity().cancelAll(context)`（撤 AlarmManager 闹钟），
   全工程无一处在这些路径调用 `Notifications.cancelDoseNotification(...)`。
3. **触发时序**：08:00 闹钟响、通知（ID=slot.id）挂托盘未处理 → 用户一直没理（10:00 被结算 EXPIRED），
   或用户在详情页点"暂停 2 天"/"归档" → 未来闹钟被撤，托盘那条通知仍在。
4. **实际后果**：托盘残留一条"该吃药了"的常驻通知，与"已过期/已暂停/已归档"状态不一致；
   只能手动划掉或重启消失。
5. **为什么是 bug**：全工程唯一会 `cancelDoseNotification` 的是用户点 TAKE/SNOOZE/SKIP 或 UI 打卡
   （DoseActionReceiver.kt:51/65/85、TodayViewModel.kt:218）；过期、暂停、归档都是无此动作的路径，
   处理了闹钟这一侧却漏了通知那一侧。
6. **定级与修复**：残留通知的按钮仍受 `isStillOpen` 守卫，点击只 toast"该提醒已处理过"、**不会二次扣库存或错账**，
   故定 B 而非 A。修法：任何使槽位不再开放的路径成功后，统一补 `cancelDoseNotification(slotId)`。

### B-06 已撤销（REVERTED）服药在详情"服药历史"显示为绿色"已服"

1. **位置**：`ui/screen/detail/MedicationDetailViewModel.kt:104`；`ui/screen/detail/MedicationDetailScreen.kt:815-820`。
2. **代码原文**：
   ```kotlin
   val recent = recordDao.getRecordsForMedication(medId).take(RECENT_RECORD_LIMIT)
   // DoseRecordDao: SELECT * FROM dose_records WHERE medication_id=:id ORDER BY actual_ts DESC（无 status 过滤）
   ```
   ```kotlin
   val (text, color) = when {
       status == RecordStatus.SKIPPED -> "已跳过" to outline
       isRetrospective -> "补录" to tertiary
       else -> "已服" to SuccessGreen   // REVERTED 且非补录落到这里
   }
   ```
3. **触发时序**：今日页打卡 → 点"撤销"（记录被标 REVERTED、槽位回 PENDING）→ 进药品详情看"服药历史"。
4. **实际后果**：一条实际已被自己撤销的服药被列为绿色"已服"、剂量照显；与同屏"近 30 天共消耗"
   （`getSumDoseTakenForMedication` 只算 COMPLETED，已正确排除 REVERTED）自相矛盾。
5. **为什么是 bug**：同屏消耗量剔除了 REVERTED，历史列表却当已服；RecordStatusChip 漏 REVERTED 分支。
6. **定级与修复**：仅单条展示误导、不影响总量，定 B。修法：VM 侧过滤 `status != REVERTED`，
   或给 chip 补 `REVERTED -> 已撤销` 分支。

### B-07 "本机备份"检查在主线程做文件读取 + JSON 全量解析

1. **位置**：`ui/screen/settings/SettingsViewModel.kt:195-210`。
2. **代码原文**：
   ```kotlin
   fun inspectLocalBackup(file: java.io.File) {
       _uiState.value = _uiState.value.copy(isInspectingBackup = true, ...)
       val result = DataExporter.inspectLocalBackup(file)   // 非 suspend，同步 readText + 全量 JSON 解析
       _uiState.value = _uiState.value.copy(isInspectingBackup = false, ...)
   }
   ```
3. **触发时序**：`SettingsScreen.kt:138` TextButton onClick 在主线程直接调用；备份越大阻塞越久。
4. **实际后果**：主线程磁盘 IO + JSON 解析，掉帧/ANR；且 isInspecting 的转圈在同步返回前来不及渲染。
5. **为什么是 bug**：同文件 SAF 路径 `inspectBackup` 是 suspend 且包在 `viewModelScope.launch`
   （SettingsViewModel.kt:162），唯独本机路径漏切后台，两条路径不对称。
6. **定级与修复**：定 B。修法：本机路径同样 `viewModelScope.launch`，或让 inspectLocalBackup 变 suspend 并切 Dispatchers.IO。

### B-08 枚举转换器遇未知值静默替换为默认枚举，Room identity 校验不覆盖枚举名

1. **位置**：`core/data/converter/AppConverters.kt:17-40`。
2. **代码原文**：
   ```kotlin
   fun toPolicyType(value: String?): PolicyType? =
       value?.let { runCatching { PolicyType.valueOf(it) }.getOrDefault(PolicyType.DAILY) }
   // toSlotStatus → PENDING；toRecordStatus → COMPLETED；toTransactionType → TAKEN_DEDUCT
   ```
3. **触发条件**：列中出现当前枚举不存在的名字。现实路径：未来重命名/删除某枚举值（如 CYCLE 改名、删 PRN）
   而 Room identity hash 只哈希表结构（列/索引/外键）、**不哈希枚举取值**——不改字段类型/不升 version 时
   启动校验通过、不崩、无日志；或手工编辑/跨版本的库。
4. **实际后果（示例）**：`policy_type='CYCLE'` 被读成 DAILY ⇒ "吃 21 停 7"变每天服药、闹钟每天响库存每天扣，
   用户无感知；`toRecordStatus` 未知值默认 COMPLETED，未知状态会被**计入"已服"**、虚高依从率。
5. **为什么是 bug**：项目一贯原则是"失败得很响"，而这四对转换器在最该响处用 `runCatching{}.getOrDefault()`
   把"数据已损坏"信号吞掉。
6. **定级与修复**：当前 App 自身写入恒合法，需"未来枚举改名 + 未升 version"同时成立，定 B。
   修法：未知值至少 `Log.w` 带原值，或失败到可观测兜底。

### B-09 `fallbackToDestructiveMigration()` 实际处于启用状态，与项目规则声称相反

1. **位置**：`core/data/AppDatabase.kt:143`（启用行）；`:94-109`（唯一注册迁移 1→2）；`:68`（version=5）。
2. **代码原文**：
   ```kotlin
   .addMigrations(MIGRATION_1_2)
   .fallbackToDestructiveMigration() // TODO(发布前删除)
   ```
3. **事实**：builder **实际挂了**破坏性回退；注册迁移只有 1→2，2→3/3→4/4→5 均无，任何 ≤4 的库走到
   未注册迁移路径即触发整库重建（含服药事实与台账被清）。项目流程文档却称"已刻意禁用破坏性迁移"。
4. **触发条件**：发布构建忘记删第 143 行，此后改 schema（version+1）老用户升级 ⇒ fallback 整库清空。
5. **为什么是 bug**：规则与实际行为相反，"发布前自查"少了双重保险，代码里仅一行 TODO 注释盯着。
   该文件 KDoc（:111-134）已自标"发布前必须删除"，与"未公开发布、不写迁移"的当前决策自洽。
6. **定级与修复**：当前开发期为有意设计、不影响正常路径，定 B。修法：发布前删除该行并补齐迁移；
   建议将其列入发布硬门 checklist。

---

## 5. 跨层不变量核对结论

> 以下逐条说明：代码是否真正保证、由哪些机制 enforce、发现的违反路径。

### 5.1 库存守恒（I1）：✅ 当前代码由结构保证

- 定义：`balance(med) := SUM(inventory_transactions.change_amount)`；`medications` 表**已无 current_stock 列**
  （MedicationEntity.kt:50-57；AppDatabaseRealTest 用 `PRAGMA table_info` 实测守护）。
- 全工程搜索：**不存在任何 `UPDATE medications SET current_stock` / `current_stock =` 的 SQL**
  （命中的 `current_stock` 全为注释，`currentStock` 全为 Kotlin 变量/UI state）。
- 流水表 DAO 仅暴露 `insert/insertAll(ABORT)` 与查询、整表清空，**接口层无 UPDATE、无按 id 删除**。
- 全部生产写库存路径（打卡 takeDose、撤销 undoDose、手动 logManualDose、盘点 calibrateStock、
  开关 setStockTracking、补货 refillStock）均以**单行 INSERT 追加流水**实现；导入（DataExporter.restoreBackup）
  是文档明示的整库批量替换例外，整体包在单一事务内。
- 无 `coerceAtLeast(0)` 作用于余额：负库存原样落流水，SUM 与余额永久一致（都为负）。
- 前缀和不变量（I2，最后一条 balance_after == SUM）：balance_after 在 `withTransaction` 内
  "读 SUM + 加 change"生成，SQLite 串行化写使读到的 SUM 与本次写入之间无并发缝。
- **违反路径**：未发现当前可触发的违反。相关潜伏缺陷为 C-01（建档判据，UI 不可达）。

### 5.2 改计划不冲历史：✅ 由状态白名单 + 无外键悬空保证

- `reconcileSchedule` 的删除/剂量更新只针对 `PENDING/SNOOZED` 且面向未来；
  COMPLETED/SKIPPED/EXPIRED 是既成事实，任何分支不碰。
- 对账全程不写 dose_records；`dose_records.slot_id` 不建指向 dose_slots 的外键，
  删 PENDING 槽位不会级联删事实，撤销留下的 REVERTED 留痕完整。
- `saveReminderPolicy` 缺省沿用历史 startDate/endDate、version 递增（MedicationAdminService.kt:228-238），
  不把起始日无脑设为今天，INTERVAL 相位不漂移。
- **违反路径**：未发现。

### 5.3 打卡/槽位状态机：⚠️ 除 markExpired 外均有 SQL 守卫

- 合法迁移（PENDING/SNOOZED→COMPLETED；PENDING/SNOOZED/EXPIRED→SKIPPED；
  PENDING/SNOOZED↔SNOOZED；COMPLETED/SKIPPED→PENDING；PENDING/SNOOZED→EXPIRED）
  绝大多数把幂等锚点下沉到 `WHERE status IN (...)`，连点/重复操作不重复写、不重复扣。
- **缺口**：唯 EXPIRED 结算（markExpired）无状态守卫且结算段无事务——即 **B-01**。
- 逾期阈值唯一（`now-2h`），PENDING 按 scheduled_ts、SNOOZED 按 snooze_until_ts，被推迟者不被冤枉。

### 5.4 闹钟身份内容寻址：✅ 已正确落地

- 全部闹钟 PendingIntent 走 `AlarmScheduler.alarmUri()` 生成的
  `carromed://alarm/{medId}/{date}/{time}/{kind}` 并 `setData()`，**requestCode 恒为 0**；
  `(medId,date,time,kind)` 参与 `filterEquals` 判重，extras 不参与。
- schedule 与 cancelAll 调用**同一构造函数**用相同四元组重建，数学上必然 filterEquals 一致，取消精确命中、
  不误杀其他槽位；kind 段区分准点/提前/推迟，三者互不覆盖。
- 通知按钮处 `slotId*10+{0,1,2}` 经证明因三个 action 互异而无碰撞（与闹钟同 action 场景本质不同）。
- BootReceiver 覆盖 BOOT/MY_PACKAGE_REPLACED/TIME/TIMEZONE；ReconcileWorker 周期 15min 兜底，
  `doWork` 捕获 Throwable 并 Log 真实异常后 retry（非静默吞错）。
- **缺口**：时区/改时后 scheduled_ts 不重算（**B-04**）；过期/暂停/归档通知不撤（**B-05**）；
  删药不主动撤闹钟（**C-23**，到点空唤醒、不误发通知）。

### 5.5 统计口径一致性：✅ 同源、无假满分；一处补录口径待确认

- 生产路径统一走 `StatsEngine.adherenceOf/aggregateBreakdowns` 与 DAO 的 `GROUP BY status` 聚合，
  状态映射（COMPLETED→completed、SKIPPED→skipped、EXPIRED→missed、PENDING/SNOOZED→pending）逐项一致；
  PENDING/SNOOZED 不进分母。
- 区间归属：依从率按 scheduled_date（计划日）、消耗按 actual_ts（实际时刻），正交。
- 0/0：领域层 decided≤0 返回 1.0f 是有意约定，但**全部 UI 渲染入口**（StatsScreen.kt:168、
  ProgressScreen.kt:159/227、MedicationDetailScreen.kt:776）均判 `decided>0` 否则显"—/暂无到期"，
  **未发现"新用户看到 100%"的假满分透出**；今日页不渲染依从率。
- 量纲：消耗/预警均经 Dose 毫单位↔展示值换算，未发现 ×/÷1000 遗漏；跨单位按单位分组、不相加。
- **缺口**：B-02（补录不回填槽位，待产品意图）、C-28（runway 近似）。

### 5.6 暂停/恢复：✅ 投影抑制 + 派生判定

- 暂停三态（null / "" 无限期 / "yyyy-MM-dd" 含当日）由投影层抑制产槽，统计、闹钟、今日清单三处自动一致；
- "是否暂停"一律走 `ReminderSettingsEntity.isPausedOn(date)` 派生（非 `pausedUntil != null`），到期自动恢复；
  AlarmReconciler 按**槽位自身日期**判暂停，不会因"今天暂停"误删恢复日后的提醒。
- **缺口**：B-05（暂停不撤已发通知）。

---

## 6. 附录

### 6.1 测试侧观察（`app/src/test/`）

- **未发现仍存活的恒真断言/失效测试**。历史两起（DoseTrackingServiceTest 未建台账却断言库存未变；
  AppDatabaseRealTest 断言恰好不成立的 current_stock 列）均已修复：前者改为真实建账并以
  `assertLedgerBalance/balanceOf` 复算，后者改为 PRAGMA 实测列不存在。
- AppendOnlyFactTable 的静态扫描器（扫源码 + "found 集合必须等于白名单"的自检）有效；
  AlarmIdentityTest 直接调用生产 `alarmIntent()` 构造真实 PI 比较，**不是影子测试**，能守住内容寻址。
- 覆盖缺口（建议补，对应 B/C）：
  - 无"负余额 + 正初值重开追踪"用例（对应 C-01）；
  - 无"打卡与过期结算并发交错"用例（对应 B-01）；
  - 无"时区/改时后 scheduled_ts 重算"用例（对应 B-04）；
  - 无"时点剂量为 0 被拦截"用例（对应 B-03）。

### 6.2 导出/备份往返核对（非问题确认）

- 8 张表全部列进出备份（逐列对照实体与 BackupFormat DTO），毫单位 wire 字段带 `Milli` 后缀、口径一致；
- 导入为单一事务、先校验后清库，自增 id 原样保留；损坏 JSON / 截断 / 未知版本 / 缺必填字段均在清库前拒绝；
  BackupRoundTripTest 断言"导出→导入→再导出"相等。未发现丢表/丢字段/丢历史路径。

### 6.3 审查覆盖的主要文件

- 数据：`core/data/entity/*`（8）、`core/data/AppDatabase.kt`、`core/data/converter/AppConverters.kt`、
  `core/data/model/{Enums,Aggregates,MedicationOverview}.kt`；
- DAO：`core/data/dao/*`（7）；
- 领域：`core/domain/engine/{SlotProjectionEngine,StatsEngine}.kt`、`core/domain/model/Dose.kt`、
  `core/domain/service/{DoseTrackingService,MedicationAdminService}.kt`；
- 闹钟：`core/alarm/*`（8）；
- UI：`ui/screen/**/*ViewModel.kt`（14）及必要的 Screen 保存/渲染段落、`ui/navigation/*`；
- 导出：`core/data/{DataExporter,BackupFormat}.kt`；
- 调试：`app/src/debug/.../{DevDataReceiver,DevSampleDataSeeder}.kt`。

---

*本报告为只读静态审查产物，所有结论以实际代码逐行核对为准；标注"待核实"的条目需相应条件或产品意图确认后方可最终定性。*
