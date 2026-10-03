package com.mcxiaoke.carromed.ui.screen.record

import android.app.Application
import com.mcxiaoke.carromed.core.alarm.ReminderSettings
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.DoseRecordEntity
import com.mcxiaoke.carromed.core.data.entity.DoseSlotEntity
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.model.RecordStatus
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import com.mcxiaoke.carromed.core.domain.AppLog
import com.mcxiaoke.carromed.core.domain.engine.SlotActionPolicy
import com.mcxiaoke.carromed.core.domain.engine.SlotProjectionEngine
import com.mcxiaoke.carromed.core.domain.model.Dose
import com.mcxiaoke.carromed.core.alarm.DoseEntryActions
import com.mcxiaoke.carromed.core.time.CurrentDateHolder
import com.mcxiaoke.carromed.core.domain.service.DoseTrackingService
import com.mcxiaoke.carromed.core.domain.service.MANUAL_DOSE_BACKFILL_DAYS
import com.mcxiaoke.carromed.ui.component.DecimalInput
import com.mcxiaoke.carromed.ui.component.Quantity
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcxiaoke.carromed.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * **可编辑窗口：7 天**（只作用于"手动补录记录"的剂量 / 时间 / 撤销）。
 *
 * 与领域层的补录时间窗 [com.mcxiaoke.carromed.core.domain.service.MANUAL_DOSE_BACKFILL_DAYS]
 * 同值同源：能补录多久之前的药，就应该能撤销多久之前的补录 ——
 * 否则"昨天补了 6 天前的药，今天想撤"会被窗口卡死，事实与修正入口不同步。
 * 旧的 2 天窗口就是这么和 7 天补录窗打架的（撤销窗 < 补录窗 = 永久不可改的既成事实）。
 *
 * ⚠️ **计划内记录的"撤销"不适用这个窗口**：那条规则是"仅当天"（见 [DoseEntryUiState.canUndo]），
 * 理由写在 `docs/PLAN-RECORD-DETAIL-20260929.md` §5.1：撤销会把槽位退回 `PENDING`，
 * 而计划时间早已过去 ⇒ 下一轮对账立刻把它结算成 `EXPIRED` ⇒
 * 用户翻到那天会看到一个**永远清不掉**的"已逾期未确认"待办。
 * 手动补录没有槽位，不存在这个问题，所以可以宽到 7 天。
 */
const val EDITABLE_WINDOW_DAYS: Long = MANUAL_DOSE_BACKFILL_DAYS

fun isWithinEditWindow(actualTs: Long, today: LocalDate = LocalDate.now()): Boolean {
    val day = Instant.ofEpochMilli(actualTs).atZone(ZoneId.systemDefault()).toLocalDate()
    val age = java.time.temporal.ChronoUnit.DAYS.between(day, today)
    return age in 0..EDITABLE_WINDOW_DAYS
}

/**
 * 两个时刻是否落在**同一个自然日**（本地时区）。
 *
 * 「撤销仅当天」的判据。刻意不做"距现在 N 小时"的宽限：
 * 跨午夜的真实缺口（23:00 打卡、00:20 想撤销）已记进
 * `docs/PLAN-EXPIRE-WINDOW-20260929.md` §8.1，等那一轮一起解，别在这里造第二个判据。
 */
fun isSameLocalDay(a: Long, b: Long): Boolean {
    val zone = ZoneId.systemDefault()
    return Instant.ofEpochMilli(a).atZone(zone).toLocalDate() ==
        Instant.ofEpochMilli(b).atZone(zone).toLocalDate()
}

private fun Long.toLocalDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

/**
 * 统一「记录详情页」的界面状态。
 *
 * ## 一个状态模型覆盖五种形态
 *
 * | 形态 | 判定 | 底部操作 |
 * | :--- | :--- | :--- |
 * | 待服 / 已推迟 | `slot.status` 为 PENDING / SNOOZED | 确认、推迟、跳过 |
 * | 已逾期 | `EXPIRED` | 确认（补记）、跳过 |
 * | 已服 | `COMPLETED` | 跳过（改判）、撤销 |
 * | 已跳过 | `SKIPPED` | 确认（改判）、撤销 |
 * | 手动补录 | `slot == null && record != null` | 撤销（7 天内）+ 改剂量 / 时间 / 备注 |
 *
 * 另外有一维**与状态正交**：`isActionable`（槽位计划日不晚于今天）。
 * 未来日即使状态是待服，也**不渲染**确认 / 推迟 / 跳过 —— 服药是已发生的事实。
 * 这一维由 `SlotActionPolicy` 与服务层 SQL 守卫共用同一份判据。
 *
 * `—` 的动作**不渲染按钮**，而不是置灰：置灰会让人以为"再等等就能用"，
 * 而"撤销一条昨天的记录"这类事永远不会变可用。
 */
data class DoseEntryUiState(
    val isLoading: Boolean = true,
    val notFound: Boolean = false,
    val slot: DoseSlotEntity? = null,
    val medication: MedicationEntity? = null,
    /** 该槽位**当前有效**的服药事实（已撤销的那条不算） */
    val record: DoseRecordEntity? = null,
    /** 台账账面余额（**展示值**，可为负）。未开启库存追踪时为 null */
    val stock: Float? = null,
    /** 低库存预警线（**展示值**）。0 表示关闭告警 */
    val minStockAlert: Float = 0f,
    /**
     * 展示单位。纯状态类不持有 Android 上下文，默认留空；
     * ViewModel 载入时填 `med.unit`，为空则兜底 R.string.rdetail_unit_default
     */
    val unit: String = "",
    /** 剂量输入（String，因为"正在输入 0."是合法中间态，见 M2-1） */
    val doseInput: String = "",
    val noteInput: String = "",
    /**
     * 这个槽位的**计划日**是否允许被表态（`scheduled_date <= 今天`）。
     *
     * 由 ViewModel 在刷新时按 `SlotActionPolicy` 算一次并**存下来**，
     * 而不是在 getter 里读挂钟：getter 里读挂钟会让这个纯状态类的测试
     * 随运行日期变色（本项目的 `DoseRecordDetailStateTest` 全部是纯状态测试）。
     * 手动补录记录（`slot == null`）恒为 true —— 它没有排班日，不受此限。
     */
    val isActionable: Boolean = true,
    /**
     * 用户改过、但**还没保存**的服药时刻。
     *
     * 与 `record.actualTs` 分开存：`record` 代表**已落库的事实**，
     * 就地改会让用户在没点保存前就看到"已经改了"的假象。
     */
    /**
     * 「今天」的可观察事实（L-12）。
     *
     * 与 [isActionable] 同一条纪律：状态类自己**不读挂钟**，判据用的"今天"由
     * ViewModel 从 [com.mcxiaoke.carromed.core.time.CurrentDateHolder] 灌进来。
     * 此前 [isToday] / [withinEditWindow] 在 getter 里实时读挂钟 —— 跨午夜时
     * 没有任何状态变化，Compose 不重组，页面继续按昨天判"仅当天可撤销"。
     * 现在午夜翻面时 ViewModel 会写入新 today，StateFlow 发射 ⇒ 重组 ⇒ 判据刷新。
     */
    val today: LocalDate = LocalDate.now(),
    val pendingActualTs: Long? = null,
    val globalSnoozeMinutes: Int = 30,
    val isSaving: Boolean = false,
    val error: String? = null,
    val done: Boolean = false
) {

    val slotStatus: SlotStatus? get() = slot?.status

    /** 无槽位的手动补录记录 */
    val isManual: Boolean get() = slot == null && record != null

    val isReverted: Boolean get() = record?.status == RecordStatus.REVERTED

    /** 有事实，且发生在今天 —— 「撤销仅当天」的唯一判据（today 为可观察字段，见其 KDoc） */
    val isToday: Boolean
        get() = record?.actualTs?.toLocalDate() == today

    /** 手动补录记录的 7 天窗口（与补录时间窗同源，见 [EDITABLE_WINDOW_DAYS]） */
    val withinEditWindow: Boolean
        get() = record?.let { isWithinEditWindow(it.actualTs, today) } ?: false

    /**
     * 确认服用。
     *
     * 开放槽位（PENDING / SNOOZED / EXPIRED）走打卡；`SKIPPED` 走**改判**——
     * 因为 `markCompletedIfOpen` 的守卫不含 `SKIPPED`（那是防连点重复扣库存的幂等锚点），
     * 直接打卡会静默返回 false。
     *
     * ⚠️ 未来槽位一律为 false：[isActionable] 为假时三个动作都不渲染，
     * 服务层也会拒 —— 两层同源，不靠约定。
     */
    val canConfirm: Boolean
        get() = isActionable && when (slotStatus) {
            SlotStatus.PENDING, SlotStatus.SNOOZED, SlotStatus.EXPIRED -> true
            SlotStatus.SKIPPED -> true
            else -> false
        }

    /** 确认这个动作是否需要走"改判"通路 */
    val confirmNeedsRestate: Boolean get() = slotStatus == SlotStatus.SKIPPED

    /**
     * 推迟。`EXPIRED` 不在此列：`DoseSlotDao.snoozeSlot` 的守卫是
     * `status IN ('PENDING','SNOOZED')`，对逾期槽位必然失败 ——
     * 按钮存在却永远失败就是虚假承诺。
     *
     * 未来槽位同样不在此列：对明天的槽位"推迟 30 分钟"算出来的是**今天**的唤醒时刻。
     */
    val canSnooze: Boolean
        get() = isActionable && (slotStatus == SlotStatus.PENDING || slotStatus == SlotStatus.SNOOZED)

    /** 跳过本次。`COMPLETED` 走改判，`SKIPPED` 是当前状态、不显示 */
    val canSkip: Boolean
        get() = isActionable && when (slotStatus) {
            SlotStatus.PENDING, SlotStatus.SNOOZED, SlotStatus.EXPIRED -> true
            SlotStatus.COMPLETED -> true
            else -> false
        }

    val skipNeedsRestate: Boolean get() = slotStatus == SlotStatus.COMPLETED

    /**
     * 撤销（回到未确认）。
     *
     * - 计划内记录：**仅当天**（见 [EDITABLE_WINDOW_DAYS] 的说明），
     *   外加一个逃生口 [isContradictoryRecord]；
     * - 手动补录记录：7 天（它不退回任何待办，不产生"永远清不掉"的问题）
     */
    val canUndo: Boolean
        get() = when {
            isManual -> withinEditWindow
            slotStatus == SlotStatus.COMPLETED || slotStatus == SlotStatus.SKIPPED ->
                isToday || isContradictoryRecord

            else -> false
        }

    /**
     * 坏数据：事实发生的自然日**早于**所属槽位的计划日。
     *
     * 正常路径写不出这种行：`takeDose` 的 `actualTs` 恒为"现在"，
     * 而槽位守卫要求 `scheduled_date <= 今天` ⇒ **事实日不可能早于计划日**。
     * 反过来说明这条记录是「对未来的槽位打卡」留下的垃圾 ——
     * 而「撤销仅当天」的判据（看 `actualTs`）到第二天就再也放不开它，
     * 用户被自己造出来的垃圾**永久锁死**：界面写着已服、库存少了一片、
     * 唯一的修复入口却不渲染。
     *
     * 这里用字符串比较（零填充 `yyyy-MM-dd` 的字典序 == 时序），
     * 与 `SlotActionPolicy` 同一套口径、同样不抛异常。
     */
    val isContradictoryRecord: Boolean
        get() {
            val r = record ?: return false
            val s = slot ?: return false
            val actualDay = Instant.ofEpochMilli(r.actualTs)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .toString()
            return actualDay < s.scheduledDate
        }

    /** 槽位来源的记录不许改剂量与时间（UX 方案 §3.3）：只有手动补录能改 */
    val canEditDose: Boolean get() = isManual && withinEditWindow

    val canEditTime: Boolean get() = isManual && withinEditWindow

    /** 是否需要渲染"备注可编辑 + 保存"这一块（有事实才谈得上保存备注） */
    val canEditNote: Boolean get() = record != null && !isReverted

    /** 页面上是否需要显示任何状态动作按钮 */
    val hasAnyAction: Boolean get() = canConfirm || canSnooze || canSkip || canUndo

    /** 备注随"确认服用"一起落库的说明只在待服形态出现 */
    val noteJoinsConfirm: Boolean get() = record == null && slot != null

    /** 剂量展示文案（毫单位 → 展示值） */
    fun doseText(milli: Int): String = Quantity.withUnit(Dose(milli).asFloat, unit)
}

/**
 * 记录详情页的 ViewModel。
 *
 * ## 两个入口，一套状态
 *
 * - `load(slotId = x)`：今日清单三分区（含还没有事实的待服槽位）
 * - `load(recordId = x)`：进展流水 / 单药历史里的手动补录记录（`slot_id == null`）
 *
 * ⚠️ **构造器只能有 `Application` 一个参数**（AGENTS.md §2 第 5 条）：
 * `viewModel()` 走 `AndroidViewModelFactory`，它用反射找
 * `getConstructor(Application::class.java)`，而 Kotlin 的默认参数**不会**生成单参 Java
 * 构造器。加参数的结果是"编译过、单测全绿、真机一打开就崩"。
 */
class DoseRecordDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val tracking = DoseTrackingService(db)
    private val actions = DoseEntryActions(application, db)
    private val slotDao = db.doseSlotDao()
    private val recordDao = db.doseRecordDao()
    private val medDao = db.medicationDao()

    private val _uiState = MutableStateFlow(DoseEntryUiState())
    val uiState: StateFlow<DoseEntryUiState> = _uiState.asStateFlow()

    private var slotId: Long? = null
    private var recordId: Long? = null
    private var slotJob: Job? = null

    init {
        // 「今天」走可观察事实（L-12）：跨午夜 / 进程回前台时 CurrentDateHolder
        // 发射新日期 ⇒ 状态里 today 刷新 ⇒ isToday / withinEditWindow 判据随之重组。
        // 与今日页/进展页同一来源，本页不再自建定时器。
        viewModelScope.launch {
            CurrentDateHolder.today.collect { d ->
                _uiState.update { it.copy(today = d) }
            }
        }
        // 推迟档位的默认档来自全局设置（与今日页长按弹窗同一个来源）
        viewModelScope.launch {
            db.appSettingDao().observeValue(ReminderSettings.KEY_SNOOZE_MINUTES).collect { v ->
                val minutes = v?.toIntOrNull() ?: ReminderSettings.DEFAULT_SNOOZE_MINUTES
                _uiState.update { it.copy(globalSnoozeMinutes = minutes) }
            }
        }
    }

    fun load(slotId: Long?, recordId: Long?) {
        this.slotId = slotId?.takeIf { it > 0 }
        this.recordId = recordId?.takeIf { it > 0 }
        slotJob?.cancel()

        val sid = this.slotId
        if (sid != null) {
            // 槽位是主数据源：对账把它结算成 EXPIRED、别的入口把它打卡，
            // 页面形态都要跟着变，所以走 Flow 而不是一次性读取。
            slotJob = viewModelScope.launch {
                slotDao.observeSlotById(sid).collect { slot ->
                    if (slot == null) {
                        _uiState.update { it.copy(isLoading = false, notFound = true) }
                    } else {
                        refreshFromSlot(slot)
                    }
                }
            }
        } else {
            viewModelScope.launch { loadManualRecord() }
        }
    }

    /** 手动补录记录（`slot_id == null`）：一次性读取，剂量与备注只在首次载入时填 */
    private suspend fun loadManualRecord() {
        val app = getApplication<Application>()
        val rid = recordId ?: run {
            _uiState.update { it.copy(isLoading = false, notFound = true) }
            return
        }
        val record = recordDao.getRecordById(rid)
        if (record == null) {
            _uiState.update { it.copy(isLoading = false, notFound = true) }
            return
        }
        val med = medDao.getMedicationById(record.medicationId)
        val overview = medDao.getOverviewById(record.medicationId)
        _uiState.update {
            it.copy(
                isLoading = false,
                notFound = false,
                slot = null,
                medication = med,
                record = record,
                stock = if (med?.isStockTracked == true) overview?.stock else null,
                minStockAlert = overview?.minStockAlert ?: 0f,
                unit = med?.unit ?: app.getString(R.string.rdetail_unit_default),
                doseInput = Quantity.fmt(Dose(record.doseTaken).asFloat),
                noteInput = record.note.orEmpty()
            )
        }
    }

    private suspend fun refreshFromSlot(slot: DoseSlotEntity) {
        val app = getApplication<Application>()
        val overview = medDao.getOverviewById(slot.medicationId)
        val med = overview?.medication
        val record = currentRecordFor(slot)
        // 未来判据在**这里**算一次（而不是 getter 里读挂钟）：状态类是纯数据，
        // 测试可以直接构造 isActionable = false / true，不受运行日期影响。
        val todayStr = LocalDate.now().format(SlotProjectionEngine.DATE_FORMATTER)
        val actionable = SlotActionPolicy.isActionableOn(slot.scheduledDate, todayStr)
        _uiState.update { state ->
            state.copy(
                isLoading = false,
                notFound = false,
                slot = slot,
                isActionable = actionable,
                medication = med,
                record = record,
                stock = if (med?.isStockTracked == true) overview?.stock else null,
                minStockAlert = overview?.minStockAlert ?: 0f,
                unit = med?.unit ?: app.getString(R.string.rdetail_unit_default),
                // 剂量与备注只在**首次**载入时灌入：后台刷新不许覆盖用户正在敲的内容（M7-3）
                doseInput = if (state.isLoading || state.record?.id != record?.id) {
                    Quantity.fmt(Dose(record?.doseTaken ?: slot.doseAmount).asFloat)
                } else {
                    state.doseInput
                },
                noteInput = if (state.isLoading || state.record?.id != record?.id) {
                    record?.note.orEmpty()
                } else {
                    state.noteInput
                }
            )
        }
    }

    /**
     * 该槽位**当前有效**的结论。
     *
     * ⚠️ 不能用 `getRecordBySlotId`（`ORDER BY id ASC LIMIT 1`）：改判与撤销都会在
     * 同一槽位留下多条事实（旧的那条标 `REVERTED`），取最早那条会读到已作废的记录。
     */
    private suspend fun currentRecordFor(slot: DoseSlotEntity): DoseRecordEntity? {
        val all = recordDao.getAllRecordsBySlotId(slot.id)
        return when (slot.status) {
            SlotStatus.COMPLETED -> all.lastOrNull { it.status == RecordStatus.COMPLETED }
            SlotStatus.SKIPPED -> all.lastOrNull { it.status == RecordStatus.SKIPPED }
            else -> null
        }
    }

    fun onDoseChange(v: String) = _uiState.update {
        it.copy(doseInput = DecimalInput.filter(v), error = null)
    }

    fun onNoteChange(v: String) = _uiState.update { it.copy(noteInput = v) }

    fun onActualTsChange(ts: Long) {
        val s = _uiState.value
        if (!s.canEditTime) {
            _uiState.update {
                it.copy(error = getApplication<Application>().getString(R.string.rdetail_error_time_locked))
            }
            return
        }
        if (ts > System.currentTimeMillis()) {
            _uiState.update {
                it.copy(error = getApplication<Application>().getString(R.string.rdetail_error_time_future))
            }
            return
        }
        _uiState.update { it.copy(pendingActualTs = ts, error = null) }
    }

    // ==================== 状态动作 ====================

    /** 确认服用（开放槽位走打卡，已跳过走改判），备注随之一并落库 */
    fun confirm() {
        val s = _uiState.value
        val sid = slotId ?: return
        if (!s.canConfirm) return
        val note = s.noteInput.ifBlank { null }
        run(R.string.rdetail_fail_confirm) {
            if (s.confirmNeedsRestate) {
                actions.restate(sid, RecordStatus.COMPLETED, note)
            } else {
                // 未来槽位的按钮在 §4.3 之后不再渲染，这里只把"没成功"如实传回骨架
                actions.confirm(sid, note = note).isApplied
            }
        }
    }

    /** 跳过本次（已服记录走改判） */
    fun skip() {
        val s = _uiState.value
        val sid = slotId ?: return
        if (!s.canSkip) return
        val note = s.noteInput.ifBlank { null }
        run(R.string.rdetail_fail_skip) {
            if (s.skipNeedsRestate) {
                actions.restate(sid, RecordStatus.SKIPPED, note)
            } else {
                actions.skip(sid, reason = note).isApplied
            }
        }
    }

    /** 撤销：回到未确认（当天可撤销，见 [DoseEntryUiState.canUndo]） */
    fun undo() {
        val s = _uiState.value
        if (s.isManual) {
            val rid = recordId ?: return
            if (!s.canUndo) return
            run(R.string.rdetail_fail_undo) { tracking.undoManualDose(rid) }
            return
        }
        val sid = slotId ?: return
        if (!s.canUndo) return
        run(R.string.rdetail_fail_undo) { actions.undo(sid) }
    }

    /** 推迟提醒 */
    fun snooze(minutes: Int) {
        val s = _uiState.value
        val sid = slotId ?: return
        if (!s.canSnooze) return
        run(R.string.rdetail_fail_snooze) { actions.snooze(sid, minutes) }
    }

    /**
     * 保存（改剂量 / 改时间 / 改备注）。
     *
     * ⚠️ 同步闸门：与 M2-4「保存」同一教训 —— 写在协程体里的话，快速连点会起两次保存。
     */
    fun save() {
        val s = _uiState.value
        val record = s.record ?: return
        if (s.isSaving) return

        val parsed = DecimalInput.parsePositive(s.doseInput)
        if (s.canEditDose && parsed == null) {
            _uiState.update {
                it.copy(error = getApplication<Application>().getString(R.string.rdetail_error_dose_invalid))
            }
            return
        }

        _uiState.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            val app = getApplication<Application>()
            runCatching {
                tracking.editDose(
                    recordId = record.id,
                    newDoseAmount = if (s.canEditDose) parsed else null,
                    newNote = s.noteInput,
                    newActualTs = s.pendingActualTs
                )
            }
                .onSuccess { changed ->
                    if (changed) {
                        _uiState.update { it.copy(isSaving = false, done = true) }
                    } else {
                        // 服务层拒绝 = 状态已经变了（已撤销）或参数非法：重新载入显示真实状态
                        _uiState.update {
                            it.copy(isSaving = false, error = app.getString(R.string.rdetail_error_record_immutable))
                        }
                    }
                }
                .onFailure { t ->
                    AppLog.e("DoseRecordDetailVM", "save failed record=${_uiState.value.record?.id}", t)
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            error = app.getString(R.string.rdetail_error_save_failed)
                        )
                    }
                }
        }
    }

    /** 每个状态动作的公共骨架：防连点 → 执行 → 成功即返回列表 / 失败给提示。 */
    private fun run(@StringRes failRes: Int, block: suspend () -> Boolean) {
        if (_uiState.value.isSaving) return
        _uiState.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            val app = getApplication<Application>()
            runCatching { block() }
                .onSuccess { ok ->
                    if (ok) {
                        _uiState.update { it.copy(isSaving = false, done = true) }
                    } else {
                        _uiState.update {
                            it.copy(
                                isSaving = false,
                                error = app.getString(R.string.rdetail_error_state_changed)
                            )
                        }
                    }
                }
                .onFailure { t ->
                    // ⚠️ 不把 t.message 透给用户：那是领域层不变量违约的技术文本
                    // （"服药剂量必须大于 0，当前 -1.0"），用户看不懂，翻译它也不划算。
                    // 诊断信息进日志（设置页可导出），界面只说"失败了、可以重试"。
                    AppLog.e("DoseRecordDetailVM", "action failed res=$failRes", t)
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            error = app.getString(failRes)
                        )
                    }
                }
        }
    }

    fun consumeError() = _uiState.update { it.copy(error = null) }
}
