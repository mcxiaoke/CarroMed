package com.mcxiaoke.carromed.ui.screen.edit

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.InventoryTransactionEntity
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.entity.PolicyTimeEntity
import com.mcxiaoke.carromed.core.data.entity.SchedulePolicyEntity
import com.mcxiaoke.carromed.core.data.model.PolicyType
import com.mcxiaoke.carromed.core.data.model.TransactionType
import com.mcxiaoke.carromed.core.domain.engine.SlotProjectionEngine
import com.mcxiaoke.carromed.core.domain.service.DoseTrackingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

data class TimeSlotDraft(
    val time: String = "08:30",
    val dose: Float = 1.0f
)

data class AddEditUiState(
    val medId: Long? = null,
    val name: String = "",
    val category: String = "常备药",
    val form: String = "片剂",
    val unit: String = "片",
    val colorHex: String = "#2563EB",
    val policyType: PolicyType = PolicyType.DAILY,
    val intervalDays: Int = 2,
    val daysOfWeek: List<Int> = listOf(1, 3, 5),
    val timeSlots: List<TimeSlotDraft> = listOf(TimeSlotDraft("08:30", 1.0f)),
    val currentStock: String = "",
    val minStockAlert: String = "10",
    val description: String = "",
    val isSaving: Boolean = false,
    val error: String? = null
)

class AddEditMedicationViewModel(
    application: Application,
    private val medId: Long?
) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val medDao = db.medicationDao()
    private val policyDao = db.schedulePolicyDao()
    private val inventoryDao = db.inventoryTransactionDao()
    private val trackingService = DoseTrackingService(db)

    private val _uiState = MutableStateFlow(AddEditUiState(medId = medId))
    val uiState: StateFlow<AddEditUiState> = _uiState.asStateFlow()

    init {
        if (medId != null && medId > 0) {
            loadExistingMedication(medId)
        }
    }

    private fun loadExistingMedication(id: Long) {
        viewModelScope.launch {
            val med = medDao.getMedicationById(id) ?: return@launch
            val policy = policyDao.getActivePolicyForMedication(id)
            val times = if (policy != null) policyDao.getTimesForPolicy(policy.id) else emptyList()

            _uiState.value = _uiState.value.copy(
                name = med.name,
                category = med.category,
                form = med.form,
                unit = med.unit,
                colorHex = med.colorHex,
                policyType = policy?.policyType ?: PolicyType.DAILY,
                intervalDays = policy?.intervalDays ?: 2,
                daysOfWeek = policy?.daysOfWeek ?: listOf(1, 3, 5),
                timeSlots = if (times.isNotEmpty()) {
                    times.map { TimeSlotDraft(it.timeOfDay, it.doseAmount) }
                } else {
                    listOf(TimeSlotDraft("08:30", 1.0f))
                },
                currentStock = if (med.isStockTracked) med.currentStock.toInt().toString() else "",
                minStockAlert = if (med.minStockAlert > 0f) med.minStockAlert.toInt().toString() else "10",
                description = med.description
            )
        }
    }

    fun onNameChange(name: String) { _uiState.value = _uiState.value.copy(name = name) }
    fun onCategoryChange(cat: String) { _uiState.value = _uiState.value.copy(category = cat) }
    fun onFormChange(form: String) { _uiState.value = _uiState.value.copy(form = form) }
    fun onPolicyTypeChange(type: PolicyType) { _uiState.value = _uiState.value.copy(policyType = type) }
    fun onCurrentStockChange(stock: String) { _uiState.value = _uiState.value.copy(currentStock = stock) }
    fun onMinStockAlertChange(min: String) { _uiState.value = _uiState.value.copy(minStockAlert = min) }
    fun onDescriptionChange(desc: String) { _uiState.value = _uiState.value.copy(description = desc) }

    fun addTimeSlot() {
        val current = _uiState.value.timeSlots.toMutableList()
        current.add(TimeSlotDraft("20:30", 1.0f))
        _uiState.value = _uiState.value.copy(timeSlots = current)
    }

    fun updateTimeSlot(index: Int, time: String, dose: Float) {
        val current = _uiState.value.timeSlots.toMutableList()
        if (index in current.indices) {
            current[index] = TimeSlotDraft(time, dose)
            _uiState.value = _uiState.value.copy(timeSlots = current)
        }
    }

    fun removeTimeSlot(index: Int) {
        val current = _uiState.value.timeSlots.toMutableList()
        if (current.size > 1 && index in current.indices) {
            current.removeAt(index)
            _uiState.value = _uiState.value.copy(timeSlots = current)
        }
    }

    fun save(onSuccess: (Long) -> Unit) {
        val state = _uiState.value
        if (state.name.isBlank()) {
            _uiState.value = state.copy(error = "请输入药品名称")
            return
        }

        viewModelScope.launch {
            _uiState.value = state.copy(isSaving = true)

            val stockFloat = state.currentStock.toFloatOrNull() ?: 0f
            val isTracked = state.currentStock.isNotBlank() && stockFloat > 0f
            val alertFloat = state.minStockAlert.toFloatOrNull() ?: 0f

            val med = MedicationEntity(
                id = state.medId ?: 0L,
                name = state.name.trim(),
                category = state.category,
                form = state.form,
                unit = state.unit,
                colorHex = state.colorHex,
                currentStock = stockFloat,
                minStockAlert = alertFloat,
                isStockTracked = isTracked,
                description = state.description.trim()
            )

            val savedMedId = medDao.insert(med)
            val finalMedId = if (state.medId != null && state.medId > 0) state.medId else savedMedId

            // 保存提醒策略与时点
            val policy = SchedulePolicyEntity(
                medicationId = finalMedId,
                policyType = state.policyType,
                intervalDays = state.intervalDays,
                daysOfWeek = state.daysOfWeek,
                startDate = LocalDate.now().format(SlotProjectionEngine.DATE_FORMATTER)
            )
            val times = state.timeSlots.mapIndexed { idx, slot ->
                PolicyTimeEntity(
                    policyId = 0,
                    timeOfDay = slot.time,
                    doseAmount = slot.dose,
                    sortOrder = idx
                )
            }
            policyDao.savePolicyWithTimes(policy, times)

            // 若初次录入库存且之前没有流水，记录初始建档流水
            if (isTracked && (state.medId == null || state.medId == 0L)) {
                inventoryDao.insert(
                    InventoryTransactionEntity(
                        medicationId = finalMedId,
                        changeAmount = stockFloat,
                        balanceAfter = stockFloat,
                        txType = TransactionType.CALIBRATION_ADJUST,
                        note = "初始录入建档"
                    )
                )
            }

            // 平滑对齐未来排班 (保留历史打卡不变，重投影未来槽位)
            trackingService.reconcileSchedule(finalMedId)

            _uiState.value = state.copy(isSaving = false)
            onSuccess(finalMedId)
        }
    }
}
