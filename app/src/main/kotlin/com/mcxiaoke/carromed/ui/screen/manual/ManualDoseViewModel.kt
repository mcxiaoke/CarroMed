package com.mcxiaoke.carromed.ui.screen.manual

import android.app.Application
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
import java.time.format.DateTimeFormatter

data class ManualDoseUiState(
    val medications: List<MedicationEntity> = emptyList(),
    val selectedMedication: MedicationEntity? = null,
    val doseAmount: String = "1",
    val actualDateTime: LocalDateTime = LocalDateTime.now(),
    val note: String = "",
    val deductStock: Boolean = true,
    val isSaving: Boolean = false,
    val error: String? = null
)

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
            val meds = medDao.getActiveMedications()
            val initialSelected = if (initialMedId != null) {
                meds.firstOrNull { it.id == initialMedId } ?: meds.firstOrNull()
            } else {
                meds.firstOrNull()
            }
            _uiState.value = _uiState.value.copy(
                medications = meds,
                selectedMedication = initialSelected,
                doseAmount = initialSelected?.defaultDose?.toInt()?.toString() ?: "1"
            )
        }
    }

    fun selectMedication(med: MedicationEntity) {
        _uiState.value = _uiState.value.copy(
            selectedMedication = med,
            doseAmount = med.defaultDose.toInt().toString()
        )
    }

    fun onDoseAmountChange(amount: String) { _uiState.value = _uiState.value.copy(doseAmount = amount) }
    fun onNoteChange(note: String) { _uiState.value = _uiState.value.copy(note = note) }
    fun onDeductStockChange(deduct: Boolean) { _uiState.value = _uiState.value.copy(deductStock = deduct) }

    fun save(onSuccess: () -> Unit) {
        val med = _uiState.value.selectedMedication ?: return
        val amount = _uiState.value.doseAmount.toFloatOrNull() ?: 1.0f

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            val epochMilli = _uiState.value.actualDateTime
                .atZone(java.time.ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()

            trackingService.logManualDose(
                medicationId = med.id,
                actualTs = epochMilli,
                doseAmount = amount,
                isRetrospective = true,
                note = _uiState.value.note.ifBlank { "手动补录服药" }
            )

            _uiState.value = _uiState.value.copy(isSaving = false)
            onSuccess()
        }
    }
}
