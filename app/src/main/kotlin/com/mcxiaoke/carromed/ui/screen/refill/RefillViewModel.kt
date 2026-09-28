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
            addAmount = amt.filter { it.isDigit() || it == '.' },
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
        val amt = s.addAmount.toFloatOrNull()
        if (amt == null || amt <= 0f) {
            _uiState.value = s.copy(error = "请输入大于 0 的入库数量")
            return
        }
        if (s.expiryDate.isNotBlank() && !Regex("""^\d{4}-\d{2}-\d{2}$""").matches(s.expiryDate)) {
            _uiState.value = s.copy(error = "有效期格式应为 yyyy-MM-dd")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, error = null)
            // 复用领域层的入库路径，而不是自己重写一遍内联事务 ——
            // 内联版本曾直接调 updateStock 改账面（现已不存在该 API），
            // 而重复实现本身就是两处逻辑漂移的来源。
            val ok = trackingService.refillStock(
                medicationId = medId,
                addedAmount = amt,
                note = buildString {
                    append("采购入库 (${s.channel})")
                    if (s.note.isNotBlank()) append(" · ${s.note}")
                },
                batchNumber = s.batchNumber.ifBlank { null },
                expiryDate = s.expiryDate.ifBlank { null }
            )
            if (!ok) {
                _uiState.value = _uiState.value.copy(isSaving = false, error = "药品已不存在，入库未执行")
                return@launch
            }
            // 入库即自动开启库存追踪（此前需用户手工在表单里填初始库存才开）
            trackingService.setStockTracking(medId, true)
            _uiState.value = _uiState.value.copy(isSaving = false)
            onSuccess()
        }
    }

    private fun trim(v: Float): String = if (v % 1f == 0f) v.toInt().toString() else v.toString()
}
