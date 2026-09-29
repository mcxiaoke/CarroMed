package com.mcxiaoke.carromed.ui.screen.record

import android.app.Application
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.DoseRecordEntity
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.model.RecordStatus
import com.mcxiaoke.carromed.core.domain.model.Dose
import com.mcxiaoke.carromed.core.domain.service.DoseTrackingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * 「超过 2 天不能撤销 / 跳过」的产品规则。
 *
 * ## 为什么是 2 天
 *
 * 对齐 MyTherapy 的实机行为（用户实机确认）。语义是：
 * **事实可以永远留着、永远能看，但它"是否成立"只有近两天内还能改** ——
 * 因为两三天前的服药已经进了依从率的历史统计，改它等于改写过去。
 *
 * 这与"物理删除"是两回事：下面 2 天的记录我们仍然不删，只是不提供翻转入口。
 */
const val EDITABLE_WINDOW_DAYS: Long = 2

data class DoseRecordUiState(
    val isLoading: Boolean = true,
    val record: DoseRecordEntity? = null,
    val medication: MedicationEntity? = null,
    /** 剂量输入（String，因为"正在输入 0."是合法中间态，见 M2-1） */
    val doseInput: String = "",
    val noteInput: String = "",
    /**
     * 用户在页面上改过、但**还没保存**的服药时刻。
     *
     * 与 `record.actualTs` 分开存而不是就地改 `record`：
     * `record` 代表**已落库的事实**，就地改会让用户在没点保存前
     * 就看到"已经改了"的假象。
     */
    val pendingActualTs: Long? = null,
    val isSaving: Boolean = false,
    val error: String? = null,
    val done: Boolean = false
) {
    /** 距今是否还在可撤销窗口内。**只有 COMPLETED / SKIPPED 才有这个概念**。 */
    val withinEditWindow: Boolean
        get() = record?.let { isWithinEditWindow(it.actualTs) } ?: false

    /**
     * 槽位来源的记录不许改剂量与时间（UX 方案 §3.3）。
     * 判据挂在**数据来源**上而不是"能不能改"这个结论上，UI 只负责转述。
     */
    val canEditDose: Boolean
        get() = record?.slotId == null && withinEditWindow

    /** 能否改服药时间。与 [canEditDose] 同源：都是"无槽位"才可改。 */
    val canEditTime: Boolean
        get() = record?.slotId == null && withinEditWindow

    /** 是否来自定时提醒的排班。UI 用它选不同的说明文案。 */
    val fromSchedule: Boolean
        get() = record?.slotId != null

    val isReverted: Boolean get() = record?.status == RecordStatus.REVERTED
    val isSkipped: Boolean get() = record?.status == RecordStatus.SKIPPED
}

fun isWithinEditWindow(actualTs: Long): Boolean {
    val today = LocalDate.now()
    val day = java.time.Instant.ofEpochMilli(actualTs)
        .atZone(ZoneId.systemDefault()).toLocalDate()
    val age = java.time.temporal.ChronoUnit.DAYS.between(day, today)
    return age in 0..EDITABLE_WINDOW_DAYS
}

class DoseRecordEditViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val tracking = DoseTrackingService(db)
    private val recordDao = db.doseRecordDao()
    private val medDao = db.medicationDao()

    private val _uiState = MutableStateFlow(DoseRecordUiState())
    val uiState: StateFlow<DoseRecordUiState> = _uiState.asStateFlow()

    /**
     * 载入记录。
     *
     * 剂量与备注**只在首次载入时填一次**，之后一律以用户输入为准 ——
     * 与 M7-3（库存页 `load()` 清空盘点输入）是同一条纪律：
     * 后台刷新不许覆盖用户正在敲的内容。
     */
    fun load(recordId: Long) {
        viewModelScope.launch {
            val record = recordDao.getRecordById(recordId)
            if (record == null) {
                _uiState.update { it.copy(isLoading = false, error = "记录不存在或已被删除") }
                return@launch
            }
            val med = medDao.getMedicationById(record.medicationId)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    record = record,
                    medication = med,
                    doseInput = com.mcxiaoke.carromed.ui.component.Quantity.fmt(
                        Dose(record.doseTaken).asFloat
                    ),
                    noteInput = record.note.orEmpty()
                )
            }
        }
    }

    fun onDoseChange(v: String) = _uiState.update {
        it.copy(
            doseInput = com.mcxiaoke.carromed.ui.component.DecimalInput.filter(v),
            error = null
        )
    }

    fun onNoteChange(v: String) = _uiState.update { it.copy(noteInput = v) }

    /** 改服药时刻（仅无槽位的记录）。UI 的日期/时间选择器调它。 */
    fun onActualTsChange(ts: Long) {
        val s = _uiState.value
        if (!s.canEditTime) {
            _uiState.update { it.copy(error = "这条记录不能改时间") }
            return
        }
        if (ts > System.currentTimeMillis()) {
            _uiState.update { it.copy(error = "服药时间不能晚于现在") }
            return
        }
        _uiState.update { it.copy(pendingActualTs = ts, error = null) }
    }

    /** 保存剂量 / 备注。走服务层 [DoseTrackingService.editDose]，台账由它保证守恒。 */
    fun save() {
        val s = _uiState.value
        val record = s.record ?: return
        // ⭐ 同步闸门：与 M2-4「保存」同一教训。写在协程体里的话，
        // 快速连点会同时起两次保存，而 `editDose` 是幂等的所以不至于写坏数据，
        // 但用户会看到两次成功提示。
        if (s.isSaving) return

        val parsed = com.mcxiaoke.carromed.ui.component.DecimalInput.parsePositive(s.doseInput)
        if (s.canEditDose && parsed == null) {
            _uiState.update { it.copy(error = "请输入大于 0 的剂量（例如 1 或 0.5）") }
            return
        }

        _uiState.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            val result = runCatching {
                tracking.editDose(
                    recordId = record.id,
                    newDoseAmount = if (s.canEditDose) parsed else null,
                    newNote = s.noteInput,
                    newActualTs = s.pendingActualTs
                )
            }
            result
                .onSuccess { changed ->
                    if (changed) {
                        _uiState.update { it.copy(isSaving = false, done = true) }
                    } else {
                        // 服务层拒绝 = 状态已经变了（已撤销）或参数非法
                        reloadAfterReject()
                    }
                }
                .onFailure { t ->
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            error = "保存失败：${t.message ?: t::class.java.simpleName}"
                        )
                    }
                }
        }
    }

    /**
     * 标记为已跳过 / 已确认。
     *
     * 对**已跳过**的记录，"确认"没有意义（事实已定），所以只提供"撤销"；
     * 对**已确认**的记录，"跳过"是误操作后的纠正，两者互斥。
     */
    fun markSkipped() = mutateStatus(RecordStatus.SKIPPED, "已标记为跳过")

    fun revert() {
        val record = _uiState.value.record ?: return
        if (!isWithinEditWindow(record.actualTs)) {
            _uiState.update { it.copy(error = "超过 2 天的记录不能撤销") }
            return
        }
        viewModelScope.launch {
            runCatching { tracking.undoManualDose(record.id) }
                .onSuccess { ok ->
                    if (ok) {
                        Toast.makeText(getApplication(), "已撤销", Toast.LENGTH_SHORT).show()
                        _uiState.update { it.copy(done = true) }
                    } else {
                        reloadAfterReject()
                    }
                }
                .onFailure { t ->
                    _uiState.update { it.copy(error = "撤销失败：${t.message}") }
                }
        }
    }

    private fun mutateStatus(target: RecordStatus, toast: String) {
        val s = _uiState.value
        val record = s.record ?: return
        if (s.isReverted) return
        if (s.isSkipped && target == RecordStatus.SKIPPED) return
        if (!isWithinEditWindow(record.actualTs)) {
            _uiState.update { it.copy(error = "超过 2 天的记录不能修改状态") }
            return
        }
        viewModelScope.launch {
            // 跳过走 skipDose（它管槽位状态），确认走 undo 再补事实太绕，
            // 这里对无槽位记录直接改事实状态，对有槽位的委托给 skipDose。
            val ok = runCatching {
                if (target == RecordStatus.SKIPPED && record.slotId != null) {
                    tracking.skipDose(record.slotId!!)
                } else {
                    tracking.editDose(recordId = record.id, newNote = s.noteInput)
                }
            }.getOrDefault(false)
            if (ok) {
                Toast.makeText(getApplication(), toast, Toast.LENGTH_SHORT).show()
                _uiState.update { it.copy(done = true) }
            } else {
                reloadAfterReject()
            }
        }
    }

    /** 服务层拒绝后重新载入，让 UI 显示**当前真实状态**而不是继续显示过期数据。 */
    private fun reloadAfterReject() {
        val record = _uiState.value.record ?: return
        viewModelScope.launch {
            val fresh = recordDao.getRecordById(record.id)
            _uiState.update {
                it.copy(
                    isSaving = false,
                    record = fresh,
                    doseInput = fresh?.let { r ->
                        com.mcxiaoke.carromed.ui.component.Quantity.fmt(Dose(r.doseTaken).asFloat)
                    } ?: it.doseInput,
                    noteInput = fresh?.note ?: it.noteInput,
                    error = "这条记录已不可修改（可能已被撤销）"
                )
            }
        }
    }

    fun consumeError() = _uiState.update { it.copy(error = null) }
}

/** 供 UI 显示用：把 `actualTs` 格式成本地日期。 */
fun formatRecordDate(actualTs: Long): String =
    java.time.Instant.ofEpochMilli(actualTs)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .toString()
