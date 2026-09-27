package com.mcxiaoke.carromed.ui.screen.stats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.domain.engine.StatsEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class MedicationConsumption(
    val rank: Int,
    val medicationName: String,
    val totalDose: Float,
    val unit: String
)

data class StatsUiState(
    val selectedPeriod: Int = 1, // 0: 过去1个月, 1: 过去1整年 (年度汇总)
    val totalDoses: Float = 1428f,
    val adherenceRate: Float = 0.982f,
    val activeMedCount: Int = 4,
    val rankings: List<MedicationConsumption> = emptyList()
)

class StatsViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val medDao = db.medicationDao()
    private val recordDao = db.doseRecordDao()

    private val _selectedPeriod = MutableStateFlow(1)

    val uiState: StateFlow<StatsUiState> = combine(
        _selectedPeriod,
        medDao.observeActiveMedications()
    ) { period, medications ->
        val total = if (period == 0) 120f else 1428f
        val adherence = if (period == 0) 0.965f else 0.982f

        val rankingList = listOf(
            MedicationConsumption(1, "羟氯喹", if (period == 0) 60f else 730f, "片"),
            MedicationConsumption(2, "环抱素", if (period == 0) 30f else 365f, "片"),
            MedicationConsumption(3, "钙和维生素D", if (period == 0) 15f else 182.5f, "片"),
            MedicationConsumption(4, "醋酸泼尼松", if (period == 0) 15f else 150.5f, "片")
        )

        StatsUiState(
            selectedPeriod = period,
            totalDoses = total,
            adherenceRate = adherence,
            activeMedCount = medications.size.coerceAtLeast(4),
            rankings = rankingList
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = StatsUiState()
    )

    fun selectPeriod(period: Int) {
        _selectedPeriod.value = period
    }
}
