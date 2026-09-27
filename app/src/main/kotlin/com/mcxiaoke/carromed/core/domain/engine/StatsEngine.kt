package com.mcxiaoke.carromed.core.domain.engine

import com.mcxiaoke.carromed.core.data.entity.DoseRecordEntity
import com.mcxiaoke.carromed.core.data.entity.DoseSlotEntity
import com.mcxiaoke.carromed.core.data.model.RecordStatus
import com.mcxiaoke.carromed.core.data.model.SlotStatus
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
}
