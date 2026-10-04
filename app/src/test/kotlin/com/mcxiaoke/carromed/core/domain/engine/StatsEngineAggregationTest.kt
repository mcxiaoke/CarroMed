package com.mcxiaoke.carromed.core.domain.engine

import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.domain.model.Dose
import com.mcxiaoke.carromed.core.data.model.PolicyType
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

    /**
     * 长周期 INTERVAL（orsbf P1-5）：「每隔 30 天吃 1 片」的真实日消耗是 1/30 片。
     * 取整版 `round(7/30) → 1` 会按"每周 1 次"折算，消耗高估 4.3 倍。
     * 精确浮点折算理论值：30 / (1 * (7/30) / 7) = 900 天。
     */
    @Test
    fun runwayBySchedule_longIntervalUsesExactFrequency() {
        val perWeek = StatsEngine.scheduledDaysPerWeekExact(
            type = PolicyType.INTERVAL, intervalDays = 30, daysOfWeek = null,
            cycleOnDays = null, cycleOffDays = null
        )
        val (days, alert) = StatsEngine.calculateStockRunwayBySchedule(
            currentStock = 30f,
            dosesPerScheduledDay = 1f,
            scheduledDosesPerWeek = perWeek
        )
        // 末次除法走 Float（展示层历史约定），7.0/30 的双精度尾差经 toFloat
        // 放大后 30 / 0.03333334 ⇒ 899。本测试钉的是量级：不是取整版折算出的
        // 214（高估 4.3 倍），一天级的浮点尾差不影响"预计可用"的用途。
        assertThat(days).isEqualTo(899)
        assertThat(alert).isFalse()
    }

    /**
     * 负库存（D-9 允许账面为负）不得与 [StatsEngine.RUNWAY_UNLIMITED] 撞码
     * （orsbf P1-4）：库存 -1、日消耗 1 时旧哨兵 -1 恰好撞上，
     * "已超支、最紧急"被渲染成"不适用"。哨兵现在是 Int.MIN_VALUE。
     */
    @Test
    fun runway_negativeStockNeverCollidesWithUnlimitedSentinel() {
        val (days, _) = StatsEngine.calculateStockRunway(currentStock = -1f, dailyEstimatedConsumption = 1f)
        assertThat(StatsEngine.isRunwayUnlimited(days)).isFalse()
        assertThat(days).isEqualTo(-1)
    }

    @Test
    fun runwayBySchedule_intervalPolicyDoesNotOverEstimateConsumption() {
        // 隔天服 1 片：单次服药日消耗 1 片，但按日历日只有 ~3.5 次/周
        val (days, alert) = StatsEngine.calculateStockRunwayBySchedule(
            currentStock = 14f,
            dosesPerScheduledDay = 1f,
            scheduledDosesPerWeek = 4.0
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
            scheduledDosesPerWeek = 3.0
        )
        assertThat(days).isEqualTo(70)
    }

    /**
     * 按需服用 ⇒ 没有"日消耗"这个概念 ⇒ 剩余天数**不适用**。
     *
     * 旧实现返回 `Int.MAX_VALUE`（= 2147483647）。那是个**合法但荒谬**的整数：
     * 一旦被算术碰到就溢出，任何消费者也分不清"无限"与"算错了"。
     * 现在返回显式哨兵 [StatsEngine.RUNWAY_UNLIMITED]（-1），由
     * [StatsEngine.isRunwayUnlimited] 判定，UI 渲染成「—」。
     *
     * 预警仍然触发：用户确实设了 20000（展示值 20）的预警线，
     * 而账面只有 10 —— 低于自己的阈值就该告警，这与"有没有日消耗"无关。
     */
    @Test
    fun runwayBySchedule_prnFallsBackToNoConsumption() {
        val (days, alert) = StatsEngine.calculateStockRunwayBySchedule(
            currentStock = 10f,
            dosesPerScheduledDay = 1f,
            scheduledDosesPerWeek = 0.0,
            minStockAlert = 20f
        )
        assertThat(StatsEngine.isRunwayUnlimited(days)).isTrue()
        assertThat(days).isEqualTo(StatsEngine.RUNWAY_UNLIMITED)
        assertThat(alert).isTrue()
    }

    /**
     * 「7 天内必提醒」现在是**显式开关**，默认关闭（决策 E）。
     *
     * 旧实现把 `runwayDays <= 7` 写成一条**隐式**规则：用户没设任何阈值也会被告警。
     * 看不见的告警是"虚假保证"的镜像 —— 他以为自己没设阈值，却总看到红色横幅。
     */
    @Test
    fun runwayBySchedule_shortRunwayAlertIsOptIn() {
        // 默认关闭：剩 5 天不告警（用户没有设任何阈值）
        val (days, alertOff) = StatsEngine.calculateStockRunwayBySchedule(
            currentStock = 5f,
            dosesPerScheduledDay = 1f,
            scheduledDosesPerWeek = 7.0
        )
        assertThat(days).isEqualTo(5)
        assertThat(alertOff).isFalse()

        // 显式开启：同样 5 天就告警
        val (_, alertOn) = StatsEngine.calculateStockRunway(
            currentStock = 5f,
            dailyEstimatedConsumption = 1f,
            minStockAlert = 0f,
            withShortRunwayAlert = true
        )
        assertThat(alertOn).isTrue()
    }
}
