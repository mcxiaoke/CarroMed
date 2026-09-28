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
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
/**
 * 夏令时（DST）属性化测试 —— 兑现 `docs/REMINDER-DOMAIN-REDESIGN.md` 附录 A.3。
 *
 * ## 为什么这条要单独写一个文件
 *
 * 引擎算的是**日历**（`LocalDate` × `LocalTime`），不涉及时区；时区只在最后一步
 * `dateTime.atZone(zoneId).toInstant()` 出现。只测 `scheduledDate` / `scheduledTime`
 * 的测试**永远抓不到**时区 bug —— 那一对字段在任何时区下都长得一样。
 *
 * 真正的风险全在 `scheduledTs`（毫单位时间戳，喂给 `AlarmManager`）：
 * 本项目用户遍布时区，换季那天所有定时用药提醒都可能提前/推迟一小时。
 * 而"提前一小时响"在用药场景里是**真会吃错剂量的**事故。
 *
 * ## 这条性质守的到底是什么
 *
 * > 计划写的是"每天 08:00"，那么**任何**时区、**任何**日期上，
 * > 这个槽位的本地时刻都必须恰好是 08:00。
 *
 * 经典反例（都是真实发生过的 bug）：
 * - 用 `ZoneOffset.UTC` 代替 `zoneId` 算瞬时 ⇒ 换季后本地时刻漂 1 小时
 * - 先取 `epochMilli` 再 `plusDays(1)` ⇒ 换季后每天 08:00 变成 07:00 / 09:00，
 *   而且**再也回不来**（漂移会累积）
 * - 缓存了 `ZoneRules` 却漏了后续的规则更新
 * - 只在开发机（单一时区）上测 ⇒ 全绿
 *
 * ## 关于"第三方成熟库"
 *
 * `java.time` 本身就是成熟库。这里的属性化测的是**我们调用它的方式**，
 * 不是测 JDK —— 所以变量是"时区 × 日期 × 时刻 × 跨度"，
 * 而被断言的是"我们产出的 `scheduledTs` 反解回本地时刻后是否仍等于计划值"。
 *
 * 纯 JVM、不依赖 Android：jqwik 走 JUnit Platform，不能与 Robolectric 的
 * JUnit 4 Runner 混用（见 `AGENTS.md` §3）。端到端那一条放在
 * `DoseSlotDstServiceTest`（Robolectric + 真库）。
 */
class SlotProjectionDstPropertyTest {

    private val fmt = DateTimeFormatter.ofPattern("HH:mm")

    /** DAILY + 单一时刻：把"日历 → 瞬时"这条链路单独拎出来，性质最干净 */
    private fun dailySlots(
        zone: ZoneId,
        from: LocalDate,
        days: Int,
        timeOfDay: String
    ): List<Pair<LocalDate, Long>> = SlotProjectionEngine.projectSlots(
        policy = SchedulePolicyEntity(
            medicationId = 1L,
            policyType = PolicyType.DAILY,
            startDate = from.toString(),
            endDate = null
        ),
        times = listOf(PolicyTimeEntity(policyId = 1L, timeOfDay = timeOfDay, doseAmount = 1000)),
        fromDate = from,
        toDate = from.plusDays(days.toLong() - 1),
        zoneId = zone
    ).map { LocalDate.parse(it.scheduledDate) to it.scheduledTs }

    // ================================================================
    // D1 本地时刻恒定 —— 这条是全部 DST 测试里唯一真正重要的
    // ================================================================

    /**
     * 计划时刻 08:00，在**任何**时区、**任何**日期上，反解回本地时刻都必须是 08:00。
     *
     * ⚠️ 唯一允许的例外是**夏令时空洞**：`atZone` 对不存在的本地时刻
     * （春季前跳那天凌晨 2 点整段消失）会顺延到切换后的第一个有效时刻 ——
     * 这是 `java.time` 的既定行为，也是**正确**行为：
     * 宁可 08:00 变 08:30（晚 30 分钟），也不能让 08:00 的那次服药凭空消失。
     *
     * 但顺延**只允许向后**。若某天出现"计划 08:00、实际 07:30 响"，
     * 用户会在没到点的时候收到提醒 —— 这在用药场景里比晚响危险得多，
     * 所以这条性质把它显式判为失败。
     */
    @Property
    fun `D1 槽位瞬时反解成本地时刻 恒等于计划时刻`(
        @ForAll("anyZones") zone: ZoneId,
        @ForAll("wallClockTimes") timeOfDay: String,
        @ForAll("yearStarts") startYear: LocalDate,
        @ForAll("spans") days: Int
    ) {
        val slots = dailySlots(zone, startYear, days, timeOfDay)
        assertThat(slots).isNotEmpty()
        val intended = LocalTime.parse(timeOfDay, fmt)
        val rules = zone.rules

        slots.forEach { (date, ts) ->
            val local = Instant.ofEpochMilli(ts).atZone(zone)
            val actual = local.toLocalTime()

            if (actual == intended) {
                // 正常路径：本地日期也必须逐条对得上，不能"日期 +1、时间不变"
                assertThat(local.toLocalDate()).isEqualTo(date)
            } else {
                // 唯一豁免：该本地时刻落在 DST 空洞里（当天不存在）
                val gap = rules.getValidOffsets(LocalDateTime.of(date, intended))
                assertThat(gap).isEmpty()   // 不是空洞 ⇒ 漂移是 bug
                assertThat(actual).isGreaterThan(intended)  // 只许向后，不许提前
            }
        }
    }

    // ================================================================
    // D2 UTC 间隔：换季处为 ±1h，其余恒为 24h
    // ================================================================

    /**
     * 相邻两天的 UTC 间隔：正常日 24h，跨 DST 那天 23h（春季前跳）或 25h（秋季回拨）。
     *
     * 用"与 24 小时的偏差"来断言，而不是硬编码 23/25 ——
     * 这样连 30 分钟 DST（Lord Howe）也自动纳入：
     * 它的偏差是 ±0.5h，`abs(deviation) in {0, 0.5, 1.0}` 仍成立。
     */
    @Property
    fun `D2 相邻槽位的 UTC 间隔 偏差不超过一个 DST 偏移量`(
        @ForAll("anyZones") zone: ZoneId,
        @ForAll("yearStarts") startYear: LocalDate,
        @ForAll("spans") days: Int
    ) {
        val ts = dailySlots(zone, startYear, days, "08:00").map { it.second }
        // 容差按**窗口实际覆盖的区间**算，而不是按"起始那一年"——
        // 窗口跨年时（如 12-01 起 120 天）两年的偏移范围可能不同。
        // 单偏移时区（已废除 DST）算出容差 0，于是退化成"必须精确 24h"，
        // 这正是我们对那些时区该有的更强要求。
        val maxOffsetHours = offsetRangeHours(zone, startYear, startYear.plusDays(days.toLong() - 1))
        ts.zipWithNext { a, b ->
            val gapHours = Duration.ofMillis(b - a).toMinutes() / 60.0
            assertThat(Math.abs(gapHours - 24.0))
                .isAtMost(maxOffsetHours + 1e-9)
        }
    }

    /**
     * 换季那一天，UTC 间隔**确实**不等于 24h —— 反例性质。
     *
     * 这条是给 D2 兜底的：如果哪天实现退化成"直接 `ts + 86_400_000`"，
     * D2 仍然会绿（24h 落在容差内？不 —— 24h 恰在容差内，会绿）。
     * 所以必须有一条测试**要求**偏移真实存在，否则"根本没做时区转换"
     * 这个缺陷是测不出来的。
     */
    @Property
    fun `D3 跨 DST 切换日时 UTC 间隔必不为 24 小时`(
        @ForAll("dstZones") zone: ZoneId,
        @ForAll("yearStarts") startYear: LocalDate
    ) {
        val to = startYear.plusDays(365)
        val ts = dailySlots(zone, startYear, 366, "08:00").map { it.second }
        val gaps = ts.zipWithNext { a, b -> Duration.ofMillis(b - a).toMinutes() }
        // 该时区在这个区间里若真的发生过 DST，则至少存在一个非 24h 的间隔
        assertThat(offsetsInRange(zone, startYear, to).distinct().size).isGreaterThan(1)
        assertThat(gaps.any { it != 1440L }).isTrue()
    }

    // ================================================================
    // D4 单调性 —— AlarmManager 依赖它
    // ================================================================

    /**
     * 瞬时序列严格递增。
     *
     * 如果换季处理错了（比如把 `epochMilli` 当成"天数"去加），
     * 就会出现**时刻倒流**：09-30 的闹钟比 10-01 的还晚，
     * 于是同一天排两个闹钟、或者一个都不响。这比漂 1 小时更难排查。
     */
    @Property
    fun `D4 槽位瞬时严格递增 不存在时刻倒流`(
        @ForAll("anyZones") zone: ZoneId,
        @ForAll("yearStarts") startYear: LocalDate,
        @ForAll("spans") days: Int
    ) {
        val ts = dailySlots(zone, startYear, days, "03:30").map { it.second }
        ts.zipWithNext { a, b -> assertThat(b).isGreaterThan(a) }
    }

    // ================================================================
    // D5 非法/缺失时区信息时的降级方向
    // ================================================================

    /**
     * 恒定偏移量时区（UTC、Asia/Shanghai 这类无 DST 的）间隔必须**精确** 24h。
     *
     * 守的是"没做多余的事"：在这些时区上多算一次偏移就是 bug。
     */
    @Property
    fun `D5 无夏令时时区上 UTC 间隔精确等于 24 小时`(
        @ForAll("fixedOffsetZones") zone: ZoneId,
        @ForAll("yearStarts") startYear: LocalDate,
        @ForAll("spans") days: Int
    ) {
        val ts = dailySlots(zone, startYear, days, "08:00").map { it.second }
        ts.zipWithNext { a, b -> assertThat(b - a).isEqualTo(24L * 3600_000L) }
    }

    // ================================================================
    // 生成器
    // ================================================================

    /**
     * 会发生夏令时切换的时区 —— **D3 专用**（它要求窗口内确实存在切换）。
     *
     * 刻意包含三类边界：
     * - `America/New_York`：典型 1h DST（负偏移，注意 `ZoneRules` 的负数方向）
     * - `Australia/Lord_Howe`：**30 分钟** DST（半小时偏移，不是 1h 整除）
     * - `Australia/Sydney` / `Pacific/Auckland`：南半球 ⇒ DST 在**年初/年末**，与北半球相反
     * - `Europe/London`：`ZoneRules` 里以 `Z`（UTC）而不是 `+00:00` 出现，容易踩 `==` 比较的坑
     *
     * ⚠️ 这里**不能**放 `Pacific/Apia`。实测：Samoa 在 2021 年立法**废除了夏令时**，
     * `Pacific/Apia` 2026 全年只有一个 `+13:00`（2020 年还是 `[+14:00, +13:00]`）。
     * 把它放进"一定有 DST"的清单，就会得到一条**恒假**的性质 ——
     * 而恒假的性质比没有性质更坏：它要么永远红，要么被人用 `assume` 悄悄跳过。
     * 这类"硬编码的外部事实会腐烂"是本文件最值得记住的一条。
     */
    @Provide
    fun dstZones(): Arbitrary<ZoneId> = Arbitraries.of(
        ZoneId.of("America/New_York"),
        ZoneId.of("Europe/Berlin"),
        ZoneId.of("Europe/London"),
        ZoneId.of("Australia/Lord_Howe"),
        ZoneId.of("Australia/Sydney"),
        ZoneId.of("Pacific/Auckland")
    )

    /**
     * 任意具名时区（含**已不再有 DST** 的）—— D1 / D2 / D4 用。
     *
     * 这三条性质不需要"一定有切换"这个前提：
     * D2 的容差是从该时区**当年实际出现的偏移集合**算出来的（单偏移 ⇒ 容差 0 ⇒
     * 退化成"间隔必须精确 24h"），所以有没有 DST 都能正确判定。
     */
    @Provide
    fun anyZones(): Arbitrary<ZoneId> = Arbitraries.of(
        ZoneId.of("America/New_York"),
        ZoneId.of("Europe/Berlin"),
        ZoneId.of("Europe/London"),
        ZoneId.of("Australia/Lord_Howe"),
        ZoneId.of("Australia/Sydney"),
        ZoneId.of("Pacific/Auckland"),
        ZoneId.of("Pacific/Apia"),        // 已废除 DST：正因为如此才必须留在这一组
        ZoneId.of("America/St_Johns"),    // 负半区 + DST
        ZoneId.of("Asia/Tehran")          // 2022 年才恢复 DST，规则历史很短
    )

    @Provide
    fun fixedOffsetZones(): Arbitrary<ZoneId> = Arbitraries.of(
        ZoneOffset.UTC,
        ZoneId.of("Asia/Shanghai"),
        ZoneId.of("Asia/Tokyo"),
        ZoneId.of("Asia/Kolkata")   // +05:30，半小时偏移区
    )

    /**
     * 起始日取**年内随机一天**而不是固定 1 月 1 日。
     *
     * 固定 1 月 1 日只覆盖北半球的冬季；南半球在 1 月是夏季，
     * 于是"Australia/Sydney 的 1 月 1 日"与"New_York 的 1 月 1 日"覆盖的
     * 实际是同一类情形 —— 看起来测了 4 个时区，其实只测了"年内某段"。
     */
    @Provide
    fun yearStarts(): Arbitrary<LocalDate> =
        Arbitraries.integers().between(2015, 2035).flatMap { y ->
            Arbitraries.integers().between(1, 365).map { d -> LocalDate.of(y, 1, 1).plusDays(d.toLong() - 1) }
        }

    /** 时刻刻意覆盖换季敏感点：01:30 / 02:30（正是"不存在的本地时刻"）、03:30、12:00 */
    @Provide
    fun wallClockTimes(): Arbitrary<String> =
        Arbitraries.of("00:30", "01:30", "02:30", "03:30", "08:00", "12:00", "23:30")

    @Provide
    fun spans(): Arbitrary<Int> = Arbitraries.integers().between(2, 120)

    // ================================================================
    // 辅助
    // ================================================================

    /**
     * 该时区在 `[from, to]` 内**实际生效过**的全部 UTC 偏移。
     *
     * ⚠️ 这里必须走 `ZoneRules.nextTransition`，不能"每隔几天采一次点"。
     * 踩过的坑：初版按 7 天步长采样，而 2 天窗口只采得到 1 个点 ⇒ 算出容差 0，
     * 可窗口恰好跨了一次切换，间隔是 23h ⇒ D2 误报。
     * **均匀采样求极值，在短窗口上必然漏** —— 采样密度的正确性依赖于窗口长度，
     * 而窗口长度正是被测变量本身。
     */
    private fun offsetsInRange(zone: ZoneId, from: LocalDate, to: LocalDate): List<ZoneOffset> {
        val rules = zone.rules
        val start = from.atStartOfDay(zone).toInstant()
        val end = to.plusDays(1).atStartOfDay(zone).toInstant()

        val out = linkedSetOf(rules.getOffset(start))
        var t = start
        // 一年最多 2 次切换，64 是防死循环的兜底而非真实上限
        repeat(64) {
            val transition = rules.nextTransition(t) ?: return@repeat
            if (!transition.instant.isBefore(end)) return@repeat
            out += transition.offsetAfter
            t = transition.instant
        }
        out += rules.getOffset(end.minusNanos(1))
        return out.toList()
    }

    /** 该时区在区间内的最大偏移跨度（小时），用作 D2 的容差 */
    private fun offsetRangeHours(zone: ZoneId, from: LocalDate, to: LocalDate): Double {
        val o = offsetsInRange(zone, from, to)
        if (o.size < 2) return 0.0
        return (o.maxOf { it.totalSeconds } - o.minOf { it.totalSeconds }) / 3600.0
    }
}
