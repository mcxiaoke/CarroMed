package com.mcxiaoke.carromed.ui.screen.detail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcxiaoke.carromed.R
import com.mcxiaoke.carromed.core.alarm.ReminderSettings
import com.mcxiaoke.carromed.core.domain.model.Dose
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.DoseRecordEntity
import com.mcxiaoke.carromed.core.data.entity.InventoryTransactionEntity
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.entity.PolicyTimeEntity
import com.mcxiaoke.carromed.core.data.entity.SchedulePolicyEntity
import com.mcxiaoke.carromed.core.data.entity.ReminderSettingsEntity
import com.mcxiaoke.carromed.core.data.model.PolicyType
import com.mcxiaoke.carromed.core.domain.AppLog
import com.mcxiaoke.carromed.core.domain.engine.SlotProjectionEngine
import com.mcxiaoke.carromed.core.domain.engine.StatsEngine
import com.mcxiaoke.carromed.core.domain.service.MedicationAdminService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

data class MedDetailUiState(
    val medication: MedicationEntity? = null,
    /**
     * 提醒运行态（A2 起独占 `reminder_settings` 表）。
     *
     * 暂停是**派生**的（`isPausedOn(today)`），不是这里的一个布尔字段 ——
     * 存第二份必然在到期自动恢复后漂移。
     */
    val reminderSettings: ReminderSettingsEntity = ReminderSettingsEntity(0L),
    /**
     * 全局默认推迟时长（§二-24）。
     * `reminderSettings.snoozeMinutes == 0` 是「跟随全局」哨兵，
     * 详情页用它渲染「跟随全局（N 分）」，与 `ReminderSettings.resolve`
     * 在通知侧的真实生效值保持一致。直接读 `app_settings` 的
     * `KEY_SNOOZE_MINUTES`（resolve 的全局分支同源）。
     */
    val globalSnoozeMinutes: Int = ReminderSettings.DEFAULT_SNOOZE_MINUTES,
    val policy: SchedulePolicyEntity? = null,
    val times: List<PolicyTimeEntity> = emptyList(),
    val transactions: List<InventoryTransactionEntity> = emptyList(),
    /** 台账聚合出的账面余额（可为负，见 FINAL-PRODUCT D-9） */
    val stock: Float = 0f,
    // 加载完成前的初值用哨兵而不是 Int.MAX_VALUE（orsbf P3-15）：
    // 后者会渲染成"可用 2147483647 天"；哨兵渲染成"—"。
    val runwayDays: Int = StatsEngine.RUNWAY_UNLIMITED,
    val isStockAlert: Boolean = false,
    val isLoading: Boolean = true,
    val adherenceRate: Float = 0f,
    val adherenceCompleted: Int = 0,
    val adherenceDecided: Int = 0,
    val recentRecords: List<DoseRecordEntity> = emptyList(),
    val doseSum: Float = 0f,
    val error: String? = null,
    /** 任一写操作（暂停/恢复/归档/删除）进行中：按钮禁用 + 防连点 */
    val isSaving: Boolean = false
)

class MedicationDetailViewModel(
    application: Application,
    private val medId: Long
) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val medDao = db.medicationDao()
    private val policyDao = db.schedulePolicyDao()
    private val inventoryDao = db.inventoryTransactionDao()
    private val slotDao = db.doseSlotDao()
    private val recordDao = db.doseRecordDao()
    private val reminderSettingsDao = db.reminderSettingsDao()
    private val adminService = MedicationAdminService(db)

    private val _uiState = MutableStateFlow(MedDetailUiState())
    val uiState: StateFlow<MedDetailUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var isDeleting = false

    init {
        loadData()
        // 响应式重载（osbf P2-3 / DB C-27）：本页曾是进页一次性读取，
        // 从补药 / 提醒设置 / 打卡返回后看到的仍是旧数据。
        //
        // 实现选"探针触发重载"而不是把整条管线改写成 Flow combine：
        // 状态里有依从率、可用天数这类多源派生值，全改 combine 是一次大重写；
        // 探针只需要"有变化就重读一遍"，Room 的 InvalidationTracker 保证
        // 只有相关表变化才发射。drop(1)：首帧由上面显式的 loadData 负责，
        // 避免与探针首值重复加载。
        viewModelScope.launch {
            combine(
                medDao.observeMedicationById(medId),
                inventoryDao.observeTransactionsForMedication(medId),
                reminderSettingsDao.observeByMedicationId(medId),
                slotDao.observeDecidedSlotCount(),
                recordDao.observeRecordCount(),
                policyDao.observePolicyCountForMedication(medId)
            ) { _ -> }.drop(1).collect {
                loadData()
            }
        }
    }

    fun loadData() {
        // 连续失效（如恢复备份批量写）会连续触发重载；取消上一个，保证
        // 状态按最新数据收敛，而不是几个旧协程乱序覆盖。
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            loadDataOnce()
        }
    }

    private suspend fun loadDataOnce() {
            if (isDeleting) return
            val overview = medDao.getOverviewById(medId)
            if (overview == null) {
                val app = getApplication<Application>()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = app.getString(R.string.mdetail_err_not_found)
                )
                return
            }
            val med = overview.medication
            val stock = overview.stock
            val policy = policyDao.getActivePolicyForMedication(medId)
            val times = if (policy != null) policyDao.getTimesForPolicy(policy.id) else emptyList()
            val txList = inventoryDao.getTransactionsForMedication(medId)

            // 近 30 天依从率 (按计划时间归属，与进展页 / 统计页同口径)
            val today = LocalDate.now()
            val startStr = today.minusDays(29).format(SlotProjectionEngine.DATE_FORMATTER)
            val endStr = today.format(SlotProjectionEngine.DATE_FORMATTER)
            val slotRows = slotDao.getSlotStatusCounts(startStr, endStr)
                .filter { it.medicationId == medId }
            val byDate = StatsEngine.aggregateBreakdowns(slotRows)[medId].orEmpty()
            val totals = StatsEngine.sumBreakdowns(
                byDate,
                (0L..29L).map { today.minusDays(it).format(SlotProjectionEngine.DATE_FORMATTER) }
            )

            // 近 30 天实际消耗与最近服药记录
            val startTs = today.minusDays(29).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val endTs = System.currentTimeMillis()
            // ⚠️ DAO 返回的是**整数毫单位**，必须经 `Dose` 换算成展示值。
            // 直接 `?: 0f` 会让吃过 1 片的药显示成「共消耗 1000 片」。
            val doseSum = Dose(
                recordDao.getSumDoseTakenForMedication(medId, startTs, endTs) ?: 0
            ).asFloat
            val recent = recordDao.getRecordsForMedication(medId).take(RECENT_RECORD_LIMIT)

            val dailyDose = Dose(times.sumOf { it.doseAmount }).asFloat
            val (runway, isAlert) = StatsEngine.calculateStockRunwayBySchedule(
                currentStock = stock,
                dosesPerScheduledDay = dailyDose,
                scheduledDosesPerWeek = scheduledDosesPerWeek(policy, times.size),
                // 与库存页一致：必须传用户配置的预警线，否则本页低库存判定会与今日/药箱页冲突
                minStockAlert = Dose(med.minStockAlert).asFloat
            )

            _uiState.value = MedDetailUiState(
                medication = med,
                reminderSettings = reminderSettingsDao.ensureDefaults(medId),
                globalSnoozeMinutes = db.appSettingDao().getValue(ReminderSettings.KEY_SNOOZE_MINUTES)
                    ?.toIntOrNull() ?: ReminderSettings.DEFAULT_SNOOZE_MINUTES,
                policy = policy,
                times = times,
                transactions = txList,
                stock = stock,
                runwayDays = runway,
                isStockAlert = isAlert,
                isLoading = false,
                adherenceRate = StatsEngine.adherenceOf(totals),
                adherenceCompleted = totals.completed,
                adherenceDecided = totals.decided,
                recentRecords = recent,
                doseSum = doseSum
            )
    }    /**
     * 每周实际排班天数，用于把日均消耗折算到"日历日"而非"服药日"。
     *
     * ## 为什么必须与 `InventoryViewModel` 逐字一致（M4-1）
     *
     * 两页显示同一个数（预计可用天数），公式却各写一份，而这份**是坏的**：
     *
     * | 分支 | 本页旧实现 | `InventoryViewModel`（正确） | 后果 |
     * | :--- | :--- | :--- | :--- |
     * | `CYCLE` | 写死 **5** | `7·on/(on+off)` | 「吃2停6」本页日消耗高估 4 倍 |
     * | `INTERVAL` | `round(7/n)` **无下界** | `.coerceAtLeast(1)` | `n ≥ 15` ⇒ 0 ⇒ **可用天数 ∞**，负库存都不告警 |
     *
     * `CYCLE -> 5` 尤其恶劣：它是把"吃 21 停 7"当成"每周 5 天"，
     * 而正确答案是 `7·21/28 = 5.25`。改动看似小，但"吃 2 停 6"（正确值 1.75）
     * 会被算成 5，**高估近 3 倍**，两页给出完全不同的可用天数。
     *
     * 修法不是"把这里改对"，而是**消除重复**：抽到
     * [StatsEngine]，两个 ViewModel 共用同一份实现。
     * 重复的公式就是下一次漂移的起点，而"两个页面数字不一致"用户只会认为是 Bug。
     *
     * 用**精确浮点版** [StatsEngine.scheduledDaysPerWeekExact]（orsbf P1-5），
     * 与库存页同口径：取整版在 INTERVAL n≥15 时折成"每周 1 天"，消耗高估 4.3 倍。
     */
    private fun scheduledDosesPerWeek(policy: SchedulePolicyEntity?, timesCount: Int): Double =
        if (timesCount == 0) 0.0
        else StatsEngine.scheduledDaysPerWeekExact(
            type = policy?.policyType,
            intervalDays = policy?.intervalDays,
            daysOfWeek = policy?.daysOfWeek,
            cycleOnDays = policy?.cycleOnDays,
            cycleOffDays = policy?.cycleOffDays
        )

    /**
     * 暂停提醒。
     *
     * @param until `null` = **无限期**（出差 / 住院这类"不知道哪天回来"）；
     *             非 null = 暂停至该日**含**，到期自动恢复。
     *
     * 恢复点是 A6 的周期对账 —— 用户不必回来开 App。
     * 存的是**意图**，日期比较一律交给 `ReminderSettingsEntity.isPausedOn`。
     */
    fun pauseReminderUntil(until: LocalDate?) {
        if (_uiState.value.isSaving) return
        viewModelScope.launch {
            runWrite(R.string.mdetail_error_op_failed) { adminService.setPausedUntil(medId, until?.toString() ?: "") }
            rescheduleAlarms()
            loadData()
        }
    }

    fun resumeReminder() {
        if (_uiState.value.isSaving) return
        viewModelScope.launch {
            runWrite(R.string.mdetail_error_op_failed) { adminService.resume(medId) }
            rescheduleAlarms()
            loadData()
        }
    }

    fun toggleArchive() {
        val med = _uiState.value.medication ?: return
        if (_uiState.value.isSaving) return
        viewModelScope.launch {
            runWrite(R.string.mdetail_error_op_failed) { medDao.updateArchiveStatus(med.id, !med.isArchived) }
            rescheduleAlarms()
            loadData()
        }
    }

    fun deleteMedication(onDeleted: () -> Unit) {
        val med = _uiState.value.medication ?: return
        if (_uiState.value.isSaving) return
        viewModelScope.launch {
            if (!med.isArchived) {
                // 在服药品不可直接删除，必须先停药归档
                _uiState.value = _uiState.value.copy(
                    error = getApplication<Application>().getString(R.string.mdetail_error_must_archive_before_delete)
                )
                return@launch
            }
            isDeleting = true
            _uiState.value = _uiState.value.copy(isSaving = true, error = null)
            val failed = runCatching {
                // 快照必须在删行**之前**拍（osbf P3-9）：级联会删掉该药全部槽位，
                // rescheduleAll 内部拍的快照看不到已删的行，对应闹钟就成了孤儿。
                val presnap = com.mcxiaoke.carromed.core.alarm.AlarmReconciler.snapshotOpenAlarms(db)
                medDao.permanentlyDelete(med.id)
                // 删除后页面即将销毁，error 状态没人看 —— 降级提示直接走 Toast
                val rescheduled = rescheduleAlarms(presnap)
                if (!rescheduled) {
                    val app = getApplication<Application>()
                    android.widget.Toast.makeText(
                        app, app.getString(R.string.mdetail_error_schedule_failed),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }.isFailure
            isDeleting = false
            if (failed) {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    error = getApplication<Application>().getString(R.string.mdetail_error_op_failed)
                )
                return@launch
            }
            onDeleted()
        }
    }

    /**
     * 写操作统一包装：置 isSaving 防连点、清旧 error、异常兜底成用户可读文案
     * （M9：服务层 `check()` 抛的 IllegalStateException 此前会未捕获崩溃）。
     * 返回 true 表示写成功。
     */
    private suspend fun runWrite(errorRes: Int, block: suspend () -> Unit): Boolean {
        val app = getApplication<Application>()
        _uiState.value = _uiState.value.copy(isSaving = true, error = null)
        return runCatching { block() }
            .onFailure { AppLog.e(TAG, "write op failed med=$medId", it) }
            .fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(isSaving = false)
                    true
                },
                onFailure = {
                    _uiState.value = _uiState.value.copy(isSaving = false, error = app.getString(errorRes))
                    false
                }
            )
    }

    /** 状态变更后按当前库内数据全量重排闹钟；带 `presnap` 时同时清理已删行的闹钟。
     *  失败不抛出，返回 false 供调用方降级提示（H 系列同款：报成功 ≠ 闹钟排上了）。 */
    private suspend fun rescheduleAlarms(
        presnap: Set<com.mcxiaoke.carromed.core.alarm.AlarmReconciler.AlarmIdentity> = emptySet()
    ): Boolean =
        runCatching {
            com.mcxiaoke.carromed.core.alarm.AlarmReconciler.rescheduleAll(
                getApplication<Application>(), db, presnap
            )
        }.fold(
            onSuccess = { true },
            onFailure = { t ->
                AppLog.e(TAG, "rescheduleAll failed med=$medId", t)
                // 页面仍存活（暂停/恢复/归档）时 error → Toast 可见；
                // 删除路径页面即将销毁，由 deleteMedication 自行 Toast
                if (!isDeleting) {
                    _uiState.value = _uiState.value.copy(
                        error = getApplication<Application>().getString(R.string.mdetail_error_schedule_failed)
                    )
                }
                false
            }
        )

    companion object {
        const val RECENT_RECORD_LIMIT = 20
        private const val TAG = "MedDetailVM"
    }
}
