package com.mcxiaoke.carromed.ui.screen.today

import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.data.entity.DoseSlotEntity
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import org.junit.Test

class TodayGroupingTest {

    private fun createItem(
        slotId: Long,
        time: String,
        ts: Long,
        status: SlotStatus = SlotStatus.PENDING,
        snoozeUntilTs: Long? = null
    ): DoseSlotItem {
        return DoseSlotItem(
            slot = DoseSlotEntity(
                id = slotId,
                medicationId = 1L,
                policyId = 1L,
                scheduledDate = "2026-10-28",
                scheduledTime = time,
                scheduledTs = ts,
                doseAmount = 1000,
                status = status,
                snoozeUntilTs = snoozeUntilTs
            ),
            medication = MedicationEntity(
                id = 1L,
                name = "药品$slotId",
                unit = "片"
            )
        )
    }

    @Test
    fun `空列表返回空组`() {
        val groups = TodayGrouping.groupPendingItems(emptyList(), isActionable = true)
        assertThat(groups).isEmpty()
    }

    @Test
    fun `单个条目返回单药组`() {
        val now = 1_000_000L
        val item = createItem(slotId = 1L, time = "08:00", ts = now + 40 * 60 * 1000L)
        val groups = TodayGrouping.groupPendingItems(listOf(item), isActionable = true, nowTs = now)

        assertThat(groups).hasSize(1)
        assertThat(groups.first().isSingle).isTrue()
        assertThat(groups.first().displayTimeRange).isEqualTo("08:00")
        assertThat(groups.first().urgency).isEqualTo(DoseUrgencyState.NORMAL)
    }

    @Test
    fun `同时间多个药合并为一组`() {
        val baseTs = 1_000_000L
        val item1 = createItem(slotId = 1L, time = "08:00", ts = baseTs)
        val item2 = createItem(slotId = 2L, time = "08:00", ts = baseTs)

        val groups = TodayGrouping.groupPendingItems(listOf(item1, item2), isActionable = true, nowTs = baseTs)
        assertThat(groups).hasSize(1)
        val group = groups.first()
        assertThat(group.isSingle).isFalse()
        assertThat(group.items).hasSize(2)
        assertThat(group.displayTimeRange).isEqualTo("08:00")
        assertThat(group.slotIds).containsExactly(1L, 2L).inOrder()
    }

    @Test
    fun `相差10分钟恰好在临界值内的两个药合并为一组`() {
        val baseTs = 1_000_000L
        val tenMinutesMs = 10 * 60 * 1000L
        val item1 = createItem(slotId = 1L, time = "08:00", ts = baseTs)
        val item2 = createItem(slotId = 2L, time = "08:10", ts = baseTs + tenMinutesMs)

        val groups = TodayGrouping.groupPendingItems(listOf(item1, item2), isActionable = true, nowTs = baseTs)
        assertThat(groups).hasSize(1)
        val group = groups.first()
        assertThat(group.isSingle).isFalse()
        assertThat(group.displayTimeRange).isEqualTo("08:00 ~ 08:10")
    }

    @Test
    fun `相差11分钟超过10分钟窗口的药拆分为不同组`() {
        val baseTs = 1_000_000L
        val elevenMinutesMs = 11 * 60 * 1000L
        val item1 = createItem(slotId = 1L, time = "08:00", ts = baseTs)
        val item2 = createItem(slotId = 2L, time = "08:11", ts = baseTs + elevenMinutesMs)

        val groups = TodayGrouping.groupPendingItems(listOf(item1, item2), isActionable = true, nowTs = baseTs)
        assertThat(groups).hasSize(2)
        assertThat(groups[0].items.first().slot.id).isEqualTo(1L)
        assertThat(groups[1].items.first().slot.id).isEqualTo(2L)
    }

    @Test
    fun `基于锚点的聚类防止链式泛滥`() {
        val baseTs = 1_000_000L
        // item1: 08:00
        // item2: 08:08 (距 item1 8分钟，可进组)
        // item3: 08:15 (距 item2 7分钟，但距 item1 15分钟，应另起新组)
        val item1 = createItem(slotId = 1L, time = "08:00", ts = baseTs)
        val item2 = createItem(slotId = 2L, time = "08:08", ts = baseTs + 8 * 60 * 1000L)
        val item3 = createItem(slotId = 3L, time = "08:15", ts = baseTs + 15 * 60 * 1000L)

        val groups = TodayGrouping.groupPendingItems(listOf(item1, item2, item3), isActionable = true, nowTs = baseTs)
        assertThat(groups).hasSize(2)
        assertThat(groups[0].items.map { it.slot.id }).containsExactly(1L, 2L)
        assertThat(groups[1].items.map { it.slot.id }).containsExactly(3L)
    }

    @Test
    fun `时效状态判定 - 已逾期与临近30分钟`() {
        val now = 10_000_000L
        // 逾期：ts < now
        val overdueItem = createItem(slotId = 1L, time = "08:00", ts = now - 5_000L)
        assertThat(TodayGrouping.resolveItemUrgency(overdueItem, isActionable = true, nowTs = now))
            .isEqualTo(DoseUrgencyState.OVERDUE)

        // 临近：ts - now in 0..30min
        val dueSoonItem = createItem(slotId = 2L, time = "08:20", ts = now + 20 * 60 * 1000L)
        assertThat(TodayGrouping.resolveItemUrgency(dueSoonItem, isActionable = true, nowTs = now))
            .isEqualTo(DoseUrgencyState.DUE_SOON)

        // 正常：ts - now > 30min
        val normalItem = createItem(slotId = 3L, time = "09:00", ts = now + 45 * 60 * 1000L)
        assertThat(TodayGrouping.resolveItemUrgency(normalItem, isActionable = true, nowTs = now))
            .isEqualTo(DoseUrgencyState.NORMAL)

        // 未来日只读：isActionable = false 恒为 NORMAL
        assertThat(TodayGrouping.resolveItemUrgency(overdueItem, isActionable = false, nowTs = now))
            .isEqualTo(DoseUrgencyState.NORMAL)
    }

    @Test
    fun `分组时效优先级 - 逾期优先于临近`() {
        val now = 10_000_000L
        val overdueItem = createItem(slotId = 1L, time = "08:00", ts = now - 1_000L)
        val dueSoonItem = createItem(slotId = 2L, time = "08:05", ts = now + 5 * 60 * 1000L)

        val groups = TodayGrouping.groupPendingItems(listOf(overdueItem, dueSoonItem), isActionable = true, nowTs = now)
        assertThat(groups).hasSize(1)
        assertThat(groups.first().urgency).isEqualTo(DoseUrgencyState.OVERDUE)
    }
}
