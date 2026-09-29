package com.mcxiaoke.carromed.core.domain.engine

import com.mcxiaoke.carromed.core.data.entity.DoseRecordEntity
import com.mcxiaoke.carromed.core.data.entity.DoseSlotEntity
import com.mcxiaoke.carromed.core.data.model.PolicyType
import com.mcxiaoke.carromed.core.data.model.RecordStatus
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import com.mcxiaoke.carromed.core.data.model.SlotStatusCountRow
import com.mcxiaoke.carromed.core.domain.model.Dose
import java.time.LocalDate

/**
 * 用药统计与合规率计算引擎 (StatsEngine)
 * 纯函数式设计，计算用药依从率、库存可用剩余天数、日历热力分布等
 */
object StatsEngine {

    /**
     * 依从度统计结果
     */
    data class AdherenceStats(
        val totalSlots: Int,
        val completedCount: Int,
        val skippedCount: Int,
        val expiredCount: Int,
        val pendingCount: Int,
        val adherenceRate: Float // 0.0f .. 1.0f
    )

    /**
     * 针对给定的槽位列表计算依从率
     * 依从率公式 = completed / (completed + skipped + expired)
     * 若分母为 0 且全部为 pending 则返回 1.0f (新排班尚未触发)，若无槽位返回 1.0f
     */
    fun calculateAdherence(slots: List<DoseSlotEntity>): AdherenceStats {
        if (slots.isEmpty()) {
            return AdherenceStats(
                totalSlots = 0,
                completedCount = 0,
                skippedCount = 0,
                expiredCount = 0,
                pendingCount = 0,
                adherenceRate = 1.0f
            )
        }

        var completed = 0
        var skipped = 0
        var expired = 0
        var pending = 0

        for (slot in slots) {
            when (slot.status) {
                SlotStatus.COMPLETED -> completed++
                SlotStatus.SKIPPED -> skipped++
                SlotStatus.EXPIRED -> expired++
                SlotStatus.PENDING, SlotStatus.SNOOZED -> pending++
            }
        }

        val decided = completed + skipped + expired
        val rate = if (decided > 0) {
            (completed.toFloat() / decided.toFloat()).coerceIn(0.0f, 1.0f)
        } else {
            1.0f
        }

        return AdherenceStats(
            totalSlots = slots.size,
            completedCount = completed,
            skippedCount = skipped,
            expiredCount = expired,
            pendingCount = pending,
            adherenceRate = rate
        )
    }

    /**
     * 计算库存可用剩余天数与预警状态。
     *
     * ## `minStockAlert = 0` 的含义：关闭告警（决策 E）
     *
     * 实体上对这一列的 KDoc 一直写的是「0 表示关闭低库存告警」，
     * 而本函数原先两个分支对它的口径**相反**：
     *
     * | 分支 | 旧行为 | 问题 |
     * | :--- | :--- | :--- |
     * | 无消耗（按需 / 未排班） | `currentStock <= 0 && 0 > 0` ⇒ 恒 false | ✅ 正确地"不告警" |
     * | 有消耗 | `currentStock <= 0` ⇒ **恒 true** | 余额恰好为 0 时永远告警 |
     *
     * 于是同一个"预警线设为 0（关闭）"的用户，会在**不按需服用的药上被永久告警**、
     * 在按需服用的药上完全不告警 —— 判据与配置的含义直接矛盾。
     *
     * 现在统一为：`minStockAlert <= 0` ⇒ **不告警**，两条分支一致。
     * 这尊重实体注释里既有的约定，也让五处调用点（今日 / 药箱 / 详情 / 库存 / 补药）
     * 拿到同一个答案。
     *
     * ## 7 天兜底告警
     *
     * 旧的 `runwayDays <= 7` 是一条**隐式**规则：用户没设预警线也会在剩不足 7 天时
     * 被告警。决策 E 把它收成**显式开关** [withShortRunwayAlert]，
     * 默认**关闭** —— 隐式的、用户看不见的告警同样是"给用户虚假的保证"的镜像：
     * 他以为自己没设任何阈值，却总看到红色横幅。
     *
     * @param currentStock 当前库存
     * @param dailyEstimatedConsumption 预估每日总消耗量
     * @param minStockAlert 最低库存预警阈值。**<= 0 表示关闭低库存告警**。
     * @param withShortRunwayAlert 是否启用"7 天内必提醒"兜底（默认关闭，见上文）
     * @return Pair<剩余天数, 是否触发预警>
     */
    fun calculateStockRunway(
        currentStock: Float,
        dailyEstimatedConsumption: Float,
        minStockAlert: Float = 0f,
        withShortRunwayAlert: Boolean = false
    ): Pair<Int, Boolean> {
        val alertEnabled = minStockAlert > 0f
        if (dailyEstimatedConsumption <= 0f) {
            // 按需服用 / 没有排班 ⇒ 没有"日消耗"这个概念，剩余天数无意义。
            // ⚠️ 旧实现返回 `Int.MAX_VALUE`（∞）。这个值会一路传到 UI 变成
            // "可用 2147483647 天"，读起来像个 bug 而不是"不适用"。
            // 用 [RUNWAY_UNLIMITED] 并在 UI 层显式渲染成"—"。
            val isAlert = alertEnabled && currentStock <= minStockAlert
            return Pair(RUNWAY_UNLIMITED, isAlert)
        }

        val runwayDays = (currentStock / dailyEstimatedConsumption).toInt()
        val isAlert = (alertEnabled && currentStock <= minStockAlert) ||
            (withShortRunwayAlert && runwayDays <= SHORT_RUNWAY_ALERT_DAYS)
        return Pair(runwayDays, isAlert)
    }

    /**
     * "没有日消耗、可用天数不适用"的哨兵。
     *
     * ⚠️ **不是** [Int.MAX_VALUE]。旧实现用 `Int.MAX_VALUE` 表示"无限"，
     * 而它是个**合法但荒谬**的整数：一旦被算术碰到（`- 1`、格式化、排序）就溢出，
     * 任何消费者也分不清"无限"与"算错了"。显式哨兵让"不适用"成为一个可判定的状态。
     */
    const val RUNWAY_UNLIMITED = -1

    /** [withShortRunwayAlert] 启用时，剩余天数不超过该值即告警。 */
    const val SHORT_RUNWAY_ALERT_DAYS = 7

    /** 剩余天数为 [RUNWAY_UNLIMITED] 时的判定（UI 渲染"—"而不是一个天文数字） */
    fun isRunwayUnlimited(runwayDays: Int): Boolean = runwayDays == RUNWAY_UNLIMITED

    /**
     * 低库存告警的**唯一**判据（M4-1）。
     *
     * ## 为什么必须收敛成一处
     *
     * 修复前有**五处**各写一份判定（今日页 / 药箱页 / 详情页 / 库存页 / 补药页），
     * 而它们的口径并不一致：
     *
     * | 调用点 | 旧判据 | 缺什么 |
     * | :--- | :--- | :--- |
     * | 今日页 | `isStockTracked && minStockAlert > 0 && stock <= minStockAlert` | ✅ 完整 |
     * | 药箱页 | 同上 | ✅ 完整 |
     * | 补药页 | `stock <= minStockAlert` | 缺 `isStockTracked` 与 `> 0` 两条守卫 |
     *
     * 补药页因此会对**未开启库存追踪**的药（账面恒 0）亮红灯，
     * 也会对**已关闭告警**（`minStockAlert = 0`）的用户在余额为 0 时亮红灯。
     * 用户在药箱页看不到告警、跳进补药页却看到，会认为数据出了问题。
     *
     * 五处各写一份不是"风格问题"：它保证了下一次改口径时**必然**漏改一处。
     *
     * @param isStockTracked 药品是否开启库存追踪。未追踪的"0"不是低库存，只是没建账。
     * @param minStockAlert 预警线。**<= 0 表示关闭告警**（实体 KDoc 的既定约定）。
     * @param stock 台账账面余额（**展示值**，可为负）
     */
    fun isLowStock(isStockTracked: Boolean, stock: Float, minStockAlert: Float): Boolean {
        if (!isStockTracked) return false
        if (minStockAlert <= 0f) return false
        return stock <= minStockAlert
    }

    /**
     * 按单位分组的累计用量。
     *
     * ## 为什么它必须在领域层（而不是 ViewModel 里）（M6-1）
     *
     * 旧实现在 `StatsViewModel` 内联，而 `StatsUnitGroupingTest` 在**测试文件里
     * 重新实现了一遍** `groupByUnit`。于是那条测试守的是**影子**：
     * 把生产代码改回 `doseSums.sumOf { it.totalDose }`（跨单位求和，
     * 显示「35 ml」这种物理上不存在的量），5 条测试**全部仍然全绿**。
     *
     * 影子测试比没有测试更危险：它让人以为这条不变量有门禁。
     * 修法是提取到领域层，**生产与测试共用同一份实现**
     * —— 与 `AlarmIdentityTest` 强制走生产 `alarmIntent` 是同一条纪律。
     *
     * @param rows 单位 → 整数毫单位。**必须是整数**（D-7）：
     *   旧的 `sumOf { it.totalDose.toDouble() }.toFloat()` 用 Float 累加，
     *   几十条记录就会漂移出 `9.999998` 这种值，显示成 "9.999998 片"。
     * @return 单位 → 展示值
     */
    fun groupByUnit(rows: List<Pair<String, Int>>): Map<String, Float> =
        rows.groupBy({ it.first }, { it.second })
            .mapValues { (_, milliList) -> Dose(milliList.sum()).asFloat }

    /**
     * 按记录集合汇总实际服药剂量。
     *
     * 返回**整数毫单位**（D-7：全程无浮点，累加不漂移）。
     * 只计 `COMPLETED`；`REVERTED` 的事实已被撤销，不再计入消耗。
     */
    fun sumDoseByDate(records: List<DoseRecordEntity>): Dose =
        Dose(records.filter { it.status == RecordStatus.COMPLETED }.sumOf { it.doseTaken })

    // ==================== 真实统计聚合 (P0-1 / P0-2 修复) ====================

    /**
     * 单个药品在一天 (或任意区间) 内的槽位状态分布
     *
     * 口径定义 (全 App 统一，统计报表与打卡矩阵共用)：
     * - **依从率** = 已服 / (已服 + 跳过 + 漏服)，分母只算"已产生结论"的槽位
     * - 待服 (PENDING / SNOOZED) 不进分母，避免"还没到时间"被算成漏服
     * - 漏服 = EXPIRED (计划时间过 2 小时仍未操作，由 AlarmReconciler 结算)
     * - 区间归属以 **计划时间 scheduled_date** 为准，补录不会污染当日依从率
     */
    data class DayStatusBreakdown(
        val completed: Int = 0,
        val skipped: Int = 0,
        val missed: Int = 0,
        val pending: Int = 0
    ) {
        /** 区间内排班总次数 */
        val total: Int get() = completed + skipped + missed + pending

        /** 已产生结论的次数 (依从率分母) */
        val decided: Int get() = completed + skipped + missed

        fun isEmpty(): Boolean = total == 0

        operator fun plus(other: DayStatusBreakdown): DayStatusBreakdown =
            DayStatusBreakdown(
                completed = completed + other.completed,
                skipped = skipped + other.skipped,
                missed = missed + other.missed,
                pending = pending + other.pending
            )
    }

    /** 打卡矩阵 / 每日卡片的单日呈现状态 */
    enum class DayAdherenceState {
        NONE,        // 当日无排班 (该药这天不服)
        UPCOMING,    // 未来日期
        FULLY_TAKEN, // 全部按计划完成
        PARTIAL,     // 部分完成 / 部分待服
        SKIPPED,     // 全部主动跳过
        MISSED       // 存在逾期未确认 (漏服)
    }

    /**
     * 由状态分布解析单日呈现状态。
     *
     * 判定优先级：未排班 → 未来 → 全完成 → 有漏服 → 全跳过 → 其余为部分完成。
     * 这样"跳过"不会遮蔽"漏服"，"漏服"也不会被误显示为"还没到"。
     */
    fun resolveDayState(
        breakdown: DayStatusBreakdown,
        isFutureDay: Boolean
    ): DayAdherenceState = when {
        breakdown.isEmpty() -> DayAdherenceState.NONE
        isFutureDay -> DayAdherenceState.UPCOMING
        breakdown.completed == breakdown.total -> DayAdherenceState.FULLY_TAKEN
        breakdown.missed > 0 -> DayAdherenceState.MISSED
        breakdown.decided == breakdown.total && breakdown.completed == 0 -> DayAdherenceState.SKIPPED
        else -> DayAdherenceState.PARTIAL
    }

    /**
     * 依从率计算 (0f..1f)。分母为 0 (全部待服或无排班) 时返回 1.0f，不冤枉用户。
     */
    fun adherenceOf(completed: Int, skipped: Int, missed: Int): Float {
        val decided = completed + skipped + missed
        return if (decided <= 0) 1.0f
        else (completed.toFloat() / decided.toFloat()).coerceIn(0.0f, 1.0f)
    }

    /** 依从率计算 (重载，直接吃状态分布) */
    fun adherenceOf(breakdown: DayStatusBreakdown): Float =
        adherenceOf(breakdown.completed, breakdown.skipped, breakdown.missed)

    /**
     * 把 DAO 的 [SlotStatusCountRow] 聚合结果折叠为 `药品ID -> 计划日期 -> 状态分布`。
     * 纯函数，可直接单测。
     */
    fun aggregateBreakdowns(
        rows: List<SlotStatusCountRow>
    ): Map<Long, Map<String, DayStatusBreakdown>> {
        val result = LinkedHashMap<Long, MutableMap<String, DayStatusBreakdown>>()
        for (row in rows) {
            val byDate = result.getOrPut(row.medicationId) { LinkedHashMap() }
            val current = byDate[row.scheduledDate] ?: DayStatusBreakdown()
            byDate[row.scheduledDate] = when (row.status) {
                SlotStatus.COMPLETED -> current.copy(completed = current.completed + row.count)
                SlotStatus.SKIPPED -> current.copy(skipped = current.skipped + row.count)
                SlotStatus.EXPIRED -> current.copy(missed = current.missed + row.count)
                SlotStatus.PENDING, SlotStatus.SNOOZED ->
                    current.copy(pending = current.pending + row.count)
            }
        }
        return result.mapValues { (_, byDate) -> byDate.toMap() }
    }

    /**
     * 汇总某药品在给定日期集合上的状态分布 (用于"近 7 天 / 近 30 天依从率")。
     */
    fun sumBreakdowns(
        byDate: Map<String, DayStatusBreakdown>,
        dateStrings: Collection<String>
    ): DayStatusBreakdown {
        var acc = DayStatusBreakdown()
        for (d in dateStrings) {
            byDate[d]?.let { acc = acc + it }
        }
        return acc
    }

    /**
     * 每周实际排班**天数**（不是时点数）。
     *
     * ## 单一实现的意义（M4-1）
     *
     * 此前这个公式在 `InventoryViewModel` 与 `MedicationDetailViewModel` 各写一份，
     * 而详情页那份是坏的（`CYCLE` 写死 5、`INTERVAL` 无下界）。
     * 两页显示同一个数却用不同公式，是"同一屏数字互相打架"类缺陷的标准成因。
     *
     * 各分支的语义：
     *
     * | 类型 | 答案 | 理由 |
     * | :--- | :--- | :--- |
     * | `DAILY` | 7 | 每天都排 |
     * | `INTERVAL` | `round(7/n)`，**下限 1** | `n` 是周期天数；`n ≥ 15` 时每周不到 1 天，但**不能是 0** |
     * | `DAYS_OF_WEEK` | 选中天数 | — |
     * | `CYCLE` | `round(7·on/(on+off))`，**下限 1** | 「吃 N 停 M」的真实排班密度 |
     * | `PRN` / 未知 | 0 | 按需服用没有"排班"，日消耗不适用 |
     *
     * 下界 1 尤其关键：`INTERVAL` 周期 ≥ 15 天时 `round(7/15) = 0`，
     * 而 `calculateStockRunwayBySchedule` 见到 0 会**直接返回"无限"** ——
     * 于是一个账面为负的药在详情页显示"可用 ∞"，且**永不告警**。
     */
    fun scheduledDaysPerWeek(
        type: PolicyType?,
        intervalDays: Int?,
        daysOfWeek: List<Int>?,
        cycleOnDays: Int?,
        cycleOffDays: Int?
    ): Int = when (type) {
        PolicyType.DAILY -> 7
        PolicyType.INTERVAL -> {
            val n = (intervalDays ?: 2).coerceAtLeast(1)
            Math.round(7.0 / n).toInt().coerceAtLeast(1)
        }
        PolicyType.DAYS_OF_WEEK -> (daysOfWeek?.size ?: 0).coerceAtLeast(0)
        PolicyType.CYCLE -> {
            val on = (cycleOnDays ?: 21).coerceAtLeast(1)
            val off = (cycleOffDays ?: 7).coerceAtLeast(0)
            val total = on + off
            if (total == 0) 0 else Math.max(1, Math.round(7.0 * on / total).toInt())
        }
        PolicyType.PRN, null -> 0
    }

    /**
     * 库存可用剩余天数 + 是否预警。
     * 计划型药品的日消耗量应按"实际排班日"折算，否则 INTERVAL / DAYS_OF_WEEK
     * 会被高估日消耗、从而低估可用天数。
     *
     * @param scheduledDosesPerWeek 该药品每周实际排班的时点总数 (0 表示按需/不追踪)
     */
    fun calculateStockRunwayBySchedule(
        currentStock: Float,
        dosesPerScheduledDay: Float,
        scheduledDosesPerWeek: Int,
        minStockAlert: Float = 0f
    ): Pair<Int, Boolean> {
        if (dosesPerScheduledDay <= 0f || scheduledDosesPerWeek <= 0) {
            return calculateStockRunway(currentStock, 0f, minStockAlert)
        }
        // 排班日折算：每周排班 N 次 → 日均消耗 = 单次日量 * N / 7
        val dailyConsumption = dosesPerScheduledDay * (scheduledDosesPerWeek / 7.0f)
        return calculateStockRunway(currentStock, dailyConsumption, minStockAlert)
    }
}
