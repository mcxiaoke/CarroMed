package com.mcxiaoke.carromed.ui.screen.inventory

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcxiaoke.carromed.core.alarm.AlarmReconciler
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.InventoryTransactionEntity
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.entity.PolicyTimeEntity
import com.mcxiaoke.carromed.core.data.model.PolicyType
import com.mcxiaoke.carromed.core.data.model.TransactionType
import com.mcxiaoke.carromed.core.domain.engine.StatsEngine
import com.mcxiaoke.carromed.core.domain.service.DoseTrackingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class InventoryUiState(
    val medication: MedicationEntity? = null,
    val frequencyDescription: String = "",
    val isLoading: Boolean = true,

    val isTracked: Boolean = false,
    val currentStock: Float = 0f,
    val minStockAlert: Float = 0f,
    val runwayDays: Int = 0,
    val isLowStock: Boolean = false,
    val dailyConsumption: Float = 0f,

    val expiryDate: String = "",
    val daysToExpiry: Int? = null,

    /** 预警线输入框草稿 (与已保存值分离，避免每敲一个字就写库) */
    val minStockAlertInput: String = "",

    val transactions: List<InventoryTransactionEntity> = emptyList(),

    val calibrateInput: String = "",
    val isSaving: Boolean = false,
    val error: String? = null,
    val message: String? = null
)

/**
 * 库存管理 —— 独立于药品信息与提醒设置的第三个维度
 *
 * 承载：当前余量 / 预警线 / 剩余可服用天数 / 有效期临期 / 开关追踪 /
 * 盘点校准 / 完整出入库流水。库存扣减只依据**实际打卡**记录，不依据计划量。
 */
class InventoryViewModel(
    application: Application,
    private val medId: Long
) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val medDao = db.medicationDao()
    private val policyDao = db.schedulePolicyDao()
    private val inventoryDao = db.inventoryTransactionDao()
    private val trackingService = DoseTrackingService(db)

    private val _uiState = MutableStateFlow(InventoryUiState())
    val uiState: StateFlow<InventoryUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val med = medDao.getMedicationById(medId)
            if (med == null) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = "药品不存在或已被删除")
                return@launch
            }
            val policy = policyDao.getActivePolicyForMedication(medId)
            val times = if (policy != null) policyDao.getTimesForPolicy(policy.id) else emptyList()
            val txs = inventoryDao.getTransactionsForMedication(medId)

            // 日均消耗：按"排班日"折算，避免隔日/每周用药被高估消耗
            val dosesPerScheduledDay = times.sumOf { it.doseAmount.toDouble() }.toFloat()
            val perWeek = scheduledDosesPerWeek(policy?.policyType, policy?.intervalDays, policy?.daysOfWeek)
            val (runway, alert) = StatsEngine.calculateStockRunwayBySchedule(
                currentStock = med.currentStock,
                dosesPerScheduledDay = dosesPerScheduledDay,
                scheduledDosesPerWeek = perWeek
            )

            val expiryDays = if (med.expiryDate.isNotBlank()) {
                runCatching {
                    ChronoUnit.DAYS.between(
                        LocalDate.now(),
                        LocalDate.parse(med.expiryDate)
                    ).toInt()
                }.getOrNull()
            } else null

            _uiState.value = _uiState.value.copy(
                medication = med,
                frequencyDescription = describe(policy?.policyType, times),
                isLoading = false,
                isTracked = med.isStockTracked,
                currentStock = med.currentStock,
                minStockAlert = med.minStockAlert,
                runwayDays = runway,
                isLowStock = alert,
                dailyConsumption = if (perWeek > 0) dosesPerScheduledDay * (perWeek / 7.0f) else dosesPerScheduledDay,
                expiryDate = med.expiryDate,
                daysToExpiry = expiryDays,
                minStockAlertInput = fmt(med.minStockAlert),
                transactions = txs,
                calibrateInput = if (med.currentStock > 0f) fmt(med.currentStock) else ""
            )
        }
    }

    fun onMinStockAlertChange(v: String) {
        _uiState.value = _uiState.value.copy(minStockAlertInput = v)
    }

    fun onCalibrateInputChange(v: String) {
        _uiState.value = _uiState.value.copy(calibrateInput = v.filter { it.isDigit() || it == '.' })
    }

    fun onExpiryDateChange(v: String) {
        _uiState.value = _uiState.value.copy(expiryDate = v)
    }

    /** 导出库存流水 CSV (生成 + 系统分享面板) */
    fun exportLedger() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                val file = com.mcxiaoke.carromed.core.data.DataExporter
                    .exportInventoryLedgerCsv(app, db)
                _uiState.value = _uiState.value.copy(message = "已导出 ${file.name}")
                com.mcxiaoke.carromed.core.data.DataExporter.shareFile(app, file, "text/csv")
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = "导出失败: ${e.message}")
            }
        }
    }

    /** 开关库存追踪（关闭时保留账面；开启时以当前账面建档） */
    fun setTracking(enabled: Boolean) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            trackingService.setStockTracking(medId, enabled, _uiState.value.currentStock)
            runCatching { AlarmReconciler.rescheduleAll(getApplication<Application>(), db) }
            _uiState.value = _uiState.value.copy(
                isSaving = false,
                message = if (enabled) "已开启库存追踪" else "已关闭库存追踪（不再自动扣减）"
            )
            load()
        }
    }

    /** 盘点校准：把账面拉回实物真实值，走流水而非直接改账面 */
    fun calibrate(note: String?) {
        val target = _uiState.value.calibrateInput.toFloatOrNull()
        if (target == null || target < 0f) {
            _uiState.value = _uiState.value.copy(error = "请输入有效的实际库存数量")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, error = null)
            val changed = trackingService.calibrateStock(medId, target, note)
            _uiState.value = _uiState.value.copy(
                isSaving = false,
                message = if (changed) "盘点已记录：账面调整为 $target" else "账面与实物一致，无需调整"
            )
            load()
        }
    }

    fun saveSettings() {
        val s = _uiState.value
        val alert = s.minStockAlertInput.toFloatOrNull() ?: s.minStockAlert
        val expiry = s.expiryDate.takeIf { it != s.medication?.expiryDate }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, error = null)
            if (expiry != null) {
                val med = s.medication
                if (med != null) {
                    medDao.updateProfile(
                        id = medId,
                        name = med.name,
                        alias = med.alias,
                        category = med.category,
                        form = med.form,
                        unit = med.unit,
                        colorHex = med.colorHex,
                        defaultDose = med.defaultDose,
                        description = med.description,
                        precautions = med.precautions,
                        noticeShort = med.noticeShort,
                        expiryDate = expiry,
                        isCriticalReminder = med.isCriticalReminder,
                        snoozeMinutes = med.snoozeMinutes,
                        advanceMinutes = med.advanceMinutes,
                        minStockAlert = alert,
                        updatedAt = System.currentTimeMillis()
                    )
                }
            } else {
                medDao.updateMinStockAlert(medId, alert)
            }
            _uiState.value = _uiState.value.copy(isSaving = false, message = "已保存")
            load()
        }
    }

    /**
     * 每周实际排班天数（不是"时点数"）。用于把单次日量折算成日历日均消耗，
     * 否则隔日/每周用药会被高估消耗、低估可用天数。
     */
    private fun scheduledDosesPerWeek(
        type: PolicyType?,
        intervalDays: Int?,
        daysOfWeek: List<Int>?
    ): Int = when (type) {
        PolicyType.DAILY -> 7
        PolicyType.INTERVAL -> {
            val n = (intervalDays ?: 2).coerceAtLeast(1)
            Math.round(7.0 / n).toInt().coerceAtLeast(1)
        }
        PolicyType.DAYS_OF_WEEK -> (daysOfWeek?.size ?: 0).coerceAtLeast(0)
        PolicyType.CYCLE -> 5 // 周期用药按 5 天/周保守折算
        PolicyType.PRN, null -> 0
    }

    private fun describe(type: PolicyType?, times: List<PolicyTimeEntity>): String {
        if (type == null || times.isEmpty()) return "暂无排班"
        val n = times.size
        return when (type) {
            PolicyType.DAILY -> "每天 $n 次 (${times.joinToString { it.timeOfDay }})"
            PolicyType.INTERVAL -> "每隔 $n 天 $n 次"
            PolicyType.DAYS_OF_WEEK -> "每周 ${times.size} 天各 $n 次"
            PolicyType.CYCLE -> "周期用药 $n 次/服药日"
            PolicyType.PRN -> "按需服用"
        }
    }

    private fun fmt(v: Float): String = if (v % 1f == 0f) v.toInt().toString() else v.toString()

    fun txLabel(t: TransactionType): String = when (t) {
        TransactionType.TAKEN_DEDUCT -> "服药扣减"
        TransactionType.REFILL -> "购药入库"
        TransactionType.REVERT_ROLLBACK -> "撤销冲正"
        TransactionType.CALIBRATION_ADJUST -> "盘点调整"
    }
}
