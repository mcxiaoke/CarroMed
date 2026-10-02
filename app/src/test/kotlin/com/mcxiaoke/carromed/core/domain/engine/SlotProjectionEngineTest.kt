package com.mcxiaoke.carromed.core.domain.engine

import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.domain.model.Dose
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
            PolicyTimeEntity(policyId = 1L, timeOfDay = "08:00", doseAmount = 1000, sortOrder = 0),
            PolicyTimeEntity(policyId = 1L, timeOfDay = "20:00", doseAmount = 2000, sortOrder = 1)
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
        assertThat(Dose(slots[0].doseAmount).asFloat).isEqualTo(1.0f)
        assertThat(Dose(slots[1].doseAmount).asFloat).isEqualTo(2.0f)
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
            PolicyTimeEntity(policyId = 2L, timeOfDay = "09:00", doseAmount = 1000)
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
            PolicyTimeEntity(policyId = 3L, timeOfDay = "07:30", doseAmount = 1000)
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
            PolicyTimeEntity(policyId = 4L, timeOfDay = "10:00", doseAmount = 1000)
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
            PolicyTimeEntity(policyId = 5L, timeOfDay = "08:00", doseAmount = 1000)
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
            PolicyTimeEntity(policyId = 6L, timeOfDay = "08:00", doseAmount = 1000)
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

    // ================================================================
    // DST 空洞/重叠显式选边（§二-17 / orsbf P1-10）
    //
    // 引擎在 SlotProjectionEngine.kt 对 `validOffsets` 三分支显式选边；
    // 属性测试（SlotProjectionDstPropertyTest D1/D3）守"不漂移"，这里用
    // **确定性**断言把选边方向本身钉死——属性测试对这两个选边都是宽容的，
    // 只有场景测试能防止"选边方向被悄悄翻转"。
    // 美国东部 2026 年换季日：3 月 8 日 02:00→03:00（前跳）、11 月 1 日 02:00→01:00（回拨）。
    // ================================================================

    /** 美东时区 + 单时点 DAILY 的便捷投影 */
    private fun nyDailySlots(timeOfDay: String, date: LocalDate) =
        SlotProjectionEngine.projectSlots(
            policy = SchedulePolicyEntity(
                id = 7L,
                medicationId = 107L,
                policyType = PolicyType.DAILY,
                startDate = date.toString(),
                endDate = null
            ),
            times = listOf(PolicyTimeEntity(policyId = 7L, timeOfDay = timeOfDay, doseAmount = 1000)),
            fromDate = date,
            toDate = date,
            zoneId = ZoneId.of("America/New_York")
        ).single()

    @Test
    fun projectSlots_dstGap_defersForwardToPostTransitionInstant() {
        // 春季前跳空洞：2026-03-08 02:30 在美东不存在（02:00 整段跳到 03:00）。
        // 选边语义：向后顺延到切换后的第一个有效时刻 = 03:30 EDT（UTC-04:00）。
        // 槽位日期/时刻字段仍如实写计划值 02:30（显示口径不变，只有 scheduledTs 被顺延）。
        val slot = nyDailySlots("02:30", LocalDate.of(2026, 3, 8))

        assertThat(slot.scheduledDate).isEqualTo("2026-03-08")
        assertThat(slot.scheduledTime).isEqualTo("02:30")
        assertThat(slot.scheduledTs).isEqualTo(
            java.time.LocalDateTime.of(2026, 3, 8, 3, 30)
                .toInstant(java.time.ZoneOffset.ofHours(-4)).toEpochMilli()
        )
    }

    @Test
    fun projectSlots_dstOverlap_picksEarlierInstant() {
        // 秋季回拨重叠：2026-11-01 01:30 在美东出现两次（EDT -04:00 与 EST -05:00）。
        // 选边语义：取较早的一次 = 05:30Z（宁早勿晚）。
        // 反解到后一个偏移应是 01:30 EST（06:30Z）——同一个挂钟时刻。
        val slot = nyDailySlots("01:30", LocalDate.of(2026, 11, 1))

        assertThat(slot.scheduledDate).isEqualTo("2026-11-01")
        assertThat(slot.scheduledTime).isEqualTo("01:30")
        assertThat(slot.scheduledTs).isEqualTo(
            java.time.LocalDateTime.of(2026, 11, 1, 1, 30)
                .toInstant(java.time.ZoneOffset.ofHours(-4)).toEpochMilli()
        )
        assertThat(slot.scheduledTs).isNotEqualTo(
            java.time.LocalDateTime.of(2026, 11, 1, 1, 30)
                .toInstant(java.time.ZoneOffset.ofHours(-5)).toEpochMilli()
        )
    }

    @Test
    fun projectSlots_dstNormalDay_usesSoleOffsetUnchanged() {
        // 正常日期（唯一偏移）：行为必须与显式选边引入前逐位一致——
        // 这条是"选边重构没有改变正常路径"的回归锚。
        val slot = nyDailySlots("08:00", LocalDate.of(2026, 3, 9))

        assertThat(slot.scheduledTs).isEqualTo(
            java.time.LocalDateTime.of(2026, 3, 9, 8, 0)
                .toInstant(java.time.ZoneOffset.ofHours(-4)).toEpochMilli()
        )
    }
}
