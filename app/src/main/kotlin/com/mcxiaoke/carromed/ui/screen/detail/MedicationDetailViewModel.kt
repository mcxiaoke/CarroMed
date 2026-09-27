package com.mcxiaoke.carromed.ui.screen.detail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.DoseRecordEntity
import com.mcxiaoke.carromed.core.data.entity.InventoryTransactionEntity
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.entity.PolicyTimeEntity
import com.mcxiaoke.carromed.core.data.entity.SchedulePolicyEntity
import com.mcxiaoke.carromed.core.data.model.PolicyType
import com.mcxiaoke.carromed.core.domain.engine.SlotProjectionEngine
import com.mcxiaoke.carromed.core.domain.engine.StatsEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

data class MedDetailUiState(
    val medication: MedicationEntity? = null,
    val policy: SchedulePolicyEntity? = null,
    val times: List<PolicyTimeEntity> = emptyList(),
    val transactions: List<InventoryTransactionEntity> = emptyList(),
    val runwayDays: Int = Int.MAX_VALUE,
    val isStockAlert: Boolean = false,
    val isLoading: Boolean = true,
    val adherenceRate: Float = 0f,
    val adherenceCompleted: Int = 0,
    val adherenceDecided: Int = 0,
    val recentRecords: List<DoseRecordEntity> = emptyList(),
    val doseSum: Float = 0f,
    val error: String? = null
)

class MedicationDetailViewModel(
    application: Application,
    private val medId: Long
) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val medDao = db.medicationDao()
    private val policyDao = db.schedulePolicyDao()
    private val inventoryDao = db.inventoryTransactionDao()
    private val slotDao = db.doseSlotDao()
    private val recordDao = db.doseRecordDao()

    private val _uiState = MutableStateFlow(MedDetailUiState())
    val uiState: StateFlow<MedDetailUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            val med = medDao.getMedicationById(medId)
            if (med == null) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = "药品不存在或已被删除")
                return@launch
            }
            val policy = policyDao.getActivePolicyForMedication(medId)
            val times = if (policy != null) policyDao.getTimesForPolicy(policy.id) else emptyList()
            val txList = inventoryDao.getTransactionsForMedication(medId)

            // 近 30 天依从率 (按计划时间归属，与进展页 / 统计页同口径)
            val today = LocalDate.now()
            val startStr = today.minusDays(29).format(SlotProjectionEngine.DATE_FORMATTER)
            val endStr = today.format(SlotProjectionEngine.DATE_FORMATTER)
            val slotRows = slotDao.getSlotStatusCounts(startStr, endStr)
                .filter { it.medicationId == medId }
            val byDate = StatsEngine.aggregateBreakdowns(slotRows)[medId].orEmpty()
            val totals = StatsEngine.sumBreakdowns(
                byDate,
                (0L..29L).map { today.minusDays(it).format(SlotProjectionEngine.DATE_FORMATTER) }
            )

            // 近 30 天实际消耗与最近服药记录
            val startTs = today.minusDays(29).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val endTs = System.currentTimeMillis()
            val doseSum = recordDao.getSumDoseTakenForMedication(medId, startTs, endTs) ?: 0f
            val recent = recordDao.getRecordsForMedication(medId).take(RECENT_RECORD_LIMIT)

            val dailyDose = times.sumOf { it.doseAmount.toDouble() }.toFloat()
            val (runway, isAlert) = StatsEngine.calculateStockRunwayBySchedule(
                currentStock = med.currentStock,
                dosesPerScheduledDay = dailyDose,
                scheduledDosesPerWeek = scheduledDosesPerWeek(policy, times.size)
            )

            _uiState.value = MedDetailUiState(
                medication = med,
                policy = policy,
                times = times,
                transactions = txList,
                runwayDays = runway,
                isStockAlert = isAlert,
                isLoading = false,
                adherenceRate = StatsEngine.adherenceOf(totals),
                adherenceCompleted = totals.completed,
                adherenceDecided = totals.decided,
                recentRecords = recent,
                doseSum = doseSum
            )
        }
    }

    /** 每周实际排班天数，用于把日均消耗折算到"日历日"而非"服药日" */
    private fun scheduledDosesPerWeek(policy: SchedulePolicyEntity?, timesCount: Int): Int =
        when (policy?.policyType) {
            PolicyType.DAILY -> 7
            PolicyType.INTERVAL -> {
                val n = (policy.intervalDays ?: 2).coerceAtLeast(1)
                Math.round(7.0 / n).toInt()
            }
            PolicyType.DAYS_OF_WEEK -> policy.daysOfWeek.size
            PolicyType.CYCLE -> 5
            PolicyType.PRN, null -> 0
        }.let { days -> if (timesCount == 0) 0 else days.coerceAtLeast(0) }

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

    companion object {
        const val RECENT_RECORD_LIMIT = 20
    }
}
