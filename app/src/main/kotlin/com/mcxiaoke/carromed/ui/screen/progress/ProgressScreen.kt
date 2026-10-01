package com.mcxiaoke.carromed.ui.screen.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcxiaoke.carromed.R
import com.mcxiaoke.carromed.core.data.model.RecordStatus
import com.mcxiaoke.carromed.core.domain.engine.StatsEngine
import com.mcxiaoke.carromed.core.domain.model.Dose
import com.mcxiaoke.carromed.ui.component.CarroMedTopAppBar
import com.mcxiaoke.carromed.ui.component.MedVocab
import com.mcxiaoke.carromed.ui.component.Quantity
import com.mcxiaoke.carromed.ui.theme.OnSuccessGreenContainer
import com.mcxiaoke.carromed.ui.theme.SuccessGreen
import com.mcxiaoke.carromed.ui.theme.SuccessGreenContainer
import com.mcxiaoke.carromed.ui.theme.WarningAmber
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import java.util.Locale

@Composable
fun ProgressScreen(
    viewModel: ProgressViewModel,
    onOpenDose: (Long?, Long) -> Unit = { _, _ -> },
    onNavigateToMedHistory: (Long) -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    /**
     * 触底加载更早的流水。
     *
     * 阈值取"距离末尾 5 项"，而不是"到底了才加载"：等真的滚到最后一项再查库，
     * 用户会看到一段明显的空白停顿。先把数据备好，视觉上就是滚不完的连续列表。
     */
    // ⚠️ key 必须带上 hasMoreTimeline（orsbf P2-12）：它是在 loadMoreTimeline()
    // 里才翻成 true 的普通捕获值，不在 snapshotFlow 的观察范围里；
    // 若只以 selectedTab 为 key，首次组合时 hasMoreTimeline = false 的闭包
    // 会被捕获，之后 filter 永远读到旧的 false —— **触底加载一次都不会触发**。
    LaunchedEffect(uiState.selectedTab, uiState.hasMoreTimeline) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible to info.totalItemsCount
        }
            .distinctUntilChanged()
            .filter { (lastVisible, total) ->
                uiState.hasMoreTimeline && lastVisible >= total - LOAD_MORE_THRESHOLD
            }
            .collect { viewModel.loadMoreTimeline() }
    }

    Scaffold(
        topBar = {
            CarroMedTopAppBar(
                title = stringResource(R.string.prog_title)
            )
        }
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item(key = "tabs") { ProgressTabSelector(uiState.selectedTab, viewModel::selectTab) }

            if (uiState.isLoading) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                return@LazyColumn
            }

            if (uiState.selectedTab == 0) {
                if (uiState.matrixItems.isEmpty()) {
                    item(key = "matrix-empty") {
                        EmptyStateCard(stringResource(R.string.prog_empty_matrix))
                    }
                } else {
                    item(key = "overall") { OverallAdherenceCard(uiState.overallAdherence, uiState) }
                    items(
                        count = uiState.matrixItems.size,
                        key = { "m-${uiState.matrixItems[it].medication.id}" }
                    ) { idx ->
                        MedicationMatrixCard(uiState.matrixItems[idx], onNavigateToMedHistory)
                    }
                }
            } else {
                if (uiState.timelineDays.isEmpty()) {
                    item(key = "timeline-empty") {
                        EmptyStateCard(stringResource(R.string.prog_empty_timeline))
                    }
                } else {
                    uiState.timelineDays.forEach { day ->
                        item(key = "day-${day.date}") { TimelineDayHeader(day) }
                        items(
                            count = day.items.size,
                            key = { "rec-${day.items[it].record.id}" }
                        ) { idx -> TimelineRow(day.items[idx], onOpenDose) }
                    }
                    item(key = "timeline-tail") {
                        TimelineFooter(
                            loading = uiState.isTimelineLoadingMore,
                            hasMore = uiState.hasMoreTimeline
                        )
                    }
                }
            }
        }
    }
}

/** 距离列表末尾多少项时预取下一页（见 [ProgressScreen] 里的注释）。 */
private const val LOAD_MORE_THRESHOLD = 5

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProgressTabSelector(selectedTab: Int, onSelect: (Int) -> Unit) {
    val tabs = listOf(stringResource(R.string.prog_tab_matrix), stringResource(R.string.prog_tab_timeline))
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth()
    ) {
        tabs.forEachIndexed { index, title ->
            val selected = selectedTab == index
            SegmentedButton(
                selected = selected,
                onClick = { onSelect(index) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = tabs.size),
                icon = {},
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun OverallAdherenceCard(rate: Float, uiState: ProgressUiState) {
    val completed = uiState.matrixItems.sumOf { it.completedCount }
    val decided = uiState.matrixItems.sumOf { it.decidedCount }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.prog_overall_adherence_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(Modifier.height(4.dp))
                // 同上：没有到期样本时不谎报 100%
                if (decided > 0) {
                    Text(
                        text = String.format(Locale.getDefault(), "%.1f%%", rate * 100),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = stringResource(R.string.prog_overall_adherence_detail, completed, decided),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                } else {
                    Text(
                        text = "—",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = stringResource(R.string.prog_overall_no_due),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

@Composable
private fun MedicationMatrixCard(item: MedMatrixItem, onNavigateToHistory: (Long) -> Unit) {
    val cdTakenSummary = stringResource(R.string.prog_cd_taken_summary, item.completedCount, item.decidedCount)
    val cdNoDueTask = stringResource(R.string.prog_cd_no_due_task)
    val cdViewDetail = stringResource(R.string.prog_cd_view_detail)
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            // 点整卡进「该药的历史」（对标 MyTherapy 的进展页每行右侧 `>`）
            .clickable { onNavigateToHistory(item.medication.id) }
            // 整卡读成一个节点：TalkBack 否则会逐个念出 7 个星期标签 + 7 个状态点，
            // 用户听到的是"周三 全部完成 周四 全部完成 …"却不知道这是哪个药。
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append(item.medication.name)
                    if (item.decidedCount > 0) {
                        append(cdTakenSummary)
                    } else {
                        append(cdNoDueTask)
                    }
                    append(cdViewDetail)
                }
            },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                runCatching {
                                    Color(android.graphics.Color.parseColor(item.medication.colorHex))
                                }.getOrDefault(MaterialTheme.colorScheme.primary)
                            )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = item.medication.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    // 分母为 0 时不显示百分比 —— "0/0 = 100%" 是统计上的空集约定，
                    // 但对用户是误导，会看起来像"表现完美"。
                    if (item.decidedCount > 0) {
                        Text(
                            text = String.format(Locale.getDefault(), "%.0f%%", item.completionRate * 100),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(R.string.prog_dose_count, item.completedCount, item.decidedCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.prog_no_due_short),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                item.days.forEach { day ->
                    Column(
                        modifier = Modifier.width(36.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = day.dayLabel,
                            style = MaterialTheme.typography.labelSmall,
                            // ⚠️ 判「是否今天」用 `day.isToday`，不用 `dayLabel == "今日"`（M7-9）。
                            // 把判据挂在展示文案上，改一次文案高亮就静默失效。
                            color = if (day.isToday) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Normal
                        )
                        Spacer(Modifier.height(6.dp))
                        // ⭐ 多次服用的药一天要画多个点（对标 MyTherapy）。
                        //
                        // 单圆点无法区分「1 次全服」与「2 次全服」——
                        // 环孢素每天两次，用户看到的却是和每天一次完全相同的绿勾，
                        // 而卡片右侧写着 100%、2/2 次。**数字与图形自相矛盾**。
                        if (day.total > 1) MultiDayDots(day, item.medication.colorHex)
                        else DayDot(day)
                    }
                }
            }
        }
    }
}

/**
 * 一天内多次服药时，按「每次一个点」纵向排列（对标 MyTherapy 的双点画法）。
 *
 * 判据是 `day.total > 1` 而不是 `completed > 1`：
 * 半天只吃了一次（completed=1, total=2）恰恰是最需要看出"少了一剂"的形态，
 * 而它按 `completed` 判断的话仍会退化成单点，缺陷照旧。
 *
 * 已服 / 未服各占一格，已服用实心色、未服用淡色，
 * 于是 `2/2` 是两个实心点、`1/2` 是一个实心一个淡 —— 一眼可分。
 */
@Composable
private fun MultiDayDots(day: DayAdherence, colorHex: String) {
    val base = runCatching { Color(android.graphics.Color.parseColor(colorHex)) }
        .getOrDefault(MaterialTheme.colorScheme.primary)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        repeat(day.total) { i ->
            val taken = i < day.completed
            Box(
                Modifier
                    .size(15.dp)
                    .clip(CircleShape)
                    .background(if (taken) SuccessGreen else base.copy(alpha = 0.18f))
            )
            if (i != day.total - 1) Spacer(Modifier.height(3.dp))
        }
    }
}

/**
 * 单日状态点。四态用不同图标 + 颜色区分，让"还没到 / 主动跳过 / 逾期漏服"不再
 * 共用同一个灰点（此前 PENDING/SKIPPED/EXPIRED 全部渲染为 `MoreHoriz`，语义完全丢失）。
 */
@Composable
private fun DayDot(day: DayAdherence) {
    val bg: Color
    val icon: @Composable () -> Unit
    when (day.state) {
        StatsEngine.DayAdherenceState.FULLY_TAKEN -> {
            bg = SuccessGreen
            icon = {
                Icon(
                    Icons.Default.Check,
                    contentDescription = stringResource(R.string.prog_cd_fully_taken),
                    tint = Color.White,
                    modifier = Modifier.size(15.dp)
                )
            }
        }

        StatsEngine.DayAdherenceState.PARTIAL -> {
            // ⚠️ 配色不是随手挑的（M7-9）。旧实现是
            // `bg = SuccessGreen.copy(alpha = 0.45f)` + **白字**，
            // 合成后底色约 #96D6AE，白字对比度只有 **1.68:1** ——
            // 而"1/2"是**文字**，适用 WCAG 1.4.3 的 4.5:1（大字豁免要 18.66sp，
            // 这里只有 9sp，够不着）。也就是说这一格几乎读不出来，
            // 而它恰恰是"部分完成"这个最需要看清的状态。
            //
            // 改成"浅底 + 深字"（`SuccessGreenContainer` 配 `OnSuccessGreenContainer`），
            // 实测 **8.30:1**。顺带保留了"部分完成比全部完成浅一档"的视觉分级：
            // 之前靠调 alpha 表达的语义，现在由底色本身承担，
            // 不再依赖"半透明叠白"这种算出来的近似。
            bg = SuccessGreenContainer
            icon = {
                Text(
                    text = "${day.completed}/${day.total}",
                    color = OnSuccessGreenContainer,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        StatsEngine.DayAdherenceState.MISSED -> {
            bg = WarningAmber
            icon = {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.prog_cd_missed),
                    tint = Color.White,
                    modifier = Modifier.size(15.dp)
                )
            }
        }

        StatsEngine.DayAdherenceState.SKIPPED -> {
            bg = MaterialTheme.colorScheme.outline
            icon = {
                Icon(
                    Icons.Default.RemoveCircleOutline,
                    contentDescription = stringResource(R.string.prog_cd_skipped),
                    tint = Color.White,
                    modifier = Modifier.size(15.dp)
                )
            }
        }

        StatsEngine.DayAdherenceState.UPCOMING -> {
            bg = MaterialTheme.colorScheme.surfaceVariant
            icon = {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
        }

        StatsEngine.DayAdherenceState.NONE -> {
            bg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            icon = {
                Box(
                    Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                )
            }
        }
    }

    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center
    ) { icon() }
}

@Composable
private fun TimelineDayHeader(day: TimelineDay) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        Text(
            text = if (day.isToday) stringResource(R.string.prog_today_header, day.headerLabel) else day.headerLabel,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = if (day.isToday) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface
        )
        // 只在真有可计入的量时才显示合计：显示「合计 0 片」对全被跳过的日子是噪音
        if (day.completedDoseMilli > 0) {
            Text(
                text = Quantity.withUnit(
                    Dose(day.completedDoseMilli).asFloat,
                    stringResource(R.string.prog_unit_tablet)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TimelineRow(item: TimelineItem, onOpenDose: (Long?, Long) -> Unit) {
    val unit = item.medication?.unit ?: stringResource(R.string.prog_unit_tablet)
    // doseTaken 是整数毫单位（D-7）。此处**不能**写 `doseTaken % 1f == 0f` 那类判断：
    // 它能编译（Kotlin 允许 Int % Float）却恒为真，会把 1 片显示成「1000 片」。
    val dose = Dose(item.record.doseTaken).asFloat
    val manualLabel = stringResource(R.string.prog_manual_dose)
    val retrospectiveLabel = stringResource(R.string.prog_retrospective)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            // 整行可点进详情（统一记录详情页）。
            // 这里不会出现已撤销的记录 —— 它们在 SQL 层就被排除了
            // （见 `DoseRecordDao.observeLatestRecords`：撤销是动作不是状态）。
            .clickable { onOpenDose(item.record.slotId, item.record.id) },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.medication?.name ?: stringResource(R.string.prog_deleted_medication),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                val subtitle = buildString {
                    if (dose > 0f) append(Quantity.fmt(dose)).append(' ').append(unit)
                    if (item.isManual) {
                        if (isNotEmpty()) append(" · ")
                        append(manualLabel)
                    }
                    if (item.record.isRetrospective) {
                        if (isNotEmpty()) append(" · ")
                        append(retrospectiveLabel)
                    }
                    if (!item.record.note.isNullOrBlank() || item.record.noteKey != null) {
                        if (isNotEmpty()) append(" · ")
                        append(
                            MedVocab.recordNoteDisplay(
                                LocalContext.current,
                                item.record.noteKey,
                                item.record.note
                            ).orEmpty()
                        )
                    }
                }
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            RecordStatusChip(item.timeLabel, item.record.status)
        }
    }
}

/**
 * 服药事实的状态 chip。
 *
 * 与 7 天矩阵里 [DayDot] 的 [StatsEngine.DayAdherenceState] 是两套东西：
 * 那边画的是**排班**状态（含"待服用 / 已漏服"），这边画的是**事实**状态
 * （含"已撤销"、不含待服）。
 * 把两套塞进一个枚举再靠 `else` 兜底，就是本轮 M7-2 修掉的那个 bug。
 *
 * ⚠️ `REVERTED` 分支**正常不会渲染**：流水与历史的查询已在 SQL 层排除它
 * （撤销是动作不是状态，见 `DoseRecordDao.observeLatestRecords`）。
 * 保留显式分支而不是补一个 `else -> "已服"`：一旦将来某处漏了过滤，
 * 显式分支还能让"已撤销"如实显示，而 `else` 会把它伪装成绿色的「已服」——
 * 同一屏自相矛盾，且没有任何报错。
 */
@Composable
private fun RecordStatusChip(timeLabel: String, status: RecordStatus) {
    val (text, color) = when (status) {
        RecordStatus.COMPLETED -> stringResource(R.string.prog_status_taken) to SuccessGreen
        RecordStatus.SKIPPED -> stringResource(R.string.prog_status_skipped) to MaterialTheme.colorScheme.onSurfaceVariant
        RecordStatus.REVERTED -> stringResource(R.string.prog_status_reverted) to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = timeLabel,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(8.dp))
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = color.copy(alpha = 0.14f)
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun TimelineFooter(loading: Boolean, hasMore: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            loading -> CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp
            )
            // 说清"到底了"而不是留一片空白：用户不知道是加载失败还是没有更多
            hasMore -> Text(
                text = stringResource(R.string.prog_timeline_more),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            else -> Text(
                text = stringResource(R.string.prog_timeline_end),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun EmptyStateCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Text(
            text = text,
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 20.sp
        )
    }
}
