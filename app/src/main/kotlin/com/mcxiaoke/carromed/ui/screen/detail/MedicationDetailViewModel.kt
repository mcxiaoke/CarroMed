package com.mcxiaoke.carromed.ui.screen.detail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcxiaoke.carromed.core.domain.model.Dose
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.DoseRecordEntity
import com.mcxiaoke.carromed.core.data.entity.InventoryTransactionEntity
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.entity.PolicyTimeEntity
import com.mcxiaoke.carromed.core.data.entity.SchedulePolicyEntity
import com.mcxiaoke.carromed.core.data.entity.ReminderSettingsEntity
import com.mcxiaoke.carromed.core.data.model.PolicyType
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
    val policy: SchedulePolicyEntity? = null,
    val times: List<PolicyTimeEntity> = emptyList(),
    val transactions: List<InventoryTransactionEntity> = emptyList(),
    /** 台账聚合出的账面余额（可为负，见 FINAL-PRODUCT D-9） */
    val stock: Float = 0f,
    val runwayDays: Int = Int.MAX_VALUE,
    val isStockAlert: Boolean = false,
    val isLoading: Boolean = true,
    val adherenceRate: Float = 0f,
    val adherenceCompleted: Int = 0,
    val adherenceDecided: Int = 0,
    val recentRecords: List<DoseRecordEntity> = emptyList(),
    val doseSum: Float = 0f,
    val error: String? = null
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
            val overview = medDao.getOverviewById(medId)
            if (overview == null) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = "药品不存在或已被删除")
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
     * 修法不是"把这里改对"，而是**消除重复**：抽成
     * [StatsEngine.scheduledDaysPerWeek]，两个 ViewModel 共用同一份实现。
     * 重复的公式就是下一次漂移的起点，而"两个页面数字不一致"用户只会认为是 Bug。
     */
    private fun scheduledDosesPerWeek(policy: SchedulePolicyEntity?, timesCount: Int): Int =
        if (timesCount == 0) 0
        else StatsEngine.scheduledDaysPerWeek(
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
        viewModelScope.launch {
            adminService.setPausedUntil(medId, until?.toString() ?: "")
            rescheduleAlarms()
            loadData()
        }
    }

    fun resumeReminder() {
        viewModelScope.launch {
            adminService.resume(medId)
            rescheduleAlarms()
            loadData()
        }
    }

    fun toggleArchive() {
        val med = _uiState.value.medication ?: return
        viewModelScope.launch {
            val newArchived = !med.isArchived
            medDao.updateArchiveStatus(med.id, newArchived)
            rescheduleAlarms()
            loadData()
        }
    }

    fun deleteMedication(onDeleted: () -> Unit) {
        val med = _uiState.value.medication ?: return
        viewModelScope.launch {
            // 快照必须在删行**之前**拍（osbf P3-9）：FK 级联会删掉该药全部槽位，
            // rescheduleAll 内部拍的快照看不到已删的行，对应闹钟就成了孤儿。
            val presnap = com.mcxiaoke.carromed.core.alarm.AlarmReconciler.snapshotOpenAlarms(db)
            medDao.deleteById(med.id)
            rescheduleAlarms(presnap)
            onDeleted()
        }
    }

    /** 状态变更后按当前库内数据全量重排闹钟；带 `presnap` 时同时清理已删行的闹钟 */
    private suspend fun rescheduleAlarms(
        presnap: Set<com.mcxiaoke.carromed.core.alarm.AlarmReconciler.AlarmIdentity> = emptySet()
    ) {
        runCatching {
            com.mcxiaoke.carromed.core.alarm.AlarmReconciler.rescheduleAll(
                getApplication<Application>(), db, presnap
            )
        }
    }

    companion object {
        const val RECENT_RECORD_LIMIT = 20
    }
}
