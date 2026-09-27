package com.mcxiaoke.carromed.ui.screen.today

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.SampleDataSeeder
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
    val lowStockAlertMed: MedicationEntity? = null,
    val pendingItems: List<DoseSlotItem> = emptyList(),
    val completedItems: List<DoseSlotItem> = emptyList(),
    val isLoading: Boolean = false
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
            // 首次冷启动自动预置样例数据
            SampleDataSeeder.seedIfNeeded(db, LocalDate.now())
        }
    }

    val uiState: StateFlow<TodayUiState> = combine(
        _selectedDate,
        medDao.observeActiveMedications(),
        _selectedDate.flatMapLatest { date ->
            val dateStr = date.format(SlotProjectionEngine.DATE_FORMATTER)
            slotDao.observeSlotsForDate(dateStr)
        }
    ) { selectedDate, medications, slots ->
        val medMap = medications.associateBy { it.id }

        val pending = mutableListOf<DoseSlotItem>()
        val completed = mutableListOf<DoseSlotItem>()

        for (slot in slots) {
            val med = medMap[slot.medicationId]
            val record = if (slot.status == SlotStatus.COMPLETED) {
                recordDao.getRecordBySlotId(slot.id)
            } else null

            val item = DoseSlotItem(slot = slot, medication = med, record = record)
            when (slot.status) {
                SlotStatus.PENDING, SlotStatus.SNOOZED -> pending.add(item)
                SlotStatus.COMPLETED, SlotStatus.SKIPPED -> completed.add(item)
                SlotStatus.EXPIRED -> pending.add(item)
            }
        }

        // 低库存告急检测 (取第一个告急药品显示在横幅)
        val lowStockMed = medications.firstOrNull {
            it.isStockTracked && it.currentStock <= it.minStockAlert && it.minStockAlert > 0f
        }

        // 构造以选中日期为中心的水平 7 天视口
        val weekDates = (-3L..3L).map { selectedDate.plusDays(it) }

        TodayUiState(
            selectedDate = selectedDate,
            weekDates = weekDates,
            lowStockAlertMed = lowStockMed,
            pendingItems = pending.sortedBy { it.slot.scheduledTs },
            completedItems = completed.sortedByDescending { it.slot.actualTakenTs ?: it.slot.scheduledTs },
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
        }
    }

    fun undoDose(slotId: Long) {
        viewModelScope.launch {
            trackingService.undoDose(slotId)
        }
    }

    fun skipDose(slotId: Long) {
        viewModelScope.launch {
            trackingService.skipDose(slotId)
        }
    }
}
