package com.mcxiaoke.carromed.core.data.model

/**
 * 提醒计划的频次类型
 */
enum class PolicyType {
    DAILY,          // 每天固定 N 次
    INTERVAL,       // 每隔 N 天 (如隔日: interval=2, 每隔2天: interval=3)
    DAYS_OF_WEEK,   // 每周特定几天 (如周一/周三/周五)
    PRN,            // 按需服用 (不设定时闹钟，仅在今日清单或手动补录打卡)
    CYCLE           // 周期轮换 (如吃 21 天停 7 天)
}

/**
 * 服药排班槽位状态 (DoseSlot)
 */
enum class SlotStatus {
    PENDING,        // 待服药
    COMPLETED,      // 已确认服用
    SKIPPED,        // 已主动跳过
    EXPIRED,        // 逾期未确认 (到了下一槽位仍未操作，防冤枉漏服)
    SNOOZED         // 推迟提醒中
}

/**
 * 服药事实状态 (DoseRecord)
 */
enum class RecordStatus {
    COMPLETED,      // 已服药（含按时打卡、推迟后打卡、事后补录）
    SKIPPED,        // 记录跳过事实
    REVERTED        // 已被用户撤销（事实保留，不物理删除）
}

/**
 * 服用事实状态 (DoseRecord) ——
 *
 * ## 为什么"事后补录"不再是 RecordStatus 的一个取值
 *
 * 此前存在 `RETROSPECTIVE` 枚举值，用于标记"这条是补录的"。这造成了一个长期 bug：
 * 所有消耗聚合查询都写死 `WHERE status = 'COMPLETED'`（`DoseRecordDao` 的三个查询 +
 * `StatsEngine.sumDoseByDate`），于是**凡���补录时间距今超过 2 分钟的服药，
 * 库存照扣、统计不计** —— 账面与消耗排行永远对不上。
 *
 * 根因是 `status` 同时承担了两件事：
 * 1. **依从分类**（吃了 / 跳过 / 被撤销）
 * 2. **记录来源**（实时打卡 / 事后补录）
 *
 * 两者正交，不该塞进一个枚举。现在：
 * - `status` 只管依从分类；
 * - 来源由 `DoseRecordEntity.isRetrospective`（布尔列）承载。
 *
 * 于是 `status = 'COMPLETED'` 天然包含补录，统计口径无需任何特判。
 * 见 `docs/REMINDER-DOMAIN-REDESIGN.md` §2.5 与 P1-4。
 */

/**
 * 不可变库存台账流水类型 (InventoryTransaction)
 */
enum class TransactionType {
    TAKEN_DEDUCT,       // 打卡服药自动扣减 (负数)
    REFILL,             // 购药补给入库 (正数)
    REVERT_ROLLBACK,    // 误触打卡撤销退回 (正数冲正)
    CALIBRATION_ADJUST, // 盘点人工校准 (正数或负数)

    /**
     * 修改服药剂量产生的差额调整（正数或负数，取决于新剂量比原剂量大还是小）。
     *
     * ⚠️ **绝不允许 UPDATE 原来的流水行**。台账只增不改是硬规矩（I1/I2），
     * 所以改剂量必须补一条差额流水，而不是"把 -1 改成 -2"。
     * 理由与撤销冲正是同一条：改过的账要能看出改了多少、什么时候改的。
     */
    DOSE_EDIT_ADJUST
}
