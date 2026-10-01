# CODE-REVIEW-20261001-bda —— 业务逻辑、数据层与提醒链路遗留问题审阅报告

> **审阅日期**：2026-10-01 13:30 (GMT+8)  
> **代码基线**：`88ddee5`（已合入 `orsbf` 25 项快赢修复之后）  
> **审阅范围**：业务逻辑（服务层、统计引擎、打卡闭环）、数据层（Room 实体、外键契约、DAO 策略、导入导出）、提醒链路（AlarmScheduler, AlarmReconciler, AlarmReceiver, Notifications, BootReceiver）  
> **审阅性质**：深入源码现场与证据链闭环，聚焦尚未修复、真实存在且直接关乎产品承诺的核心缺陷。

---

## 订正说明（2026-10-01 13:39 GMT+8）

本文经逐条回源码比对后修订，基线同为 `88ddee5`（工作区未改动源码）。全文共 14 条结论，核对结果：**10 条完全属实**（行号引用几乎逐条命中）、**3 条表述/范围需修正**、**1 条证伪**。修订处均以 `【订正】` 标注。

**核对方法**：对每条结论的「代码位置」逐一打开对应文件的行，比对代码事实；对推断出的「后果」再回溯其调用链是否可达。

| 项 | 原结论 | 订正 |
| :--- | :--- | :--- |
| 二.3 备份重复时点丢提醒 | P1 待修复 | **证伪 —— 校验已存在**（见该节） |
| 一.4 补打卡无时间窗 | P1 | 结论成立；「日期条无限向前滚动」表述修正为「日历 sheet 可无下界回翻」 |
| 三.3 Android 12 缺权限 | P1 | 缺声明属实；「无修复引导」不成立，且该声明为**有意取舍**（见该节） |
| 二.2 REPLACE 级联 | P1 | 补注：级联删除仅对**主表 `medications`** 的 REPLACE 成立 |

---

## 零、执行摘要与风险矩阵

在经历多轮代码审查与快赢修复后，CarroMed 的核心骨架（内容寻址闹钟、三层时序解耦、局部 UPDATE）表现出极高水准。但在**深层业务逻辑自洽**、**极端场景提醒可靠性**以及**不可变历史承诺的底层约束**上，仍存在数个直接击穿产品承诺的严重问题。

| 领域 | 评级 | 最严重问题 | 违背承诺 / 核心危害 |
| :--- | :---: | :--- | :--- |
| **数据层** | **高危** | **删药级联删除历史（P0）**<br>`MedicationDao` / `SchedulePolicyDao` 等多处使用 `OnConflictStrategy.REPLACE`（P1） | 击穿「吃过的药永不丢失、永不篡改」承诺；<br>主表 `medications` 的 REPLACE 底层触发 DELETE，进而级联删除历史台账 |
| **业务逻辑** | **高危** | **补录与槽位脱节（P0）**<br>**连续打卡天数（Streak）假指标（P0）**<br>跨零点推迟槽位在次日清单中隐形（P1） | 导致依从率说谎且同剂药可二次打卡双扣库存；<br>停药数月仍报全勤连续误导健康决策；<br>跨日推迟提醒后用户无法在界面操作打卡 |
| **提醒链路** | **高危** | **关机超 2 小时静默漏提醒（P1）**<br>`AlarmReceiver` 与 `AlarmReconciler` 暂停判据分裂（P1）<br>Android 12/12L 未声明精确闹钟权限致降级（P2，已知取舍） | 关机超 2 小时开机后无闹钟、无通知、无角标，用户无感知漏服；<br>跨夜推迟或暂停场景下通知被静默掐死；<br>API 31/32 上降级为 `setAlarmClock`（仍准点，状态栏常驻闹钟图标） |

---

## 一、业务逻辑层（Business & Domain Logic）存在的问题

### 1. 【P0 严重】补录过去的服药「不认账」：同日槽位既不结清也不关联，导致依从率说谎与同剂药双扣库存
- **代码位置**：
  - `app/src/main/kotlin/com/mcxiaoke/carromed/core/domain/service/DoseTrackingService.kt:764` (`logManualDose` 写死 `slotId = null`)
  - `app/src/main/kotlin/com/mcxiaoke/carromed/core/data/dao/DoseSlotDao.kt:224` (`markCompletedIfOpen` 放行 `EXPIRED`)
  - `app/src/main/kotlin/com/mcxiaoke/carromed/core/domain/service/DoseTrackingService.kt:459` (`restateSlot` 拒绝 `EXPIRED`)
- **证据与因果链**：
  1. `logManualDose` 在记录补录服药时，硬编码生成 `slotId = null` 的 `DoseRecordEntity`，完全不与当天的既有排班槽位关联；
  2. 槽位状态更新 SQL `markCompletedIfOpen` 明确包含 `status IN ('PENDING', 'SNOOZED', 'EXPIRED')`；
  3. 改判入口 `restateSlot` 却限定 `if (slot.status != SlotStatus.COMPLETED && slot.status != SlotStatus.SKIPPED) return false`。
- **后果表现**：
  - **后果 A（依从率对用户说谎且无法自愈）**：昨天某顿药错过了，午夜跨天后被结算为 `EXPIRED`（漏服）。用户今天在补录界面补记了昨天的服药，虽然流水扣了库存、事实表记了服药，但昨天的槽位**依然定格在 `EXPIRED`**！统计模块（`StatsEngine` 依从率基于槽位）依然判定昨天漏服！用户明确在 App 里补录了，依从率却认定没吃；用户若试图在记录详情页通过“改判”来修正，又被 `restateSlot` 直接拒绝。
  - **后果 B（同剂药双扣库存、重复记账）**：用户在补录页补记后（扣除 1 次库存），如果在历史清单、日历中看到昨天的该槽位依然显示“已逾期”，顺手点击了该槽位的“服用”打卡，`markCompletedIfOpen` 再次判定通过，生成第 2 条 COMPLETED 记录并**再次扣除库存**！同一剂药被记录两次、扣减两次库存。现有库存不变量测试（仅检查 `SUM(change_amount) == balance`）无法拦截此漏洞。
- **修复方案**：
  - `logManualDose` 写入前，先检索 `(medicationId, actualDate, 邻近计划时间)` 的开放或逾期槽位；若命中则优先转走槽位结算通路（复用 `takeDose` 幂等锚点）；
  - 同时放宽 `restateSlot`，允许对 `EXPIRED` 槽位改判为 `COMPLETED` 或 `SKIPPED`。

---

### 2. 【P0 严重】连续服药天数（Streak）逻辑缺陷：停药数月仍报满贯连续、医嘱跳过被严苛清零
- **代码位置**：`app/src/main/kotlin/com/mcxiaoke/carromed/core/domain/engine/StatsEngine.kt:384-399` (`calculateStreak`)
- **证据与因果链**：
  1. 回溯循环 `while (daysChecked < maxLookbackDays && !currentDate.isBefore(earliestDate))` 中，当某天没有槽位记录时（`b == null || b.isEmpty()`），代码无条件 `currentDate = currentDate.minusDays(1); daysChecked++; continue;`；
  2. 当天只要有跳过（`todayBreakdown.skipped > 0`），代码直接 `return 0`。
- **后果表现**：
  - **停药数月报假满贯**：用户完成 30 天疗程停药或将药品归档。3 个月后打开 App，算法跳过中间无排班的 90 天，直接回溯连接到 3 个月前的记录，在首页顶栏常驻展示「★ 30 连续天数」并夸赞「你做得很好！保持良好节奏」。患者数月未服药，系统却报全勤连续，形成虚假健康指标；
  - **医嘱跳过被错误清零**：用户因身体检查（如需空腹验血）主动遵医嘱在 App 点击“跳过本次”，当天 `skipped > 0`，系统立即将连续几个月积累的 Streak 彻底清零，将遵医嘱的合规操作当作违约惩罚；
  - **低频方案断崖**：对于隔 30 天用药（`INTERVAL = 30`）的患者，365 天回溯预算全被空档天数消耗，Streak 永远只能累计到 12 次以内。
- **修复方案**：
  - 引入最大允许空档天数判定（依据药品排班策略的最大周期间隔推导），连续无排班超过阈值即断签；
  - 调整断签规则：主动跳过（SKIPPED）不断签，仅漏服（MISSED/EXPIRED）断签。

---

### 3. 【P1 重要】跨零点推迟的槽位在次日「今日清单」中完全隐形
- **代码位置**：
  - `app/src/main/kotlin/com/mcxiaoke/carromed/ui/screen/today/TodayViewModel.kt:187`
  - `app/src/main/kotlin/com/mcxiaoke/carromed/core/data/dao/DoseSlotDao.kt:143` (`observeSlotsForDate`)
- **证据与因果链**：
  - 今日页通过 `slotDao.observeSlotsForDate(dateStr)` 获取清单，SQL 限制严格为 `WHERE scheduled_date = :dateStr`；
  - 若用户昨晚 23:30 服药推迟到次日凌晨 00:30：槽位 `scheduled_date` 仍是昨日，但推迟唤醒目标时刻 `snooze_until_ts` 属于今天。
- **后果表现**：
  - 次日 00:30 闹钟准点响铃，用户点击通知或解锁进入 App 查看“今日清单”，在今天的列表里**完全看不到这个药**；
  - 用户必须向后翻回昨天的历史才能找到该药打卡。在今日页面无法操作，产生极大困惑。
- **修复方案**：
  - 今日清单的数据源查询调整为：`scheduled_date = :today OR (status = 'SNOOZED' AND date(snooze_until_ts / 1000, 'unixepoch', 'localtime') = :today)`，将跨日推迟至今日的开放槽位并入今日清单。

---

### 4. 【P1 隐患】历史槽位补打卡缺乏时间窗口，且 `isRetrospective` 恒为 false
- **代码位置**：
  - `app/src/main/kotlin/com/mcxiaoke/carromed/ui/screen/today/TodayViewModel.kt:238`
  - `app/src/main/kotlin/com/mcxiaoke/carromed/core/domain/service/DoseTrackingService.kt:217` (`takeDose`)
- **证据与因果链**：
  - **【订正】** 原表述「今日页顶部日期条可无限向前滚动」不准确：顶部周条实为 ±3 天窗口（`TodayViewModel.kt:238` `(-3L..3L)`）。**真正无下界的是历史日历 sheet** —— `DoseHistoryCalendarSheet.kt:99` 的 `minusMonths(1)` 可无限回翻，且 `TodayViewModel.selectDate` 只对「未来」做过钳制（`:123-124`）。用户仍可借助日历 sheet 回翻到任意久远的日期；
  - 用户翻到几个月前未打卡的槽位点击打卡，`takeDose` 毫无时间窗口守卫（相比之下手动补录有 7 天限制 `MANUAL_DOSE_BACKFILL_DAYS = 7`）；
  - `takeDose` 插入服药事实时，写死 `isRetrospective = false`（`DoseTrackingService.kt:217`）。
- **后果表现**：
  - 用户可以补打半年前的槽位；
  - 半年前的补录在历史事实与导出台账中被标记为实时打卡，破坏事实真实性。
- **修复方案**：
  - `takeDose` 增加时间窗守卫（与手动补录对齐，如最多补记最近 7~14 天）；
  - 根据 `slot.scheduledDate < todayStr` 自动推导 `isRetrospective = true`。

---

## 二、数据层（Data Layer & Persistence）存在的问题

### 1. 【P0 致命】删除药品通过外键级联（CASCADE）物理抹除所有服药事实与库存台账
- **代码位置**：
  - `app/src/main/kotlin/com/mcxiaoke/carromed/core/data/entity/DoseRecordEntity.kt:21` (`onDelete = ForeignKey.CASCADE`)
  - `app/src/main/kotlin/com/mcxiaoke/carromed/core/data/entity/InventoryTransactionEntity.kt:25` (`onDelete = ForeignKey.CASCADE`)
  - `app/src/main/kotlin/com/mcxiaoke/carromed/ui/screen/detail/MedicationDetailViewModel.kt:245` (`medDao.deleteById(med.id)`)
- **证据与因果链**：
  - `DoseRecordEntity` 与 `InventoryTransactionEntity` 在 Room 实体定义上，外键关联 `MedicationEntity` 均配置了 `onDelete = ForeignKey.CASCADE`；
  - 详情页 `deleteMedication` 直接调用 `medDao.deleteById`。
- **后果表现**：
  - 用户用完一个疗程后在详情页点击“删除药品”，SQLite 底层级联触发，将该药品关联的全部服药事实、跳过记录、库存进出流水物理删除；
  - 彻底违背「吃过的药永不丢失、永不篡改」的产品承诺，账本不再守恒。
- **修复方案**：
  - 外键改为 `onDelete = ForeignKey.RESTRICT`；
  - 药品详情页主推「停药归档」（`isArchived = true`）；若存在历史记录，禁止物理硬删除。

---

### 2. 【P1 高危】DAO 层插入操作广泛使用 `OnConflictStrategy.REPLACE`
- **代码位置**：
  - `app/src/main/kotlin/com/mcxiaoke/carromed/core/data/dao/MedicationDao.kt:58,61`
  - `app/src/main/kotlin/com/mcxiaoke/carromed/core/data/dao/SchedulePolicyDao.kt:19,22,25`
  - `app/src/main/kotlin/com/mcxiaoke/carromed/core/data/dao/DoseRecordDao.kt:29,316`
- **证据与因果链**：
  - SQLite 的 `INSERT OR REPLACE` 冲突时执行先 DELETE 再 INSERT；
  - 一旦主表主键冲突，底层 DELETE 立即激活子表的 `ON DELETE CASCADE`。
- **【订正】范围界定**：级联删除历史台账的因果链**仅对主表 `medications` 的 REPLACE 成立**。`dose_records` 自身是子表，`inventory_transactions.record_id` 并无外键约束，故 `DoseRecordDao` 的 REPLACE 不会级联到台账（其风险是覆盖同 id 记录行本身）。行号引用无误。
- **后果表现**：
  - 一旦任何业务代码误用了带已有主键的实体调用 `insert`，该药名下的槽位、记录、流水被瞬间级联抹除。
- **修复方案**：
  - 主表插入方法全面改用 `OnConflictStrategy.ABORT`，更新操作必须显式走 `@Update`。

---

### 3. 【已闭环 · 原报告误判】恢复备份重复服药时点的保护**已存在**，非遗留缺陷
- **【订正】结论证伪**：原报告称「`validateBackup` 阶段未校验同一 policy 下是否存在相同时点」，与源码不符 —— 该保护**早已实现**。
- **代码位置（实际实现处）**：
  - `app/src/main/kotlin/com/mcxiaoke/carromed/core/data/DataExporter.kt:711-720`（`policy_times` 按 `(policyId, timeOfDay)` 去重 → 报 `DUPLICATE_POLICY_TIME`）
  - `app/src/main/kotlin/com/mcxiaoke/carromed/core/data/DataExporter.kt:624-634`（`dose_slots` 按 `(medicationId, scheduledDate, scheduledTime)` 去重 → 报 `DUPLICATE_SLOT_KEY`）
  - `app/src/main/kotlin/com/mcxiaoke/carromed/core/data/DataExporter.kt:157`（枚举定义 `DUPLICATE_POLICY_TIME(blocksRestore = true)`）
  - `app/src/main/kotlin/com/mcxiaoke/carromed/core/data/entity/DoseSlotEntity.kt:47-50`（唯一索引，属实）
  - `app/src/main/kotlin/com/mcxiaoke/carromed/core/data/dao/DoseSlotDao.kt:31`（`insertAll` = IGNORE，属实）
- **核对说明**：原报告的两条事实前提（唯一索引、`insertAll` 用 IGNORE）成立，但由它们推出的「会静默丢提醒」结论不成立 —— 恢复前 `validateBackup` 会先拦下（`blocksRestore = true`，见 `restoreBackup` 对 `blocksRestore` 的过滤）。原报告提出的「修复方案」`DUPLICATE_POLICY_TIME(blocksRestore = true)` **正是仓库中已存在的实现**。
- **【订正】处置**：本条从「待修复」移除，不需再排期。`docs/CODE-REVIEW-20260930-ocsbf.md` / `osbf P3-5` 语境下该问题已闭环。

---

### 4. 【P2 隐患】CSV 导出未开启数据库事务与 Locale 格式化风险
- **代码位置**：`app/src/main/kotlin/com/mcxiaoke/carromed/core/data/DataExporter.kt:262,310` (`exportDoseRecordsCsv`, `exportInventoryLedgerCsv`)
- **证据与因果链**：
  - 两个 CSV 导出方法在事务外分别查 `getAllMedications()` 和明细记录；
  - 文件名时间戳格式化使用 `SimpleDateFormat(..., Locale.getDefault())`。
- **后果表现**：
  - 并发写入时可能发生跨表脏读；在非公历 Locale 下文件名可能包含异常字符或佛历年份。
- **修复方案**：
  - 导出方法统一包裹在 `db.withTransaction` 内；时间戳格式化钉死 `Locale.ROOT`。

---

### 5. 【P2 隐患】备份验证缺少 `recordId` 悬空引用校验
- **代码位置**：`app/src/main/kotlin/com/mcxiaoke/carromed/core/data/DataExporter.kt:610-614` (`validateBackup`)
- **证据与因果链**：校验台账表时，只校验了 `medicationId in medIds`，未校验 `inventoryTransactions.recordId in recordIds`。
- **后果表现**：悬空的 `recordId` 会导致后续打卡撤销冲正找不到对应原始事实，破坏台账净额守恒。
- **修复方案**：补齐 `inventoryTransactions.recordId` 悬空校验。

---

## 三、提醒与闹钟链路（Alarm & Notification）存在的问题

### 1. 【P1 严重】关机/重启超过 2 小时，漏掉的服药完全无提醒、静默待办变成黑洞
- **代码位置**：`app/src/main/kotlin/com/mcxiaoke/carromed/core/alarm/AlarmReconciler.kt:92,233,366` (`CATCHUP_WINDOW_MS = 2小时`)
- **证据与因果链**：
  - 补响下界为 `catchupFloor = now - 2 * 60 * 60 * 1000L`；
  - 过期结算线为当日 0 点。
- **真实场景与后果**：
  - 用户晚上 23:00 关机睡觉，原定早晨 07:00 服药；早晨 09:30 开机；
  - 开机后 `BootReceiver` 触发对账，07:00 属于今天，不结算为 EXPIRED，保持 PENDING；
  - 但 07:00 距离 09:30 已经过去了 2.5 小时，超出了 2 小时的 `catchupFloor`；
  - 导致：**不响闹钟、不发通知、不弹气泡**；
  - 该槽位进入无任何提醒的“静默待办”，用户开机后没有任何感知。如果用户今天没有主动打开 App，这剂药就彻底被静默漏服。违背产品第一承诺「到点一定响，不静默漏提醒」。
- **修复方案**：
  - 开机或回到前台时，针对今天所有已过点且未确认的槽位，聚合发送一条汇总通知（如「今天有 1 次服药尚未提醒」）。

---

### 2. 【P1 严重】`AlarmReceiver` 与 `AlarmReconciler` 暂停判据口径分裂
- **代码位置**：
  - `app/src/main/kotlin/com/mcxiaoke/carromed/core/alarm/AlarmReconciler.kt:244-249`（按槽位计划日判断）
  - `app/src/main/kotlin/com/mcxiaoke/carromed/core/alarm/AlarmReceiver.kt:80` (`val paused = overview?.isPausedOn(LocalDate.now()) == true`)
- **证据与因果链**：
  - `AlarmReconciler` KDoc 明确警告必须按**槽位自己的 scheduledDate** 判定暂停；
  - 但在真正的闹钟接收器 `AlarmReceiver` 中，却硬编码了 `LocalDate.now()`。
- **后果表现**：
  - 当跨日推迟或补响发生时，`AlarmReconciler` 按槽位日期判断认为未暂停并排了闹钟；
  - 到点闹钟响铃唤醒 `AlarmReceiver`，`AlarmReceiver` 却拿当天的墙上时钟 `LocalDate.now()` 去判断，认为处于暂停期，直接 `return@launch` 静默退出！导致闹钟醒了但通知被强行吞掉。
- **修复方案**：
  - `AlarmReceiver` 统一改用 `overview.isPausedOn(LocalDate.parse(slot.scheduledDate, ...))` 进行判定。

---

### 3. 【P2 · 已知兼容取舍】Android 12/12L (API 31/32) 未声明 `SCHEDULE_EXACT_ALARM`
- **代码位置**：`app/src/main/AndroidManifest.xml:16`
- **证据与因果链**：清单文件只声明了 Android 13+ 引入的 `USE_EXACT_ALARM`，没有声明 API 31/32 的 `SCHEDULE_EXACT_ALARM`（属实，行号无误）。
- **后果表现**：
  - 在 Android 12 和 12L 设备上，系统不认识 `USE_EXACT_ALARM`，导致 `canScheduleExactAlarms()` 恒为 `false`；
  - 提醒降级到 `setAlarmClock` 档位（`AlarmScheduler.kt:270-274`：`SDK < S` 返回 EXACT，`>= S` 且无权限返回 ALARM_CLOCK）。该档位仍准点，但系统状态栏会常驻闹钟图标。
- **【订正】两点修正**：
  1. 原表述「权限自检页永久报黄『降级中』且**无修复引导**」**不成立**：`PermissionCheckScreen.kt:136-140` 在 `isDegraded && needsExactAlarmRequest` 时会给出「去授权」按钮，而 API 31/32 上 `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` 存在（`:206-208`）⇒ 有引导入口。黄灯（降级态）属实，但「无引导」有误。
  2. 该声明变更是**有意取舍**：`AlarmScheduler.kt:16-28` 的 KDoc 明确记录了「改声明 `USE_EXACT_ALARM`（闹钟类应用免授权），让第一档成为常态」。故本条定性从「缺失声明缺陷」下调为**跨版本兼容取舍**，是否补回 `SCHEDULE_EXACT_ALARM` 属产品决策。
- **修复方案（可选）**：
  - 若需覆盖 API 31/32 的精确档，补上 `<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />` 并保留 `USE_EXACT_ALARM`。

---

### 4. 【P1 重要】点击通知销毁重建 Activity 且无深链接，丢失上下文
- **代码位置**：`app/src/main/kotlin/com/mcxiaoke/carromed/core/alarm/Notifications.kt:189-194`
- **证据与因果链**：
  - 点击通知的 PendingIntent 带有 `FLAG_ACTIVITY_CLEAR_TOP`，而 `MainActivity` 默认是 standard 模式且没有配置深链接分发。
- **后果表现**：
  - 用户如果正在使用 App 填表单，通知弹出点击后，当前界面直接被销毁重创；
  - 通知中没有携带 `slotId` / `medicationId`，点击后永远只能回到首页今日列表，无法直接定位到该药物或该记录。
- **修复方案**：
  - `MainActivity` 声明 `launchMode="singleTask"` 并处理 `onNewIntent`；`Notifications` 传入深链接 URI。

---

### 5. 【P1 体验与安全】今日页无“提醒链路健康状态”感知入口
- **代码位置**：`app/src/main/kotlin/com/mcxiaoke/carromed/ui/screen/today/TodayScreen.kt`
- **证据与因果链**：
  - 今日页没有任何关于闹钟精度、通知权限、电池白名单状态的聚合指示。
- **后果表现**：
  - 第一承诺是“到点一定响”，但首页对提醒链路是否健康零感知；
  - 用户从通知被系统静默关闭到发现漏服，首页没有任何提示。
- **修复方案**：
  - 在今日页顶栏聚合显示健康状态徽标，链路降级时显示黄色指示，点击直达自检页。

---

## 四、建议实施批次

```
第一批：核心数据安全与记账防漏（高危止血）
├── 1. P0-3 补录通路结清当日槽位 + restateSlot 放宽 EXPIRED
├── 2. P0-2 删药外键改 RESTRICT + 详情页阻断硬删除并引导归档
└── 3. P1-2 DAO 插入策略全面从 REPLACE 改为 ABORT

第二批：指标自洽与闹钟可靠性提升
├── 4. P0 Streak 算法重构：基于排班周期的空档熔断 + 主动跳过不断签
├── 5. P1 修复 AlarmReceiver 暂停判据（对齐槽位日期）
├── 6. P1 开机超 2 小时未服槽位聚合通知机制
└── 7. P1 跨零点推迟槽位在今日清单中的查询并入

第三批：平台兼容与交互体验收口
├── 8. Android 12 补齐 SCHEDULE_EXACT_ALARM 声明（【订正】可选，属兼容取舍决策）
├── 9. 通知深链接与 singleTask 启动模式
├── 10. 今日页右上角提醒健康状态指示徽标
└── 11. 备份与 CSV 导出事务一致性与悬空引用防御
    （【订正】原含「重复时点阻断校验」——该项已实现，仅剩 CSV 事务/悬空引用）
```

> **【订正】批次说明**：原「二.3 备份重复时点丢提醒」经核对为**已闭环**，已从待办中移除；原「三.3 Android 12 缺权限」定性下调为可选兼容项。14 条结论中除去上述 1 项证伪、1 项降级为可选后，实际待修复 12 条。
