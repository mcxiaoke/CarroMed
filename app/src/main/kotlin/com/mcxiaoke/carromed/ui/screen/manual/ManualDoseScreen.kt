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
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.format.DateTimeFormatter

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
                title = { Text("手动补录服药", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) {
                        Text("取消", style = MaterialTheme.typography.bodyLarge)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
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
                            text = "忘记打卡或未带手机？支持补记过去任意时刻，系统会保留真实服药事实，" +
                                "并按需联动扣减库存。",
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
                            text = "⚠️ $err",
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
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "选择药品",
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
                            val selectedText = when {
                                selected == null -> "请选择药品"
                                selected.isStockTracked ->
                                    "${selected.name} (剩 ${fmtQty(selected.currentStock)} ${selected.unit})"
                                else -> selected.name
                            }
                            OutlinedTextField(
                                value = selectedText,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("药品 *") },
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = medDropdownExpanded)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor()
                            )
                            ExposedDropdownMenu(
                                expanded = medDropdownExpanded,
                                onDismissRequest = { medDropdownExpanded = false }
                            ) {
                                if (uiState.medications.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("暂无在服药品，请先在药箱添加") },
                                        onClick = { medDropdownExpanded = false }
                                    )
                                }
                                uiState.medications.forEach { med ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (med.isStockTracked) {
                                                    "${med.name} (剩 ${fmtQty(med.currentStock)} ${med.unit})"
                                                } else med.name
                                            )
                                        },
                                        onClick = {
                                            viewModel.selectMedication(med)
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
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "实际服药时间",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "补录最常用于「忘记打卡」场景，因此必须能指定过去时刻。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))

                        val dt = uiState.actualDateTime
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = String.format("%02d-%02d-%02d", dt.year, dt.monthValue, dt.dayOfMonth),
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("日期") },
                                trailingIcon = {
                                    Icon(
                                        Icons.Default.CalendarToday,
                                        contentDescription = "选择日期",
                                        modifier = Modifier
                                            .size(18.dp)
                                            .androidxClickable {
                                                val c = java.util.Calendar.getInstance()
                                                DatePickerDialog(
                                                    context,
                                                    { _, y, m, d -> viewModel.onActualDateChange(y, m, d) },
                                                    dt.year, dt.monthValue - 1, dt.dayOfMonth
                                                ).show()
                                            }
                                    )
                                },
                                modifier = Modifier
                                    .weight(1.2f)
                                    .androidxClickable {
                                        val c = java.util.Calendar.getInstance()
                                        DatePickerDialog(
                                            context,
                                            { _, y, m, d -> viewModel.onActualDateChange(y, m, d) },
                                            dt.year, dt.monthValue - 1, dt.dayOfMonth
                                        ).show()
                                    },
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = String.format("%02d:%02d", dt.hour, dt.minute),
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("时间") },
                                trailingIcon = {
                                    Icon(
                                        Icons.Default.Schedule,
                                        contentDescription = "选择时间",
                                        modifier = Modifier
                                            .size(18.dp)
                                            .androidxClickable {
                                                TimePickerDialog(
                                                    context,
                                                    { _, h, m -> viewModel.onActualTimeChange(h, m) },
                                                    dt.hour, dt.minute, true
                                                ).show()
                                            }
                                    )
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
                                label = { Text("此刻", fontSize = 12.sp) }
                            )
                            AssistChip(
                                onClick = { viewModel.quickFill(QuickFill.ONE_HOUR_AGO) },
                                label = { Text("1 小时前", fontSize = 12.sp) }
                            )
                            AssistChip(
                                onClick = { viewModel.quickFill(QuickFill.YESTERDAY) },
                                label = { Text("昨天此时", fontSize = 12.sp) }
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
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "剂量与备注",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = uiState.doseAmount,
                                onValueChange = { viewModel.onDoseAmountChange(it) },
                                label = { Text("服用剂量") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            Spacer(Modifier.width(12.dp))
                            OutlinedTextField(
                                value = uiState.selectedMedication?.unit ?: "片",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("单位") },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = uiState.note,
                            onValueChange = { viewModel.onNoteChange(it) },
                            label = { Text("备注 (选填)") },
                            placeholder = { Text("如: 随早餐服下、外出聚餐补服") },
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
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "联动扣减库存",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    if (uiState.selectedMedication?.isStockTracked == true) {
                                        "服药后从该药品库存中扣除本次剂量"
                                    } else {
                                        "该药品未开启库存追踪，此开关无效"
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
                        Text("保存服药记录", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

private fun Modifier.androidxClickable(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)

private fun fmtQty(v: Float): String =
    if (v % 1f == 0f) v.toInt().toString() else String.format(java.util.Locale.getDefault(), "%.2f", v)
