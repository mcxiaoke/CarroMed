package com.mcxiaoke.carromed.ui.screen.progress

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mcxiaoke.carromed.core.data.model.RecordStatus
import com.mcxiaoke.carromed.ui.component.Quantity
import com.mcxiaoke.carromed.ui.theme.OnSuccessGreenContainer
import com.mcxiaoke.carromed.ui.theme.SuccessGreenContainer

/**
 * 单个药品的服药历史（UX 方案 §4.2）。
 *
 * 按月分组，组内倒序；每行点进统一的「记录详情页」（`DoseRecordDetailScreen`）。
 * 每次进入都重新读库（`load` 有 `loadedMedId` 的一次性守卫，
 * 但返回时页面是新的组合，所以会重新载入），
 * 这样刚在详情页改完的剂量回到这里立刻可见。
 */
@Composable
fun MedHistoryScreen(
    medId: Long,
    onNavigateBack: () -> Unit,
    onOpenDose: (Long?, Long) -> Unit,
    viewModel: MedHistoryViewModel = viewModel()
) {
    LaunchedEffect(medId) { viewModel.load(medId) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(56.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Spacer(Modifier.width(4.dp))
                Column {
                    Text(
                        text = uiState.medication?.name ?: "服药历史",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    // 副标题说明"这是一个什么页面"。只显示药名的话，
                    // 用户从进展页点进来会短暂困惑"这列是什么"。
                    Text(
                        text = "服药历史",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    ) { innerPadding ->
        when {
            uiState.isLoading -> Box(
                Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            uiState.error != null -> Box(
                Modifier.fillMaxSize().padding(innerPadding).padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = uiState.error!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            uiState.months.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(innerPadding).padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "还没有服药记录。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val unit = uiState.medication?.unit ?: "片"
                uiState.months.forEach { month ->
                    item(key = "m-${month.yearMonth}") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            Text(
                                text = month.headerLabel,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            if (month.completedDoseMilli > 0) {
                                Text(
                                    text = month.totalText(unit),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    items(
                        count = month.items.size,
                        key = { "r-${month.items[it].recordId}" }
                    ) { idx -> MedHistoryRow(month.items[idx], unit, onOpenDose) }
                }
            }
        }
    }
}

@Composable
private fun MedHistoryRow(
    item: MedHistoryItem,
    unit: String,
    onOpenDose: (Long?, Long) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenDose(item.slotId, item.recordId) },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = java.time.Instant.ofEpochMilli(item.actualTs)
                        .atZone(java.time.ZoneId.systemDefault())
                        .toLocalDate()
                        .toString()
                        .substring(5)          // "09-29"，月日够用，年份在月分节头上
                        .replace('-', '/'),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val dose = com.mcxiaoke.carromed.core.domain.model.Dose(item.doseMilli).asFloat
                val subtitle = buildString {
                    append(Quantity.fmt(dose)).append(' ').append(unit)
                    if (item.isManual) append(" · 临时用药")
                    if (item.isRetrospective) append(" · 补录")
                    if (!item.note.isNullOrBlank()) {
                        append(" · ").append(item.note)
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            HistoryStatusChip(item.timeLabel, item.status)
        }
    }
}

/**
 * 事实状态 chip。与流水页同口径。
 *
 * ⚠️ `REVERTED` 分支正常不会渲染（查询已在 SQL 层排除已撤销的事实）；
 * 保留它是为了在"某处漏过滤"时如实显示，而不是被 `else` 伪装成「已服」。
 */
@Composable
private fun HistoryStatusChip(timeLabel: String, status: RecordStatus) {
    val (text, color) = when (status) {
        RecordStatus.COMPLETED -> "已服" to MaterialTheme.colorScheme.primary
        RecordStatus.SKIPPED -> "已跳过" to MaterialTheme.colorScheme.outline
        RecordStatus.REVERTED -> "已撤销" to MaterialTheme.colorScheme.outline
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = timeLabel,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(8.dp))
        Surface(shape = RoundedCornerShape(6.dp), color = color.copy(alpha = 0.14f)) {
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
