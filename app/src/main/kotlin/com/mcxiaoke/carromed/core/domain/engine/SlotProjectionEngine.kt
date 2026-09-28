package com.mcxiaoke.carromed.core.domain.engine

import com.mcxiaoke.carromed.core.data.entity.DoseSlotEntity
import com.mcxiaoke.carromed.core.data.entity.PolicyTimeEntity
import com.mcxiaoke.carromed.core.data.entity.SchedulePolicyEntity
import com.mcxiaoke.carromed.core.data.model.PolicyType
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 服药排班槽位前向投影引擎 (SlotProjectionEngine)
 * 纯函数式设计，根据用药策略和时点生成指定日期范围内的服药槽位。
 */
object SlotProjectionEngine {

    val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /**
     * 计算并投影指定时间范围内的服药槽位
     *
     * @param policy 用药策略
     * @param times 该策略对应的每日服药时间点与单次剂量列表
     * @param fromDate 投影起始日期 (包含)
     * @param toDate 投影截止日期 (包含)
     * @param zoneId 时区，默认系统当前时区
     * @param pausedUntil 暂停结束日，三态语义见 `ReminderSettingsEntity.pausedUntil`：
     *   `null` = 未暂停 / `""` = 无限期 / `"2026-10-15"` = 暂停至该日**含**。
     *   暂停窗口的**起点取 [fromDate]**（即"从今天起生效"）——
     *   本方法只投影未来，所以这与"暂停被设置的那一刻"等价。
     * @return 生成的 DoseSlotEntity 列表 (按照 scheduledTs 升序排列)
     *
     * ## 核心语义：**槽位存在 ⟺ 这个时点会响**
     *
     * 产品口径（用户 2026-09-28 拍板）：「今日清单显示的是**当日会提醒**的项；
     * 没有提醒存在，为什么要在今日显示」。
     *
     * 早期实现把暂停当成"只影响闹钟"的事，于是暂停中的药，它的待服槽位仍留在库里，
     * 被统计算成"该吃没吃"，也仍列在今日清单上 ——
     * 而闹钟早已被对账器撤掉，**永远不会响**。用户看到一条永远不会兑现的待办。
     *
     * 正确做法是让暂停参与**投影**，而不是参与**显示过滤**：
     * 槽位在投影阶段就不产生，统计、闹钟、今日清单三处自动一致，
     * 不必各自再写一遍"要不要跳过它"的判断 —— 那种判断必然会漏一处。
     */
    fun projectSlots(
        policy: SchedulePolicyEntity,
        times: List<PolicyTimeEntity>,
        fromDate: LocalDate,
        toDate: LocalDate,
        zoneId: ZoneId = ZoneId.systemDefault(),
        pausedUntil: String? = null
    ): List<DoseSlotEntity> {
        if (!policy.isActive || times.isEmpty()) {
            return emptyList()
        }
        if (policy.policyType == PolicyType.PRN) {
            // PRN (按需服用) 不生成前向定时槽位
            return emptyList()
        }

        val policyStart = runCatching { LocalDate.parse(policy.startDate, DATE_FORMATTER) }
            .getOrDefault(fromDate)
        val policyEnd = policy.endDate?.let {
            runCatching { LocalDate.parse(it, DATE_FORMATTER) }.getOrNull()
        }

        // 实际有效计算区间
        val effectiveStart = if (fromDate.isBefore(policyStart)) policyStart else fromDate
        val effectiveEnd = if (policyEnd != null && toDate.isAfter(policyEnd)) policyEnd else toDate

        if (effectiveStart.isAfter(effectiveEnd)) {
            return emptyList()
        }

        // 暂停窗口 = [effectiveStart, pauseEnd]（含两端）。解析失败按"未暂停"处理 ——
        // 与 `ReminderSettingsEntity.isPausedOn` 保持同一套降级方向：
        // 「该响的不响」比「多响一次」危险得多。
        val pauseEnd: LocalDate? = when {
            pausedUntil == null -> null
            pausedUntil.isBlank() -> LocalDate.MAX            // 无限期
            else -> runCatching { LocalDate.parse(pausedUntil.trim(), DATE_FORMATTER) }
                .getOrNull()                                  // 解析失败 ⇒ null ⇒ 不抑制
        }
        // 暂停已过期（pauseEnd < effectiveStart）时不抑制任何日期
        val suppressUntil = pauseEnd?.takeIf { !it.isBefore(effectiveStart) }

        val result = mutableListOf<DoseSlotEntity>()
        var current = effectiveStart

        while (!current.isAfter(effectiveEnd)) {
            if (isScheduledOnDate(policy, current, policyStart)) {
                for (time in times) {
                    // 暂停期内不产生槽位：没有提醒，就没有槽位
                    if (suppressUntil != null && !current.isAfter(suppressUntil)) continue
                    val localTime = runCatching { LocalTime.parse(time.timeOfDay, TIME_FORMATTER) }
                        .getOrDefault(LocalTime.of(8, 0))
                    val dateTime = LocalDateTime.of(current, localTime)
                    val epochMilli = dateTime.atZone(zoneId).toInstant().toEpochMilli()

                    result.add(
                        DoseSlotEntity(
                            id = 0,
                            medicationId = policy.medicationId,
                            policyId = policy.id,
                            scheduledDate = current.format(DATE_FORMATTER),
                            scheduledTime = time.timeOfDay,
                            scheduledTs = epochMilli,
                            doseAmount = time.doseAmount,
                            status = SlotStatus.PENDING
                        )
                    )
                }
            }
            current = current.plusDays(1)
        }

        return result.sortedBy { it.scheduledTs }
    }

    /**
     * 判断某具体日期是否属于该用药策略的排班日
     */
    fun isScheduledOnDate(
        policy: SchedulePolicyEntity,
        targetDate: LocalDate,
        policyStartDate: LocalDate
    ): Boolean {
        if (targetDate.isBefore(policyStartDate)) {
            return false
        }
        policy.endDate?.let {
            val end = runCatching { LocalDate.parse(it, DATE_FORMATTER) }.getOrNull()
            if (end != null && targetDate.isAfter(end)) return false
        }

        return when (policy.policyType) {
            PolicyType.DAILY -> true

            PolicyType.INTERVAL -> {
                val interval = if (policy.intervalDays <= 0) 1 else policy.intervalDays
                val daysDiff = ChronoUnit.DAYS.between(policyStartDate, targetDate)
                daysDiff >= 0 && (daysDiff % interval == 0L)
            }

            PolicyType.DAYS_OF_WEEK -> {
                val dayOfWeek = targetDate.dayOfWeek.value // 1 (Mon) .. 7 (Sun)
                policy.daysOfWeek.contains(dayOfWeek)
            }

            PolicyType.CYCLE -> {
                // 周期用药: 用药 cycleOnDays 天，停药 cycleOffDays 天 (如吃 21 天停 7 天)
                val takeDays = policy.cycleOnDays.coerceAtLeast(1)
                val pauseDays = policy.cycleOffDays.coerceAtLeast(0)
                val totalCycle = takeDays + pauseDays
                val daysDiff = ChronoUnit.DAYS.between(policyStartDate, targetDate)
                if (daysDiff < 0) return false
                val cycleDay = (daysDiff % totalCycle).toInt()
                cycleDay < takeDays
            }

            PolicyType.PRN -> false
        }
    }
}
