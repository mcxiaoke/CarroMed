package com.mcxiaoke.carromed.ui.screen.stats

import android.app.Application
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcxiaoke.carromed.R
import com.mcxiaoke.carromed.core.domain.AppLog
import com.mcxiaoke.carromed.core.domain.model.Dose
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.DataExporter
import com.mcxiaoke.carromed.core.data.model.MedicationOverview
import com.mcxiaoke.carromed.core.domain.engine.SlotProjectionEngine
import com.mcxiaoke.carromed.core.domain.engine.StatsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

data class MedicationConsumption(
    val rank: Int,
    val medicationId: Long,
    val medicationName: String,
    val totalDose: Float,
    val unit: String
)

/** 依从率拆解，让用户看懂"100% 之外漏在哪" */
data class AdherenceBreakdown(
    val completed: Int = 0,
    val skipped: Int = 0,
    val missed: Int = 0,
    val pending: Int = 0
)

data class StatsUiState(
    val selectedPeriod: Int = 0,
    val totalDoses: Float = 0f,
    /** 单一单位时的单位；`null` 表示无消耗或存在多种单位（此时不应显示总量数字） */
    val totalDoseUnit: String? = null,
    /** 按单位分组的累计用量，供多单位时逐项展示 */
    val totalDosesByUnit: Map<String, Float> = emptyMap(),
    /** 是否存在多种单位 —— UI 据此决定"显示总量"还是"逐单位列出" */
    val mixedUnits: Boolean = false,
    val adherenceRate: Float = 0f,
    val breakdown: AdherenceBreakdown = AdherenceBreakdown(),
    val activeMedCount: Int = 0,
    val scheduledDoseCount: Int = 0,
    val rankings: List<MedicationConsumption> = emptyList(),
    val isLoading: Boolean = true
)

/** 统计周期：周 / 月 / 年 */
enum class StatsPeriod(@StringRes val labelRes: Int, val days: Int) {
    WEEK(R.string.stats_period_week, 7),
    MONTH(R.string.stats_period_month, 30),
    YEAR(R.string.stats_period_year, 365)
}

/**
 * 统计页的状态构建器。**普通类，不是 ViewModel**。
 *
 * ## 为什么不把它做成 `StatsViewModel` 的构造参数（真实踩过的坑）
 *
 * 我最初给 `StatsViewModel` 加了第二个带默认值的参数 `dbOverride: AppDatabase? = null`
 * 以便测试注入内存库。**Kotlin 的默认参数不会生成单参 Java 构造器**
 * （除非标 `@JvmOverloads`），而 `viewModel()` 走的 `AndroidViewModelFactory`
 * 是用 `getConstructor(Application::class.java)` **反射**找构造器的 ——
 * 于是统计页一点就崩：
 *
 * ```
 * Caused by: java.lang.NoSuchMethodException:
 *   StatsViewModel.<init> [class android.app.Application]
 * ```
 *
 * **编译通过、单测全绿、只有点开那一页才崩** —— 典型的"测试覆盖不到接线"缺口，
 * 也正是 AGENTS §4 坚持"UI 改动必须跑走查看图"的原因。
 *
 * 所以正确做法是**保持 ViewModel 的单参构造器不变**，把可测的部分抽成普通类。
 * 这条纪律对所有 `AndroidViewModel` 都成立：**不要给构造器加参数**。
 */
class StatsStateBuilder(private val db: AppDatabase) {

    private val medDao = db.medicationDao()
    private val recordDao = db.doseRecordDao()
    private val slotDao = db.doseSlotDao()
    private val zoneId = ZoneId.systemDefault()

    /**
     * 统计口径（全 App 统一，与进展页打卡矩阵同源）：
     * - **依从率**基于 `dose_slots`（按 *计划时间* 归属），
     *   分母 = 已服 + 跳过 + 漏服(EXPIRED)，待服不进分母；
     * - **累计用量**基于 `dose_records`（按 *实际服药时刻* 归属），只算 COMPLETED 事实。
     *
     * 此前此页是**完全硬编码的假数据**（1428 片 / 98.2% / 写死的排行榜药名），
     * 用户真实只有 3 条记录却显示"1428 片"，属于会误导健康决策的严重缺陷。
     */
    suspend fun build(period: StatsPeriod): StatsUiState {
        val overviews: List<MedicationOverview> = medDao.getActiveOverviews()
        val today = LocalDate.now()
        val endDate = today.format(SlotProjectionEngine.DATE_FORMATTER)
        val startDate = today.minusDays(period.days - 1L)
            .format(SlotProjectionEngine.DATE_FORMATTER)

        val startTs = LocalDate.parse(startDate).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val endTs = today.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli() - 1

        // 1. 依从率：按计划日期聚合槽位状态（**已归档的药品不进分母**，决策 E）
        val slotRows = slotDao.getSlotStatusCounts(startDate, endDate)
        val medBreakdowns = StatsEngine.aggregateBreakdowns(slotRows)
        val allBreakdowns = medBreakdowns.values.fold(
            StatsEngine.DayStatusBreakdown()
        ) { acc, byDate ->
            acc + byDate.values.fold(StatsEngine.DayStatusBreakdown()) { a, b -> a + b }
        }

        // 2. 累计用量：按实际服药时刻聚合
        val doseSums = recordDao.getDoseSumByMedicationInRange(startTs, endTs)
        val medById = overviews.associateBy { it.id }

        // ⚠️ 先过滤掉已归档药品再排名。原先用 mapIndexedNotNull，索引在过滤前就被占用，
        // 导致"第 1 名若已归档，UI 会从『2.』开始显示，永远没有第 1 名"。
        val rankings = doseSums
            .mapNotNull { row -> medById[row.medicationId]?.let { row to it } }
            .sortedByDescending { (row, _) -> row.totalDose }
            .mapIndexed { index, (row, med) ->
                MedicationConsumption(
                    rank = index + 1,
                    medicationId = row.medicationId,
                    medicationName = med.medication.name,
                    totalDose = Dose(row.totalDose).asFloat,
                    unit = med.medication.unit
                )
            }

        // 跨单位求和没有意义（30 片 + 5 ml ≠ 35 片）。按单位分组，
        // 只有全部药品同单位时才给出一个"总量"大数字。
        //
        // ⭐ 走领域层的 [StatsEngine.groupByUnit]（M6-1）：旧实现内联在这里，
        // 而 `StatsUnitGroupingTest` 在**自己的文件里重写了一遍** ——
        // 那是影子实现，把这里改回跨单位求和，测试仍然全绿。
        //
        // 传整数毫单位而不是展示值：内联版用 `sumOf { it.totalDose.toDouble() }.toFloat()`
        // 做 Float 累加，几十条记录就会漂成 9.999998 这种值（D-7）。
        val byUnit: Map<String, Float> = StatsEngine.groupByUnit(
            doseSums.mapNotNull { row ->
                medById[row.medicationId]?.let { it.medication.unit to row.totalDose }
            }
        )

        val totalDose: Float
        val totalDoseUnit: String?
        if (byUnit.isEmpty()) {
            totalDose = 0f
            totalDoseUnit = null
        } else if (byUnit.size == 1) {
            val (unit, amount) = byUnit.entries.first()
            totalDose = amount
            totalDoseUnit = unit
        } else {
            // 多单位：不显示总量（宁可不给数字，也不能给错数字）
            totalDose = 0f
            totalDoseUnit = null
        }

        return StatsUiState(
            selectedPeriod = period.ordinal,
            totalDoses = totalDose,
            totalDoseUnit = totalDoseUnit,
            // 多单位时把分组结果交给 UI 逐单位展示，而不是给一个假的总数
            totalDosesByUnit = byUnit,
            mixedUnits = byUnit.size > 1,
            adherenceRate = StatsEngine.adherenceOf(allBreakdowns),
            breakdown = AdherenceBreakdown(
                completed = allBreakdowns.completed,
                skipped = allBreakdowns.skipped,
                missed = allBreakdowns.missed,
                pending = allBreakdowns.pending
            ),
            activeMedCount = overviews.size,
            scheduledDoseCount = allBreakdowns.total,
            rankings = rankings,
            isLoading = false
        )
    }

    /**
     * 「已产生结论」且药品未归档的槽位总数，**只作为变化探针**。
     *
     * ## 它解决的是"统计页不刷新"（M4-2）
     *
     * `StatsViewModel` 的 `combine` 若只挂「药品概览」，那张表在打卡 / 跳过 / 结算时
     * **一行都不变** ⇒ Flow 不发射 ⇒ 依从率与用量永远停在旧值，
     * 而进展页是刷新的 —— 用户会认为统计坏了，或者更糟：相信那个过期的数字。
     *
     * 用探针而不是直接把聚合 Flow 接进 `combine`：聚合按周期区间查询，
     * 区间随 [StatsPeriod] 变化，Flow 要重建；探针与周期无关，最省事。
     * 它**不参与任何计算**，只负责"有事发生了，叫醒 combine"。
     */
    fun decidedSlotCountProbe(): Flow<Int> = slotDao.observeDecidedSlotCount()

    /**
     * 服药事实计数探针（sba P1-1 残留）：手动补录不产生槽位、只写 `dose_records`，
     * 槽位探针看不到它 —— 纯补录后"累计用量 / 排行榜"就停在旧值。
     * 与槽位探针并列挂进 `combine`，两个来源任何一个变化都会唤醒重算。
     */
    fun recordCountProbe(): Flow<Int> = recordDao.observeRecordCount()
}

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        const val TAG = "StatsViewModel"
    }

    /**
     * ⚠️ **不要给本类的构造器加参数**。
     * `viewModel()` 走 `AndroidViewModelFactory`，它用
     * `getConstructor(Application::class.java)` 反射查找 ——
     * Kotlin 的默认参数**不会**生成单参 Java 构造器，加了参数就崩在
     * `NoSuchMethodException`，而且**编译与单测都发现不了**。
     * 需要注入就抽 collaborator（见 [StatsStateBuilder]）。
     */
    private val builder = StatsStateBuilder(AppDatabase.getInstance(application))

    private val _selectedPeriod = MutableStateFlow(StatsPeriod.WEEK)

    val uiState: StateFlow<StatsUiState> = combine(
        _selectedPeriod,
        builder.decidedSlotCountProbe(),
        builder.recordCountProbe()
    ) { period, _, _ ->
        builder.build(period)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = StatsUiState()
    )

    fun selectPeriod(index: Int) {
        _selectedPeriod.value = StatsPeriod.entries.getOrElse(index) { StatsPeriod.WEEK }
    }

    /** 导出服药明细 CSV (生成 + 系统分享面板) */
    fun exportReport() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                val file = DataExporter.exportDoseRecordsCsv(app, AppDatabase.getInstance(app))
                withContext(Dispatchers.Main) {
                    Toast.makeText(app, app.getString(R.string.stats_export_done, file.name), Toast.LENGTH_LONG).show()
                }
                DataExporter.shareFile(app, file, "text/csv")
            } catch (e: Exception) {
                // 吞异常降级成 Toast 的地方必须留痕（PLAN-LOGGING G4）：
                // e.message 可能为 null，堆栈才是归因依据
                AppLog.w(TAG, "exportReport failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(app, app.getString(R.string.stats_export_failed, e.message), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
