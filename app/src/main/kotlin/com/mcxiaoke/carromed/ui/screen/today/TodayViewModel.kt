package com.mcxiaoke.carromed.ui.screen.today

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcxiaoke.carromed.R
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.DoseRecordEntity
import com.mcxiaoke.carromed.core.data.entity.DoseSlotEntity
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.model.MedicationOverview
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import com.mcxiaoke.carromed.core.time.CurrentDateHolder
import com.mcxiaoke.carromed.core.domain.AppLog
import com.mcxiaoke.carromed.core.domain.engine.SlotActionPolicy
import com.mcxiaoke.carromed.core.domain.engine.SlotProjectionEngine
import com.mcxiaoke.carromed.core.domain.engine.StatsEngine
import com.mcxiaoke.carromed.core.alarm.DoseActionResult
import com.mcxiaoke.carromed.core.alarm.DoseEntryActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * 一条待服 / 已服 / 已跳过槽位的展示模型。
 *
 * ## 为什么带 `overview` 而不是 `medication` + 各自散落的 `stock`
 *
 * 库存余额与预警线都是**整数毫单位**（D-7），只有 `MedicationOverview` 上那两个
 * Float 代理是可直接用于渲染的展示值。若这里放实体 `MedicationEntity` 再单挂一个
 * `stock: Float`，UI 里就会出现 `item.medication.minStockAlert`（Int 毫单位）
 * 与 `item.stock`（Float 展示值）混着比大小 —— `50f <= 15000` 恒真，
 * 结果是**每个药都误报低库存**。这类量纲错误编译器抓不到，只能靠类型设计挡住。
 *
 * 所以这里只暴露一个入口：[medication]（实体，仅用于纯展示字段）与
 * [stock] / [minStockAlert]（均已换算为展示值）。
 */
data class DoseSlotItem(
    val slot: DoseSlotEntity,
    val medication: MedicationEntity?,
    val record: DoseRecordEntity? = null,
    /** 该药品的台账账面余额（**展示值**，可为负）；未开启库存追踪时为 null */
    val stock: Float? = null,
    /** 低库存预警线（**展示值**）。0 表示关闭低库存告警。 */
    val minStockAlert: Float = 0f
)

data class TodayUiState(
    val selectedDate: LocalDate = LocalDate.now(),
    /**
     * 当前**自然日**（来自 `CurrentDateHolder`，不是本页的临时 `LocalDate.now()`）。
     *
     * 页面里所有"是不是今天 / 是不是未来"的判断都必须走它：
     * 各写一份 `LocalDate.now()` 会让标题、日期格小圆点、只读判据在跨午夜那一分钟里
     * 各说一套（AGENTS.md §3「测试在早上/下午变红」的同类根源：判据多份、口径不同）。
     */
    val today: LocalDate = LocalDate.now(),
    val weekDates: List<LocalDate> = emptyList(),
    /**
     * 选中日是否允许对被表态（`selectedDate <= today`）。
     *
     * 未来日**仍然可以选中**（预览排班是产品功能），但清单是只读的：
     * 卡片上不渲染 ✓，改成一句"明天 10:30 服用"。
     */
    val isActionable: Boolean = true,
    /** 低库存告急药品（含台账聚合出的账面余额，可能为负 —— 见 FINAL-PRODUCT D-9） */
    val lowStockAlertMeds: List<MedicationOverview> = emptyList(),
    val pendingItems: List<DoseSlotItem> = emptyList(),
    val skippedItems: List<DoseSlotItem> = emptyList(),
    val completedItems: List<DoseSlotItem> = emptyList(),
    val globalSnoozeMinutes: Int = 30,
    val completionSound: String = com.mcxiaoke.carromed.core.alarm.ReminderSettings.DEFAULT_COMPLETION_SOUND,
    val completionHaptic: Boolean = true,
    /** 药箱里是否已有任何在服药品。用于区分"全新用户"与"这一天恰好没排班" */
    val hasAnyMedication: Boolean = false,
    /** 连续服药打卡天数 */
    val streakDays: Int = 0,
    /** 打卡历史月历当前选中的月份 */
    val calendarMonth: YearMonth = YearMonth.now(),
    /** 当前月各自然日的打卡达成状态 */
    val calendarDayStates: Map<LocalDate, StatsEngine.DayAdherenceState> = emptyMap(),
    /**
     * 主数据流异常降级（orsbf P1-17）：数据库打不开等 Room 异常时置位。
     * 此时列表绝不是"空状态"——显示错误卡而不是"去添药"引导，避免误导。
     */
    val loadError: Boolean = false,
    val isLoading: Boolean = true
)

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "TodayViewModel"

        /** 可查看的历史下界：今天-14 天（与 [selectDate] 的 clamp、屏幕日期格的禁用判定共用） */
        const val MIN_HISTORY_DAYS = 14L
        /** 可预览的未来上界：今天+3 天 */
        const val MAX_FUTURE_DAYS = 3L
    }

    private val db = AppDatabase.getInstance(application)
    /**
     * 动作编排（打卡 / 跳过 / 推迟 / 撤销 / 改判 + 闹钟与通知的副作用）。
     *
     * 这一层此前在本 VM 与 `DoseActionReceiver` 各写一份，记录详情页会需要第三份 ——
     * 三份必然漂移，而漂移的后果是静默的（不响、或响两次），所以收敛到一处。
     */
    private val actions = DoseEntryActions(application, db)
    private val slotDao = db.doseSlotDao()
    private val medDao = db.medicationDao()
    private val recordDao = db.doseRecordDao()

    /**
     * 用户当前正在查看的日期。
     *
     * ⚠️ **不是** [CurrentDateHolder.today] 的别名，而是**用户的选择**。
     * 两者必须分开：用户可以翻到昨天、前天看历史（那正是本功能），
     * 跨过午夜时不该把他的选择强制拉回今天。
     */
    private val _selectedDate = MutableStateFlow(CurrentDateHolder.today.value)
    /** 记录上次响应式流观察到的今天，用于前台自然跨天时平滑推进 */
    private var lastObservedToday: LocalDate = CurrentDateHolder.today.value
    /** 记录上次在前台（或初始化）时的今天，用于判定后台恢复时是否已跨天 */
    private var lastResumeDate: LocalDate = CurrentDateHolder.today.value

    init {
        // 跨午夜时把选择跟进到今天（M3-2）：
        // 若用户原本在看今天（current == lastObservedToday）或日期已落在未来（current > realToday），
        // 自动推进到新的今天；若用户主动在看历史，则保持该历史日期不被粗暴弹回。
        viewModelScope.launch {
            CurrentDateHolder.today.collect { realToday ->
                val current = _selectedDate.value
                if (current == lastObservedToday || current > realToday) {
                    _selectedDate.value = realToday
                }
                lastObservedToday = realToday
            }
        }

        // ⚠️ 冷启动对账**不在这里做**：`viewModelScope` 落在主线程，而全量对账实测
        // 要 0.4–1.5s（会阻塞首屏、掉帧近百）。这件事归 `MainActivity`（RESUMED 时
        // 在 IO 线程跑一次）与 `ReconcileWorker`（周期兜底 + 闹钟触发的 `enqueueOneShot`）。
        // 同时刻意**不播种任何演示数据**：首次启动必须是干净空库，
        // 否则用户会看到凭空出现的"环孢素 / 羟氯喹"等不属于自己的服药记录，
        // 进而污染依从率与库存统计。演示数据由 debug 源集的
        // `DevSampleDataSeeder` 手动触发。
    }

    private val _calendarMonth = MutableStateFlow(YearMonth.now())

    /**
     * 连续服药打卡天数（Streak）。
     * 观察最近 365 天直至今日的槽位完成状态，在打卡/撤销/补录或跨夜时响应式重算。
     */
    private val streakDaysFlow: Flow<Int> = CurrentDateHolder.today.flatMapLatest { today ->
        val startDate = today.minusDays(365).format(SlotProjectionEngine.DATE_FORMATTER)
        val endDate = today.format(SlotProjectionEngine.DATE_FORMATTER)
        slotDao.observeSlotStatusCounts(startDate, endDate).map { rows ->
            val dailyBreakdowns = StatsEngine.aggregateDailyOverallBreakdowns(rows)
            StatsEngine.calculateStreak(today, dailyBreakdowns)
        }
        // 365 天窗口的查询 + 内存聚合较重（ocsbf P1-10）：
        // 每次打卡/撤销/跨午夜都重算，切到 Default，避免占住主线程
    }.flowOn(Dispatchers.Default).catch { t ->
        // 查询异常不让整条 stateIn 链死掉（L5）：徽章降级为 0 而不是列表冻结
        AppLog.e(TAG, "streak flow failed", t)
        emit(0)
    }

    /**
     * 当前月历查看月份的单日达成状态。
     * 随 [_calendarMonth] 或当前日变化按月聚合槽位状态。
     */
    private val calendarDayStatesFlow: Flow<Pair<YearMonth, Map<LocalDate, StatsEngine.DayAdherenceState>>> =
        combine(_calendarMonth, CurrentDateHolder.today) { month, today ->
            month to today
        }.flatMapLatest { (month, today) ->
            val startDate = month.atDay(1)
            val endDate = month.atEndOfMonth()
            val startStr = startDate.format(SlotProjectionEngine.DATE_FORMATTER)
            val endStr = endDate.format(SlotProjectionEngine.DATE_FORMATTER)
            slotDao.observeSlotStatusCounts(startStr, endStr).map { rows ->
                val dailyBreakdowns = StatsEngine.aggregateDailyOverallBreakdowns(rows)
                val resultMap = mutableMapOf<LocalDate, StatsEngine.DayAdherenceState>()
                var d = startDate
                while (!d.isAfter(endDate)) {
                    val b = dailyBreakdowns[d.format(SlotProjectionEngine.DATE_FORMATTER)]
                        ?: StatsEngine.DayStatusBreakdown()
                    resultMap[d] = StatsEngine.resolveDayState(b, isFutureDay = d.isAfter(today))
                    d = d.plusDays(1)
                }
                month to resultMap
            }
        }.catch { t ->
            // 同 streakDaysFlow：月历查询失败降级为空图，不让日历 sheet 冻结（L5）
            AppLog.e(TAG, "calendar flow failed", t)
            emit(YearMonth.now() to mutableMapOf<LocalDate, StatsEngine.DayAdherenceState>())
        }

    /**
     * 主状态流（3-4）。
     *
     * `.catch` 在发射错误态后会让这条流**完成**；`stateIn` 于是冻结在错误态，
     * 该页在本次驻留期间永不恢复（`WhileSubscribed(5000)` 只在离开 >5s 再回来时才
     * 重新订阅冷流）。所以这里把"重建一次主状态流"做成可显式触发的动作：
     * 每次 [retry] 自增 [retryTrigger]，`flatMapLatest` 丢掉旧流、重新订阅一次。
     *
     * 重试时先发一帧 [TodayUiState.isLoading]，让用户看到"确实重试了"，
     * 而不是按钮点了毫无反应（Room 仍坏时下一帧又落回错误态 —— 这是预期）。
     */
    private val retryTrigger = MutableStateFlow(0)

    private val baseUiState: Flow<TodayUiState> = retryTrigger.flatMapLatest { attempt ->
        flow {
            if (attempt > 0) emit(loadingUiState())
            emitAll(buildBaseUiState())
        }
    }

    private fun loadingUiState(): TodayUiState {
        val d = _selectedDate.value
        return TodayUiState(
            selectedDate = d,
            today = CurrentDateHolder.today.value,
            weekDates = (-3L..3L).map { d.plusDays(it) },
            isLoading = true
        )
    }

    private fun buildBaseUiState(): Flow<TodayUiState> = combine(
        _selectedDate,
        // "今天"参与 combine（M3-2）：跨午夜后即使 `_selectedDate` 被夹回今天，
        // 页面标题、日期选择器、告警文案都需要跟着重算。
        // 少了这个源，跨夜后标题仍显示昨天的日期字符串。
        CurrentDateHolder.today,
        medDao.observeActiveOverviews(),
        _selectedDate.flatMapLatest { date ->
            val dateStr = date.format(SlotProjectionEngine.DATE_FORMATTER)
            // ⚠️ 日界必须是"次日零点"，不能用 `start + 24h`：该值是
            // `snooze_until_ts` 的开区间上界，夏令时前进日（23h）会多算 1 小时、
            // 回退日（25h）会少算 1 小时 → 推迟剂错误混入当天或当天最后一段整段丢失。
            val zone = ZoneId.systemDefault()
            val dayStartTs = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val dayEndTs = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            slotDao.observeSlotsForDateWithSnoozed(dateStr, dayStartTs, dayEndTs)
                .catch { t ->
                    // 查询失败降级为空清单（L5）：宁可显示空的一天也不让整个列表静默冻结
                    AppLog.e(TAG, "slots flow failed date=$dateStr", t)
                    emit(emptyList())
                }
        },
        db.appSettingDao().observeAllSettings().map { list ->
            list.associate { it.key to it.value }
        }
    ) { selectedDate, today, overviews, slots, settingsMap ->
        val medMap = overviews.associateBy { it.id }

        val pending = mutableListOf<DoseSlotItem>()
        val completed = mutableListOf<DoseSlotItem>()
        val skipped = mutableListOf<DoseSlotItem>()

        // 一次批量取回已完成/已跳过槽位对应的服药事实，避免循环内 N+1 查询。
        // 取**最新**一条未撤销事实（DB C-21）：同槽位"跳过→撤销→再跳过"后，
        // 旧写法（id ASC LIMIT 1）会展示出已作废的最早那条。
        val decidedSlotIds = slots
            .filter { it.status == SlotStatus.COMPLETED || it.status == SlotStatus.SKIPPED }
            .map { it.id }
        val recordsBySlot = recordDao.getActiveRecordsForSlots(decidedSlotIds)
            .groupBy { it.slotId }
            .mapValues { (_, v) -> v.last() }

        for (slot in slots) {
            val overview = medMap[slot.medicationId]
            val med = overview?.medication
            val record = when (slot.status) {
                SlotStatus.COMPLETED, SlotStatus.SKIPPED -> recordsBySlot[slot.id]
                else -> null
            }

            val item = DoseSlotItem(
                slot = slot,
                medication = med,
                record = record,
                stock = if (med?.isStockTracked == true) overview?.stock else null,
                // 走 Overview 的 Float 代理，绝不直接读实体的毫单位 Int
                minStockAlert = overview?.minStockAlert ?: 0f
            )
            when (slot.status) {
                SlotStatus.PENDING, SlotStatus.SNOOZED, SlotStatus.EXPIRED -> pending.add(item)
                SlotStatus.COMPLETED -> completed.add(item)
                SlotStatus.SKIPPED -> skipped.add(item)
            }
        }

        // 低库存告急检测：返回全部告急药品（此前只取第一个，多药告警时会被静默吞掉）
        // 判据走 StatsEngine 的唯一实现，避免与药箱/详情/库存/补药页漂移（M4-1）
        val lowStock = overviews.filter {
            StatsEngine.isLowStock(it.isStockTracked, it.stock, it.minStockAlert)
        }

        val weekDates = (-3L..3L).map { selectedDate.plusDays(it) }

        TodayUiState(
            selectedDate = selectedDate,
            today = today,
            // 未来日只读：判据走领域层的同一份实现，UI 不自己写 `<=`（见 SlotActionPolicy）
            isActionable = SlotActionPolicy.isActionableOn(selectedDate, today),
            weekDates = weekDates,
            lowStockAlertMeds = lowStock,
            pendingItems = pending.sortedBy { it.slot.scheduledTs },
            skippedItems = skipped.sortedBy { it.slot.scheduledTs },
            completedItems = completed.sortedByDescending { it.slot.actualTakenTs ?: it.slot.scheduledTs },
            globalSnoozeMinutes = settingsMap[com.mcxiaoke.carromed.core.alarm.ReminderSettings.KEY_SNOOZE_MINUTES]?.toIntOrNull()
                ?: com.mcxiaoke.carromed.core.alarm.ReminderSettings.DEFAULT_SNOOZE_MINUTES,
            completionSound = settingsMap[com.mcxiaoke.carromed.core.alarm.ReminderSettings.KEY_COMPLETION_SOUND]
                ?: com.mcxiaoke.carromed.core.alarm.ReminderSettings.DEFAULT_COMPLETION_SOUND,
            completionHaptic = settingsMap[com.mcxiaoke.carromed.core.alarm.ReminderSettings.KEY_COMPLETION_HAPTIC]?.toBoolean() ?: true,
            hasAnyMedication = overviews.isNotEmpty(),
            isLoading = false
        )
    }.catch { t ->
        // 主状态流降级（orsbf P1-17）：药品列表/设置流抛 Room 异常时，
        // 不能让 combine 链死掉后页面伪装成"空状态"诱导用户去"添加药品"。
        // 与上方 streak/月历流的 `.catch` 同一纪律 —— 宁可如实报错。
        AppLog.e(TAG, "today base state flow failed", t)
        val d = _selectedDate.value
        emit(
            TodayUiState(
                selectedDate = d,
                today = d,
                weekDates = (-3L..3L).map { d.plusDays(it) },
                isLoading = false,
                loadError = true,
                // 保持 true：错误态下不得出现"首次使用，去添药"引导
                hasAnyMedication = true
            )
        )
    }

    val uiState: StateFlow<TodayUiState> = combine(
        baseUiState,
        streakDaysFlow,
        calendarDayStatesFlow
    ) { base, streak, (month, dayStates) ->
        base.copy(
            streakDays = streak,
            calendarMonth = month,
            calendarDayStates = dayStates
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = TodayUiState(
            selectedDate = CurrentDateHolder.today.value,
            today = CurrentDateHolder.today.value,
            weekDates = (-3L..3L).map { CurrentDateHolder.today.value.plusDays(it) }
        )
    )

    fun selectCalendarMonth(month: YearMonth) {
        _calendarMonth.value = month
    }

    /**
     * 界面回到前台 / 恢复（Resume）时调用（方案 B）。
     *
     * 1. 刷新 [CurrentDateHolder]，获取最新日期事实；
     * 2. 跨天检测：若发现当前真实日期与上次记录的今天不一致（跨天了），将选中的日期自动对准当天的今日；
     * 3. 避免用户过夜切回前台时停留在昨天的药单上。
     */
    fun onResume() {
        CurrentDateHolder.refresh()
        val realToday = CurrentDateHolder.today.value
        val current = _selectedDate.value
        if (lastResumeDate != realToday || current > realToday) {
            AppLog.i(TAG, "onResume: date rollover from $lastResumeDate to $realToday, reset selectedDate from $current to $realToday")
            _selectedDate.value = realToday
        }
        lastResumeDate = realToday
        lastObservedToday = realToday
    }

    /**
     * 错误卡上的「重试」（3-4）：重新订阅主状态流。
     *
     * 主状态流 `.catch` 之后会完成、`stateIn` 冻结在错误态 —— 没有这个入口，
     * 用户碰到一次瞬时 Room 异常就只能"退出 App 重进"。
     */
    fun retry() {
        val next = retryTrigger.value + 1
        AppLog.i(TAG, "retry base state flow attempt=$next")
        retryTrigger.value = next
    }

    fun selectDate(date: LocalDate) {
        val today = CurrentDateHolder.today.value
        val minAllowed = today.minusDays(MIN_HISTORY_DAYS)
        val maxAllowed = today.plusDays(MAX_FUTURE_DAYS)
        val clamped = when {
            date < minAllowed -> minAllowed
            date > maxAllowed -> today
            else -> date
        }
        AppLog.i(TAG, "selectDate target=$date clamped=$clamped")
        _selectedDate.value = clamped
    }

    fun takeDose(slotId: Long) {
        viewModelScope.launch {
            AppLog.i(TAG, "takeDose start slot=$slotId")
            val app = getApplication<Application>()
            // 三种结果必须说三种话：未来槽位说"已处理过"是撒谎
            // （事实是从未有机会处理），说"未重复扣减"也会让用户以为打卡生效了。
            // 服务抛异常（DB 损坏/磁盘满等）也不能沿协程崩掉整个应用（L6），
            // 降级成与其它失败同款的提示。
            runCatching { actions.confirm(slotId) }
                .onFailure { AppLog.e(TAG, "takeDose failed slot=$slotId", it) }
                .fold(
                    onSuccess = { result ->
                        AppLog.i(TAG, "takeDose done slot=$slotId result=$result")
                        when (result) {
                            DoseActionResult.APPLIED -> Unit
                            DoseActionResult.FUTURE_SLOT -> emitEvent(app.getString(R.string.today_err_future_slot))
                            DoseActionResult.ALREADY_HANDLED ->
                                emitEvent(app.getString(R.string.today_err_already_handled))
                        }
                    },
                    onFailure = { emitEvent(app.getString(R.string.today_err_op_failed)) }
                )
        }
    }

    fun undoDose(slotId: Long) {
        viewModelScope.launch {
            AppLog.i(TAG, "undoDose start slot=$slotId")
            val app = getApplication<Application>()
            runCatching { actions.undo(slotId) }
                .onFailure { AppLog.e(TAG, "undoDose failed slot=$slotId", it) }
                .fold(
                    onSuccess = { ok ->
                        AppLog.i(TAG, "undoDose done slot=$slotId ok=$ok")
                        if (!ok) {
                            emitEvent(app.getString(R.string.today_err_op_failed))
                        }
                    },
                    onFailure = { emitEvent(app.getString(R.string.today_err_op_failed)) }
                )
        }
    }

    // ---------------- 一次性提示事件 ----------------

    /**
     * 页面反馈通道。
     *
     * 原先 `takeDose` / `skipDose` 的返回值被直接丢弃，操作失败时**没有任何提示** ——
     * 用户点 ✓ 后卡片不动，既不知道成功也不知道失败。
     */
    private val _events = Channel<String>(Channel.BUFFERED)
    val events: Flow<String> = _events.receiveAsFlow()

    private fun emitEvent(message: String) {
        _events.trySend(message)
    }
}
