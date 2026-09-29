package com.mcxiaoke.carromed.ui.screen.reminder

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcxiaoke.carromed.core.data.model.PolicyType
import com.mcxiaoke.carromed.ui.screen.edit.MedicationFormOptions
import com.mcxiaoke.carromed.ui.screen.edit.ReadOnlyDateField
import java.util.Locale

/**
 * 提醒设置页 —— 「修改提醒」的专属界面
 *
 * 与 [com.mcxiaoke.carromed.ui.screen.edit.AddEditMedicationScreen]（药品信息）严格分离：
 * 频次、疗程、时点、提醒行为都在这里改，改完只重排未来排班，历史打卡事实永不改动。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReminderSettingsScreen(
    viewModel: ReminderSettingsViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("提醒设置", fontWeight = FontWeight.Bold)
                        uiState.medication?.let {
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
                        onClick = { viewModel.save() },
                        enabled = !uiState.isSaving && !uiState.isLoading,
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
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
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
            uiState.error?.let { err ->
                item {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Text(
                            text = "⚠️ $err",
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // 1. 提醒开关
            item {
                SettingsCard(title = "提醒开关") {
                    SwitchRow(
                        title = "暂停该药品的提醒",
                        subtitle = if (uiState.isPaused) {
                            "当前已暂停：不再产生新提醒与闹钟，历史记录保留"
                        } else {
                            "临时出差 / 感冒停药时用，比删除药品安全"
                        },
                        checked = uiState.isPaused,
                        onChange = { viewModel.onPausedChange(it) }
                    )
                }
            }

            // 2. 频次
            item {
                SettingsCard(title = "服药频次") {
                    val types = listOf(
                        PolicyType.DAILY to "每天",
                        PolicyType.INTERVAL to "隔 N 天",
                        PolicyType.DAYS_OF_WEEK to "每周",
                        PolicyType.CYCLE to "周期",
                        PolicyType.PRN to "按需"
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        types.forEachIndexed { i, (t, label) ->
                            SegmentedButton(
                                shape = SegmentedButtonDefaults.itemShape(i, types.size),
                                onClick = { viewModel.onPolicyTypeChange(t) },
                                selected = uiState.policyType == t,
                                icon = {}
                            ) { Text(label, fontSize = 11.sp) }
                        }
                    }
                    Spacer(Modifier.height(14.dp))

                    when (uiState.policyType) {
                        PolicyType.INTERVAL -> Stepper(
                            label = "服药间隔",
                            hint = if (uiState.intervalDays == 2) "隔天一次"
                            else "每隔 ${uiState.intervalDays - 1} 天一次",
                            value = uiState.intervalDays,
                            canDec = uiState.intervalDays > 2,
                            canInc = uiState.intervalDays < 30,
                            onDec = { viewModel.onIntervalDaysChange(uiState.intervalDays - 1) },
                            onInc = { viewModel.onIntervalDaysChange(uiState.intervalDays + 1) }
                        )

                        PolicyType.DAYS_OF_WEEK -> {
                            Text(
                                "选择每周固定服药日",
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
                                        onClick = { viewModel.onToggleDay(day) },
                                        label = {
                                            Text(
                                                labels[day - 1],
                                                fontWeight = if (day in uiState.daysOfWeek) FontWeight.Bold
                                                else FontWeight.Normal
                                            )
                                        }
                                    )
                                }
                            }
                        }

                        PolicyType.CYCLE -> {
                            Text(
                                "连续服 N 天后停药 M 天（如避孕药 21 服 / 7 停）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(10.dp))
                            Stepper(
                                label = "连续服药",
                                hint = "服 ${uiState.cycleOnDays} 天",
                                value = uiState.cycleOnDays,
                                canDec = uiState.cycleOnDays > 1,
                                canInc = uiState.cycleOnDays < 90,
                                onDec = { viewModel.onCycleOnChange(uiState.cycleOnDays - 1) },
                                onInc = { viewModel.onCycleOnChange(uiState.cycleOnDays + 1) }
                            )
                            Spacer(Modifier.height(10.dp))
                            Stepper(
                                label = "停药",
                                hint = if (uiState.cycleOffDays == 0) "不设停药期" else "停 ${uiState.cycleOffDays} 天",
                                value = uiState.cycleOffDays,
                                canDec = uiState.cycleOffDays > 0,
                                canInc = uiState.cycleOffDays < 30,
                                onDec = { viewModel.onCycleOffChange(uiState.cycleOffDays - 1) },
                                onInc = { viewModel.onCycleOffChange(uiState.cycleOffDays + 1) }
                            )
                        }

                        PolicyType.PRN -> HintCard(
                            "按需服用：不设定时闹钟，也不会生成每日排班。" +
                                "适合止痛药、晕车药等临时用药 —— 需要记录时在今日清单用「手动补录」写下实际时间与剂量即可。"
                        )

                        PolicyType.DAILY -> HintCard("每天在下方设定的时点提醒服药。")
                    }
                }
            }

            // 3. 疗程
            item {
                SettingsCard(title = "服药疗程") {
                    ReadOnlyDateField(
                        label = "开始日期",
                        dateStr = uiState.startDate,
                        onDateChange = { viewModel.onStartDateChange(it) }
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "设置结束日期",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                if (uiState.hasEndDate) "到该日期后自动停止提醒（抗生素疗程）" else "长期服用：无限期",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = uiState.hasEndDate, onCheckedChange = { viewModel.onHasEndDateChange(it) })
                    }
                    if (uiState.hasEndDate) {
                        Spacer(Modifier.height(12.dp))
                        ReadOnlyDateField(
                            label = "结束日期",
                            dateStr = uiState.endDate.orEmpty(),
                            onDateChange = { viewModel.onEndDateChange(it) },
                            onClear = { viewModel.onEndDateChange(null) }
                        )
                    }
                }
            }

            // 4. 时点
            if (uiState.policyType != PolicyType.PRN) {
                item {
                    SettingsCard(title = "提醒时点与剂量") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(1, 2, 3, 4).forEach { n ->
                                OutlinedButton(
                                    onClick = { viewModel.spreadTimes(n) },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(vertical = 6.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) { Text("每天 $n 次", fontSize = 12.sp) }
                            }
                        }
                        Spacer(Modifier.height(14.dp))

                        uiState.times.forEachIndexed { index, t ->
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
                                                val p = t.time.split(":")
                                                android.app.TimePickerDialog(
                                                    context,
                                                    { _, h, m ->
                                                        viewModel.updateTime(
                                                            index,
                                                            time = String.format(
                                                                Locale.getDefault(), "%02d:%02d", h, m
                                                            )
                                                        )
                                                    },
                                                    p.getOrNull(0)?.toIntOrNull() ?: 8,
                                                    p.getOrNull(1)?.toIntOrNull() ?: 30,
                                                    true
                                                ).show()
                                            },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Schedule,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(t.time, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                        }
                                        if (uiState.times.size > 1) {
                                            IconButton(onClick = { viewModel.removeTime(index) }) {
                                                Icon(
                                                    Icons.Default.DeleteOutline,
                                                    contentDescription = "删除",
                                                    tint = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        OutlinedTextField(
                                            value = t.dose,
                                            onValueChange = { viewModel.updateTime(index, doseText = it) },
                                            label = { Text("剂量") },
                                            modifier = Modifier.width(100.dp),
                                            singleLine = true,
                                            // ⭐ `Decimal` 而非 `Number`（M2-1）：
                                            // `Number` 键盘多数 ROM 上没有小数点键，
                                            // 0.5 片这类"半片"根本不可录入。
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                            isError = t.parsedDose() == null
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        LabelDropdown(
                                            value = t.label,
                                            onSelect = { viewModel.updateTime(index, label = it) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }

                        OutlinedButton(
                            onClick = { viewModel.addTime() },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("添加一个提醒时点", fontSize = 13.sp)
                        }
                    }
                }

                // 5. 未来 7 天预览
                item { PreviewCard(uiState) }
            }

            // 6. 提醒行为
            item {
                SettingsCard(title = "提醒行为") {
                    SwitchRow(
                        title = "重要提醒",
                        subtitle = "到点时不静音、不受夜间免打扰限制，响铃 + 强提醒横幅",
                        checked = uiState.isCriticalReminder,
                        onChange = { viewModel.onCriticalChange(it) }
                    )
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Spacer(Modifier.height(14.dp))
                    Stepper(
                        label = "推迟时长",
                        // ⭐ 显式的「跟随全局」档位（M7-5）。
                        //
                        // 旧实现把哨兵 0 显示成 30，于是：
                        // - 「本药固定 30 分钟」**不可表达** —— 一调到 30 就变成"跟随全局"；
                        // - 全局改成 20 之后，本药这一行仍显示 30，**实际生效 20**。
                        //
                        // 显示值与生效值分叉是最坏的一种不一致：用户看到的数字
                        // 恰恰是**系统没有采用**的那个。
                        //
                        // 现在把"跟随全局"做成一个看得见、点得回的档位：
                        // 显示全局实际生效的分钟数，并标明来源，让两者不可能再分叉。
                        hint = if (uiState.snoozeMinutes == 0) {
                            "跟随全局设置（当前 ${uiState.globalSnoozeMinutes} 分钟）"
                        } else {
                            "通知栏「稍后提醒」的分钟数（本药专属）"
                        },
                        value = if (uiState.snoozeMinutes == 0) uiState.globalSnoozeMinutes
                        else uiState.snoozeMinutes,
                        canDec = uiState.snoozeMinutes > 0,
                        canInc = (if (uiState.snoozeMinutes == 0) uiState.globalSnoozeMinutes
                        else uiState.snoozeMinutes) < 120,
                        onDec = {
                            val cur = if (uiState.snoozeMinutes == 0) uiState.globalSnoozeMinutes
                            else uiState.snoozeMinutes
                            // 从"跟随全局"第一次减 ⇒ 落到"专属 5"，让用户能表达出这一档
                            viewModel.onSnoozeMinutesChange(cur - 5)
                        },
                        onInc = {
                            val cur = if (uiState.snoozeMinutes == 0) uiState.globalSnoozeMinutes
                            else uiState.snoozeMinutes
                            viewModel.onSnoozeMinutesChange(cur + 5)
                        },
                        // 从"专属"退回"跟随全局"的唯一出口
                        onResetToGlobal = {
                            viewModel.onSnoozeMinutesChange(FOLLOW_GLOBAL)
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                    Stepper(
                        label = "提前提醒",
                        hint = if (uiState.advanceMinutes == 0) "准点提醒" else "提前 ${uiState.advanceMinutes} 分钟",
                        value = uiState.advanceMinutes,
                        canDec = uiState.advanceMinutes > 0,
                        // ⭐ 上界 120，与 VM 的 `coerceIn(0, 120)` **逐字对齐**（M7-7）。
                        // 旧实现这里写 60，而 VM 允许到 120：导入一个 90 分钟后
                        // 「+」是禁用的，值卡在 90 上不去也下不来（先减到 55 再加能回来，
                        // 但那是"绕"，不是"能用"）。UI 与 VM 的取值域必须只有一个定义。
                        canInc = uiState.advanceMinutes < 120,
                        onDec = { viewModel.onAdvanceMinutesChange(uiState.advanceMinutes - 5) },
                        onInc = { viewModel.onAdvanceMinutesChange(uiState.advanceMinutes + 5) }
                    )
                }
            }

            item {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                ) {
                    Text(
                        text = "ℹ️ 修改提醒只会重排「未来尚未执行」的排班；已经打卡的历史记录永远不会被改动或删除。",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewCard(uiState: ReminderSettingsUiState) {
    SettingsCard(title = "未来 7 天排班预览") {
        if (uiState.policyType == PolicyType.PRN) {
            HintCard("按需服用不产生排班，因此没有预览。")
            return@SettingsCard
        }
        uiState.preview.forEach { d ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(Modifier.width(76.dp)) {
                    Text(
                        text = if (d.date == java.time.LocalDate.now()) "今天 ${d.dayLabel}" else "${d.date.monthValue}/${d.date.dayOfMonth} ${d.dayLabel}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (d.date == java.time.LocalDate.now()) FontWeight.Bold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = if (d.scheduled) d.timeTexts.joinToString("  ") else "—",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (d.scheduled) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.outlineVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f)
                )
            }
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

@Composable
private fun HintCard(text: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            lineHeight = 18.sp
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 17.sp
            )
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Stepper(
    label: String,
    hint: String,
    value: Int,
    canDec: Boolean,
    canInc: Boolean,
    onDec: () -> Unit,
    onInc: () -> Unit,
    /**
     * 非 null 时显示一个「回到全局」的文字按钮。
     *
     * 只给"存在哨兵档位"的字段用（当前只有推迟时长）。给一个纯数值的
     * 步进器加这个按钮会让人以为它也能"恢复默认"，而它没有默认值概念。
     */
    onResetToGlobal: (() -> Unit)? = null
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(hint, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            if (onResetToGlobal != null) {
                TextButton(
                    onClick = onResetToGlobal,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)
                ) {
                    Text("跟随全局", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Row(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDec, enabled = canDec) {
                Icon(Icons.Default.Remove, contentDescription = "减少", modifier = Modifier.size(18.dp))
            }
            Text(
                text = "$value",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(40.dp),
                textAlign = TextAlign.Center
            )
            IconButton(onClick = onInc, enabled = canInc) {
                Icon(Icons.Default.Add, contentDescription = "增加", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabelDropdown(
    value: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text("服药建议") },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MedicationFormOptions.TIME_LABELS.forEach { opt ->
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
