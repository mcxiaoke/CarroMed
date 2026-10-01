# CODE-REVIEW-20261001-orsbf —— CarroMed 全面审阅报告

> 审阅日期：2026-10-01 · 代码基线：`ed1d380`（2026-09-30 21:26）
> 范围：数据层 / 领域与业务逻辑 / 闹钟提醒链路 / UI-UX / 工程与验收闭环 / 竞品对标
> 方法：6 路并行深审（数据、领域、闹钟、UI、工程、竞品调研）→ 主审逐条核验 P0 证据 → 汇总定级。
> 所有 P0 结论均经第二人（主审）对当前工作区代码复核，证据行号以当前 HEAD 为准。

---

## 0. 总体结论

| 维度 | 评分 | 一句话 |
| :--- | :---: | :--- |
| 数据层 | **5.5 / 10** | 架构骨架优秀，但备份恢复通道在真实数据量下是坏的，append-only 承诺被外键级联击穿 |
| 领域与业务逻辑 | **6 / 10** | 数学守恒有单测守着，但补录与剂量量化两个入口各有一个静默破口 |
| 提醒链路 | **7 / 10** | 身份与调度层是全项目最强部分；窟窿在输出端（通知投递）而非调度端 |
| UI / UX | **5.5 / 10** | 信息诚实度罕见地好，但视觉层从未为「中老年 + 大字号」这一目标人群设计过 |
| 工程与验收闭环 | **7 / 10** | 领域层测试密度远超同类个人项目；但 release/视觉/走查三条最贵的验证循环全部停摆 |
| **综合** | **6 / 10** | 底子是同类项目里罕见的扎实，当前最大的问题不是"缺功能"，而是**承诺与验证之间的四条裂缝** |

**本轮最重要的判断：** 这个项目的问题分布很特别——核心域逻辑（投影引擎、幂等锚点、内容寻址闹钟）质量很高，但**三条最关键的端到端通路没有一条被真实验证过**：

1. 「备份能恢复」——恢复路径在超过 90 行槽位时**必然崩溃**（P0-1）；
2. 「release 能编译」——`isMinifyEnabled = false`，R8 从未运行，文档却写着"通过 R8 检查"（P0-6）；
3. 「改了界面看图验证」——快照基线停在 7 个 UI 提交之前，走查脚本当天跑出 21 条断言失败且失败清单在 gitignore 里（P0-8）。

### 与既有审查的关系

`CODE-REVIEW-20260930-ocsbf.md`（51KB）与 `CODE-REVIEW-20260930-xdsf.md`（58KB）生成于 2026-09-30 22:50/22:55，晚于最后一次提交（21:26）。**即两轮报告的所有问题至今一行未修，全部仍然成立**。本轮报告：

- **不重复**两轮已报告的条目（仅在与新发现构成同一因果链时引用，如 Streak 问题）；
- 聚焦**两轮都漏掉的新缺陷**——其中包括数条比前两轮所有 P0 更严重的（P0-1、P0-2、P0-3）。

---

## 1. P0 —— 已核验，建议立即修（8 条）

### P0-1 恢复备份在数据量稍大时必然失败：Room 的 `@Insert(List)` 是一条多值 INSERT，撞穿 SQLite 变量上限 999

**已核验。** 证据链：

- Room 生成的 SQL（`app/build/generated/ksp/debug/java/.../DoseSlotDao_Impl.java:110`）：

  ```sql
  INSERT OR IGNORE INTO `dose_slots` (`id`,`medication_id`,`policy_id`,`scheduled_date`,
  `scheduled_time`,`scheduled_ts`,`dose_amount`,`status`,`actual_taken_ts`,
  `snooze_until_ts`,`created_at`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?,?,?)
  ```

  每行 **11 个占位符**，Room 把同一语句按行数拼接后**一次 compile、逐行 bind，不分片**。Android 的 SQLite 编译期 `SQLITE_MAX_VARIABLE_NUMBER = 999`（Android 14 才提升），而本项目 `minSdk = 26`。
- 恢复路径把**整张表**一次性塞进去（`DataExporter.kt:836/852/867`）：

  ```kotlin
  db.doseSlotDao().insertAll(backup.doseSlots.map { ... })              // 上限 ≈ 90 行
  db.doseRecordDao().insertAll(backup.doseRecords.map { ... })          // 上限 ≈ 99 行
  db.inventoryTransactionDao().insertAll(backup.inventoryTransactions.map { ... })
  ```

**后果：** 3 种药 × 每天 3 次 = 一天 9 个槽位，14 天对账窗口就有 126 行——已超上限。而 `dose_slots` 从不归档清理，用满一年就是 3000+ 行。用户拿一年前的备份恢复 → `bind or column index out of range` → 事务整体回滚 → 界面报"恢复失败"。**「备份能恢复」这条唯一的逃生通道，对绝大多数真实用户不可用。** 同理，`DoseTrackingService.reconcileSchedule` 的 `insertAll(toInsert)` 在"一天 8 次以上服药"时也会越界。

**修法：** DAO 层加分片辅助（`chunked(50)`），覆盖 `DoseSlotDao.insertAll` / `deleteByIds`、`DoseRecordDao.insertAll`、`InventoryTransactionDao.insertAll`、`MedicationDao.insertAll`；补一条"300 槽位 + 300 流水完整往返"的 Robolectric 测试。

### P0-2 删除药品会把 append-only 的服药历史与库存台账一起物理删掉

**已核验。** 生成的 DDL（`AppDatabase_Impl.java:74,78`）：

```sql
FOREIGN KEY(`medication_id`) REFERENCES `medications`(`id`) ON DELETE CASCADE
```

挂在 `dose_records`（服药事实）和 `inventory_transactions`（库存台账）两张被 KDoc 反复论证为"永不篡改"的表上。而 `MedicationDetailViewModel.deleteMedication`（`MedicationDetailViewModel.kt:234-244`）直接 `medDao.deleteById`，无二次确认、无任何 UI 提示。

**后果：** "我吃完一个疗程，把这个药删掉"是完全正常的用户动作——一按，这个药两年来的全部服药事实、全部库存流水、`balanceAfter` 快照一起消失。第二产品承诺「吃过的药永不丢失」被外键从背后击穿，且没有任何注释或提示告诉调用方这条路径会毁掉历史。

**修法：** `onDelete` 改 `RESTRICT`（或 `SET NULL` + 冗余药品名快照列保住可读性）；`MedicationDao.deleteById` 标 `@Deprecated` 指向 `updateArchiveStatus`；若确定保留硬删除，必须在 `deleteMedication` 里先统计行数并二次确认。补测试：「删药品后两张事实表行数不变」。**这一条需要产品先拍板：删药到底该不该留历史。**

### P0-3 补录过去的服药「不认账」：同日槽位既不结清也不关联，可导致同剂药双扣库存

**已核验。** 三段代码拼出完整链条：

- `logManualDose` 写记录时 `slotId = null`（`DoseTrackingService.kt:752`）——补录**永不**与当日槽位关联；
- `markCompletedIfOpen` 的 SQL 守卫放行 `EXPIRED`（`DoseSlotDao.kt:226`：`status IN ('PENDING','SNOOZED','EXPIRED')`）；
- `restateSlot` 却拒绝 `EXPIRED`（`DoseTrackingService.kt:450` 只接受 `COMPLETED/SKIPPED`）。

**后果（两个用户可见的 bug）：**

1. 昨天漏服（槽位被结算成 `EXPIRED`），今天走补录页补一条——依从率仍把昨天算**漏服**（统计按 `scheduled_date` 归属，补录不碰槽位；唯一能改判的 `restateSlot` 又拒绝 `EXPIRED`）。**漏服永久留在分母里，用户无法自愈**，同一天同时存在"已服记录"与"已逾期未服"两条矛盾陈述。
2. 补录之后再对那条 `EXPIRED` 槽位点确认 → `markCompletedIfOpen` 放行 → **插入第二条 COMPLETED 事实 + 第二条扣减流水**。同一剂药记两次、扣两次库存。数值上 `SUM(change_amount)` 仍守恒，所以现有不变量测试 I1/I2 **拦不住**它。

**修法：** `logManualDose` 写记录前先按 `(medicationId, actualDate, 临近 planned time)` 找当日 `PENDING/SNOOZED/EXPIRED` 槽位，命中走槽位通路（复用 `takeDose` 幂等锚点），未命中才落 `slotId = null`；同时把 `restateSlot` 守卫放宽到接受 `EXPIRED`。

### P0-4 剂量→毫单位的量化没有守住「必须为正」：0 剂量永不扣库存、溢出负值反而加库存

**已核验。** `Dose.kt:52`：

```kotlin
fun of(value: Float): Dose = Dose(Math.round(value * 1000f))
```

- `0.0004` → `Math.round(0.4) = 0` → 落库 `dose_amount = 0`。而 `saveReminderPolicy` 的 KDoc 恰好把「0 剂量 ⇒ 打卡照记、库存永不扣」列为自己要堵的 P0——这个洞从量化函数底下漏过去了；
- 极大值（如 `3e6`）经 `Math.round(v * 1000f)` **溢出 Int 为负** → `changeAmount = -finalDose` 变成正数，**每打一次卡给账面加一片药**，全程无报错；
- 守卫检查的是浮点入参（`MedicationAdminService.kt:280-283` 校验 `dose <= 0f`），**不是量化后的毫单位**；
- 备份恢复直写 `PolicyTimeEntity(doseAmount = t.doseAmount, ...)`（`DataExporter.kt:824-828`），**根本不走** `saveReminderPolicy`——KDoc 宣称自己是"保护所有调用方（含备份导入）的第三层防线"，实际恢复路径绕过了它。

**修法：** 守卫移到量化之后（`require(Dose.of(it.dose).milli > 0)`），`Dose.of` 加上界钳制防溢出；`takeDose` 对 `slot.doseAmount` 补最后一道 `require(finalDose.milli > 0)`；`restoreBackup` 复用同一组校验。

### P0-5 通知投递被静默吞掉，且补响自锁成「每 30 秒一次」的唤醒风暴

**已核验。** 三段代码：

- `Notifications.kt:221-223`：`notify()` 包在 `runCatching` 里。Android 13+ 在 `POST_NOTIFICATIONS` 未授予时 `notify()` **不抛异常、什么都不做**——于是 `AlarmReceiver.kt:93` 照样记 `notification shown`（假阳性日志）；
- `Notifications.kt:245-248`：补响判据 `isDoseNotificationShown` 查"托盘里有没有这条通知"，查询失败与"确实不在"都返回 `false`；
- `AlarmReconciler.kt:365-381`：`mainAt >= now - 2h && !isDoseNotificationShown` → 再排一条 `now + 30_000` 的 MAIN 闹钟。

**后果：** 用户拒绝了通知权限（或在系统设置里关掉渠道）后：闹钟**正常唤醒**（不受通知权限影响）→ `notify()` 空操作 → 托盘判据恒为 false → 再排 30 秒后的闹钟 → 循环。`CATCHUP_WINDOW_MS = 2h`，即**每条漏掉的服药在 2 小时内唤醒设备约 240 次、触发约 240 轮全量对账**（每轮 = 全部药品 × 14 天投影 + 全部槽位排闹钟）。用户侧零输出，设备耗电发热，App 内部"一切正常"。这是第一承诺「到点一定响」最典型的静默失效形态：**输出端断了，链路对自己的判断是"成功"。**

**修法：** `showDoseNotification` 返回 `Boolean`（`areNotificationsEnabled()` 前置检查 + 回读确认）；补响判据改用**持久化计数**（每槽位补响窗口内最多 1~2 次），托盘判据降级为参考；`AlarmReceiver` 在通知不可达时**不入队续期**。

### P0-6 release 从未跑过 R8，文档却宣称已验证；一旦开启，所有历史数据会被静默改写

**已核验。**

- `app/build.gradle.kts:49` `isMinifyEnabled = false`；`app/` 下**不存在** `proguard-rules.pro`（`build.gradle.kts:52` 却引用它）；`app/build/outputs/apk/release/` 只有 apk，**没有 mapping.txt**——R8 确实从未运行；
- `docs/CHANGES-20260930.md:70/115/133` 三处白纸黑字写着「`assembleRelease` 顺利完成（通过 Lint Vital 与 **ProGuard/R8 检查**）」「混淆优化全部无异常」——**不成立**。`docs/DEVGUIDE.md:444` 的验收命令 `compileReleaseKotlin` 只编译 Kotlin，不跑 R8；
- 真正的风险在开启那一刻：本项目把枚举**按 `.name` 落库**（`AppConverters.kt:37/44/51/58`），解析用 `enumValueOf` + fallback（`:27-34`）。R8 默认规则只保留 `values()/valueOf()` **方法**，不保留**常量字段名**。混淆后所有历史行的 `enumValueOf` 全部落进 fallback：槽位全回 `PENDING`、流水全变 `TAKEN_DEDUCT`。**且它是静默的**——release 日志只收 WARN+，用户看不到任何异常。

**修法：** 新建 `app/proguard-rules.pro`（`-keepnames enum com.mcxiaoke.carromed.core.data.model.**` 等，见工程节），`isMinifyEnabled = true` + `isShrinkResources = true`，DEVGUIDE 验收命令改 `./gradlew assembleRelease`，并修正 CHANGES 文档那三处不实记录。

### P0-7 设置入口从今日页消失了，`onNavigateToSettings` 是死参数

**已核验。** `TodayScreen.kt:99` 声明参数、`AppNavigation.kt:142` 传入，但 `TodayScreen.kt` 全文 908 行**无一处引用**；全 App 唯一活着的人口是统计报表页的齿轮（`AppNavigation.kt:199`）。`today_cd_settings` 已成孤儿资源。

**后果：** 新用户四个 Tab 逛一圈找不到「设置」；要改「夜间免打扰」「后台保活」「备份」必须先莫名其妙进统计报表。对中老年人基本等于不可达。**App 唯一关乎"会不会漏吃药"的配置入口，被藏在一个毫无关系的页面里。**

**修法：** 在 `TodayScreen` 的 `topBar.actions` 恢复齿轮（与 streak 徽章并列）；中期考虑第 5 个 Tab「我的」。

### P0-8 验收闭环停摆：快照过期 7 个提交、走查 21 条失败藏在 gitignore、新页面从未登记

**已核验。**

- 快照基线最后提交于 `99254c1`（09-30 18:43），其后有 **7 个 UI 提交**（顶栏图标、文案标准化、streak 徽章、类型安全路由……）。`today_seeded.png` 里还有已不存在的齿轮图标，`today_empty.png` 里按钮还是旧文案「添加第一个药品」（现为「添加药品」）；
- Roborazzi 在普通 `testDebugUnitTest` 下是 `TaskType.None`——**既不 record 也不 compare**。同一次全绿的测试运行里 9 张 PNG 一张没动。项目自己在 `CHANGES-20260930.md:256-257` 记录了这一点，但基线照旧挂着"视觉回归"的假身份；
- 走查脚本 `tools/app_screenshots.py:128` 的断言词表硬编码「未来排班预览」，而 `de39c07` 已把文案改成「未来用药预览」——09-30 21:10 那次实跑 **21 条断言级联失败**，后半程 39 张截图全是同一个页面，而失败清单写在 **gitignore 掉的** `temp/appscreenshots/manifest.md` 里，无人看见；
- `cacfd00` 新增的 `DoseHistoryCalendarSheet`（月度打卡日历）与 `TodayStreakBadge` 从未登记进走查脚本——违反 DEVGUIDE §4.1 自己的 checklist；Maestro 只覆盖 6/14 个路由。

**后果：** 「改动涉及界面时跑 UI 走查并亲眼看图」这条流程（AGENTS.md §二 第 4 步）目前验的是三天前的界面。谁哪天按 `ComponentSnapshotTest.kt:24` 的承诺"冻结后升为门禁"打开 `verifyRoborazziDebug`，会拿到 9 张全红 diff。

**修法：** 立即重跑 `recordRoborazziDebug` 刷新基线；走查词表从 `strings.xml` 生成 + 词表新鲜度检查（不在词表直接 `exit 2`）；失败时退出码非 0；把 `DoseHistoryCalendarSheet`、`TodayStreakBadge` 登记进走查 PROGRAM；Maestro 补齐 8 个未覆盖路由或明确声明只做冒烟。

---

## 2. P1 —— 本轮应修

### 数据与业务

**P1-1 恢复失败后，闹钟与托盘通知已被拆掉且不会重建。** `DataExporter.kt:1144-1164`：`cancelAllAlarmsBeforeRestore` 与 `cancelAll` 在事务**之外**先执行，`catch` 分支只回滚数据库，成功与失败两条路都**没有** `AlarmReconciler.rescheduleAll`（重排靠用户下次打开 MainActivity 或周期 Worker）。后果：数据库完好，但接下来（可能直到下一个对账周期）**这个 App 不响任何提醒**。修法：`catch` 与成功分支都补 `runCatching { AlarmReconciler.rescheduleAll(context, db) }`。

**P1-2 `MedicationDao.insert` / `SchedulePolicyDao.insertPolicy` 的 `REPLACE` 是活的地雷。** SQLite 的 `INSERT OR REPLACE` = DELETE + INSERT，DELETE 会触发 `ON DELETE CASCADE`。`DataExporter.kt:76-79` 自己把这条写成了导入校验的判据，却把杀伤力更大的入口原样留着。今天只新增分支调用所以未爆；一旦有人为"编辑"路径复用它，后果是该药的全部槽位、服药记录、库存流水一起消失且插入返回成功。修法：改 `ABORT`。

**P1-3 流水页 keyset 分页在同一毫秒的记录簇上静默吞记录。** `DoseRecordDao.kt:193-198` 的 KDoc 把 `actual_ts < cursor`（严格小于）论证成"正确性要求"，实际它是丢数据的根因：补录"昨天 08:00 吃了 A/B/C 三种药"产生三条 `actual_ts` 完全相同的记录，当页边界切在簇中间时，`<` 会把簇里其余未加载的记录**永远跳过**。调用方的 `existingIds` 去重（`ProgressViewModel.kt:281-283`）就是这个 bug 已发生过的证据。修法：复合游标 `(actual_ts, id)`。

**P1-4 `RUNWAY_UNLIMITED = -1` 与真实负库存撞码。** `StatsEngine.kt:140` 哨兵取 `-1`，而 `runwayDays = (currentStock / daily).toInt()`（:127）对负库存（D-9 明文允许账面为负）**未钳制**——库存 -1、日消耗 1 时算出 `-1`，命中哨兵，UI 把「已超支、最紧急」渲染成「—」（按需服用 = 无限），且 `1..7` 低库存横幅不触发。修法：哨兵改 `Int.MIN_VALUE` 或 sealed 结果；负库存单列"已超支"分支。

**P1-5 `scheduledDaysPerWeek(INTERVAL)` 把 n≥15 全折成"每周 1 天"，日消耗高估 4.3 倍。** `StatsEngine.kt:446-449`：`Math.round(7.0 / n).coerceAtLeast(1)`——"每隔 30 天吃一次"（长效制剂、月度方案，`intervalDays` 取值域 1..30）按单次量/7 而非 /30 估算 → 可用天数只剩真实值的 1/4。现有测试只覆盖 n≈2。修法：用浮点日均参与消耗推导。

**P1-6 槽位补记路径完全没有时间窗，且 `isRetrospective` 恒为 `false`。** `MANUAL_DOSE_BACKFILL_DAYS = 7` 只挂在手动补录通路（`DoseTrackingService.kt:743-747`）；用户实际在用的槽位补记（今日页日期条可无限往前翻）无任何下界（`SlotActionPolicy.kt:54` 对"过去"一律放行），且补三个月前的槽位 `isRetrospective` 仍是 `false`（`:208`）——详情页、历史页、台账把历史补记显示成实时打卡。修法：时间窗守卫下沉到 `takeDose`，由"槽位日 vs 今天"推出 `isRetrospective`。

**P1-7 `DoseEntryActions.undo` 吞掉重排异常。** `DoseEntryActions.kt:152-157`：`runCatching { AlarmReconciler.rescheduleAll(...) }` 静默吞掉；同文件 `:177-179` 的 `snooze` 却明确注释"排闹钟失败必须能被用户看见"。撤销后槽位已回 `PENDING` 但可能没有闹钟，函数仍返回 `true`，UI 提示"已撤销"——**直到下一轮周期对账前静默不提醒**。修法：与 `snooze` 同纪律。

**P1-8 Streak 缺陷（前两轮已报，仍未修，本轮降级收录）。** 跨无排班空档不断签 + 今天部分完成恒显示 0 天（`StatsEngine.kt:377-395`）；叠加本轮新发现：`calculateStreak` 的 365 天回溯预算被空档日按天消耗——`INTERVAL = 30` 的药每命中一次要跨 29 个空档，365 天预算只够约 **12 次命中**，低频方案（长效针剂）用户 Streak 长期卡在个位数。

### 提醒链路

**P1-9 补响窗口硬编码 2 小时：手机关机/重启超过 2 小时，漏掉的服药完全没有提醒。** `AlarmReconciler.kt:88-100`。最常见场景——晚上关机、早上开机已过服药点 3 小时——落在"静默待办"里：无闹钟、无通知、无徽标，用户得自己打开 App 才知道漏了。修法：把"今天内未响"的槽位在开机/回前台时汇总成**一条**通知（"今天有 3 次服药未提醒"），窗口至少放宽到覆盖最长给药间隔。

**P1-10 夏令时切换不产生广播，提醒最多晚 1 小时且无提示。** `TIMEZONE_CHANGED` 只在时区 ID 变化时发送；同一时区内的 DST 切换不发。投影把绝对毫秒提前 14 天固化（`SlotProjectionEngine.kt:114-116`），春季跳变当天的 02:30 被 `atZone` 隐式顺延到 03:30，秋季回拨第二次被跳过；修复依赖周期对账恰好在那天跑到。修法：投影显式检测空洞/歧义时刻；对账时比对时区规则版本。

**P1-11 Android 12/12L 恒定拿不到 EXACT 档。** `AndroidManifest.xml` 只声明 `USE_EXACT_ALARM`（API 33 引入），未声明 `SCHEDULE_EXACT_ALARM` → API 31/32 上 `canScheduleExactAlarms()` 恒 false。可靠性无碍（降级档 `setAlarmClock` 在 Doze 下仍准点），但状态栏常驻闹钟图标，且自检页显示"降级"却没有修复按钮——**一个功能正常的自检项报非绿，会训练用户忽略这页的真正告警**。修法：补声明 `SCHEDULE_EXACT_ALARM`，或把 API 31/32 + 未生效单独标注为"闹钟通道（正常）"。

**P1-12 点通知销毁重建 MainActivity，且不落到那条服药记录。** `Notifications.kt:178-183` 用 `CLEAR_TOP` 但 `MainActivity` 是 standard 启动模式且无 `onNewIntent`——该 flag 语义下系统会**销毁重建**，用户丢失当前位置；点哪条提醒都落到同一个页面。修法：`carromed://dose/{...}` 深链 + `singleTask` + `onNewIntent`（与闹钟内容寻址同一套哲学）。

**P1-13 恢复备份里的重复服药时点会让一半的服药彻底没有闹钟。** 新建/编辑路径拒绝重复时点（`MedicationAdminService.kt:243-245`），恢复路径没有；UNIQUE 索引 + `insertAll(IGNORE)` 让第二条槽位**静默丢弃**——恢复后该药每天只响一次，而计划编辑页显示两个时点；被丢的那一半无闹钟、不进今日清单、不计入依从率，零痕迹。修法：`DUPLICATE_POLICY_TIME(blocksRestore=true)`（M10），过渡期先 `AppLog.e` 记录被 IGNORE 的差额。

### UI / UX

**P1-14 `colorScheme.outline` 被当作文字前景色，白底对比度 1.23:1。** **已核验** 8 处文字用法：`MedicationDetailScreen.kt:176/901/902`、`MedHistoryScreen.kt:221/223`、`ProgressScreen.kt:607/608`、`TodayScreen.kt:806`。`OutlineLight = 0xFFE2E8F0`（`Color.kt:56`）对白底 = **1.23:1**（AA 要求 4.5:1）——「已归档」「已跳过」「已撤销」这几个字在浅色主题下**接近隐形**。另有 `SuccessGreen` 作 16sp bold 文字 3.26:1 不达标。修法：文字前景一律换 `onSurfaceVariant`（7.4:1），`Color.kt` 增加显式 muted-text token。

**P1-15 关键控件触摸目标 25–42dp（标准 48dp）。** 全 App 最高频的「确认服药 ✓」42dp（`TodayScreen.kt:553`）；**修复精确闹钟降级的唯一入口**（权限自检页"去授权"按钮）≈25dp（`PermissionCheckScreen.kt:301-307`）；提醒频次快捷按钮 ≈26dp；日期/时间小图标 18dp。目标用户是手抖的中老年人——自救入口反而是全 App 最小的点击区。修法：9 处按钮统一 `height(48.dp)`，见 UI 节表格。

**P1-16 反馈机制全 App 五套，且 Toast 把原始异常抛给用户。** Snackbar / 常驻 NoticeBar / Toast / ViewModel 里发 Toast / 页面顶部 error Surface 五种并存（`ReminderSettingsScreen.kt:126`、`SettingsScreen.kt:490,494`、`StatsViewModel.kt:261,269`）。`stats_export_failed, e.message` 会显示成「导出失败： java.io.FileNotFoundException: /storage/emulated/0/...」甚至字面量 `null`。ReminderSettings 保存后 Toast + 立即返回——反馈飘在错误的页面上。修法：统一 Snackbar，经 `SavedStateHandle` 传给目标页消费；异常只给代号/人话，堆栈进 AppLog。

**P1-17 今日页与药箱页完全没有错误态。** `TodayScreen` / `CabinetScreen` 无 `error` 字段——Room 查询失败时 `isLoading` 永远 false，走空状态分支，用户看到"药箱还是空的"然后去**添加药品，而真实原因是数据库打不开**。`MedicationDetailScreen.kt:140` 更是不区分"加载中"与"失败"。修法：`ErrorState(text, onRetry)` 组件 + UiState 加 `error` 字段。

**P1-18 `Locale.getDefault()` 参与持久化格式构造（11 处）。** `AddEditMedicationViewModel.kt:350/592`、`ReminderSettingsViewModel.kt:312/591` 等用 `Locale.getDefault()` 格式化 `%02d:%02d` 写入 `policy_times.time_of_day`——`ar-EG`/`fa-IR` 等 locale 下产出 `١٠:٣٠`，`LocalTime.parse` 失败，**提醒静默降级**。今天因只有中文资源而潜伏，但 `supportsRtl="true"` + i18n 计划正在引爆路径上。修法：持久化/协议一律 `Locale.ROOT`；测试 JVM 钉死 locale；补"切 de-DE 后 `Quantity.fmt` 仍输出小数点"的测试。

**P1-19 CSV 导出与 6 个 ViewModel 零测试。** `escapeCsv` / `exportDoseRecordsCsv` / `exportInventoryLedgerCsv` 在 57 个测试文件里**引用数为 0**（对照 `buildBackup` 28 处）——CSV 转义一旦漏掉引号/逗号，用户在 Excel 里看到的是错列的服药记录且无从察觉。`ReminderSettingsViewModel`（559 行，全项目最复杂表单）、`DoseRecordDetailViewModel`、`InventoryViewModel`、`ProgressViewModel`、`ManualDoseViewModel`、`RefillViewModel` 合计约 1900 行，测试引用为 0。`DoseRecordDetailStateTest` 的 29 条断言**手工构造 `isActionable = true`** 绕开了 ViewModel 的真实推导——把 `isActionable` 写死测试仍全绿。修法：CSV 转义测试 + 按 `StatsStateBuilder` 范式抽状态 Builder 补测。

---

## 3. P2 / P3 摘要

### P2（体验债与一致性债，按影响排序）

| # | 问题 | 证据 | 要点 |
| :--- | :--- | :--- | :--- |
| 1 | 时点串 trim 口径不一致 | `MedicationAdminService.kt:258/320`、`SlotProjectionEngine.kt:101` | `" 21:00 "` 通过入口校验、投影解析失败回退 **08:00**——每天 08:00 响一个用户从没设过的时间 |
| 2 | `startDate`/`endDate` 无格式校验 + 投影 fail-open | `MedicationAdminService.kt:302-310`、`SlotProjectionEngine.kt:67-71` | 非规范结束日期 → `policyEnd = null` → **疗程边界消失，提醒永不停** |
| 3 | 暂停判据三处实现，其中一处是文档亲自点名为错的 | `AlarmReceiver.kt:80` vs `AlarmReconciler.kt:244-249` | 推迟跨零点的槽位，两条路径对同一条提醒给出相反结论 |
| 4 | 领域层 `android.*` 依赖违反铁律 | `DoseEntryActions.kt:3-7`、`CurrentDateHolder.kt:3-5` | AGENTS.md 例外只有 `withTransaction`；编排类应移出 `core/domain` |
| 5 | 补响通知文案不区分"刚响"与"迟到了 5 小时" | `Notifications.kt:161-176` | 文案写"计划 08:30"，用户 14:00 收到，无法判断是补响 → 划掉 → 下一轮再补 → "莫名其妙反复响" |
| 6 | 顶栏样式三处各写各的 | `CarroMedTopAppBar.kt` + 9 个页面裸 `TopAppBar` | 药箱页与详情页字重肉眼可辨不同；组件注释写 22sp 实为 18sp |
| 7 | 药箱卡片药名无 `maxLines` | `CabinetScreen.kt:327-348` | 类别标签被挤成**竖排单字**（营/养/保/健）；同款 Row 模式还有 4 处 |
| 8 | 今日页日期条 ±3 天窗口随点平移 | `TodayViewModel.kt:238` | 点"1 号"整条星期表头横移一格，老人无法建立"固定一周"心智；`DoseHistoryCalendarSheet.kt:133` 还有硬编码周一名单漏网 |
| 9 | 统计页三个数据格窄屏碎成三行 | `StatsScreen.kt:173-192` | "2 / 已按时服 / 用"——标签第三字掉行；"跳过/漏服"在数据格里合并、在图例里又拆开，数字对不上 |
| 10 | `ProgressScreen` 触底加载读到过期的 `hasMoreTimeline` | `ProgressScreen.kt:85-96` | `LaunchedEffect` key 只有 `selectedTab`，首次为 false 时**翻页永远不触发** |
| 11 | 注意事项渲染成点不动的伪按钮 | `MedicationDetailScreen.kt:396-405` | `AssistChip(onClick = {})` 有涟漪、有 TalkBack "按钮"播报，按了没反应——用在**安全相关**内容上是最坏组合 |
| 12 | 表单错误只有颜色无朗读 | `ReminderSettingsScreen.kt:435` | `isError` 无 `supportingText`，TalkBack 与色觉障碍用户都收不到错误信息 |
| 13 | 语义色无深色变体 | `Color.kt:69-78`、`Theme.kt:18-70` | `SuccessGreenContainer #DCFCE7` 等浅色容器在深色模式下打出刺眼亮块 |
| 14 | `RefillScreen` 缺 `imePadding()`；渠道下拉用 `remember` | `RefillScreen.kt:92/230` | 键盘吞掉提交按钮；下拉滚一下自己合上——`rememberSaveable` 纪律的第 5 处漏网 |
| 15 | App 图标是系统默认机器人 + 平台 Material 主题 + 无 `windowSoftInputMode` | `AndroidManifest.xml`、`themes.xml:3` | 桌面/启动画面零品牌；首帧闪白；IME 行为随 ROM 而变 |
| 16 | `MedicationDao.OVERVIEW_SELECT` 台账子查询全表重算，失效粒度是任意一行流水 | `MedicationDao.kt:24-38` | 唯一随时间单调劣化的查询形状；`observeDecidedSlotCount` 全扫探针挂三个页面 |
| 17 | `LogFileSink` 单日体积只滚不封顶 + `close()` 数据竞争 | `LogFileSink.kt:100-115/77-84` | 坏循环可一次会话写满内部存储——存储满会让 SQLite 写失败，日志问题升级成"提醒写不进去" |
| 18 | 诊断日志写药品名，与自己的隐私决策冲突 | `MedicationAdminService.kt:156/180` vs `DoseTrackingService.kt:149` | 自由文本按"方案 D6"不落日志，药品名（本身就是健康信息）却落，且可被导出成分享文件 |
| 19 | CSV 导出与备份口径不一致 | `DataExporter.kt:262/310` vs `:415` | `buildBackup` 用 `withTransaction` 修过的一致性 bug 在两条 CSV 路径上还在 |
| 20 | `validateBackup` 漏校验 `policyId` / `recordId` 悬空引用 | `DataExporter.kt:594-613` | 悬空 `recordId` 会让撤销路径判定"这条没扣过"，账面不再守恒 |
| 21 | 34 个 Robolectric 类未钉 SDK、无 `robolectric.properties` | 全部测试类 | targetSdk 提 36 时运行时无声改变；快照类钉 34 其余跑 35 无文档说明 |
| 22 | 无 CI；版本无 catalog；协程是隐式传递依赖；`androidx.test:core` 1.6.1 vs `runner` 1.6.2 | `app/build.gradle.kts` | AGENTS.md 四步流程没有任何强制点 |
| 23 | 走查与 Maestro 的中文断言与 strings.xml 无关联 | `.maestro/smoke-seeded.yaml:12-55` | 三个走查工具里只有 `SmokeNavigationTest` 做对了资源化 |

### P3（卫生，摘选）

- 12 个零调用 DAO 方法（含危险的 `ReminderSettingsDao.deleteForMedication`——与 FK CASCADE 两条删除路并存）；另有 4 个仅测试调用；
- `BackupFormat.kt:31-33` KDoc 引用不存在的 `schemaVersion` 字段；`BackupFile.app/formatVersion/exportedAt` 无默认值，缺键即整份恢复失败；
- `DataExporter.timestamp()` 用 `Locale.getDefault()` → 佛历/和暦 locale 下文件名变 `2569...`；
- 分享失败被静默吞掉（`DataExporter.kt:1237`），用户点"分享"后什么都不会发生；
- `DoseRecordEntity` 缺 `(medication_id, actual_ts)` 复合索引；
- `ReminderSettingsEntity.daysUntilResume` 暂停已过期时返回 `0` 而非 KDoc 承诺的 `null` → UI 可渲染出"0 天后恢复"；
- `StatsEngine` 存在两份依从率实现 + 两个只有测试调用的影子函数（`calculateAdherence`/`sumDoseByDate`）——"影子测试"活标本；
- `DoseTrackingService` 时钟三个来源混用；`reconcileSchedule` 默认参数直接绕过注入时钟；
- 死代码：`StepperRow`、`currentMinuteOfDay()`、`MotionSpec.modalEnter/Exit`、`InventoryScreen.fmt`（`Quantity.fmt` 的逐字副本，注释还宣称已统一）；
- `RefillViewModel.channel` 预填真实药房品牌名「同仁堂实体药房」——用户数据默认值被写死成商业机构名，会进 CSV 与备份；
- 今日页 FAB「添加」与空状态按钮「添加药品」字面撞车且几何重叠；"手动补录"全 App 有四个名字（添加/临时用药/手动添加/补录）；「轻按」是 iOS 用词；顶栏底栏图标 contentDescription 让 TalkBack 念两遍；
- 签名配置用已废弃 V1 且未启用 V3/V4；Gradle wrapper 无 sha256 校验且走第三方镜像；`gradle.properties` 无缓存开关；`core/domain` 零 android 依赖铁律无机器校验；jqwik 反例库被 gitignore（属性测试失败不可复现）；
- 文档失真：`DEVGUIDE.md:186` 测试数写 420（实际 518）；`CHANGES-20260930.md` 三处"通过 R8 检查"不成立；`app/build.gradle.kts:71` 注释与事实不符。

---

## 4. UI / UX 专项

### 4.1 三分钟首次体验走查（按代码推演）

| 步骤 | 用户看到 | 判定 |
| :--- | :--- | :--- |
| 0 打开 App | 绿色机器人默认图标、首帧白底闪 | ❌ 第一印象负分 |
| 1 落地空态 | 空状态卡「添加药品」按钮 + 右下 FAB「+ 添加」**压在按钮上** | ❌ 两个同名 CTA 叠加 |
| 2 点错 FAB | 进入手动补录页，药品下拉为空只有一句"没有药品"，**死胡同无出路** | ❌ |
| 3 新建药品 | 顶栏是「取消」文字而非返回箭头；**填一半退出全部静默丢失（全项目无一处 BackHandler）** | ❌ |
| 4 保存 | 跳详情页，「提醒设置」琥珀高亮「尚未设置服药计划」 | ✅ 全 App 最亮的一笔：诚实高亮而非伪装完成 |
| 5 配提醒 | 5 段频次选择器 11sp、快捷按钮 26dp | ❌ 最关键的配置控件用全 App 最小字号与点击区 |
| 6 保存 | Toast「已保存」飘过时页面已经退掉 | ⚠️ |
| 7 打卡 | 42dp 的 ✓ 圆钮 | ⚠️ 不达 48dp |
| 8 想改设置 | **找不到入口**（要绕到统计报表的齿轮） | ❌ 出口端断裂 |

**结论：核心链路是通的，但漏斗在第一屏（入口）和最后一屏（出口）各断一次。**

### 4.2 老年友好度

**做得好的：** 零网络权限的隐私叙事贯穿文案；"未配置"显式高亮不伪装完成；状态随墙上时钟自动刷新；主提交按钮 52dp 全宽——全 App 做得最好的部分。

**拦路虎（按严重度）：** 字号体系照抄 Material 默认（bodyMedium 13sp，另有 12 处硬编码 11/12/13sp）；最小触摸目标 25dp；1.23:1 的状态文字；TalkBack 噪音（标签念两遍、每卡念一次"查看详情"）与漏报（错误只靠颜色）；大字号下布局崩（统计格碎行、步进器裁掉"120"、日期条横移）；术语四个名字。

**结论：功能可用性合格，可及性不合格。** 最小改造（半天工作量、纯删加）：Type.kt 整体放大一档 + 删全部硬编码 fontSize；9 处小按钮 48dp；outline 不作前景色；恢复齿轮 + FAB 改「补记服药」；加 fontScale=1.8 快照集进 CI。

### 4.3 值得保持的设计（勿在"优化"中破坏）

- 通知用两条独立渠道实现静音/强提醒（渠道重要性创建后不可改，这个坑踩对了）；
- 「重要提醒」穿透夜间免打扰——胰岛素、抗凝药的刚需；
- 漏服按自然日结算（当地 0 点）而非"超时 2 小时判死"——比多数竞品的"超时即失败"更合乎人的心智；
- "没配计划"琥珀高亮、"0/0 不渲染成 100%"、「跟随全局」做成可见档位——**把"静默降级 = 给用户虚假的保证"当成设计纪律**，这在同类产品里罕见；
- 权限自检页文案其实已把后果说清（"闹钟会照常响铃但屏幕上什么都不会出现"）——问题只是没人走到那里。

---

## 5. 提醒可靠性时间线（五种场景的真实行为）

| 场景 | 预期 | 真实行为 |
| :--- | :--- | :--- |
| 设备重启 | BOOT → 立即对账 → 补响 | 机制通，但补响只覆盖 `now-2h`；**重启时距上次服药 >2h 的剂量完全无提醒**（P1-9） |
| 进程被杀 | 闹钟照常唤醒 | ✅ 做得正确：intent 只带业务键、槽位信息全部现查库，无过期数据风险 |
| 时区变更 | 按新时区墙钟响 | 方向正确（对账重算 scheduledTs + 同 Uri 重排）；但 **DST 切换不发广播**，命中即晚 1 小时（P1-10） |
| 通知权限被拒 | 明确告知 + 降级 | **最差场景**：`notify()` 静默空操作 → 假阳性成功日志 → 30 秒唤醒风暴 ×2 小时（P0-5）；权限只请求一次、被拒两次后系统对话框永不再现，App 零感知 |
| 精确闹钟权限被拒 | 降级仍准点 | ✅ `setAlarmClock` 在 Doze 下仍准点，不漏提醒；⚠️ Android 12 恒落此档（P1-11） |

**提醒链路的最强处是身份层**（内容寻址 Uri + DB 唯一索引 + 不信任 extras——把"闹钟互相覆盖/取消"整类缺陷从结构上消灭）；**最弱处是输出端**（把"调用了 notify()"当成"用户收到了提醒"，且三档降级的可见性只覆盖了权限、没覆盖通知出口）。

---

## 6. 竞品对标

> 调研受限说明：developer.android.com 主站与部分海外站点不可达，改用官方中文镜像与可用来源交叉验证；查不到的一律标「未查到」。

### 6.1 能力矩阵（CarroMed vs 主要竞品）

| 能力项 | CarroMed | Medisafe | MyTherapy | 药点点 | 用药助手(丁香园) |
| :--- | :---: | :---: | :---: | :---: | :---: |
| 重复规则（每天/隔N天/每周几天/周期/按需） | ✅ 5 种 | ◐ | ◐ | ✅ 多 1 种"每月指定日" | — |
| 多时段 + 每时段独立剂量 | ✅ | ◐ | ◐ | ✅ | — |
| 餐前/随餐/空腹关系 | ✅ 按时点标注 | 未查到 | ◐ | ❌ | — |
| 按疗程自动停 | ✅ | 未查到 | 未查到 | ✅ | — |
| **补响/重复响铃升级** | ◐ 仅补响 1 次 | 未查到 | 未查到 | ✅ **持续提醒** | — |
| 稍后提醒 Snooze | ✅ 6 档+自定义 | 未查到 | 未查到 | ◐ | — |
| 漏服补录 | ✅ 近 7 天 | 未查到 | 未查到 | ◐ | — |
| 依从率 | ✅ | ✅ 主打 | ◐ | ◐ | — |
| **准时率 vs 服用率** | ❌ | 未查到 | 未查到 | 未查到 | — |
| 库存/余量追踪 | ✅ 不可变台账 | ✅ | ✅ | ❌ | — |
| 低库存预警 / 补货预测 | ✅ / ◐ | ✅ | ✅ | — | — |
| **家人/照护者协作** | ❌ | ✅ 多档案 | ✅ 团队 | ❌ | — |
| **药物相互作用** | ❌ | ✅ | 未查到 | ❌ | ✅ |
| 条码/照片识别 | ❌ | ◐ 硬件联动 | ✅ 扫处方药盒 | ❌ | — |
| **完全离线 + 不采集** | ✅ 无网络权限 | ❌ | ❌ | ◐ | ✅ 查询离线 |
| 通知隐私伪装（隐藏药名） | ❌ | 未查到 | 未查到 | ✅ | — |
| 语音朗读 / 锁屏全屏提醒 | ❌ / ❌ | 未查到 | 未查到 | ✅ / ✅ | — |

### 6.2 CarroMed 的真实优势（可演示级别的）

1. **提醒内核是真做扎实的**：`USE_EXACT_ALARM` 的选择来自一次真实事故复盘（改之前 `canScheduleExactAlarms()` 恒 false，67 个闹钟全部带 +1h 窗口，第一承诺在 Android 12+ 整体失效）；14 天闹钟视野 + 触发后续期 + 周期对账三层保险；验收时长特意改成 21 天因为"验收时长不能等于机制边界"——这种工程自觉同行很难有。
2. **历史不可改写 + 库存事件溯源**，`SUM(change_amount) == currentStock` 被单测守着。
3. **零第三方运行时库、零网络权限、allowBackup=false**——"物理断网"是多数竞品做不到的隐私叙事完整性。
4. **有效期/临期提醒 + 库存账本**——国内特色，海外同类少见。
5. **CSV(UTF-8 BOM)/JSON 导出**，对要拿记录给医生看的老人，比"导出失败请联网"现实得多。

### 6.3 短板清单（按对中老年用药人群的实际影响排序）

| # | 差距 | 竞品做法 | 建议与成本 |
| :--- | :--- | :--- | :--- |
| 1 | **到点未确认后不再响**（响一次 + 30s 补响一次） | 药点点"持续提醒 + 突破静音"；Apple 提醒 Urgent 分级 | 有限升级：准点 → +5min → +15min，各带三按钮。**成本：中**（复用 `AlarmScheduler.Kind.SNOOZE` 分支）。与第一承诺最贴合的一条 |
| 2 | **无相互作用警示** | Medisafe、用药助手都有；NMPA《2026 年公众十大用药提示》第 1 条就是"老年共病与多重用药" | 先做最便宜的一半：内置几十条"常见需避开组合"本地小表（华法林×NSAIDs、复方感冒药叠加对乙酰氨基酚），新增药品时对已有药品做本地检查。**成本：低** |
| 3 | **录入门槛全手输** | MyTherapy 扫处方/药盒条码自动建计划 | 不必做 OCR（对老人是重交互）；最低成本是内置几百条常用药通用名离线小词典 + 拼音联想，命中自动填剂型/规格。**成本：低** |
| 4 | **无语音朗读、无锁屏全屏提醒** | 药点点自定义音效 + 突破静音 + Widget | TTS（需降级路径）+ 真正接 `USE_FULL_SCREEN_INTENT`（Android 14 起需 `canUseFullScreenIntent` 授权）。**成本：中** |
| 5 | **无准时率**——"依从率 100% 但全是下午三点补的"是虚假安全感 | 未查到任何一家明确区分（可能是空白市场） | 在 `DayStatusBreakdown` 旁增列 `onTime`，判据用已有 `advanceMinutes` + 可配 ±30 分钟窗口。**先落《统计口径规范》再动代码**。成本：低 |
| 6 | 无"每月指定日"、无例外日历、无 Widget | 药点点三者都有 | 低/中/中，按需排期 |
| 7 | 无家人协作 | Medisafe/MyTherapy 核心卖点 | **建议明确不做**（见 6.4） |

### 6.4 战略判断：不该追什么

**CarroMed 不该追"账号 + 云同步 + 社交提醒 + 购药闭环 + AI 荐药 + 家人代管"这一整片——追一个，就亲手拆掉它唯一的护城河。**

- 引入账号或网络权限，`allowBackup=false` 和"不申请 INTERNET"两行承诺同时作废，而这是目前唯一无法被抄走的差异化；
- "购药渠道"会立刻拖进药品电商合规（互联网药品信息服务、处方流转、实名）——NMPA 2026 第 10 条刚明确警告直播购药与 AI 荐药风险，这不是加分项是新负债；
- 家人代管需要可信远端身份与推送通道，纯离线架构给不了；
- Android 平台面：Health Connect 40 类数据类型中**没有任何用药类型**（药物数据只能走 FHIR 原始资源透传）——不存在"等平台统一了再做"的窗口期，纯离线单设备路线在 Android 上是安全的；
- 真正该抄的只有三样且都不破坏定位：**本地相互作用提示、持续提醒 + 通知伪装（隐藏药名）、Urgent 分级**——恰好都发生在"响铃那一刻"和"本地一张小表"上，一个字节都不用发出去。

**一句话：CarroMed 的战场不在"管得更多"，而在"响得更狠、录得更省、错得更有据"。**

---

## 7. 修复路线图

### 第一批（本周，全部是小改动大收益）

1. **P0-1 备份分片**（`chunked(50)`）+ 300 行往返测试；
2. **P0-6 release 真验证**：建 `proguard-rules.pro` + `isMinifyEnabled = true` + 修正文档三处不实记录；
3. **P0-7 恢复今日页齿轮**（10 行）+ FAB 文案改「补记服药」（P1 相关文案统一顺带）；
4. **P0-8 刷新快照基线 + 走查词表从 strings.xml 生成 + 失败 exit 非 0**；
5. **P0-5 notify 返回值 + 补响计数终止**（比想象中局部，`AlarmScheduler.Kind` 已有 SNOOZE 可复用）；
6. **P0-4 剂量量化守正**（`require(Dose.of(x).milli > 0)` + 溢出钳制，三个入口各一行）；
7. **P1-14 outline 换 onSurfaceVariant**（8 处机械替换）+ **P1-15 九处 48dp**（机械改）。

### 第二批（需要小设计）

8. P0-3 补录结账通路（找槽位关联 + `restateSlot` 放宽 `EXPIRED`）；
9. P0-2 删药留史（先产品拍板，改 `RESTRICT` 或加二次确认）；
10. P1-1 恢复失败重建闹钟；P1-13 重复时点恢复校验；
11. P1-9 未响槽位当日汇总通知（比放宽 2h 窗口更符合产品）；
12. P1-18 Locale.ROOT 全面替换 + 测试钉 locale；
13. P1-16 反馈统一 Snackbar；P1-17 错误态组件。

### 第三批（产品向）

14. 有限升级提醒（准点 → +5 → +15min）；准时率口径（先写规范文档）；相互作用本地小表；药品名离线词典联想；TTS + 全屏 Intent；通知伪装开关。

### 工程基建（穿插进行）

15. 最小 CI（`testDebugUnitTest` + `assembleRelease`）；version catalog；`robolectric.properties` 钉 SDK；`checkDomainPurity` Gradle 任务；jqwik 反例入库。

---

## 8. 核验记录

**主审亲自复核过证据的条目**（当前 HEAD `ed1d380`）：

- P0-1：Room 生成 SQL 11 占位符/行 + `DataExporter.kt:836/852/867` 整表插入 ✅
- P0-2：生成 DDL `ON DELETE CASCADE` 挂两张事实表 ✅（`AppDatabase_Impl.java:74,78`）
- P0-3：`DoseTrackingService.kt:752` `slotId = null` + `DoseSlotDao.kt:226` 放行 `EXPIRED` ✅
- P0-4：`Dose.kt:52` `Math.round(value * 1000f)` ✅
- P0-5：`Notifications.kt:221/246` `runCatching` ✅
- P0-6：`isMinifyEnabled = false` + `proguard-rules.pro` 不存在 + release 无 mapping.txt ✅
- P0-7：`TodayScreen.kt:99` 声明后全文无引用 ✅
- P0-8：快照最后提交 `99254c1`（18:43）早于 7 个后续 UI 提交 ✅
- P1-4：`StatsEngine.kt:140` 哨兵 `-1` + `:127` 无钳制 ✅
- P1-5：`StatsEngine.kt:448` `Math.round(7.0/n).coerceAtLeast(1)` ✅
- P1-14：8 处 `colorScheme.outline` 作文字色 + `OutlineLight = 0xFFE2E8F0` ✅
- P1-15 相关字符串：`today_manual_log`="添加"、`today_first_run_cta`="添加药品" ✅

**本次未能验证、需实测确认的项**：DST 切换与 Android 12 降级的真机行为（推断自平台文档与代码）；`today_empty.png` 等快照与当前 UI 的逐像素差异（快照已确认过期，结论以代码为准）；竞品矩阵中标注「未查到」的格子。

**评分依据**：六路深审独立打分（数据 6.5/领域 6/提醒 7/UI 5.5/工程 7），主审综合 P0 的数量与验证状态调整（数据层因恢复通道损坏下调至 5.5）。
