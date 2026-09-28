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
import com.mcxiaoke.carromed.core.domain.service.MedicationAdminService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

data class ReminderTimeDraft(
    val time: String,
    val dose: Float,
    val label: String
)

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
    val snoozeMinutes: Int = 30,
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
                    ReminderTimeDraft(it.timeOfDay, Dose(it.doseAmount).asFloat, it.label)
                },
                isCriticalReminder = rs.isCriticalReminder,
                // 0 是"跟随全局设置"的档位，UI 上显示为 30
                snoozeMinutes = rs.snoozeMinutes.coerceIn(0, 120).let { if (it == 0) 30 else it },
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
    fun onHasEndDateChange(v: Boolean) = mutate { it.copy(hasEndDate = v) }
    fun onEndDateChange(v: String?) = mutate { it.copy(endDate = v) }

    // ---------------- 时点 ----------------

    fun addTime() = mutate { s ->
        val last = s.times.maxByOrNull { it.time }?.time ?: "08:30"
        s.copy(times = s.times + ReminderTimeDraft(nextTime(last), 1.0f, guessLabel(last)))
    }

    fun updateTime(index: Int, time: String? = null, dose: Float? = null, label: String? = null) =
        mutate { s ->
            if (index !in s.times.indices) return@mutate s
            val c = s.times[index]
            s.copy(
                times = s.times.toMutableList().also {
                    it[index] = c.copy(time = time ?: c.time, dose = dose ?: c.dose, label = label ?: c.label)
                }
            )
        }

    fun removeTime(index: Int) = mutate { s ->
        if (s.times.size <= 1 || index !in s.times.indices) s
        else s.copy(times = s.times.toMutableList().also { it.removeAt(index) })
    }

    fun spreadTimes(n: Int) = mutate { s ->
        val count = n.coerceIn(1, 8)
        val start = 7 * 60
        val end = 21 * 60
        val step = if (count == 1) 0 else (end - start) / (count - 1)
        s.copy(
            times = (0 until count).map { i ->
                val t = if (count == 1) 8 * 60 else start + step * i
                ReminderTimeDraft(
                    time = String.format(java.util.Locale.getDefault(), "%02d:%02d", t / 60, t % 60),
                    dose = 1.0f,
                    label = guessLabel(String.format(java.util.Locale.getDefault(), "%02d:%02d", t / 60, t % 60))
                )
            }
        )
    }

    // ---------------- 提醒行为 ----------------

    fun onCriticalChange(v: Boolean) = mutate { it.copy(isCriticalReminder = v) }
    fun onSnoozeMinutesChange(v: Int) = mutate { it.copy(snoozeMinutes = v.coerceIn(0, 120)) }
    fun onAdvanceMinutesChange(v: Int) = mutate { it.copy(advanceMinutes = v.coerceIn(0, 120)) }
    fun onPausedChange(v: Boolean) = mutate { it.copy(isPaused = v) }

    // ---------------- 保存 ----------------

    fun save() {
        val s = _uiState.value
        if (s.policyType == PolicyType.DAYS_OF_WEEK && s.daysOfWeek.isEmpty()) {
            _uiState.value = s.copy(error = DOW_ERROR)
            return
        }
        if (s.policyType != PolicyType.PRN && s.times.isEmpty()) {
            _uiState.value = s.copy(error = TIME_ERROR)
            return
        }

        viewModelScope.launch {
            _uiState.value = s.copy(isSaving = true, error = null)

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
                    times = s.times.map { MedicationAdminService.TimeDraft(it.time, it.dose, it.label) }
                )
            )

            // 提醒行为写回 reminder_settings (只写这三列，不动药品档案、库存、暂停状态)。
            // P0-5 至此没有第二条写路径。snoozeMinutes 的 30 是 UI 的"跟随全局"档位，存 0。
            adminService.saveReminderBehavior(
                MedicationAdminService.ReminderBehaviorDraft(
                    medId = medId,
                    isCriticalReminder = s.isCriticalReminder,
                    snoozeMinutes = if (s.snoozeMinutes == 30) 0 else s.snoozeMinutes,
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
    }
}
