package com.mcxiaoke.carromed.ui.screen.detail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.InventoryTransactionEntity
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.entity.PolicyTimeEntity
import com.mcxiaoke.carromed.core.data.entity.SchedulePolicyEntity
import com.mcxiaoke.carromed.core.domain.engine.StatsEngine
import com.mcxiaoke.carromed.core.domain.service.DoseTrackingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MedDetailUiState(
    val medication: MedicationEntity? = null,
    val policy: SchedulePolicyEntity? = null,
    val times: List<PolicyTimeEntity> = emptyList(),
    val transactions: List<InventoryTransactionEntity> = emptyList(),
    val runwayDays: Int = Int.MAX_VALUE,
    val isStockAlert: Boolean = false,
    val isLoading: Boolean = true
)

class MedicationDetailViewModel(
    application: Application,
    private val medId: Long
) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val medDao = db.medicationDao()
    private val policyDao = db.schedulePolicyDao()
    private val inventoryDao = db.inventoryTransactionDao()
    private val trackingService = DoseTrackingService(db)

    private val _uiState = MutableStateFlow(MedDetailUiState())
    val uiState: StateFlow<MedDetailUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            val med = medDao.getMedicationById(medId) ?: return@launch
            val policy = policyDao.getActivePolicyForMedication(medId)
            val times = if (policy != null) policyDao.getTimesForPolicy(policy.id) else emptyList()
            val txList = inventoryDao.getTransactionsForMedication(medId)

            val dailyDose = times.sumOf { it.doseAmount.toDouble() }.toFloat()
            val (runway, isAlert) = StatsEngine.calculateStockRunway(
                currentStock = med.currentStock,
                dailyEstimatedConsumption = dailyDose,
                minStockAlert = med.minStockAlert
            )

            _uiState.value = MedDetailUiState(
                medication = med,
                policy = policy,
                times = times,
                transactions = txList,
                runwayDays = runway,
                isStockAlert = isAlert,
                isLoading = false
            )
        }
    }

    fun togglePause() {
        val med = _uiState.value.medication ?: return
        viewModelScope.launch {
            val newPaused = !med.isPaused
            medDao.updatePauseStatus(med.id, newPaused)
            rescheduleAlarms()
            loadData()
        }
    }

    fun toggleArchive() {
        val med = _uiState.value.medication ?: return
        viewModelScope.launch {
            val newArchived = !med.isArchived
            medDao.updateArchiveStatus(med.id, newArchived)
            rescheduleAlarms()
            loadData()
        }
    }

    fun deleteMedication(onDeleted: () -> Unit) {
        val med = _uiState.value.medication ?: return
        viewModelScope.launch {
            medDao.delete(med)
            rescheduleAlarms()
            onDeleted()
        }
    }

    /** 状态变更后按当前库内数据全量重排闹钟 */
    private suspend fun rescheduleAlarms() {
        runCatching {
            com.mcxiaoke.carromed.core.alarm.AlarmReconciler.rescheduleAll(
                getApplication<Application>(), db
            )
        }
    }
}
