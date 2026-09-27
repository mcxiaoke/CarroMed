package com.mcxiaoke.carromed.core.domain.engine

import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.data.entity.PolicyTimeEntity
import com.mcxiaoke.carromed.core.data.entity.SchedulePolicyEntity
import com.mcxiaoke.carromed.core.data.model.PolicyType
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 槽位投影引擎单元测试
 * 测试 DAILY、INTERVAL (跨月/闰年边界)、DAYS_OF_WEEK、CYCLE、PRN 及区间截断规则
 */
class SlotProjectionEngineTest {

    private val testZoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun projectSlots_dailyPolicy_generatesAllDaysAndTimes() {
        val policy = SchedulePolicyEntity(
            id = 1L,
            medicationId = 101L,
            policyType = PolicyType.DAILY,
            startDate = "2026-10-01",
            endDate = null
        )
        val times = listOf(
            PolicyTimeEntity(policyId = 1L, timeOfDay = "08:00", doseAmount = 1.0f, sortOrder = 0),
            PolicyTimeEntity(policyId = 1L, timeOfDay = "20:00", doseAmount = 2.0f, sortOrder = 1)
        )

        val from = LocalDate.of(2026, 10, 1)
        val to = LocalDate.of(2026, 10, 3) // 3 天: 10-01, 10-02, 10-03

        val slots = SlotProjectionEngine.projectSlots(policy, times, from, to, testZoneId)

        // 3 天 * 2 次 = 6 个槽位
        assertThat(slots).hasSize(6)
        assertThat(slots.all { it.status == SlotStatus.PENDING }).isTrue()
        assertThat(slots.map { "${it.scheduledDate} ${it.scheduledTime}" }).containsExactly(
            "2026-10-01 08:00", "2026-10-01 20:00",
            "2026-10-02 08:00", "2026-10-02 20:00",
            "2026-10-03 08:00", "2026-10-03 20:00"
        ).inOrder()
        assertThat(slots[0].doseAmount).isEqualTo(1.0f)
        assertThat(slots[1].doseAmount).isEqualTo(2.0f)
    }

    @Test
    fun projectSlots_intervalPolicy_handlesMonthBoundaryAndLeapYear() {
        // 2028 是闰年：2月有29天
        val policy = SchedulePolicyEntity(
            id = 2L,
            medicationId = 102L,
            policyType = PolicyType.INTERVAL,
            intervalDays = 2, // 隔日服用 (每2天一次)
            startDate = "2028-02-27"
        )
        val times = listOf(
            PolicyTimeEntity(policyId = 2L, timeOfDay = "09:00", doseAmount = 1.0f)
        )

        val from = LocalDate.of(2028, 2, 27)
        val to = LocalDate.of(2028, 3, 3)

        val slots = SlotProjectionEngine.projectSlots(policy, times, from, to, testZoneId)

        // 2028-02-27 (day 0, scheduled)
        // 2028-02-28 (day 1, skip)
        // 2028-02-29 (day 2, scheduled - 闰年29日)
        // 2028-03-01 (day 3, skip)
        // 2028-03-02 (day 4, scheduled)
        // 2028-03-03 (day 5, skip)
        val dates = slots.map { it.scheduledDate }
        assertThat(dates).containsExactly(
            "2028-02-27",
            "2028-02-29",
            "2028-03-02"
        ).inOrder()
    }

    @Test
    fun projectSlots_daysOfWeekPolicy_matchesOnlySpecifiedWeekdays() {
        val policy = SchedulePolicyEntity(
            id = 3L,
            medicationId = 103L,
            policyType = PolicyType.DAYS_OF_WEEK,
            daysOfWeek = listOf(1, 3, 5), // 周一、周三、周五
            startDate = "2026-10-05" // 2026-10-05 是周一
        )
        val times = listOf(
            PolicyTimeEntity(policyId = 3L, timeOfDay = "07:30", doseAmount = 1.0f)
        )

        // 一整周：2026-10-05(周一) 到 2026-10-11(周日)
        val from = LocalDate.of(2026, 10, 5)
        val to = LocalDate.of(2026, 10, 11)

        val slots = SlotProjectionEngine.projectSlots(policy, times, from, to, testZoneId)

        val dates = slots.map { it.scheduledDate }
        assertThat(dates).containsExactly(
            "2026-10-05", // 周一
            "2026-10-07", // 周三
            "2026-10-09"  // 周五
        ).inOrder()
    }

    @Test
    fun projectSlots_cyclePolicy_runsOnDaysAndPausesOffDays() {
        // 吃 3 天停 2 天，总周期 5 天
        val policy = SchedulePolicyEntity(
            id = 4L,
            medicationId = 104L,
            policyType = PolicyType.CYCLE,
            cycleOnDays = 3,
            cycleOffDays = 2,
            startDate = "2026-10-01"
        )
        val times = listOf(
            PolicyTimeEntity(policyId = 4L, timeOfDay = "10:00", doseAmount = 1.0f)
        )

        val from = LocalDate.of(2026, 10, 1)
        val to = LocalDate.of(2026, 10, 10) // 10 天，两个整周期

        val slots = SlotProjectionEngine.projectSlots(policy, times, from, to, testZoneId)
        val dates = slots.map { it.scheduledDate }

        // 周期 1: 10-01, 10-02, 10-03 服用; 10-04, 10-05 停用
        // 周期 2: 10-06, 10-07, 10-08 服用; 10-09, 10-10 停用
        assertThat(dates).containsExactly(
            "2026-10-01", "2026-10-02", "2026-10-03",
            "2026-10-06", "2026-10-07", "2026-10-08"
        ).inOrder()
    }

    @Test
    fun projectSlots_prnPolicy_returnsEmptySlots() {
        val policy = SchedulePolicyEntity(
            id = 5L,
            medicationId = 105L,
            policyType = PolicyType.PRN,
            startDate = "2026-10-01"
        )
        val times = listOf(
            PolicyTimeEntity(policyId = 5L, timeOfDay = "08:00", doseAmount = 1.0f)
        )

        val slots = SlotProjectionEngine.projectSlots(
            policy, times,
            LocalDate.of(2026, 10, 1),
            LocalDate.of(2026, 10, 7),
            testZoneId
        )
        assertThat(slots).isEmpty()
    }

    @Test
    fun projectSlots_endDateClipping_doesNotProjectPastEndDate() {
        val policy = SchedulePolicyEntity(
            id = 6L,
            medicationId = 106L,
            policyType = PolicyType.DAILY,
            startDate = "2026-10-01",
            endDate = "2026-10-03" // 疗程在 10-03 截止
        )
        val times = listOf(
            PolicyTimeEntity(policyId = 6L, timeOfDay = "08:00", doseAmount = 1.0f)
        )

        // 尝试向后投影到 10-10
        val slots = SlotProjectionEngine.projectSlots(
            policy, times,
            LocalDate.of(2026, 10, 1),
            LocalDate.of(2026, 10, 10),
            testZoneId
        )

        val dates = slots.map { it.scheduledDate }
        assertThat(dates).containsExactly(
            "2026-10-01",
            "2026-10-02",
            "2026-10-03"
        ).inOrder()
    }
}
