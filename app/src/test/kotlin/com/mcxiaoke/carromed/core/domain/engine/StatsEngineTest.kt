package com.mcxiaoke.carromed.core.domain.engine

import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.domain.model.Dose
import com.mcxiaoke.carromed.core.data.entity.DoseRecordEntity
import com.mcxiaoke.carromed.core.data.entity.DoseSlotEntity
import com.mcxiaoke.carromed.core.data.model.RecordStatus
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import org.junit.Test

/**
 * 统计与依从率引擎单元测试
 */
class StatsEngineTest {

    @Test
    fun calculateAdherence_calculatesCorrectRatio() {
        val slots = listOf(
            createSlot(SlotStatus.COMPLETED),
            createSlot(SlotStatus.COMPLETED),
            createSlot(SlotStatus.COMPLETED),
            createSlot(SlotStatus.SKIPPED),
            createSlot(SlotStatus.EXPIRED),
            createSlot(SlotStatus.PENDING)
        )

        val stats = StatsEngine.calculateAdherence(slots)

        // total: 6, completed: 3, skipped: 1, expired: 1, pending: 1
        // decided = 3 + 1 + 1 = 5
        // adherenceRate = 3 / 5 = 0.6f (60%)
        assertThat(stats.totalSlots).isEqualTo(6)
        assertThat(stats.completedCount).isEqualTo(3)
        assertThat(stats.skippedCount).isEqualTo(1)
        assertThat(stats.expiredCount).isEqualTo(1)
        assertThat(stats.pendingCount).isEqualTo(1)
        assertThat(stats.adherenceRate).isWithin(0.001f).of(0.6f)
    }

    @Test
    fun calculateAdherence_allPending_returnsOne() {
        val slots = listOf(
            createSlot(SlotStatus.PENDING),
            createSlot(SlotStatus.PENDING)
        )
        val stats = StatsEngine.calculateAdherence(slots)
        assertThat(stats.adherenceRate).isEqualTo(1.0f)
    }

    @Test
    fun calculateStockRunway_computesDaysAndAlerts() {
        // 30 片药，每天吃 2 片，警戒线为 5 片 -> 可用 15 天，不触发预警
        val (days1, alert1) = StatsEngine.calculateStockRunway(
            currentStock = 30.0f,
            dailyEstimatedConsumption = 2.0f,
            minStockAlert = 5f
        )
        assertThat(days1).isEqualTo(15)
        assertThat(alert1).isFalse()

        // 10 片药，每天吃 2 片 -> 可用 5 天，<= 7 天，触发低库存预警
        val (days2, alert2) = StatsEngine.calculateStockRunway(
            currentStock = 10.0f,
            dailyEstimatedConsumption = 2.0f,
            minStockAlert = 5f
        )
        assertThat(days2).isEqualTo(5)
        assertThat(alert2).isTrue()

        // 4 片药，阈值 5 片 -> 立即触发预警
        val (days3, alert3) = StatsEngine.calculateStockRunway(
            currentStock = 4.0f,
            dailyEstimatedConsumption = 0.5f,
            minStockAlert = 5f
        )
        assertThat(days3).isEqualTo(8)
        assertThat(alert3).isTrue()
    }

    @Test
    fun sumDoseByDate_sumsOnlyCompletedRecords() {
        val records = listOf(
            DoseRecordEntity(medicationId = 1L, actualTs = 1000L, doseTaken = 2000, status = RecordStatus.COMPLETED),
            DoseRecordEntity(medicationId = 1L, actualTs = 2000L, doseTaken = 1500, status = RecordStatus.COMPLETED),
            DoseRecordEntity(medicationId = 1L, actualTs = 3000L, doseTaken = 0, status = RecordStatus.SKIPPED)
        )
        val sum = StatsEngine.sumDoseByDate(records)
        assertThat(sum.asFloat).isEqualTo(3.5f)
    }

    private fun createSlot(status: SlotStatus) = DoseSlotEntity(
        medicationId = 1L,
        policyId = 1L,
        scheduledDate = "2026-09-27",
        scheduledTime = "08:00",
        scheduledTs = 1000000L,
        doseAmount = 1000,
        status = status
    )
}
