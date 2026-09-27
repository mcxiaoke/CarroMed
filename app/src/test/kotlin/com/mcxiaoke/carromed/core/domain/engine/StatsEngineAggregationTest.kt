package com.mcxiaoke.carromed.core.domain.engine

import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import com.mcxiaoke.carromed.core.data.model.SlotStatusCountRow
import org.junit.Test

/**
 * 真实统计聚合测试
 *
 * 背景：统计报表页与进展页此前完全是**硬编码假数据** ——
 * 总剂量 1428 片、依从率 98.2%、排行榜写死 4 个药名，
 * 而真实库里只有 3 条服药记录。本文件锁死"真实口径"的行为，
 * 防止再次退化成字面量。
 */
class StatsEngineAggregationTest {

    // ---------- aggregateBreakdowns ----------

    @Test
    fun aggregateBreakdowns_groupsByMedicationAndDate() {
        val rows = listOf(
            SlotStatusCountRow(1L, "2026-09-27", SlotStatus.COMPLETED, 2),
            SlotStatusCountRow(1L, "2026-09-27", SlotStatus.SKIPPED, 1),
            SlotStatusCountRow(1L, "2026-09-26", SlotStatus.PENDING, 2),
            SlotStatusCountRow(2L, "2026-09-27", SlotStatus.COMPLETED, 1)
        )

        val result = StatsEngine.aggregateBreakdowns(rows)

        assertThat(result).hasSize(2)
        val med1Day1 = result.getValue(1L).getValue("2026-09-27")
        assertThat(med1Day1.completed).isEqualTo(2)
        assertThat(med1Day1.skipped).isEqualTo(1)
        assertThat(med1Day1.missed).isEqualTo(0)
        assertThat(med1Day1.pending).isEqualTo(0)
        assertThat(med1Day1.total).isEqualTo(3)
        assertThat(med1Day1.decided).isEqualTo(3)

        // 无排班的日期不出现（调用方按"缺失即无排班"处理）
        assertThat(result.getValue(1L)).doesNotContainKey("2026-09-25")
    }

    @Test
    fun aggregateBreakdowns_mergesPendingAndSnoozedIntoPending() {
        val rows = listOf(
            SlotStatusCountRow(1L, "2026-09-27", SlotStatus.PENDING, 1),
            SlotStatusCountRow(1L, "2026-09-27", SlotStatus.SNOOZED, 1)
        )
        val result = StatsEngine.aggregateBreakdowns(rows)
        val b = result.getValue(1L).getValue("2026-09-27")
        assertThat(b.pending).isEqualTo(2)
        assertThat(b.decided).isEqualTo(0)
    }

    // ---------- resolveDayState ----------

    @Test
    fun resolveDayState_emptyIsNone() {
        val b = StatsEngine.DayStatusBreakdown()
        assertThat(StatsEngine.resolveDayState(b, isFutureDay = false))
            .isEqualTo(StatsEngine.DayAdherenceState.NONE)
    }

    @Test
    fun resolveDayState_futureOverridesCounts() {
        val b = StatsEngine.DayStatusBreakdown(completed = 1, pending = 1)
        assertThat(StatsEngine.resolveDayState(b, isFutureDay = true))
            .isEqualTo(StatsEngine.DayAdherenceState.UPCOMING)
    }

    @Test
    fun resolveDayState_allCompletedIsFullyTaken() {
        val b = StatsEngine.DayStatusBreakdown(completed = 2)
        assertThat(StatsEngine.resolveDayState(b, isFutureDay = false))
            .isEqualTo(StatsEngine.DayAdherenceState.FULLY_TAKEN)
    }

    @Test
    fun resolveDayState_missedTakesPriorityOverSkipped() {
        // 跳过不能遮蔽漏服
        val b = StatsEngine.DayStatusBreakdown(completed = 1, skipped = 1, missed = 1)
        assertThat(StatsEngine.resolveDayState(b, isFutureDay = false))
            .isEqualTo(StatsEngine.DayAdherenceState.MISSED)
    }

    @Test
    fun resolveDayState_allSkippedIsSkipped() {
        val b = StatsEngine.DayStatusBreakdown(skipped = 2)
        assertThat(StatsEngine.resolveDayState(b, isFutureDay = false))
            .isEqualTo(StatsEngine.DayAdherenceState.SKIPPED)
    }

    @Test
    fun resolveDayState_mixedIsPartial() {
        assertThat(
            StatsEngine.resolveDayState(
                StatsEngine.DayStatusBreakdown(completed = 1, pending = 1),
                isFutureDay = false
            )
        ).isEqualTo(StatsEngine.DayAdherenceState.PARTIAL)

        assertThat(
            StatsEngine.resolveDayState(
                StatsEngine.DayStatusBreakdown(completed = 1, skipped = 1),
                isFutureDay = false
            )
        ).isEqualTo(StatsEngine.DayAdherenceState.PARTIAL)

        assertThat(
            StatsEngine.resolveDayState(
                StatsEngine.DayStatusBreakdown(skipped = 1, pending = 1),
                isFutureDay = false
            )
        ).isEqualTo(StatsEngine.DayAdherenceState.PARTIAL)
    }

    // ---------- adherenceOf ----------

    @Test
    fun adherenceOf_excludesPendingFromDenominator() {
        // 2 次已服 + 1 次待服 = 100%，而不是 2/3
        assertThat(StatsEngine.adherenceOf(2, 0, 0)).isEqualTo(1.0f)
        val withPending = StatsEngine.DayStatusBreakdown(completed = 2, pending = 5)
        assertThat(StatsEngine.adherenceOf(withPending)).isEqualTo(1.0f)
    }

    @Test
    fun adherenceOf_countsSkippedAndMissedInDenominator() {
        assertThat(StatsEngine.adherenceOf(2, 1, 1)).isWithin(1e-5f).of(0.5f)
        assertThat(StatsEngine.adherenceOf(0, 0, 4)).isEqualTo(0.0f)
    }

    @Test
    fun adherenceOf_allPendingReturnsOne() {
        assertThat(StatsEngine.adherenceOf(0, 0, 0)).isEqualTo(1.0f)
    }

    // ---------- sumBreakdowns ----------

    @Test
    fun sumBreakdowns_sumsOverRequestedDatesOnly() {
        val byDate = mapOf(
            "2026-09-27" to StatsEngine.DayStatusBreakdown(completed = 1),
            "2026-09-26" to StatsEngine.DayStatusBreakdown(completed = 2, missed = 1),
            "2026-09-01" to StatsEngine.DayStatusBreakdown(completed = 10) // 区间外，应被忽略
        )
        val total = StatsEngine.sumBreakdowns(byDate, listOf("2026-09-27", "2026-09-26", "2026-09-25"))
        assertThat(total.completed).isEqualTo(3)
        assertThat(total.missed).isEqualTo(1)
        assertThat(total.total).isEqualTo(4)
    }

    @Test
    fun sumBreakdowns_emptyInputIsZeroNotNull() {
        val total = StatsEngine.sumBreakdowns(emptyMap(), listOf("2026-09-27"))
        assertThat(total.total).isEqualTo(0)
        assertThat(total.isEmpty()).isTrue()
    }

    // ---------- calculateStockRunwayBySchedule ----------

    @Test
    fun runwayBySchedule_intervalPolicyDoesNotOverEstimateConsumption() {
        // 隔天服 1 片：单次服药日消耗 1 片，但按日历日只有 ~3.5 次/周
        val (days, alert) = StatsEngine.calculateStockRunwayBySchedule(
            currentStock = 14f,
            dosesPerScheduledDay = 1f,
            scheduledDosesPerWeek = 4
        )
        // 14 / (1 * 4/7) = 24.5 → 24 天；若错按"每天 1 片"算只会剩 14 天
        assertThat(days).isEqualTo(24)
        assertThat(alert).isFalse()
    }

    @Test
    fun runwayBySchedule_daysOfWeekUsesActualScheduledDays() {
        // 每周一三五各 1 片 = 每周 3 次
        val (days, _) = StatsEngine.calculateStockRunwayBySchedule(
            currentStock = 30f,
            dosesPerScheduledDay = 1f,
            scheduledDosesPerWeek = 3
        )
        assertThat(days).isEqualTo(70)
    }

    @Test
    fun runwayBySchedule_prnFallsBackToNoConsumption() {
        val (days, alert) = StatsEngine.calculateStockRunwayBySchedule(
            currentStock = 10f,
            dosesPerScheduledDay = 1f,
            scheduledDosesPerWeek = 0,
            minStockAlert = 20f
        )
        assertThat(days).isEqualTo(Int.MAX_VALUE)
        assertThat(alert).isTrue()
    }

    @Test
    fun runwayBySchedule_triggersAlertWithinSevenDays() {
        val (days, alert) = StatsEngine.calculateStockRunwayBySchedule(
            currentStock = 5f,
            dosesPerScheduledDay = 1f,
            scheduledDosesPerWeek = 7
        )
        assertThat(days).isEqualTo(5)
        assertThat(alert).isTrue()
    }
}
