package com.mcxiaoke.carromed.ui.screen.progress
import com.mcxiaoke.carromed.core.domain.model.Dose

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import com.mcxiaoke.carromed.core.domain.engine.StatsEngine
import com.mcxiaoke.carromed.ui.component.HomeTabHeader
import com.mcxiaoke.carromed.ui.component.Quantity
import com.mcxiaoke.carromed.ui.theme.SuccessGreen
import com.mcxiaoke.carromed.ui.theme.WarningAmber
import java.util.Locale

@Composable
fun ProgressScreen(
    viewModel: ProgressViewModel
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            HomeTabHeader(
                title = "进展追踪",
                actionIcon = Icons.Default.FileDownload,
                actionContentDescription = "导出报告",
                onActionClick = { viewModel.exportReport() }
            )
        }

        item {
            val tabs = listOf("7 天打卡矩阵", "今日服药流水")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                tabs.forEachIndexed { index, title ->
                    val selected = uiState.selectedTab == index
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
                            .clickable { viewModel.selectTab(index) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (uiState.isLoading) {
            item {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            return@LazyColumn
        }

        if (uiState.selectedTab == 0) {
            if (uiState.matrixItems.isEmpty()) {
                item { EmptyStateCard("还没有在服药品。添加第一个药品后，这里会显示近 7 天的真实打卡情况。") }
            } else {
                item { OverallAdherenceCard(uiState.overallAdherence, uiState) }
                items(uiState.matrixItems, key = { it.medication.id }) { item ->
                    MedicationMatrixCard(item)
                }
            }
        } else {
            item { TodayTimelineCard(uiState.todayTimeline) }
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
                    text = "近 7 天整体依从率",
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
                        text = "按时服药 $completed 次 / 已到期 $decided 次",
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
                        text = "近 7 天还没有到期的服药任务",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

@Composable
private fun MedicationMatrixCard(item: MedMatrixItem) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
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
                            text = "${item.completedCount}/${item.decidedCount} 次",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = "暂无到期",
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
                            color = if (day.dayLabel == "今日") MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (day.dayLabel == "今日") FontWeight.Bold else FontWeight.Normal
                        )
                        Spacer(Modifier.height(6.dp))
                        DayDot(day)
                    }
                }
            }
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
                    contentDescription = "全部完成",
                    tint = Color.White,
                    modifier = Modifier.size(15.dp)
                )
            }
        }

        StatsEngine.DayAdherenceState.PARTIAL -> {
            bg = SuccessGreen.copy(alpha = 0.45f)
            icon = {
                Text(
                    text = "${day.completed}/${day.total}",
                    color = Color.White,
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
                    contentDescription = "逾期漏服",
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
                    contentDescription = "主动跳过",
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
private fun TodayTimelineCard(items: List<TimelineItem>) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "今日服药流水",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))

            if (items.isEmpty()) {
                Text(
                    text = "今天没有排班。若是临时用药，可在今日清单用「手动补录」记录。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }

            items.forEachIndexed { idx, item ->
                val unit = item.medication?.unit ?: "片"
                val doseText = if (item.slot.doseAmount % 1f == 0f) {
                    "${item.slot.doseAmount.toInt()} $unit"
                } else {
                    "${item.slot.doseAmount} $unit"
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = item.medication?.name ?: "已删除的药品",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (Dose(item.slot.doseAmount).asFloat > 0f) {
                            Text(
                                text = doseText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    StatusChip(item.slot.status, item.slot.scheduledTime)
                }
                if (idx < items.size - 1) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: SlotStatus, scheduledTime: String) {
    val (text, color) = when (status) {
        SlotStatus.COMPLETED -> "已服" to SuccessGreen
        SlotStatus.SKIPPED -> "已跳过" to MaterialTheme.colorScheme.outline
        SlotStatus.EXPIRED -> "已漏服" to WarningAmber
        SlotStatus.SNOOZED -> "已推迟" to MaterialTheme.colorScheme.tertiary
        SlotStatus.PENDING -> "待服用" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = scheduledTime,
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
