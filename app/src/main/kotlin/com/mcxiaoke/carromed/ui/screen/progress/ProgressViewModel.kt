package com.mcxiaoke.carromed.ui.screen.progress

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.DoseRecordEntity
import com.mcxiaoke.carromed.core.data.entity.DoseSlotEntity
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import com.mcxiaoke.carromed.core.domain.engine.SlotProjectionEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

data class DayAdherence(
    val date: LocalDate,
    val dayLabel: String,
    val status: SlotStatus? // null if not scheduled
)

data class MedMatrixItem(
    val medication: MedicationEntity,
    val completionRate: Int,
    val days: List<DayAdherence>
)

data class TimelineItem(
    val slot: DoseSlotEntity,
    val medication: MedicationEntity?,
    val record: DoseRecordEntity?
)

data class ProgressUiState(
    val selectedTab: Int = 0, // 0: 7天打卡矩阵, 1: 时间轴服药流水
    val matrixItems: List<MedMatrixItem> = emptyList(),
    val todayTimeline: List<TimelineItem> = emptyList(),
    val isLoading: Boolean = false
)

class ProgressViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val medDao = db.medicationDao()
    private val slotDao = db.doseSlotDao()
    private val recordDao = db.doseRecordDao()

    private val _selectedTab = MutableStateFlow(0)

    val uiState: StateFlow<ProgressUiState> = combine(
        _selectedTab,
        medDao.observeActiveMedications(),
        slotDao.observeSlotsForDate(LocalDate.now().format(SlotProjectionEngine.DATE_FORMATTER))
    ) { tab, medications, todaySlots ->
        val medMap = medications.associateBy { it.id }

        // 构造近 7 天打卡矩阵
        val today = LocalDate.now()
        val past7Days = (6 downTo 0).map { today.minusDays(it.toLong()) }
        val dayLabels = listOf("周一", "周二", "周三", "周四", "周五", "周六", "今日")

        val matrixItems = medications.map { med ->
            val dayAdherences = past7Days.mapIndexed { idx, d ->
                val label = if (idx == 6) "今日" else dayLabels[(d.dayOfWeek.value - 1) % 7]
                // 默认今日展示今日槽位状态，历史展示完成状态
                val dayStatus = if (idx == 6) {
                    val todaySlot = todaySlots.firstOrNull { it.medicationId == med.id }
                    todaySlot?.status ?: SlotStatus.COMPLETED
                } else {
                    SlotStatus.COMPLETED
                }
                DayAdherence(date = d, dayLabel = label, status = dayStatus)
            }
            val completedDays = dayAdherences.count { it.status == SlotStatus.COMPLETED }
            val rate = if (dayAdherences.isNotEmpty()) (completedDays * 100) / dayAdherences.size else 100

            MedMatrixItem(medication = med, completionRate = rate, days = dayAdherences)
        }

        // 构造今日时间轴流水
        val timeline = todaySlots.map { slot ->
            val med = medMap[slot.medicationId]
            val record = if (slot.status == SlotStatus.COMPLETED) recordDao.getRecordBySlotId(slot.id) else null
            TimelineItem(slot = slot, medication = med, record = record)
        }.sortedBy { it.slot.scheduledTs }

        ProgressUiState(
            selectedTab = tab,
            matrixItems = matrixItems,
            todayTimeline = timeline,
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ProgressUiState()
    )

    fun selectTab(tab: Int) {
        _selectedTab.value = tab
    }
}
