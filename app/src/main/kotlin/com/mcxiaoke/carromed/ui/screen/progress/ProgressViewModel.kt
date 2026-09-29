package com.mcxiaoke.carromed.ui.screen.progress

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.DataExporter
import com.mcxiaoke.carromed.core.data.entity.DoseRecordEntity
import com.mcxiaoke.carromed.core.data.entity.DoseSlotEntity
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.domain.CurrentDateHolder
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
import java.time.LocalDate

data class DayAdherence(
    val date: LocalDate,
    val dayLabel: String,
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

data class TimelineItem(
    val slot: DoseSlotEntity,
    val medication: MedicationEntity?,
    val record: DoseRecordEntity?
)

data class ProgressUiState(
    val selectedTab: Int = 0,
    val matrixItems: List<MedMatrixItem> = emptyList(),
    val todayTimeline: List<TimelineItem> = emptyList(),
    val overallAdherence: Float = 0f,
    val isLoading: Boolean = true
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

    private val _selectedTab = MutableStateFlow(0)

    /**
     * "今天"来自 [CurrentDateHolder]，不是 `LocalDate.now()` 字段（M3-2）。
     *
     * 进程跨夜存活时，字段版会把 7 天矩阵永久冻结在昨天，
     * 且最后一列还标着「今日」。见 `CurrentDateHolder` 的 KDoc。
     */
    private val todayFlow = CurrentDateHolder.today

    val uiState: StateFlow<ProgressUiState> = todayFlow.flatMapLatest { today ->
        val weekDates = remember7Days(today)
        combine(
            _selectedTab,
            medDao.observeActiveOverviews(),
            slotDao.observeSlotStatusCounts(
                weekDates.first().format(SlotProjectionEngine.DATE_FORMATTER),
                weekDates.last().format(SlotProjectionEngine.DATE_FORMATTER)
            ),
            slotDao.observeSlotsForDate(today.format(SlotProjectionEngine.DATE_FORMATTER))
        ) { tab, overviews, statusRows, todaySlots ->
            val medMap = overviews.associateBy { it.id }
            val byMedDate = StatsEngine.aggregateBreakdowns(statusRows)
            val dateStrs = weekDates.map { it.format(SlotProjectionEngine.DATE_FORMATTER) }

            val matrixItems = overviews.map { overview ->
                val byDate = byMedDate[overview.id].orEmpty()
                val days = weekDates.map { d ->
                    val b = byDate[d.format(SlotProjectionEngine.DATE_FORMATTER)]
                        ?: StatsEngine.DayStatusBreakdown()
                    DayAdherence(
                        date = d,
                        // ⚠️ 不用 `dayLabel == "今日"` 这种字符串比较（M7-9）：
                        // 标签一改文案，判定就静默失效。
                        dayLabel = if (d == today) "今日" else dayLabelOf(d),
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

            val timeline = todaySlots.map { slot ->
                TimelineItem(
                    slot = slot,
                    medication = medMap[slot.medicationId]?.medication,
                    record = if (slot.status == com.mcxiaoke.carromed.core.data.model.SlotStatus.COMPLETED) {
                        recordDao.getRecordBySlotId(slot.id)
                    } else null
                )
            }.sortedBy { it.slot.scheduledTs }

            ProgressUiState(
                selectedTab = tab,
                matrixItems = matrixItems,
                todayTimeline = timeline,
                overallAdherence = StatsEngine.adherenceOf(overall),
                isLoading = false
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ProgressUiState()
    )

    private fun remember7Days(today: LocalDate): List<LocalDate> =
        (6 downTo 0).map { today.minusDays(it.toLong()) }

    private fun dayLabelOf(d: LocalDate): String = when (d.dayOfWeek.value) {
        1 -> "周一"; 2 -> "周二"; 3 -> "周三"; 4 -> "周四"
        5 -> "周五"; 6 -> "周六"; else -> "周日"
    }

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
