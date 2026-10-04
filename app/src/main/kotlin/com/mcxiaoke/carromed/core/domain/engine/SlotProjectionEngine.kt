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

    /** 周期用药「吃药天数 / 停药天数」各自的上界：远离任何真实疗程，只为防止相加溢出 Int。 */
    private const val MAX_CYCLE_DAYS = 3650

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
                    // ⚠️ `scheduledTime` 必须用**归一化后**的值（C-40）。
                    //
                    // 坏时点串（导入的备份、手改的库）解析失败会回退到 08:00，
                    // 旧代码 `scheduledTs` 用了回退值、`scheduledTime` 却原样留着坏串 ——
                    // 于是**同一个槽位自称两个时间**：时间戳说 08:00，
                    // 而 `scheduledTime`（今日页显示的文案 + 闹钟 Uri 身份寻址的键）说别的。
                    //
                    // 后果不只是"显示难看"：闹钟按 `(medId, date, time, kind)` 内容寻址，
                    // 坏串进 Uri 之后，`scheduledTime` 与实际触发时刻对不上，
                    // 排查时看到的是"闹钟在 08:00 响，但槽位写的是 25:99"。
                    // 这里让两个字段说同一句话，坏数据也只坏在一处、可见。
                    val normalizedTime = localTime.format(TIME_FORMATTER)
                    val dateTime = LocalDateTime.of(current, localTime)
                    // DST 空洞/重叠显式选边（orsbf P1-10 / §二-17）。
                    //
                    // 本地时刻在换季日可能不存在（春季前跳空洞）或出现两次（秋季回拨重叠）。
                    // 这里用 validOffsets 显式检测并钉死选边方向，防止未来重构悄悄改变
                    // `atZone` 的既定行为；两个方向都遵循同一条用药安全原则：
                    // **宁可早/晚响一次，也不能让该响的提醒凭空消失或提前到未到点的时刻**。
                    // - 重叠：本地时刻出现两次 ⇒ 取**较早**的一次（宁早勿晚，晚响才是事故方向）；
                    // - 空洞：该时刻不存在 ⇒ 顺延到切换后的第一个有效时刻（gap 宽度向后跳，
                    //   如美东 02:30 → 03:30、Lord Howe 02:00 → 02:30）。
                    // 中国无 DST，实害为零；本段为正确性债的显式偿还，
                    // 语义由 SlotProjectionDstPropertyTest（D1/D3）与
                    // SlotProjectionEngineTest 的确定性场景测试共同钉住。
                    val validOffsets = zoneId.rules.getValidOffsets(dateTime)
                    val epochMilli = when {
                        validOffsets.size > 1 ->
                            // 秋季回拨重叠：localDateTime − offset = 瞬时，偏移越大瞬时越早
                            //（美东 01:30：-04:00 ⇒ 05:30Z 早于 -05:00 ⇒ 06:30Z），取较早的一次
                            dateTime.toInstant(validOffsets.maxBy { it.totalSeconds })
                        validOffsets.isEmpty() ->
                            // 春季前跳空洞：atZone 的既定行为即向后顺延到切换后时刻
                            dateTime.atZone(zoneId).toInstant()
                        else -> dateTime.toInstant(validOffsets.single())
                    }.toEpochMilli()

                    result.add(
                        DoseSlotEntity(
                            id = 0,
                            medicationId = policy.medicationId,
                            policyId = policy.id,
                            scheduledDate = current.format(DATE_FORMATTER),
                            scheduledTime = normalizedTime,
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
                // ⚠️ 上下界都要夹：`cycleOnDays + cycleOffDays` 相加溢出 Int 会让
                // `totalCycle` 变负，`%` 语义随之崩坏 ⇒ 排班静默错乱。
                // 上界取 ~10 年，远离任何真实疗程（`intervalDays.coerceIn(1, 30)` 是先例）。
                val takeDays = policy.cycleOnDays.coerceIn(1, MAX_CYCLE_DAYS)
                val pauseDays = policy.cycleOffDays.coerceIn(0, MAX_CYCLE_DAYS)
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
