# CarroMed 提醒域与数据层重构设计（阶段 A）

> **文档时间**：2026-09-28 09:06 (GMT+8)
> **状态**：设计稿 · 待评审 · 尚未实施
> **触发来源**：[`docs/CODE-REVIEW-20260927-sbf.md`](CODE-REVIEW-20260927-sbf.md) 的 P0-1 / P0-3 / P0-4 / P0-5 / P0-6 / P0-7 与 P1-1 / P1-2 / P1-3 / P1-4 / P1-5 / P1-6 / P1-12
> **决策依据**：[`docs/FINAL-PRODUCT.md`](FINAL-PRODUCT.md) v3.1.0（产品唯一权威源）的 D-6 / D-7 / D-9 / D-10 / D-14 与场景 2 / 3 / 5 / 6
> **前置变更**：`AGENTS.md` 第 5 行已确认「本项目内部开发中，还未公开发布，不需要任何迁移或兼容旧版本的代码」——**本文档的所有 schema 改造按"改完删库重装"估算，不写 `MIGRATION_*`，不复用 `MigrationTest`**
> **分期**：阶段 A = 本文档主体；阶段 B = 排班模型迁移到 RFC 5545 RRULE 语义（本文档只锁定接口，不实施）
> **工程原则**（用户 2026-09-28 明确）：**测试与工具一律采用成熟库，不自造轮子**

---

## 〇、范围与不做的事

### 本期做（阶段 A）

| # | 内容 | 消除的发现 |
| ---: | --- | --- |
| A0 | 测试基建：JUnit 5 Platform + Vintage Engine + jqwik | P1-12（空测） |
| A1 | 删除 `medications.current_stock`，余额改由 `SUM(change_amount)` 派生；金额列改整数毫单位 | P0-3、P1-6、**D-7** |
| A2 | 拆出 `reminder_settings` 表（1:1），并把 `is_paused` 布尔升级为 `paused_until` 日期 | P0-5 的跨表部分、`FINAL-PRODUCT` M-02「暂停至某日」缺口 |
| A3 | 6 条细粒度写命令 + 幂等下沉到 SQL 条件更新 + **闹钟内容寻址** | P0-1、P0-2、P0-4、P0-5 的命令粒度部分、P1-1、P1-5 |
| A4 | 备份格式改 kotlinx.serialization + 导入前本地快照 | P1-14 |
| A5 | 穷举式字段保全测试 ×6 + 属性化测试（jqwik）覆盖 12 条不变量 + **DST 属性测试** | P1-12、时区语义漂移，并为 A1~A4 上机械防线 |
| A6 | **WorkManager 周期对账兜底**（兑现 `FINAL-PRODUCT` D-14 第三档） | D-14 承诺缺口、P0-2 的结构性盲区 |

> **步骤顺序调整（2026-09-28）**：A0 → A1 → **A2 → A3 → A6 → A4 → A5**。
> A2 提前于 A4 是为了把 `reminder_settings` 与备份 JSON 结构一次改完；A6 独立成步
> 是因为它是**产品承诺的缺口**而非重构的一部分，混进 A3 会让回滚无法分离。

### 本期不做（明确排除，避免 scope 膨胀）

- **阶段 B 的 RRULE 改造**（只在本文档 §6.1 锁定 `PolicyType` → RRULE 的映射契约，代码保持现状）
- **月度规则 MONTHLY** —— 外部四家方案都有，但 `FINAL-PRODUCT` M-02 未要求，详见附录 A.2
- **确定性实例 ID** —— 与 A3-5 的内容寻址二选一，详见附录 A.2
- 抽离纯 JVM `:core` Gradle 模块 —— `SlotProjectionEngine` 已是纯函数，编译期物理隔离边际收益有限，详见附录 A.6
- 提醒设置页日期先后校验（P1-11）、剂量范围校验（P1-10）—— 属 UI 层，另开
- 自动滚动备份、备份加密、CSV 脱敏开关（`FINAL-PRODUCT` D-13 / §六.4 未实现项）—— 属产品范围决策
- 「准时率」指标（`FINAL-PRODUCT` D-11 列为 v1-P0，但本项目 `README.md:320` 降级为 P2，**属待拍板项，见 §9**）

---

## 一、根因：问题不是"用了 UPDATE"，是"写路径不唯一"

### 1.1 现状写路径清单（可 grep 枚举的事实）

| 屏幕 / 入口 | 调用的命令 | 实际写入的列数 | 备注 |
| --- | --- | ---: | --- |
| 药品信息页 `AddEditMedicationViewModel:309` | `updateProfile` | **20** | 跨了 3 个提醒列 → **P0-5（漏传型）** |
| 库存页有效期 `InventoryViewModel:188` | `updateProfile` | **20** | 只想改 1 列，被迫重传 19 列 → **P0-5（被迫重传型）** |
| 提醒设置页 `ReminderSettingsViewModel:222` | `updateReminderBehavior` | 5 | 含 `is_paused` → 与下一行撞列 |
| 详情页暂停 `MedicationDetailViewModel:127` | `updatePauseStatus` | 2 | **与上一行 `is_paused` 撞车** |
| 详情页归档 `MedicationDetailViewModel:137` | `updateArchiveStatus` | 2 | ✅ 粒度正确 |
| 库存页预警线 `InventoryViewModel:209` | `updateMinStockAlert` | 2 | ✅ 粒度正确 |
| 库存追踪开关 `InventoryViewModel:151` / `RefillViewModel:113` | `updateStockTracking` | 2 | ✅ 粒度正确 |
| 打卡 / 撤销 / 补录 / 盘点 / 补药 | 流水 INSERT + `updateStock` | 2 + 流水 | ✅ 走流水，但含 read-modify-write |

**两个可验证的规律：**

1. **出错的 2 条路径，命令粒度都是 20 列；一次没错的 4 条，粒度都是 2 列。**
2. **有 2 条写路径的列，恰好就是出 bug 的列**：`is_critical_reminder` / `snooze_minutes` / `advance_minutes`（各 2 条）、`is_paused`（2 条）。

**结论：P0-5 的根因不是"忘了局部 UPDATE"，而是"同一组列存在多条写路径，且没有机制保证它们同步演进"。** 上一轮审查（`PLAN-REVIEW-20260927-v2r.md` P0-3）的修复方向是对的（整行覆盖 → 局部 UPDATE），但只解决了"一条路径覆盖全部列"，没有解决"多路径覆盖同一列"。

### 1.2 `InventoryViewModel.kt:188` 的实证（这是本设计的核心论据）

```kotlin
// 库存页只想改「有效期」一个字段
medDao.updateProfile(
    id = medId,
    name = med.name,            // 以下 19 个字段全靠手工重传
    alias = med.alias, category = med.category, form = med.form, unit = med.unit,
    colorHex = med.colorHex, defaultDose = med.defaultDose,
    description = med.description, precautions = med.precautions,
    noticeShort = med.noticeShort,
    expiryDate = expiry,                                  // ← 真正想改的只有这个
    isCriticalReminder = med.isCriticalReminder,          // :201-203 还得再查一次原值传回来
    snoozeMinutes = med.snoozeMinutes,
    advanceMinutes = med.advanceMinutes,
    minStockAlert = alert,
    updatedAt = System.currentTimeMillis()
)
```

**这不只是"多写了几个字段"，它还内含一个 read-modify-write 竞态**：`med` 来自进页面时的快照 `s.medication`。若用户在别处改了 `snoozeMinutes`，回到库存页改个有效期，就会用**陈旧值覆盖回去**。

**这类 bug 无法通过"更小心地传参"消除**，只能通过"让调用方没有机会传错"消除。这决定了 A3（细粒度命令）比 A2（拆表）更关键 —— **拆表解决的是跨表误伤，细粒度命令解决的是同表内的被迫重传与竞态。**

### 1.3 拆表能解决什么、不能解决什么

**能**：让 `is_paused / is_critical_reminder / snooze_minutes / advance_minutes` 这组"提醒运行态"列与"药品静态档案"列**物理分离**。此后 `updateProfile` 的 SET 列表里**根本不存在**这些列，`AddEdit` 与 `Inventory` 两条路径的 P0-5 一起消失；将来给档案加字段时也不会有人塞进提醒的 SET 列表。

**不能**：`updateProfile` 无论在哪个 schema 下都还是宽命令，库存页还是得重传 17 个无关字段、竞态依然存在。

**→ 两者都做。A2 是 A3 的配套设施，不是替代品。**

### 1.4 边界为什么不是"药品信息 vs 提醒行为"

按语义，正确的切分是**「药品静态档案」vs「提醒运行态」**：

| 列 | 语义 | 归属 |
| --- | --- | --- |
| `name` `alias` `category` `form` `unit` `colorHex` `iconName` `defaultDose` `description` `precautions` `noticeShort` `expiryDate` | 药品是什么 | `medications` |
| `minStockAlert` `isStockTracked` | 库存策略开关 | `medications`（仅 2 列，拆出去属过度设计） |
| `isPaused` | 药还在，只是**提醒**暂停了 | **`reminder_settings`**（现状错放在 `medications`） |
| `isCriticalReminder` `snoozeMinutes` `advanceMinutes` | 提醒行为 | **`reminder_settings`** |
| `isArchived` | 停药归档（影响列表可见性 + 统计保留） | `medications` |
| `currentStock` | — | **不存在**（见 §2.3） |

`is_paused` 归位到 `reminder_settings` 是本次拆表最实质的一处：它同时解决了「2 条写路径」和「列放错表」两个问题。

---

## 二、目标数据模型

### 2.1 表清单（7 张 → 7 张，职责重划）

| 表 | 职责 | 主键 | 唯一约束 | 写命令数 |
| --- | --- | --- | --- | ---: |
| `medications` | 药品静态档案 + 库存开关 + 归档位 | `id` | — | 4 |
| `reminder_settings` | 提醒运行态（1:1）：`is_critical_reminder` / `snooze_minutes` / `advance_minutes` / `paused_until` | `medication_id`（同时是外键） | — | 3 |
| `schedule_policies` | 服药规则（版本化） | `id` | `medication_id` + `is_active=1` 唯一 | 1 |
| `policy_times` | 规则的时点与剂量 | `id` | `(policy_id, time_of_day)` | 随策略 |
| `dose_slots` | 规则在某日的投影 + 当日可变状态 | `id` | **`(medication_id, scheduled_date, scheduled_time)` UNIQUE** | 1 |
| `dose_records` | 服药事实（**永不 DELETE**） | `id` | — | 1 |
| `inventory_transactions` | 库存台账（**永不 DELETE/UPDATE**） | `id` | — | 1 |

### 2.2 相对现状的结构性变更（5 处）

| # | 变更 | 类型 | 消除 |
| ---: | --- | --- | --- |
| 1 | **删 `medications.current_stock`** | 删列 | P0-3（守恒由定义成立）、P1-5 的一半 |
| 2 | **新增 `reminder_settings` 表**，迁出 `is_paused` / `is_critical_reminder` / `snooze_minutes` / `advance_minutes` | 拆表 | P0-5 跨表部分、`is_paused` 双写路径 |
| 3 | 5 个金额列 `REAL` → `INTEGER`（毫单位） | 改类型 | P1-6（执行 D-7） |
| 4 | `dose_slots` 复合索引改 `unique = true` | 加约束 | P2-5（槽位唯一性从内存去重升级为 DB 不变量） |
| 5 | **`is_paused` 布尔 → `paused_until` 日期** | 改语义 | `FINAL-PRODUCT` M-02「暂停至某日」的能力缺口（见 §2.6） |

**第 4 项是本设计里"顺手但重要"的一条**：现状靠 `DoseTrackingService.kt:318-322` 的 `existingSlotKeys` 内存去重，任何绕过 `reconcileSchedule` 的插入路径都能造出重复槽位 → 重复闹钟 + 重复扣库存。改成 DB 约束后，**A5 步骤的 `reconcileSchedule` 改写（幂等 diff）才真正安全**。

### 2.6 暂停需要结束日（用户 2026-09-28 拍板）

**发现**：`FINAL-PRODUCT` M-02 排程管理要求「**暂停至某日**」，而现状 `medications.is_paused` 是**纯布尔** —— 没有结束日的概念，用户只能"暂停到某天"然后靠记忆回来手动恢复。药箱页只能显示一句静态的"提醒已暂停"，既不知道何时恢复，也没有任何自动恢复的时机。

这是**产品能力缺口，且只有在 A2 动 `is_paused` 那一刻才会浮现** —— 先拆完表再加结束日，那几列要动两次。所以并入 A2 一次建对。

**数据模型**：`reminder_settings.paused_until: String?`（`yyyy-MM-dd`），语义：

| 取值 | 含义 |
| --- | --- |
| `NULL` | 未暂停（正常提醒） |
| `'2026-10-15'` | 暂停至该日**含**当天；`2026-10-16` 起自动恢复 |
| `''`（空串） | 无限期暂停，需用户手动恢复 |

> 为什么保留"无限期"而不是只允许有结束日：出差、住院这类场景就是"不知道哪天回来"。强制填日期等于逼用户编一个假日期。
>
> 为什么不直接用 `LocalDate?` 存列：与项目既有约定一致（`startDate` / `endDate` / `expiryDate` 全是 `String` + `SlotProjectionEngine.DATE_FORMATTER`），避免引入第二套日期表示。

**"是否暂停"是派生量，不是存储量**：

```
isPaused(today) := paused_until IS NOT NULL AND (paused_until == '' OR paused_until >= today)
```

**关键：这个派生判断必须出现在**所有**过滤"该不该为它排闹钟"的地方，且必须由一个函数产出。**理由**：把 `paused_until != null` 当成"已暂停"是最自然的写法，也是错的 —— 到期自动恢复后，只要还有任何一处漏改，闹钟就会**静默地不再被排**（比误响更危险：用户以为有提醒，实际没有）。因此 A2 要同时把 `isPaused` 从字段降级为函数，并补不变量测试覆盖"跨过恢复日"这条边界。

**产品第二承诺的一致性**：「吃过的药永不丢失」意味着暂停**不删除**历史。`paused_until` 到期后，此前生成的 `dose_slots` 仍在，状态仍是 PENDING —— 用户会看到一堆逾期未服用的提醒。因此 A2 还要决定：**恢复瞬间的未来槽位如何处理**。取"只影响未来、不回溯"：恢复日后按当前计划重投影未来窗口，暂停期间**本应产生**的槽位不回填（否则用户回来会看到 30 条补服提醒，那是在制造焦虑而不是帮助）。这条在 A2 的验收里显式断言。

### 2.3 余额：唯一权威定义

> **`balance(medicationId) := COALESCE(SUM(inventory_transactions.change_amount), 0)`**

**`medications` 表不再存储余额。** 这不是"加了个约束"，而是**取消了约束本身** —— 从"两份数据必须保持一致"变成"只有一份数据"。

**收益（按重要性排序）**

1. **P0-3 永久消失。** 守恒不再是需要维护的不变式，而是恒等式。
2. **5/6 的库存写路径退化为单条 INSERT**（见下表），**读-改-写竞态随之消失**。
3. **D-9（允许负库存）变得自然**：负数就是负数，不再需要 `coerceAtLeast(0f)` —— 而那个钳制正是 P0-3 的直接成因。
4. P1-6 的浮点漂移从"两份数互相漂移"降级为"一份数的累加误差"，且可由不变量 #2 持续监控。

**改造后的写路径代价对比**

| 操作 | 现状 | 改造后 | 变化 |
| --- | --- | --- | --- |
| `takeDose` | 读余额 → 钳制 → INSERT 流水 → UPDATE 账面 | **单条 INSERT** | ⬇ 少一次读、一次写、一个钳制 |
| `undoDose` | 读余额 → INSERT 冲正 → UPDATE 账面 | **单条 INSERT** | ⬇ 同上 |
| `logManualDose` | 同 `takeDose` | **单条 INSERT** | ⬇ |
| `refillStock` | 读余额 → INSERT → UPDATE 账面 | **单条 INSERT** | ⬇ |
| `calibrateStock` | 读余额 → 算 delta → INSERT → UPDATE | 读派生余额 → 算 delta → **单条 INSERT** | ➖ 仍需读一次（本质要求） |
| `setStockTracking` | 读 → 比较 → 分支写 | 读派生余额 → 必要时 **单条 INSERT** | ⬇ |

**并发正确性**：现状的"读余额 → 写账面"是典型 read-modify-write，两个并发打卡可能丢失更新。改造后**并发打卡只是并发 append，天然正确**。

**`balance_after` 列保留，但降级为"展示用快照"**

它不是权威值（权威值是 `SUM`），但让流水列表不必做累计和就能显示每行的结余。写入时在同一事务内由"当前派生余额 + delta"算出。

> ⚠️ **注意 `minSdk = 26`（SQLite 3.18）不支持窗口函数**，所以不能用 `SUM(...) OVER (PARTITION BY ...)` 生成累计和。保留快照列是当前 minSdk 下的正确选择。
>
> **配套不变量 #2**：`最后一条流水的 balance_after == SUM(change_amount)`。这条测试会在快照与权威值一旦分叉时立刻失败 —— 即"检查缓存"而不是"信任缓存"。

### 2.4 D-7 整数毫单位的落地

`FINAL-PRODUCT.md:38`（D-7）：「**整数毫单位记账**（如 1.5 片 = 1500），**全程无浮点**；杜绝账本对账漂移；UI 层做单位换算展示」

**换算基准：1 个药品单位 = 1000 毫单位**（即 1.5 片 = 1500）。

| 列 | 现状 | 改造后 |
| --- | --- | --- |
| `medications.default_dose` | `REAL` | `INTEGER`（毫单位） |
| `medications.min_stock_alert` | `REAL` | `INTEGER`（毫单位） |
| `medications.current_stock` | `REAL` | **删除**（§2.3） |
| `inventory_transactions.change_amount` | `REAL` | `INTEGER`（毫单位，有符号） |
| `inventory_transactions.balance_after` | `REAL` | `INTEGER`（毫单位，展示用） |
| `dose_slots.dose_amount` | `REAL` | `INTEGER`（毫单位） |
| `dose_records.dose_taken` | `INTEGER` | `INTEGER`（毫单位） |
| `policy_times.dose_amount` | `REAL` | `INTEGER`（毫单位） |

**关键性质**：毫单位是**每个药品自己的单位**（片 / 粒 / ml / 滴），台账永远只在一个药品内部求和，**不存在跨单位相加的存储问题**。P0-7 的跨单位求和是**纯展示/聚合层**问题，与存储层正交 —— 阶段 A 改存储，P0-7 的 UI 修复另开。

**换算层放在哪**：集中在 `core/domain/` 的一个 `Dose` 值对象（见 §3.3），**不散落在 ViewModel**。`doseAmount = 1500` 这样的裸整数不应出现在 UI 层任何位置。

**顺带修掉**：`DoseTrackingService.kt:255-256` 的 `trimFloat`（`v % 1f == 0f` 判断整数，本身就是浮点陷阱）随整数化一并消失。

### 2.5 枚举调整

| 枚举 | 变更 | 理由 |
| --- | --- | --- |
| `RecordStatus` | **删 `RETROSPECTIVE`** | 补录的"来源"信息已由 `dose_records.is_retrospective` 布尔列承载。`status` 现在同时混着"来源"和"依从分类"两件事，导致 3 个统计查询（`DoseRecordDao.kt:45/62/76`）被迫二选一 —— 这正是 P1-4（补录扣了库存却不进统计）的成因。拆开后 `status='COMPLETED'` 天然包含补录，**3 个查询都不用改** |
| `RecordStatus` | **加 `REVERTED`** | P1-1：`undoDose` 现在物理 `DELETE FROM dose_records`，违反 `FINAL-PRODUCT` 场景 2「事实层追加 REVERT 修正…全程留痕」与产品第二承诺「吃过的药永不丢失」。改为状态流转 + 永不 DELETE 后，承诺成为物理事实 |
| `SlotStatus` | 不变 | — |
| `TransactionType` | 不变 | 4 种已够用 |
| `PolicyType` | **不变**（阶段 A 不动） | 阶段 B 才迁 RRULE；本阶段锁定对外语义契约，见 §6.1 |

**`dose_records` 与 `inventory_transactions` 的"永不 DELETE/UPDATE"应在 DAO 层物理保证**：`InventoryTransactionDao` 已经做到了（只有 `@Insert` + 聚合查询，**接口层面就不存在 UPDATE/DELETE**），`DoseRecordDao` 需照做 —— 删掉 `@Delete delete(record)` 与 `deleteBySlotId`，新增 `@Query("UPDATE dose_records SET status='REVERTED' WHERE slot_id=?")`。

---

## 三、写命令契约

### 3.1 六条命令（每条对应一个明确的 UI 所有权）

| # | 命令 | 调用方 | 自有列 | 禁止触碰 |
| ---: | --- | --- | --- | --- |
| C1 | `saveProfileInfo` | 药品信息页 | 档案 12 列 | `reminder_settings.*`、`minStockAlert`、`isStockTracked`、`isArchived`、`createdAt` |
| C2 | `updateExpiryDate` | 库存页 | `expiryDate` | 其余全部 |
| C3 | `updateReminderBehavior` | 提醒设置页 | `reminder_settings.isCriticalReminder` / `snoozeMinutes` / `advanceMinutes` | 档案全部、`isPaused` |
| C4 | `updatePause` | 详情页暂停开关 | `reminder_settings.isPaused` | 其余全部 |
| C5 | `updateArchive` | 详情页归档 | `isArchived` | 其余全部 |
| C6 | `setStockTracking` / `updateMinStockAlert` | 库存页 | 各自 1 列 | 其余全部 |

**C3 与 C4 拆开是必须的**：现状 `updateReminderBehavior` 顺手写了 `is_paused`（`MedicationDao.kt:98`），与独立的 `updatePauseStatus` 撞列。拆开后 `is_paused` 只有一条写路径。

### 3.2 为什么用"一屏一命令"而不是"DTO + 通用 patch"

考虑过的替代方案是"一个 `MedicationPatch` DTO，字段可空表示不修改"：

```kotlin
data class MedicationPatch(val name: String? = null, val alias: String? = null, ...)
```

**不采用**，三个理由：

1. **可空性不是所有权。** `alias: String?` 无法区分"这个字段不属于本次命令"和"本次要把别名清空"。`MedicationAdminService.kt:86-87` 已经在为这个歧义打补丁（`draft.alias?.trim()?.ifBlank { "" } ?: existingAlias` —— 空串=清空、null=不修改），补丁本身就是歧义的症状。
2. **每加一个字段要改三处**（DTO + DAO 参数 + 构造点），漏一处编译器不报错。
3. **无法自动生成"非自有列保全测试"**（§5.1），因为"自有列"没有被显式声明。

**"一屏一命令"让"自有列"成为 SQL 的 SET 列表本身**，可被直接解析、可被穷举测试自动覆盖。

### 3.3 值对象：让裸整数不出领域层

```kotlin
// core/domain/model/Dose.kt（新增）
@JvmInline
value class Dose(val milli: Int) {
    val asFloat: Float get() = milli / 1000f
    operator fun plus(o: Dose) = Dose(milli + o.milli)
    operator fun minus(o: Dose) = Dose(milli - o.milli)
    companion object { val ZERO = Dose(0) }
}
```

Entity 层用 `Int` 存（Room 不需要自定义 TypeConverter），**领域层与 UI 层用 `Dose`**，转换只在两个边界发生。这让"忘了除以 1000"变成编译期错误而不是数据错误。

### 3.4 幂等下沉到 SQL（P0-4）

```kotlin
// DoseSlotDao —— 关键改动
@Query("""
    UPDATE dose_slots
    SET status = 'COMPLETED', actual_taken_ts = :actualTs
    WHERE id = :slotId AND status IN ('PENDING', 'SNOOZED')
""")
suspend fun markCompletedIfOpen(slotId: Long, actualTs: Long): Int      // 返回受影响行数
```

```kotlin
// DoseTrackingService.takeDose —— 在 withTransaction 内部
val affected = slotDao.markCompletedIfOpen(slotId, actualTs)
if (affected == 0) return@withTransaction false        // 幂等锚点下沉到原子操作
// ... 后续 INSERT 流水 / INSERT 事实
```

**为什么这比"调用方先查状态"强**

| | 调用方守卫（现状） | 条件 UPDATE（改造后） |
| --- | --- | --- |
| 原子性 | ❌ 读与写是两个事务，有竞态窗口 | ✅ 单条 SQL 原子 |
| 权威性 | ⚠️ 边缘守卫，核心无守卫 | ✅ 守卫就是写操作本身 |
| 可测性 | 需模拟竞态 | 单线程重复调用即可验证 |
| 适用 | 仅 `DoseActionReceiver` 一处 | **所有调用方自动受益** |

同时 `skipDose` 改用 `markSkippedIfOpen`（`WHERE status IN ('PENDING','SNOOZED','EXPIRED')`，允许对已逾期的补记跳过）。

---

## 四、不变量清单（12 条 → 测试的单一来源）

> 这张表是 A5 阶段所有测试的**唯一来源**。测试数量由它决定，不为了好看而增加。
> **门禁：每条测试必须能失败** —— 任何"改了实现却不会变红"的测试视为无效（`AddEditLogicTest` 的 4 项即反例，见 P1-12）。

| # | 不变量 | 检验方式 | 对应发现 |
| ---: | --- | --- | --- |
| I1 | ∀ 药品：`balance ≡ SUM(change_amount)`，且**不存在第二份存储** | 不变量测试 + schema 断言（`PRAGMA table_info` 无 `current_stock`） | P0-3 |
| I2 | 每药品最后一条流水的 `balance_after == SUM(change_amount)` | 不变量测试 | §2.3 快照漂移 |
| I3 | 同一 slot 重复 `takeDose` → 仅 1 条 `dose_records`、1 条扣减流水、余额只扣 1 次，且剂量取**首次**的值 | **穷举**重复次数 0..6，每次换一个剂量（A5 已改，见 A5 偏差 1） | **P0-4** |
| I4 | `takeDose` 后 `undoDose` → `SUM` 精确回到原值；事实变为 `REVERTED` 而非消失 | 不变量测试 | P1-1 |
| I5 | ∀ `(medicationId, date, time)` 至多 1 条 `dose_slots` | DB unique 约束 + 属性化测试 | P2-5 |
| I6 | `projectSlots` 幂等：同参数两次调用结果完全相同 | 属性化测试 | P1-5 前提 |
| I7 | `INTERVAL` 策略相邻槽位间隔恒等于 `intervalDays` | 属性化测试（随机 interval 2..30、随机区间 400 天） | **P1-9** |
| I8 | 收窄 `endDate` 只减少槽位，绝不新增 | 属性化测试 | `FINAL-PRODUCT` 场景 1 |
| I9 | **写路径隔离**：命令 X 执行后，`medications` 与 `reminder_settings` 中所有非 X 自有列逐字节不变 | 穷举式字段保全测试 ×8（`FieldPreservationInvariantTest`） | **P0-5 两种症状** |
| I10 | 闹钟身份：任意两组 `(medId, date, time, kind)` 产生互不覆盖的 `PendingIntent` | 纯函数测试（遍历 1..10⁴） | **P0-1** |
| I11 | `inventory_transactions` 运行期**只增**；`dose_records` 行不删、除 `status` 外列不改，`status` 只允许 `COMPLETED → REVERTED` 单向翻转 | 静态半：扫源码，改写型 Query 的方法名集合与白名单**完全相等**；行为半：跑完整套操作后既有行**逐列相等** | P1-1 |
| I12 | 负余额合法：`balance < 0` 时低库存告警仍触发，且 UI 不显示"扣减失败" | 不变量测试 + 领域层单测 | **D-9**（`FINAL-PRODUCT:40`） |

> **I11 的表述在 A5 被修正过。** 原文写「永不 DELETE / UPDATE」，但那样撤销打卡就
> 只能靠"删了重插"实现，主键一变 `dose_slots` ↔ `dose_records` 的关联就断了；
> 而靠物理删除实现撤销又会抹掉用药历史。真相是"行不删 + 只允许翻一个状态列"。
> 详见 A5 §偏差 2。

**夏令时（`SlotProjectionDstPropertyTest` / `DoseSlotDstServiceTest`，A5 新增）**：
这一组不是"某条不变量"，而是**一整类事故的护栏** —— 计划写"每天 08:00"，
换季后闹钟在 07:00 或 09:00 响，而在用药场景里这是**会吃错剂量**的事故。

| # | 性质 | 抓什么缺陷 |
| ---: | :--- | :--- |
| D1 | 槽位瞬时反解回本地时刻恒等于计划时刻（仅 DST 空洞日允许顺延，且只许向后） | 换季后提前/推迟一小时 |
| D2 | 相邻槽位 UTC 间隔偏差 ≤ 该时区实际 DST 偏移跨度 | 间隔算错（Lord Howe 的 30 分钟 DST 自动纳入） |
| D3 | 跨切换日时 UTC 间隔**必不**为 24h | "根本没做时区转换"（D2 抓不到） |
| D4 | 瞬时严格递增 | 时刻倒流 ⇒ 同一天两个闹钟或一个都不响 |
| D5 | 无 DST 时区上间隔精确 24h | 做了多余的偏移换算 |

**I7 的写法举例**（这条能直接锁死 P1-9 那一族 bug）：

```kotlin
@Property
fun `INTERVAL 策略的相邻槽位间隔恒等于 intervalDays`(interval: Int, fromOffset: Int) {
    val n = interval.coerceIn(2, 30)
    val from = LocalDate.of(2026, 1, 1).plusDays(fromOffset.toLong())
    val policy = SchedulePolicyEntity(medicationId = 1, policyType = PolicyType.INTERVAL,
                                      intervalDays = n, startDate = from.toString())
    val slots = SlotProjectionEngine.projectSlots(
        policy, listOf(PolicyTimeEntity(policyId = 1, timeOfDay = "08:00", doseAmount = 1)),
        from, from.plusDays(400))
    slots.zipWithNext { a, b ->
        val gap = ChronoUnit.DAYS.between(
            LocalDate.parse(a.scheduledDate, DATE_FORMATTER),
            LocalDate.parse(b.scheduledDate, DATE_FORMATTER))
        assertThat(gap).isEqualTo(n.toLong())      // 现状下 CabinetViewModel:123 的文案会挂在这条上
    }
}
```

---

## 五、测试策略

### 5.1 穷举式字段保全测试（治 P0-5 复发）

**模式**：把所有非自有列写成**可辨识的哨兵值**，执行命令，逐列断言存活。

```kotlin
@Test fun `C1 saveProfileInfo 不触碰任何非自有列`() = runTest {
    val id = medDao.insert(fullSentinelMedication())   // 21 列全部显式给哨兵值

    adminService.saveProfileInfo(ProfileInfoDraft(id, name = "新药名", unit = "ml"))

    val after = medDao.getMedicationById(id)!!
    assertThat(after.alias).isEqualTo(SENTINEL_ALIAS)
    assertThat(after.precautions).isEqualTo(SENTINEL_PRECAUTIONS)
    assertThat(after.isArchived).isTrue()
    assertThat(after.minStockAlert).isEqualTo(SENTINEL_ALERT)
    // ... 档案非自有列全断言
    // 拆表后还要跨表断言：
    assertThat(db.reminderSettingsDao().get(id)!!.snoozeMinutes).isEqualTo(SENTINEL_SNOOZE)
    assertThat(db.reminderSettingsDao().get(id)!!.isPaused).isTrue()
}
```

**为什么这个模式能防复发**：将来给 `MedicationEntity` 加一列，Kotlin 的**具名参数构造**会强制在 `fullSentinelMedication()` 里给它一个值，于是它**自动进入断言集合**。漏传字段的缺陷从"靠人记得"变成"编译期强制"。

**配套原则**：`fullSentinelMedication()` 必须对**每一列显式赋值**，不得依赖默认值 —— 否则新增列若默认值恰好等于哨兵值就会漏检。建议用可辨识常量（`"⚠SENTINEL-alias"`）而非 `42` 这类平凡值。

### 5.2 属性化测试（jqwik）

覆盖 I3 / I5 / I6 / I7 / I8 五条。**先写属性（I7 那类），再补示例用例**。

### 5.3 现有 71 项测试的处置

| 分类 | 数量 | 处置 |
| --- | ---: | --- |
| `MigrationTest`（2 项） | 2 | **删除** —— `AGENTS.md` 新规不再需要迁移 |
| `AppDatabaseRealTest` 中的 v1→v2 迁移断言 | 3 | **删除**，改为 schema 断言（`PRAGMA table_info`） |
| `AddEditLogicTest` 空测（3、4、5、8 项） | 4 | **删除**（P1-12：测 `Int.coerceIn` 与测试体内复制品） |
| `AddEditLogicTest` 有效项（1、2、6、7） | 4 | 保留，随 `AddEditUiState` 结构调整 |
| `DoseTrackingServiceTest` | 4 | 重写：适配余额派生 + 补 I1/I3/I4/I12 |
| 其余 | 54 | 保留，随类型变更做机械适配 |

**门禁措辞变更**：`AGENTS.md` §3 的「**71 项全绿是提交前的硬门槛**」应改为「**§4 的 12 条不变量全绿是硬门槛**」。数量不再是质量指标 —— 这正是 P1-12 的教训。

### 5.4 测试基建变更（A0）

| 变更 | 说明 |
| --- | --- |
| `tasks.withType<Test> { useJUnitPlatform() }` | 引入 JUnit 5 Platform |
| `testImplementation("org.junit.vintage:junit-vintage-engine")` | **保留现有 71 项 JUnit 4 + Robolectric 测试可继续运行** |
| `testImplementation("net.jqwik:jqwik:1.9.x")` | 属性化测试（**成熟库，不自造随机数生成器**） |

**为什么是"5 + Vintage"而不是"全部迁到 JUnit 5"**：现有 71 项里有 17 项依赖 `@RunWith(AndroidJUnit4::class)`（Robolectric）。Robolectric 的 JUnit 5 支持（`RobolectricExtension`）在能力上不及 JUnit 4 runner，迁移会把一批本来能跑的测试变成不确定。**Vintage Engine 让两套并存是零风险的**，全量迁 JUnit 5 留作独立清理任务。

**属性化库的选型**：`jqwik`（JVM 原生、JUnit 5 生态、久经使用）优于 Kotest property —— 后者要求全量 Kotest runner 接管，与 Vintage 并存时集成面更大。

---

## 六、外部规格与成熟库

> 用户 2026-09-28 明确：「能有成熟的库用就不要自己造轮子」。本节记录选型与理由。

### 6.1 阶段 B 的接口契约（本期只锁定，不实施）

`SlotProjectionEngine` 对外语义**在本期冻结**，阶段 B 按下表迁移到 RFC 5545：

| 现行 `PolicyType` | RRULE 等价式 | 阶段 B 处置 |
| --- | --- | --- |
| `DAILY` | `FREQ=DAILY` | 迁入 RRULE |
| `INTERVAL` | `FREQ=DAILY;INTERVAL=n` | 迁入 RRULE（**语义已等价**，见下） |
| `DAYS_OF_WEEK` | `FREQ=WEEKLY;BYDAY=MO,WE,FR` | 迁入 RRULE |
| `CYCLE` | — | **RRULE 表达不了**（无"周期开关"概念），保留自定义扩展 |
| `PRN` | — | 非 recurrence，模型上就是"无排班"，保留 |

**关于 `INTERVAL` 的澄清（避免误判现状有 bug）**：引擎侧 `daysDiff % intervalDays == 0`（`SlotProjectionEngine.kt:116`）**与 RRULE 语义完全等价**，`intervalDays=2` 就是 `INTERVAL=2`（隔天）。P1-9 的 bug 全在 **UI 文案生成层**（`InventoryViewModel.kt:237` 把 `times.size` 当间隔天数、`CabinetViewModel.kt:123` 把 `intervalDays=1` 说成"隔天"但引擎按每日排班）。**阶段 A 用 I7 锁死引擎侧语义，阶段 B 再换实现。**

**阶段 B 库选型**：`ical4j`（RFC 5545 完整实现，JVM 原生）。**建议只作为 domain 层实现细节，不引入 Android 运行时**（保持 `core/domain` 零 Android 依赖 + 可 JVM 直测）。若体积/依赖树不可接受，替代方案是**只吸收 RRULE 的语义与官方测试向量**，自行实现所需的 3 种频率（仍然远优于现状，但不如用库）。

**RFC 5545 正文含有一批现成示例用例，可直接改写成单测** —— 等于白拿一份权威测试语料。

### 6.2 Android 精确闹钟的行为要点（需以官方文档复核，本文档记录待验证项）

| 待验证 | 现状问题 |
| --- | --- |
| `setAlarmClock` 在 Android 12+ 是否同样需要 `SCHEDULE_EXACT_ALARM` | 若需要，则 `AlarmScheduler.kt:59-68` 的**第二档是死代码**，真实降级是第三档 `setAndAllowWhileIdle`（Doze 下可能延迟 15 分钟+）。`AlarmScheduler.kt:9-16` 的 KDoc 与 `AndroidManifest.xml` 注释、`README.md:100` 的说法均需订正 |
| 撤销 `SCHEDULE_EXACT_ALARM` 时系统清空全部闹钟后的自愈 | P1-19：目前无任何触发点感知此事件 |
| Doze 下每 9 分钟唤醒配额对"提前提醒 + 准点提醒"两次唤醒的影响 | P0-1 修好后每个 slot 最多 2 次唤醒/天，需评估 |

**P0-1 的修法与本设计的关系**（在 §3 之外单列，因为它改的是 alarm 包的**身份**而非数据层）：

```kotlin
// 从 requestCode 算术编码 → 内容寻址
private fun alarmUri(medId: Long, date: String, time: String, kind: String): Uri =
    Uri.Builder().scheme("carromed").authority("alarm")
        .appendPath(medId.toString()).appendPath(date).appendPath(time).appendPath(kind)
        .build()

// kind ∈ { "main", "advance", "snooze" }
Intent(context, AlarmReceiver::class.java)
    .setData(alarmUri(slot.medicationId, slot.scheduledDate, slot.scheduledTime, kind))
    .setAction(ACTION_DOSE_ALARM)
```

**收益**：① 碰撞由定义不可能（`Intent.filterEquals` 参与判重）；② 同一 slot 重注册天然幂等；③ 孤儿闹钟自限（同 URI 的旧闹钟被替换而非堆积）—— 这是 P1-5 泄漏的一半解药。**这正是 `docs/reviews/DESIGN_REVIEW.sbf.md:166` 早已给出但未被完整采纳的建议。**

**建议把 P0-1 的修复放在 A3 一起做**（都改写路径层，合并验证成本低）。`AlarmReceiver` 同时读 `EXTRA_IS_ADVANCE` → 用不同 `kind` 区分文案（P1-20）。

### 6.3 选型汇总

| 用途 | 选型 | 新增依赖 | 阶段 |
| --- | --- | :---: | :--- |
| 属性化测试 | `net.jqwik:jqwik` | ✅ | A0 |
| JUnit 4/5 并存 | `org.junit.vintage:junit-vintage-engine` | ✅ | A0 |
| 备份/恢复格式 | `org.jetbrains.kotlinx:kotlinx-serialization-json` | ✅ | A4 |
| 重复规则（阶段 B） | `org.ical4j:ical4j` | ✅ | B |
| ViewModel 工厂 | `androidx.lifecycle.viewmodel.viewModelFactory` DSL | ❌ **已在依赖内** | A3 |
| Room 读写 | 现有 `room-runtime` / `room-ktx` | ❌ 已有 | — |
| 断言 | 现有 `com.google.truth` | ❌ 已有 | — |
| 精确闹钟 | Android 平台 `AlarmManager` + `Intent.setData` | ❌ 平台原生 | A3 |

### 6.4 关于 `kotlinx.serialization`（A4）的理由

现状 `DataExporter.kt` 用 `org.json`（`JSONObject` / `JSONArray`）手工 `put` / `optString` / `optBoolean`：

- **无 schema**：字段名是散落的字符串字面量，导出与导入两处各写一遍，改名必漏。
- **无类型安全**：`optInt("intervalDays", 1)` 的默认值散落各处 —— 这正是 P2-8（`cycleOnDays=0`）与 P1-9（`intervalDays=1`）的来源。
- **异常信息面向开发者**：`RestoreResult.Failure("恢复失败: ${e.message}")` 会把 `No value for startDate` 直接透给用户。
- **备份格式无版本化类型**：`formatVersion` 是一个裸 `Int` 字段，与具体结构无绑定。

`kotlinx.serialization` 提供 `@Serializable data class` + `@SerialName` + `Json { ignoreUnknownKeys = true }`，让**备份格式成为可类型检查、可往返测试的对象**（`导出 → 导入 → 再导出 → 字节相等` 是一条天然的属性测试）。

**成本**：`DataExporter.kt` 435 行基本重写。`AGENTS.md` 新规下备份格式无兼容负担（旧格式直接作废）。**收益**：备份/恢复这条"不可逆操作链"从"手工 JSON 拼接"升级为"有类型保证的编解码"，直接服务于 P1-14（恢复无二次确认 → 恢复前可先做本地快照）。

### 6.5 明确**不**引入的（避免过度工程）

| 方案 | 不引入的理由 |
| --- | --- |
| **Hilt / Koin** | 单模块、8 个 ViewModel、`AppDatabase.getInstance` 单例已足够。DI 框架解决的是"多模块 / 多实现 / 大团队协作"的问题，本项目一个都没有。引入后每个 ViewModel 多一层注解与生成代码，**收益为负** |
| **Compose 导航类型化路由**（`navigation-compose` 的 type-safe route） | 当前 12 条路由全部手写 `createRoute()`，工作正常。属"成熟库更好"的边界，但收益低于改动风险，留作独立评估 |
| **前台服务保活** | 与"纯本地零网络 + 免登录"定位冲突；且 P0-2 的正确解法是"`AlarmReceiver` 触发后续期"，不需要常驻 |
| **Room 2.7+ / Kotlin 2.1+ / 新 Compose BOM** | 现有 `Room 2.6.1` + `Kotlin 2.0.21` + `BOM 2024.10.01` 均已过时约 2 年，且有 7 处弃用警告。**建议列为独立卫生任务**，与本次数据层重构分开，避免一次改动混入两类风险 |
| **WorkManager** | `FINAL-PRODUCT` D-14 把 WorkManager 写成降级第三档，但 **WorkManager 不适用于精确闹钟**（它是可延迟的周期任务）。D-14 的表述本身有误，应在实施时一并订正 |

---

## 七、实施步骤（A 分 6 步，每步独立可编译 / 可测试 / 可回滚）

> 每步结束后必须满足：`./gradlew testDebugUnitTest` 绿 + `assembleDebug` 绿。
> **不需要** `MigrationTest`（`AGENTS.md` 新规）；需要 schema 变更时直接删库重装。

### A0 — 测试基建（无行为变更）

| 项 | 内容 |
| --- | --- |
| 改 | `app/build.gradle.kts`：`useJUnitPlatform()` + Vintage + jqwik |
| 改 | 迁移测试文件的 JUnit 4 注解（保持不变即可，Vintage 会跑） |
| 删 | `MigrationTest.kt` 全部 2 项 + `AppDatabaseRealTest.v2Columns_haveDefaultsOnLegacyInsert` 1 项 |
| 新增 | `SlotProjectionEngineProperties`（I5/I6/I7/I8 的 jqwik 属性测试，5 个 @Property） |
| 验收 | 71 − 3 + 5 = **73 项全绿**，且每条属性测试须能通过变异测试验证（非空测） |

**回滚**：纯依赖与配置变更，`git checkout` 即可。

### A1 — 余额派生 + 整数毫单位（P0-3 / P1-6 / D-7）

> **实施记录（2026-09-28）**：原计划一步做完，实际拆成 **A1a（余额派生）** 与
> **A1b（整数毫单位）** 两个可独立验证的子步。A1a 先把"余额只有一份权威定义"立起来，
> A1b 再改存储精度 —— 反过来做的话，中间态下"Float 累加漂移"与"账面与流水分叉"两类
> 问题会同时存在，无法定位是哪一类引起的。
>
> 落地结果：**118 项全绿**、release 编译通过、走查 35 步全通过。
> 变异测试：余额权威定义植入 0.001 片偏差 → 27/105 项变红（守恒是真实生效的）。
>
> 实施中发现设计文档未预见的 4 个问题，已一并修掉（详见 `docs/CHANGES-20260928.md`）：
> 1. `LazyColumn` key 跨序列撞号导致 **App 崩溃**（只在滚动时崩，第一屏完全正常）；
> 2. 毫单位 `Int` 与展示值 `Float` 混用 → **所有药品误报低库存**；
> 3. 库存页日均消耗漏换算 → 显示 1000.00、可用天数算成 0；
> 4. 顺带修掉 `mapIndexedNotNull` 索引错位、周期用药消耗折算写死、
>    频次文案拿时点数当间隔天数等一批既有缺陷。
>
> **教训**：第 2 条（量纲混用）编译器抓不到 —— Kotlin 允许 `Float <= Int`。
> 唯一的防线是**类型设计**：`MedicationOverview` 只暴露 Float 展示值，
> 读实体那份就必须显式 `Dose(x).asFloat`。这条已写成 `MedicationOverviewTest`。

| 序 | 内容 |
| ---: | --- |
| 1 | `MedicationEntity`：删 `currentStock`，`defaultDose` / `minStockAlert` → `Int` |
| 2 | `InventoryTransactionEntity` / `DoseSlotEntity` / `DoseRecordEntity` / `PolicyTimeEntity`：金额列 → `Int` |
| 3 | `DoseTrackingService`：5 条写路径退化为单 INSERT；`calibrateStock` / `setStockTracking` 改读派生余额；**删 `coerceAtLeast(0f)`** |
| 4 | `InventoryTransactionDao` 已有 `getSumOfChanges` → 提升为**唯一的余额权威查询** |
| 5 | `MedicationDao`：删 `updateStock`；新增 `MedicationOverview` 投影（含 `LEFT JOIN` 派生余额子查询） |
| 6 | 读路径改名：所有 `med.currentStock` → `med.stock`（约 12 处） |
| 7 | 新增 `core/domain/model/Dose.kt` 值对象 |
| 8 | 格式化层：`fmtQty` 统一走 `Quantity`（顺带修 P2-20 的四套格式化） |
| 9 | **测试**：I1、I2、I12 |

**风险点**：`DoseTrackingService` 改动面最大，但每条路径都独立可测（真内存 SQLite）。**这是全设计里风险最高的一步，建议单独成一个提交。**

> **实施补充（超出原设计）**：
> - `RecordStatus.RETROSPECTIVE` 枚举值删除，改为 `isRetrospective` 布尔列。
>   原因见 `Enums.kt` 的 KDoc：原枚举把"依从分类"与"记录来源"两件正交的事挤在一起，
>   导致所有聚合 SQL 写死 `status='COMPLETED'`，**补录超 2 分钟的服药库存照扣、统计不计**。
>   这是 P1-4 的根因，不修则 A1 的统计口径仍然是错的。
> - `DoseRecordDao` 移除全部按 id / slot 的删除方法，撤销改为标记 `REVERTED`。
> - 打卡 / 跳过 / 撤销的幂等锚点下沉到 SQL（`markCompletedIfOpen` 等返回受影响行数），
>   取代"先查后写"的竞态窗口。这原本排在 A3，但它是 I3 的实现前提，提前到 A1。
> - `AppDatabase` 加 `fallbackToDestructiveMigration()`，让 schema 变更在开发期能落地。
>   **发布前必须删掉这一行**（代码里有 `TODO` 标记）。

### A2 — 拆 `reminder_settings`

| 序 | 内容 |
| ---: | --- |
| 1 | 新增 `ReminderSettingsEntity`（`medication_id` PK + FK CASCADE + 4 列：`is_critical_reminder` / `snooze_minutes` / `advance_minutes` / `paused_until`） |
| 2 | `MedicationEntity` 删 `isPaused` / `isCriticalReminder` / `snoozeMinutes` / `advanceMinutes` |
| 3 | `MedicationDao`：删 `updateReminderBehavior` / `updatePauseStatus`；新增 `ReminderSettingsDao`（3 条细粒度命令） |
| 4 | **`paused_until` 落地**（§2.6）：`isPaused` 从字段降级为**派生函数**，且该函数是"该不该为它排闹钟"的唯一判据 |
| 5 | 读路径：`AlarmReconciler` / `ReminderSettings.resolve` / `MedicationDetailScreen` / `CabinetViewModel` / `AlarmReceiver` 改 JOIN 或加一次查询；`MedicationOverview` 增加暂停派生的代理字段 |
| 6 | `DataExporter` 的 JSON 结构同步调整（与 A4 一起做，避免改两遍） |
| 7 | **测试**：I9 的跨表部分（6 条命令的字段保全测试在此建立）+ **暂停到期恢复**测试 |

**读路径性能说明**：`AlarmReceiver` 现状是 2 次查询（`getSlotById` + `getMedicationById`），拆表后可用 1 个 JOIN 完成 → **不劣化，略优**。`observeActiveMedications()` 驱动的药箱列表同样用 1 个 JOIN。

**`paused_until` 的验收要点（易错，单独列出）**：
1. `paused_until = '2026-10-15'` 时，**10-15 当天仍不排闹钟**，10-16 起恢复（"含当天"）。
2. 跨过恢复日之后，**到期自动恢复**，不依赖用户回来开 App —— 由 A6 的周期对账兜底。
3. 恢复后**不回填**暂停期间本应产生的槽位（否则用户回来看到几十条补服提醒）。
4. **不能**把"是否暂停"实现成 `paused_until != null` —— 那是到期后闹钟静默消失的最短路径，比误响更危险。必须有测试专门覆盖"跨过恢复日"。

### A3 — 细粒度写命令 + 幂等下沉 + 闹钟内容寻址

> **状态：已完成（2026-09-28 13:08 +08:00）**。下表保留原计划，末尾"实际落地"记录与计划的偏差。

| 序 | 内容 | 状态 |
| ---: | --- | :--- |
| 1 | 6 条命令落地（§3.1）；`ProfileDraft` 拆为 `ProfileInfoDraft`（仅 C1 自有列） | ✅ 大部分在 A2 已完成（A2 一步就落地了 `saveReminderBehavior` / `setPausedUntil` / `resume`） |
| 2 | `DoseSlotDao`：`markCompletedIfOpen` / `markSkippedIfOpen` 条件更新（§3.4） | ✅ 已在 A1 完成 |
| 3 | `DoseTrackingService.takeDose` / `skipDose` / `undoDose` 改用条件更新 | ✅ 已在 A1 完成 |
| 4 | `AppNavigation` 6 处匿名 `ViewModelProvider.Factory` → `viewModelFactory { initializer { } }` | ✅ 零新依赖，实际净减 **22** 行（不是原估的 54） |
| 5 | `AlarmScheduler` / `Notifications` 改 `setData(Uri)` 内容寻址（§6.2）；`AlarmReceiver` 读 `kind` 区分文案（P1-20） | ✅ **P0-1** |
| 6 | `reconcileSchedule` 改幂等 diff：只 INSERT 新增、只 DELETE 真正消失的（P1-5） | ✅ **并额外发现投机区泄漏**，见下 |
| 7 | `AlarmReceiver` 触发后续期（投影 `[today, today+14]` 并重排）+ 窗口 7→14 天 | ✅ **P0-2** |
| 8 | **测试**：I3、I5、I6、I10 | ✅ 新增 23 项；I3 已由 A1 覆盖 |

#### 实际落地中偏离计划的一处：`reconcileSchedule` 的三区时间轴

原计划只说"只 INSERT 新增、只 DELETE 真正消失的"。**照字面实现会漏一个泄漏**：

纯窗口内 diff ⇒ 窗口**之外**那些"上一次顺手多排的猜测"永远删不掉；
而 `AlarmReconciler` 会给**所有**开放槽位排闹钟 ⇒ 改完计划后用户会收到一个
**已不存在的服药时间**的提醒，且该闹钟再也取消不掉。

所以最终的时间轴规则是**三区**（`DoseTrackingService.reconcileSchedule` 的 KDoc 已写全）：

```
  < fromDate            [ fromDate .. toDate ]              > toDate
  ─────────────────────┼───────────────────────────────┼──────────────
   过去：绝不触碰        权威窗口：与投影逐条 diff           投机区：整段丢弃
   （已服/已跳/已逾期     删：不再被命中的 PENDING/SNOOZED    只删 PENDING / SNOOZED，
     是既成事实）        留：仍被命中的（**保留原 id**）      已完成的既成事实仍保留
                        插：新增的（DB 唯一约束兜底）
```

新增 `DoseSlotDao.deleteSpeculativeFutureSlots`，同时删掉已无调用点的
`deleteFuturePendingSlots`（旧实现的遗留物）。**这个泄漏是测试先变红才发现的**，
不是设计阶段想到的 —— 记在这里是因为它说明"按字面实现计划"仍然不够。

#### 闹钟身份：`Notifications` 里的算术编码**不能一起改**

| 位置 | 是否安全 | 原因 |
| --- | :---: | --- |
| `AlarmScheduler` main / advance | ❌ **P0-1** | 两个分支的 component 与 action **完全相同**，只能靠 `requestCode` 区分，而 `10N+1` 与 `M` 必然相交 |
| `Notifications` 通知栏三个 Action | ✅ 安全 | 三个 Action 的 **action 字符串互不相同**，`filterEquals` 能区分；不同槽位再靠 `slotId*10+{0,1,2}` 区分 |

代码里已加注释说明这个差别，防止后人"顺手统一"改坏。

#### A3 **没有**修、但本轮顺带确认仍然存在的问题

| 编号 | 问题 | 状态 |
| --- | --- | --- |
| **P1-2** | `SNOOZED` 槽位永不结算为 `EXPIRED`：推迟后关机超过 2 小时，该槽位闹钟被撤、状态永不推进，依从率被系统性高估 | ❌ **A3 未修，也不在 A3 范围**。本轮逐行比对了 `HEAD` 与新版 `AlarmReconciler` 的取消分支，行为**完全一致**（旧版同样走 `triggerAt <= now → cancel`），**A3 没有让它变好也没有变坏**。修法是给 `getStalePendingSlots` 加一个 `SNOOZED` 分支并按 `snooze_until_ts` 判定过期 |
| **P1-19** | 系统撤销精确闹钟权限后会清空全部闹钟，App 无任何感知与自愈 | ❌ 未修。需要接 `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`，与 A6 的周期对账是同一类兜底 |
| P1-20 | 「提前提醒」与「准点提醒」文案相同 | ✅ A3-5 已修（`Kind` 驱动标题与正文） |

### A4 — 枚举调整 + `kotlinx.serialization` 备份

> **状态：已完成（2026-09-28 15:23 +08:00）**。1~3 项早已在 A1 落地，本轮做 4~6。

| 序 | 内容 | 状态 |
| ---: | --- | :--- |
| 1 | 删 `RecordStatus.RETROSPECTIVE`；`is_retrospective` 列保留（已是布尔） | ✅ 已在 A1 |
| 2 | 加 `RecordStatus.REVERTED`；`DoseRecordDao` 删 `@Delete` / `deleteBySlotId`，加 `markRevertedBySlot` | ✅ 已在 A1 |
| 3 | `undoDose` 改为状态流转（P1-1） | ✅ 已在 A1 |
| 4 | 备份格式改 `kotlinx.serialization`（§6.4）；`formatVersion` 改为枚举 | ⚠️ **格式码在 wire 上仍是 Int**，类型化放在 `BackupFormatVersion.from()`。改成枚举会写 `"V1"` 字符串，**所有旧备份立刻全部不可读** —— 而旧备份仍可读是净收益，没理由为"类型更漂亮"放弃 |
| 5 | 恢复加**导入前本地快照** + 二次确认（`FINAL-PRODUCT:158` / P1-14） | ✅ 且额外前置了 `validateBackup`：先校验、**再留快照、最后才清库** |
| 6 | **测试**：I4、I11 + 备份往返属性测试 | ✅ 19 项，见下 |
| 7 | （计划外）**「从本机备份恢复」入口** | ✅ 实测发现：备份写在 `Android/data/<pkg>/...`，**SAF 不允许浏览该目录** ⇒ 导出了却选不回来。详见 `DataExporter.listLocalBackups` 的 KDoc |

#### A4 抓到的**真实数据丢失**（不是理论风险）

旧 `appSettings` 导出只写 `key` 与 `value`，`AppSettingEntity.updatedAt` **恢复即永久丢失**。

之所以一直没人发现：手写 `JSONObject` 映射**没有 schema**，导出与导入两个方向要人肉同步；
而且**从来没有一条测试跑过"导出 → 导入 → 再导出"** —— 想测一次往返得先造一个 `Uri`，
于是编解码全埋在 `importBackup` 里，从来没被单独测过。

#### A4 的核心设计：纯函数与 IO 分层

| 层 | 函数 | 依赖 | 谁能测 |
| --- | --- | --- | :---: |
| 纯 | `buildBackup` / `encodeBackup` / `decodeBackup` / `validateBackup` / `restoreBackup` | 只有 `AppDatabase` | ✅ 直接单测 |
| IO | `exportFullBackupJson` / `inspectBackup` / `importBackup` | `Context` / `Uri` | 需 Robolectric |

**这个分层本身就是修复**：正因为纯编解码可单独测，往返链路才第一次有了测试。

#### A4 实际落地的两处判断

1. **`validateBackup` 必须在任何写操作之前**。旧实现先 `deleteAll*()` 再逐段解析，
   解析到一半失败就是"清空了但没恢复成" —— 用户两头都没了。
   它拦的是"解析不报错、但恢复中途会炸在事务里"的一类问题：悬空外键、药品 id 重复、
   **槽位唯一键重复**、不支持的版本。缺 `reminderSettings` 只算警告（旧版本备份，恢复时补默认值）。

2. **槽位重复那条最要紧**：`dose_slots` 在 A3 有了 UNIQUE 索引，而 `insertAll` 是
   `OnConflictStrategy.IGNORE` —— 重复键会被**静默丢弃**，恢复"成功"了但少了若干槽位，
   用户毫无察觉。校验阶段提前报错，而不是事后看行数对不上。

#### A4 的测试策略：为什么"往返相等"还不够

往返相等只能发现**单向**遗漏 —— 导出漏了字段 A、同时也漏了导入字段 A，往返仍然相等。

所以 `BackupFieldPreservationTest` 用**手写期望的 `BackupFile`** 逐字段比对，
能同时抓住漏字段、张冠李戴、类型不匹配三种错误。
且种子数据**每一列都取非默认值** —— 用默认值的话"这列没被导出"与"这列恰好等于默认值"
在断言里长得一样，测试就成了恒真断言（本项目已因此吃过两次亏）。

#### A4 顺带修掉的一个**测试质量**问题：断言随时钟翻转

`AlarmReconcilerIdempotencyTest` 的 4 条断言在 15:0x 全绿、15:5x 全红，
而那段时间**一行相关代码都没改**。

真因是 fixture 时点 `13:30` 跨过了「计划时间 + 2 小时」的逾期线：
`rescheduleAll` 把当天那条结算成 `EXPIRED`，而 `reconcileSchedule` **只删
PENDING / SNOOZED**（EXPIRED 是既成事实）⇒「一条都不剩」多出 1 条。

**第一反应（把时点换成 `23:50`）是错的** —— 那只是把窗口从「每天下午」挪到
「每天午夜前」，并没有解决问题。真正的修法是断言不变量本身：
只断言**开放槽位**（PENDING/SNOOZED）、按**日期集合**而非条数，
并新增一条把「暂停不删除既成事实」钉成显式不变量。已记入 `AGENTS.md` §3。

### A5 — 测试补全与门禁措辞

> **状态：已完成（2026-09-28 20:54 +08:00）**。下表为原计划，"落地"列记录偏差。

| 序 | 内容 | 落地 |
| ---: | :--- | :--- |
| 1 | 6 条穷举式字段保全测试（I9） | `FieldPreservationInvariantTest`，**8** 条（写命令从 6 条扩到 8 条） |
| 2 | jqwik 属性化测试：I3、I5、I6、I7、I8 | I5–I8 原已存在；**I3 改为穷举**（0..6 次），见下方偏差说明 |
| 3 | **DST 属性测试** | `SlotProjectionDstPropertyTest`（D1–D5）+ `DoseSlotDstServiceTest`（落库版 5 条） |
| 4 | `DoseActionBottomSheet.kt` 删除 | 已删，231 行，**零引用** |
| 5 | 修正 `AGENTS.md` §2 关于"必须加 `MIGRATION_*`"的红线 | 已改 |
| 6 | 全文删除 `MigrationTest` 引用 | （A0 已完成） |

**额外做了两件计划外的事**：

1. **I11（事实表只增不改）此前无测试**，一并补上 `AppendOnlyFactTableTest`（9 条），
   并**修正了本文件 §4 里 I11 的错误表述**（见下）。
2. 除删除死代码外，**没动任何生产代码** —— A5 是纯测试步。

#### 偏差 1：I3 用穷举而非 jqwik

原计划写"属性化测试（随机重复次数 0..5）"。实际改成**穷举 0..6**：

- 定义域只有 7 个元素，穷举严格强于随机采样；
- `markCompletedIfOpen` 的幂等锚点必须跑**真 SQLite**，
  而 jqwik 走 JUnit Platform、Robolectric 走 JUnit 4 Runner，两套不能混用
  （见 `AGENTS.md` §3）—— 强行搭桥的收益不抵风险。
- 在 6 元素的定义域上追求"随机"，只是把确定性换成了不确定性。

穷举时**每次重复都换一个剂量**（第 i 次传 `1f + i`），
这样"后一次把前一次的剂量改掉"这类缺陷在旧测试里看不出来、在新测试里立刻现形。

#### 偏差 2：I11 的表述原本是错的，已修正

§4 原写「`dose_records` / `inventory_transactions` **永不 DELETE / UPDATE**」。
实际代码不是这样，而且**不该**是这样：

| 表 | 真实情况 |
| :--- | :--- |
| `inventory_transactions` | 运行期**绝对只增**。唯一的 `DELETE` 是 `deleteAllTransactions()`，仅供恢复备份前清库 |
| `dose_records` | 行永不删、**除 `status` 外**的列永不改。`status` 允许 `COMPLETED → REVERTED` 单向翻转（撤销打卡） |

撤销若靠物理删除实现，"我误点了打卡"就会把历史抹掉 ——
而剂量台账是**用药安全**的唯一凭据。反过来，写成"永不 UPDATE"则撤销只能"删了重插"，
主键一变 `dose_slots` ↔ `dose_records` 的关联就断了。两种都不行。

测试分成**互补的两半**，缺一不可：

- **静态半**：扫源码（**不是反射** —— Room 的 `@Query` 是 `@Retention(BINARY)`，
  运行期反射看不见，用反射写出来的断言恒真），
  要求改写型 Query 的方法名集合与显式白名单**完全相等**。
- **行为半**：跑一整套日常操作后，既有行**逐列相等**、id 集合只增不减。

变异验证证明了互补性：给 `markRevertedBySlot` 加上 `note = NULL, dose_taken = 0`
（形状不变、语义变坏）时，**静态半保持绿**，只有行为半变红。

#### 偏差 3：DST 测试的写法比计划里要求的更严

计划只要求"跨 DST 切换日，本地时刻恒定而 UTC 间隔为 23h/25h"。实际加了 5 条：

| 编号 | 性质 | 抓什么缺陷 |
| :--- | :--- | :--- |
| D1 | 槽位瞬时反解回本地时刻恒等于计划时刻 | 换季后闹钟提前/推迟一小时 |
| D2 | 相邻槽位 UTC 间隔偏差不超过该时区实际 DST 偏移 | 间隔算错（Lord Howe 的 30 分钟 DST 自动纳入） |
| D3 | 跨切换日时 UTC 间隔**必不**为 24h | "根本没做时区转换"（D2 抓不到，见下） |
| D4 | 瞬时严格递增 | 时刻倒流 ⇒ 同一天两个闹钟或一个都不响 |
| D5 | 无 DST 时区上间隔精确 24h | 做了多余的偏移换算 |

D3 是 D2 的**反例兜底**：若实现退化成 `ts + 86_400_000`，D2 仍会绿
（24h 恰在容差内），所以必须有一条测试**要求**偏移真实存在。

写这批测试时踩到三个坑，都记在测试文件的 KDoc 里：

1. `Pacific/Apia` 早已**不适用**—— Samoa 2021 年立法废除夏令时，
   2026 年全年只有 `+13:00`（2020 年还是 `[+14:00, +13:00]`）。
   把它放进"一定有 DST"的清单会得到一条**恒假**性质。
   **教训：硬编码的外部事实（时区规则、节假日表）会腐烂。**
2. DST **空洞**（春季前跳那天凌晨 2 点整段不存在）下 `atZone` 会顺延到 03:30，
   这是正确行为。D1 必须显式承认这个豁免，且**只许向后不许提前** ——
   "计划 08:00 却 07:30 响"比晚响危险得多。
3. **均匀采样求极值在短窗口上必然漏**：容差原按 7 天步长采样偏移，
   2 天窗口只采到 1 个点 ⇒ 容差算成 0，而窗口恰好跨切换点（间隔 23h）⇒ 误报。
   改走 `ZoneRules.nextTransition`。

### A6 — 周期对账兜底（兑现 D-14，新增步骤）

> **状态：已完成（2026-09-28 13:45 +08:00）**。下表为原计划，末尾"实际落地"记录偏差。

> **为什么单独成为一步而不是塞进 A3**：它是**产品承诺的缺口**，不是重构的一部分。混进 A3 会让"重构顺手加了个 Worker"与"补上了一个一直缺的能力"混在一起，回滚时无法分离。

| 序 | 内容 |
| ---: | --- |
| 1 | 引入 `androidx.work:work-runtime`（**唯一新增运行时依赖**） |
| 2 | `ReconcileWorker`：无网络、无输入，`doWork()` 只调一次 `AlarmReconciler.rescheduleAll` |
| 3 | `PeriodicWorkRequest` 15 分钟一轮（WorkManager 下限），`ExistingPeriodicWorkPolicy.KEEP` 防重入；`setBackoffCriteria` 指数退避 |
| 4 | 在 `BootReceiver` / `App.onCreate` 统一 enqueue（**保证唯一触发点**） |
| 5 | 逾期槽位补判 + 未来窗口补投影 + 全量重排（复用 `rescheduleAll`，零重复逻辑） |

**这解决的是 P0-2 的结构性盲区，不只是"加个兜底"**：

`FINAL-PRODUCT:159` 的验收标准是「真机矩阵连续 **7 天**零漏提醒」，而 P0-2 的机制边界恰好是**闹钟视野 7 天**（`AlarmReconciler.kt:47` 的 `plusDays(7)`）。**验收时长等于机制边界 ⇒ 第 8 天的问题在测试期内结构上不可能被发现。** 上一轮审查没能抓到它，不是运气差，是这个巧合。

周期对账把"重新排闹钟"从"必须靠某次用户行为触发"变成"最多 15 分钟后自愈"，两个后果：
1. 视野窗口从 7 天扩到 7 天 + 15 分钟容错，**验收标准改 21 天后机制才真正跟得上**；
2. 即便视野设置仍被改错，漏掉的闹钟也会被补排，**故障从"静默漏提醒"降级为"延迟 15 分钟"**。

**必须一起做的**（否则 A6 只是装饰）：
- `AlarmReconciler.rescheduleAll` 需幂等 —— A3-6 的 diff 式重排是它的前提；
- 恢复点必须在 `paused_until` 到期时生效（A2-4 的派生函数）—— 否则暂停到期要等用户开 App 才恢复；
- `WorkManager` 自带进程唤醒，**不需要也不应该有**前台常驻服务（§6.5 明确不引入）。

**已知取舍**：`PeriodicWorkRequest` 的实际执行间隔由系统按电量/厂商策略决定，**15 分钟是名义下限不是保证**。因此 A6 是**第二层兜底**，第一层仍是 A3-7 的 `AlarmReceiver` 触发后续期（那个是即时的）。两者缺一不可。

#### A6 实际落地（2026-09-28）

| 序 | 计划 | 实际 |
| ---: | --- | --- |
| 1 | 引入 `work-runtime` | `work-runtime-ktx:2.9.1`（运行时唯一新增依赖）+ `work-testing:2.9.1`（测试） |
| 2 | `ReconcileWorker` 只调 `rescheduleAll` | ✅ 额外把 `catch` 的返回值定为 `retry()` 而非 `failure()`：后者等于永久放弃这一轮 |
| 3 | 15 分钟 + `KEEP` + 指数退避 | ✅ 退避起点设 10 分钟（不设的话翻几轮就涨到几十分钟，对 15 分钟尺度的事太慢） |
| 4 | `BootReceiver` / `App.onCreate` 统一 enqueue | ✅ 新建 `CarroMedApp : Application`。**理由**：`MainActivity` 与 `BootReceiver` 各有盲区（前者覆盖不了"用户不开 App"，后者覆盖不了"正常冷启动"），而 `Application.onCreate` 覆盖**任何**组件拉起进程的场景 —— 这是"周期对账是否已排上"的唯一真相来源 |
| 5 | 复用 `rescheduleAll`，零重复逻辑 | ✅ |

**触发点为什么需要冗余**：`WorkManager` 的周期任务**不会**随 `BOOT_COMPLETED` 自动恢复，
也**不会**随换包恢复（系统升级 App 或用户清数据后既有任务被丢弃且不重建，不报错）。
所以 `CarroMedApp.onCreate` 用 `REPLACE` 幂等重排（覆盖"进程被拉起"），
`BootReceiver` 里再 `REPLACE` 一次（覆盖"开机后进程压根没被拉起"）。两者都在才不留缺口。

**`Application.onCreate` 里刻意不做的事**：不跑 `AlarmReconciler.rescheduleAll`。
那会拖慢每次冷启动，且 `MainActivity` 已经会跑一次。Worker 是**兜底**不是首发路径。

#### A6 期间改掉的实现问题与发现的产品问题

| 项 | 性质 | 处理 |
| :--- | :--- | :--- |
| `reconcileSchedule` 在无有效计划时**提前返回**，窗口内的 PENDING 槽位永远删不掉 | **实现 bug**（既有） | ✅ 已修：无计划时把投影当空集，删除逻辑照常执行 |
| 暂停中的药品，今日清单仍列出待服项但**永远不会有闹钟** | **产品决策**，见 §9 #5 | ⏸ 登记待拍板，**未擅自决定** |

#### A6 期间踩到的测试基建坑

`doWork()` 走 `AppDatabase.getInstance()`，而那是 `companion object` 的静态单例。
Robolectric 每个测试方法重建 `Application` 与沙箱文件系统，**单例不重置**，
于是后一个测试拿到失效句柄而抛异常，被 `doWork` 的 `catch (Throwable) → Result.retry()` 吞成"退避重试"。

**失败信息（Retry）与真实原因完全无关，比不测更糟。**
所以 `ReconcileWorkerTest` 不断言 `doWork`；`rescheduleAll` 的幂等性由
`AlarmReconcilerIdempotencyTest` 用**显式传入的内存库**来守 —— 那本来就是 A6 真正依赖的不变量。

> 附带一条给未来的人的提醒：work-runtime **2.9.1** 的 `WorkSpec.intervalDuration` /
> `flexDuration` / `backoffDelayDuration` 是**毫秒 `long`**；
> `WorkRequest.intervalDuration: Duration` 那个扩展要到 **2.10** 才有。别照新版文档写。

---

## 八、风险与回滚

| 风险 | 等级 | 缓解 |
| --- | :---: | --- |
| A1 改动 `DoseTrackingService` 全部库存路径 | **高** | 单独提交；真内存 SQLite 逐路径测；I1/I2 覆盖 |
| `Dose` 值对象导致 UI 层类型摩擦 | 中 | 只在领域层用；UI 边界一次性转换；`fmtQty` 统一收口 |
| 拆表后 JOIN 遗漏导致读路径 N+1 | 中 | A2 完成后用 `adb shell` 或 DAO 测试统计查询次数；`AppDatabaseRealTest` 加查询计数断言 |
| JUnit 5 Platform 切换导致 Robolectric 测试不跑 | 中 | Vintage Engine；A0 单独一步，**验收标准就是"66 项仍全绿"**，不通过则本步回滚 |
| jqwik 在 Robolectric 类路径下行为异常 | 低 | 属性化测试**只覆盖无 Android 依赖的纯逻辑**（`SlotProjectionEngine` / `StatsEngine` / 库存台账逻辑），不与 Robolectric 混用 |
| `kotlinx.serialization` 插件与 KSP/Room 插件共存 | 中 | A4 单独一步；Room 的 KSP 与 serialization 的 compiler plugin 是两套，不冲突但需验证 |

**逐步回滚**：每步一个独立提交，`git revert` 即可。因无迁移负担，回滚不需要数据修复。

---

## 九、需要拍板的决策点

| # | 决策点 | 选项 | 我的建议 |
| ---: | --- | --- | --- |
| 1 | **「准时率」指标**（`FINAL-PRODUCT` D-11 列为 v1-P0，`README.md:320` 降为 P2） | (a) 实现；(b) 改文档统一为 P2；(c) 保持"完成率"但把文案从「按时」改为「已服」 | 🟡 **部分拍板（2026-09-28）** —— 用户选择 (c)。**但随后又说「依从率可以不用去掉，可以弱化颜色之类的，甚至可以完全不动」**，并给出原则「数据是回顾性的总结，不是考卷和评分」。故 (c) 的"改文案"部分**暂缓**，UI 全部保持原样，等真实使用反馈再定。理由：现在改只是猜测用户会介意什么，��没有证据 |
| 2 | **`balance_after` 快照列保留 or 删除** | (a) 保留 + I2 持续校验；(b) 删除，流水列表改显示"变动量"不显示结余；(c) 删除，ViewModel 里算前缀和 | 🟡 **待定，因范围收窄暂缓**。原建议 (a)，但复核后**原理由不成立**（曾写"`minSdk 26` 无窗口函数，删了只能显示变动量"——其实前缀和可在应用层算，(c) 同时做到"不存冗余"和"不丢 UX"，且 `InventoryScreen` 已在全量取流水，改动很小）。本轮未实施 |
| 3 | **阶段 B 的 `ical4j` 是否真引入** | (a) 引入库；(b) 只吸收 RRULE 语义 + 官方测试向量，自实现 3 种频率 | ✅ **拍板 (b)** —— 用户 2026-09-28：「先做 b 看看再说」。阶段 B 开工时先按 RRULE 语义 + RFC 5545 官方示例实现；若 (b) 的自实现顶不住，再评估 (a) 的依赖树与体积（`core/domain` 保持零 Android 依赖是硬约束） |
| 4 | **暂停是否需要结束日** | (a) `paused_until: String?`（`null`=未暂停 / `''`=无限期 / 日期=暂停至该日含）；(b) 保持纯布尔 | ✅ **已拍板 (a)** —— 用户 2026-09-28 决定。`FINAL-PRODUCT` M-02 明确要求「暂停至某日」，纯布尔无法表达。并入 A2 一次建对，详见 §2.6 |
| 5 | **暂停中的药品，今日清单怎么表现**（A6 期间新发现） | (a) 清理暂停药的未来 PENDING 槽位，清单变诚实但"暂停中"不可见；(b) 保留槽位但标注「已暂停」，需要新增 UI 状态 | ✅ **已拍板并实现 (a)** —— 用户 2026-09-28：「暂停的不需要在今日显示，某天显示的是当日有提醒的 item，没有提醒存在为啥要显示」。实现上**没有加显示过滤**，而是让暂停参与**投影**（`SlotProjectionEngine.projectSlots` 收 `pausedUntil`），于是今日清单、统计、闹钟三处读同一个"不存在"，不必各自再写一遍判断 |
| 6 | **`SNOOZED` 槽位永不结算为 `EXPIRED`**（P1-2） | (a) `getStale...` 加 `SNOOZED` 分支并按 `snooze_until_ts` 判定过期；(b) 推迟时另建一条 EXPIRED 定时任务 | ✅ **已拍板并实现 (a)** —— 用户 2026-09-28 提问「应该是 b 更清晰？」，确认 (b) 需要第三种闹钟身份（触发时刻同时依赖 `snoozeMinutes` 与逾期窗口两个参数，判重变复杂）且多一条会静默失效的依赖链路，而 P0-2 的教训正是"提醒依赖单一链路，断了就静默漏提醒"；逾期窗口长达 2 小时，15 分钟的对账粒度对它毫无影响。实现细节见 `docs/CHANGES-20260928.md`。**遗留**：其兜底依赖 `ReconcileWorker` 能在用户整天不开 App 时执行，**尚未实测** |
| 7 | **撤销精确闹钟权限无感知**（P1-19） | (a) 设置页加一栏「系统权限」+ 跳转 `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`，并在对账失败时提示；(b) 静默降级，只在通知里说明"可能延迟" | ✅ **已拍板 (b) 静默降级** —— 用户 2026-09-28：「我记得国内手机都没有这个权限」。**实测佐证**：模拟器 API 35 上 `canScheduleExactAlarms()` 为 false，且 `setAlarmClock` 在 API 35 上**确实**要求同一权限（SecurityException 明写），所以每个闹钟都走第三档 inexact。降级方向是安全的：晚 15 分钟响 ≠ 漏服。`USE_EXACT_ALARM`（Android 13+ 对闹钟类自动授权）**仅作记录**，发布前再评估是否声明 |
| 8 | **出差跨时区后闹钟是否跟随本地时区**（A5 新发现） | (a) 跟随：对账时按当前时区重算 `scheduled_ts`；(b) 不跟随；(c) 跟随但要用户确认 | ✅ **已拍板 (b) 不跟随** —— 用户 2026-09-28。反对 (a) 的技术理由仍然成立且已被记录：`reconcileSchedule` 的 diff 键是日历字符串，槽位**从不更新**，改成重算会让 `AlarmReconciler` 把"时间戳变了"当成需重排闹钟的信号，与 I6 幂等性直接冲突。**需要在发布前把这一行为写进用户可见的说明**（当前行为：计划时刻锚定在**首次排班时**的时区） |

> **#8 的技术事实**（A5 实测确认）：`reconcileSchedule` 的删除/插入判定用的键是
> `(scheduledDate, scheduledTime)` —— 纯**日历**字符串，**刻意**不涉及时区。
> 已被命中的槽位**永不更新** `scheduled_ts`，所以"在东京设的 08:00 闹钟，
> 人飞到伦敦后仍按东京的瞬时响"。已用 `DoseSlotDstServiceTest` 里一条
> **明确标注为「当前行为、非正确性断言」**的测试把这个行为钉住；
> 若将来决定处理，该测试会红，届时应**改名**成断言新行为，而不是把断言反过来。

> **#5 的技术事实**（A6 实测确认，随后已修）：`AlarmReconciler` 原先把暂停的药从 `activeMeds` 剔掉，
> 所以它的 `reconcileSchedule` 根本不会被调用，旧槽位原样留在库里；
> 而 `TodayViewModel` 的 `medMap` 来自 `observeActiveOverviews()`，**只过滤 `is_archived`，不过滤暂停**。
> 两者叠加的结果是：清单上写着"该吃药"，但闹钟已被撤掉，**永远不会响**。
> 这是 A2 加 `paused_until` 时就存在的既有问题，不是 A3/A6 引入的。
> 修法是让暂停参与**投影**（而不是加一层显示过滤），见 `SlotProjectionEngine` 的 KDoc。

**另需确认（非阻塞）**：`D-6`（全部文案走 strings.xml）与 `FINAL-PRODUCT` §七（v1 不做多语言）自相矛盾，建议在文档层面择一，不在本期动代码。

> 早期版本此处曾记「D-14 的 WorkManager 表述有误」。**该判断已撤回**，理由见 §附录 A.2：WorkManager 确实不适用于*精确闹钟本身*，但 D-14 说的是**兜底对账**（周期性地重跑一次 `AlarmReconciler`，把错过的槽位补上并重排闹钟），这正是 WorkManager 该做的事，属于 D-14 的正确表达。

---

## 十、验收标准

**每步结束时**：
```
./gradlew clean assembleDebug testDebugUnitTest     # 全绿
./gradlew compileReleaseKotlin                        # 通过
```

**A 阶段全部完成时**，除上述外必须通过：

| 验收项 | 方法 |
| --- | --- |
| I1~I12 全部有对应测试且能失败 | 逐条人为破坏实现后确认变红 |
| 库存守恒真机验证 | 建 3 个药（含 ml 单位）→ 打卡/补药/盘点/撤销各若干次 → `dbdump` 断言 `SUM(change_amount)` 恒等于 UI 显示余额 |
| 负库存真机验证 | 把某药库存改到 0 后打卡 → UI 显示负数 + 告警 + 建议盘点（I12 / D-9） |
| 编辑隔离真机验证 | 药品信息页改药名 → 回详情页确认 `reminder_settings` 四项与 `minStockAlert` 均未变 |
| 库存页隔离真机验证 | 库存页改有效期 → 确认药品名/别名/注意事项/提醒行为全部未变 |
| 闹钟可靠性 | 真机 **21 天**零漏提醒，覆盖 Doze / 杀后台 / 重启 / 改时 / 改时区 / 权限被拒 / 权限被撤销（**21 天而非 7 天的理由见 `CODE-REVIEW-20260927-sbf.md` P0-2 末段：验收时长不得等于机制边界**） |
| 备份往返 | 导出 → 清库 → 导入 → 再导出，两次 JSON 字节相等（属性测试 I-A4） |
| UI 走查 | `python tools\app_screenshots.py --clear --seed` 跑通，`manifest.md` 无新增失败项，**并逐张看图** |
| 暂停到期 | 暂停某药至「明天」→ 不排闹钟 → 改系统日期到后天 → 周期对账后**自动恢复排闹钟**，且**不回填**暂停期间的槽位 |

---

## 附录 A — 外部方案对照（2026-09-28）

> 来源：`temp/docs/` 下 9 份文档 —— Sonnet 5、GPT、Grok、K3 四家各 2 份（总体方案 + 工程化设计），外加一份四家对比总结。本附录记录**逐条回代码核实后**的判断，而不是转述那份对比文档的结论。

### A.1 四家独立收敛到的共识（本设计已在主干上命中）

1. 内核纯逻辑、零 Android 依赖 —— 我们的 `SlotProjectionEngine` 已是纯 `object` + `java.time`，无 `android.*` import
2. 计划与实例分离 —— 我们是 `schedule_policies` / `dose_slots` / `dose_records` 三层
3. 改计划不冲历史 —— 我们是 `reconcileSchedule` 只删未来 PENDING
4. 统一对账入口 —— 我们是 `AlarmReconciler.rescheduleAll`
5. DB 是唯一事实源，AlarmManager 是可重建的派生状态 —— 我们的写路径全部先提交事务再排闹钟
6. 不用 `setRepeating` —— 我们只 `setExact` / `setAlarmClock` 单次
7. 权限是平台运行时能力，不是业务状态 —— 我们有 `permission_check` 页
8. 不用 `Date.now() + interval` 做时间加法 —— 我们的引擎是**按日步进 + 命中判定**，与 Sonnet 的 `OccurrenceGenerator` 同一策略

**方向无需怀疑，不重写。**

### A.2 对比文档高估的两项（不采纳）

| 项 | 对比文档结论 | 核实结论 |
| --- | --- | --- |
| **月度规则 MONTHLY** | "P0 真实缺口，0.5–1 人日" | ❌ **对我们不成立**。`FINAL-PRODUCT` M-02 枚举的频次只有「每日 / 隔 N 天 / 每周特定天 / 按需(PRN)」。四家都有 MONTHLY 是因为在做通用日历提醒；"每月 31 号"在吃药场景的重要性远低于"漏提醒"。**不排期** |
| **确定性实例 ID** | "P1 缺口，1–2 人日" | ❌ **与 A3-5 是二选一，不是补充**。它的目标是"重投影后闹钟/通知身份不变"。A3-5 的内容寻址用另一种方式达成同一目标，且不需改主键：同一 `Uri` 重注册天然**替换**旧 `PendingIntent` 而非堆积，孤儿闹钟问题消失。两样都做是冗余。**维持内容寻址** |

### A.3 对比文档漏掉的一项（必须补，已排进 A5-3）

**DST 属性测试**。K3 的 P5 表述很到位：对 DST 切换日断言「UTC 间隔恰好 23h/25h，而本地时刻恒为 8:00」。我们的引擎用 `java.time` + `ZoneId.systemDefault()`，用户语义存本地时间、调度存绝对时刻，这条性质**目前没有任何测试守着** —— 引擎里任何一个"顺手改成 UTC 日"的改动都会静默破坏换时区后的提醒，而这类 bug 只在用户真的跨时区时才显形。

### A.4 对比文档漏掉的一项产品能力缺口（已并入 A2）

**「暂停至某日」**（`FINAL-PRODUCT` M-02）。对比文档的 4 项缺口清单里没有它。现状 `is_paused` 是纯布尔，无法表达结束日，用户只能"暂停到某天"然后靠记忆回来手动恢复。详见 §2.6，用户已拍板采纳。

### A.5 对比文档的一处事实错误（记录以免再被采信）

它写「`requestCode = dose_slots.id`（自增主键，**零碰撞**）」—— 这是**直接采信了 `AlarmScheduler.kt:15` 的注释**，没有核 `PendingIntent` 的判重语义。

实际（**P0-1，至今未修**）：`PendingIntent` 相等 = `requestCode` + `Intent.filterEquals`（**extras 不参与**）。`AlarmScheduler.pendingIntent` 两个分支的 component（`AlarmReceiver`）与 action（`DOSE_ALARM`）完全相同，于是 `advance(slotId=1)` 的 `1*10+1 = 11` 与 `main(slotId=11)` 的 `11` 是**同一个 PendingIntent**。后果是提前提醒被吞，且 `cancel(槽位1)` 会连带杀掉槽位 11 的主闹钟。代码注释「两个号段天然不相交」在数学上错误。

> 教训记在这里，因为它正是本轮反复出现的失效模式：**注释和文档里的断言，和实现一起被采信，没人验证。** 本项目的门禁（编译 → 测试 → 模拟器 → 看图）之所以要求"每条测试必须能失败"，原因就在这里。

### A.6 关于"借机制不搬仪式"

对比文档最终建议的采纳清单（按其自评）：

| 优先级 | 项 | 我们的处理 |
| --- | --- | --- |
| P0 | 月度规则 | ❌ 不采纳（A.2） |
| P0 | WorkManager 周期对账 | ✅ **新增 A6**（且升级为"D-14 承诺缺口 + P0-2 结构性盲区"） |
| P1 | 属性测试 + 朴素对拍 | ✅ A0 已做属性测试；朴素参照对拍见 A5 |
| P1 | 确定性槽位 ID | ❌ 不采纳（A.2） |
| P2 | 变更干跑预览 | ⏸ 不排期（体验项，优先级低于"不漏提醒"） |
| P2 | 锚点配对（同日提前服用后改计划） | ⏸ 不排期，但**记录**：当前 `dose_slots` 的 `(medication_id, scheduled_date, scheduled_time)` UNIQUE 约束（A2-4）已经把"同日同时点"去重兜住了，跨时点配对是更细的语义 |
| P3 | 抽离纯 JVM `:core` Gradle 模块 | ⏸ 不排期。`SlotProjectionEngine` 已是纯函数，**编译期物理隔离的边际收益主要是防"偷 import"**。可在下次大重构时顺带做 |
| — | 单一全局闹钟 / 全量事件溯源 / PIT+JMH 全套 | ❌ **不建议**。单机个人规模下，现有"状态位 + 逐槽闹钟 + 库存流水"更务实 |

**我们领先、不要为套方案而推倒的部分**：库存流水台账（四家都把库存当可选外挂或干脆不做）、PRN 按需（Sonnet 推到 v1.1）、CYCLE 吃 N 停 M（只有 Sonnet 有）、事实可撤销 + 库存可冲正（比 K3 的"MISSED 终态不可撤销"更符合"误触可改正"）、物理断网（`AndroidManifest` 不申请 `INTERNET`）。
