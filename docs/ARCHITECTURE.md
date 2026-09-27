# CarroMed - 系统技术架构设计文档 (ARCHITECTURE)

> ⚠️ **本文档已于 2026-09-27 废弃（SUPERSEDED）**
> 本文的数据模型（缺槽位表、actual_time 统计过滤错误、RequestCode 碰撞、事实原地修改等问题）已在最终设计中修正。
> 当前唯一权威源：
> - 系统架构与数据模型：`docs/FINAL-ARCHITECTURE-20260927-qwa.md`
> - 产品功能与范围：`docs/FINAL-PRODUCT-20260927-qwa.md`
> - 界面与交互：`docs/FINAL-UI-20260927-qwa.md`
> 废弃原因详见 `docs/PLAN-REVIEW-20260927-qwa.md`、`docs/DESIGN_REVIEW_dsf.md`、`docs/DESIGN_REVIEW.sbf.md`。本文仅作历史存档保留。

> **版本**：v2.0.0 (企业级防推倒与边缘场景加固版)  
> **文档位置**：`docs/ARCHITECTURE.md`  
> **定位**：纯本地离线、零广告、免登录、注重隐私、毫秒级启动的 Android 专属服药/待办管理工具。  
> **设计原则**：结构简单、逻辑清晰、提醒可靠、时序解耦、事实不可变、防推倒重构。

---

## 目录
1. [主流同类 App 架构调研与核心设计思想](#一主流同类-app-架构调研与核心设计思想)
2. [三层时序解耦模型 (The 3-Tier Temporal Decoupling)](#二三层时序解耦模型-the-3-tier-temporal-decoupling)
3. [核心领域模型与 SQLite 数据库设计](#三核心领域模型与-sqlite-数据库设计)
4. [核心边缘场景应对与状态机转移](#四核心边缘场景应对与状态机转移)
   - [场景 1：吃过一次后，当天中途改计划（改时间、改频次、增减药）](#场景-1吃过一次后当天中途改计划改时间改频次增减药)
   - [场景 2：手滑点错打卡（极速撤销 Undo 与库存回滚）](#场景-2手滑点错打卡极速撤销-undo-与库存回滚)
   - [场景 3：历史记录的修改与补录（补服、改剂量、改实际时间）](#场景-3历史记录的修改与补录补服改剂量改实际时间)
   - [场景 4：停药、换药与归档（绝对保留历史数据）](#场景-4停药换药与归档绝对保留历史数据)
   - [场景 5：临时加服与按需服药 (PRN / Ad-hoc)](#场景-5临时加服与按需服药-prn--ad-hoc)
   - [场景 6：多次推迟与重叠闹钟 (Snooze Chains)](#场景-6多次推迟与重叠闹钟-snooze-chains)
5. [定时提醒与 Android 系统保活链路](#五定时提醒与-android-系统保活链路)
6. [事件溯源级库存账本机制 (Event-Sourced Ledger)](#六事件溯源级库存账本机制-event-sourced-ledger)
7. [长周期统计与报表聚合算法](#七长周期统计与报表聚合算法)
8. [数据导入导出规范 (CSV / JSON)](#八数据导入导出规范-csv--json)

---

## 一、主流同类 App 架构调研与核心设计思想

在医疗健康与待办类领域（如 **Medisafe、Apple Health Meds、MyTherapy、Google Calendar、Todoist、TickTick**），最容易导致“架构推倒重来”的致命缺陷是：**把“计划规则”与“历史执行记录”强绑定在了一起**。

例如：很多初级应用只设计了一张“药品表”和一张“提醒时间表”，打卡记录直接外键引用当下的提醒时间 ID。一旦用户中途修改了服药时间（如把 09:00 改为 08:00），就会发生严重灾难：
1. 过去的打卡记录时间全部被错误串改或显示异常；
2. 当天已经吃完了一次药，改了规则后，当天已打卡的状态被冲掉，或者系统又重复给用户发已经吃过的药的闹钟；
3. 用户删除了一个不再吃的药，过去一年的所有历史统计全被级联删除抹除。

### CarroMed 确立的 4 大黄金架构法则：
1. **法则一：履约事实不可变 (Facts are Immutable)**：
   每一次服药打卡记录（`DoseRecord`），记录的是**真实物理世界中已经发生的客观事实**。它包含了打卡当时的药品名称快照、剂量快照、时间戳。**未来的任何计划变更，严禁破坏已发生的历史记录**。
2. **法则二：三层时序解耦 (3-Tier Separation)**：
   严格分离 **【规则定义层】（Rule/Policy）**、**【当日计划槽位层】（Daily Slot/Projection）** 与 **【打卡事实层】（Dose Record）**。
3. **法则三：双向会计式库存流水 (Compensating Ledger)**：
   库存严禁简单进行 `stock = stock - 1` 的无痕覆盖；必须像银行账本一样，每一次扣减、补货、撤销、校准都有独立的交易凭证。
4. **法则四：软删除与版本化 (Soft Archive & Versioning)**：
   药品和规则只归档、只做生命周期截断（`valid_until`），坚决不对有历史数据的实体做硬物理删除。

---

## 二、三层时序解耦模型 (The 3-Tier Temporal Decoupling)

```
┌─────────────────────────────────────────────────────────────┐
│ 1. 规则定义层 (Schedule Policy)                              │
│    • 定义服药频次: 每天多次、隔日、每隔N天、按需             │
│    • 具备生命周期: effective_from (生效日) ~ effective_until │
│    • 用户改计划 = 截断当前规则版本，生成新规则版本          │
└──────────────────────────────┬──────────────────────────────┘
                               │ (动态投影映射)
┌──────────────────────────────▼──────────────────────────────┐
│ 2. 单日任务槽位层 (Daily Dose Slots - 投影展示)              │
│    • 每天零点或进入“今日”时，根据当天有效的规则生成任务槽位 │
│    • 状态机: PENDING(待服) | SNOOZED(推迟中) | TAKEN(已服)  │
│              SKIPPED(跳过) | CANCELLED_TODAY(今日临时取消)  │
│    • 当天改计划时: 只操作 PENDING 槽位，绝不影响已 TAKEN 槽位│
└──────────────────────────────┬──────────────────────────────┘
                               │ (打卡落盘)
┌──────────────────────────────▼──────────────────────────────┐
│ 3. 履约事实层 (Immutable Dose Records - 历史不可变流水)     │
│    • 记录真实物理事实: 谁在何时真正吃了多少药               │
│    • 支持撤销 (REVERTED) 与修正，自动触发库存对冲凭证        │
│    • 长周期(1个月/1年)统计报表的唯一真理数据来源            │
└─────────────────────────────────────────────────────────────┘
```

---

## 三、核心领域模型与 SQLite 数据库设计

```mermaid
erDiagram
    MEDICATIONS ||--o{ SCHEDULE_POLICIES : "1对多: 版本化排程规则"
    MEDICATIONS ||--o{ DOSE_RECORDS : "1对多: 真实打卡流水"
    MEDICATIONS ||--o{ INVENTORY_TRANSACTIONS : "1对多: 库存记账流水"
    SCHEDULE_POLICIES ||--o{ DOSE_RECORDS : "关联生成(可为空)"

    MEDICATIONS {
        INTEGER id PK "主键自增"
        TEXT name "药品名称 (如 环抱素)"
        TEXT category "类别/种类 (如 处方药/慢病/常备)"
        TEXT description "描述 (用法、注意事项、禁忌摘要)"
        TEXT color "标识色彩 Hex (#8B5CF6)"
        TEXT dosage_unit "规格单位 (片/粒/包/支/ml)"
        REAL current_stock "当前实时结余缓存"
        REAL min_stock_alert "低库存警戒阈值"
        INTEGER is_stock_alert_enabled "是否开启低库存提示 (0/1)"
        INTEGER is_paused "是否全局暂停提醒 (0正常 / 1暂停)"
        INTEGER is_archived "是否已停用归档 (0正常 / 1已归档)"
        INTEGER created_at "创建时间戳"
        INTEGER updated_at "更新时间戳"
    }

    SCHEDULE_POLICIES {
        INTEGER id PK "主键自增"
        INTEGER medication_id FK "关联药品ID"
        INTEGER version "规则版本号 (1, 2, 3...)"
        TEXT frequency_type "DAILY(每日) / INTERVAL(隔N天) / WEEKDAYS(每周特定天) / AS_NEEDED(按需)"
        INTEGER interval_days "间隔天数 (如 1表示隔日，2表示隔2天)"
        TEXT anchor_date "间隔基准日期 (YYYY-MM-DD)"
        TEXT weekdays "有效星期 (如 1,3,5)"
        TEXT time_doses_json "计划时间与剂量配置列表: [{\"time\":\"08:00\",\"dose\":1.0}]"
        INTEGER effective_from "本规则生效起始时间戳"
        INTEGER effective_until "本规则失效截止时间戳 (永久有效为 NULL)"
        INTEGER is_active "是否当前有效 (0/1)"
    }

    DOSE_RECORDS {
        INTEGER id PK "主键自增"
        INTEGER medication_id FK "关联药品ID"
        INTEGER policy_id FK "关联规则ID (临时加服/按需服药可为NULL)"
        INTEGER policy_version "打卡时的规则版本快照"
        TEXT med_name_snapshot "药品名快照 (防止更名后历史变形)"
        INTEGER scheduled_time "计划应服时间戳 (精确到秒)"
        INTEGER actual_time "实际服药打卡时间戳"
        REAL scheduled_dose "计划剂量"
        REAL dose_taken "实际服药剂量"
        TEXT status "状态: TAKEN(已吃) / REVERTED(已撤销) / SKIPPED(跳过) / MISSED(漏服) / SNOOZED(推迟中)"
        TEXT note "备注说明 (如 饭后微恶心)"
        INTEGER is_manual "是否为手动补录/临时加服 (0否 / 1是)"
        INTEGER created_at "记录创建时间戳"
        INTEGER updated_at "最后修改时间戳"
    }

    INVENTORY_TRANSACTIONS {
        INTEGER id PK "主键自增"
        INTEGER medication_id FK "关联药品ID"
        INTEGER dose_record_id FK "关联服药记录 (若是服药扣减或撤销回滚)"
        REAL change_amount "变动量 (-1.0 扣减, +30.0 补药, +1.0 撤销回滚)"
        REAL balance_after "变动后即时库存"
        TEXT type "AUTO_DEDUCT(服药自动扣除) / REVERT_ROLLBACK(撤销打卡回滚) / REPLENISH(采购入库) / INVENTORY_CHECK(盘点修正)"
        TEXT remark "记账备注"
        INTEGER created_at "流水生成时间戳"
    }
```

---

## 四、核心边缘场景应对与状态机转移

### 场景 1：吃过一次后，当天中途改计划（改时间、改频次、增减药）
* **用户行为**：
  早上 08:00 吃了 1 片环抱素（状态已为 `TAKEN`）。上午 11:00 医生通知：今天起改成每天 3 次（08:00, 14:00, 20:00），或者今天不需要再吃了。
* **架构解法与保护机制**：
  1. **历史保护原则**：早上 08:00 的 `DoseRecord` 状态为 `TAKEN`，属于不可篡改的已发生事实，**绝对不删、不改、不覆盖**。
  2. **规则版本平滑接替**：
     - 将旧规则的 `effective_until` 设为当前时间戳；
     - 新规则的 `effective_from` 设为当前时间戳并存入新版本号。
  3. **当日任务槽位差分对齐 (Today Slot Reconciliation)**：
     - 查询今天**当前时间之后 (after now)** 的任务：
     - 若旧计划下半天有未服用的待办（`status = PENDING`），将其标记为 `CANCELLED_BY_RULE_CHANGE` 并取消 `AlarmManager` 闹钟；
     - 依据新规则，生成今天剩余时间点（如 14:00、20:00）的新任务槽位，并注册精准闹钟。
  4. **用户交互指引**：
     - 修改计划时，系统弹出友好提示：*“检测到今日 08:00 已服用 1 次，新计划将从今日下午（下一次提醒）开始生效，历史打卡已为您完整保留。”*

---

### 场景 2：手滑点错打卡（极速撤销 Undo 与库存回滚）
* **用户行为**：
  在通知栏或今日页面不小心碰到了“确认已吃”，但实际并没有吃药。
* **架构解法与状态机流转**：
  1. **撤销打卡 (Revert Action)**：
     - 允许在今日页面已完成栏、或历史记录中点击 **「撤销」**；
     - 打卡记录状态从 `TAKEN` 转为 `REVERTED`（或重新回到 `PENDING`）。
  2. **原子性库存回滚凭证 (Atomic Ledger Rollback)**：
     - 触发一条对冲流水：`change_amount = +dose_taken`，`type = REVERT_ROLLBACK`；
     - `medications.current_stock` 同步恢复；
  3. **重置提醒闹钟**：
     - 若该任务计划时间仍处于未来或刚刚过去不久，系统提示用户是否重新激活此闹钟。

---

### 场景 3：历史记录的修改与补录（补服、改剂量、改实际时间）
* **用户行为**：
  - 昨天晚上忘了打开手机打卡，今天想**补录昨天 20:00 的服药**；
  - 或者上午实际吃了 2 片，系统当时默认按 1 片打卡了，需要**把剂量改成 2 片**。
* **架构解法**：
  1. **手动补录**：
     - 插入一条 `DoseRecord`，`is_manual = 1`，`scheduled_time = 昨天20:00`，`actual_time = 昨天20:00`，`status = TAKEN`；
     - 记账扣除库存，记录流水 `MANUAL_INTAKE`。
  2. **剂量修改与库存差额清算**：
     - 原剂量 $D_{old} = 1.0$，新剂量 $D_{new} = 2.0$，差额 $\Delta = D_{old} - D_{new} = -1.0$；
     - 记录一条审计流水：`change_amount = -1.0`，`type = DOSE_CORRECTION`；
     - 更新打卡记录中的 `dose_taken = 2.0`。数据始终保持平衡。

---

### 场景 4：停药、换药与归档（绝对保留历史数据）
* **用户行为**：
  病好了，或者换了新药，用户在药箱中点击“删除此药”。
* **架构解法**：
  1. **禁止物理级联硬删除**：
     - 系统检测该药品是否有历史 `DoseRecord`。如果有，**系统强制采用「归档 (Archive)」机制**；
     - 只有在从未被打卡过（纯新建误操作）的药品，才允许物理删除。
  2. **归档处理流水线**：
     - 设置 `is_archived = 1`，`is_paused = 1`；
     - 立即注销该药品的所有系统挂起闹钟；
     - 在「今日」页面与「药箱」主列表中隐藏该药品（移入“已归档”筛选区）；
     - **在「统计」报表与「历史日历」中，该药品过去 1 个月、1 年的所有吃药数据、消耗总量、打卡率完整保留**。

---

### 场景 5：临时加服与按需服药 (PRN / Ad-hoc)
* **用户行为**：
  头痛发作临时吃了一片布洛芬，或者突发情况医生嘱咐中午临时加服一片药。
* **架构解法**：
  1. 不需要专门为此去创建一条“周期性用药规则”；
  2. 直接调用 `RecordAdhocDose(med_id, dose, time, note)`；
  3. 生成 `policy_id = NULL, is_manual = 1` 的独立打卡记录与扣库存流水；
  4. 完美计入过去 1 个月/ 1 年的该药总消耗量统计。

---

### 场景 6：多次推迟与重叠闹钟 (Snooze Chains)
* **用户行为**：
  08:00 该吃药，手头忙点击“推迟 30 分钟”；08:30 闹钟又响了，开会继续点击“推迟 1 小时”至 09:30。如果中午 12:00 还有一次该药的提醒，两者如何协调？
* **架构解法**：
  1. 每一个具体的服药槽位（Slot）持有独立的生命周期状态：
     - 初始：`status = PENDING, target_alarm = 08:00`；
     - 第一次推迟：`status = SNOOZED, snooze_target = 08:30, snooze_count = 1`；
     - 第二次推迟：`status = SNOOZED, snooze_target = 09:30, snooze_count = 2`；
  2. **闹钟防重叠隔离机制**：
     - 每一个任务槽位注册到 Android `AlarmManager` 时，使用确定性算法生成唯一的 RequestCode：
       $$\text{RequestCode} = (\text{med\_id} \times 10000) + (\text{scheduled\_hour} \times 100) + \text{scheduled\_minute}$$
     - 推迟闹钟使用 `RequestCode + 500000` 专用推迟段，无论推迟多少次，绝对不会覆盖或干扰 12:00 的正常固定闹钟。

---

## 五、定时提醒与 Android 系统保活链路

```
[ 开机自启 BOOT_COMPLETED ] 或 [ 应用前后台切换 ] 或 [ 规则变更 ]
                       │
                       ▼
         [ 调度对齐引擎 (Scheduler Reconciler) ]
         • 扫描当前有效且未暂停的规则 (is_paused=0, is_archived=0)
         • 计算未来 48 小时内的下一次待触发时序点
                       │
                       ▼
       [ AlarmManager.setExactAndAllowWhileIdle ]
         • 使用精准时钟，绕过 Android Doze 低功耗休眠
                       │ (准时触发)
                       ▼
            [ Android 系统广播 BroadcastReceiver ]
                       │
         ┌─────────────┴─────────────┐
         ▼                           ▼
[ 发送 Heads-up 顶部横幅通知 ]     [ 唤醒快捷弹窗 (Quick Action Activity) ]
  • 响铃 + 强震动                   • 单手大热区界面
  • 4个快捷按钮:                     • 确认吃药 (原子扣库存)
    [已吃] [推迟30m] [推迟1h] [跳过]  • 推迟 / 跳过 / 改剂量
```

---

## 六、事件溯源级库存账本机制 (Event-Sourced Ledger)

为了杜绝任何库存漂移或算错：
* 药品的 `current_stock` 字段仅作为**快速查询的物化缓存 (Materialized Cache)**；
* **真正权威的数据是 `INVENTORY_TRANSACTIONS` 流水表**：
  $$\text{真实库存} = \sum_{\text{all tx}} \text{change\_amount}$$
* 系统在每日启动或数据导出时执行一次校验，若缓存与流水求和不一致，以流水为准自动平账。

---

## 七、长周期统计与报表聚合算法

完全满足“统计过去 1 个月、过去 1 年吃了多少药”的核心查询架构：

```sql
-- 过去 1 个月 / 1 年用药总量与依从性权威查询
SELECT 
    m.id AS med_id,
    m.name AS med_name,
    m.category,
    m.dosage_unit,
    m.color,
    -- 1. 真实吃药总片数 (包含周期服用、按需服用与手动补录)
    COALESCE(SUM(CASE WHEN d.status = 'TAKEN' THEN d.dose_taken ELSE 0 END), 0.0) AS total_pills_consumed,
    -- 2. 打卡次数统计
    COUNT(CASE WHEN d.status = 'TAKEN' THEN 1 END) AS taken_count,
    COUNT(CASE WHEN d.status = 'MISSED' THEN 1 END) AS missed_count,
    COUNT(CASE WHEN d.status = 'SKIPPED' THEN 1 END) AS skipped_count,
    -- 3. 按时依从率
    ROUND(
        COUNT(CASE WHEN d.status = 'TAKEN' THEN 1 END) * 100.0 / 
        NULLIF(COUNT(CASE WHEN d.status IN ('TAKEN', 'MISSED', 'SKIPPED') THEN 1 END), 0), 1
    ) AS adherence_rate
FROM medications m
JOIN dose_records d ON m.id = d.medication_id
WHERE d.actual_time BETWEEN :rangeStartTimestamp AND :rangeEndTimestamp
GROUP BY m.id
ORDER BY total_pills_consumed DESC;
```

---

## 八、数据导入导出规范 (CSV / JSON)

### 1. CSV 历史与报表导出
* `CarroMed_服药流水明细_YYYYMMDD.csv`：记录每一笔服药的精确时间、计划时间、实际剂量、打卡状态、是否手动补录、备注。
* `CarroMed_年度药品消耗报表_YYYYMMDD.csv`：输出过去一年各药品总消耗片数、服药次数、按时率。

### 2. JSON 全量镜像与换机迁移
* 备份文件采用版本化 JSON：包含 `schema_version`, `medications`, `schedule_policies`, `dose_records`, `inventory_transactions`。
* 换新 Android 手机后，本地一键还原，所有历史与配置 100% 完美复原。
