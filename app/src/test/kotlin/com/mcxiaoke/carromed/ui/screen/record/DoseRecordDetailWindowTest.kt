package com.mcxiaoke.carromed.ui.screen.record

import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.domain.service.MANUAL_DOSE_BACKFILL_DAYS
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 记录详情页的两条**时间规则**，以及它们的边界。
 *
 * ## 为什么要分成两个函数测
 *
 * 本轮把"能不能改"拆成了两条不同的规则（`docs/PLAN-RECORD-DETAIL-20260929.md` §5.1）：
 *
 * | 规则 | 适用 | 判据 |
 * | :--- | :--- | :--- |
 * | 仅当天 | 计划内记录的**撤销** | [isSameLocalDay] |
 * | 7 天窗口 | 手动补录的剂量 / 时间 / 撤销 | [isWithinEditWindow] |
 *
 * 拆开的理由是撤销的**后果**不同：计划内记录的撤销会把槽位退回 `PENDING`，
 * 而计划时间早已过去 ⇒ 下一轮对账立刻判它逾期 ⇒ 用户翻到那天会看到一个
 * **永远清不掉**的待办。手动补录没有槽位，不存在这个问题。
 *
 * 7 天与领域层的补录时间窗 `MANUAL_DOSE_BACKFILL_DAYS` 同值同源：
 * 能补多久之前的药，就要能撤多久之前的补录。
 *
 * 两条判据都必须能被单独钉住 —— 混在一起测，改坏其中一条另一条会掩盖它。
 */
class DoseRecordDetailWindowTest {

    private fun tsOfDaysAgo(days: Long): Long =
        LocalDate.now()
            .minusDays(days)
            .atTime(9, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    // ==================== 7 天窗口（手动补录） ====================

    @Test
    fun `今天与昨天和一周内的记录都在 7 天窗口内`() {
        assertThat(isWithinEditWindow(tsOfDaysAgo(0))).isTrue()
        assertThat(isWithinEditWindow(tsOfDaysAgo(1))).isTrue()
        assertThat(isWithinEditWindow(tsOfDaysAgo(6))).isTrue()
    }

    @Test
    fun `第 7 天的记录刚好在窗内（第 8 天算超窗）`() {
        // 边界就是 7 天：今天=0 … 7 天前=7 都可改
        assertThat(isWithinEditWindow(tsOfDaysAgo(7))).isTrue()
        assertThat(isWithinEditWindow(tsOfDaysAgo(8))).isFalse()
    }

    @Test
    fun `更早的记录一律超窗`() {
        for (d in listOf(9L, 10L, 30L, 365L)) {
            assertThat(isWithinEditWindow(tsOfDaysAgo(d))).isFalse()
        }
    }

    /**
     * 未来的时间戳（补录被拒的越界情况）不算在窗口内。
     *
     * 判据写成 `age in 0..7` 而不是 `age <= 7`，就是为了让负数（未来）落到窗外，
     * 否则一个 `actual_ts` 被写坏成未来的记录会变成"永远可改"。
     */
    @Test
    fun `未来时间的记录不在 7 天窗口内`() {
        val future = LocalDate.now()
            .plusDays(1)
            .atTime(9, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        assertThat(isWithinEditWindow(future)).isFalse()
    }

    @Test
    fun `窗口常量是 7 天且与领域层补录窗口同值`() {
        // 改这个数就是改产品规则，所以让它出现在断言里而不是只活在实现里；
        // 与 MANUAL_DOSE_BACKFILL_DAYS 绑定：撤销窗绝不能窄于补录窗
        assertThat(EDITABLE_WINDOW_DAYS).isEqualTo(7L)
        assertThat(EDITABLE_WINDOW_DAYS).isEqualTo(MANUAL_DOSE_BACKFILL_DAYS)
    }

    // ==================== 仅当天（计划内记录的撤销） ====================

    private fun at(hour: Int, minute: Int = 0, daysOffset: Long = 0): Long =
        LocalDate.now().plusDays(daysOffset)
            .atTime(hour, minute)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    @Test
    fun `同一天的任何时刻都算当天`() {
        val now = System.currentTimeMillis()
        // 凌晨与深夜都算"今天" —— 判据是自然日，不是"距今多少小时"
        assertThat(isSameLocalDay(at(0, 1), now)).isTrue()
        assertThat(isSameLocalDay(at(23, 59), now)).isTrue()
    }

    @Test
    fun `昨天与明天都不算当天`() {
        val now = System.currentTimeMillis()
        assertThat(isSameLocalDay(at(23, 59, daysOffset = -1), now)).isFalse()
        assertThat(isSameLocalDay(at(0, 1, daysOffset = 1), now)).isFalse()
    }

    /**
     * ⚠️ 已知缺口（2026-09-29 记入 `docs/PLAN-EXPIRE-WINDOW-20260929.md` §8.1）：
     *
     * 23:00 打卡、次日 00:20 想撤销 —— 按自然日判定会被拒，尽管这次打卡
     * 距现在只有 1 小时 20 分，用户的语义（点错了 / 出门发现没带药）完全成立。
     *
     * 这条断言**故意钉住当前行为**，而不是钉住期望行为：等那一轮补上
     * "距事实发生 ≤ K 小时"的宽限时，它会失败并提醒改这里。
     */
    @Test
    fun `跨午夜的记录按当前规则不可撤销（待后续放宽）`() {
        val yesterdayLate = at(23, 0, daysOffset = -1)
        assertThat(isSameLocalDay(yesterdayLate, System.currentTimeMillis())).isFalse()
    }
}
