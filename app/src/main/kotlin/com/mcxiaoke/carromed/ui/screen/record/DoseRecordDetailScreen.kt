package com.mcxiaoke.carromed.ui.screen.record

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
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
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SkipNext
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mcxiaoke.carromed.R
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import com.mcxiaoke.carromed.ui.component.Quantity
import com.mcxiaoke.carromed.ui.theme.OnSuccessGreenContainer
import com.mcxiaoke.carromed.ui.theme.OnWarningAmberContainer
import com.mcxiaoke.carromed.ui.theme.SuccessGreenContainer
import com.mcxiaoke.carromed.ui.theme.WarningAmberContainer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * 统一「记录详情页」。
 *
 * ## 为什么是一个页面而不是三个
 *
 * 待服 / 已服 / 已跳过共享同一套信息区（药名、计划与实际时点、剂量、备注、余量），
 * 只有操作区不同；而**状态是运行期会变的** —— 用户在这里点「撤销」，
 * 同一条记录就从"已服"变成"待服"。一个页面 + Flow 数据源，形态自动跟随。
 *
 * 拆成三个页面会有三份布局代码，且状态迁移要么跳转、要么重载。
 *
 * ## 入口
 *
 * - 今日清单三分区 → `slotId`
 * - 进展流水 / 单药历史 → `record.slotId` 归一后走 `slotId`，手动补录走 `recordId`
 *
 * 详见 `docs/PLAN-RECORD-DETAIL-20260929.md`。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoseRecordDetailScreen(
    slotId: Long?,
    recordId: Long?,
    onNavigateBack: () -> Unit,
    viewModel: DoseRecordDetailViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(slotId, recordId) { viewModel.load(slotId, recordId) }

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
                title = { Text(stringResource(R.string.rdetail_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.rdetail_back_cd)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        when {
            uiState.isLoading -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            uiState.notFound -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.rdetail_not_found),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            else -> DetailContent(uiState, viewModel, context, innerPadding)
        }
    }
}

@Composable
private fun DetailContent(
    state: DoseEntryUiState,
    viewModel: DoseRecordDetailViewModel,
    context: Context,
    innerPadding: PaddingValues
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .imePadding()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { HeaderCard(state) }

        if (state.canEditTime) {
            item { TimeCard(state) { base -> openTimePicker(context, base, viewModel::onActualTsChange) } }
        }

        item { DoseCard(state, viewModel::onDoseChange) }

        item { NoteCard(state, viewModel::onNoteChange) }

        if (state.hasAnyAction) {
            item { ActionArea(state, viewModel) }
        }

        if (state.canEditNote) {
            item {
                Button(
                    onClick = { viewModel.save() },
                    enabled = !state.isSaving,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (state.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text(stringResource(R.string.rdetail_save_note), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }

        item { Footnote(state) }
    }
}

/** 状态徽标文案与配色（唯一的表，别在别处再写一份） */
private data class StatusChip(val text: String, val container: Color, val content: Color)

@Composable
private fun statusChipOf(state: DoseEntryUiState): StatusChip {
    val scheme = MaterialTheme.colorScheme
    return when {
        state.isManual ->
            StatusChip(stringResource(R.string.rdetail_status_manual), scheme.surfaceVariant, scheme.onSurfaceVariant)
        state.slotStatus == SlotStatus.COMPLETED ->
            StatusChip(
                stringResource(R.string.rdetail_status_completed),
                SuccessGreenContainer,
                OnSuccessGreenContainer
            )

        state.slotStatus == SlotStatus.SKIPPED ->
            StatusChip(
                stringResource(R.string.rdetail_status_skipped),
                scheme.surfaceVariant,
                scheme.onSurfaceVariant
            )

        state.slotStatus == SlotStatus.SNOOZED ->
            StatusChip(
                stringResource(R.string.rdetail_status_snoozed),
                scheme.tertiaryContainer,
                scheme.onTertiaryContainer
            )

        state.slotStatus == SlotStatus.EXPIRED ->
            StatusChip(
                stringResource(R.string.rdetail_status_expired),
                WarningAmberContainer,
                OnWarningAmberContainer
            )

        state.slotStatus == SlotStatus.PENDING ->
            StatusChip(
                stringResource(R.string.rdetail_status_pending),
                scheme.primaryContainer,
                scheme.onPrimaryContainer
            )

        else ->
            StatusChip(
                stringResource(R.string.rdetail_status_record),
                scheme.surfaceVariant,
                scheme.onSurfaceVariant
            )
    }
}

@Composable
private fun HeaderCard(state: DoseEntryUiState) {
    val chip = statusChipOf(state)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = state.medication?.name ?: stringResource(R.string.rdetail_unknown_medication),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Surface(shape = RoundedCornerShape(6.dp), color = chip.container) {
                    Text(
                        text = chip.text,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = chip.content
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // 计划时点与实际时点**分开显示**：槽位来源的记录里，
            // 这两者本来就可能是不同的值，合成一个会让用户以为"就是那个时间吃的"
            //
            // 计划日必须带上：进展页 / 药箱历史点进来的记录可能属于**任何一天**，
            // 只写"计划 10:30"时用户分不清这是哪天的那一剂
            // （本缺陷产生的坏数据更是把"计划 10:30 · 实际 22:06"直接显示成自相矛盾）。
            val lines = buildList {
                state.slot?.let {
                    add(
                        stringResource(
                            R.string.rdetail_scheduled_at,
                            formatDayLabel(it.scheduledDate),
                            it.scheduledTime
                        )
                    )
                }
                state.record?.let { add(stringResource(R.string.rdetail_actual_at, formatClock(it.actualTs))) }
                if (state.slot == null && state.record != null) {
                    add(formatDay(state.record.actualTs))
                }
            }
            if (lines.isNotEmpty()) {
                Text(
                    text = lines.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            val doseMilli = state.record?.doseTaken ?: state.slot?.doseAmount
            if (doseMilli != null && doseMilli > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.rdetail_dose_prefix, state.doseText(doseMilli)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            state.stock?.let { stock ->
                Spacer(Modifier.height(4.dp))
                val isOverspent = stock < 0f
                val isLow = isOverspent || (state.minStockAlert > 0f && stock <= state.minStockAlert)
                Text(
                    text = if (isOverspent) {
                        stringResource(R.string.rdetail_stock_overspent, Quantity.fmt(stock), state.unit)
                    } else {
                        stringResource(R.string.rdetail_stock_remaining, Quantity.fmt(stock), state.unit)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isLow) FontWeight.Bold else FontWeight.Normal,
                    color = if (isLow) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (state.slotStatus == SlotStatus.SNOOZED) {
                state.slot?.snoozeUntilTs?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.rdetail_snoozed_until, formatClock(it)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

/** 改服药时刻。**仅手动补录记录可改**（槽位来源的改时间会让事实与排班分叉，见 §3.3） */
@Composable
private fun TimeCard(state: DoseEntryUiState, onPickTime: (Long) -> Unit) {
    val base = state.pendingActualTs ?: state.record?.actualTs ?: return
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.rdetail_time_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${formatDay(base)}  ${formatClock(base)}",
                    style = MaterialTheme.typography.bodyLarge
                )
                OutlinedButton(
                    onClick = { onPickTime(base) },
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
                ) { Text(stringResource(R.string.rdetail_edit), style = MaterialTheme.typography.labelLarge) }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.rdetail_time_manual_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun DoseCard(state: DoseEntryUiState, onDoseChange: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.rdetail_dose_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
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
                    Text(state.unit, style = MaterialTheme.typography.bodyLarge)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.rdetail_dose_edit_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val doseMilli = state.record?.doseTaken ?: state.slot?.doseAmount ?: 0
                Text(state.doseText(doseMilli), style = MaterialTheme.typography.bodyLarge)
                if (state.isManual && !state.withinEditWindow) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.rdetail_dose_readonly_hint, EDITABLE_WINDOW_DAYS),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (!state.isManual) {
                    Spacer(Modifier.height(6.dp))
                    val hasConclusion = state.slotStatus == SlotStatus.COMPLETED ||
                        state.slotStatus == SlotStatus.SKIPPED
                    Text(
                        // ⚠️ 待服 / 已逾期形态**没有**「撤销」按钮（还没有结论，无从撤销），
                        // 所以不能对它说"记错了请用撤销" —— 那是把用户指向一个不存在的入口。
                        text = if (hasConclusion) {
                            stringResource(R.string.rdetail_dose_locked_conclusion)
                        } else {
                            stringResource(R.string.rdetail_dose_locked_no_conclusion)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun NoteCard(state: DoseEntryUiState, onNoteChange: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.rdetail_note_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            if (state.isReverted) {
                Text(
                    text = state.noteInput.ifBlank { stringResource(R.string.rdetail_note_empty) },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                OutlinedTextField(
                    value = state.noteInput,
                    onValueChange = onNoteChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(stringResource(R.string.rdetail_note_placeholder), style = MaterialTheme.typography.bodyMedium)
                    },
                    shape = RoundedCornerShape(10.dp)
                )
                if (state.noteJoinsConfirm) {
                    Spacer(Modifier.height(6.dp))
                    // ⚠️ 待服状态还没有服药事实，备注无处可存 —— 如实说明，
                    // 不做"看起来保存了"的假提示（AGENTS.md §2 第 6 条）。
                    // 未来槽位更要把话说全：这一页**没有**确认按钮，
                    // 说"会随确认服用一起保存"等于指着一个不存在的入口。
                    Text(
                        text = if (state.isActionable) {
                            stringResource(R.string.rdetail_note_actionable_hint)
                        } else {
                            stringResource(R.string.rdetail_note_future_hint)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}

/**
 * 动作区。**按状态渲染，不渲染的按钮不置灰** ——
 * 置灰会让人以为"再等等就能用"，而"撤销一条昨天的记录"永远不会变可用。
 */
@Composable
private fun ActionArea(state: DoseEntryUiState, viewModel: DoseRecordDetailViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (state.canConfirm) {
            Button(
                onClick = { viewModel.confirm() },
                enabled = !state.isSaving,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (state.slotStatus == SlotStatus.SKIPPED) {
                        stringResource(R.string.rdetail_confirm_rejudged)
                    } else {
                        stringResource(R.string.rdetail_confirm)
                    },
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        if (state.canSnooze) {
            Text(
                text = stringResource(R.string.rdetail_snooze_label),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val options = remember(state.globalSnoozeMinutes) { snoozeOptionsOf(state.globalSnoozeMinutes) }
            SnoozeRow(options.take(3)) { viewModel.snooze(it) }
            if (options.size > 3) {
                SnoozeRow(options.drop(3)) { viewModel.snooze(it) }
            }
        }

        if (state.canSkip) {
            OutlinedButton(
                onClick = { viewModel.skip() },
                enabled = !state.isSaving,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(vertical = 10.dp)
            ) {
                Icon(Icons.Default.SkipNext, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (state.slotStatus == SlotStatus.COMPLETED) {
                        stringResource(R.string.rdetail_skip_rejudged)
                    } else {
                        stringResource(R.string.rdetail_skip)
                    }
                )
            }
        }

        if (state.canUndo) {
            OutlinedButton(
                onClick = { viewModel.undo() },
                enabled = !state.isSaving,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(vertical = 10.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Undo,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.rdetail_undo))
            }
        }
    }
}

@Composable
private fun SnoozeRow(options: List<Int>, onPick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        options.forEach { m ->
            OutlinedButton(
                onClick = { onPick(m) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) { Text(stringResource(R.string.rdetail_minutes, m), fontSize = 13.sp) }
        }
    }
}

/** 页脚说明。**诚实提示比置灰按钮更有用**。 */
@Composable
private fun Footnote(state: DoseEntryUiState) {
    val text = when {
        // ⚠️ 这一条必须在最前：未来槽位"没有按钮"是**正确行为**，
        // 不能被下面的"只有当天可以撤销"之类文案误导成"这项功能不可用"。
        !state.isActionable && !state.isManual ->
            stringResource(R.string.rdetail_footnote_future)

        state.isReverted ->
            stringResource(R.string.rdetail_footnote_reverted)

        state.isManual && !state.withinEditWindow ->
            stringResource(R.string.rdetail_footnote_manual_readonly, EDITABLE_WINDOW_DAYS)

        !state.isManual && state.slotStatus == SlotStatus.COMPLETED && !state.canUndo ->
            stringResource(R.string.rdetail_footnote_completed_no_undo)

        !state.isManual && state.slotStatus == SlotStatus.SKIPPED && !state.canUndo ->
            stringResource(R.string.rdetail_footnote_skipped_no_undo)

        state.slotStatus == SlotStatus.EXPIRED ->
            stringResource(R.string.rdetail_footnote_expired)

        else -> null
    }
    if (text != null) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 16.sp
        )
    }
}

/** 推迟档位：与今日页长按弹窗同一套取值（10/15/30/60/120），并把全局设置值并进来 */
private fun snoozeOptionsOf(globalMinutes: Int): List<Int> =
    listOf(10, 15, 30, 60, 120)
        .let { if (it.contains(globalMinutes)) it else (it + globalMinutes).sorted() }

private fun formatClock(ts: Long): String {
    val zdt = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault())
    return String.format(Locale.getDefault(), "%02d:%02d", zdt.hour, zdt.minute)
}

private fun formatDay(ts: Long): String =
    Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate().toString()

/**
 * 计划日（库里是规范 `yyyy-MM-dd` 串）→「9月30日」。
 *
 * 解析失败时**原样回显**：这段代码渲染的是详情页顶部，
 * 一条坏串不该把整页打成崩溃（AGENTS.md §2 第 7 条：坏数据只坏在一处）。
 */
private fun formatDayLabel(scheduledDate: String): String =
    runCatching { LocalDate.parse(scheduledDate) }.getOrNull()
        ?.let { "${it.monthValue}月${it.dayOfMonth}日" }
        ?: scheduledDate

/**
 * 日期 → 时间 两级选择器。
 *
 * 抽成独立函数而不是内联：Kotlin 对 `DatePickerDialog` 的多个重载做 SAM 转换时，
 * 在嵌套 lambda 里会选错重载（把 listener 当成 `themeResId`），
 * 报出一串"Cannot infer type"的费解错误。
 *
 * `maxDate` 设为现在：服药是**已发生**的事实，不允许记一条未来的服药。
 */
private fun openTimePicker(context: Context, baseTs: Long, onPicked: (Long) -> Unit) {
    val z = Instant.ofEpochMilli(baseTs).atZone(ZoneId.systemDefault())
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
                                .atZone(ZoneId.systemDefault())
                                .toInstant()
                                .toEpochMilli()
                            onPicked(picked)
                        }
                    },
                    z.hour, z.minute,
                    // 24 小时制：服药时点是"几点几分"，12 小时制在 13:30
                    // 这类值上要多一次 AM/PM 心智负担，与手动补录页保持一致。
                    true
                ).show()
            }
        },
        z.year, z.monthValue - 1, z.dayOfMonth
    )
    dialog.datePicker.maxDate = System.currentTimeMillis()
    dialog.show()
}

/** 供预览/测试用：剂量展示口径与流水页保持一致。 */
internal fun doseText(milli: Int, unit: String): String =
    Quantity.withUnit(com.mcxiaoke.carromed.core.domain.model.Dose(milli).asFloat, unit)
