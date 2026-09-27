package com.mcxiaoke.carromed.ui.screen.refill

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

data class RefillUiState(
    val medication: MedicationEntity? = null,
    val addAmount: String = "30",
    val channel: String = "同仁堂实体药房",
    val batchNumber: String = "",
    val expiryDate: String = "2028/05/30",
    val isSaving: Boolean = false
)

class RefillViewModel(
    application: Application,
    private val medId: Long
) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val medDao = db.medicationDao()
    private val trackingService = DoseTrackingService(db)

    private val _uiState = MutableStateFlow(RefillUiState())
    val uiState: StateFlow<RefillUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val med = medDao.getMedicationById(medId)
            _uiState.value = _uiState.value.copy(medication = med)
        }
    }

    fun onAddAmountChange(amt: String) { _uiState.value = _uiState.value.copy(addAmount = amt) }
    fun setQuickAmount(amt: Int) { _uiState.value = _uiState.value.copy(addAmount = amt.toString()) }
    fun onChannelChange(ch: String) { _uiState.value = _uiState.value.copy(channel = ch) }
    fun onBatchNumberChange(batch: String) { _uiState.value = _uiState.value.copy(batchNumber = batch) }
    fun onExpiryDateChange(expiry: String) { _uiState.value = _uiState.value.copy(expiryDate = expiry) }

    fun confirmRefill(onSuccess: () -> Unit) {
        val amt = _uiState.value.addAmount.toFloatOrNull() ?: return
        if (amt <= 0f) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            val note = "采购入库 (${_uiState.value.channel})"
            trackingService.refillStock(medId, amt, note)
            _uiState.value = _uiState.value.copy(isSaving = false)
            onSuccess()
        }
    }
}
