package com.mcxiaoke.carromed.ui.screen.refill

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcxiaoke.carromed.R
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.domain.AppLog
import com.mcxiaoke.carromed.core.domain.service.DoseTrackingService
import com.mcxiaoke.carromed.ui.component.DecimalInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RefillUiState(
    val medication: MedicationEntity? = null,
    /** 台账聚合出的账面余额（可为负，见 FINAL-PRODUCT D-9） */
    val stock: Float = 0f,
    val addAmount: String = "30",
    val channel: String = "同仁堂实体药房",
    val batchNumber: String = "",
    val expiryDate: String = "",
    val note: String = "",
    val isSaving: Boolean = false,
    val error: String? = null
)

/**
 * 补药入库
 *
 * 修复记录：原实现把 `batchNumber` / `expiryDate` 采集进 UI 状态后**直接丢弃**
 * （只有 `channel` 被拼进 note），并且给"有效期至"预填了一个假值 `2028/05/30`，
 * 用户会误以为系统已记录。现已把批次号与有效期落到 inventory_transactions 新增列，
 * 并去掉假预填。
 */
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
            val overview = medDao.getOverviewById(medId)
            _uiState.value = _uiState.value.copy(
                medication = overview?.medication,
                stock = overview?.stock ?: 0f,
                // 不预填假日期
                expiryDate = ""
            )
        }
    }

    fun onAddAmountChange(amt: String) {
        _uiState.value = _uiState.value.copy(
            addAmount = DecimalInput.filter(amt),
            error = null
        )
    }

    fun setQuickAmount(amt: Float) {
        val cur = _uiState.value.addAmount.toFloatOrNull() ?: 0f
        _uiState.value = _uiState.value.copy(
            addAmount = trim(cur + amt),
            error = null
        )
    }

    fun onChannelChange(ch: String) { _uiState.value = _uiState.value.copy(channel = ch) }
    fun onBatchNumberChange(v: String) { _uiState.value = _uiState.value.copy(batchNumber = v.trim()) }
    fun onExpiryDateChange(v: String) { _uiState.value = _uiState.value.copy(expiryDate = v) }
    fun onNoteChange(v: String) { _uiState.value = _uiState.value.copy(note = v) }

    fun confirmRefill(onSuccess: () -> Unit) {
        val s = _uiState.value
        val amt = DecimalInput.parsePositive(s.addAmount)
        if (amt == null) {
            _uiState.value = s.copy(
                error = getApplication<Application>().getString(R.string.refill_error_amount_invalid)
            )
            return
        }
        if (s.expiryDate.isNotBlank() && !Regex("""^\d{4}-\d{2}-\d{2}$""").matches(s.expiryDate)) {
            _uiState.value = s.copy(
                error = getApplication<Application>().getString(R.string.refill_error_expiry_format)
            )
            return
        }
        // 双击保护与另两个表单同一条纪律（M2-4）
        if (s.isSaving) return

        viewModelScope.launch {
            val app = getApplication<Application>()
            _uiState.value = _uiState.value.copy(isSaving = true, error = null)
            // 复用领域层的入库路径，而不是自己重写一遍内联事务 ——
            // 内联版本曾直接调 updateStock 改账面（现已不存在该 API），
            // 而重复实现本身就是两处逻辑漂移的来源。
            //
            // ⚠️ 整条链包 runCatching（M2-3）：药品被删 / 领域层 require 拒绝时
            // 未捕获的异常会崩到主线程，且 `isSaving` 永久停在 true。
            val ok = runCatching {
                trackingService.refillStock(
                    medicationId = medId,
                    addedAmount = amt,
                    note = buildString {
                        append("采购入库 (${s.channel})")
                        if (s.note.isNotBlank()) append(" · ${s.note}")
                    },
                    batchNumber = s.batchNumber.ifBlank { null },
                    expiryDate = s.expiryDate.ifBlank { null }
                )
            }.getOrElse { t ->
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    error = app.getString(R.string.refill_error_save_failed, t.message ?: t::class.java.simpleName)
                )
                return@launch
            }
            if (!ok) {
                _uiState.value = _uiState.value.copy(isSaving = false, error = app.getString(R.string.refill_error_med_missing))
                return@launch
            }
            // 入库即自动开启库存追踪（此前需用户手工在表单里填初始库存才开）。
            // ⚠️ **必须传 initialStock = null**（M2-5）：本页面停留期间可能已发生打卡扣减，
            // 传页面上的陈旧余额会把它当"用户声明的初始库存"写回账面，凭空多出一份。
            //
            // 失败不能静默（osbf P2-5）：入库已成功而开启追踪失败，用户必须知道
            // "这次入库没有开始自动扣库存"，否则他会以为追踪一直是开着的。
            runCatching { trackingService.setStockTracking(medId, true, initialStock = null) }
                .onFailure { t ->
                    AppLog.w("RefillViewModel", "enable stock tracking after refill failed med=$medId", t)
                    _uiState.value = _uiState.value.copy(
                        error = app.getString(R.string.refill_error_tracking_failed, t.message ?: t::class.java.simpleName)
                    )
                }
            _uiState.value = _uiState.value.copy(isSaving = false)
            onSuccess()
        }
    }

    private fun trim(v: Float): String = if (v % 1f == 0f) v.toInt().toString() else v.toString()
}
