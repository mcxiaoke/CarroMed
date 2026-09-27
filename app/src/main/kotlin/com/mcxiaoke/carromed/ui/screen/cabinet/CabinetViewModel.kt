package com.mcxiaoke.carromed.ui.screen.cabinet

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.entity.PolicyTimeEntity
import com.mcxiaoke.carromed.core.data.entity.SchedulePolicyEntity
import com.mcxiaoke.carromed.core.data.model.PolicyType
import com.mcxiaoke.carromed.core.domain.engine.SlotProjectionEngine
import com.mcxiaoke.carromed.core.domain.engine.StatsEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

data class MedicationItemUi(
    val medication: MedicationEntity,
    val policy: SchedulePolicyEntity?,
    val times: List<PolicyTimeEntity>,
    val frequencyDescription: String
)

/** 药箱排序方式 */
enum class CabinetSortOrder(val label: String) {
    DEFAULT("默认 (最近添加)"),
    NAME("按名称"),
    STOCK_LOW("库存由少到多"),
    EXPIRY_SOON("临期优先")
}

data class CabinetUiState(
    val selectedTab: Int = 0, // 0: 正在服用, 1: 已停药归档
    val keyword: String = "",
    val sortOrder: CabinetSortOrder = CabinetSortOrder.DEFAULT,
    val activeList: List<MedicationItemUi> = emptyList(),
    val archivedList: List<MedicationItemUi> = emptyList(),
    val isLoading: Boolean = true
) {
    val filteredActive: List<MedicationItemUi>
        get() = activeList.filterBy(keyword).sortedBy(sortOrder)
    val filteredArchived: List<MedicationItemUi>
        get() = archivedList.filterBy(keyword)
}

private fun List<MedicationItemUi>.filterBy(kw: String): List<MedicationItemUi> {
    val k = kw.trim()
    if (k.isEmpty()) return this
    return filter {
        it.medication.name.contains(k, ignoreCase = true) ||
            (it.medication.alias?.contains(k, ignoreCase = true) == true) ||
            it.medication.category.contains(k, ignoreCase = true)
    }
}

private fun List<MedicationItemUi>.sortedBy(order: CabinetSortOrder): List<MedicationItemUi> =
    when (order) {
        CabinetSortOrder.DEFAULT -> sortedByDescending { it.medication.id }
        CabinetSortOrder.NAME -> sortedBy { it.medication.name }
        CabinetSortOrder.STOCK_LOW ->
            sortedBy { if (it.medication.isStockTracked) it.medication.currentStock else Float.MAX_VALUE }
        CabinetSortOrder.EXPIRY_SOON ->
            sortedBy { it.medication.expiryDate.takeIf { d -> d.isNotBlank() } ?: "9999-12-31" }
    }

@OptIn(ExperimentalCoroutinesApi::class)
class CabinetViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val medDao = db.medicationDao()
    private val policyDao = db.schedulePolicyDao()

    private val _selectedTab = MutableStateFlow(0)
    private val _keyword = MutableStateFlow("")
    private val _sortOrder = MutableStateFlow(CabinetSortOrder.DEFAULT)

    val uiState: StateFlow<CabinetUiState> = combine(
        _selectedTab,
        _keyword,
        _sortOrder,
        medDao.observeActiveMedications(),
        medDao.observeArchivedMedications()
    ) { tab, kw, order, activeMeds, archivedMeds ->
        CabinetUiState(
            selectedTab = tab,
            keyword = kw,
            sortOrder = order,
            activeList = activeMeds.map { buildItemUi(it) },
            archivedList = archivedMeds.map { buildItemUi(it) },
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = CabinetUiState()
    )

    fun selectTab(index: Int) {
        _selectedTab.value = index
    }

    fun setKeyword(kw: String) {
        _keyword.value = kw
    }

    fun setSortOrder(order: CabinetSortOrder) {
        _sortOrder.value = order
    }

    private suspend fun buildItemUi(med: MedicationEntity): MedicationItemUi {
        val policy = policyDao.getActivePolicyForMedication(med.id)
        val times = if (policy != null) policyDao.getTimesForPolicy(policy.id) else emptyList()

        val timeStr = times.joinToString(", ") { it.timeOfDay }
        val perDay = if (times.isEmpty()) "0 次" else "${times.size} 次"
        val freqDesc = when (policy?.policyType) {
            PolicyType.DAILY -> "每天 $perDay · $timeStr"
            PolicyType.INTERVAL -> {
                val intervalText = if (policy.intervalDays <= 2) "隔天" else "每隔 ${policy.intervalDays - 1} 天"
                "$intervalText $perDay · $timeStr"
            }
            PolicyType.DAYS_OF_WEEK -> {
                val dayNames = listOf("一", "二", "三", "四", "五", "六", "日")
                val picked = policy.daysOfWeek.sorted().joinToString("·") { dayNames.getOrElse(it - 1) { "?" } }
                "每周 $picked · $timeStr"
            }
            PolicyType.CYCLE -> "周期 ${policy.cycleOnDays}天服/${policy.cycleOffDays}天停 · $timeStr"
            PolicyType.PRN -> "按需服用 · 不设定时闹钟"
            null -> if (times.isNotEmpty()) "每日 $perDay · $timeStr" else "暂无排班"
        }

        return MedicationItemUi(
            medication = med,
            policy = policy,
            times = times,
            frequencyDescription = freqDesc
        )
    }
}
