package com.mcxiaoke.carromed.ui.screen.cabinet

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.entity.PolicyTimeEntity
import com.mcxiaoke.carromed.core.data.entity.SchedulePolicyEntity
import com.mcxiaoke.carromed.core.data.model.PolicyType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MedicationItemUi(
    val medication: MedicationEntity,
    val policy: SchedulePolicyEntity?,
    val times: List<PolicyTimeEntity>,
    val frequencyDescription: String
)

data class CabinetUiState(
    val selectedTab: Int = 0, // 0: 正在服用, 1: 已停药归档
    val activeList: List<MedicationItemUi> = emptyList(),
    val archivedList: List<MedicationItemUi> = emptyList(),
    val isLoading: Boolean = false
)

class CabinetViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val medDao = db.medicationDao()
    private val policyDao = db.schedulePolicyDao()

    private val _selectedTab = MutableStateFlow(0)

    val uiState: StateFlow<CabinetUiState> = combine(
        _selectedTab,
        medDao.observeActiveMedications(),
        medDao.observeArchivedMedications()
    ) { tab, activeMeds, archivedMeds ->
        val activeItems = activeMeds.map { med -> buildItemUi(med) }
        val archivedItems = archivedMeds.map { med -> buildItemUi(med) }

        CabinetUiState(
            selectedTab = tab,
            activeList = activeItems,
            archivedList = archivedItems,
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = CabinetUiState()
    )

    fun selectTab(index: Int) {
        _selectedTab.value = index
    }

    private suspend fun buildItemUi(med: MedicationEntity): MedicationItemUi {
        val policy = policyDao.getActivePolicyForMedication(med.id)
        val times = if (policy != null) policyDao.getTimesForPolicy(policy.id) else emptyList()

        val timeStr = times.joinToString(", ") { it.timeOfDay }
        val freqDesc = when (policy?.policyType) {
            PolicyType.DAILY -> "每天 ${times.size} 次 · $timeStr"
            PolicyType.INTERVAL -> {
                val intervalText = if (policy.intervalDays <= 2) "隔天" else "每隔 ${policy.intervalDays - 1} 天"
                "$intervalText 1 次 · $timeStr"
            }
            PolicyType.DAYS_OF_WEEK -> "每周 ${policy.daysOfWeek.size} 天 · $timeStr"
            PolicyType.CYCLE -> "周期轮换 (${policy.cycleOnDays}天服/${policy.cycleOffDays}天停) · $timeStr"
            PolicyType.PRN -> "按需服用 (不设闹钟)"
            null -> if (times.isNotEmpty()) "每日 · $timeStr" else "暂无排班"
        }

        return MedicationItemUi(
            medication = med,
            policy = policy,
            times = times,
            frequencyDescription = freqDesc
        )
    }
}
