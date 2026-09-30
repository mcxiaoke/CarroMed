package com.mcxiaoke.carromed.ui.screen.record

import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.data.entity.DoseRecordEntity
import com.mcxiaoke.carromed.core.data.entity.DoseSlotEntity
import com.mcxiaoke.carromed.core.data.model.RecordStatus
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 记录详情页的「状态 × 时间 → 可见操作」矩阵。
 *
 * ## 为什么这些断言值得单独一个文件
 *
 * 矩阵是这一轮改造里**最容易静默漂移**的东西：按钮少一个是"功能缺失"（看得见），
 * 按钮多一个却是"点下去必然失败"（看不见）—— 比如给逾期槽位排一个「推迟」，
 * 而 `DoseSlotDao.snoozeSlot` 的守卫里根本没有 `EXPIRED`。
 *
 * 所以每个 `canXxx` 都要有一条断言钉住，包括**不该出现**的那些。
 */
class DoseRecordDetailStateTest {

    /** 相对今天第 N 天的某个时刻 */
    private fun tsOn(daysFromToday: Long, hour: Int = 9): Long =
        LocalDate.now().plusDays(daysFromToday)
            .atTime(hour, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private fun slot(status: SlotStatus, scheduledDate: String = LocalDate.now().toString()) = DoseSlotEntity(
        medicationId = 1L,
        policyId = 0L,
        scheduledDate = scheduledDate,
        scheduledTime = "08:00",
        scheduledTs = tsOn(0, 8),
        doseAmount = 1000,
        status = status
    )

    private fun record(
        actualTs: Long,
        status: RecordStatus = RecordStatus.COMPLETED,
        slotId: Long? = 1L
    ) = DoseRecordEntity(
        slotId = slotId,
        medicationId = 1L,
        actualTs = actualTs,
        doseTaken = if (status == RecordStatus.SKIPPED) 0 else 1000,
        status = status
    )

    private fun stateOf(
        status: SlotStatus?,
        actualTs: Long? = null,
        isManual: Boolean = false,
        isActionable: Boolean = true,
        /**
         * 槽位的计划日。默认「今天」。
         *
         * ⚠️ 断言"不能撤销"的用例必须让计划日与事实日**对得上**（见 [isContradictoryRecord]）：
         * 计划日写今天、事实写三天前，那是**坏数据**形态，本来就该可撤销 ——
         * 用它来断言"较早的记录不可撤销"测的是一种真实路径写不出来的行。
         */
        scheduledDate: String = LocalDate.now().toString()
    ): DoseEntryUiState = DoseEntryUiState(
        isLoading = false,
        slot = if (isManual) null else status?.let { slot(it, scheduledDate) },
        record = actualTs?.let { record(it, slotId = if (isManual) null else 1L) },
        isActionable = isActionable
    )

    // ==================== 待服 / 已推迟 / 逾期 ====================

    @Test
    fun `待服 可确认可推迟可跳过 但没有撤销`() {
        val s = stateOf(SlotStatus.PENDING)
        assertThat(s.canConfirm).isTrue()
        assertThat(s.canSnooze).isTrue()
        assertThat(s.canSkip).isTrue()
        // 还没有结论 ⇒ 没有任何"撤销"的语义
        assertThat(s.canUndo).isFalse()
        assertThat(s.confirmNeedsRestate).isFalse()
        assertThat(s.skipNeedsRestate).isFalse()
    }

    @Test
    fun `已推迟 与待服同一套操作`() {
        val s = stateOf(SlotStatus.SNOOZED)
        assertThat(s.canConfirm).isTrue()
        assertThat(s.canSnooze).isTrue()
        assertThat(s.canSkip).isTrue()
        assertThat(s.canUndo).isFalse()
    }

    /**
     * ⭐ 逾期**不给**「推迟」。
     *
     * `snoozeSlot` 的守卫是 `status IN ('PENDING','SNOOZED')`，对 `EXPIRED` 必然返回 0。
     * 按钮存在却永远失败，就是给用户一个假的承诺。
     */
    @Test
    fun `逾期 可确认可跳过 但不可推迟也不可撤销`() {
        val s = stateOf(SlotStatus.EXPIRED)
        assertThat(s.canConfirm).isTrue()
        assertThat(s.canSkip).isTrue()
        assertThat(s.canSnooze).isFalse()
        assertThat(s.canUndo).isFalse()
    }

    // ==================== 已服 ====================

    @Test
    fun `已服当天 可跳过改判且可撤销`() {
        val s = stateOf(SlotStatus.COMPLETED, actualTs = tsOn(0))
        assertThat(s.canSkip).isTrue()
        assertThat(s.skipNeedsRestate).isTrue()
        assertThat(s.canUndo).isTrue()
        // 已服不显示「确认」（当前状态不放按钮）
        assertThat(s.canConfirm).isFalse()
        assertThat(s.canSnooze).isFalse()
    }

    @Test
    fun `已服非当天 只能跳过 不能撤销`() {
        // 计划日与事实日同为昨天 —— 这是"较早的正常记录"的真实形态
        val yesterday = LocalDate.now().minusDays(1).toString()
        val s = stateOf(SlotStatus.COMPLETED, actualTs = tsOn(-1), scheduledDate = yesterday)
        assertThat(s.canSkip).isTrue()
        assertThat(s.canUndo).isFalse()
        assertThat(s.isToday).isFalse()
    }

    @Test
    fun `已服较早 同样不能撤销`() {
        for (d in listOf(-2L, -3L, -30L)) {
            val day = LocalDate.now().plusDays(d).toString()
            assertThat(stateOf(SlotStatus.COMPLETED, actualTs = tsOn(d), scheduledDate = day).canUndo)
                .isFalse()
        }
    }

    /**
     * ⭐ 坏数据逃生口：事实日**早于**计划日 ⇒ 视为坏数据，允许撤销。
     *
     * 这正是「对未来的槽位打卡」留下的行（计划 9-30、事实 9-29），
     * 而"撤销仅当天"的判据看的是 `actualTs` —— 到了 9-30 它就永远打不开了，
     * 用户被自己造出来的垃圾锁死。
     */
    @Test
    fun `事实早于计划日的坏记录 即使不是当天也可撤销`() {
        val today = LocalDate.now()
        val s = stateOf(
            SlotStatus.COMPLETED,
            actualTs = tsOn(-1),
            scheduledDate = today.toString()
        )
        assertThat(s.isContradictoryRecord).isTrue()
        assertThat(s.isToday).isFalse()
        assertThat(s.canUndo).isTrue()
    }

    @Test
    fun `事实晚于计划日的正常补记 仍然按当天判据`() {
        // 昨天该吃、今天补记：事实日（今天）晚于计划日（昨天）⇒ 不是坏数据，
        // 且落在"仅当天"的窗口内 ⇒ 可撤销
        val yesterday = LocalDate.now().minusDays(1).toString()
        val s = stateOf(SlotStatus.COMPLETED, actualTs = tsOn(0), scheduledDate = yesterday)
        assertThat(s.isContradictoryRecord).isFalse()
        assertThat(s.canUndo).isTrue()
    }

    // ==================== 已跳过 ====================

    @Test
    fun `已跳过当天 可确认改判且可撤销`() {
        val s = stateOf(SlotStatus.SKIPPED, actualTs = tsOn(0))
        assertThat(s.canConfirm).isTrue()
        assertThat(s.confirmNeedsRestate).isTrue()
        assertThat(s.canUndo).isTrue()
        // 已跳过不显示「跳过」（当前状态不放按钮）
        assertThat(s.canSkip).isFalse()
        assertThat(s.canSnooze).isFalse()
    }

    @Test
    fun `已跳过非当天 只能确认 不能撤销`() {
        val yesterday = LocalDate.now().minusDays(1).toString()
        val s = stateOf(SlotStatus.SKIPPED, actualTs = tsOn(-1), scheduledDate = yesterday)
        assertThat(s.canConfirm).isTrue()
        assertThat(s.canUndo).isFalse()
    }

    // ==================== 手动补录（无槽位） ====================

    @Test
    fun `手动补录 窗口内可撤销且可改剂量与时间`() {
        val s = stateOf(status = null, actualTs = tsOn(-1), isManual = true)
        assertThat(s.isManual).isTrue()
        assertThat(s.canUndo).isTrue()
        assertThat(s.canEditDose).isTrue()
        assertThat(s.canEditTime).isTrue()
        // 手动补录没有排班，就没有"确认/跳过/推迟"这些排班语义
        assertThat(s.canConfirm).isFalse()
        assertThat(s.canSkip).isFalse()
        assertThat(s.canSnooze).isFalse()
    }

    /**
     * ⭐ 手动补录的撤销放宽到 7 天（与补录窗口同源），与计划内记录的"仅当天"**故意不同**：
     * 它只是把事实标 `REVERTED`，不会把任何条目退回待服，
     * 因此不产生"计划时间已过、对账立刻又判逾期"的永远清不掉的待办。
     */
    @Test
    fun `手动补录超过 7 天只读`() {
        val s = stateOf(status = null, actualTs = tsOn(-8), isManual = true)
        assertThat(s.canUndo).isFalse()
        assertThat(s.canEditDose).isFalse()
        assertThat(s.canEditTime).isFalse()
        // 备注仍可改：它不改变"吃了多少"，也不改变结论
        assertThat(s.canEditNote).isTrue()
    }

    /** 窗口边界内侧：第 7 天仍可撤销（与补录窗口同边界） */
    @Test
    fun `手动补录第 7 天仍可撤销`() {
        val s = stateOf(status = null, actualTs = tsOn(-7), isManual = true)
        assertThat(s.canUndo).isTrue()
    }

    @Test
    fun `手动补录当天 可撤销`() {
        val s = stateOf(status = null, actualTs = tsOn(0), isManual = true)
        assertThat(s.canUndo).isTrue()
    }

    // ==================== 未来槽位：三个动作全不渲染 ====================

    /**
     * ⭐ `isActionable = false`（计划日在今天之后）时，确认 / 推迟 / 跳过**一个都不渲染**。
     *
     * 渲染了就是"点下去必然失败"的假承诺 —— 服务层的 SQL 守卫会拒，
     * 用户只会看到一句"这条记录的状态已变化"。
     */
    @Test
    fun `未来槽位 三个动作一个都不渲染`() {
        for (status in listOf(SlotStatus.PENDING, SlotStatus.SNOOZED, SlotStatus.EXPIRED)) {
            val s = stateOf(status, isActionable = false)
            assertThat(s.canConfirm).isFalse()
            assertThat(s.canSnooze).isFalse()
            assertThat(s.canSkip).isFalse()
            assertThat(s.hasAnyAction).isFalse()
        }
    }

    @Test
    fun `未来槽位 已服与已跳过形态同样不给改判入口`() {
        val future = LocalDate.now().plusDays(1).toString()
        for (status in listOf(SlotStatus.COMPLETED, SlotStatus.SKIPPED)) {
            val s = stateOf(status, actualTs = tsOn(0), isActionable = false, scheduledDate = future)
            assertThat(s.canConfirm).isFalse()
            assertThat(s.canSkip).isFalse()
            assertThat(s.canSnooze).isFalse()
        }
    }

    @Test
    fun `未来槽位的坏记录仍然能撤销（修复通道不能被误伤）`() {
        val future = LocalDate.now().plusDays(1).toString()
        val s = stateOf(SlotStatus.COMPLETED, actualTs = tsOn(0), isActionable = false, scheduledDate = future)
        assertThat(s.canUndo).isTrue()
        assertThat(s.isContradictoryRecord).isTrue()
    }

    /** 防"修过头"：同一条槽位只要计划日不晚于今天，动作照旧全给 */
    @Test
    fun `今天与过去的槽位 动作照旧`() {
        for (day in listOf(LocalDate.now().toString(), LocalDate.now().minusDays(1).toString())) {
            val s = stateOf(SlotStatus.PENDING, isActionable = true, scheduledDate = day)
            assertThat(s.canConfirm).isTrue()
            assertThat(s.canSnooze).isTrue()
            assertThat(s.canSkip).isTrue()
        }
    }

    // ==================== 其它 ====================

    @Test
    fun `待服形态的备注随确认一起落库`() {
        assertThat(stateOf(SlotStatus.PENDING).noteJoinsConfirm).isTrue()
        // 已经有事实的形态，备注是独立保存的
        assertThat(stateOf(SlotStatus.COMPLETED, actualTs = tsOn(0)).noteJoinsConfirm).isFalse()
    }

    @Test
    fun `待服形态没有任何状态动作之外的东西时 hasAnyAction 为真`() {
        assertThat(stateOf(SlotStatus.PENDING).hasAnyAction).isTrue()
        // 超窗的手动补录：四个动作全不可用 ⇒ 整块不渲染（窗口 7 天，取 8 天前）
        assertThat(stateOf(status = null, actualTs = tsOn(-8), isManual = true).hasAnyAction)
            .isFalse()
    }

    @Test
    fun `未来时间戳不算今天 也不可撤销`() {
        // `isSameLocalDay` 只看自然日，所以"明天"不是今天；
        // 而 2 天窗口用 age in 0..2，负数（未来）会落到窗外
        val future = tsOn(1)
        assertThat(isSameLocalDay(future, System.currentTimeMillis())).isFalse()
        assertThat(isWithinEditWindow(future)).isFalse()
    }
}
