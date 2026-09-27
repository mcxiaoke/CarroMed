package com.mcxiaoke.carromed.ui.screen.today

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.DoseRecordEntity
import com.mcxiaoke.carromed.core.data.entity.DoseSlotEntity
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import com.mcxiaoke.carromed.core.domain.engine.SlotProjectionEngine
import com.mcxiaoke.carromed.core.domain.service.DoseTrackingService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class DoseSlotItem(
    val slot: DoseSlotEntity,
    val medication: MedicationEntity?,
    val record: DoseRecordEntity? = null
)

data class TodayUiState(
    val selectedDate: LocalDate = LocalDate.now(),
    val weekDates: List<LocalDate> = emptyList(),
    val lowStockAlertMeds: List<MedicationEntity> = emptyList(),
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

    private val _selectedDate = MutableStateFlow(LocalDate.now())

    init {
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
        medDao.observeActiveMedications(),
        _selectedDate.flatMapLatest { date ->
            val dateStr = date.format(SlotProjectionEngine.DATE_FORMATTER)
            slotDao.observeSlotsForDate(dateStr)
        },
        db.appSettingDao().observeValue(
            com.mcxiaoke.carromed.core.alarm.ReminderSettings.KEY_SNOOZE_MINUTES
        )
    ) { selectedDate, medications, slots, snoozeSetting ->
        val medMap = medications.associateBy { it.id }

        val pending = mutableListOf<DoseSlotItem>()
        val completed = mutableListOf<DoseSlotItem>()
        val skipped = mutableListOf<DoseSlotItem>()

        for (slot in slots) {
            val med = medMap[slot.medicationId]
            val record = when (slot.status) {
                SlotStatus.COMPLETED, SlotStatus.SKIPPED -> recordDao.getRecordBySlotId(slot.id)
                else -> null
            }

            val item = DoseSlotItem(slot = slot, medication = med, record = record)
            when (slot.status) {
                SlotStatus.PENDING, SlotStatus.SNOOZED, SlotStatus.EXPIRED -> pending.add(item)
                SlotStatus.COMPLETED -> completed.add(item)
                SlotStatus.SKIPPED -> skipped.add(item)
            }
        }

        // 低库存告急检测：返回全部告急药品（此前只取第一个，多药告警时会被静默吞掉）
        val lowStock = medications.filter {
            it.isStockTracked && it.minStockAlert > 0f && it.currentStock <= it.minStockAlert
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
            hasAnyMedication = medications.isNotEmpty(),
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
            trackingService.takeDose(slotId)
            com.mcxiaoke.carromed.core.alarm.AlarmScheduler.cancel(getApplication<Application>(), slotId)
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
            trackingService.skipDose(slotId)
            com.mcxiaoke.carromed.core.alarm.AlarmScheduler.cancel(getApplication<Application>(), slotId)
        }
    }

    /** 推迟提醒：置 SNOOZED 并重排该槽位的临时闹钟 */
    fun snoozeDose(slotId: Long, minutes: Int) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            trackingService.snoozeDose(slotId, minutes)
            com.mcxiaoke.carromed.core.alarm.Notifications.cancelDoseNotification(app, slotId)
            val slot = db.doseSlotDao().getSlotById(slotId)
            val triggerAt = slot?.snoozeUntilTs ?: (System.currentTimeMillis() + minutes * 60_000L)
            runCatching {
                com.mcxiaoke.carromed.core.alarm.AlarmScheduler.schedule(app, slotId, triggerAt)
            }
        }
    }
}
