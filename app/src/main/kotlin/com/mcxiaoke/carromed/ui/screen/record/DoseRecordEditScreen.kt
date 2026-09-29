package com.mcxiaoke.carromed.ui.screen.record

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mcxiaoke.carromed.core.data.model.RecordStatus
import com.mcxiaoke.carromed.core.domain.model.Dose
import com.mcxiaoke.carromed.ui.component.Quantity
import com.mcxiaoke.carromed.ui.theme.OnSuccessGreenContainer
import com.mcxiaoke.carromed.ui.theme.SuccessGreenContainer
import com.mcxiaoke.carromed.ui.theme.WarningAmberContainer
import kotlinx.coroutines.launch

/**
 * 服药记录详情页（UX 方案 §3）。
 *
 * ## 为什么是独立页面而不是弹菜单
 *
 * 低频操作有四个：撤销、跳过、确认、改剂量/备注/时间。
 * 塞进一个 `⋮` 弹窗里，用户每次都要在四项里辨认自己要做哪一个；
 * 而"我记错了"这个场景往往发生在**已经忘了当时填了什么**的时候 ——
 * 弹菜单只给你操作，不给你信息。独立页面把"这条记录是什么"和"能怎么改"放在一起。
 *
 * ## 2 天窗口
 *
 * 见 [EDITABLE_WINDOW_DAYS]。超窗的记录**只读**且不显示撤销/跳过，
 * 而不是把按钮置灰 —— 置灰会让人以为"再等等就能改"，实际永远不会。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoseRecordEditScreen(
    recordId: Long,
    onNavigateBack: () -> Unit,
    viewModel: DoseRecordEditViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(recordId) { viewModel.load(recordId) }

    LaunchedEffect(uiState.done) {
        if (uiState.done) onNavigateBack()
    }
    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("服药记录", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
                Modifier.fillMaxSize().padding(innerPadding),
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
            contentPadding = PaddingValues(top = 8.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { RecordHeaderCard(uiState) }

            item {
                RecordTimeCard(uiState) { base -> openTimePicker(context, base, viewModel::onActualTsChange) }
            }

            item { DoseFieldCard(uiState, viewModel::onDoseChange) }

            item { NoteFieldCard(uiState, viewModel::onNoteChange) }

            if (!uiState.isReverted) {
                item { ActionRow(uiState, viewModel) }
            }

            item {
                Text(
                    text = READ_ONLY_NOTE,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }

            item {
                Button(
                    onClick = { viewModel.save() },
                    enabled = !uiState.isSaving && !uiState.isReverted,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (uiState.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text("保存", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

/** 页面底部的常驻说明。**诚实提示比置灰按钮更有用**。 */
private const val READ_ONLY_NOTE =
    "服药记录不会被物理删除：撤销只是把它标为「已撤销」，不再计入统计。" +
        "这样你的历史数据永远可追溯，库存台账也不会出现悬空引用。"

@Composable
private fun RecordHeaderCard(state: DoseRecordUiState) {
    val (chipText, chipColor) = when (state.record?.status) {
        RecordStatus.COMPLETED -> "已服" to MaterialTheme.colorScheme.primaryContainer
        RecordStatus.SKIPPED -> "已跳过" to MaterialTheme.colorScheme.surfaceVariant
        RecordStatus.REVERTED -> "已撤销" to MaterialTheme.colorScheme.surfaceVariant
        null -> "" to MaterialTheme.colorScheme.surfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = state.medication?.name ?: "已删除的药品",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (state.record?.isRetrospective == true) {
                    Text(
                        text = "补录",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Surface(shape = RoundedCornerShape(6.dp), color = chipColor) {
                Text(
                    text = chipText,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun RecordTimeCard(state: DoseRecordUiState, onPickTime: (Long) -> Unit) {
    val base = state.pendingActualTs ?: state.record?.actualTs ?: return
    val zdt = java.time.Instant.ofEpochMilli(base).atZone(java.time.ZoneId.systemDefault())
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "服药时间",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "${zdt.toLocalDate()}  ${zdt.toLocalTime().toString().take(5)}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (state.canEditTime) {
                    OutlinedButton(
                        onClick = { onPickTime(base) },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
                    ) { Text("修改", style = MaterialTheme.typography.labelLarge) }
                }
            }
            Spacer(Modifier.height(8.dp))
            // ⚠️ 文案按**记录来源**分两种，不能一套话说两件事。
            //
            // 定时提醒产生的记录若允许改时间，事实行 `actual_ts` 与槽位的
            // `scheduled_time` 就会各说一个时间（与 C-40 同一类缺陷），
            // 所以只读并说清正确做法；手动补录的记录没有槽位，可以自由改。
            Text(
                text = if (state.fromSchedule) {
                    "定时提醒产生的服药不能改时间 —— 它要和你在提醒设置里排的时点保持一致。" +
                        "记错了请用下面的「撤销」，撤销后可以在今日清单重新打卡。"
                } else {
                    "这是一条手动补录的记录，可以自由修改时间。" +
                        "不能选到未来的时间。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun DoseFieldCard(state: DoseRecordUiState, onDoseChange: (String) -> Unit) {
    val unit = state.medication?.unit ?: "片"
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "服用剂量",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            if (state.canEditDose) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = state.doseInput,
                        onValueChange = onDoseChange,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        shape = RoundedCornerShape(10.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "改剂量会自动补一条差额流水，库存台账保持对得上。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = Quantity.withUnit(
                            Dose(state.record?.doseTaken ?: 0).asFloat,
                            unit
                        ),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = when {
                        !state.withinEditWindow -> "超过 2 天的记录只读。"
                        state.fromSchedule ->
                            "定时提醒产生的服药不能改剂量 —— 它要和你在提醒设置里排的" +
                                "计划量保持一致。实际吃了多少可以写在备注里。"
                        else -> "这是一条手动补录的记录，可以自由修改剂量。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
private fun NoteFieldCard(state: DoseRecordUiState, onNoteChange: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "备注",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            if (state.isReverted) {
                Text(
                    text = state.noteInput.ifBlank { "（无）" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                OutlinedTextField(
                    value = state.noteInput,
                    onValueChange = onNoteChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("例如：随餐温水送服", style = MaterialTheme.typography.bodyMedium) },
                    shape = RoundedCornerShape(10.dp)
                )
            }
        }
    }
}

/**
 * 撤销 / 跳过 / 确认。
 *
 * 超窗时**整块不渲染**而不是置灰：置灰会让人以为"过两天就能改"，
 * 而实际上永远不能 —— 那是比灰按钮更坏的承诺。
 */
@Composable
private fun ActionRow(state: DoseRecordUiState, viewModel: DoseRecordEditViewModel) {
    if (state.isReverted) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = SuccessGreenContainer
        ) {
            Text(
                text = "这条记录已撤销，不再计入依从率与消耗统计。",
                modifier = Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = OnSuccessGreenContainer
            )
        }
        return
    }
    if (!state.withinEditWindow) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = WarningAmberContainer
        ) {
            Text(
                text = "这条记录已超过 2 天，不能再撤销或改状态 —— " +
                    "它已经计入了那几天的依从率。备注仍可修改。",
                modifier = Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 20.sp
            )
        }
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (state.isSkipped) {
            // 已跳过的记录，"确认"与"撤销"并存：跳过往往是误操作
            OutlinedButton(
                onClick = { viewModel.revert() },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp)
            ) { Text("撤销") }
        } else {
            OutlinedButton(
                onClick = { viewModel.markSkipped() },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp)
            ) { Text("标记已跳过") }
            OutlinedButton(
                onClick = { viewModel.revert() },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp)
            ) { Text("撤销") }
        }
    }
}

/** 供预览/测试用：剂量展示口径与流水页保持一致。 */
internal fun doseText(milli: Int, unit: String): String =
    Quantity.withUnit(Dose(milli).asFloat, unit)

/**
 * 日期 → 时间 两级选择器，返回选中的时刻（epoch 毫秒）。
 *
 * 抽成独立函数而不是内联在 `item {}` 里：Kotlin 对 `DatePickerDialog` 的多个重载
 * 做 SAM 转换时，在嵌套 lambda 里会选错重载（把 listener 当成 `themeResId`），
 * 报出一串"Cannot infer type"的费解错误。写明 listener 类型就没这个问题。
 *
 * `maxDate` 设为现在：服药是**已发生**的事实，不允许记一条未来的服药。
 */
private fun openTimePicker(
    context: Context,
    baseTs: Long,
    onPicked: (Long) -> Unit
) {
    val z = java.time.Instant.ofEpochMilli(baseTs).atZone(java.time.ZoneId.systemDefault())
    val dialog = DatePickerDialog(
        context,
        object : DatePickerDialog.OnDateSetListener {
            override fun onDateSet(
                view: android.widget.DatePicker?,
                year: Int,
                month: Int,
                day: Int
            ) {
                TimePickerDialog(
                    context,
                    object : TimePickerDialog.OnTimeSetListener {
                        override fun onTimeSet(view: android.widget.TimePicker?, hour: Int, minute: Int) {
                            val picked = java.time.LocalDate.of(year, month + 1, day)
                                .atTime(hour, minute)
                                .atZone(java.time.ZoneId.systemDefault())
                                .toInstant()
                                .toEpochMilli()
                            onPicked(picked)
                        }
                    },
                    z.hour, z.minute,
                    // 24 小时制：服药时点是"几点几分"，12 小时制在 13:30
                    // 这类值上要多一次 AM/PM 心智负担，和手动补录页保持一致。
                    true
                ).show()
            }
        },
        z.year, z.monthValue - 1, z.dayOfMonth
    )
    dialog.datePicker.maxDate = System.currentTimeMillis()
    dialog.show()
}
