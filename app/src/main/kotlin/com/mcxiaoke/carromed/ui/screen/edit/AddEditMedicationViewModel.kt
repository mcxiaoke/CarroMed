package com.mcxiaoke.carromed.ui.screen.edit

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcxiaoke.carromed.R
import com.mcxiaoke.carromed.core.alarm.AlarmReconciler
import com.mcxiaoke.carromed.core.alarm.ReconcileWorker
import com.mcxiaoke.carromed.core.domain.model.Dose
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.model.PolicyType
import com.mcxiaoke.carromed.core.domain.engine.SlotProjectionEngine
import com.mcxiaoke.carromed.core.domain.model.MedicationCategory
import com.mcxiaoke.carromed.core.domain.model.MedicationForm
import com.mcxiaoke.carromed.core.domain.model.SlotLabel
import com.mcxiaoke.carromed.core.domain.AppLog
import com.mcxiaoke.carromed.core.domain.service.DoseTrackingService
import com.mcxiaoke.carromed.core.domain.service.MedicationAdminService
import com.mcxiaoke.carromed.ui.component.DecimalInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 表单模式
 *
 * - [FULL]      新增药品：一次性填写"药品信息 + 提醒计划 + 初始库存"，低门槛录入
 * - [INFO_ONLY] 编辑药品：**只**编辑药品信息维度，提醒计划与库存各有独立页面
 *
 * 这条区分直接对应用户诉求："添加药品"与"编辑药品信息"复用同一界面，
 * 而"修改提醒"必须走另一套界面 (ReminderSettingsScreen)。
 */
enum class AddEditMode { FULL, INFO_ONLY }

/**
 * 一个提醒时点的草稿。
 *
 * ## [dose] 为什么是 `String` 而不是 `Float`（M2-1）
 *
 * 剂量是全 App 后果最重的一个输入框，而 `Float` 在这里同时做不到三件事：
 *
 * 1. **表达不了中间态**。用户输入 `0.` 时 `toFloatOrNull()` 返回 0，
 *    `?: 0f` 兜底后 TextField 立刻显示 `0` —— **小数点打不出来，0.5 片不可录入**。
 * 2. **区分不了"清空"和"填 0"**。两者都归一成 0，于是清空一次就写 0 剂量，
 *    0 剂量**可以保存** ⇒ 闹钟照响、打卡照记、**库存永不扣**。
 * 3. **拦不住负号**。粘贴 `-2` 得到 -2 片，打卡时 `-finalDose` 变成 **+2** ——
 *    一次打卡给库存**加** 2 片。
 *
 * 改成 `String` 后由 [DecimalInput] 统一做字符过滤与"必须 > 0"的解析，
 * 与同文件里的 `defaultDose` 口径一致。
 */
data class TimeSlotDraft(
    val time: String = "08:30",
    val dose: String = "1",
    val label: String = SlotLabel.GENERIC.name
) {
    /** 合法剂量；null 表示"空 / 0 / 无法解析"，保存前必须拦下 */
    fun parsedDose(): Float? = DecimalInput.parsePositive(dose)

    /**
     * 合法时点；null 表示"空 / 不是 `HH:mm`"，保存前必须拦下。
     *
     * ⚠️ 为什么光靠 `timeSlots.isEmpty()` 不够（M7-9）：
     * 列表里有**一个** `time = ""` 的槽位时，`isEmpty()` 返回 false，
     * 于是校验放行、空时点一路写进 `policy_times.time_of_day`。
     * 领域层 [com.mcxiaoke.carromed.core.domain.service.MedicationAdminService]
     * 只查重复与剂量，不查时点格式 —— 而 `SlotProjectionEngine` 对坏串的
     * 处理是**回退到 08:00**。合起来的效果是：用户清空了时间框，
     * App 安静地给这味药排了每天 08:00 的闹钟，界面上写的是空。
     *
     * 判据用 `LocalTime.parse` 本身，与引擎的回退判据**同源** ——
     * 否则这里放行、那里回退，两处对"什么算坏串"的理解会漂移。
     */
    fun parsedTime(): java.time.LocalTime? =
        runCatching {
            java.time.LocalTime.parse(
                time.trim(),
                com.mcxiaoke.carromed.core.domain.engine.SlotProjectionEngine.TIME_FORMATTER
            )
        }.getOrNull()
}

data class AddEditUiState(
    val medId: Long? = null,
    val mode: AddEditMode = AddEditMode.FULL,
    val isLoading: Boolean = false,

    // ---- 药品信息维度 ----
    // category / form 存词表 key（枚举 name，B3），显示时经 MedVocab 解析；
    // unit 是用户内容（显示文本），保持中文默认值不动。
    val name: String = "",
    val alias: String = "",
    val category: String = MedicationCategory.COMMON.name,
    val form: String = MedicationForm.TABLET.name,
    val unit: String = "片",
    val colorHex: String = "#2563EB",
    val defaultDose: String = "1",
    val description: String = "",
    val precautions: List<String> = emptyList(),
    val noticeShort: String = "",
    val expiryDate: String = "",

    // ---- 提醒计划维度 (仅 FULL 模式) ----
    val policyType: PolicyType = PolicyType.DAILY,
    val intervalDays: Int = 2,
    val daysOfWeek: List<Int> = listOf(1, 3, 5),
    val cycleOnDays: Int = 21,
    val cycleOffDays: Int = 7,
    val startDate: String = LocalDate.now().format(SlotProjectionEngine.DATE_FORMATTER),
    val endDate: String? = null,
    val timeSlots: List<TimeSlotDraft> = listOf(TimeSlotDraft("08:30", "1", SlotLabel.GENERIC.name)),

    // ---- 库存维度 (仅 FULL 模式) ----
    val currentStock: String = "",

    val isSaving: Boolean = false,
    val error: String? = null
) {
    val isEdit: Boolean get() = medId != null && medId > 0L

    /** 顶部栏标题的资源 ID，Screen 侧用 `stringResource(titleRes)` 解析 */
    @get:StringRes
    val titleRes: Int
        get() = when {
            isEdit && mode == AddEditMode.INFO_ONLY -> R.string.medit_title_edit_medication_info
            isEdit -> R.string.medit_title_edit_medication
            else -> R.string.medit_title_add_medication
        }
}

/**
 * 表单可选枚举值 (集中一处，避免 UI 里散落硬编码)
 *
 * ## B3 词表 key 化后的口径
 *
 * - [CATEGORIES] / [FORMS] / [TIME_LABELS]：存库值 = 枚举 name（稳定 key），
 *   显示时由 UI 经 `MedVocab` 解析成当前 locale 的资源文案；未知 key（自由文本）
 *   由调用方原样显示。
 * - [UNITS] / [PRECAUTION_PRESETS]：**用户内容**，选中后存的是显示文本而非 key ——
 *   这里只提供预设的 `@StringRes`，渲染时 `stringResource` 解析。
 */
object MedicationFormOptions {
    val CATEGORIES = MedicationCategory.entries.map { it.name }
    val FORMS = MedicationForm.entries.map { it.name }

    /** 单位预设（资源 ID）。"ml" 是纯符号不进词表，由 Screen 侧原样追加。 */
    val UNITS: List<Int> = listOf(
        R.string.vocab_unit_tablet,
        R.string.vocab_unit_capsule,
        R.string.vocab_unit_sachet,
        R.string.vocab_unit_packet,
        R.string.vocab_unit_ampoule,
        R.string.vocab_unit_pill,
        R.string.vocab_unit_patch,
        R.string.vocab_unit_drop,
        R.string.vocab_unit_bottle
    )

    /** 不进词表的自由单位（保持原样，用户内容） */
    val UNITS_RAW = listOf("ml")

    val COLORS = listOf(
        "#2563EB", "#10B981", "#F59E0B",
        "#8B5CF6", "#EF4444", "#0EA5E9"
    )

    /** 注意事项预设（资源 ID）。选中后把显示文本存进档案（用户内容）。 */
    val PRECAUTION_PRESETS: List<Int> = listOf(
        R.string.precaution_after_meal,
        R.string.precaution_before_meal,
        R.string.precaution_with_meal,
        R.string.precaution_fasting,
        R.string.precaution_swallow_whole,
        R.string.precaution_no_grapefruit,
        R.string.precaution_no_milk,
        R.string.precaution_refrigerate,
        R.string.precaution_keep_from_light,
        R.string.precaution_no_driving,
        R.string.precaution_no_aluminum_antacid,
        R.string.precaution_caution_liver_kidney
    )
    /**
     * 服药与用餐关系。
     *
     * 这是主流吃药 App 的核心枚举（MyTherapy / Medisafe / 药准时都有），
     * 会显示在通知正文与今日清单上 —— 告诉用户"这顿该饭前还是饭后吃"，
     * 比单纯一个时间点有用得多。存库值 = [SlotLabel] 的 name。
     */
    val TIME_LABELS = SlotLabel.entries.map { it.name }
}

class AddEditMedicationViewModel(
    application: Application,
    private val medId: Long?,
    private val mode: AddEditMode = if (medId != null && medId > 0) AddEditMode.INFO_ONLY else AddEditMode.FULL
) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val medDao = db.medicationDao()
    private val policyDao = db.schedulePolicyDao()
    private val adminService = MedicationAdminService(db)
    private val trackingService = DoseTrackingService(db)

    private val _uiState = MutableStateFlow(
        AddEditUiState(
            medId = medId,
            mode = mode,
            isLoading = medId != null && medId > 0
        )
    )
    val uiState: StateFlow<AddEditUiState> = _uiState.asStateFlow()

    init {
        if (medId != null && medId > 0) {
            loadExistingMedication(medId)
        }
    }

    private fun loadExistingMedication(id: Long) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val med = medDao.getMedicationById(id)
            if (med == null) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = app.getString(R.string.medit_error_med_not_found)
                )
                return@launch
            }
            val policy = policyDao.getActivePolicyForMedication(id)
            val times = if (policy != null) policyDao.getTimesForPolicy(policy.id) else emptyList()

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                name = med.name,
                alias = med.alias.orEmpty(),
                category = med.category,
                form = med.form,
                unit = med.unit,
                colorHex = med.colorHex,
                defaultDose = trimFloat(Dose(med.defaultDose).asFloat),
                description = med.description,
                precautions = med.precautions,
                noticeShort = med.noticeShort,
                expiryDate = med.expiryDate,
                policyType = policy?.policyType ?: PolicyType.DAILY,
                intervalDays = (policy?.intervalDays ?: 2).coerceIn(2, 30),
                daysOfWeek = policy?.daysOfWeek?.ifEmpty { null } ?: listOf(1, 3, 5),
                cycleOnDays = (policy?.cycleOnDays ?: 21).coerceAtLeast(1),
                cycleOffDays = (policy?.cycleOffDays ?: 7).coerceAtLeast(0),
                startDate = policy?.startDate ?: LocalDate.now().format(SlotProjectionEngine.DATE_FORMATTER),
                endDate = policy?.endDate,
                timeSlots = times
                    .sortedBy { it.sortOrder }
                    .map { TimeSlotDraft(it.timeOfDay, trimFloat(Dose(it.doseAmount).asFloat), it.label) }
                    .ifEmpty { listOf(TimeSlotDraft()) },
                // ⚠️ 编辑模式不预填库存余额：账面是台账聚合值，在本页编辑它
                // 语义上等于"直接改账面"，必须走盘点校准（库存页）或补药入库。
                // 这里保持空字符串，保存时不会产生任何库存写入。
                // 预警线同样不进本页：唯一写入口在库存页（ocsbf P1-2 / DB C-14）。
                currentStock = ""
            )
        }
    }

    // ---------------- 药品信息维度 ----------------

    fun onNameChange(v: String) = mutate(clearErrorFor = NAME_ERROR) { it.copy(name = v) }
    fun onAliasChange(v: String) = mutate { it.copy(alias = v) }
    fun onCategoryChange(v: String) = mutate { it.copy(category = v) }
    fun onFormChange(v: String) = mutate { it.copy(form = v) }
    fun onUnitChange(v: String) = mutate { it.copy(unit = v) }
    fun onColorChange(v: String) = mutate { it.copy(colorHex = v) }
    fun onDefaultDoseChange(v: String) = mutate { it.copy(defaultDose = v) }
    fun onDescriptionChange(v: String) = mutate { it.copy(description = v) }
    fun onNoticeShortChange(v: String) = mutate { it.copy(noticeShort = v) }
    fun onExpiryDateChange(v: String) = mutate { it.copy(expiryDate = v) }

    fun onPrecautionToggle(tag: String) = mutate(clearErrorFor = NAME_ERROR) { s ->
        val next = if (tag in s.precautions) s.precautions - tag else (s.precautions + tag).sorted()
        s.copy(precautions = next)
    }

    fun onPrecautionAdd(custom: String) {
        val tag = custom.trim()
        if (tag.isEmpty()) return
        mutate { s ->
            if (s.precautions.any { it == tag }) s else s.copy(precautions = (s.precautions + tag).sorted())
        }
    }

    fun onPrecautionRemove(tag: String) = mutate { s -> s.copy(precautions = s.precautions - tag) }

    // ---------------- 提醒计划维度 ----------------

    fun onPolicyTypeChange(type: PolicyType) = mutate { it.copy(policyType = type) }
    fun onIntervalDaysChange(days: Int) = mutate { it.copy(intervalDays = days.coerceIn(2, 30)) }
    fun onCycleDaysChange(on: Int) = mutate { it.copy(cycleOnDays = on.coerceIn(1, 90)) }
    fun onCycleOffDaysChange(off: Int) = mutate { it.copy(cycleOffDays = off.coerceIn(0, 30)) }
    fun onStartDateChange(v: String) = mutate { it.copy(startDate = v) }
    fun onEndDateChange(v: String?) = mutate { it.copy(endDate = v) }

    fun onToggleDayOfWeek(day: Int) = mutate(clearErrorFor = DOW_ERROR) { s ->
        val current = s.daysOfWeek
        val next = if (day in current) current - day else (current + day).sorted()
        s.copy(daysOfWeek = next)
    }

    fun addTimeSlot() = mutate { s ->
        val last = s.timeSlots.maxByOrNull { it.time }?.time ?: "08:30"
        s.copy(timeSlots = s.timeSlots + TimeSlotDraft(nextSlotTime(last), s.defaultDose, SlotLabel.GENERIC.name))
    }

    /**
     * 一键铺排"每天 N 次"的均分时点。
     *
     * 主流 App 的标准做法 (吃药啦 / 药准时 / Medisafe 都有)：用户只需说"每天三次"，
     * 系统在 07:00–21:00 之间均分出 3 个时点，再按需微调。避免用户自己算时间。
     * 保留已有的时段标签习惯：均分点按落在早/午/晚自动打标签。
     *
     * 剂量统一取 `defaultDose`（M4-5）。旧实现写死 1.0f 而本函数之外的
     * 另一处铺排取 `defaultDose` —— 两处取值来源不同 ⇒ 用户设了"每次 2 片"，
     * 两个页面的"一键铺排"产出**不同剂量**，且都显示成功。
     * `defaultDose` 非法/为空时回落到 1，并保持可编辑（保存时仍会被校验）。
     */
    fun spreadTimes(count: Int) = mutate { s ->
        val n = count.coerceIn(1, 8)
        val startMinutes = 7 * 60
        val endMinutes = 21 * 60
        val step = if (n == 1) 0 else (endMinutes - startMinutes) / (n - 1)
        val dose = s.defaultDose.takeIf { DecimalInput.parsePositive(it) != null } ?: "1"
        val slots = (0 until n).map { i ->
            val total = if (n == 1) 8 * 60 else startMinutes + step * i
            TimeSlotDraft(
                // Locale.ROOT（orsbf P1-18）：写进 policy_times.time_of_day，必须可被投影引擎 ISO 解析
                time = String.format(java.util.Locale.ROOT, "%02d:%02d", total / 60, total % 60),
                dose = dose,
                label = guessLabel(total)
            )
        }
        s.copy(timeSlots = slots)
    }

    /**
     * 剂量输入。
     *
     * 收 `String` 而不是 `Float`（M2-1）：`Float` 无法表达"正在输入 `0.`"这个中间态，
     * 于是小数点打不出来；也无法区分"清空"与"填 0"，清空一次就写 0 剂量。
     * 字符过滤在 [DecimalInput.filter]，`> 0` 的判定在保存时（[save]）。
     */
    fun updateTimeSlot(index: Int, time: String? = null, doseText: String? = null, label: String? = null) =
        mutate { s ->
            if (index !in s.timeSlots.indices) return@mutate s
            val cur = s.timeSlots[index]
            s.copy(
                timeSlots = s.timeSlots.toMutableList().also { list ->
                    list[index] = cur.copy(
                        time = time ?: cur.time,
                        dose = doseText?.let { DecimalInput.filter(it) } ?: cur.dose,
                        label = label ?: cur.label
                    )
                }
            )
        }

    fun removeTimeSlot(index: Int) = mutate { s ->
        if (s.timeSlots.size <= 1 || index !in s.timeSlots.indices) s
        else s.copy(timeSlots = s.timeSlots.toMutableList().also { it.removeAt(index) })
    }

    // ---------------- 库存维度 ----------------

    fun onCurrentStockChange(v: String) = mutate { it.copy(currentStock = v) }

    // ---------------- 保存 ----------------

    fun save(onSuccess: (Long) -> Unit) {
        val s = _uiState.value
        val app = getApplication<Application>()

        // ⭐ 同步前置的"正在保存"闸门（M2-4）。
        //
        // 旧实现把 `isSaving = true` 写在 `viewModelScope.launch { ... }` 的**协程体内**。
        // 协程体要等到获得调度机会才执行，而 `launch` 默认 `Dispatchers.Main.immediate`
        // 在事件回调里**未必立刻跑** —— 于是同一帧内连点两次「保存」，
        // 两次都读到 `isSaving == false`、都以 `medId = null` 走 insert ⇒ **插了两行药**。
        //
        // 双击保护必须在**读取状态之前**同步置位，不能寄望于协程体内的赋值。
        if (s.isSaving) return

        if (s.name.isBlank()) {
            _uiState.value = s.copy(error = app.getString(NAME_ERROR))
            return
        }
        val policyRequired = s.mode == AddEditMode.FULL
        if (policyRequired && s.policyType == PolicyType.DAYS_OF_WEEK && s.daysOfWeek.isEmpty()) {
            _uiState.value = s.copy(error = app.getString(DOW_ERROR))
            return
        }
        // ⚠️ 两个 `if` 的条件互斥且体完全相同，等价于
        // `policyRequired && s.timeSlots.isEmpty()`（M7-9）。
        // 更要紧的是它与**提醒页入口口径不一**：
        // `ReminderSettingsViewModel` 写的是 `policyType != PRN && times.isEmpty()`，
        // 而 PRN 在领域层（`SlotProjectionEngine`）根本不产生槽位。
        //
        // 于是同一份"按需服用"配置，从新建页存要按时段，
        // 从提醒页存不按时段也能过 —— 用户改个入口就换一套校验规则。
        // 这里对齐提醒页：PRN 不要求时段。
        if (policyRequired && s.policyType != PolicyType.PRN) {
            // ⚠️ 判据是"有没有**合法**时点"，不是"列表空不空"（M7-9）。
            // 详见 [TimeSlotDraft.parsedTime]：一个 `time = ""` 的槽位
            // 能让 `isEmpty()` 放行，随后被引擎回退成 08:00。
            if (s.timeSlots.none { it.parsedTime() != null }) {
                _uiState.value = s.copy(error = app.getString(TIME_ERROR))
                return
            }
            // 逐条指出是哪一格没填 —— 六格表单只说"请设置提醒时点"，
            // 用户不知道要改哪个框。半截输入（"08:"）同样在这里被点名。
            val badTime = s.timeSlots.firstOrNull { it.parsedTime() == null }
            if (badTime != null) {
                _uiState.value = s.copy(
                    error = if (badTime.time.isBlank()) {
                        app.getString(
                            R.string.medit_error_time_blank,
                            s.timeSlots.indexOf(badTime) + 1
                        )
                    } else {
                        app.getString(
                            R.string.medit_error_time_invalid,
                            s.timeSlots.indexOf(badTime) + 1,
                            badTime.time
                        )
                    }
                )
                return
            }
        }
        // 重复时点在领域层会被 require 拒绝（事务回滚）；这里前置拦下，
        // 把它变成表单台词而不是一个没接住的异常（P1）
        if (policyRequired) {
            val timeKeys = s.timeSlots.map { it.time.trim() }
            if (timeKeys.size != timeKeys.distinct().size) {
                _uiState.value = s.copy(error = app.getString(DUPLICATE_TIME_ERROR))
                return
            }
            // ⭐ 剂量必须**逐条**为正（M2-1）。
            //
            // 0 剂量是一条完整的数据损坏路径：闹钟照响、打卡照记、**库存永不扣**。
            // 报错必须指出**哪一条**时点 —— 六个时点的表单只说"剂量非法"，
            // 用户根本不知道要改哪个框。
            val badDose = s.timeSlots.firstOrNull { it.parsedDose() == null }
            if (badDose != null) {
                _uiState.value = s.copy(
                    error = app.getString(R.string.medit_error_dose_invalid, badDose.time)
                )
                return
            }
        }

        // ⭐ 默认剂量必须**显式合法**（M8）：旧实现在保存时
        // `parsePositive(s.defaultDose) ?: 1.0f` —— 填 "0" 或清空后保存
        // "成功"，但落库值被静默改成 1.0，与表单显示不一致且无任何提示。
        if (DecimalInput.parsePositive(s.defaultDose) == null) {
            _uiState.value = s.copy(error = app.getString(DEFAULT_DOSE_ERROR))
            return
        }
        // 初始库存不得静默回落（L9）：非法输入以前被 toFloatOrNull
        // 悄悄折算成 0 —— 库存不建档，用户毫不知情。
        // （预警线不在本页：唯一写入口是库存页，ocsbf P1-2 / DB C-14。）
        if (!s.isEdit && s.currentStock.isNotBlank() && s.currentStock.toFloatOrNull() == null) {
            _uiState.value = s.copy(error = app.getString(R.string.medit_error_stock_invalid))
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, error = null)

            // ⚠️ 整个保存链包在 runCatching 里（M2-3）。
            //
            // 领域层会在多种情况下抛 `IllegalArgumentException`
            // （"Medication not found" / 重复时点 / 剂量非法…），而 ViewModel 里
            // 未捕获的异常会一路打到主线程 → **App 崩溃**，同时 `isSaving`
            // 永远停在 true ⇒ 保存按钮**永久禁用**，用户除了杀进程没有出路。
            //
            // 「补录」页的药品列表是 init 时的快照，表单开着删药完全可达，
            // 所以这不是理论风险。
            runCatching {
                saveInternal(s, onSuccess)
            }.onFailure { t ->
                // 不透出 t.message：领域层抛的是不变量违约文本，用户看不懂，
                // 翻译它也不划算。细节进日志，界面只说"保存失败"。
                AppLog.e("AddEditMedVM", "save failed med=${s.medId}", t)
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    error = app.getString(R.string.medit_error_save_failed)
                )
            }
        }
    }

    private suspend fun saveInternal(s: AddEditUiState, onSuccess: (Long) -> Unit) {
        val policyRequired = s.mode == AddEditMode.FULL
        run {
            val stockFloat = s.currentStock.toFloatOrNull() ?: 0f

            // 1) 药品档案 (新增或编辑，绝不整行覆盖状态位)。
            // 预警线不在本页所有权内：ProfileDraft 已无 minStockAlert 字段，
            // 唯一写入口是库存页 updateMinStockAlert（ocsbf P1-2 / DB C-14）。
            val medId = adminService.saveProfile(
                MedicationAdminService.ProfileDraft(
                    medId = s.medId ?: 0L,
                    name = s.name,
                    alias = s.alias,
                    category = s.category,
                    form = s.form,
                    unit = s.unit,
                    colorHex = s.colorHex,
                    defaultDose = DecimalInput.parsePositive(s.defaultDose) ?: 1.0f,
                    description = s.description,
                    precautions = s.precautions,
                    noticeShort = s.noticeShort,
                    expiryDate = s.expiryDate
                )
            )

            // 2) 提醒计划 —— ⚠️ **新建时不再写**（2026-09-29 UX 改造）。
            //
            // 旧实现在这里无条件 `saveReminderPolicy`，用的是表单默认值
            // （DAILY + 08:30 + 1 片）。表单里那段 UI 拿掉之后，这个默认值
            // 就变成了**用户从没选过、却已经生效**的计划：
            // 药箱和详情页都显示"每天 08:30"，而他根本不知道。
            //
            // 这比"没有计划"更糟 —— 因为它在**看起来一切正常**的前提下说谎。
            // 正确做法是不写：新药没有 active policy，
            // 详情页的「提醒设置」行会显式提示"尚未设置服药计划"，
            // 用户从那里点进去自己配（`ReminderSettingsScreen` 一直都在）。
            //
            // 编辑模式本来就不写计划（由"提醒设置"页负责，避免两个入口互相覆盖），
            // 所以这里两条路径统一：**本类不负责写计划**。
            // （原为"起始日顺延"保留的 `effectiveStartDate` / `isBeforeNow` 死代码
            //   已删除 —— 它们自新建页移除计划配置后不再参与任何计算，
            //   留着会被后继读者当活代码改。将来若恢复"新建页可配计划"，
            //   从 git 历史里找这段，连同 ReminderSettingsScreen 的对应校验。）

            // 3) 初始库存建档 —— **仅新增时**。
            // 编辑路径绝不碰库存：账面是台账聚合值，改它必须走盘点校准或补药入库。
            // （编辑表单也不再预填库存，故此处 stockFloat 恒为 0，显式加 !s.isEdit
            //   是为了让"编辑永远不写库存"这条规则在代码里一目了然。）
            if (!s.isEdit && stockFloat > 0f) {
                trackingService.setStockTracking(medId, true, stockFloat)
            }

            // 4) 平滑重排未来排班 (历史事实不可变)
            //
            // 新建的药此刻**还没有任何计划**，所以这里实际是空操作。
            // 保留调用是为了编辑路径：改档案后重排一次，保证与现有计划一致。
            //
            // 本路径**不写计划**，投影结果与既有槽位一致，重排前不会发生删行 ——
            // 无需 presnap 快照（改计划的 ReminderSettings 路径才需要）。
            trackingService.reconcileSchedule(medId)

            // 5) 按最新计划重排全部精确闹钟
            //
            // 重排失败不能静默（round2 N11-g 残留）：药品和计划已保存，但精确闹钟
            // 没排上 ⇒ 提醒可能不响。与 6553081 批次同口径：长 Toast 顶住导航后的
            // 下一屏 + ERROR 日志，并交 ReconcileWorker 立即补一轮对账兜底。
            val appContext = getApplication<Application>()
            val rescheduled = runCatching { AlarmReconciler.rescheduleAll(appContext, db) }
                .onFailure { AppLog.e("AddEditMedVM", "rescheduleAll after save failed med=$medId", it) }
                .isSuccess
            if (!rescheduled) {
                runCatching { ReconcileWorker.enqueueOneShot(appContext) }
                    .onFailure { AppLog.e("AddEditMedVM", "enqueueOneShot fallback failed med=$medId", it) }
                android.widget.Toast.makeText(
                    appContext,
                    appContext.getString(R.string.medit_saved_schedule_failed),
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }

            _uiState.value = _uiState.value.copy(isSaving = false, medId = medId)
            onSuccess(medId)
        }
    }

    // ---------------- 内部工具 ----------------

    private inline fun mutate(
        clearErrorFor: Int? = null,
        crossinline block: (AddEditUiState) -> AddEditUiState
    ) {
        val cur = _uiState.value
        // clearErrorFor 是报错文案的资源 ID：仅当当前报错正是同一句时才清掉，
        // 改名不该顺手抹掉"剂量无效"之类的别的报错。
        val cleared = if (clearErrorFor != null && cur.error != null &&
            cur.error == getApplication<Application>().getString(clearErrorFor)
        ) null else cur.error
        _uiState.value = block(cur.copy(error = cleared))
    }

    private fun trimFloat(v: Float): String = if (v % 1f == 0f) v.toInt().toString() else v.toString()

    private fun nextSlotTime(after: String): String {
        val parts = after.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: 8
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 30
        val total = (h * 60 + m + 240) % (24 * 60)
        return String.format(java.util.Locale.ROOT, "%02d:%02d", total / 60, total % 60)
    }

    /** 按时刻猜一个粗粒度时段标签，用户可再改 */
    /** 按时刻猜一个粗粒度"服药与用餐关系"（存 [SlotLabel] key），用户可再改 */
    private fun guessLabel(minutesOfDay: Int): String = when (minutesOfDay) {
        in 5 * 60 until 6 * 60 -> SlotLabel.FASTING.name
        in 6 * 60 until 9 * 60 -> SlotLabel.BEFORE_MEAL.name
        in 9 * 60 until 10 * 60 -> SlotLabel.WITH_MEAL.name
        in 10 * 60 until 13 * 60 -> SlotLabel.BEFORE_MEAL.name
        in 13 * 60 until 15 * 60 -> SlotLabel.WITH_MEAL.name
        in 15 * 60 until 18 * 60 -> SlotLabel.BEFORE_MEAL.name
        in 18 * 60 until 21 * 60 -> SlotLabel.AFTER_MEAL.name
        else -> SlotLabel.BEDTIME.name
    }

    companion object {
        /** 校验报错文案的资源 ID（B2 文案抽取），文案见 strings_medit.xml */
        val NAME_ERROR = R.string.medit_error_name_required
        val DOW_ERROR = R.string.medit_error_dow_required
        val TIME_ERROR = R.string.medit_error_time_required
        val DUPLICATE_TIME_ERROR = R.string.medit_error_duplicate_time
        val DEFAULT_DOSE_ERROR = R.string.medit_error_default_dose_invalid
    }
}
