package com.mcxiaoke.carromed.core.domain.engine

import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.domain.model.Dose
import com.mcxiaoke.carromed.core.data.entity.DoseRecordEntity
import com.mcxiaoke.carromed.core.data.entity.DoseSlotEntity
import com.mcxiaoke.carromed.core.data.model.RecordStatus
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import com.mcxiaoke.carromed.core.data.model.SlotStatusCountRow
import java.time.LocalDate
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

        // 10 片药，每天吃 2 片 -> 可用 5 天。
        //
        // ⚠️ 预警为 **false**，且这是本轮**有意的行为变更**（决策 E）。
        // 旧实现有一条隐式规则 `runwayDays <= 7` ⇒ 剩 5 天必告警，
        // 于是「明明还剩 10 片、高于自己设的 5 片预警线」也会亮红。
        // 那条规则对用户不可见：他没设任何阈值，却总看到红色横幅。
        // 现在告警只看**他自己设的预警线**，"7 天内必提醒"收成显式开关（默认关闭）。
        //
        // 注意 days2 仍如实算出 5 —— 告警与否不该改变这个数字的计算，
        // 否则"关掉告警"会连可见天数一起关掉，用户就完全不知道还剩多少了。
        val (days2, alert2) = StatsEngine.calculateStockRunway(
            currentStock = 10.0f,
            dailyEstimatedConsumption = 2.0f,
            minStockAlert = 5f
        )
        assertThat(days2).isEqualTo(5)
        assertThat(alert2).isFalse()

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

    @Test
    fun aggregateDailyOverallBreakdowns_aggregatesAcrossMedications() {
        val rows = listOf(
            SlotStatusCountRow(medicationId = 1L, scheduledDate = "2026-09-29", status = SlotStatus.COMPLETED, count = 2),
            SlotStatusCountRow(medicationId = 2L, scheduledDate = "2026-09-29", status = SlotStatus.COMPLETED, count = 1),
            SlotStatusCountRow(medicationId = 1L, scheduledDate = "2026-09-30", status = SlotStatus.PENDING, count = 1)
        )
        val map = StatsEngine.aggregateDailyOverallBreakdowns(rows)
        assertThat(map["2026-09-29"]?.completed).isEqualTo(3)
        assertThat(map["2026-09-29"]?.total).isEqualTo(3)
        assertThat(map["2026-09-30"]?.pending).isEqualTo(1)
    }

    @Test
    fun calculateStreak_scenarios() {
        val today = LocalDate.of(2026, 9, 30)

        // 场景 1：历史 3 天全服，今天全服 -> 4 天连续
        val map1 = mapOf(
            "2026-09-27" to StatsEngine.DayStatusBreakdown(completed = 1),
            "2026-09-28" to StatsEngine.DayStatusBreakdown(completed = 2),
            "2026-09-29" to StatsEngine.DayStatusBreakdown(completed = 1),
            "2026-09-30" to StatsEngine.DayStatusBreakdown(completed = 1)
        )
        assertThat(StatsEngine.calculateStreak(today, map1)).isEqualTo(4)

        // 场景 2：历史 3 天全服，今天还没到服药时间 (pending=1, completed=0) -> 不断签，保留截止昨天的 3 天
        val map2 = mapOf(
            "2026-09-27" to StatsEngine.DayStatusBreakdown(completed = 1),
            "2026-09-28" to StatsEngine.DayStatusBreakdown(completed = 2),
            "2026-09-29" to StatsEngine.DayStatusBreakdown(completed = 1),
            "2026-09-30" to StatsEngine.DayStatusBreakdown(pending = 1)
        )
        assertThat(StatsEngine.calculateStreak(today, map2)).isEqualTo(3)

        // 场景 3：今天发生漏服 (missed=1) -> 今天破签，streak=0
        val map3 = mapOf(
            "2026-09-29" to StatsEngine.DayStatusBreakdown(completed = 1),
            "2026-09-30" to StatsEngine.DayStatusBreakdown(missed = 1)
        )
        assertThat(StatsEngine.calculateStreak(today, map3)).isEqualTo(0)

        // 场景 4：昨天漏服 (missed=1)，今天还没服 -> streak=0
        val map4 = mapOf(
            "2026-09-28" to StatsEngine.DayStatusBreakdown(completed = 1),
            "2026-09-29" to StatsEngine.DayStatusBreakdown(missed = 1),
            "2026-09-30" to StatsEngine.DayStatusBreakdown(pending = 1)
        )
        assertThat(StatsEngine.calculateStreak(today, map4)).isEqualTo(0)

        // 场景 5：隔日服药（9-27 服、9-28 无排班、9-29 服、9-30 无排班） -> 2 天有效连续
        val map5 = mapOf(
            "2026-09-27" to StatsEngine.DayStatusBreakdown(completed = 1),
            // 9-28 无记录
            "2026-09-29" to StatsEngine.DayStatusBreakdown(completed = 1)
            // 9-30 无记录
        )
        assertThat(StatsEngine.calculateStreak(today, map5)).isEqualTo(2)
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
