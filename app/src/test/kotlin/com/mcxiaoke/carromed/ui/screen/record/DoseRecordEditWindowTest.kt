package com.mcxiaoke.carromed.ui.screen.record

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.ZoneId

/**
 * 「2 天内才可撤销 / 跳过」这条产品规则。
 *
 * ## 为什么单独测一个纯函数
 *
 * 规则挂在**日期**上而不是状态上，而 AGENTS.md 记过一个坑：
 * 按「此刻」写 fixture 的断言会随时钟变色。所以这里把时点构造在
 * 「今天 - N 天」上，而不是固定时间戳 —— 断言的是**相对天数**这个不变量。
 */
class DoseRecordEditWindowTest {

    private fun tsOfDaysAgo(days: Long): Long =
        java.time.LocalDate.now()
            .minusDays(days)
            .atTime(9, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    @Test
    fun `今天与昨天的记录都在可改窗口内`() {
        assertThat(isWithinEditWindow(tsOfDaysAgo(0))).isTrue()
        assertThat(isWithinEditWindow(tsOfDaysAgo(1))).isTrue()
    }

    @Test
    fun `前天的记录刚好出窗（第 3 天算超窗）`() {
        // 边界就是 2 天：今天=0、昨天=1、前天=2 都可改
        assertThat(isWithinEditWindow(tsOfDaysAgo(2))).isTrue()
        assertThat(isWithinEditWindow(tsOfDaysAgo(3))).isFalse()
    }

    @Test
    fun `更早的记录一律超窗`() {
        for (d in listOf(4L, 10L, 30L, 365L)) {
            assertThat(isWithinEditWindow(tsOfDaysAgo(d))).isFalse()
        }
    }

    /**
     * 未来的时间戳（补录被拒的越界情况）不算在窗口内。
     *
     * 判据写成 `age in 0..2` 而不是 `age <= 2`，就是为了让负数（未来）落到窗外，
     * 否则一个 `actual_ts` 被写坏成未来的记录会变成"永远可改"。
     */
    @Test
    fun `未来时间的记录不在可改窗口内`() {
        val future = java.time.LocalDate.now()
            .plusDays(1)
            .atTime(9, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        assertThat(isWithinEditWindow(future)).isFalse()
    }

    @Test
    fun `窗口常量是 2 天`() {
        // 改这个数就是改产品规则，所以让它出现在断言里而不是只活在实现里
        assertThat(EDITABLE_WINDOW_DAYS).isEqualTo(2L)
    }
}
