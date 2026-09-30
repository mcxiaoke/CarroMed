package com.mcxiaoke.carromed.ui.screen.cabinet

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.model.MedicationOverview
import com.mcxiaoke.carromed.core.data.entity.PolicyTimeEntity
import com.mcxiaoke.carromed.core.data.entity.SchedulePolicyEntity
import com.mcxiaoke.carromed.core.data.model.PolicyType
import com.mcxiaoke.carromed.R
import com.mcxiaoke.carromed.core.domain.engine.SlotProjectionEngine
import com.mcxiaoke.carromed.core.domain.engine.StatsEngine
import com.mcxiaoke.carromed.ui.component.MedVocab
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

data class MedicationItemUi(
    val overview: MedicationOverview,
    val policy: SchedulePolicyEntity?,
    val times: List<PolicyTimeEntity>,
    val frequencyDescription: String,
    /** 类别的本地化显示名（未知 key 原样）。搜索过滤要能命中中文关键词，
     *  而存库值是稳定 key（如 `COMMON`），解析需要 Context，所以在建条目时一次算好。 */
    val categoryDisplay: String
) {
    /** 代理到实体，避免调用方到处写 `.overview.medication.` */
    val medication: MedicationEntity get() = overview.medication
    /** 由台账聚合出的账面余额（可为负，见 FINAL-PRODUCT D-9） */
    val stock: Float get() = overview.stock
}

/** 药箱排序方式 */
enum class CabinetSortOrder(@StringRes val labelRes: Int) {
    DEFAULT(R.string.cabinet_sort_default),
    NAME(R.string.cabinet_sort_name),
    STOCK_LOW(R.string.cabinet_sort_stock_low),
    EXPIRY_SOON(R.string.cabinet_sort_expiry_soon)
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
            // 类别按本地化显示名匹配（搜"常备"能命中 COMMON），同时保留对原始
            // key / 自由文本的匹配 —— 未知 key 时 categoryDisplay 就是原文。
            it.categoryDisplay.contains(k, ignoreCase = true) ||
            it.medication.category.contains(k, ignoreCase = true)
    }
}

private fun List<MedicationItemUi>.sortedBy(order: CabinetSortOrder): List<MedicationItemUi> =
    when (order) {
        CabinetSortOrder.DEFAULT -> sortedByDescending { it.medication.id }
        CabinetSortOrder.NAME -> sortedBy { it.medication.name }
        // ⭐ 不用 `Float.MAX_VALUE` 哨兵（M7-8）。
        //
        // 哨兵把"未追踪库存"和"账面 3.4 亿片"放进**同一个数值空间**：
        // 一旦某味药真的有巨额账面（囤药、换包装一次入库几千片），
        // 它会被排到未追踪的药**前面**，而用户选中的是"库存由少到多"。
        // 排序键必须是"未追踪优先分组"这个**结构**，不是某个假想的最大值。
        CabinetSortOrder.STOCK_LOW -> sortedWith(
            // null（未追踪）排在最前 —— 它没有库存概念，不该参与数值比较
            compareBy({ if (it.medication.isStockTracked) 1 else 0 }, { if (it.medication.isStockTracked) it.stock else 0f })
        )
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
        medDao.observeAllOverviews()
    ) { tab, kw, order, allMeds ->
        // 计划与时点**批量**取回（zcg #19）：旧实现对每个药品发 2 次 DAO 查询
        // （active 计划 + 该计划时点），每次 combine 发射就是 2N 次查询。
        // 现在总共 2 次，剩下的分组是纯内存操作。分组取首条与逐药 `LIMIT 1`
        // 同结果（DAO 查询按 `version DESC, id DESC` 排序，见其 KDoc）。
        val policiesByMed = policyDao.getAllActivePolicies()
            .groupBy { it.medicationId }
            .mapValues { (_, v) -> v.first() }
        val timesByPolicy = policyDao.getTimesForPolicies(policiesByMed.values.map { it.id })
            .groupBy { it.policyId }
        CabinetUiState(
            selectedTab = tab,
            keyword = kw,
            sortOrder = order,
            activeList = allMeds.filter { !it.medication.isArchived }
                .map { buildItemUi(it, policiesByMed[it.id], timesByPolicy) },
            archivedList = allMeds.filter { it.medication.isArchived }
                .map { buildItemUi(it, policiesByMed[it.id], timesByPolicy) },
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

    private fun buildItemUi(
        overview: MedicationOverview,
        policy: SchedulePolicyEntity?,
        timesByPolicy: Map<Long, List<PolicyTimeEntity>>
    ): MedicationItemUi {
        val med = overview.medication
        val times = policy?.let { timesByPolicy[it.id] } ?: emptyList()

        val timeStr = times.joinToString(", ") { it.timeOfDay }
        val app = getApplication<Application>()
        val perDay = app.getString(R.string.cabinet_freq_times, times.size)
        val freqDesc = when (policy?.policyType) {
            PolicyType.DAILY -> app.getString(R.string.cabinet_freq_daily, perDay, timeStr)
            PolicyType.INTERVAL -> {
                // ⭐ 与库存页逐字同口径（M4-4）。
                //
                // 旧实现：`if (n <= 2) "隔天" else "每隔 ${n-1} 天"`。
                // 两个问题：
                //   ① `n == 1`（**每天**）被标成「隔天」—— 引擎语义是每 1 天一次 = 每天，
                //      文案与实际排班**相反**；
                //   ② 措辞与库存页的「每 N 天」不统一，同一个药在两个页面被描述成两件事。
                //
                // 统一为「n<=1 每天 / n==2 隔天 / 其余 每 n 天」。
                // 注意是「每 n 天」而不是「每隔 n-1 天」：中文里后者读起来是同一个意思，
                // 但两个页面用两种写法会让人怀疑它们算的是不同的事。
                val n = policy.intervalDays
                val intervalText = when {
                    n <= 1 -> app.getString(R.string.cabinet_freq_everyday)
                    n == 2 -> app.getString(R.string.cabinet_freq_every_other_day)
                    else -> app.getString(R.string.cabinet_freq_every_n_days, n)
                }
                app.getString(R.string.cabinet_freq_pattern, intervalText, perDay, timeStr)
            }
            PolicyType.DAYS_OF_WEEK -> {
                val dayNames = listOf(
                    R.string.cabinet_freq_dow_1,
                    R.string.cabinet_freq_dow_2,
                    R.string.cabinet_freq_dow_3,
                    R.string.cabinet_freq_dow_4,
                    R.string.cabinet_freq_dow_5,
                    R.string.cabinet_freq_dow_6,
                    R.string.cabinet_freq_dow_7
                )
                val picked = policy.daysOfWeek.sorted()
                    .joinToString("·") { app.getString(dayNames.getOrElse(it - 1) { R.string.cabinet_freq_dow_unknown }) }
                app.getString(R.string.cabinet_freq_weekly, picked, timeStr)
            }
            PolicyType.CYCLE ->
                app.getString(R.string.cabinet_freq_cycle, policy.cycleOnDays, policy.cycleOffDays, timeStr)
            PolicyType.PRN -> app.getString(R.string.cabinet_freq_prn)
            null ->
                if (times.isNotEmpty()) app.getString(R.string.cabinet_freq_daily_no_policy, perDay, timeStr)
                else app.getString(R.string.cabinet_freq_no_schedule)
        }

        return MedicationItemUi(
            overview = overview,
            policy = policy,
            times = times,
            frequencyDescription = freqDesc,
            categoryDisplay = MedVocab.categoryRes(med.category)?.let { app.getString(it) } ?: med.category
        )
    }
}
