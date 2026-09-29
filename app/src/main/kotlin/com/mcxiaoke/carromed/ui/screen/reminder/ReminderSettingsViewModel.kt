package com.mcxiaoke.carromed.ui.screen.reminder

import android.app.Application
import com.mcxiaoke.carromed.core.domain.model.Dose
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcxiaoke.carromed.core.alarm.AlarmReconciler
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.model.PolicyType
import com.mcxiaoke.carromed.core.domain.engine.SlotProjectionEngine
import com.mcxiaoke.carromed.core.domain.engine.StatsEngine
import com.mcxiaoke.carromed.core.domain.service.DoseTrackingService
import com.mcxiaoke.carromed.core.alarm.ReminderSettings
import com.mcxiaoke.carromed.core.domain.service.MedicationAdminService
import com.mcxiaoke.carromed.ui.component.DecimalInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 一个提醒时点的草稿。
 *
 * ## [dose] 为什么是 `String`（M2-1）
 *
 * 与 `AddEditMedicationViewModel.TimeSlotDraft` 同因同解，见那里的详细论证：
 * `Float` 表达不了"正在输入 `0.`"这个中间态（小数点打不出来），
 * 也区分不了"清空"与"填 0"（0 剂量可保存 ⇒ 库存永不扣），
 * 更拦不住负号（打卡变成给库存**加**药）。
 * 过滤走 [DecimalInput.filter]，`> 0` 的判定在 [save]。
 */
/**
 * 「跟随全局」哨兵。0 在 `reminder_settings.snooze_minutes` 里的既定含义。
 *
 * 提成常量而不是散落字面量 `0`：这个 0 与"推迟 0 分钟"挤在同一个 Int 字段上，
 * 只有一处定义才能保证读代码的人立刻知道它不是笔误。
 * 放在顶层是因为 UI 状态类的默认值也要用它。
 */
const val FOLLOW_GLOBAL = 0

data class ReminderTimeDraft(
    val time: String,
    val dose: String,
    val label: String
) {
    /** 合法剂量；null 表示"空 / 0 / 无法解析"，保存前必须拦下 */
    fun parsedDose(): Float? = DecimalInput.parsePositive(dose)
}

data class ReminderSettingsUiState(
    val medication: MedicationEntity? = null,
    val isLoading: Boolean = true,

    // 频次
    val policyType: PolicyType = PolicyType.DAILY,
    val intervalDays: Int = 2,
    val daysOfWeek: List<Int> = emptyList(),
    val cycleOnDays: Int = 21,
    val cycleOffDays: Int = 7,

    // 疗程
    val startDate: String = LocalDate.now().format(SlotProjectionEngine.DATE_FORMATTER),
    val hasEndDate: Boolean = false,
    val endDate: String? = null,

    // 时点
    val times: List<ReminderTimeDraft> = emptyList(),

    // 提醒行为
    val isCriticalReminder: Boolean = false,
    /**
     * 本药专属的推迟分钟数。**0 = 跟随全局**（[FOLLOW_GLOBAL]），不是"0 分钟"。
     *
     * 哨兵 0 本身是对的（`ReminderSettings.resolve` 就是这么解析的），
     * 错的是 UI 把它显示成 30 —— 那让"显示值"与"生效值"分叉。
     * 现在 UI 显示 [globalSnoozeMinutes] 并标注来源，两者不可能再分叉（M7-5）。
     */
    val snoozeMinutes: Int = FOLLOW_GLOBAL,
    /**
     * 全局 `app_settings.snooze_minutes` 的真实值。
     *
     * 单独读一次进状态，而不是让 UI 自己去猜：解析优先级是
     * 「药品专属 > 全局 > 30」，UI 要显示"跟随全局时实际生效多少"
     * 就必须知道全局值，而它**会变**（用户可能在设置页改过）。
     */
    val globalSnoozeMinutes: Int = 30,
    val advanceMinutes: Int = 0,
    val isPaused: Boolean = false,

    // 预览
    val preview: List<PreviewDay> = emptyList(),

    val isSaving: Boolean = false,
    val error: String? = null,
    val savedAt: Long = 0L
)

data class PreviewDay(
    val date: LocalDate,
    val dayLabel: String,
    val scheduled: Boolean,
    val timeTexts: List<String>
)

/**
 * 提醒设置 —— 与「药品信息」「库存」完全分离的第三个维度。
 *
 * 承载：频次 (每天/隔N天/每周/周期/按需) + 疗程起止 + 时点与剂量 + 提醒行为
 * (重要提醒 / 推迟时长 / 提前提醒 / 暂停)。保存后立即重排未来排班与闹钟。
 */
class ReminderSettingsViewModel(
    application: Application,
    private val medId: Long
) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val medDao = db.medicationDao()
    private val policyDao = db.schedulePolicyDao()
    private val adminService = MedicationAdminService(db)
    private val trackingService = DoseTrackingService(db)

    private val _uiState = MutableStateFlow(ReminderSettingsUiState())
    val uiState: StateFlow<ReminderSettingsUiState> = _uiState.asStateFlow()

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
            // 提醒运行态独占 reminder_settings 表（A2）。先确保有行 ——
            // 否则下面 save() 的 UPDATE 会命中 0 行，用户改了设置点保存却毫无变化。
            val rs = db.reminderSettingsDao().ensureDefaults(medId)
            val today = LocalDate.now()
            val policy = policyDao.getActivePolicyForMedication(medId)
            val times = if (policy != null) policyDao.getTimesForPolicy(policy.id) else emptyList()

            val s = _uiState.value
            _uiState.value = s.copy(
                medication = med,
                isLoading = false,
                policyType = policy?.policyType ?: PolicyType.DAILY,
                intervalDays = (policy?.intervalDays ?: 2).coerceIn(2, 30),
                daysOfWeek = policy?.daysOfWeek?.takeIf { it.isNotEmpty() } ?: emptyList(),
                cycleOnDays = (policy?.cycleOnDays ?: 21).coerceAtLeast(1),
                cycleOffDays = (policy?.cycleOffDays ?: 7).coerceAtLeast(0),
                startDate = policy?.startDate ?: today.format(SlotProjectionEngine.DATE_FORMATTER),
                hasEndDate = policy?.endDate != null,
                endDate = policy?.endDate,
                times = times.sortedBy { it.sortOrder }.map {
                    ReminderTimeDraft(
                        it.timeOfDay,
                        DecimalInput.display(Dose(it.doseAmount).asFloat),
                        it.label
                    )
                },
                isCriticalReminder = rs.isCriticalReminder,
                // ⭐ 0 原样保留为"跟随全局"哨兵（M7-5）。
                // 旧实现 `if (it == 0) 30 else it` 把哨兵翻译成了 30，
                // 于是「本药固定 30 分钟」不可表达，且全局改 20 后这一行仍显示 30。
                snoozeMinutes = rs.snoozeMinutes.coerceIn(0, 120),
                globalSnoozeMinutes = globalSnoozeMinutes(),
                advanceMinutes = rs.advanceMinutes.coerceIn(0, 120),
                isPaused = rs.isPausedOn(today)
            )
            refreshPreview()
        }
    }

    // ---------------- 频次 ----------------

    fun onPolicyTypeChange(t: PolicyType) = mutate { it.copy(policyType = t) }
    fun onIntervalDaysChange(v: Int) = mutate { it.copy(intervalDays = v.coerceIn(2, 30)) }
    fun onCycleOnChange(v: Int) = mutate { it.copy(cycleOnDays = v.coerceIn(1, 90)) }
    fun onCycleOffChange(v: Int) = mutate { it.copy(cycleOffDays = v.coerceIn(0, 30)) }

    fun onToggleDay(day: Int) = mutate(clearErrorFor = DOW_ERROR) { s ->
        val cur = s.daysOfWeek
        s.copy(daysOfWeek = if (day in cur) cur - day else (cur + day).sorted())
    }

    // ---------------- 疗程 ----------------

    fun onStartDateChange(v: String) = mutate { it.copy(startDate = v) }

    /**
     * 关掉开关 = **清空疗程结束日**。
     *
     * 所以这里同时清掉 `endDate` 值并置位 [MedicationAdminService.PolicyDraft.clearEndDate] ——
     * 只把 `endDate` 置 null 会被服务端当成"用户没改"而沿用旧值，
     * 于是关掉开关后提醒仍在原定结束日静默停止，用户却以为已经改成长期服用了。
     *
     * 打开开关时**必须**给一个默认日期（M7-1）。旧实现只置位不填值，
     * 于是"打开却不选日期"这条最自然的操作路径产生两个方向都错的结果：
     * - 新药：`endDate` 为 null 保存 ⇒ 服务端把 null 当"没传"⇒ 开关在下次加载时**自己弹回**；
     * - 老药：`endDate` 为 null 但 `clearEndDate = false` ⇒ 沿用**库里的旧结束日**，
     *   页面显示「未设置」而实际仍在那天静默停药。
     *
     * 两者都在骗用户。默认取"今天 + 90 天"（一个常见疗程长度），
     * 用户可在日期选择器里改。
     */
    fun onHasEndDateChange(v: Boolean) = mutate { s ->
        if (v) {
            s.copy(
                hasEndDate = true,
                endDate = s.endDate ?: defaultEndDate()
            )
        } else {
            s.copy(hasEndDate = false, endDate = null)
        }
    }

    /** 疗程结束日的默认值：今天 + 90 天。写死一个数而不是"今天"是为了不立刻到期。 */
    private fun defaultEndDate(): String = LocalDate.now().plusDays(90)
        .format(SlotProjectionEngine.DATE_FORMATTER)

    fun onEndDateChange(v: String?) = mutate { it.copy(endDate = v) }

    // ---------------- 时点 ----------------

    fun addTime() = mutate { s ->
        val last = s.times.maxByOrNull { it.time }?.time ?: "08:30"
        s.copy(times = s.times + ReminderTimeDraft(nextTime(last), defaultDoseText(s), guessLabel(last)))
    }

    fun updateTime(index: Int, time: String? = null, doseText: String? = null, label: String? = null) =
        mutate { s ->
            if (index !in s.times.indices) return@mutate s
            val c = s.times[index]
            s.copy(
                times = s.times.toMutableList().also {
                    it[index] = c.copy(
                        time = time ?: c.time,
                        dose = doseText?.let { DecimalInput.filter(it) } ?: c.dose,
                        label = label ?: c.label
                    )
                }
            )
        }

    fun removeTime(index: Int) = mutate { s ->
        if (s.times.size <= 1 || index !in s.times.indices) s
        else s.copy(times = s.times.toMutableList().also { it.removeAt(index) })
    }

    /**
     * 一键铺排。
     *
     * 剂量统一取药品档案的 `defaultDose`（M4-5）。旧实现写死 1.0f，
     * 而"新建药品"页的同名函数取 `defaultDose` —— 两处来源不同，
     * 用户设了「每次 2 片」之后，两个页面的"一键铺排"产出**不同剂量**，都显示成功。
     *
     * 保留一条"已有时点的剂量"作为回退：本页不暴露 `defaultDose` 字段，
     * 而用户可能已经在这里手工调过剂量；一律用档案值会把他刚填的覆盖掉。
     * 优先级：`medication.defaultDose` > 现有首个时点的剂量 > 1。
     */
    private fun defaultDoseText(s: ReminderSettingsUiState): String {
        val fromMed = s.medication?.let { DecimalInput.display(Dose(it.defaultDose).asFloat) }
        if (fromMed != null && DecimalInput.parsePositive(fromMed) != null) return fromMed
        val existing = s.times.firstOrNull()?.dose
        if (existing != null && DecimalInput.parsePositive(existing) != null) return existing
        return "1"
    }

    fun spreadTimes(n: Int) = mutate { s ->
        val count = n.coerceIn(1, 8)
        val start = 7 * 60
        val end = 21 * 60
        val step = if (count == 1) 0 else (end - start) / (count - 1)
        val dose = defaultDoseText(s)
        s.copy(
            times = (0 until count).map { i ->
                val t = if (count == 1) 8 * 60 else start + step * i
                val text = String.format(java.util.Locale.getDefault(), "%02d:%02d", t / 60, t % 60)
                ReminderTimeDraft(
                    time = text,
                    dose = dose,
                    label = guessLabel(text)
                )
            }
        )
    }

    // ---------------- 提醒行为 ----------------

    fun onCriticalChange(v: Boolean) = mutate { it.copy(isCriticalReminder = v) }
    fun onSnoozeMinutesChange(v: Int) = mutate { it.copy(snoozeMinutes = v.coerceIn(0, 120)) }

    /**
     * 全局推迟时长的真实值。
     *
     * 与 [ReminderSettings.resolve] 的解析链保持一致（药品专属 > 全局 > 默认），
     * 但这里**只取全局那一级** —— 用途是"当本药选跟随全局时，实际会生效多少"。
     */
    private suspend fun globalSnoozeMinutes(): Int =
        db.appSettingDao().getValue(ReminderSettings.KEY_SNOOZE_MINUTES)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: ReminderSettings.DEFAULT_SNOOZE_MINUTES
    fun onAdvanceMinutesChange(v: Int) = mutate { it.copy(advanceMinutes = v.coerceIn(0, 120)) }
    fun onPausedChange(v: Boolean) = mutate { it.copy(isPaused = v) }

    // ---------------- 保存 ----------------

    fun save() {
        val s = _uiState.value

        // ⭐ 同步前置的"正在保存"闸门（M2-4）：双击会重复提交。
        if (s.isSaving) return

        if (s.policyType == PolicyType.DAYS_OF_WEEK && s.daysOfWeek.isEmpty()) {
            _uiState.value = s.copy(error = DOW_ERROR)
            return
        }
        if (s.policyType != PolicyType.PRN && s.times.isEmpty()) {
            _uiState.value = s.copy(error = TIME_ERROR)
            return
        }
        // 重复时点在领域层会被 require 拒绝（事务回滚）；这里前置拦下，
        // 把它变成表单台词而不是一个没接住的异常（P1）
        val timeKeys = s.times.map { it.time.trim() }
        if (timeKeys.size != timeKeys.distinct().size) {
            _uiState.value = s.copy(error = DUPLICATE_TIME_ERROR)
            return
        }
        // ⭐ 剂量必须逐条为正（M2-1）。0 剂量 ⇒ 闹钟照响、打卡照记、库存永不扣。
        // 报错必须指出是哪一条时点，六个时点的表单只说"剂量非法"等于没说。
        val badDose = s.times.firstOrNull { it.parsedDose() == null }
        if (badDose != null) {
            _uiState.value = s.copy(
                error = "${badDose.time} 的剂量无效：请输入大于 0 的数值（例如 1 或 0.5）"
            )
            return
        }
        // ⭐ 疗程结束日不得早于起始日（N2 / sbf P1-11）。
        //
        // 这个校验此前**完全不存在**。结束日早于起始日时，
        // `SlotProjectionEngine.projectSlots` 的 `effectiveStart.isAfter(effectiveEnd)`
        // 命中空投影 ⇒ **该药一个槽位都不产生、不响任何提醒**，
        // 而保存却显示成功。日期来自系统 `DatePickerDialog`（格式合法、先后不受控），
        // 所以这是一条完全可达的路径，不是理论风险。
        if (s.hasEndDate) {
            val start = runCatching { LocalDate.parse(s.startDate) }.getOrNull()
            val end = s.endDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            // 结束日解析不出来（理论上不会，日期都是选择器给的）时**不拦**：
            // 拿一个解析失败当"非法"去阻止用户保存，是在制造新问题。
            if (start != null && end != null && end.isBefore(start)) {
                _uiState.value = s.copy(error = END_BEFORE_START_ERROR)
                return
            }
        }

        viewModelScope.launch {
            _uiState.value = s.copy(isSaving = true, error = null)

            // ⚠️ 整条保存链包 runCatching（M2-3）。领域层的 `require`
            // （重复时点 / 药品不存在）在 ViewModel 里未捕获会一路打到主线程 → 崩溃，
            // 且 `isSaving` 永远停在 true ⇒ 保存按钮**永久禁用**。
            runCatching {
                // N5：这几步分属不同 service、各自开事务，**四步之间没有共同事务**。
                // 第 2 步抛异常 ⇒ 药品档案已保存、提醒计划未保存，而用户什么都不知道。
                // 这里显式串行 + 失败即整单报错（不假装成功），把"部分写入"从静默变成可见。
                adminService.saveReminderPolicy(
                    medicationId = medId,
                    draft = MedicationAdminService.PolicyDraft(
                        policyType = s.policyType,
                        intervalDays = s.intervalDays,
                        daysOfWeek = s.daysOfWeek,
                        cycleOnDays = s.cycleOnDays,
                        cycleOffDays = s.cycleOffDays,
                        startDate = s.startDate,
                        endDate = if (s.hasEndDate) s.endDate else null,
                        clearEndDate = !s.hasEndDate,
                        times = s.times.map {
                            // `!!` 安全：save() 已在协程之前逐条校验过 parsedDose() != null
                            MedicationAdminService.TimeDraft(
                                it.time,
                                requireNotNull(it.parsedDose()) { "剂量无效：${it.time}" },
                                it.label
                            )
                        }
                    )
                )

                // 提醒行为写回 reminder_settings (只写这三列，不动药品档案、库存、暂停状态)。
                // P0-5 至此没有第二条写路径。snoozeMinutes 的 30 是 UI 的"跟随全局"档位，存 0。
                adminService.saveReminderBehavior(
                    MedicationAdminService.ReminderBehaviorDraft(
                        medId = medId,
                        isCriticalReminder = s.isCriticalReminder,
                        // ⚠️ 这里**直接写**状态值，不再做 `== 30 → 0` 的翻译（M7-5）。
                        // 旧翻译让"本药固定 30 分钟"永远存成"跟随全局"，于是 30 不可表达；
                        // 而"跟随全局"现在有了显式档位（[FOLLOW_GLOBAL]），不需要再猜。
                        snoozeMinutes = s.snoozeMinutes,
                        advanceMinutes = s.advanceMinutes
                    )
                )
                // 暂停归详情页的开关所有；提醒设置页只读展示，不在这里改。
                val wasPaused = db.reminderSettingsDao().getByMedicationId(medId)
                    ?.isPausedOn(LocalDate.now()) == true
                if (s.isPaused != wasPaused) {
                    if (s.isPaused) adminService.setPausedUntil(medId, "")
                    else adminService.resume(medId)
                }

                trackingService.reconcileSchedule(medId)
                runCatching { AlarmReconciler.rescheduleAll(getApplication<Application>(), db) }
            }.onFailure { t ->
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    error = "保存失败：${t.message ?: t::class.java.simpleName}"
                )
                return@launch
            }

            _uiState.value = _uiState.value.copy(isSaving = false, savedAt = System.currentTimeMillis())
            load()
        }
    }

    // ---------------- 未来 7 天排班预览 ----------------

    private fun refreshPreview() {
        val s = _uiState.value
        val start = LocalDate.now()
        val days = (0L..6L).map { start.plusDays(it) }
        val preview = days.map { d ->
            val scheduled = when (s.policyType) {
                PolicyType.DAILY -> true
                PolicyType.PRN -> false
                PolicyType.INTERVAL -> {
                    val diff = java.time.temporal.ChronoUnit.DAYS.between(
                        runCatching { LocalDate.parse(s.startDate) }.getOrDefault(start),
                        d
                    )
                    diff >= 0 && diff % s.intervalDays == 0L
                }
                PolicyType.DAYS_OF_WEEK -> d.dayOfWeek.value in s.daysOfWeek
                PolicyType.CYCLE -> {
                    val take = s.cycleOnDays.coerceAtLeast(1)
                    val total = take + s.cycleOffDays.coerceAtLeast(0)
                    val diff = java.time.temporal.ChronoUnit.DAYS.between(
                        runCatching { LocalDate.parse(s.startDate) }.getOrDefault(start),
                        d
                    )
                    diff >= 0 && (diff % total) < take
                }
            }
            val pastEnd = s.hasEndDate && s.endDate != null && d > runCatching {
                LocalDate.parse(s.endDate!!)
            }.getOrDefault(d)
            PreviewDay(
                date = d,
                dayLabel = dayLabel(d),
                scheduled = scheduled && !pastEnd,
                timeTexts = s.times.map { t ->
                    if (t.label == "服药时段") t.time else "${t.time} ${t.label}"
                }
            )
        }
        _uiState.value = _uiState.value.copy(preview = preview)
    }

    private fun dayLabel(d: LocalDate): String =
        when (d.dayOfWeek.value) {
            1 -> "周一"; 2 -> "周二"; 3 -> "周三"; 4 -> "周四"
            5 -> "周五"; 6 -> "周六"; else -> "周日"
        }

    private inline fun mutate(
        clearErrorFor: String? = null,
        crossinline block: (ReminderSettingsUiState) -> ReminderSettingsUiState
    ) {
        val cur = _uiState.value
        val cleared = if (clearErrorFor != null && cur.error == clearErrorFor) null else cur.error
        val next = block(cur.copy(error = cleared))
        _uiState.value = next
        refreshPreview()
    }

    private fun nextTime(after: String): String {
        val p = after.split(":")
        val total = ((p.getOrNull(0)?.toIntOrNull() ?: 8) * 60 +
            (p.getOrNull(1)?.toIntOrNull() ?: 30) + 240) % (24 * 60)
        return String.format(java.util.Locale.getDefault(), "%02d:%02d", total / 60, total % 60)
    }

    private fun guessLabel(time: String): String {
        val p = time.split(":")
        val m = (p.getOrNull(0)?.toIntOrNull() ?: 8) * 60 + (p.getOrNull(1)?.toIntOrNull() ?: 0)
        return when (m) {
            in 5 * 60 until 6 * 60 -> "空腹服用"
            in 6 * 60 until 9 * 60 -> "饭前服用"
            in 9 * 60 until 10 * 60 -> "随餐服用"
            in 10 * 60 until 13 * 60 -> "饭前服用"
            in 13 * 60 until 15 * 60 -> "随餐服用"
            in 15 * 60 until 18 * 60 -> "饭前服用"
            in 18 * 60 until 21 * 60 -> "餐后服用"
            else -> "睡前"
        }
    }

    companion object {
        const val DOW_ERROR = "请至少选择一个每周服药日"
        const val TIME_ERROR = "请至少设置一个提醒时点"
        const val DUPLICATE_TIME_ERROR = "存在重复的服药时点，请合并或修改"
        const val END_BEFORE_START_ERROR = "疗程结束日不能早于起始日"
    }
}
