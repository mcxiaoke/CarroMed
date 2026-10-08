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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.animation.animateContentSize
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcxiaoke.carromed.R
import com.mcxiaoke.carromed.ui.component.MedVocab
import com.mcxiaoke.carromed.ui.component.Quantity
import com.mcxiaoke.carromed.ui.screen.edit.ReadOnlyDateField
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

    // 有效期/预警线是草稿字段，不点顶栏「保存」直接返回会静默丢失（M4）：
    // 有未保存修改时拦截返回键，让用户明确选择。
    val isDirty = remember(uiState) { viewModel.isDirty() }
    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = isDirty && med != null) {
        showDiscardDialog = true
    }
    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(stringResource(R.string.inv_unsaved_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.inv_unsaved_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    onNavigateBack()
                }) { Text(stringResource(R.string.inv_unsaved_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text(stringResource(R.string.inv_unsaved_stay))
                }
            }
        )
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.message) {
        val msg = uiState.message
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
        }
    }
    LaunchedEffect(uiState.error) {
        val err = uiState.error
        if (err != null) {
            snackbarHostState.showSnackbar(err)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.inv_title), fontWeight = FontWeight.Bold)
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.inv_back))
                    }
                },
                actions = {
                    Button(
                        onClick = { viewModel.saveSettings() },
                        enabled = !uiState.isSaving && !uiState.isLoading,
                        modifier = Modifier.padding(end = 8.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) { Text(stringResource(R.string.inv_save), fontWeight = FontWeight.Bold) }
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
                Text(uiState.error ?: stringResource(R.string.inv_med_not_found))
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
            // 1. 余量总览
            item(key = "card_stock_overview") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (uiState.isLowStock && uiState.isTracked) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        }
                    )
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            stringResource(R.string.inv_current_stock),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (uiState.isLowStock && uiState.isTracked) MaterialTheme.colorScheme.onErrorContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${Quantity.fmt(uiState.currentStock)} ${med.unit}",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (uiState.isLowStock && uiState.isTracked) MaterialTheme.colorScheme.error
                            else if (uiState.currentStock <= 0f) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = if (!uiState.isTracked) stringResource(R.string.inv_tracking_disabled_hint)
                            else uiState.frequencyDescription,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(Modifier.height(14.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            MiniStat(
                                Modifier.weight(1f),
                                when {
                                    !uiState.isTracked -> "—"
                                    StatsEngine.isRunwayUnlimited(uiState.runwayDays) -> "—"
                                    uiState.runwayDays < 0 -> stringResource(R.string.inv_runway_overspent)
                                    else -> "${uiState.runwayDays}"
                                },
                                stringResource(R.string.inv_stat_runway_label)
                            )
                            MiniStat(
                                Modifier.weight(1f),
                                if (!uiState.isTracked || uiState.dailyConsumption <= 0f) "—"
                                else Quantity.fmt(uiState.dailyConsumption),
                                stringResource(R.string.inv_stat_daily_consumption_label)
                            )
                            MiniStat(
                                Modifier.weight(1f),
                                Quantity.fmt(uiState.minStockAlert),
                                stringResource(R.string.inv_stat_alert_threshold_label)
                            )
                        }
                        if (uiState.isTracked && uiState.runwayDays in 1..7) {
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.WarningAmber,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    stringResource(R.string.inv_low_runway_warning, uiState.runwayDays),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    fontWeight = FontWeight.SemiBold
                                )
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
                            Text(stringResource(R.string.inv_refill), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // 2. 有效期
            item(key = "card_expiry") {
                SettingsCard(stringResource(R.string.inv_expiry_card_title)) {
                    ReadOnlyDateField(
                        label = stringResource(R.string.inv_expiry_date_label),
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
                            d == -1 -> NoticeBar(stringResource(R.string.inv_expired_yesterday), NoticeTone.ERROR)
                            d < 0 -> NoticeBar(stringResource(R.string.inv_expired_days_ago, -d), NoticeTone.ERROR)
                            d == 0 -> NoticeBar(stringResource(R.string.inv_expires_today), NoticeTone.WARN)
                            d <= 30 -> NoticeBar(stringResource(R.string.inv_expires_in_days, d), NoticeTone.WARN)
                            else -> Text(
                                stringResource(R.string.inv_days_to_expiry, d),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 3. 预警线与追踪开关
            item(key = "card_stock_settings") {
                SettingsCard(stringResource(R.string.inv_low_stock_card_title)) {
                    OutlinedTextField(
                        value = uiState.minStockAlertInput,
                        onValueChange = { viewModel.onMinStockAlertChange(it.filter { c -> c.isDigit() || c == '.' }) },
                        label = { Text(stringResource(R.string.inv_alert_threshold_label, med.unit)) },
                        placeholder = { Text(stringResource(R.string.inv_alert_threshold_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.inv_alert_threshold_hint),
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
                            Text(stringResource(R.string.inv_tracking_toggle_title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (uiState.isTracked) stringResource(R.string.inv_tracking_on_desc)
                                else stringResource(R.string.inv_tracking_off_desc),
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
            item(key = "card_calibrate") {
                SettingsCard(stringResource(R.string.inv_calibrate_card_title)) {
                    Text(
                        stringResource(R.string.inv_calibrate_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = uiState.calibrateInput,
                            onValueChange = { viewModel.onCalibrateInputChange(it) },
                            label = { Text(stringResource(R.string.inv_actual_remaining_label, med.unit)) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                        )
                        Spacer(Modifier.width(10.dp))
                        Button(
                            onClick = { viewModel.calibrate(null) },
                            enabled = !uiState.isSaving,
                            modifier = Modifier.height(52.dp)
                        ) { Text(stringResource(R.string.inv_calibrate_button), fontWeight = FontWeight.Bold) }
                    }
                }
            }

            // 5. 出入库流水
            item(key = "card_ledger") {
                SettingsCard(stringResource(R.string.inv_ledger_card_title)) {
                    if (uiState.transactions.isEmpty()) {
                        Text(
                            stringResource(R.string.inv_ledger_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            stringResource(R.string.inv_ledger_balance_rule),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = { viewModel.exportLedger() },
                            enabled = !uiState.isSaving,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(
                                Icons.Default.FileDownload,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.inv_export_ledger_csv), fontSize = 13.sp)
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
                    label = stringResource(viewModel.txLabel(tx.txType)),
                    note = MedVocab.ledgerNoteDisplay(LocalContext.current, tx.noteKey, tx.note),
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
        NoticeTone.WARN -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
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
                    .background(if (change >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "$time · $label",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (!note.isNullOrBlank() || !batch.isNullOrBlank() || !expiry.isNullOrBlank()) {
                    val batchLine = if (batch.isNullOrBlank()) "" else stringResource(R.string.inv_tx_batch, batch)
                    val expiryLine = if (expiry.isNullOrBlank()) "" else stringResource(R.string.inv_tx_expiry, expiry)
                    Text(
                        buildString {
                            if (!note.isNullOrBlank()) append(note)
                            if (batchLine.isNotEmpty()) {
                                if (isNotEmpty()) append(" · ")
                                append(batchLine)
                            }
                            if (expiryLine.isNotEmpty()) {
                                if (isNotEmpty()) append(" · ")
                                append(expiryLine)
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = (if (change > 0) "+${Quantity.fmt(change)}" else Quantity.fmt(change)) + " $unit",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (change > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    stringResource(R.string.inv_tx_balance, Quantity.fmt(balance)),
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
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

// ⚠️ 已删除（orsbf P2-6）：本页私有的 fmt() 是 Quantity.fmt 的逐字副本 ——
// 一份规则两处实现，改 Quantity 时这里会静默漂移。数值格式化统一走
// com.mcxiaoke.carromed.ui.component.Quantity。
