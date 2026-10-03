package com.mcxiaoke.carromed.ui.screen.progress

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcxiaoke.carromed.R
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.DataExporter
import com.mcxiaoke.carromed.core.data.entity.DoseRecordEntity
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.domain.AppLog
import com.mcxiaoke.carromed.core.time.CurrentDateHolder
import com.mcxiaoke.carromed.core.domain.engine.SlotProjectionEngine
import com.mcxiaoke.carromed.core.domain.engine.StatsEngine
import com.mcxiaoke.carromed.ui.component.WeekLabels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class DayAdherence(
    val date: LocalDate,
    val dayLabel: String,
    /**
     * 「这一格是今天」——**给 UI 判定用，不要拿 [dayLabel] 去比字符串**（M7-9）。
     *
     * 旧实现在 `ProgressScreen` 里写 `dayLabel == "今日"` 来决定高亮。
     * 判据挂在**给人看的文案**上：把"今日"改成"今天"、加个空格，
     * 或者将来支持多语言走 `strings.xml`，高亮就**静默失效** ——
     * 而且编译通过、单测全绿，只有肉眼能看出"今天的格子没变色"。
     *
     * 展示与判定是两件事，所以这里给的是布尔值，文案随便改。
     */
    val isToday: Boolean,
    val state: StatsEngine.DayAdherenceState,
    val completed: Int,
    val total: Int
)

data class MedMatrixItem(
    val medication: MedicationEntity,
    val completionRate: Float,
    val completedCount: Int,
    val decidedCount: Int,
    val days: List<DayAdherence>
)

/**
 * 服药流水的一行。
 *
 * ⚠️ 旧版持有 `slot: DoseSlotEntity` —— 那是**排班**，不是**事实**。
 * 后果是手动补录的服药（`slot_id == null`）永远进不了这个列表：
 * 它根本没有槽位。PRN 药吃完药，今日清单看得到、进展页看不到。
 *
 * 现在数据源换成 [DoseRecordEntity]（服药事实），两个来源一并覆盖。
 * 副作用也是对的：**未打卡的 PENDING 槽位不再出现** ——
 * "流水"记录的是已发生的事实，待服清单是首页的职责。
 */
data class TimelineItem(
    val record: DoseRecordEntity,
    val medication: MedicationEntity?,
    /** 实际发生时刻 `HH:mm`，由 [DoseRecordEntity.actualTs] 按本地时区换算 */
    val timeLabel: String,
    /** 临时用药（无排班）标记 —— UI 用来加一个「临时」标签 */
    val isManual: Boolean
)

/** 流水按天分节（对齐 MyTherapy 列表视图的日期分组）。 */
data class TimelineDay(
    val date: LocalDate,
    /** 节头文案，如「星期二, 26/9/29」；今天另起「今天」样式 */
    val headerLabel: String,
    val isToday: Boolean,
    val items: List<TimelineItem>,
    /** 本节已完成剂量的合计（整数毫单位；SKIPPED / REVERTED 不计） */
    val completedDoseMilli: Int
)

data class ProgressUiState(
    val selectedTab: Int = 0,
    val matrixItems: List<MedMatrixItem> = emptyList(),
    val timelineDays: List<TimelineDay> = emptyList(),
    val overallAdherence: Float = 0f,
    val isLoading: Boolean = true,
    val isTimelineLoadingMore: Boolean = false,
    val hasMoreTimeline: Boolean = false,
    /** 触底加载失败（M10）：footer 显示错误 + 重试，不再伪装成"到底了" */
    val timelineLoadFailed: Boolean = false
)

/**
 * 进展追踪
 *
 * 口径修复说明：此前此页的"7 天打卡矩阵"是**硬编码假数据** ——
 * 历史 6 天无条件返回 `COMPLETED`，今日只取该药第一个槽位，取不到也默认 `COMPLETED`。
 * 结果是"今天才建的药品"也会显示"周一到周六全绿 + 完成率 100%"。
 * 现在完全由 `dose_slots` 的真实状态分布驱动，四态区分：已服 / 部分 / 跳过 / 逾期漏服 / 未排班。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProgressViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val medDao = db.medicationDao()
    private val slotDao = db.doseSlotDao()
    private val recordDao = db.doseRecordDao()

    private companion object {
        const val TAG = "ProgressViewModel"
        /**
         * 每页条数。60 条约等于"一个月、两味药、每天各两次"，
         * 也就是用户默认能看到的范围（见 UX 方案 §4.1.2）。
         */
        const val FIRST_PAGE_SIZE = 60

        val TIME_ZONE: ZoneId = ZoneId.systemDefault()
        val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }

    private val _selectedTab = MutableStateFlow(0)

    /**
     * 流水已加载的原始行（未分组）。
     *
     * 单独持有而不是塞进 [uiState]：分页要**追加**，而 `uiState` 是
     * `combine` 出来的只读流。用一个可写源承接追加，再交给 [uiState] 派生，
     * 才不会出现"两个可写源互相覆盖"。
     */
    private val _timelineRecords = MutableStateFlow<List<TimelineItem>>(emptyList())
    private val _hasMoreTimeline = MutableStateFlow(false)
    private val _isLoadingMore = MutableStateFlow(false)
    private val _timelineLoadFailed = MutableStateFlow(false)

    /**
     * "今天"来自 [CurrentDateHolder]，不是 `LocalDate.now()` 字段（M3-2）。
     *
     * 进程跨夜存活时，字段版会把 7 天矩阵永久冻结在昨天，
     * 且最后一列还标着「今日」。见 `CurrentDateHolder` 的 KDoc。
     */
    private val todayFlow = CurrentDateHolder.today

    /** 7 天矩阵的原始计算结果，单独成一个流好和流水分开演进。 */
    private val matrixState: Flow<MatrixState> = todayFlow.flatMapLatest { today ->
        val app = getApplication<Application>()
        val weekDates = remember7Days(today)
        combine(
            medDao.observeActiveOverviews(),
            slotDao.observeSlotStatusCounts(
                weekDates.first().format(SlotProjectionEngine.DATE_FORMATTER),
                weekDates.last().format(SlotProjectionEngine.DATE_FORMATTER)
            )
        ) { overviews, statusRows ->
            val byMedDate = StatsEngine.aggregateBreakdowns(statusRows)
            val dateStrs = weekDates.map { it.format(SlotProjectionEngine.DATE_FORMATTER) }

            val matrixItems = overviews.map { overview ->
                val byDate = byMedDate[overview.id].orEmpty()
                val days = weekDates.map { d ->
                    val b = byDate[d.format(SlotProjectionEngine.DATE_FORMATTER)]
                        ?: StatsEngine.DayStatusBreakdown()
                    DayAdherence(
                        date = d,
                        isToday = d == today,
                        dayLabel = if (d == today) app.getString(R.string.prog_today_label) else dayLabelOf(d),
                        state = StatsEngine.resolveDayState(b, isFutureDay = d.isAfter(today)),
                        completed = b.completed,
                        total = b.total
                    )
                }
                val total = StatsEngine.sumBreakdowns(byDate, dateStrs)
                MedMatrixItem(
                    medication = overview.medication,
                    completionRate = StatsEngine.adherenceOf(total),
                    completedCount = total.completed,
                    decidedCount = total.decided,
                    days = days
                )
            }

            val overall = matrixItems.fold(StatsEngine.DayStatusBreakdown()) { acc, m ->
                acc + StatsEngine.sumBreakdowns(byMedDate[m.medication.id].orEmpty(), dateStrs)
            }
            MatrixState(matrixItems, StatsEngine.adherenceOf(overall))
        }
    }

    private data class MatrixState(
        val items: List<MedMatrixItem>,
        val overallAdherence: Float
    )

    val uiState: StateFlow<ProgressUiState> = combine(
        _selectedTab,
        matrixState,
        todayFlow,
        _timelineRecords,
        _hasMoreTimeline,
        _isLoadingMore,
        _timelineLoadFailed
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val tab = values[0] as Int
        val matrix = values[1] as MatrixState
        val today = values[2] as LocalDate
        val records = values[3] as List<TimelineItem>
        @Suppress("UNCHECKED_CAST")
        val hasMore = values[4] as Boolean
        @Suppress("UNCHECKED_CAST")
        val loadingMore = values[5] as Boolean
        val loadFailed = values[6] as Boolean

        ProgressUiState(
            selectedTab = tab,
            matrixItems = matrix.items,
            timelineDays = groupByDay(records, today),
            overallAdherence = matrix.overallAdherence,
            isLoading = false,
            isTimelineLoadingMore = loadingMore,
            hasMoreTimeline = hasMore,
            timelineLoadFailed = loadFailed
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ProgressUiState()
    )

    // ==================== 服药流水（跨月分页） ====================

    /**
     * 首屏：订阅最近一页。
     *
     * 用 `observeLatestRecords` 而非 `suspend getRecordsBefore` 是为了**跟随写入**：
     * 用户在别处补录一次服药后回到本页，首屏应当自动包含它。
     *
     * 语义是"**替换**"而不是"合并"：首屏一旦重新发射就以它为准。
     * 这在实践中的影响是——用户翻了几页后去别处补录再回来，会回到列表顶部。
     * 而他刚加了一条记录，顶部正是他关心的地方，所以这个取舍是划算的。
     * 真的想保住滚动位置，得引入"记录 id 水位线 + 差量合并"，那是过度设计。
     */
    /**
     * 首屏流的单调修订号（ocsbf P1-8 残余）。
     *
     * [loadMoreTimeline] 取快照后要挂起查库，期间首屏流可能重发射（新打卡/撤销/
     * 补录）并**整表替换** `_timelineRecords`。恢复后若照旧用旧快照覆盖写回，
     * 就会把首屏刚带进来的新记录丢掉 —— 写-写竞态。
     * 首屏每次发射 [firstPageRevision] 自增；loadMore 比对快照时的值，
     * 不一致即本次追加过期，直接丢弃（首屏已接管列表，重新触底即可续页）。
     * 全部读写都在 viewModelScope（Main）上，普通字段即可，无需原子类。
     */
    private var firstPageRevision: Long = 0L

    private val firstPageFlow: Flow<List<TimelineItem>> =
        recordDao.observeLatestRecords(FIRST_PAGE_SIZE)
            .map { records -> records.toTimelineItems() }
            .onEach { items ->
                firstPageRevision++
                _timelineRecords.value = items
                _hasMoreTimeline.value = items.size >= FIRST_PAGE_SIZE
                // 首屏重新发射（数据已变）时旧失败态不再有意义
                _timelineLoadFailed.value = false
            }

    init {
        // 首屏必须常驻订阅：它是 [uiState] 的数据源，
        // 若只在 UI 可见时才订阅，进页面第一帧会是空列表再闪一下。
        viewModelScope.launch {
            firstPageFlow.collect { /* onEach 已写入 _timelineRecords，此处只维持订阅 */ }
        }
    }

    /**
     * 触底加载更早的记录（keyset 游标，见 [DoseRecordDao.getRecordsBefore]）。
     *
     * 并发防护用**同步前置**的标志位：与 M2-4「保存」闸门同一教训，
     * 标志写在协程体内则快速连点能同时起两个加载，追加出重复行。
     */
    fun loadMoreTimeline() {
        if (_isLoadingMore.value || !_hasMoreTimeline.value) return
        // 失败后停止自动重试：触底触发器会持续命中，无人为门槛的话会一直打库。
        // 用户点 footer 的「重试」才恢复（见 [retryTimeline]）。
        if (_timelineLoadFailed.value) return
        val current = _timelineRecords.value
        if (current.isEmpty()) return
        // 快照与修订号必须**同刻**取得：修订号是"首屏是否重发射过"的判据
        val revisionAtSnapshot = firstPageRevision
        // 复合游标 (actualTs, id)（orsbf P1-3）：同毫秒记录簇之间也要有全序，
        // 只用时间戳做游标时，页边界切在簇中间会静默吞掉簇里其余记录。
        val cursorTs = current.minOf { it.record.actualTs }
        val cursorId = current
            .filter { it.record.actualTs == cursorTs }
            .minOf { it.record.id }
        _isLoadingMore.value = true
        viewModelScope.launch {
            try {
                val older = recordDao.getRecordsBefore(cursorTs, cursorId, FIRST_PAGE_SIZE)
                if (older.isNotEmpty()) {
                    val medMap = loadMedMap(older)
                    val existingIds = current.mapTo(HashSet()) { it.record.id }
                    val fresh = older
                        .filter { it.id !in existingIds }
                        .map { it.toTimelineItem(medMap) }
                    // 追加前检查修订号：挂起期间首屏若已重发射（整表替换），
                    // 本次基于旧快照的追加即过期 —— 写回会丢掉首屏的新记录，
                    // 直接丢弃，hasMore/失败态均由首屏发射方接管（ocsbf P1-8 残余）
                    if (firstPageRevision != revisionAtSnapshot) {
                        AppLog.i(TAG, "loadMoreTimeline stale (revision changed), append discarded")
                        return@launch
                    }
                    // 追加后仍需按时间倒序：游标保证更早，同刻记录按 id 稳定排序
                    // （与 DAO 的 ORDER BY actual_ts DESC, id DESC 同口径）
                    _timelineRecords.value =
                        (current + fresh).sortedWith(
                            compareByDescending<TimelineItem> { it.record.actualTs }
                                .thenByDescending { it.record.id }
                        )
                }
                if (firstPageRevision == revisionAtSnapshot) {
                    _hasMoreTimeline.value = older.size >= FIRST_PAGE_SIZE
                }
            } catch (t: Throwable) {
                // 加载失败必须让用户看见（M10）：旧实现把它置成"没有更多"，
                // 错误被伪装成正常结束。现在进独立的失败态，footer 给出「重试」入口。
                AppLog.w(TAG, "loadMoreTimeline failed, entering failed state", t)
                _timelineLoadFailed.value = true
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    /** 用户点了 footer 的「重试」：清失败态并再试一次 */
    fun retryTimeline() {
        _timelineLoadFailed.value = false
        loadMoreTimeline()
    }

    private suspend fun loadMedMap(records: List<DoseRecordEntity>): Map<Long, MedicationEntity> {
        val ids = records.map { it.medicationId }.distinct()
        if (ids.isEmpty()) return emptyMap()
        return medDao.getAllMedications()
            .filter { it.id in ids }
            .associateBy { it.id }
    }

    private suspend fun List<DoseRecordEntity>.toTimelineItems(): List<TimelineItem> {
        val medMap = loadMedMap(this)
        return map { it.toTimelineItem(medMap) }
    }

    private fun DoseRecordEntity.toTimelineItem(medMap: Map<Long, MedicationEntity>): TimelineItem {
        val zdt = java.time.Instant.ofEpochMilli(actualTs).atZone(TIME_ZONE)
        return TimelineItem(
            record = this,
            medication = medMap[medicationId],
            timeLabel = TIME_FORMATTER.format(zdt),
            isManual = slotId == null
        )
    }

    /**
     * 按本地日期分组，组内按实际时刻倒序。
     *
     * 分组在 ViewModel 而不是 Composable 里做：分组是 O(n) 的确定性计算，
     * 放进重组会每帧重算；而且"分几节"是数据形状问题，不该由 UI 决定。
     */
    private fun groupByDay(items: List<TimelineItem>, today: LocalDate): List<TimelineDay> =
        items
            .groupBy { java.time.Instant.ofEpochMilli(it.record.actualTs).atZone(TIME_ZONE).toLocalDate() }
            .toSortedMap(compareByDescending { it })
            .map { (date, dayItems) ->
                TimelineDay(
                    date = date,
                    headerLabel = buildString {
                        append(dayLabelOf(date)).append(", ")
                        // 当年才省略世纪：流水会一路往前翻好几年，
                        // 一律写 "26/9/29" 会让 2026 和 2029 看不出区别。
                        // 跨年时把年份补全，宁可长一点也不制造歧义。
                        if (date.year != today.year) append(date.year).append('/')
                        append(date.monthValue).append('/').append(date.dayOfMonth)
                    },
                    isToday = date == today,
                    items = dayItems.sortedByDescending { it.record.actualTs },
                    // 只有 COMPLETED 计入合计：SKIPPED 没吃，REVERTED 已被撤销
                    completedDoseMilli = dayItems
                        .filter { it.record.status == com.mcxiaoke.carromed.core.data.model.RecordStatus.COMPLETED }
                        .sumOf { it.record.doseTaken }
                )
            }

    private fun remember7Days(today: LocalDate): List<LocalDate> =
        (6 downTo 0).map { today.minusDays(it.toLong()) }

    /**
     * 星期标签走 WeekLabels 唯一实现（L-9，内部是 `java.time` 本地化格式化）：
     * 中文环境给出「周一」…「周日」，其他语言自动跟随系统（"Mon"…）。
     * SHORT 两字宽度与矩阵列宽 36dp 匹配，英文环境 FULL 的 "Monday" 会撑破布局。
     */
    private fun dayLabelOf(d: LocalDate): String = WeekLabels.short(d.dayOfWeek)

    fun selectTab(tab: Int) {
        _selectedTab.value = tab
    }

    /** 导出服药明细 CSV 报告 (生成 + 系统分享面板) */
    fun exportReport() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                val file = DataExporter.exportDoseRecordsCsv(app, db)
                withContext(Dispatchers.Main) {
                    Toast.makeText(app, app.getString(R.string.prog_export_success, file.name), Toast.LENGTH_LONG).show()
                }
                DataExporter.shareFile(app, file, "text/csv")
            } catch (e: Exception) {
                // 吞异常降级成 Toast 的地方必须留痕（PLAN-LOGGING G4）
                AppLog.w(TAG, "exportReport failed", e)
                withContext(Dispatchers.Main) {
                    // 异常原文不进用户文案（orsbf P1-16 残留），细节只在 AppLog
                    Toast.makeText(app, app.getString(R.string.prog_export_failed_generic), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
