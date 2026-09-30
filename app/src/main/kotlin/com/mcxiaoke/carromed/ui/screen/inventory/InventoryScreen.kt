package com.mcxiaoke.carromed.ui.screen.inventory
import com.mcxiaoke.carromed.core.domain.engine.StatsEngine
import com.mcxiaoke.carromed.core.domain.model.Dose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcxiaoke.carromed.ui.screen.edit.ReadOnlyDateField
import com.mcxiaoke.carromed.ui.component.Quantity
import com.mcxiaoke.carromed.ui.theme.OnWarningAmberContainer
import com.mcxiaoke.carromed.ui.theme.SuccessGreen
import com.mcxiaoke.carromed.ui.theme.WarningAmber
import com.mcxiaoke.carromed.ui.theme.WarningAmberContainer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 库存管理页 —— 独立于药品信息与提醒设置 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun InventoryScreen(
    viewModel: InventoryViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToRefill: (Long) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val med = uiState.medication

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("库存管理", fontWeight = FontWeight.Bold)
                        med?.let {
                            Text(
                                it.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    Button(
                        onClick = { viewModel.saveSettings() },
                        enabled = !uiState.isSaving && !uiState.isLoading,
                        modifier = Modifier.padding(end = 8.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) { Text("保存", fontWeight = FontWeight.Bold) }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        if (uiState.isLoading) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        if (med == null) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Text(uiState.error ?: "药品不存在")
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            uiState.message?.let {
                item { NoticeBar(text = it, tone = NoticeTone.INFO) }
            }
            uiState.error?.let {
                item { NoticeBar(text = it, tone = NoticeTone.ERROR) }
            }

            // 1. 余量总览
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (uiState.isLowStock && uiState.isTracked) {
                            WarningAmberContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
                    )
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            "当前余量",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (uiState.isLowStock && uiState.isTracked) OnWarningAmberContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${fmt(uiState.currentStock)} ${med.unit}",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (uiState.isLowStock && uiState.isTracked) WarningAmber
                            else if (uiState.currentStock <= 0f) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = if (!uiState.isTracked) "未开启库存追踪：服药不会自动扣减库存"
                            else uiState.frequencyDescription,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (uiState.isTracked && uiState.dailyConsumption > 0f) {
                            Spacer(Modifier.height(14.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                MiniStat(
                                    Modifier.weight(1f),
                                    // ⚠️ 走 [StatsEngine.isRunwayUnlimited] 而不是 `>= 9999`
                                    // 的魔数比较（M4-1）。领域层已把"不适用"改成显式哨兵，
                                    // UI 再去猜一个下界，两处定义必然会漂移。
                                    if (StatsEngine.isRunwayUnlimited(uiState.runwayDays)) "—"
                                    else "${uiState.runwayDays}",
                                    "预计可用天数"
                                )
                                MiniStat(
                                    Modifier.weight(1f),
                                    // 统一走 Quantity 格式化（此前本页内联 "%.2f"，
                                    // 与全库其他页面的规则不一致，见 Quantity 的 KDoc）
                                    Quantity.fmt(uiState.dailyConsumption),
                                    "日均消耗"
                                )
                                MiniStat(
                                    Modifier.weight(1f),
                                    fmt(uiState.minStockAlertInput.toFloatOrNull() ?: uiState.minStockAlert),
                                    "预警线"
                                )
                            }
                            if (uiState.runwayDays in 1..7) {
                                Spacer(Modifier.height(12.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.WarningAmber,
                                        contentDescription = null,
                                        tint = WarningAmber,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "按当前用量仅够约 ${uiState.runwayDays} 天，建议尽快补货",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = WarningAmber,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = { onNavigateToRefill(med.id) },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("补药入库", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // 2. 有效期
            item {
                SettingsCard("有效期与临期提醒") {
                    ReadOnlyDateField(
                        label = "药品有效期至",
                        dateStr = uiState.expiryDate,
                        onDateChange = { viewModel.onExpiryDateChange(it) },
                        onClear = { viewModel.onExpiryDateChange("") }
                    )
                    val d = uiState.daysToExpiry
                    if (d != null) {
                        Spacer(Modifier.height(10.dp))
                        when {
                            // ⚠️ `d == -1` 说"昨天"，不说"1 天前"（M7-9）。
                            // "已于 1 天前过期"是机器腔的中文：用户脑子里的时间是
                            // 「昨天买的 / 昨天就该扔了」，"1 天前"要求他先做一次减法。
                            // 1 天是绝大多数情况，2 天以上才退回计数。
                            d == -1 -> NoticeBar("昨天已过期，请勿继续服用", NoticeTone.ERROR)
                            d < 0 -> NoticeBar("已过期 ${-d} 天，请勿继续服用", NoticeTone.ERROR)
                            d == 0 -> NoticeBar("今天到期，请尽快用完或更换", NoticeTone.WARN)
                            d <= 30 -> NoticeBar("还有 $d 天到期，注意用完并及时更换", NoticeTone.WARN)
                            else -> Text(
                                "距离到期还有 $d 天",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 3. 预警线与追踪开关
            item {
                SettingsCard("低库存预警") {
                    OutlinedTextField(
                        value = uiState.minStockAlertInput,
                        onValueChange = { viewModel.onMinStockAlertChange(it.filter { c -> c.isDigit() || c == '.' }) },
                        label = { Text("预警阈值 (${med.unit})") },
                        placeholder = { Text("如 10") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "余量低于该值时，今日清单顶部会出现告警横幅。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Spacer(Modifier.height(14.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("库存追踪", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (uiState.isTracked) "服药打卡时自动扣减库存"
                                else "关闭：每次服药需自行核对剩余量",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = uiState.isTracked,
                            onCheckedChange = { viewModel.setTracking(it) },
                            enabled = !uiState.isSaving
                        )
                    }
                }
            }

            // 4. 盘点校准
            item {
                SettingsCard("库存盘点校准") {
                    Text(
                        "换了包装、之前漏记或首次建档时，把账面修正为实物真实数量。" +
                            "系统会写入一条盘点流水，保证账实可追溯。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = uiState.calibrateInput,
                            onValueChange = { viewModel.onCalibrateInputChange(it) },
                            label = { Text("实际剩余 (${med.unit})") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                        )
                        Spacer(Modifier.width(10.dp))
                        Button(
                            onClick = { viewModel.calibrate(null) },
                            enabled = !uiState.isSaving,
                            modifier = Modifier.height(52.dp)
                        ) { Text("校准", fontWeight = FontWeight.Bold) }
                    }
                }
            }

            // 5. 出入库流水
            item {
                SettingsCard("出入库流水 (双向可追溯)") {
                    if (uiState.transactions.isEmpty()) {
                        Text(
                            "暂无流水。首次补药入库或首次盘点后开始记录。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            "账面 = 全部流水变动之和，恒等于当前余量",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = { viewModel.exportLedger() },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(
                                Icons.Default.FileDownload,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("导出库存流水 CSV", fontSize = 13.sp)
                        }
                    }
                }
            }

            items(
                count = uiState.transactions.size,
                key = { "tx_${uiState.transactions[it].id}" }
            ) { idx ->
                val tx = uiState.transactions[idx]
                TxRow(
                    time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(tx.createdAt)),
                    label = viewModel.txLabel(tx.txType),
                    note = tx.note,
                    change = Dose(tx.changeAmount).asFloat,
                    balance = Dose(tx.balanceAfter).asFloat,
                    unit = med.unit,
                    batch = tx.batchNumber,
                    expiry = tx.expiryDate
                )
            }
        }
    }
}

private enum class NoticeTone { INFO, WARN, ERROR }

@Composable
private fun NoticeBar(text: String, tone: NoticeTone) {
    val (bg, fg) = when (tone) {
        NoticeTone.INFO -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        NoticeTone.WARN -> WarningAmberContainer to OnWarningAmberContainer
        NoticeTone.ERROR -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(shape = RoundedCornerShape(10.dp), color = bg) {
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = fg
        )
    }
}

@Composable
private fun TxRow(
    time: String,
    label: String,
    note: String?,
    change: Float,
    balance: Float,
    unit: String,
    batch: String?,
    expiry: String?
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (change >= 0) SuccessGreen else MaterialTheme.colorScheme.outline)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "$time · $label",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (!note.isNullOrBlank() || !batch.isNullOrBlank() || !expiry.isNullOrBlank()) {
                    Text(
                        buildString {
                            if (!note.isNullOrBlank()) append(note)
                            if (!batch.isNullOrBlank()) {
                                if (isNotEmpty()) append(" · ")
                                append("批号 $batch")
                            }
                            if (!expiry.isNullOrBlank()) {
                                if (isNotEmpty()) append(" · ")
                                append("效期 $expiry")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = (if (change > 0) "+${fmt(change)}" else fmt(change)) + " $unit",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (change > 0) SuccessGreen else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "结余 ${fmt(balance)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MiniStat(modifier: Modifier, value: String, label: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = modifier
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

private fun fmt(v: Float): String =
    if (v % 1f == 0f) v.toInt().toString() else String.format(Locale.getDefault(), "%.2f", v)
