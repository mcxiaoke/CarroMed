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
    COMPLETED,      // 正常按时或推迟后完成
    SKIPPED,        // 记录跳过事实
    RETROSPECTIVE   // 事后补录
}

/**
 * 不可变库存台账流水类型 (InventoryTransaction)
 */
enum class TransactionType {
    TAKEN_DEDUCT,       // 打卡服药自动扣减 (负数)
    REFILL,             // 购药补给入库 (正数)
    REVERT_ROLLBACK,    // 误触打卡撤销退回 (正数冲正)
    CALIBRATION_ADJUST  // 盘点人工校准 (正数或负数)
}
