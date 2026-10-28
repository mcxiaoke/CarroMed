package com.mcxiaoke.carromed.ui.screen.today

import com.mcxiaoke.carromed.core.data.model.SlotStatus

/**
 * 待服药品的时效紧急程度。
 *
 * - [OVERDUE]：已过计划时间（或推迟截止已过 / 状态为已逾期），红色边框加粗高亮；
 * - [DUE_SOON]：距计划时间在 30 分钟以内（如 12:00 提醒，当前在 11:30~12:00），主色边框加粗高亮；
 * - [NORMAL]：距离计划时间还早于 30 分钟，或选中的是未来日期（只读）。
 */
enum class DoseUrgencyState {
    OVERDUE,
    DUE_SOON,
    NORMAL
}

/**
 * 今日待服药品展示分组。
 *
 * 相差 10 分钟及以内的待服槽位聚合为一个组：
 * - 单药组（[isSingle] == true）：以原有单药卡片展示；
 * - 多药组（[isSingle] == false）：合并为一个大卡片，右侧提供大圆圈批量确认。
 */
data class PendingDoseGroup(
    val key: String,
    val items: List<DoseSlotItem>,
    val displayTimeRange: String,
    val urgency: DoseUrgencyState
) {
    val isSingle: Boolean get() = items.size == 1
    val firstItem: DoseSlotItem get() = items.first()
    val slotIds: List<Long> get() = items.map { it.slot.id }
}

object TodayGrouping {

    /** 聚类时间窗口：10 分钟以内归为一组 */
    const val GROUP_WINDOW_MS = 10 * 60 * 1000L

    /** 临近提醒高亮时间窗口：30 分钟以内 */
    const val DUE_SOON_WINDOW_MS = 30 * 60 * 1000L

    /**
     * 判断单条待服槽位的时效状态。
     */
    fun resolveItemUrgency(
        item: DoseSlotItem,
        isActionable: Boolean,
        nowTs: Long = System.currentTimeMillis()
    ): DoseUrgencyState {
        if (!isActionable) return DoseUrgencyState.NORMAL
        val effectiveTs = when (item.slot.status) {
            SlotStatus.SNOOZED -> item.slot.snoozeUntilTs ?: item.slot.scheduledTs
            else -> item.slot.scheduledTs
        }
        return when {
            item.slot.status == SlotStatus.EXPIRED || effectiveTs < nowTs -> DoseUrgencyState.OVERDUE
            effectiveTs - nowTs in 0L..DUE_SOON_WINDOW_MS -> DoseUrgencyState.DUE_SOON
            else -> DoseUrgencyState.NORMAL
        }
    }

    /**
     * 将待服槽位按计划时间分组（相差 10 分钟及以内合并展示）。
     *
     * 聚类规则：以每组首个条目的 [DoseSlotEntity.scheduledTs] 为基准锚点，
     * 时间差在 [GROUP_WINDOW_MS]（10分钟）以内的归为一组；超过则开启新组。
     */
    fun groupPendingItems(
        items: List<DoseSlotItem>,
        isActionable: Boolean,
        nowTs: Long = System.currentTimeMillis()
    ): List<PendingDoseGroup> {
        if (items.isEmpty()) return emptyList()

        val sorted = items.sortedBy { it.slot.scheduledTs }
        val groups = mutableListOf<PendingDoseGroup>()
        var currentChunk = mutableListOf<DoseSlotItem>()
        var anchorTs = 0L

        for (item in sorted) {
            if (currentChunk.isEmpty()) {
                currentChunk.add(item)
                anchorTs = item.slot.scheduledTs
            } else {
                val diff = item.slot.scheduledTs - anchorTs
                if (diff in 0L..GROUP_WINDOW_MS) {
                    currentChunk.add(item)
                } else {
                    groups.add(buildGroup(currentChunk, isActionable, nowTs))
                    currentChunk = mutableListOf(item)
                    anchorTs = item.slot.scheduledTs
                }
            }
        }
        if (currentChunk.isNotEmpty()) {
            groups.add(buildGroup(currentChunk, isActionable, nowTs))
        }

        return groups
    }

    private fun buildGroup(
        chunk: List<DoseSlotItem>,
        isActionable: Boolean,
        nowTs: Long
    ): PendingDoseGroup {
        val key = "pending-group-" + chunk.joinToString("-") { it.slot.id.toString() }
        val firstTime = chunk.first().slot.scheduledTime
        val lastTime = chunk.last().slot.scheduledTime
        val timeRange = if (firstTime == lastTime) firstTime else "$firstTime ~ $lastTime"

        // 组优先级：组内任一逾期 -> 逾期；否则任一临近 -> 临近；否则正常
        val urgencies = chunk.map { resolveItemUrgency(it, isActionable, nowTs) }
        val groupUrgency = when {
            urgencies.any { it == DoseUrgencyState.OVERDUE } -> DoseUrgencyState.OVERDUE
            urgencies.any { it == DoseUrgencyState.DUE_SOON } -> DoseUrgencyState.DUE_SOON
            else -> DoseUrgencyState.NORMAL
        }

        return PendingDoseGroup(
            key = key,
            items = chunk,
            displayTimeRange = timeRange,
            urgency = groupUrgency
        )
    }
}
