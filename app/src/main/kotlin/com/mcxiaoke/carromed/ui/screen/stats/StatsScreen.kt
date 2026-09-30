package com.mcxiaoke.carromed.ui.screen.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcxiaoke.carromed.R
import com.mcxiaoke.carromed.ui.component.CarroMedTopAppBar
import com.mcxiaoke.carromed.ui.component.Quantity
import com.mcxiaoke.carromed.ui.theme.OnWarningAmberContainer
import com.mcxiaoke.carromed.ui.theme.SuccessGreen
import com.mcxiaoke.carromed.ui.theme.WarningAmber
import com.mcxiaoke.carromed.ui.theme.WarningAmberContainer
import java.util.Locale

@Composable
fun StatsScreen(
    viewModel: StatsViewModel,
    onNavigateToSettings: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            CarroMedTopAppBar(
                title = stringResource(R.string.stats_title),
                actionIcon = Icons.Outlined.Settings,
                actionContentDescription = stringResource(R.string.stats_cd_settings),
                onActionClick = onNavigateToSettings
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 周期切换
            item {
                val periods = StatsPeriod.entries
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    periods.forEachIndexed { index, p ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = periods.size),
                            onClick = { viewModel.selectPeriod(index) },
                            selected = uiState.selectedPeriod == index,
                            icon = {}
                        ) {
                            Text(
                                stringResource(p.labelRes),
                                fontSize = 13.sp,
                                fontWeight = if (uiState.selectedPeriod == index) FontWeight.Bold else FontWeight.Normal
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

            // Hero 统计
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            text = stringResource(
                                R.string.stats_period_total,
                                stringResource(StatsPeriod.entries[uiState.selectedPeriod].labelRes)
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                        )
                        Spacer(Modifier.height(8.dp))
                        // 跨单位不能求和（30 片 + 5 ml ≠ 35 片）。
                        // 只有全部药品同单位时才给一个 36sp 总量大数字；多单位时逐单位列出。
                        if (uiState.mixedUnits) {
                            Text(
                                text = stringResource(R.string.stats_mixed_units),
                                style = MaterialTheme.typography.headlineLarge.copy(fontSize = 36.sp),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.height(4.dp))
                            @OptIn(ExperimentalLayoutApi::class)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                uiState.totalDosesByUnit.entries
                                    .sortedByDescending { it.value }
                                    .forEach { (unit, amount) ->
                                        Text(
                                            text = "${Quantity.fmt(amount)}${Quantity.unitSuffix(unit)}",
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                    }
                            }
                        } else {
                            Text(
                                text = "${Quantity.fmt(uiState.totalDoses)}${Quantity.unitSuffix(uiState.totalDoseUnit)}",
                                style = MaterialTheme.typography.headlineLarge.copy(fontSize = 36.sp),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                        Text(
                            text = stringResource(R.string.stats_plan_summary, uiState.scheduledDoseCount, uiState.activeMedCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                        )
                        Spacer(Modifier.height(16.dp))
                        val decided = uiState.breakdown.completed +
                            uiState.breakdown.skipped + uiState.breakdown.missed
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            StatTile(
                                modifier = Modifier.weight(1f),
                                // 无到期样本时显示 "—" 而不是 100%：空集的 100% 会误导用户
                                value = if (decided > 0) {
                                    String.format(Locale.getDefault(), "%.1f%%", uiState.adherenceRate * 100)
                                } else {
                                    "—"
                                },
                                label = stringResource(R.string.stats_adherence_label)
                            )
                            StatTile(
                                modifier = Modifier.weight(1f),
                                value = "${uiState.breakdown.completed}",
                                label = stringResource(R.string.stats_completed_label)
                            )
                            StatTile(
                                modifier = Modifier.weight(1f),
                                value = "${uiState.breakdown.missed + uiState.breakdown.skipped}",
                                label = stringResource(R.string.stats_missed_label)
                            )
                        }
                    }
                }
            }

            // 依从率拆解条
            item {
                AdherenceBreakdownCard(uiState)
            }

            // 消耗排行榜
            item {
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
                            Text(
                                text = stringResource(R.string.stats_consumption_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.stats_by_actual),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(12.dp))

                        if (uiState.rankings.isEmpty()) {
                            Text(
                                text = stringResource(R.string.stats_empty_rankings),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 20.sp
                            )
                        } else {
                            val max = uiState.rankings.maxOf { it.totalDose }.coerceAtLeast(0.0001f)
                            uiState.rankings.forEachIndexed { index, r ->
                                Column(Modifier.padding(vertical = 6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${r.rank}. ${r.medicationName}",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = Quantity.withUnit(r.totalDose, r.unit),
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    LinearProgressIndicator(
                                        progress = { (r.totalDose / max).coerceIn(0f, 1f) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(3.dp)),
                                        color = MaterialTheme.colorScheme.primary,
                                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                }
                                if (index < uiState.rankings.size - 1) {
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = { viewModel.exportReport() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.stats_export_csv), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun StatTile(modifier: Modifier, value: String, label: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.15f),
        modifier = modifier
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
            )
        }
    }
}

/** 依从率拆解：已服 / 跳过 / 漏服 三段占比，让用户看懂"没到 100% 是漏在哪" */
@Composable
private fun AdherenceBreakdownCard(uiState: StatsUiState) {
    val b = uiState.breakdown
    val decided = (b.completed + b.skipped + b.missed).coerceAtLeast(1)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.stats_breakdown_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(10.dp))

            Row(
                Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
            ) {
                if (b.completed > 0) {
                    Box(
                        Modifier
                            .weight(b.completed.toFloat() / decided)
                            .fillMaxSize()
                            .background(SuccessGreen)
                    )
                }
                if (b.skipped > 0) {
                    Box(
                        Modifier
                            .weight(b.skipped.toFloat() / decided)
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.outline)
                    )
                }
                if (b.missed > 0) {
                    Box(
                        Modifier
                            .weight(b.missed.toFloat() / decided)
                            .fillMaxSize()
                            .background(WarningAmber)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LegendDot(SuccessGreen, stringResource(R.string.stats_legend_taken), b.completed, Modifier.weight(1f))
                LegendDot(MaterialTheme.colorScheme.outline, stringResource(R.string.stats_legend_skipped), b.skipped, Modifier.weight(1f))
                LegendDot(WarningAmber, stringResource(R.string.stats_legend_missed), b.missed, Modifier.weight(1f))
            }

            if (b.pending > 0) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.stats_pending_note, b.pending),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun LegendDot(color: androidx.compose.ui.graphics.Color, label: String, count: Int, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Column {
            Text(
                text = "$count",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// 数量格式化与单位后缀已统一到 com.mcxiaoke.carromed.ui.component.Quantity。
// 原先本文件用 "%.1f"、其他页面用 "%.2f"，同一数值四处四种写法。
