package com.mcxiaoke.carromed.ui.screen.stats

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcxiaoke.carromed.core.domain.model.Dose
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.DataExporter
import com.mcxiaoke.carromed.core.data.model.MedicationOverview
import com.mcxiaoke.carromed.core.domain.engine.SlotProjectionEngine
import com.mcxiaoke.carromed.core.domain.engine.StatsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
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
enum class StatsPeriod(val label: String, val days: Int) {
    WEEK("过去 7 天", 7),
    MONTH("过去 30 天", 30),
    YEAR("过去 1 年", 365)
}

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val medDao = db.medicationDao()
    private val recordDao = db.doseRecordDao()
    private val slotDao = db.doseSlotDao()
    private val zoneId = ZoneId.systemDefault()

    private val _selectedPeriod = MutableStateFlow(StatsPeriod.WEEK)

    /**
     * 统计口径（全 App 统一，与进展页打卡矩阵同源）：
     * - **依从率**基于 `dose_slots`（按 *计划时间* 归属），分母 = 已服 + 跳过 + 漏服(EXPIRED)，待服不进分母；
     * - **累计用量**基于 `dose_records`（按 *实际服药时刻* 归属），只算 COMPLETED 事实。
     *
     * 此前此页是**完全硬编码的假数据**（1428 片 / 98.2% / 写死的排行榜药名），
     * 用户真实只有 3 条记录却显示"1428 片"，属于会误导健康决策的严重缺陷。
     */
    val uiState: StateFlow<StatsUiState> = combine(
        _selectedPeriod,
        medDao.observeActiveOverviews()
    ) { period, overviews ->
        buildState(period, overviews)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = StatsUiState()
    )

    private suspend fun buildState(
        period: StatsPeriod,
        overviews: List<MedicationOverview>
    ): StatsUiState {
        val today = LocalDate.now()
        val endDate = today.format(SlotProjectionEngine.DATE_FORMATTER)
        val startDate = today.minusDays(period.days - 1L)
            .format(SlotProjectionEngine.DATE_FORMATTER)

        val startTs = LocalDate.parse(startDate).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val endTs = today.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli() - 1

        // 1. 依从率：按计划日期聚合槽位状态
        val slotRows = slotDao.getSlotStatusCounts(startDate, endDate)
        val medBreakdowns = StatsEngine.aggregateBreakdowns(slotRows)
        val allBreakdowns = medBreakdowns.values.fold(
            StatsEngine.DayStatusBreakdown()
        ) { acc, byDate -> acc + byDate.values.fold(StatsEngine.DayStatusBreakdown()) { a, b -> a + b } }

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
        val byUnit: Map<String, Float> = rankings
            .groupBy { it.unit }
            .mapValues { (_, rows) -> rows.sumOf { it.totalDose.toDouble() }.toFloat() }

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
            selectedPeriod = _selectedPeriod.value.ordinal,
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

    fun selectPeriod(index: Int) {
        _selectedPeriod.value = StatsPeriod.entries.getOrElse(index) { StatsPeriod.WEEK }
    }

    /** 导出服药明细 CSV (生成 + 系统分享面板) */
    fun exportReport() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                val file = DataExporter.exportDoseRecordsCsv(app, db)
                withContext(Dispatchers.Main) {
                    Toast.makeText(app, "已导出 ${file.name}", Toast.LENGTH_LONG).show()
                }
                DataExporter.shareFile(app, file, "text/csv")
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(app, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
