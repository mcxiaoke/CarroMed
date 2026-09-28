package com.mcxiaoke.carromed.ui.screen.manual

import android.app.Application
import com.mcxiaoke.carromed.core.domain.model.Dose
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.domain.service.DoseTrackingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ManualDoseUiState(
    val medications: List<MedicationEntity> = emptyList(),
    /** 药品 id -> 台账账面余额，供下拉列表逐项显示（可为负） */
    val stockByMedicationId: Map<Long, Float> = emptyMap(),
    val selectedMedication: MedicationEntity? = null,
    /** 选中药品的台账账面余额（可为负，见 FINAL-PRODUCT D-9） */
    val selectedStock: Float = 0f,
    val doseAmount: String = "1",
    val actualDateTime: LocalDateTime = LocalDateTime.now(),
    val note: String = "",
    val deductStock: Boolean = true,
    val isSaving: Boolean = false,
    val error: String? = null
)

/**
 * 手动补录服药
 *
 * 修复记录：
 * 1. 原实现**完全没有修改服药时刻的入口** —— 界面写着"可指定过去时间"，
 *    但时间字段是只读文本框，ViewModel 也没有任何 setter，用户根本无法补录历史服药。
 *    现在接上 `DatePickerDialog` + `TimePickerDialog`。
 * 2. 原实现的"自动扣减对应库存台账"开关**从未传给领域层**，永远生效。
 *    现在 `deductStock` 真正透传到 `DoseTrackingService.logManualDose`。
 */
class ManualDoseViewModel(
    application: Application,
    private val initialMedId: Long?
) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val medDao = db.medicationDao()
    private val trackingService = DoseTrackingService(db)

    private val _uiState = MutableStateFlow(ManualDoseUiState())
    val uiState: StateFlow<ManualDoseUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val meds = medDao.getActiveOverviews()
            val initialSelected = if (initialMedId != null) {
                meds.firstOrNull { it.id == initialMedId } ?: meds.firstOrNull()
            } else {
                meds.firstOrNull()
            }
            _uiState.value = _uiState.value.copy(
                medications = meds.map { it.medication },
                stockByMedicationId = meds.associate { it.id to it.stock },
                selectedMedication = initialSelected?.medication,
                selectedStock = initialSelected?.stock ?: 0f,
                doseAmount = trimFloat(Dose(initialSelected?.medication?.defaultDose ?: 1000).asFloat)
            )
        }
    }

    fun selectMedication(id: Long) {
        viewModelScope.launch {
            val overview = medDao.getOverviewById(id) ?: return@launch
            _uiState.value = _uiState.value.copy(
                selectedMedication = overview.medication,
                selectedStock = overview.stock,
                doseAmount = trimFloat(Dose(overview.medication.defaultDose).asFloat)
            )
        }
    }

    fun onDoseAmountChange(amount: String) {
        _uiState.value = _uiState.value.copy(
            doseAmount = amount.filter { it.isDigit() || it == '.' },
            error = null
        )
    }

    fun onNoteChange(note: String) {
        _uiState.value = _uiState.value.copy(note = note)
    }

    fun onDeductStockChange(deduct: Boolean) {
        _uiState.value = _uiState.value.copy(deductStock = deduct)
    }

    /** 指定服药时刻 (系统日期选择器回调) */
    fun onActualDateChange(year: Int, month: Int, day: Int) {
        val cur = _uiState.value.actualDateTime
        _uiState.value = _uiState.value.copy(
            actualDateTime = LocalDateTime.of(year, month + 1, day, cur.hour, cur.minute),
            error = null
        )
    }

    /** 指定服药时刻 (系统时间选择器回调) */
    fun onActualTimeChange(hour: Int, minute: Int) {
        val cur = _uiState.value.actualDateTime
        _uiState.value = _uiState.value.copy(
            actualDateTime = LocalDateTime.of(cur.year, cur.monthValue, cur.dayOfMonth, hour, minute),
            error = null
        )
    }

    fun onActualDateTimeChange(dt: LocalDateTime) {
        _uiState.value = _uiState.value.copy(actualDateTime = dt, error = null)
    }

    /** 常用快捷：昨天同一时刻 / 今天此刻 / 30 分钟前 */
    fun quickFill(kind: QuickFill) {
        val now = LocalDateTime.now()
        val dt = when (kind) {
            QuickFill.NOW -> now
            QuickFill.ONE_HOUR_AGO -> now.minusHours(1)
            QuickFill.YESTERDAY -> now.minusDays(1).withMinute(0).withSecond(0).withNano(0)
        }
        _uiState.value = _uiState.value.copy(actualDateTime = dt, error = null)
    }

    fun save(onSuccess: () -> Unit) {
        val s = _uiState.value
        val med = s.selectedMedication
        if (med == null) {
            _uiState.value = s.copy(error = "请先选择药品")
            return
        }
        val amount = s.doseAmount.toFloatOrNull()
        if (amount == null || amount <= 0f) {
            _uiState.value = s.copy(error = "请输入有效的服用剂量")
            return
        }
        if (s.actualDateTime.isAfter(LocalDateTime.now().plusMinutes(1))) {
            _uiState.value = s.copy(error = "不能补录未来的服药时间")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, error = null)
            val epochMilli = s.actualDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

            trackingService.logManualDose(
                medicationId = med.id,
                actualTs = epochMilli,
                doseAmount = amount,
                isRetrospective = s.actualDateTime.isBefore(LocalDateTime.now().minusMinutes(2)),
                note = s.note.ifBlank { "手动补录服药" },
                deductStock = s.deductStock
            )

            _uiState.value = _uiState.value.copy(isSaving = false)
            onSuccess()
        }
    }

    private fun trimFloat(v: Float): String =
        if (v % 1f == 0f) v.toInt().toString() else v.toString()
}

enum class QuickFill { NOW, ONE_HOUR_AGO, YESTERDAY }
