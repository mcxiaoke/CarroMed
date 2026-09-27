package com.mcxiaoke.carromed.core.domain.engine

import com.mcxiaoke.carromed.core.data.entity.DoseRecordEntity
import com.mcxiaoke.carromed.core.data.entity.DoseSlotEntity
import com.mcxiaoke.carromed.core.data.model.RecordStatus
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import com.mcxiaoke.carromed.core.data.model.SlotStatusCountRow
import java.time.LocalDate

/**
 * 用药统计与合规率计算引擎 (StatsEngine)
 * 纯函数式设计，计算用药依从率、库存可用剩余天数、日历热力分布等
 */
object StatsEngine {

    /**
     * 依从度统计结果
     */
    data class AdherenceStats(
        val totalSlots: Int,
        val completedCount: Int,
        val skippedCount: Int,
        val expiredCount: Int,
        val pendingCount: Int,
        val adherenceRate: Float // 0.0f .. 1.0f
    )

    /**
     * 针对给定的槽位列表计算依从率
     * 依从率公式 = completed / (completed + skipped + expired)
     * 若分母为 0 且全部为 pending 则返回 1.0f (新排班尚未触发)，若无槽位返回 1.0f
     */
    fun calculateAdherence(slots: List<DoseSlotEntity>): AdherenceStats {
        if (slots.isEmpty()) {
            return AdherenceStats(
                totalSlots = 0,
                completedCount = 0,
                skippedCount = 0,
                expiredCount = 0,
                pendingCount = 0,
                adherenceRate = 1.0f
            )
        }

        var completed = 0
        var skipped = 0
        var expired = 0
        var pending = 0

        for (slot in slots) {
            when (slot.status) {
                SlotStatus.COMPLETED -> completed++
                SlotStatus.SKIPPED -> skipped++
                SlotStatus.EXPIRED -> expired++
                SlotStatus.PENDING, SlotStatus.SNOOZED -> pending++
            }
        }

        val decided = completed + skipped + expired
        val rate = if (decided > 0) {
            (completed.toFloat() / decided.toFloat()).coerceIn(0.0f, 1.0f)
        } else {
            1.0f
        }

        return AdherenceStats(
            totalSlots = slots.size,
            completedCount = completed,
            skippedCount = skipped,
            expiredCount = expired,
            pendingCount = pending,
            adherenceRate = rate
        )
    }

    /**
     * 计算库存可用剩余天数与预警状态
     *
     * @param currentStock 当前库存
     * @param dailyEstimatedConsumption 预估每日总消耗量
     * @param minStockAlert 最低库存预警阈值
     * @return Pair<剩余天数, 是否触发预警>
     */
    fun calculateStockRunway(
        currentStock: Float,
        dailyEstimatedConsumption: Float,
        minStockAlert: Float = 0f
    ): Pair<Int, Boolean> {
        if (dailyEstimatedConsumption <= 0f) {
            val isAlert = currentStock <= minStockAlert && minStockAlert > 0f
            return Pair(Int.MAX_VALUE, isAlert)
        }

        val runwayDays = (currentStock / dailyEstimatedConsumption).toInt().coerceAtLeast(0)
        val isAlert = currentStock <= minStockAlert || runwayDays <= 7 // 7 天以内常规定位预警线
        return Pair(runwayDays, isAlert)
    }

    /**
     * 按日期统计实际总服药剂量
     */
    fun sumDoseByDate(records: List<DoseRecordEntity>): Float {
        return records.filter { it.status == RecordStatus.COMPLETED }
            .map { it.doseTaken }
            .sum()
    }

    // ==================== 真实统计聚合 (P0-1 / P0-2 修复) ====================

    /**
     * 单个药品在一天 (或任意区间) 内的槽位状态分布
     *
     * 口径定义 (全 App 统一，统计报表与打卡矩阵共用)：
     * - **依从率** = 已服 / (已服 + 跳过 + 漏服)，分母只算"已产生结论"的槽位
     * - 待服 (PENDING / SNOOZED) 不进分母，避免"还没到时间"被算成漏服
     * - 漏服 = EXPIRED (计划时间过 2 小时仍未操作，由 AlarmReconciler 结算)
     * - 区间归属以 **计划时间 scheduled_date** 为准，补录不会污染当日依从率
     */
    data class DayStatusBreakdown(
        val completed: Int = 0,
        val skipped: Int = 0,
        val missed: Int = 0,
        val pending: Int = 0
    ) {
        /** 区间内排班总次数 */
        val total: Int get() = completed + skipped + missed + pending

        /** 已产生结论的次数 (依从率分母) */
        val decided: Int get() = completed + skipped + missed

        fun isEmpty(): Boolean = total == 0

        operator fun plus(other: DayStatusBreakdown): DayStatusBreakdown =
            DayStatusBreakdown(
                completed = completed + other.completed,
                skipped = skipped + other.skipped,
                missed = missed + other.missed,
                pending = pending + other.pending
            )
    }

    /** 打卡矩阵 / 每日卡片的单日呈现状态 */
    enum class DayAdherenceState {
        NONE,        // 当日无排班 (该药这天不服)
        UPCOMING,    // 未来日期
        FULLY_TAKEN, // 全部按计划完成
        PARTIAL,     // 部分完成 / 部分待服
        SKIPPED,     // 全部主动跳过
        MISSED       // 存在逾期未确认 (漏服)
    }

    /**
     * 由状态分布解析单日呈现状态。
     *
     * 判定优先级：未排班 → 未来 → 全完成 → 有漏服 → 全跳过 → 其余为部分完成。
     * 这样"跳过"不会遮蔽"漏服"，"漏服"也不会被误显示为"还没到"。
     */
    fun resolveDayState(
        breakdown: DayStatusBreakdown,
        isFutureDay: Boolean
    ): DayAdherenceState = when {
        breakdown.isEmpty() -> DayAdherenceState.NONE
        isFutureDay -> DayAdherenceState.UPCOMING
        breakdown.completed == breakdown.total -> DayAdherenceState.FULLY_TAKEN
        breakdown.missed > 0 -> DayAdherenceState.MISSED
        breakdown.decided == breakdown.total && breakdown.completed == 0 -> DayAdherenceState.SKIPPED
        else -> DayAdherenceState.PARTIAL
    }

    /**
     * 依从率计算 (0f..1f)。分母为 0 (全部待服或无排班) 时返回 1.0f，不冤枉用户。
     */
    fun adherenceOf(completed: Int, skipped: Int, missed: Int): Float {
        val decided = completed + skipped + missed
        return if (decided <= 0) 1.0f
        else (completed.toFloat() / decided.toFloat()).coerceIn(0.0f, 1.0f)
    }

    /** 依从率计算 (重载，直接吃状态分布) */
    fun adherenceOf(breakdown: DayStatusBreakdown): Float =
        adherenceOf(breakdown.completed, breakdown.skipped, breakdown.missed)

    /**
     * 把 DAO 的 [SlotStatusCountRow] 聚合结果折叠为 `药品ID -> 计划日期 -> 状态分布`。
     * 纯函数，可直接单测。
     */
    fun aggregateBreakdowns(
        rows: List<SlotStatusCountRow>
    ): Map<Long, Map<String, DayStatusBreakdown>> {
        val result = LinkedHashMap<Long, MutableMap<String, DayStatusBreakdown>>()
        for (row in rows) {
            val byDate = result.getOrPut(row.medicationId) { LinkedHashMap() }
            val current = byDate[row.scheduledDate] ?: DayStatusBreakdown()
            byDate[row.scheduledDate] = when (row.status) {
                SlotStatus.COMPLETED -> current.copy(completed = current.completed + row.count)
                SlotStatus.SKIPPED -> current.copy(skipped = current.skipped + row.count)
                SlotStatus.EXPIRED -> current.copy(missed = current.missed + row.count)
                SlotStatus.PENDING, SlotStatus.SNOOZED ->
                    current.copy(pending = current.pending + row.count)
            }
        }
        return result.mapValues { (_, byDate) -> byDate.toMap() }
    }

    /**
     * 汇总某药品在给定日期集合上的状态分布 (用于"近 7 天 / 近 30 天依从率")。
     */
    fun sumBreakdowns(
        byDate: Map<String, DayStatusBreakdown>,
        dateStrings: Collection<String>
    ): DayStatusBreakdown {
        var acc = DayStatusBreakdown()
        for (d in dateStrings) {
            byDate[d]?.let { acc = acc + it }
        }
        return acc
    }

    /**
     * 库存可用剩余天数 + 是否预警。
     * 计划型药品的日消耗量应按"实际排班日"折算，否则 INTERVAL / DAYS_OF_WEEK
     * 会被高估日消耗、从而低估可用天数。
     *
     * @param scheduledDosesPerWeek 该药品每周实际排班的时点总数 (0 表示按需/不追踪)
     */
    fun calculateStockRunwayBySchedule(
        currentStock: Float,
        dosesPerScheduledDay: Float,
        scheduledDosesPerWeek: Int,
        minStockAlert: Float = 0f
    ): Pair<Int, Boolean> {
        if (dosesPerScheduledDay <= 0f || scheduledDosesPerWeek <= 0) {
            return calculateStockRunway(currentStock, 0f, minStockAlert)
        }
        // 排班日折算：每周排班 N 次 → 日均消耗 = 单次日量 * N / 7
        val dailyConsumption = dosesPerScheduledDay * (scheduledDosesPerWeek / 7.0f)
        return calculateStockRunway(currentStock, dailyConsumption, minStockAlert)
    }
}
