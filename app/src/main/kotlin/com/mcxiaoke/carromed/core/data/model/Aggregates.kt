package com.mcxiaoke.carromed.core.data.model

import androidx.room.ColumnInfo

/**
 * 聚合查询投影 (Room 只读 POJO)
 *
 * 全部为数据库 GROUP BY 聚合的直接结果载体，不落表、不参与写路径。
 * 目的是让"统计报表 / 打卡矩阵"在数据量增长后仍能走索引聚合，
 * 而不是把几千条 dose_slots 全量读进内存再在 Kotlin 里数。
 */

/** 按 [药品 + 计划日期 + 状态] 分组的槽位计数 */
data class SlotStatusCountRow(
    @ColumnInfo(name = "medId") val medicationId: Long,
    @ColumnInfo(name = "date") val scheduledDate: String,
    @ColumnInfo(name = "status") val status: SlotStatus,
    @ColumnInfo(name = "cnt") val count: Int
)

/**
 * 按药品汇总的区间实际消耗剂量 (仅 COMPLETED 事实)
 *
 * `totalDose` 是**整数毫单位**（1 片 = 1000），与 `dose_records.dose_taken` 同口径。
 * 聚合层若用 Float 而实体用 Int，会在 DAO 边界上产生一次静默的量级错误 ——
 * 所以这里保持与实体一致的整数口径，由 UI 层负责换算（D-7）。
 */
data class MedDoseSumRow(
    @ColumnInfo(name = "medId") val medicationId: Long,
    @ColumnInfo(name = "total") val totalDose: Int
)
