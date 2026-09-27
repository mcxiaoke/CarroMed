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
     * @return 生成的 DoseSlotEntity 列表 (按照 scheduledTs 升序排列)
     */
    fun projectSlots(
        policy: SchedulePolicyEntity,
        times: List<PolicyTimeEntity>,
        fromDate: LocalDate,
        toDate: LocalDate,
        zoneId: ZoneId = ZoneId.systemDefault()
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

        val result = mutableListOf<DoseSlotEntity>()
        var current = effectiveStart

        while (!current.isAfter(effectiveEnd)) {
            if (isScheduledOnDate(policy, current, policyStart)) {
                for (time in times) {
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
