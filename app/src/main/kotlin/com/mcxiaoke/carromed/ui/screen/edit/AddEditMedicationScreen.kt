package com.mcxiaoke.carromed.ui.screen.edit

import android.app.TimePickerDialog
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcxiaoke.carromed.core.data.model.PolicyType
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditMedicationScreen(
    viewModel: AddEditMedicationViewModel,
    onNavigateBack: () -> Unit,
    onSavedSuccess: (Long) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (uiState.medId != null && uiState.medId!! > 0) "修改用药与计划" else "设置用药与提醒", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) {
                        Text("取消", style = MaterialTheme.typography.bodyLarge)
                    }
                },
                actions = {
                    Button(
                        onClick = { viewModel.save(onSavedSuccess) },
                        modifier = Modifier.padding(end = 8.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("保存", fontWeight = FontWeight.Bold)
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
            contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 0. 低门槛录入提示条
            item {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                ) {
                    Text(
                        text = "✨ 低门槛录入: 只需填写【药品名称】和选择【提醒频次/时间】，其余均为可选扩展项。",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        lineHeight = 18.sp
                    )
                }
            }

            // 1. 基本信息 (必填)
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "1. 基本信息 (必填)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = uiState.name,
                            onValueChange = { viewModel.onNameChange(it) },
                            label = { Text("药品名称 *") },
                            placeholder = { Text("例如: 阿司匹林、阿莫西林、维生素C") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            isError = uiState.error != null
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(modifier = Modifier.fillMaxWidth()) {
                            // 类别选择
                            val categories = listOf("常备药", "慢病处方", "处方药 · 免疫", "营养保健", "激素类")
                            var catExpanded by remember { mutableStateOf(false) }

                            ExposedDropdownMenuBox(
                                expanded = catExpanded,
                                onExpandedChange = { catExpanded = it },
                                modifier = Modifier.weight(1f)
                            ) {
                                OutlinedTextField(
                                    value = uiState.category,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("类别") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = catExpanded) },
                                    modifier = Modifier.menuAnchor()
                                )
                                ExposedDropdownMenu(
                                    expanded = catExpanded,
                                    onDismissRequest = { catExpanded = false }
                                ) {
                                    categories.forEach { cat ->
                                        DropdownMenuItem(
                                            text = { Text(cat) },
                                            onClick = {
                                                viewModel.onCategoryChange(cat)
                                                catExpanded = false
                                            }
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            // 剂型选择
                            val forms = listOf("片剂", "胶囊", "软胶囊", "口服液", "外用")
                            var formExpanded by remember { mutableStateOf(false) }

                            ExposedDropdownMenuBox(
                                expanded = formExpanded,
                                onExpandedChange = { formExpanded = it },
                                modifier = Modifier.weight(1f)
                            ) {
                                OutlinedTextField(
                                    value = uiState.form,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("剂型") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = formExpanded) },
                                    modifier = Modifier.menuAnchor()
                                )
                                ExposedDropdownMenu(
                                    expanded = formExpanded,
                                    onDismissRequest = { formExpanded = false }
                                ) {
                                    forms.forEach { f ->
                                        DropdownMenuItem(
                                            text = { Text(f) },
                                            onClick = {
                                                viewModel.onFormChange(f)
                                                formExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 2. 提醒频次与时间方案 (必填)
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "2. 提醒频次与时间方案 *",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "频次类型",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        val policyTypes = listOf(
                            Pair(PolicyType.DAILY, "每天固定"),
                            Pair(PolicyType.INTERVAL, "隔天/隔N天"),
                            Pair(PolicyType.DAYS_OF_WEEK, "每周特定天")
                        )

                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            policyTypes.forEachIndexed { index, (type, label) ->
                                SegmentedButton(
                                    shape = SegmentedButtonDefaults.itemShape(index = index, count = policyTypes.size),
                                    onClick = { viewModel.onPolicyTypeChange(type) },
                                    selected = uiState.policyType == type,
                                    icon = {}
                                ) {
                                    Text(label, fontSize = 12.sp)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "设定每日提醒时点",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        uiState.timeSlots.forEachIndexed { index, slot ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 时间选择按钮 (点击拉起系统 TimePickerDialog)
                                OutlinedButton(
                                    onClick = {
                                        val parts = slot.time.split(":")
                                        val initHour = parts.getOrNull(0)?.toIntOrNull() ?: 8
                                        val initMinute = parts.getOrNull(1)?.toIntOrNull() ?: 30
                                        TimePickerDialog(
                                            context,
                                            { _, hourOfDay, minute ->
                                                val formatted = String.format(Locale.getDefault(), "%02d:%02d", hourOfDay, minute)
                                                viewModel.updateTimeSlot(index, formatted, slot.dose)
                                            },
                                            initHour,
                                            initMinute,
                                            true
                                        ).show()
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Schedule,
                                        contentDescription = "选择时间",
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(slot.time, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                // 剂量输入框
                                OutlinedTextField(
                                    value = if (slot.dose % 1f == 0f) slot.dose.toInt().toString() else slot.dose.toString(),
                                    onValueChange = {
                                        val parsed = it.toFloatOrNull() ?: 1.0f
                                        viewModel.updateTimeSlot(index, slot.time, parsed)
                                    },
                                    modifier = Modifier.width(80.dp),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    trailingIcon = { Text("片", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                )

                                if (uiState.timeSlots.size > 1) {
                                    IconButton(onClick = { viewModel.removeTimeSlot(index) }) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteOutline,
                                            contentDescription = "删除该时段",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedButton(
                            onClick = { viewModel.addTimeSlot() },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "添加", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("添加一个提醒时点", fontSize = 13.sp)
                        }
                    }
                }
            }

            // 3. 库存追踪 (可选)
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "3. 库存追踪 (可选)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = uiState.currentStock,
                                onValueChange = { viewModel.onCurrentStockChange(it) },
                                label = { Text("当前现有库存") },
                                placeholder = { Text("不填则不追踪") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            OutlinedTextField(
                                value = uiState.minStockAlert,
                                onValueChange = { viewModel.onMinStockAlertChange(it) },
                                label = { Text("低库存预警阈值") },
                                placeholder = { Text("如 10 片") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                        }
                    }
                }
            }

            // 4. 说明与医嘱注意事项 (可选)
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "4. 说明与医嘱注意事项 (可选)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = uiState.description,
                            onValueChange = { viewModel.onDescriptionChange(it) },
                            label = { Text("服药注意事项 / 医嘱描述") },
                            placeholder = { Text("如: 饭后温水吞服、整粒吞服禁嚼碎、禁葡萄柚等") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3
                        )
                    }
                }
            }
        }
    }
}
