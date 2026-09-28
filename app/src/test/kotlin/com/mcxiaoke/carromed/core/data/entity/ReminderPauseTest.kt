package com.mcxiaoke.carromed.core.data.entity

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

/**
 * 暂停语义的边界测试（`FINAL-PRODUCT` M-02「暂停至某日」）。
 *
 * ## 这里为什么值得单独一个测试类
 *
 * 暂停只有**一个**实现（[ReminderSettingsEntity.isPausedOn]），但它有三条相互纠缠的规则：
 *
 * 1. 三态：未暂停 / 无限期 / 有限期
 * 2. **含当天**：暂停至 D 那天，D 当天仍算暂停，D+1 才恢复
 * 3. **派生而非存储**：判断绝不能写成 `pausedUntil != null`
 *
 * 第 3 条是真正的坑。到期自动恢复之后，`pausedUntil` 仍是非 null 的旧日期，
 * 此时 `!= null` 会让"该排的闹钟静默不再排" —— 用户以为有提醒、实际没有。
 * 这类故障比误响危险得多，而且**不会报错、不会崩**，只是安静地漏提醒。
 *
 * 所以下面每一条都直接对着这三个规则写，而不是笼统地"测一下暂停"。
 */
class ReminderPauseTest {

    private fun settings(pausedUntil: String?) =
        ReminderSettingsEntity(medicationId = 1L, pausedUntil = pausedUntil)

    private val today: LocalDate = LocalDate.of(2026, 9, 28)

    // ---------------- 三态 ----------------

    @Test
    fun `null 表示未暂停`() {
        assertThat(settings(null).isPausedOn(today)).isFalse()
        assertThat(settings(null).pauseDescription(today)).isNull()
        assertThat(settings(null).daysUntilResume(today)).isNull()
    }

    @Test
    fun `空串表示无限期暂停 不会自动恢复`() {
        val s = settings("")
        assertThat(s.isPausedOn(today)).isTrue()
        // 十年后仍然算暂停 —— 无限期就是无限期
        assertThat(s.isPausedOn(today.plusYears(10))).isTrue()
        assertThat(s.daysUntilResume(today)).isNull()   // 不会自动恢复 ⇒ 没有恢复日
        assertThat(s.pauseDescription(today)).isEqualTo("提醒已暂停")
    }

    @Test
    fun `日期表示有限期暂停`() {
        val s = settings("2026-10-15")
        assertThat(s.isPausedOn(today)).isTrue()
        assertThat(s.pauseDescription(today)).isEqualTo("提醒已暂停，18 天后恢复")
    }

    // ---------------- 含当天（最容易写错的一条） ----------------

    @Test
    fun `暂停至当天仍算暂停`() {
        // 选今天当结束日 ⇒ 今天是暂停的最后一天
        val s = settings(today.toString())
        assertThat(s.isPausedOn(today)).isTrue()
    }

    @Test
    fun `次日自动恢复`() {
        val s = settings(today.toString())
        assertThat(s.isPausedOn(today.plusDays(1))).isFalse()
    }

    @Test
    fun `距恢复天数在到期当天为 1`() {
        // 今天是最后一天 ⇒ 明天恢复 ⇒ "1 天后"
        val s = settings(today.toString())
        assertThat(s.daysUntilResume(today)).isEqualTo(1)
        assertThat(s.pauseDescription(today)).isEqualTo("提醒已暂停，明天恢复")
    }

    @Test
    fun `尚未到期时天数递减`() {
        val s = settings("2026-10-05")
        assertThat(s.daysUntilResume(today)).isEqualTo(8)      // 9-28 → 10-5
        assertThat(s.daysUntilResume(LocalDate.of(2026, 10, 4))).isEqualTo(2)
    }

    // ---------------- 跨过恢复日（派生的核心价值） ----------------

    @Test
    fun `跨过恢复日后不再算暂停 —— 闹钟必须恢复排期`() {
        val s = settings("2026-10-15")
        // 恢复日之后：pausedUntil 仍是**非 null 的旧值**，
        // 但判定必须为 false，否则这个药会被永久静默
        val after = LocalDate.of(2026, 10, 16)
        assertThat(s.pausedUntil).isNotNull()             // 前提：字段没被清
        assertThat(s.isPausedOn(after)).isFalse()          // 结论：判定正确
        assertThat(s.pauseDescription(after)).isNull()     // UI 也不该再显示"已暂停"
    }

    @Test
    fun `恢复日当天仍算暂停 次日才恢复`() {
        val s = settings("2026-10-15")
        assertThat(s.isPausedOn(LocalDate.of(2026, 10, 15))).isTrue()
        assertThat(s.isPausedOn(LocalDate.of(2026, 10, 16))).isFalse()
    }

    // ---------------- 异常输入的降级方向 ----------------

    @Test
    fun `日期解析失败按未暂停处理`() {
        // 选"宁可多响也不要静默漏提醒"：解析失败时若当作暂停，
        // 一个格式错误就可能让某个药永久不再提醒，且用户看不出来。
        for (bad in listOf("2026-13-45", "不是日期", "20261015", "")) {
            val s = settings(bad)
            if (bad.isEmpty()) continue      // 空串是"无限期"，已单独覆盖
            assertThat(s.isPausedOn(today)).isFalse()
        }
    }

    @Test
    fun `带空白的日期能正常解析`() {
        assertThat(settings("  2026-10-15  ").isPausedOn(today)).isTrue()
    }

    // ---------------- 跨月 / 闰年 ----------------

    @Test
    fun `跨月与跨年边界`() {
        val crossMonth = settings("2026-10-01")
        assertThat(crossMonth.isPausedOn(LocalDate.of(2026, 9, 30))).isTrue()
        assertThat(crossMonth.isPausedOn(LocalDate.of(2026, 10, 1))).isTrue()
        assertThat(crossMonth.isPausedOn(LocalDate.of(2026, 10, 2))).isFalse()

        val crossYear = settings("2027-01-01")
        assertThat(crossYear.isPausedOn(LocalDate.of(2026, 12, 31))).isTrue()
        assertThat(crossYear.isPausedOn(LocalDate.of(2027, 1, 1))).isTrue()
        assertThat(crossYear.isPausedOn(LocalDate.of(2027, 1, 2))).isFalse()
    }

    @Test
    fun `闰年 2 月 29 日可作为结束日`() {
        val leap = settings("2028-02-29")
        assertThat(leap.isPausedOn(LocalDate.of(2028, 2, 29))).isTrue()
        assertThat(leap.isPausedOn(LocalDate.of(2028, 3, 1))).isFalse()
    }

    // ---------------- 与提醒行为的正交性 ----------------

    @Test
    fun `暂停不影响提醒行为三列`() {
        val s = ReminderSettingsEntity(
            medicationId = 1L,
            isCriticalReminder = true,
            snoozeMinutes = 20,
            advanceMinutes = 10,
            pausedUntil = ""
        )
        assertThat(s.isPausedOn(today)).isTrue()
        // 暂停期间这三列一个都不该变 —— 恢复后要按原样生效
        assertThat(s.isCriticalReminder).isTrue()
        assertThat(s.snoozeMinutes).isEqualTo(20)
        assertThat(s.advanceMinutes).isEqualTo(10)
    }
}
