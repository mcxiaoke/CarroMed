package com.mcxiaoke.carromed.ui.screen.edit

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcxiaoke.carromed.core.data.model.PolicyType
import com.mcxiaoke.carromed.ui.theme.OnWarningAmberContainer
import com.mcxiaoke.carromed.ui.theme.WarningAmberContainer
import java.util.Calendar
import java.util.Locale

/**
 * 新增药品 / 编辑药品信息 —— **同一个界面，两种模式**
 *
 * - [AddEditMode.FULL]      新增：药品信息 + 提醒计划 + 初始库存 一次填完 (低门槛)
 * - [AddEditMode.INFO_ONLY] 编辑：只显示药品信息维度；提醒计划与库存分别由
 *   "提醒设置"页与"库存管理"页负责，避免"改个药名要滚过整个计划表单"
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddEditMedicationScreen(
    viewModel: AddEditMedicationViewModel,
    onNavigateBack: () -> Unit,
    onSavedSuccess: (Long) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val isInfoOnly = uiState.mode == AddEditMode.INFO_ONLY

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.title, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) {
                        Text("取消", style = MaterialTheme.typography.bodyLarge)
                    }
                },
                actions = {
                    Button(
                        onClick = { viewModel.save(onSavedSuccess) },
                        enabled = !uiState.isSaving,
                        modifier = Modifier.padding(end = 8.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        if (uiState.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text("保存", fontWeight = FontWeight.Bold)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
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
            if (uiState.error != null) {
                item {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Text(
                            text = "⚠️ ${uiState.error}",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            if (!isInfoOnly) {
                item { LowFrictionTipCard() }
            }

            // ============ 1. 药品信息 ============
            item {
                SectionCard(
                    index = 1,
                    title = if (uiState.isEdit) "药品信息" else "基本信息",
                    required = true
                ) {
                    OutlinedTextField(
                        value = uiState.name,
                        onValueChange = { viewModel.onNameChange(it) },
                        label = { Text("药品名称 *") },
                        placeholder = { Text("例如: 阿司匹林肠溶片") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = uiState.alias,
                        onValueChange = { viewModel.onAliasChange(it) },
                        label = { Text("别名 / 通用名 (选填)") },
                        placeholder = { Text("例如: 赛妥、西药名") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(Modifier.height(12.dp))

                    Row(Modifier.fillMaxWidth()) {
                        OptionDropdown(
                            label = "类别",
                            value = uiState.category,
                            options = MedicationFormOptions.CATEGORIES,
                            onSelect = { viewModel.onCategoryChange(it) },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(10.dp))
                        OptionDropdown(
                            label = "剂型",
                            value = uiState.form,
                            options = MedicationFormOptions.FORMS,
                            onSelect = { viewModel.onFormChange(it) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OptionDropdown(
                            label = "单位",
                            value = uiState.unit,
                            options = MedicationFormOptions.UNITS,
                            onSelect = { viewModel.onUnitChange(it) },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(10.dp))
                        OutlinedTextField(
                            value = uiState.defaultDose,
                            onValueChange = { viewModel.onDefaultDoseChange(it) },
                            label = { Text("默认单次剂量") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    Text(
                        text = "标识颜色",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MedicationFormOptions.COLORS.forEach { hex ->
                            val c = runCatching {
                                Color(android.graphics.Color.parseColor(hex))
                            }.getOrDefault(MaterialTheme.colorScheme.primary)
                            val selected = uiState.colorHex.equals(hex, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(c)
                                    .clickable { viewModel.onColorChange(hex) },
                                contentAlignment = Alignment.Center
                            ) {
                                if (selected) {
                                    Text(
                                        text = "✓",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ============ 2. 注意事项与医嘱 ============
            item {
                SectionCard(index = 2, title = "注意事项 / 禁忌 (选填)")
                {
                    Text(
                        text = "常用标签 (点击增删，会在详情页以醒目样式高亮)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        MedicationFormOptions.PRECAUTION_PRESETS.forEach { tag ->
                            FilterChip(
                                selected = tag in uiState.precautions,
                                onClick = { viewModel.onPrecautionToggle(tag) },
                                label = {
                                    Text(
                                        tag,
                                        fontWeight = if (tag in uiState.precautions) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            )
                        }
                    }

                    if (uiState.precautions.any { it !in MedicationFormOptions.PRECAUTION_PRESETS }) {
                        Spacer(Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            uiState.precautions
                                .filter { it !in MedicationFormOptions.PRECAUTION_PRESETS }
                                .forEach { tag ->
                                    FilterChip(
                                        selected = true,
                                        onClick = { viewModel.onPrecautionRemove(tag) },
                                        label = { Text(tag, fontWeight = FontWeight.Bold) },
                                        trailingIcon = {
                                            Icon(
                                                Icons.Default.DeleteOutline,
                                                contentDescription = "移除",
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    )
                                }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    var customTag by remember { mutableStateOf("") }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = customTag,
                            onValueChange = { customTag = it },
                            label = { Text("自定义注意事项") },
                            placeholder = { Text("如: 忌与头孢类同用") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                viewModel.onPrecautionAdd(customTag)
                                customTag = ""
                            },
                            enabled = customTag.isNotBlank()
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "添加标签")
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = uiState.description,
                        onValueChange = { viewModel.onDescriptionChange(it) },
                        label = { Text("详细说明 / 医嘱描述") },
                        placeholder = { Text("如: 饭后温水吞服，整粒吞服禁嚼碎") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3
                    )

                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = uiState.noticeShort,
                        onValueChange = { viewModel.onNoticeShortChange(it) },
                        label = { Text("通知栏简述 (选填)") },
                        placeholder = { Text("如: 温水吞服 · 禁葡萄柚") },
                        supportingText = { Text("显示在到点提醒通知的第二行") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(Modifier.height(12.dp))

                    ReadOnlyDateField(
                        label = "药品有效期至 (选填)",
                        dateStr = uiState.expiryDate,
                        onDateChange = { viewModel.onExpiryDateChange(it) },
                        onClear = { viewModel.onExpiryDateChange("") }
                    )
                }
            }

            // ============ 3. 提醒计划 (仅新增模式) ============
            if (!isInfoOnly) {
                item { ReminderPolicyCard(viewModel, uiState) }
                item { InitialStockCard(viewModel, uiState) }
            } else {
                item {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                    ) {
                        Text(
                            text = "ℹ️ 这里只编辑药品信息。提醒频次与时间请在药品详情页的「提醒设置」中调整，库存请在「库存管理」中管理。",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * 当前时刻的"当天分钟数"，**每次组合现算**。
 *
 * 刻意不做成 `remember` 或一个顶层常量（见 M7-4）。判据是**墙上时钟**，
 * 而墙上时钟会走 —— 把它冻结在组合时刻等于让提示在用户眼皮底下过期。
 */
private fun currentMinuteOfDay(): Int {
    val c = Calendar.getInstance()
    return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
}

@Composable
private fun LowFrictionTipCard() {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
    ) {
        Text(
            text = "✨ 低门槛录入：只需填写【药品名称】并选择【提醒频次/时间】，其余均为可选扩展项。",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            lineHeight = 18.sp
        )
    }
}

@Composable
private fun SectionCard(
    index: Int,
    title: String,
    required: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "$index. $title" + if (required) " *" else "",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionDropdown(
    label: String,
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt) },
                    onClick = {
                        onSelect(opt)
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 * 只读日期字段：点击任意位置拉起系统 `DatePickerDialog`，可选清除。
 * 单独抽出来是因为"课程结束日""有效期至"等都需要同样的交互与校验。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadOnlyDateField(
    label: String,
    dateStr: String,
    onDateChange: (String) -> Unit,
    onClear: (() -> Unit)? = null,
    placeholder: String = "未设置",
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    val context = LocalContext.current
    val openPicker = {
        val cal = Calendar.getInstance()
        if (dateStr.isNotBlank()) {
            runCatching {
                val p = dateStr.split("-")
                cal.set(p[0].toInt(), p[1].toInt() - 1, p[2].toInt())
            }
        }
        DatePickerDialog(
            context,
            { _, y, m, d ->
                onDateChange(String.format(Locale.getDefault(), "%04d-%02d-%02d", y, m + 1, d))
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    OutlinedTextField(
        value = dateStr.ifBlank { placeholder },
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        placeholder = { if (dateStr.isBlank()) Text(placeholder) },
        textStyle = if (dateStr.isBlank()) {
            MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            MaterialTheme.typography.bodyLarge
        },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 纯视觉图标：说明这是一个可点的日期字段，实际点击由整框 clickable 承担，
                // 避免"图标有自己的 clickable 抢走事件"导致点图标无反应。
                Icon(
                    Icons.Default.CalendarToday,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                if (dateStr.isNotBlank() && onClear != null) {
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = onClear,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = "清除 $label",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        },
        modifier = modifier.clickable { openPicker() },
        singleLine = true
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ReminderPolicyCard(
    viewModel: AddEditMedicationViewModel,
    uiState: AddEditUiState
) {
    val context = LocalContext.current
    SectionCard(index = 3, title = "提醒频次与时间", required = true) {
        val policyTypes = listOf(
            PolicyType.DAILY to "每天",
            PolicyType.INTERVAL to "隔 N 天",
            PolicyType.DAYS_OF_WEEK to "每周",
            PolicyType.CYCLE to "周期",
            PolicyType.PRN to "按需"
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            policyTypes.forEachIndexed { index, (type, label) ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = policyTypes.size),
                    onClick = { viewModel.onPolicyTypeChange(type) },
                    selected = uiState.policyType == type,
                    icon = {}
                ) { Text(label, fontSize = 11.sp) }
            }
        }

        Spacer(Modifier.height(16.dp))

        when (uiState.policyType) {
            PolicyType.INTERVAL -> {
                StepperRow(
                    label = "服药间隔",
                    value = uiState.intervalDays,
                    onDecrement = { viewModel.onIntervalDaysChange(uiState.intervalDays - 1) },
                    onIncrement = { viewModel.onIntervalDaysChange(uiState.intervalDays + 1) },
                    canDecrement = uiState.intervalDays > 2,
                    canIncrement = uiState.intervalDays < 30,
                    hint = if (uiState.intervalDays == 2) "隔天一次" else "每隔 ${uiState.intervalDays - 1} 天一次"
                )
            }

            PolicyType.CYCLE -> {
                Text(
                    text = "周期轮换：连续服用若干天后停药若干天（如避孕药 21 天服 / 7 天停）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                StepperRow(
                    label = "连续服药天数",
                    value = uiState.cycleOnDays,
                    onDecrement = { viewModel.onCycleDaysChange(uiState.cycleOnDays - 1) },
                    onIncrement = { viewModel.onCycleDaysChange(uiState.cycleOnDays + 1) },
                    canDecrement = uiState.cycleOnDays > 1,
                    canIncrement = uiState.cycleOnDays < 90,
                    hint = "服 ${uiState.cycleOnDays} 天"
                )
                Spacer(Modifier.height(10.dp))
                StepperRow(
                    label = "停药天数",
                    value = uiState.cycleOffDays,
                    onDecrement = { viewModel.onCycleOffDaysChange(uiState.cycleOffDays - 1) },
                    onIncrement = { viewModel.onCycleOffDaysChange(uiState.cycleOffDays + 1) },
                    canDecrement = uiState.cycleOffDays > 0,
                    canIncrement = uiState.cycleOffDays < 30,
                    hint = if (uiState.cycleOffDays == 0) "不设停药期" else "停 ${uiState.cycleOffDays} 天"
                )
            }

            PolicyType.DAYS_OF_WEEK -> {
                Text(
                    text = "每周哪几天服药 (至少选一天)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val labels = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
                    (1..7).forEach { day ->
                        FilterChip(
                            selected = day in uiState.daysOfWeek,
                            onClick = { viewModel.onToggleDayOfWeek(day) },
                            label = {
                                Text(
                                    labels[day - 1],
                                    fontWeight = if (day in uiState.daysOfWeek) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        )
                    }
                }
            }

            PolicyType.PRN -> {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
                ) {
                    Text(
                        text = "按需服用：不设定时闹钟，不会产生任何排班。适合止痛药、晕车药等" +
                            "临时用药，用药时在今日清单或「手动补录」中记录即可。下方仍可设置单次剂量与服药时段。",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        lineHeight = 18.sp
                    )
                }
            }

            else -> Unit
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = "提醒时点",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "常见频次可直接一键铺排 07:00–21:00 之间的均分时间，再单独微调即可。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        val quickCounts = listOf(1, 2, 3, 4)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            quickCounts.forEach { n ->
                OutlinedButton(
                    onClick = { viewModel.spreadTimes(n) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 6.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("每天 $n 次", fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        uiState.timeSlots.forEachIndexed { index, slot ->
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = {
                                val parts = slot.time.split(":")
                                TimePickerDialog(
                                    context,
                                    { _, h, m ->
                                        viewModel.updateTimeSlot(
                                            index,
                                            time = String.format(Locale.getDefault(), "%02d:%02d", h, m)
                                        )
                                    },
                                    parts.getOrNull(0)?.toIntOrNull() ?: 8,
                                    parts.getOrNull(1)?.toIntOrNull() ?: 30,
                                    true
                                ).show()
                            },
                                    modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(slot.time, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                        if (uiState.timeSlots.size > 1) {
                            IconButton(onClick = { viewModel.removeTimeSlot(index) }) {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = "删除该时段",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = slot.dose,
                            onValueChange = { viewModel.updateTimeSlot(index, doseText = it) },
                            label = { Text("剂量") },
                            modifier = Modifier.width(96.dp),
                            singleLine = true,
                            // ⭐ `Decimal` 而不是 `Number`（M2-1）。
                            // `Number` 键盘在多数 ROM 上**没有小数点键**，
                            // 于是 0.5 片这类"半片"根本不可录入 —— 用户只能填整数，
                            // 随后我们还在别处支持 `Dose` 的毫单位精度，自相矛盾。
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            isError = slot.parsedDose() == null
                        )
                        Spacer(Modifier.width(10.dp))
                        OptionDropdown(
                            label = "时段",
                            value = slot.label,
                            options = MedicationFormOptions.TIME_LABELS,
                            onSelect = { viewModel.updateTimeSlot(index, label = it) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // 若有落在过去的时点，明确告知"今天这一剂会直接算逾期"，
        // 避免用户保存后在今日清单看到刺眼的红色"已逾期"却不知原因。
        //
        // ⚠️ `nowMinutes` **不能** `remember` 住（M7-4）。它是"现在几点"，不是"组合时的几点"：
        // 表单开着不动，跨过某个时点后判据就该变，而 `remember` 把值冻结在首次组合的那一刻，
        // 于是提示在用户眼里凭空过期/永不出现。同理 `all { isBeforeNow() }` 的
        // 顺延判定在 VM 里每次现算，两边口径必须一致。
        val nowMinutes = currentMinuteOfDay()
        val pastSlots = uiState.timeSlots.filter {
            val p = it.time.split(":")
            val m = (p.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (p.getOrNull(1)?.toIntOrNull() ?: 0)
            m < nowMinutes
        }
        if (pastSlots.isNotEmpty()) {
            // ⭐ 判据必须与 VM 的顺延规则**逐字对齐**（M7-4）。
            // VM 是 `timeSlots.all { isBeforeNow() }` 才顺延明天 ——
            // **任一**时点未过就照常排进今天。
            // 旧文案只说"这些时点会记为逾期"，可当六个时点里只过了三个时，
            // 实际行为是**不**顺延、那三个确实逾期；文案让人以为整单被推迟。
            // 现在按同一个 `all` 判据分成两种说法，用户看到的就是将要发生的事。
            val allPast = pastSlots.size == uiState.timeSlots.size
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = WarningAmberContainer
            ) {
                Text(
                    text = if (allPast) {
                        "⏰ 所有时点（${pastSlots.joinToString("、") { it.time }}）都已早于当前时间，" +
                            "保存后起始日会自动顺延到明天，今天这一剂不算逾期。"
                    } else {
                        "⏰ ${pastSlots.joinToString("、") { it.time }} 已早于当前时间，" +
                            "保存后这些时点今天这一剂会直接记为逾期。若只想从明天开始提醒，" +
                            "可把时点改到当前时间之后。"
                    },
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnWarningAmberContainer,
                    lineHeight = 18.sp
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        OutlinedButton(
            onClick = { viewModel.addTimeSlot() },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("添加一个提醒时点", fontSize = 13.sp)
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Spacer(Modifier.height(16.dp))

        Text(
            text = "低库存预警",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = uiState.minStockAlert,
            onValueChange = { viewModel.onMinStockAlertChange(it) },
            label = { Text("库存预警阈值") },
            placeholder = { Text("如 10") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
    }
}

@Composable
private fun InitialStockCard(
    viewModel: AddEditMedicationViewModel,
    uiState: AddEditUiState
) {
    SectionCard(index = 4, title = "初始库存 (选填)") {
        Text(
            text = "填写后系统会记录一条建档流水，之后每次服药自动扣减，可随时在「库存管理」中盘点校准。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 18.sp
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = uiState.currentStock,
            onValueChange = { viewModel.onCurrentStockChange(it) },
            label = { Text("当前现有库存 (${uiState.unit})") },
            placeholder = { Text("不填则不追踪库存") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
    }
}

@Composable
private fun StepperRow(
    label: String,
    value: Int,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    canDecrement: Boolean,
    canIncrement: Boolean,
    hint: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDecrement, enabled = canDecrement) {
                Icon(Icons.Default.Remove, contentDescription = "减少", modifier = Modifier.size(18.dp))
            }
            Text(
                text = "$value",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(30.dp),
                textAlign = TextAlign.Center
            )
            IconButton(onClick = onIncrement, enabled = canIncrement) {
                Icon(Icons.Default.Add, contentDescription = "增加", modifier = Modifier.size(18.dp))
            }
        }
    }
}
