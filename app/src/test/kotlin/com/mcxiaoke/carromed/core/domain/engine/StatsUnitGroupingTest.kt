package com.mcxiaoke.carromed.core.domain.engine

import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.data.model.RecordStatus
import com.mcxiaoke.carromed.core.data.entity.DoseRecordEntity
import com.mcxiaoke.carromed.core.domain.model.Dose
import org.junit.Test

/**
 * 统计层"跨单位不得求和"的分组口径测试（修复 P0-7）。
 *
 * ## 原来的 bug
 *
 * ```kotlin
 * val totalDose = doseSums.sumOf { it.totalDose }   // 30 片 + 5 ml = 35 ?
 * val dominantUnit = rankings.firstOrNull()?.unit ?: "片"
 * ```
 *
 * UI 于是显示「35 ml」—— 一个物理上根本不存在的量。
 *
 * ## 修法
 *
 * 按单位分组；**只有全部药品同单位时**才给出一个总量大数字，
 * 否则逐单位并列展示。宁可不给数字，也不能给错数字。
 *
 * 演示数据里 4 个药全是"片"，所以这条分支**走查截图覆盖不到**，
 * 必须靠测试守住。
 */
class StatsUnitGroupingTest {

    /**
     * ⭐ 直接调 [StatsEngine.groupByUnit] —— 生产与测试**共用同一份实现**。
     *
     * 旧版这里在测试文件里**重写了一遍** `groupByUnit`，于是把生产代码改回
     * 跨单位求和的 bug 时，5 条测试全部仍然全绿：守的是影子。
     * 影子测试比没有测试更危险，它让人以为这条不变量有门禁。
     */
    private fun groupByUnit(rows: List<Pair<String, Int>>): Map<String, Float> =
        StatsEngine.groupByUnit(rows)

    @Test
    fun `单一单位时可以给出总量`() {
        val rows = listOf("片" to 30000, "片" to 20000)
        val grouped = groupByUnit(rows)
        assertThat(grouped).hasSize(1)
        assertThat(grouped["片"]).isEqualTo(50f)
    }

    @Test
    fun `多种单位时分组求和 而不是跨单位相加`() {
        val rows = listOf(
            "片" to 30000,   // 30 片
            "ml" to 5000,    // 5 ml
            "粒" to 12000    // 12 粒
        )
        val grouped = groupByUnit(rows)
        assertThat(grouped).hasSize(3)
        assertThat(grouped["片"]).isEqualTo(30f)
        assertThat(grouped["ml"]).isEqualTo(5f)
        assertThat(grouped["粒"]).isEqualTo(12f)
        // 关键：跨单位求和会得到 47，而 47 不属于任何单位 ——
        // 因此**任何一组都不应等于 47**，尤其是"片"那组不能是 47。
        assertThat(grouped["片"]).isNotEqualTo(47f)
        assertThat(grouped.values).doesNotContain(47f)
    }

    @Test
    fun `同单位的多个药品会合并到一组`() {
        val rows = listOf("片" to 1000, "片" to 1500, "片" to 500)
        assertThat(groupByUnit(rows)).containsExactly("片", 3f)
    }

    @Test
    fun `空集合不产生任何单位`() {
        assertThat(groupByUnit(emptyList())).isEmpty()
    }

    /**
     * 整数累加不漂移。
     *
     * 旧的内联实现是 `sumOf { it.totalDose.toDouble() }.toFloat()` —— Float 累加。
     * 一屏几十条记录就能漂出 `9.999998`，UI 会把它显示成 "9.999998 片"。
     * 领域层用 `Int` 毫单位求和（D-7）从根本上消掉这一类误差。
     */
    @Test
    fun `毫单位用 Int 累加 不产生浮点漂移`() {
        val rows = List(100) { "片" to 100 }   // 100 × 0.1 片 = 10 片
        assertThat(groupByUnit(rows)["片"]).isEqualTo(10f)
    }

    @Test
    fun `REVERTED 的事实不计入消耗（撤销后不应还有消耗记录）`() {
        val records = listOf(
            DoseRecordEntity(medicationId = 1L, actualTs = 1L, doseTaken = 1000, status = RecordStatus.COMPLETED),
            DoseRecordEntity(medicationId = 1L, actualTs = 2L, doseTaken = 1000, status = RecordStatus.REVERTED),
            DoseRecordEntity(medicationId = 1L, actualTs = 3L, doseTaken = 0, status = RecordStatus.SKIPPED)
        )
        val sum = StatsEngine.sumDoseByDate(records)
        assertThat(sum.milli).isEqualTo(1000)
    }

    @Test
    fun `补录事实与实时事实同口径计入 D-7 拆分 RETROSPECTIVE 后的收益`() {
        // 此前"补录"是 RecordStatus 的独立取值，聚合 SQL 写死 status='COMPLETED'
        // 会导致补录超过 2 分钟的服药**库存照扣、统计不计**。
        // 现在 isRetrospective 是布尔列，status 恒为 COMPLETED，天然同口径。
        val records = listOf(
            DoseRecordEntity(medicationId = 1L, actualTs = 1L, doseTaken = 1000,
                status = RecordStatus.COMPLETED, isRetrospective = false),
            DoseRecordEntity(medicationId = 1L, actualTs = 2L, doseTaken = 2000,
                status = RecordStatus.COMPLETED, isRetrospective = true)
        )
        assertThat(StatsEngine.sumDoseByDate(records).milli).isEqualTo(3000)
    }
}
