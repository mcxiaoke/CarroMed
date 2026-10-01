package com.mcxiaoke.carromed.ui.screen.inventory

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcxiaoke.carromed.R
import com.mcxiaoke.carromed.core.alarm.AlarmReconciler
import com.mcxiaoke.carromed.core.domain.AppLog
import com.mcxiaoke.carromed.core.domain.model.Dose
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.InventoryTransactionEntity
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.entity.PolicyTimeEntity
import com.mcxiaoke.carromed.core.data.model.PolicyType
import com.mcxiaoke.carromed.core.data.model.TransactionType
import com.mcxiaoke.carromed.core.domain.engine.StatsEngine
import com.mcxiaoke.carromed.core.domain.service.DoseTrackingService
import com.mcxiaoke.carromed.ui.component.DecimalInput
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
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

    private companion object {
        const val TAG = "InventoryViewModel"
    }

    private val db = AppDatabase.getInstance(application)
    private val medDao = db.medicationDao()
    private val policyDao = db.schedulePolicyDao()
    private val inventoryDao = db.inventoryTransactionDao()
    private val trackingService = DoseTrackingService(db)

    private val _uiState = MutableStateFlow(InventoryUiState())
    val uiState: StateFlow<InventoryUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        load()
        // 响应式重载（osbf P2-3 / DB C-27）：本页曾是进页一次性读取，
        // 在别处入库 / 打卡扣减后返回，账面与流水仍停在旧值。
        // 与详情页同一套"探针触发重载"实现，探针不参与计算、只叫醒重读。
        viewModelScope.launch {
            combine(
                medDao.observeMedicationById(medId),
                inventoryDao.observeTransactionsForMedication(medId),
                policyDao.observePolicyCountForMedication(medId),
                db.doseSlotDao().observeDecidedSlotCount()
            ) { _, _, _, _ -> }.drop(1).collect {
                load()
            }
        }
    }

    fun load() {
        // 连续失效（批量流水写入）时取消上一个加载协程，保证状态收敛于最新数据
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            loadOnce()
        }
    }

    private suspend fun loadOnce() {
        val app = getApplication<Application>()
        val overview = medDao.getOverviewById(medId)
        if (overview == null) {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = app.getString(R.string.inv_err_not_found)
            )
            return
        }
            val med = overview.medication
            val stock = overview.stock
            val policy = policyDao.getActivePolicyForMedication(medId)
            val times = if (policy != null) policyDao.getTimesForPolicy(policy.id) else emptyList()
            val txs = inventoryDao.getTransactionsForMedication(medId)

            // 日均消耗：按"排班日"折算，避免隔日/每周用药被高估消耗。
            // ⚠️ doseAmount 是整数毫单位（D-7），必须先聚合成毫单位再一次性换算，
            //    绝不能逐项 asFloat —— 那会把 1 片的两个时点算成 2.0 而不是 1.0。
            val dosesPerScheduledDay = Dose(times.sumOf { it.doseAmount }).asFloat
            val perWeek = scheduledDosesPerWeek(
                policy?.policyType, policy?.intervalDays, policy?.daysOfWeek,
                policy?.cycleOnDays, policy?.cycleOffDays
            )
            val (runway, alert) = StatsEngine.calculateStockRunwayBySchedule(
                currentStock = stock,
                dosesPerScheduledDay = dosesPerScheduledDay,
                scheduledDosesPerWeek = perWeek,
                // 必须传用户配置的预警线，否则本页的"低库存"判定会与今日页/药箱页不一致
                // （引擎在 minStockAlert=0 时只剩"7 天内"一条硬规则）
                minStockAlert = Dose(med.minStockAlert).asFloat
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
                frequencyDescription = describe(
                    policy?.policyType, policy?.intervalDays, policy?.daysOfWeek, times
                ),
                isLoading = false,
                isTracked = med.isStockTracked,
                currentStock = stock,
                minStockAlert = Dose(med.minStockAlert).asFloat,
                runwayDays = runway,
                isLowStock = alert,
                dailyConsumption = if (perWeek > 0.0) dosesPerScheduledDay * (perWeek / 7.0).toFloat() else dosesPerScheduledDay,
                expiryDate = med.expiryDate,
                daysToExpiry = expiryDays,
                minStockAlertInput = fmt(Dose(med.minStockAlert).asFloat),
                transactions = txs,
                // ⚠️ **不要**在这里重建 `calibrateInput`（M7-3）。
                //
                // `load()` 在保存成功、开关切换、盘点完成之后都会被调用，而用户可能
                // 正在盘点框里敲到一半。旧实现无条件 `calibrateInput = fmt(stock)`，
                // 于是用户敲的 "2" 被静默改写成当前账面 —— 他以为在填 20，
                // 点保存时校准到的是账面原值，于是提示"账面与实物一致，无需调整"。
                //
                // 判据用"用户还没动过"而不是无条件回填：首次进入时给一个合理初值，
                // 一旦用户输入过就**归他所有**，任何后台刷新都不许覆盖。
                calibrateInput = if (_uiState.value.calibrateInput.isBlank()) {
                    if (stock > 0f) fmt(stock) else ""
                } else {
                    _uiState.value.calibrateInput
                }
            )
    }

    fun onMinStockAlertChange(v: String) {
        _uiState.value = _uiState.value.copy(minStockAlertInput = DecimalInput.filter(v))
    }

    fun onCalibrateInputChange(v: String) {
        _uiState.value = _uiState.value.copy(calibrateInput = DecimalInput.filter(v))
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
                _uiState.value = _uiState.value.copy(message = app.getString(R.string.inv_exported, file.name))
                com.mcxiaoke.carromed.core.data.DataExporter.shareFile(app, file, "text/csv")
            } catch (e: Exception) {
                // 吞异常降级成 UI error 的地方必须留痕（PLAN-LOGGING G4）
                AppLog.w(TAG, "exportLedger failed", e)
                _uiState.value = _uiState.value.copy(error = app.getString(R.string.inv_export_failed, e.message))
            }
        }
    }

    /**
     * 开关库存追踪。
     *
     * ## 为什么 `initialStock` 传 `null`（M2-5）
     *
     * 旧实现传 `_uiState.value.currentStock`，而那是**打开页面那一刻的快照余额**。
     * 用户在库存页停留期间若在别处（今日页打卡、通知栏「确认已吃」）扣了库存，
     * 此刻重开追踪就会把那个**陈旧快照**当作"用户声明的初始库存"写回账面：
     *
     * | 时刻 | 真实账面 | 页面快照 | 传入的 initialStock | 结果 |
     * | :--- | ---: | ---: | ---: | :--- |
     * | 进页面 | 30 | 30 | — | — |
     * | 打卡扣 1 片 | 29 | 30 | — | — |
     * | 点「开启追踪」 | 29 | 30 | 30 | 账面被拉回 **30** |
     *
     * 凭空多出 1 片，且账面与实物一致 —— 用户没有任何线索能发现。
     * 传 `null` 走 [DoseTrackingService.setStockTracking] 的"沿用当前账面"分支：
     * 追踪开关只影响**今后**是否自动扣减，绝不回头改已经算清楚的账。
     */
    fun setTracking(enabled: Boolean) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, error = null)
            runCatching {
                trackingService.setStockTracking(medId, enabled, initialStock = null)
                runCatching { AlarmReconciler.rescheduleAll(getApplication<Application>(), db) }
            }.onFailure { t ->
                // 不透出 t.message：领域层抛的是不变量违约文本，用户看不懂。细节进日志。
                AppLog.e("InventoryVM", "setTracking failed med=$medId enabled=$enabled", t)
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    error = app.getString(R.string.inv_op_failed)
                )
                return@launch
            }
            _uiState.value = _uiState.value.copy(
                isSaving = false,
                message = app.getString(
                    if (enabled) R.string.inv_tracking_on else R.string.inv_tracking_off
                )
            )
            load()
        }
    }

    /** 盘点校准：把账面拉回实物真实值，走流水而非直接改账面 */
    fun calibrate(note: String?) {
        val app = getApplication<Application>()
        // parseNonNegative 而非 parsePositive：盘点出"实物为 0"（药已用完/清空）
        // 是合法的校准结果，领域层 calibrateStock 的 KDoc 明确允许 0。
        // 旧写法 parsePositive 让用户没法把账面校准到 0。
        val target = DecimalInput.parseNonNegative(_uiState.value.calibrateInput)
        if (target == null) {
            // ⚠️ 0 必须**报错**而不是当"清空了输入框"（M7-3）。
            // 旧写法 `toFloatOrNull() ?: 回退旧值` 静默接受非法输入并提示"已保存" ——
            // 用户以为自己改了预警线，实际什么都没发生。
            _uiState.value = _uiState.value.copy(
                error = app.getString(R.string.inv_err_calibrate_invalid)
            )
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, error = null)
            val changed = runCatching {
                trackingService.calibrateStock(medId, target, note)
            }.getOrElse { t ->
                AppLog.e("InventoryVM", "calibrate failed med=$medId", t)
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    error = app.getString(R.string.inv_calibrate_failed)
                )
                return@launch
            }
            _uiState.value = _uiState.value.copy(
                isSaving = false,
                message = app.getString(
                    if (changed) R.string.inv_calibrated else R.string.inv_calibrate_no_change,
                    target
                )
            )
            load()
        }
    }

    /**
     * 保存本页设置：**只写本页真正拥有的两列**。
     *
     * 此前这里调用的是整行档案命令 `updateProfile`，被迫手工重传 19 个无关字段
     * （含 `isCriticalReminder` / `snoozeMinutes` / `advanceMinutes`）。
     * 那些重传值来自进页面时的快照 `s.medication`，因此内含一个 read-modify-write 竞态：
     * 用户在别处改了提醒行为，回到本页改个有效期，就会用**陈旧值覆盖回去**。
     *
     * 这正是 `docs/REMINDER-DOMAIN-REDESIGN.md` §1.2 所说的"P0-5 的第二种症状"，
     * 单靠"提醒页写回三列"无法修复，必须让写命令粒度对齐屏幕所有权。
     */
    fun saveSettings() {
        val s = _uiState.value
        val app = getApplication<Application>()
        // ⚠️ 非法输入必须**报错**，不能静默回退旧值（M7-3）。
        //
        // 旧写法 `?: s.minStockAlert` 配上"已保存"的提示，等于对用户说谎：
        // 他把预警线改成 "abc"、点保存、看到"已保存"，于是**相信**预警线已经生效，
        // 实际库里仍是旧值。药品用完时没有告警，而用户明确设置过。
        //
        // ⭐ 这里用 `parseNonNegative` 而不是 `parsePositive`：
        // `minStockAlert = 0` 在本项目里的约定是**关闭低库存告警**，
        // 判成非法会让用户**无法关闭告警** —— 那比误报更糟。
        val alert = DecimalInput.parseNonNegative(s.minStockAlertInput)
        if (alert == null) {
            _uiState.value = s.copy(
                error = app.getString(R.string.inv_err_alert_invalid)
            )
            return
        }
        val expiry = s.expiryDate.takeIf { it != s.medication?.expiryDate }
        if (s.isSaving) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, error = null)
            runCatching {
                if (expiry != null) {
                    medDao.updateExpiryDate(medId, expiry)
                }
                medDao.updateMinStockAlert(medId, Dose.of(alert).milli)
            }.onFailure { t ->
                AppLog.e("InventoryVM", "saveSettings failed med=$medId", t)
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    error = app.getString(R.string.inv_save_failed)
                )
                return@launch
            }
            _uiState.value = _uiState.value.copy(
                isSaving = false,
                message = app.getString(R.string.inv_saved)
            )
            load()
        }
    }

    /**
     * 每周实际排班天数（不是"时点数"）。用于把单次日量折算成日历日均消耗，
     * 否则隔日/每周用药会被高估消耗、低估可用天数。
     *
     * 实现已上移到 [StatsEngine]（M4-1）：详情页原先有一份
     * **不同的**（且是坏的）实现，两页因此给出不同的可用天数。
     *
     * 用**精确浮点版** [StatsEngine.scheduledDaysPerWeekExact]（orsbf P1-5）：
     * 取整版在 INTERVAL n≥15 时折成"每周 1 天"，消耗高估 4.3 倍。
     */
    private fun scheduledDosesPerWeek(
        type: PolicyType?,
        intervalDays: Int?,
        daysOfWeek: List<Int>?,
        cycleOnDays: Int?,
        cycleOffDays: Int?
    ): Double = StatsEngine.scheduledDaysPerWeekExact(type, intervalDays, daysOfWeek, cycleOnDays, cycleOffDays)

    /**
     * 频次描述文案。
     *
     * ⚠️ `INTERVAL` 分支此前用 `times.size`（时点个数）当"间隔天数"渲染，
     * 于是"隔天一次、1 个时点"会显示成「每隔 1 天 1 次」，而引擎实际是隔 2 天。
     * 这与同页 `scheduledDosesPerWeek`（用真实 intervalDays）自相矛盾 ——
     * 同一屏上"频次文案"与"预计可用天数"互相打架。
     *
     * 语义统一到引擎口径（不变量 I7）：`intervalDays` 是**周期天数**，
     * 2 = 每 2 天一次（隔天），3 = 每 3 天一次。
     */
    private fun describe(
        type: PolicyType?,
        intervalDays: Int?,
        daysOfWeek: List<Int>?,
        times: List<PolicyTimeEntity>
    ): String {
        val app = getApplication<Application>()
        if (type == null || times.isEmpty()) return app.getString(R.string.inv_freq_none)
        val perDay = times.size
        val timeStr = times.joinToString(", ") { it.timeOfDay }
        return when (type) {
            PolicyType.DAILY -> app.getString(R.string.inv_freq_daily, perDay, timeStr)
            PolicyType.INTERVAL -> {
                val n = (intervalDays ?: 2).coerceAtLeast(1)
                val dayText = when {
                    n <= 1 -> app.getString(R.string.inv_freq_day_everyday)
                    n == 2 -> app.getString(R.string.inv_freq_day_alternate)
                    else -> app.getString(R.string.inv_freq_day_every_n, n)
                }
                app.getString(R.string.inv_freq_generic, dayText, perDay, timeStr)
            }
            PolicyType.DAYS_OF_WEEK -> {
                // NARROW 在中文下恰好是单字「一二三…」,英文下是 M/T/W…,随系统语言走
                val locale = app.resources.configuration.locales[0]
                val picked = (daysOfWeek ?: emptyList()).sorted()
                    .joinToString("·") {
                        DayOfWeek.of(((it - 1) % 7 + 7) % 7 + 1)
                            .getDisplayName(TextStyle.NARROW, locale)
                    }
                app.getString(R.string.inv_freq_weekly, picked, perDay, timeStr)
            }
            PolicyType.CYCLE -> app.getString(R.string.inv_freq_cycle, perDay, timeStr)
            PolicyType.PRN -> app.getString(R.string.inv_freq_prn)
        }
    }

    private fun fmt(v: Float): String = if (v % 1f == 0f) v.toInt().toString() else v.toString()

    @StringRes
    fun txLabel(t: TransactionType): Int = when (t) {
        TransactionType.TAKEN_DEDUCT -> R.string.inv_tx_taken_deduct
        TransactionType.REFILL -> R.string.inv_tx_refill
        TransactionType.REVERT_ROLLBACK -> R.string.inv_tx_revert
        TransactionType.CALIBRATION_ADJUST -> R.string.inv_tx_calibrate
        TransactionType.DOSE_EDIT_ADJUST -> R.string.inv_tx_dose_edit
    }
}
