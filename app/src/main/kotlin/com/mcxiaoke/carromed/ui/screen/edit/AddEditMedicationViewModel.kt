package com.mcxiaoke.carromed.ui.screen.edit

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcxiaoke.carromed.core.alarm.AlarmReconciler
import com.mcxiaoke.carromed.core.domain.model.Dose
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.model.PolicyType
import com.mcxiaoke.carromed.core.domain.engine.SlotProjectionEngine
import com.mcxiaoke.carromed.core.domain.service.DoseTrackingService
import com.mcxiaoke.carromed.core.domain.service.MedicationAdminService
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

data class TimeSlotDraft(
    val time: String = "08:30",
    val dose: Float = 1.0f,
    val label: String = "服药时段"
)

data class AddEditUiState(
    val medId: Long? = null,
    val mode: AddEditMode = AddEditMode.FULL,
    val isLoading: Boolean = false,

    // ---- 药品信息维度 ----
    val name: String = "",
    val alias: String = "",
    val category: String = "常备药",
    val form: String = "片剂",
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
    val timeSlots: List<TimeSlotDraft> = listOf(TimeSlotDraft("08:30", 1.0f, "服药时段")),

    // ---- 库存维度 (仅 FULL 模式) ----
    val currentStock: String = "",
    val minStockAlert: String = "10",

    val isSaving: Boolean = false,
    val error: String? = null
) {
    val isEdit: Boolean get() = medId != null && medId > 0L
    val title: String
        get() = when {
            isEdit && mode == AddEditMode.INFO_ONLY -> "编辑药品信息"
            isEdit -> "编辑药品"
            else -> "添加药品"
        }
}

/** 表单可选枚举值 (集中一处，避免 UI 里散落硬编码) */
object MedicationFormOptions {
    val CATEGORIES = listOf("常备药", "慢病处方", "处方药 · 免疫", "抗生素", "激素类", "营养保健", "其他")
    val FORMS = listOf("片剂", "胶囊", "软胶囊", "颗粒剂", "口服液", "外用", "滴剂", "喷雾剂", "贴剂")
    val UNITS = listOf("片", "粒", "袋", "包", "支", "丸", "贴", "滴", "ml", "支装")
    val COLORS = listOf(
        "#2563EB", "#10B981", "#F59E0B",
        "#8B5CF6", "#EF4444", "#0EA5E9"
    )
    val PRECAUTION_PRESETS = listOf(
        "饭后服用", "饭前服用", "随餐服用", "空腹服用",
        "整粒吞服禁嚼碎", "严禁与葡萄柚同食", "避免与牛奶同服",
        "需冷藏保存", "避光保存", "服后勿驾车",
        "不可与含铝抗酸药同服", "肝肾功能不全慎用"
    )
    /**
     * 服药与用餐关系。
     *
     * 这是主流吃药 App 的核心枚举（MyTherapy / Medisafe / 药准时都有），
     * 会显示在通知正文与今日清单上 —— 告诉用户"这顿该饭前还是饭后吃"，
     * 比单纯一个时间点有用得多。
     */
    val TIME_LABELS = listOf(
        "服药时段",
        "空腹服用", "饭前服用", "随餐服用", "餐后服用", "睡前"
    )
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
            val med = medDao.getMedicationById(id)
            if (med == null) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = "药品不存在或已被删除")
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
                    .map { TimeSlotDraft(it.timeOfDay, Dose(it.doseAmount).asFloat, it.label) }
                    .ifEmpty { listOf(TimeSlotDraft("08:30", 1.0f, "服药时段")) },
                // ⚠️ 编辑模式不预填库存余额：账面是台账聚合值，在本页编辑它
                // 语义上等于"直接改账面"，必须走盘点校准（库存页）或补药入库。
                // 这里保持空字符串，保存时不会产生任何库存写入。
                currentStock = "",
                // ⚠️ 预警线不要用业务默认值"10"污染用户数据：minStockAlert = 0
                // 在本项目里语义是"关闭低库存告警"，预填 10 会让用户在只改药名时
                // 意外把已关闭的告警又打开。
                minStockAlert = trimFloat(Dose(med.minStockAlert).asFloat)
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
        s.copy(timeSlots = s.timeSlots + TimeSlotDraft(nextSlotTime(last), 1.0f, "服药时段"))
    }

    /**
     * 一键铺排"每天 N 次"的均分时点。
     *
     * 主流 App 的标准做法 (吃药啦 / 药准时 / Medisafe 都有)：用户只需说"每天三次"，
     * 系统在 07:00–21:00 之间均分出 3 个时点，再按需微调。避免用户自己算时间。
     * 保留已有的时段标签习惯：均分点按落在早/午/晚自动打标签。
     */
    fun spreadTimes(count: Int) = mutate { s ->
        val n = count.coerceIn(1, 8)
        val startMinutes = 7 * 60
        val endMinutes = 21 * 60
        val step = if (n == 1) 0 else (endMinutes - startMinutes) / (n - 1)
        val dose = s.defaultDose.toFloatOrNull() ?: 1.0f
        val slots = (0 until n).map { i ->
            val total = if (n == 1) 8 * 60 else startMinutes + step * i
            TimeSlotDraft(
                time = String.format(java.util.Locale.getDefault(), "%02d:%02d", total / 60, total % 60),
                dose = dose,
                label = guessLabel(total)
            )
        }
        s.copy(timeSlots = slots)
    }

    fun updateTimeSlot(index: Int, time: String? = null, dose: Float? = null, label: String? = null) =
        mutate { s ->
            if (index !in s.timeSlots.indices) return@mutate s
            val cur = s.timeSlots[index]
            s.copy(
                timeSlots = s.timeSlots.toMutableList().also { list ->
                    list[index] = cur.copy(
                        time = time ?: cur.time,
                        dose = dose ?: cur.dose,
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
    fun onMinStockAlertChange(v: String) = mutate { it.copy(minStockAlert = v) }

    // ---------------- 保存 ----------------

    fun save(onSuccess: (Long) -> Unit) {
        val s = _uiState.value
        if (s.name.isBlank()) {
            _uiState.value = s.copy(error = NAME_ERROR)
            return
        }
        val policyRequired = s.mode == AddEditMode.FULL
        if (policyRequired && s.policyType == PolicyType.DAYS_OF_WEEK && s.daysOfWeek.isEmpty()) {
            _uiState.value = s.copy(error = DOW_ERROR)
            return
        }
        if (policyRequired && s.policyType != PolicyType.PRN && s.timeSlots.isEmpty()) {
            _uiState.value = s.copy(error = TIME_ERROR)
            return
        }
        if (policyRequired && s.policyType == PolicyType.PRN && s.timeSlots.isEmpty()) {
            _uiState.value = s.copy(error = TIME_ERROR)
            return
        }
        // 重复时点在领域层会被 require 拒绝（事务回滚）；这里前置拦下，
        // 把它变成表单台词而不是一个没接住的异常（P1）
        if (policyRequired) {
            val timeKeys = s.timeSlots.map { it.time.trim() }
            if (timeKeys.size != timeKeys.distinct().size) {
                _uiState.value = s.copy(error = DUPLICATE_TIME_ERROR)
                return
            }
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, error = null)

            val stockFloat = s.currentStock.toFloatOrNull() ?: 0f
            val alertFloat = s.minStockAlert.toFloatOrNull() ?: 0f

            // 起始日修正：新增药品时，若所有时点都已早于当前时间，
            // 排进今天会让"今天这一剂一创建就是逾期"，新用户看到的依从率直接是 0%。
            // 此时把起始日推到明天，从下一次正常服药开始，不冤枉用户。
            val effectiveStartDate = if (policyRequired && !s.isEdit && s.startDate == todayDate()) {
                if (s.timeSlots.all { it.isBeforeNow() }) tomorrowDate() else s.startDate
            } else {
                s.startDate
            }

            // 1) 药品档案 (新增或编辑，绝不整行覆盖状态位)
            val medId = adminService.saveProfile(
                MedicationAdminService.ProfileDraft(
                    medId = s.medId ?: 0L,
                    name = s.name,
                    alias = s.alias,
                    category = s.category,
                    form = s.form,
                    unit = s.unit,
                    colorHex = s.colorHex,
                    defaultDose = s.defaultDose.toFloatOrNull() ?: 1.0f,
                    description = s.description,
                    precautions = s.precautions,
                    noticeShort = s.noticeShort,
                    expiryDate = s.expiryDate,
                    minStockAlert = alertFloat
                )
            )

            // 2) 提醒计划 (仅新增模式写；编辑模式由"提醒设置"页负责，避免两个入口互相覆盖)
            if (policyRequired) {
                adminService.saveReminderPolicy(
                    medicationId = medId,
                    draft = MedicationAdminService.PolicyDraft(
                        policyType = s.policyType,
                        intervalDays = s.intervalDays,
                        daysOfWeek = s.daysOfWeek,
                        cycleOnDays = s.cycleOnDays,
                        cycleOffDays = s.cycleOffDays,
                        startDate = effectiveStartDate,
                        endDate = s.endDate,
                        times = s.timeSlots.map {
                            MedicationAdminService.TimeDraft(it.time, it.dose, it.label)
                        }
                    )
                )
            }

            // 3) 初始库存建档 —— **仅新增时**。
            // 编辑路径绝不碰库存：账面是台账聚合值，改它必须走盘点校准或补药入库。
            // （编辑表单也不再预填库存，故此处 stockFloat 恒为 0，显式加 !s.isEdit
            //   是为了让"编辑永远不写库存"这条规则在代码里一目了然。）
            if (!s.isEdit && stockFloat > 0f) {
                trackingService.setStockTracking(medId, true, stockFloat)
            }

            // 4) 平滑重排未来排班 (历史事实不可变)
            trackingService.reconcileSchedule(medId)

            // 5) 按最新计划重排全部精确闹钟
            runCatching { AlarmReconciler.rescheduleAll(getApplication<Application>(), db) }

            _uiState.value = _uiState.value.copy(isSaving = false, medId = medId)
            onSuccess(medId)
        }
    }

    // ---------------- 内部工具 ----------------

    private inline fun mutate(
        clearErrorFor: String? = null,
        crossinline block: (AddEditUiState) -> AddEditUiState
    ) {
        val cur = _uiState.value
        val cleared = if (clearErrorFor != null && cur.error == clearErrorFor) null else cur.error
        _uiState.value = block(cur.copy(error = cleared))
    }

    private fun trimFloat(v: Float): String = if (v % 1f == 0f) v.toInt().toString() else v.toString()

    private fun todayDate(): String =
        LocalDate.now().format(SlotProjectionEngine.DATE_FORMATTER)

    private fun tomorrowDate(): String =
        LocalDate.now().plusDays(1).format(SlotProjectionEngine.DATE_FORMATTER)

    /** 该时点是否已经早于当前时刻 */
    private fun TimeSlotDraft.isBeforeNow(): Boolean {
        val p = time.split(":")
        val m = (p.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (p.getOrNull(1)?.toIntOrNull() ?: 0)
        val cal = java.util.Calendar.getInstance()
        val now = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
            cal.get(java.util.Calendar.MINUTE)
        return m < now
    }

    private fun nextSlotTime(after: String): String {
        val parts = after.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: 8
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 30
        val total = (h * 60 + m + 240) % (24 * 60)
        return String.format(java.util.Locale.getDefault(), "%02d:%02d", total / 60, total % 60)
    }

    /** 按时刻猜一个粗粒度时段标签，用户可再改 */
    /** 按时刻猜一个粗粒度"服药与用餐关系"，用户可再改 */
    private fun guessLabel(minutesOfDay: Int): String = when (minutesOfDay) {
        in 5 * 60 until 6 * 60 -> "空腹服用"
        in 6 * 60 until 9 * 60 -> "饭前服用"
        in 9 * 60 until 10 * 60 -> "随餐服用"
        in 10 * 60 until 13 * 60 -> "饭前服用"
        in 13 * 60 until 15 * 60 -> "随餐服用"
        in 15 * 60 until 18 * 60 -> "饭前服用"
        in 18 * 60 until 21 * 60 -> "餐后服用"
        else -> "睡前"
    }

    companion object {
        const val NAME_ERROR = "请输入药品名称"
        const val DOW_ERROR = "请至少选择一个每周服药日"
        const val TIME_ERROR = "请至少设置一个提醒时点"
        const val DUPLICATE_TIME_ERROR = "存在重复的服药时点，请合并或修改"
    }
}
