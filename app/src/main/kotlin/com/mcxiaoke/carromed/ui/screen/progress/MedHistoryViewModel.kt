package com.mcxiaoke.carromed.ui.screen.progress

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcxiaoke.carromed.R
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.model.RecordStatus
import com.mcxiaoke.carromed.core.domain.model.Dose
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 单个药品的服药历史（UX 方案 §4.2）。
 *
 * 对标 MyTherapy 的「进展 → 点某个药 → 按月分组的列表」。
 * 数据源 `getRecordsForMedication` **本来就存在**，缺的只是页面。
 */
data class MedHistoryItem(
    val recordId: Long,
    /**
     * 排班槽位 id（手动补录的记录为 null）。
     *
     * 点开记录详情页时用它做**入口归一**：有槽位就按槽位打开（形态由槽位状态决定），
     * 没有槽位才按事实打开。少了它就只能按 recordId 打开，
     * 于是同一条记录从今日清单和从流水点开会看到两套形态。
     */
    val slotId: Long?,
    val actualTs: Long,
    val timeLabel: String,
    val doseMilli: Int,
    val status: RecordStatus,
    val isManual: Boolean,
    val isRetrospective: Boolean,
    val note: String?
)

data class MedHistoryMonth(
    val yearMonth: YearMonth,
    /** "2026 年 9 月" */
    val headerLabel: String,
    val items: List<MedHistoryItem>,
    val completedDoseMilli: Int
)

data class MedHistoryUiState(
    val isLoading: Boolean = true,
    val medication: MedicationEntity? = null,
    val months: List<MedHistoryMonth> = emptyList(),
    val error: String? = null
)

class MedHistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val recordDao = db.doseRecordDao()
    private val medDao = db.medicationDao()

    private val _uiState = MutableStateFlow(MedHistoryUiState())
    val uiState: StateFlow<MedHistoryUiState> = _uiState.asStateFlow()

    private var loadedMedId: Long? = null

    private var historyJob: Job? = null

    private val zone: ZoneId = ZoneId.systemDefault()
    private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /**
     * 订阅式加载（osbf P2-3）：`loadedMedId` 一次性守卫保留（同一药品不重复订阅），
     * 但首帧之后还会订阅该药的事实计数与档案 —— 补录、撤销、改剂量之后返回本页，
     * 列表即时重算，而不是停在进页时的旧数据。
     */
    fun load(medId: Long) {
        if (loadedMedId == medId) return
        loadedMedId = medId
        historyJob?.cancel()
        historyJob = viewModelScope.launch {
            loadOnce(medId)
            combine(
                recordDao.observeRecordCountForMedication(medId),
                medDao.observeMedicationById(medId)
            ) { _, _ -> }.drop(1).collect {
                loadOnce(medId)
            }
        }
    }

    private suspend fun loadOnce(medId: Long) {
        val app = getApplication<Application>()
        val med = medDao.getMedicationById(medId)
        if (med == null) {
            _uiState.update { it.copy(isLoading = false, error = app.getString(R.string.mhist_error_med_not_found)) }
            return
        }
        val records = recordDao.getRecordsForMedication(medId)

            val items = records.map { r ->
                MedHistoryItem(
                    recordId = r.id,
                    slotId = r.slotId,
                    actualTs = r.actualTs,
                    timeLabel = timeFmt.format(Instant.ofEpochMilli(r.actualTs).atZone(zone)),
                    doseMilli = r.doseTaken,
                    status = r.status,
                    isManual = r.slotId == null,
                    isRetrospective = r.isRetrospective,
                    note = r.note
                )
            }

            // 按月分组，月内按实际时刻倒序。
            // 分组在 ViewModel 而不在 Composable：O(n) 的确定性计算不该每帧重算。
            val months = items
                .groupBy { YearMonth.from(Instant.ofEpochMilli(it.actualTs).atZone(zone)) }
                .toSortedMap(compareByDescending { it })
                .map { (ym, list) ->
                    MedHistoryMonth(
                        yearMonth = ym,
                        headerLabel = app.getString(R.string.mhist_header_ym, ym.year, ym.monthValue),
                        items = list.sortedByDescending { it.actualTs },
                        completedDoseMilli = list
                            .filter { it.status == RecordStatus.COMPLETED }
                            .sumOf { it.doseMilli }
                    )
                }

            _uiState.update { it.copy(isLoading = false, medication = med, months = months) }
    }
}

/** 供 UI 用：本节已完成剂量的展示文案。 */
fun MedHistoryMonth.totalText(unit: String): String =
    "${Dose(completedDoseMilli).asFloat} $unit"
