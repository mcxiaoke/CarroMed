package com.mcxiaoke.carromed.core.domain.engine

import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.data.entity.PolicyTimeEntity
import com.mcxiaoke.carromed.core.data.entity.SchedulePolicyEntity
import com.mcxiaoke.carromed.core.data.model.PolicyType
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * SlotProjectionEngine 的属性化测试 —— 对应不变量 I5 / I6 / I7 / I8
 *
 * 设计见 docs/REMINDER-DOMAIN-REDESIGN.md §4。
 *
 * 这四条性质是"引擎语义漂移"的唯一警报器：
 * - I5 重复槽位（重复槽位 = 重复闹钟 + 重复扣库存）
 * - I6 重排不幂等（重排不幂等 = 每次对账都在删+建 = 闹钟泄漏，见 CODE-REVIEW P1-5）
 * - I7 间隔天数语义漂移（P1-9 那一族 bug 的根：引擎语义对、UI 文案错）
 * - I8 改疗程时凭空多出槽位（会凭空改写用户历史）
 *
 * 这些是**属性**（对任意输入成立）而非示例。手写用例只覆盖想得到的边界；
 * 属性化覆盖想不到的边界 —— 这正是 CODE-REVIEW P1-12 那类空测（测 `Int.coerceIn`）的反面。
 */
class SlotProjectionEngineProperties {

    private val anchor = LocalDate.of(2026, 1, 1)
    private val formatter = SlotProjectionEngine.DATE_FORMATTER

    private fun project(
        type: PolicyType,
        from: LocalDate,
        to: LocalDate,
        intervalDays: Int = 2,
        endDate: String? = null,
        daysOfWeek: List<Int> = emptyList(),
        cycleOn: Int = 21,
        cycleOff: Int = 7
    ): List<Pair<String, String>> = SlotProjectionEngine.projectSlots(
        policy = SchedulePolicyEntity(
            medicationId = 1L,
            policyType = type,
            intervalDays = intervalDays,
            daysOfWeek = daysOfWeek,
            cycleOnDays = cycleOn,
            cycleOffDays = cycleOff,
            startDate = from.format(formatter),
            endDate = endDate
        ),
        times = listOf(PolicyTimeEntity(policyId = 1, timeOfDay = "08:00", doseAmount = 1000)),
        fromDate = from,
        toDate = to
    ).map { it.scheduledDate to it.scheduledTime }

    private fun date(s: String): LocalDate = LocalDate.parse(s, formatter)

    private fun dates(keys: List<Pair<String, String>>): List<LocalDate> = keys.map { date(it.first) }

    // ---------------- I5 槽位唯一性 ----------------

    @Property
    fun `I5 任意策略与区间内 药品-日期-时间 三元组唯一`(
        @ForAll("schedulePolicyTypes") type: PolicyType,
        @ForAll("dayOffsets") fromOffset: Int,
        @ForAll("spans") span: Int,
        @ForAll("intervals") interval: Int,
        @ForAll("weeklyDaySets") days: List<Int>,
        @ForAll("cycleOnDays") cycleOn: Int,
        @ForAll("cycleOffDays") cycleOff: Int
    ) {
        val from = anchor.plusDays(fromOffset.toLong())
        val keys = project(type, from, from.plusDays(span.toLong()), interval,
            daysOfWeek = days, cycleOn = cycleOn, cycleOff = cycleOff)
        assertThat(keys).containsNoDuplicates()
    }

    // ---------------- I6 投影幂等性 ----------------

    @Property
    fun `I6 同参数重复投影结果完全相同`(
        @ForAll("schedulePolicyTypes") type: PolicyType,
        @ForAll("dayOffsets") fromOffset: Int,
        @ForAll("spans") span: Int,
        @ForAll("intervals") interval: Int
    ) {
        val from = anchor.plusDays(fromOffset.toLong())
        val a = project(type, from, from.plusDays(span.toLong()), interval)
        val b = project(type, from, from.plusDays(span.toLong()), interval)
        assertThat(b).isEqualTo(a)
    }

    // ---------------- I7 INTERVAL 相位稳定性 ----------------

    /**
     * P1-9 那一族 bug 的根：不变量成立时，UI 只要如实按 `intervalDays` 生成文案就不会错；
     * 一旦引擎语义被改动（例如把 intervalDays 从"周期"改成"间隔"），这条立刻变红。
     */
    @Property
    fun `I7 INTERVAL 策略相邻槽位间隔恒等于 intervalDays 天`(
        @ForAll("intervals") interval: Int,
        @ForAll("dayOffsets") fromOffset: Int,
        @ForAll("gapSpans") span: Int
    ) {
        val from = anchor.plusDays(fromOffset.toLong())
        val d = dates(project(PolicyType.INTERVAL, from, from.plusDays(span.toLong()), interval))
        d.zipWithNext { a, b ->
            assertThat(ChronoUnit.DAYS.between(a, b)).isEqualTo(interval.toLong())
        }
    }

    @Property
    fun `I7b INTERVAL 策略每个槽位日期与 startDate 的差都能被 intervalDays 整除`(
        @ForAll("intervals") interval: Int,
        @ForAll("dayOffsets") fromOffset: Int,
        @ForAll("spans") span: Int
    ) {
        val from = anchor.plusDays(fromOffset.toLong())
        val d = dates(project(PolicyType.INTERVAL, from, from.plusDays(span.toLong()), interval))
        assertThat(d).isNotEmpty()
        d.forEach { assertThat(ChronoUnit.DAYS.between(from, it) % interval).isEqualTo(0L) }
    }

    // ---------------- I8 疗程单调性 ----------------

    /**
     * 用两个独立参数生成 (紧 endDate 偏移, 额外放宽量)，wide = tight + extra 天然保证
     * tight <= wide，因此"收窄疗程后槽位是子集"这条性质可以无过滤地成立
     * （不过滤 = 不会 jqwik 反复丢弃样本直到超时）。
     */
    @Property
    fun `I8 收窄 endDate 只会减少槽位 绝不新增`(
        @ForAll("schedulePolicyTypes") type: PolicyType,
        @ForAll("dayOffsets") fromOffset: Int,
        @ForAll("spans") span: Int,
        @ForAll("courseEndOffsets") tightOffset: Int,
        @ForAll("courseExtensions") extraOffset: Int
    ) {
        val from = anchor.plusDays(fromOffset.toLong())
        val to = from.plusDays(span.toLong())
        val tightEnd = from.plusDays(tightOffset.toLong())
        val wideEnd = tightEnd.plusDays(extraOffset.toLong())

        val wide = project(type, from, to, endDate = wideEnd.format(formatter)).toSet()
        val tight = project(type, from, to, endDate = tightEnd.format(formatter)).toSet()

        assertThat(wide).containsAtLeastElementsIn(tight)
    }

    // ---------------- 生成器 ----------------

    /** PRN 恒返回空投影，纳入会让性质退化成恒真断言，故排除（PRN 由 SlotProjectionEngineTest 单独覆盖） */
    @Provide
    fun schedulePolicyTypes(): Arbitrary<PolicyType> =
        Arbitraries.of(PolicyType.DAILY, PolicyType.INTERVAL, PolicyType.DAYS_OF_WEEK, PolicyType.CYCLE)

    @Provide
    fun dayOffsets(): Arbitrary<Int> = Arbitraries.integers().between(0, 700)

    @Provide
    fun spans(): Arbitrary<Int> = Arbitraries.integers().between(1, 120)

    /** I7 需要足够跨度以产生至少两个槽位 */
    @Provide
    fun gapSpans(): Arbitrary<Int> = Arbitraries.integers().between(3, 400)

    @Provide
    fun intervals(): Arbitrary<Int> = Arbitraries.integers().between(2, 30)

    @Provide
    fun cycleOnDays(): Arbitrary<Int> = Arbitraries.integers().between(1, 90)

    @Provide
    fun cycleOffDays(): Arbitrary<Int> = Arbitraries.integers().between(0, 30)

    /** 紧 endDate 相对起点的偏移天数 */
    @Provide
    fun courseEndOffsets(): Arbitrary<Int> = Arbitraries.integers().between(1, 200)

    /** 在紧 endDate 之上再放宽的天数（0 表示两者相等，属于合法边界） */
    @Provide
    fun courseExtensions(): Arbitrary<Int> = Arbitraries.integers().between(0, 200)

    /** 引擎用 `daysOfWeek.contains(dayOfWeek)` 判定，重复元素无害，故不做去重 */
    @Provide
    fun weeklyDaySets(): Arbitrary<List<Int>> =
        Arbitraries.integers().between(1, 7).list().ofMinSize(1).ofMaxSize(7)
}
