package com.mcxiaoke.carromed.core.domain.engine

import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.data.entity.PolicyTimeEntity
import com.mcxiaoke.carromed.core.data.entity.SchedulePolicyEntity
import com.mcxiaoke.carromed.core.data.model.PolicyType
import org.junit.Test
import java.time.LocalDate

/**
 * 暂停窗口对投影的影响（产品口径：**槽位存在 ⟺ 这个时点会提醒**）。
 *
 * ## 为什么不放在 UI 层过滤
 *
 * 早期实现只让暂停影响闹钟，于是槽位留在库里：
 * 今日清单照列（用户看到一条永远不会兑现的待办）、统计照算（"该吃没吃"）、
 * 闹钟被撤（永远不会响）。**三处不一致**。
 *
 * 让暂停参与投影之后，槽位在源头就不产生，三处自动一致。
 * 本测试守的就是"源头不产生"这条。
 *
 * ## 三态语义必须与 `ReminderSettingsEntity.isPausedOn` 同源
 *
 * `null` 未暂停 / `""` 无限期 / `"2026-10-05"` 暂停至该日**含**。
 * 两处若不同源，就会出现"清单不显示但闹钟照响"这类反向故障。
 */
class SlotProjectionPauseTest {

    private val today: LocalDate = LocalDate.of(2026, 9, 28)
    private val horizon: LocalDate = today.plusDays(6)

    private fun policy() = SchedulePolicyEntity(
        id = 1,
        medicationId = 1,
        policyType = PolicyType.DAILY,
        startDate = today.toString()
    )

    private fun times() = listOf(
        PolicyTimeEntity(policyId = 1, timeOfDay = "08:00", doseAmount = 1000)
    )

    private fun project(pausedUntil: String?) = SlotProjectionEngine.projectSlots(
        policy = policy(),
        times = times(),
        fromDate = today,
        toDate = horizon,
        pausedUntil = pausedUntil
    ).map { it.scheduledDate }

    // ==================== 三态 ====================

    @Test
    fun `未暂停时槽位覆盖整个窗口`() {
        assertThat(project(null)).hasSize(7)
    }

    @Test
    fun `无限期暂停不产生任何槽位`() {
        assertThat(project("")).isEmpty()
    }

    @Test
    fun `暂停至某日含当天 该日不排 之后照排`() {
        val dates = project(today.plusDays(2).toString())
        // 9-28、9-29、9-30 三天被压掉，10-01 起恢复
        assertThat(dates).containsExactly(
            today.plusDays(3).toString(),
            today.plusDays(4).toString(),
            today.plusDays(5).toString(),
            today.plusDays(6).toString()
        )
    }

    @Test
    fun `暂停已到期时不压制任何日期`() {
        // 昨天到期 ⇒ 今天起已恢复
        assertThat(project(today.minusDays(1).toString())).hasSize(7)
    }

    @Test
    fun `暂停期远长于视野时窗口内全空`() {
        assertThat(project(today.plusYears(3).toString())).isEmpty()
    }

    @Test
    fun `解析失败按未暂停处理（宁可多响不可漏响）`() {
        // 与 isPausedOn 同一套降级方向：解析不出来就当没暂停
        assertThat(project("不是日期")).hasSize(7)
    }

    @Test
    fun `两端空白等价于无限期`() {
        assertThat(project("   ")).isEmpty()
    }

    // ==================== 与 isPausedOn 同源 ====================

    @Test
    fun `投影的抑制范围与 isPausedOn 的判定逐日一致`() {
        val pauseEnd = today.plusDays(2)
        val settings = com.mcxiaoke.carromed.core.data.entity.ReminderSettingsEntity(
            medicationId = 1,
            pausedUntil = pauseEnd.toString()
        )
        val projected = project(pauseEnd.toString()).toSet()

        // 逐日检查：某天被投影 ⟺ 该天不被 isPausedOn 判为暂停
        for (offset in 0..6L) {
            val date = today.plusDays(offset)
            val key = date.toString()
            assertThat(projected.contains(key)).isEqualTo(!settings.isPausedOn(date))
        }
    }

    @Test
    fun `未暂停时逐日都产生槽位`() {
        val settings = com.mcxiaoke.carromed.core.data.entity.ReminderSettingsEntity(1, pausedUntil = null)
        val projected = project(null).toSet()
        for (offset in 0..6L) {
            assertThat(projected.contains(today.plusDays(offset).toString()))
                .isEqualTo(!settings.isPausedOn(today.plusDays(offset)))
        }
    }

    // ==================== 多时点 ====================

    @Test
    fun `暂停期内该日的所有时点都被压制`() {
        val multi = listOf(
            PolicyTimeEntity(policyId = 1, timeOfDay = "08:00", doseAmount = 1000),
            PolicyTimeEntity(policyId = 1, timeOfDay = "20:00", doseAmount = 500)
        )
        val slots = SlotProjectionEngine.projectSlots(
            policy = policy(),
            times = multi,
            fromDate = today,
            toDate = today.plusDays(1),
            pausedUntil = today.toString()
        )
        // 今天全压掉，只剩明天两次
        assertThat(slots.map { it.scheduledTime }).containsExactly("08:00", "20:00")
        assertThat(slots.map { it.doseAmount }).containsExactly(1000, 500)
    }

    // ==================== 其它策略类型 ====================

    @Test
    fun `隔日策略在暂停期同样被压制`() {
        val everyOther = policy().copy(policyType = PolicyType.INTERVAL, intervalDays = 2)
        val slots = SlotProjectionEngine.projectSlots(
            policy = everyOther,
            times = times(),
            fromDate = today,
            toDate = today.plusDays(6),
            pausedUntil = today.plusDays(4).toString()
        )
        // 9-28 / 9-30 / 10-02 / 10-04 / 10-06 是排班日；前四个落在暂停期内被压掉
        assertThat(slots.map { it.scheduledDate }).containsExactly(today.plusDays(6).toString())
    }

    @Test
    fun `PRN 策略无论是否暂停都不产生槽位`() {
        val prn = policy().copy(policyType = PolicyType.PRN)
        assertThat(
            SlotProjectionEngine.projectSlots(
                policy = prn, times = times(),
                fromDate = today, toDate = horizon, pausedUntil = null
            )
        ).isEmpty()
    }
}
