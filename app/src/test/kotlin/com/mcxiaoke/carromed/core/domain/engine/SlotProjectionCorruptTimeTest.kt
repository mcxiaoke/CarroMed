package com.mcxiaoke.carromed.core.domain.engine

import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.data.entity.PolicyTimeEntity
import com.mcxiaoke.carromed.core.data.entity.SchedulePolicyEntity
import com.mcxiaoke.carromed.core.data.model.PolicyType
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 坏时点串必须**只坏在一处**（C-40）。
 *
 * ## 守的不变量
 *
 * 槽位里有两个"时刻"字段：[DoseSlotEntity.scheduledTs]（epoch 毫秒，闹钟实际按它触发）
 * 与 [DoseSlotEntity.scheduledTime]（`HH:mm` 文本，今日页显示它，
 * 而闹钟 Uri 的内容寻址 `(medId, date, time, kind)` 也拿它当键）。
 *
 * 解析失败时引擎回退到 08:00。旧实现的 bug 是**只回退了一半**：
 * `scheduledTs` 用了回退值，`scheduledTime` 原样留着坏串，
 * 于是同一个槽位自称两个时间。
 *
 * ## 断言不变量本身，不断言槽位条数
 *
 * 断言"投影出 7 个槽位"会随时钟变色（见 AGENTS.md 的时点陷阱）。
 * 这里断言的是**字段之间的一致性**，与窗口大小、日期集合无关。
 *
 * ## 为什么要守
 *
 * `scheduledTime` 进闹钟 Uri，坏串会让 `dumpsys alarm` 里的触发时刻
 * 与槽位自述对不上，排查时看到的是"闹钟 08:00 响、槽位写 25:99"。
 * 两个字段说同一句话，坏数据也只坏在一处、可见。
 */
class SlotProjectionCorruptTimeTest {

    private val today: LocalDate = LocalDate.of(2026, 9, 29)
    private val horizon: LocalDate = today.plusDays(2)
    private val zone: ZoneId = ZoneId.systemDefault()

    private fun project(vararg rawTimes: String) = SlotProjectionEngine.projectSlots(
        policy = SchedulePolicyEntity(
            id = 1,
            medicationId = 1,
            policyType = PolicyType.DAILY,
            startDate = today.format(SlotProjectionEngine.DATE_FORMATTER)
        ),
        times = rawTimes.mapIndexed { i, t ->
            PolicyTimeEntity(policyId = 1, timeOfDay = t, doseAmount = 1000, sortOrder = i)
        },
        fromDate = today,
        toDate = horizon,
        zoneId = zone
    )

    /** scheduledTs 反解出的 `HH:mm` —— 闹钟真正会响的那一刻。 */
    private fun timeOfTs(ts: Long): String =
        DateTimeFormatter.ofPattern("HH:mm")
            .withZone(zone)
            .format(Instant.ofEpochMilli(ts))

    @Test
    fun `正常时点串 文本与时间戳一致`() {
        val slots = project("08:00", "22:30")
        assertThat(slots).isNotEmpty()
        slots.forEach { slot ->
            assertThat(slot.scheduledTime).isEqualTo(timeOfTs(slot.scheduledTs))
        }
    }

    @Test
    fun `坏时点串回退到 0800 且文本同步归一化`() {
        val slots = project("25:99")
        assertThat(slots).isNotEmpty()
        slots.forEach { slot ->
            // 回退目标：08:00（既有行为，不在本次改动范围内）
            assertThat(timeOfTs(slot.scheduledTs)).isEqualTo("08:00")
            // ⭐ 本次修复点：文本也必须是 08:00，而不是把 "25:99" 带进槽位
            assertThat(slot.scheduledTime).isEqualTo("08:00")
        }
    }

    @Test
    fun `各种畸形输入都不会让文本与时间戳分叉`() {
        // 覆盖三类坏法：越界、格式错、空串
        val malformed = listOf("25:99", "8:00", "0800", "", "  ", "abc", "08:60", "-1:00")
        val slots = project(*malformed.toTypedArray())
        assertThat(slots).isNotEmpty()
        slots.forEach { slot ->
            assertThat(slot.scheduledTime).isEqualTo(timeOfTs(slot.scheduledTs))
        }
        // 且全部落在可解析的 HH:mm 上，不会把坏值泄漏给闹钟 Uri
        slots.forEach { slot ->
            assertThat(runCatching {
                java.time.LocalTime.parse(slot.scheduledTime, SlotProjectionEngine.TIME_FORMATTER)
            }.isSuccess).isTrue()
        }
    }

    @Test
    fun `坏串与正常串混排时 正常槽位不受影响`() {
        val slots = project("09:15", "99:99")
        assertThat(slots).isNotEmpty()
        slots.forEach { slot ->
            assertThat(slot.scheduledTime).isEqualTo(timeOfTs(slot.scheduledTs))
        }
        // 09:15 那个槽位必须原样保留，不能被"归一化"顺手改成 08:00
        assertThat(slots.map { it.scheduledTime }).contains("09:15")
    }

    @Test
    fun `槽位仍是 PENDING 不因坏串被提前结算`() {
        val slots = project("25:99")
        assertThat(slots).isNotEmpty()
        slots.forEach { assertThat(it.status).isEqualTo(SlotStatus.PENDING) }
    }
}
