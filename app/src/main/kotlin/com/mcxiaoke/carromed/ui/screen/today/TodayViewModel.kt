package com.mcxiaoke.carromed.ui.screen.today

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.DoseRecordEntity
import com.mcxiaoke.carromed.core.data.entity.DoseSlotEntity
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.model.MedicationOverview
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import com.mcxiaoke.carromed.core.domain.CurrentDateHolder
import com.mcxiaoke.carromed.core.domain.engine.SlotProjectionEngine
import com.mcxiaoke.carromed.core.domain.engine.StatsEngine
import com.mcxiaoke.carromed.core.domain.service.DoseTrackingService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 一条待服 / 已服 / 已跳过槽位的展示模型。
 *
 * ## 为什么带 `overview` 而不是 `medication` + 各自散落的 `stock`
 *
 * 库存余额与预警线都是**整数毫单位**（D-7），只有 `MedicationOverview` 上那两个
 * Float 代理是可直接用于渲染的展示值。若这里放实体 `MedicationEntity` 再单挂一个
 * `stock: Float`，UI 里就会出现 `item.medication.minStockAlert`（Int 毫单位）
 * 与 `item.stock`（Float 展示值）混着比大小 —— `50f <= 15000` 恒真，
 * 结果是**每个药都误报低库存**。这类量纲错误编译器抓不到，只能靠类型设计挡住。
 *
 * 所以这里只暴露一个入口：[medication]（实体，仅用于纯展示字段）与
 * [stock] / [minStockAlert]（均已换算为展示值）。
 */
data class DoseSlotItem(
    val slot: DoseSlotEntity,
    val medication: MedicationEntity?,
    val record: DoseRecordEntity? = null,
    /** 该药品的台账账面余额（**展示值**，可为负）；未开启库存追踪时为 null */
    val stock: Float? = null,
    /** 低库存预警线（**展示值**）。0 表示关闭低库存告警。 */
    val minStockAlert: Float = 0f
)

data class TodayUiState(
    val selectedDate: LocalDate = LocalDate.now(),
    val weekDates: List<LocalDate> = emptyList(),
    /** 低库存告急药品（含台账聚合出的账面余额，可能为负 —— 见 FINAL-PRODUCT D-9） */
    val lowStockAlertMeds: List<MedicationOverview> = emptyList(),
    val pendingItems: List<DoseSlotItem> = emptyList(),
    val skippedItems: List<DoseSlotItem> = emptyList(),
    val completedItems: List<DoseSlotItem> = emptyList(),
    val globalSnoozeMinutes: Int = 30,
    /** 药箱里是否已有任何在服药品。用于区分"全新用户"与"这一天恰好没排班" */
    val hasAnyMedication: Boolean = false,
    val isLoading: Boolean = true
)

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val trackingService = DoseTrackingService(db)
    private val slotDao = db.doseSlotDao()
    private val medDao = db.medicationDao()
    private val recordDao = db.doseRecordDao()

    /**
     * 用户当前正在查看的日期。
     *
     * ⚠️ **不是** [CurrentDateHolder.today] 的别名，而是**用户的选择**。
     * 两者必须分开：用户可以翻到昨天、前天看历史（那正是本功能），
     * 跨过午夜时不该把他的选择强制拉回今天。
     */
    private val _selectedDate = MutableStateFlow(LocalDate.now())

    init {
        // 跨午夜时把选择**夹回**今天（M3-2）。不是"重置"：若用户正在看历史就保持原样，
        // 只有选中日期已经**落在未来**（只有跨日才可能发生）时才拉回今天。
        // 直接重置会把正在看历史的用户莫名其妙地弹回今天。
        viewModelScope.launch {
            CurrentDateHolder.today.collect { realToday ->
                val current = _selectedDate.value
                if (current > realToday) _selectedDate.value = realToday
            }
        }

        viewModelScope.launch {
            // 冷启动按当前策略补齐未来排班 + 重排闹钟。
            // 刻意**不播种任何演示数据**：首次启动必须是干净空库，
            // 否则用户会看到凭空出现的"环孢素 / 羟氯喹"等不属于自己的服药记录，
            // 进而污染依从率与库存统计。演示数据由 debug 源集的
            // `DevSampleDataSeeder` 手动触发，不在冷启动路径上。
            runCatching {
                com.mcxiaoke.carromed.core.alarm.AlarmReconciler
                    .rescheduleAll(getApplication<Application>(), db)
            }
        }
    }

    val uiState: StateFlow<TodayUiState> = combine(
        _selectedDate,
        // "今天"参与 combine（M3-2）：跨午夜后即使 `_selectedDate` 被夹回今天，
        // 页面标题、日期选择器、告警文案都需要跟着重算。
        // 少了这个源，跨夜后标题仍显示昨天的日期字符串。
        CurrentDateHolder.today,
        medDao.observeActiveOverviews(),
        _selectedDate.flatMapLatest { date ->
            val dateStr = date.format(SlotProjectionEngine.DATE_FORMATTER)
            slotDao.observeSlotsForDate(dateStr)
        },
        db.appSettingDao().observeValue(
            com.mcxiaoke.carromed.core.alarm.ReminderSettings.KEY_SNOOZE_MINUTES
        )
    ) { selectedDate, _, overviews, slots, snoozeSetting ->
        val medMap = overviews.associateBy { it.id }

        val pending = mutableListOf<DoseSlotItem>()
        val completed = mutableListOf<DoseSlotItem>()
        val skipped = mutableListOf<DoseSlotItem>()

        // 一次批量取回已完成/已跳过槽位对应的服药事实，避免循环内 N+1 查询
        val decidedSlotIds = slots
            .filter { it.status == SlotStatus.COMPLETED || it.status == SlotStatus.SKIPPED }
            .map { it.id }
        val recordsBySlot = recordDao.getCompletedRecordsForSlots(decidedSlotIds).associateBy { it.slotId }

        for (slot in slots) {
            val overview = medMap[slot.medicationId]
            val med = overview?.medication
            val record = when (slot.status) {
                SlotStatus.COMPLETED -> recordsBySlot[slot.id]
                SlotStatus.SKIPPED -> recordDao.getRecordBySlotId(slot.id)
                else -> null
            }

            val item = DoseSlotItem(
                slot = slot,
                medication = med,
                record = record,
                stock = if (med?.isStockTracked == true) overview?.stock else null,
                // 走 Overview 的 Float 代理，绝不直接读实体的毫单位 Int
                minStockAlert = overview?.minStockAlert ?: 0f
            )
            when (slot.status) {
                SlotStatus.PENDING, SlotStatus.SNOOZED, SlotStatus.EXPIRED -> pending.add(item)
                SlotStatus.COMPLETED -> completed.add(item)
                SlotStatus.SKIPPED -> skipped.add(item)
            }
        }

        // 低库存告急检测：返回全部告急药品（此前只取第一个，多药告警时会被静默吞掉）
        // 判据走 StatsEngine 的唯一实现，避免与药箱/详情/库存/补药页漂移（M4-1）
        val lowStock = overviews.filter {
            StatsEngine.isLowStock(it.isStockTracked, it.stock, it.minStockAlert)
        }

        val weekDates = (-3L..3L).map { selectedDate.plusDays(it) }

        TodayUiState(
            selectedDate = selectedDate,
            weekDates = weekDates,
            lowStockAlertMeds = lowStock,
            pendingItems = pending.sortedBy { it.slot.scheduledTs },
            skippedItems = skipped.sortedBy { it.slot.scheduledTs },
            completedItems = completed.sortedByDescending { it.slot.actualTakenTs ?: it.slot.scheduledTs },
            globalSnoozeMinutes = snoozeSetting?.toIntOrNull()
                ?: com.mcxiaoke.carromed.core.alarm.ReminderSettings.DEFAULT_SNOOZE_MINUTES,
            hasAnyMedication = overviews.isNotEmpty(),
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = TodayUiState(
            selectedDate = LocalDate.now(),
            weekDates = (-3L..3L).map { LocalDate.now().plusDays(it) }
        )
    )

    fun selectDate(date: LocalDate) {
        _selectedDate.value = date
    }

    fun takeDose(slotId: Long) {
        viewModelScope.launch {
            val ok = trackingService.takeDose(slotId)
            cancelAlarmsOf(slotId)
            if (!ok) emitEvent("该服药记录已处理过，未重复扣减库存")
        }
    }

    fun undoDose(slotId: Long) {
        viewModelScope.launch {
            trackingService.undoDose(slotId)
            // 撤销后槽位回到待服，重新对账恢复其未来闹钟
            runCatching {
                com.mcxiaoke.carromed.core.alarm.AlarmReconciler.rescheduleAll(getApplication<Application>(), db)
            }
        }
    }

    fun skipDose(slotId: Long) {
        viewModelScope.launch {
            val ok = trackingService.skipDose(slotId)
            cancelAlarmsOf(slotId)
            if (!ok) emitEvent("该服药记录已处理过")
        }
    }

    /**
     * 取消某槽位的**全部种类**闹钟。
     *
     * 闹钟身份是内容寻址的（medId/date/time/kind），不再依赖 `slot.id` 算术编码，
     * 所以取消必须带齐定位信息。打卡 / 跳过后这次提醒就终结了，三种种类一次清干净。
     */
    private suspend fun cancelAlarmsOf(slotId: Long) {
        val app = getApplication<Application>()
        val slot = db.doseSlotDao().getSlotById(slotId) ?: return
        com.mcxiaoke.carromed.core.alarm.AlarmScheduler.cancelAll(
            app, slot.medicationId, slot.scheduledDate, slot.scheduledTime, slot.id
        )
    }

    /** 推迟提醒：置 SNOOZED 并重排该槽位的临时闹钟 */
    fun snoozeDose(slotId: Long, minutes: Int) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val ok = trackingService.snoozeDose(slotId, minutes)
            if (!ok) {
                emitEvent("该服药记录已处理过，无法推迟")
                return@launch
            }
            com.mcxiaoke.carromed.core.alarm.Notifications.cancelDoseNotification(app, slotId)
            val slot = db.doseSlotDao().getSlotById(slotId)
            if (slot == null) {
                emitEvent("该服药记录已不存在")
                return@launch
            }
            val triggerAt = slot.snoozeUntilTs ?: (System.currentTimeMillis() + minutes * 60_000L)
            // 原定准点/提前提醒已无意义：清掉后只留一个 SNOOZE 种类
            com.mcxiaoke.carromed.core.alarm.AlarmScheduler.cancelAll(
                app, slot.medicationId, slot.scheduledDate, slot.scheduledTime, slot.id,
                kinds = listOf(
                    com.mcxiaoke.carromed.core.alarm.AlarmScheduler.Kind.MAIN,
                    com.mcxiaoke.carromed.core.alarm.AlarmScheduler.Kind.ADVANCE
                )
            )
            runCatching {
                com.mcxiaoke.carromed.core.alarm.AlarmScheduler.schedule(
                    app, slot, triggerAt, com.mcxiaoke.carromed.core.alarm.AlarmScheduler.Kind.SNOOZE
                )
            }.onFailure { emitEvent("推迟失败：${it.message}") }
        }
    }

    // ---------------- 一次性提示事件 ----------------

    /**
     * 页面反馈通道。
     *
     * 原先 `takeDose` / `skipDose` 的返回值被直接丢弃，操作失败时**没有任何提示** ——
     * 用户点 ✓ 后卡片不动，既不知道成功也不知道失败。
     */
    private val _events = Channel<String>(Channel.BUFFERED)
    val events: Flow<String> = _events.receiveAsFlow()

    private fun emitEvent(message: String) {
        _events.trySend(message)
    }
}
