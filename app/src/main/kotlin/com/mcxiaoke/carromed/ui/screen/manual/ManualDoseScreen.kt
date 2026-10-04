package com.mcxiaoke.carromed.ui.screen.manual

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcxiaoke.carromed.R
import com.mcxiaoke.carromed.core.domain.service.MANUAL_DOSE_BACKFILL_DAYS
import com.mcxiaoke.carromed.ui.component.Quantity
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 手动补录服药
 *
 * 修复的关键缺陷（详见 [ManualDoseViewModel] 注释）：
 * - 服药时刻从"只读、无法修改"变为可点选的日期 + 时间选择器，并拒绝未来时间；
 * - "自动扣减库存"开关真正生效。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ManualDoseScreen(
    viewModel: ManualDoseViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var medDropdownExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.man_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) {
                        Text(stringResource(R.string.man_cancel), style = MaterialTheme.typography.bodyLarge)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        // 保存按钮在页面最底部，错误 banner 在列表第 2 项（提示卡之后）：
        // 剂量这类错误恰好发生在底部区域，banner 在视口外（M2）。错误出现时滚回顶部。
        val listState = rememberLazyListState()
        LaunchedEffect(uiState.error) {
            if (uiState.error != null) listState.animateScrollToItem(1)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            Icons.Default.Lightbulb,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.man_backfill_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            uiState.error?.let { err ->
                item {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Text(
                            text = stringResource(R.string.man_error_with_icon, err),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // 1. 药品
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.man_select_medication),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(14.dp))

                        ExposedDropdownMenuBox(
                            expanded = medDropdownExpanded,
                            onExpandedChange = { medDropdownExpanded = it },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val selected = uiState.selectedMedication
                            val selectedStock = uiState.selectedStock
                            val stocksById = uiState.stockByMedicationId
                            val selectedText = when {
                                selected == null -> stringResource(R.string.man_please_select_medication)
                                !selected.isStockTracked -> selected.name
                                selectedStock < 0f ->
                                    stringResource(
                                        R.string.man_stock_negative,
                                        selected.name,
                                        Quantity.fmt(selectedStock),
                                        selected.unit
                                    )
                                else ->
                                    stringResource(
                                        R.string.man_stock_remaining,
                                        selected.name,
                                        Quantity.fmt(selectedStock),
                                        selected.unit
                                    )
                            }
                            OutlinedTextField(
                                value = selectedText,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.man_medication_label)) },
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = medDropdownExpanded)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            )
                            ExposedDropdownMenu(
                                expanded = medDropdownExpanded,
                                onDismissRequest = { medDropdownExpanded = false }
                            ) {
                                if (uiState.medications.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.man_no_medications)) },
                                        onClick = { medDropdownExpanded = false }
                                    )
                                }
                                uiState.medications.forEach { med ->
                                    val stock = stocksById[med.id] ?: 0f
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (med.isStockTracked) {
                                                    stringResource(
                                                        R.string.man_stock_remaining,
                                                        med.name,
                                                        Quantity.fmt(stock),
                                                        med.unit
                                                    )
                                                } else med.name
                                            )
                                        },
                                        onClick = {
                                            viewModel.selectMedication(med.id)
                                            medDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 2. 服药时刻 (可指定过去时间) —— 此前完全无法修改
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.man_actual_time_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.man_backfill_window, MANUAL_DOSE_BACKFILL_DAYS),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))

                        // 日期下界与领域层 logManualDose 的时间窗守卫同源（自然日口径），
                        // 让用户在选择器里就选不出会被拒的日期，而不是保存时才报错。
                        val minDateMs = java.time.LocalDate.now()
                            .minusDays(MANUAL_DOSE_BACKFILL_DAYS)
                            .atStartOfDay(java.time.ZoneId.systemDefault())
                            .toInstant().toEpochMilli()

                        val dt = uiState.actualDateTime
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                // Locale.ROOT：默认 locale 在 ar-EG 等地区会输出阿拉伯-印度数字，
                                // 日期/时间这类纯数字串必须钉死（Lint DefaultLocale）。
                                value = String.format(Locale.ROOT, "%02d-%02d-%02d", dt.year, dt.monthValue, dt.dayOfMonth),
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.man_date_label)) },
                                trailingIcon = {
                                    // IconButton 自带 48dp 最小触摸目标；此前是 18dp 的裸
                                    // clickable Icon，对手抖用户几乎点不中（orsbf P1-15）
                                    IconButton(onClick = {
                                        DatePickerDialog(
                                            context,
                                            { _, y, m, d -> viewModel.onActualDateChange(y, m, d) },
                                            dt.year, dt.monthValue - 1, dt.dayOfMonth
                                        ).apply { datePicker.minDate = minDateMs }.show()
                                    }) {
                                        Icon(
                                            Icons.Default.CalendarToday,
                                            contentDescription = stringResource(R.string.man_cd_pick_date),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                },
                                modifier = Modifier
                                    .weight(1.2f)
                                    .androidxClickable {
                                        DatePickerDialog(
                                            context,
                                            { _, y, m, d -> viewModel.onActualDateChange(y, m, d) },
                                            dt.year, dt.monthValue - 1, dt.dayOfMonth
                                        ).apply { datePicker.minDate = minDateMs }.show()
                                    },
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = String.format(Locale.ROOT, "%02d:%02d", dt.hour, dt.minute),
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.man_time_label)) },
                                trailingIcon = {
                                    IconButton(onClick = {
                                        TimePickerDialog(
                                            context,
                                            { _, h, m -> viewModel.onActualTimeChange(h, m) },
                                            dt.hour, dt.minute, true
                                        ).show()
                                    }) {
                                        Icon(
                                            Icons.Default.Schedule,
                                            contentDescription = stringResource(R.string.man_cd_pick_time),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .androidxClickable {
                                        TimePickerDialog(
                                            context,
                                            { _, h, m -> viewModel.onActualTimeChange(h, m) },
                                            dt.hour, dt.minute, true
                                        ).show()
                                    },
                                singleLine = true
                            )
                        }

                        Spacer(Modifier.height(12.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            AssistChip(
                                onClick = { viewModel.quickFill(QuickFill.NOW) },
                                label = { Text(stringResource(R.string.man_quick_now), fontSize = 12.sp) }
                            )
                            AssistChip(
                                onClick = { viewModel.quickFill(QuickFill.ONE_HOUR_AGO) },
                                label = { Text(stringResource(R.string.man_quick_one_hour_ago), fontSize = 12.sp) }
                            )
                            AssistChip(
                                onClick = { viewModel.quickFill(QuickFill.YESTERDAY) },
                                label = { Text(stringResource(R.string.man_quick_yesterday), fontSize = 12.sp) }
                            )
                        }
                    }
                }
            }

            // 3. 剂量
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.man_dose_note_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = uiState.doseAmount,
                                onValueChange = { viewModel.onDoseAmountChange(it) },
                                label = { Text(stringResource(R.string.man_dose_label)) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                            )
                            Spacer(Modifier.width(12.dp))
                            OutlinedTextField(
                                value = uiState.selectedMedication?.unit
                                    ?: stringResource(R.string.man_default_unit),
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.man_unit_label)) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = uiState.note,
                            onValueChange = { viewModel.onNoteChange(it) },
                            label = { Text(stringResource(R.string.man_note_label)) },
                            placeholder = { Text(stringResource(R.string.man_note_placeholder)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // 4. 库存开关 —— 此前该开关完全无效
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.man_deduct_stock_title),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    if (uiState.selectedMedication?.isStockTracked == true) {
                                        stringResource(R.string.man_deduct_stock_on)
                                    } else {
                                        stringResource(R.string.man_deduct_stock_off)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = uiState.deductStock &&
                                    uiState.selectedMedication?.isStockTracked == true,
                                onCheckedChange = { viewModel.onDeductStockChange(it) },
                                enabled = uiState.selectedMedication?.isStockTracked == true
                            )
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = { viewModel.save(onNavigateBack) },
                    enabled = !uiState.isSaving && uiState.selectedMedication != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (uiState.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text(stringResource(R.string.man_save), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

private fun Modifier.androidxClickable(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)

// 数量格式化已统一到 com.mcxiaoke.carromed.ui.component.Quantity。
