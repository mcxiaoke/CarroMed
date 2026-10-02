package com.mcxiaoke.carromed.ui.screen.detail
import com.mcxiaoke.carromed.core.time.CurrentDateHolder
import com.mcxiaoke.carromed.core.domain.engine.StatsEngine
import android.app.DatePickerDialog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcxiaoke.carromed.R
import com.mcxiaoke.carromed.core.domain.model.Dose
import com.mcxiaoke.carromed.core.domain.model.PauseStatus
import com.mcxiaoke.carromed.core.data.model.PolicyType
import com.mcxiaoke.carromed.core.data.model.RecordStatus
import com.mcxiaoke.carromed.core.data.model.TransactionType
import com.mcxiaoke.carromed.ui.component.MedVocab
import com.mcxiaoke.carromed.ui.component.Quantity
import com.mcxiaoke.carromed.ui.component.TestTags
import com.mcxiaoke.carromed.ui.component.intervalLabel
import com.mcxiaoke.carromed.ui.theme.OnWarningAmberContainer
import com.mcxiaoke.carromed.ui.theme.SuccessGreen
import com.mcxiaoke.carromed.ui.theme.WarningAmber
import com.mcxiaoke.carromed.ui.theme.WarningAmberContainer
import java.time.LocalDate
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 药品详情页 —— **只读总览 + 四个分节入口**
 *
 * 对齐 MyTherapy 实机截图的信息架构：药品信息 / 库存 / 提醒设置 三件事各自分开，
 * 每件都给一个明确的可点击入口与摘要，而不是把三个维度平铺在一屏里
 * 只留一个语义模糊的「修改计划」按钮。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MedicationDetailScreen(
    viewModel: MedicationDetailViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToEditInfo: (Long) -> Unit,
    onNavigateToReminder: (Long) -> Unit,
    onNavigateToInventory: (Long) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val med = uiState.medication

    var showArchiveDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showAllRecords by remember { mutableStateOf(false) }

    var showPauseDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(uiState.error) {
        val err = uiState.error
        if (!err.isNullOrBlank()) {
            android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.mdetail_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.mdetail_cd_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        if (med == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                if (uiState.isLoading) CircularProgressIndicator()
                else Text(uiState.error ?: stringResource(R.string.mdetail_loading))
            }
            return@Scaffold
        }

        val medColor = med.colorHex.let {
            runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull()
        } ?: MaterialTheme.colorScheme.primary


        // PRN(按需) 没有定时排班，状态徽标要如实反映，不能沿用"提醒进行中"
        val policyType = uiState.policy?.policyType
        val isPrn = policyType == null || policyType == PolicyType.PRN
        // 暂停说明与判定统一走 ReminderSettingsEntity（含"暂停至某日"的到期恢复语义）
        //
        // ⚠️ "今天"取 [CurrentDateHolder.today] 而不是 `LocalDate.now()`（M3-4）。
        // `pauseDescription` 是**墙上时钟**驱动的：跨过恢复日而进程仍活着时，
        // 徽标会一直显示「提醒已暂停，N 天后恢复」，而实际上早已自动恢复、
        // 闹钟也照常排了。用户会以为这个药还在停药期里而漏服。
        // 页面在 state 里 observe 这个 Flow（M3-2），跨过恢复日会重新组合。
        val today by CurrentDateHolder.today.collectAsStateWithLifecycle(LocalDate.now())
        val isPaused = uiState.reminderSettings.pauseStatus(today) != PauseStatus.NotPaused
        val pauseText = when (val ps = uiState.reminderSettings.pauseStatus(today)) {
            PauseStatus.NotPaused -> ""
            PauseStatus.PausedIndefinitely -> stringResource(R.string.common_pause_paused)
            is PauseStatus.PausedWithResume ->
                if (ps.days <= 1) stringResource(R.string.common_pause_resume_tomorrow)
                else stringResource(R.string.common_pause_resume_days, ps.days)
        }
        val statusText = when {
            med.isArchived -> stringResource(R.string.mdetail_status_archived)
            isPaused -> pauseText
            isPrn -> stringResource(R.string.mdetail_status_prn)
            else -> stringResource(R.string.mdetail_status_active)
        }
        val statusColor = when {
            med.isArchived -> MaterialTheme.colorScheme.onSurfaceVariant
            isPaused || isPrn -> MaterialTheme.colorScheme.tertiary
            else -> SuccessGreen
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ---------- 1. Hero 卡 (药名 + 状态 + 徽标) ----------
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = med.name,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (!med.alias.isNullOrBlank()) {
                                Text(
                                    text = med.alias,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // category/form 存的是稳定 key（B3 key 化），显示时映射成
                                // 本地化名；未知 key（自由文本/旧数据）原样回显。
                                TagChip(MedVocab.categoryRes(med.category)?.let { stringResource(it) } ?: med.category)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "${MedVocab.formRes(med.form)?.let { stringResource(it) } ?: med.form} · ${med.unit}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(statusColor)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = statusText,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = statusColor
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(medColor.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(medColor)
                            )
                        }
                    }
                }
            }

            // ---------- 2. 三个维度入口 (MyTherapy 式分节) ----------
            item {
                Text(
                    text = stringResource(R.string.mdetail_section_settings),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                )
            }

            item {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DetailEntryRow(
                        icon = Icons.Default.Medication,
                        title = stringResource(R.string.mdetail_entry_profile),
                        subtitle = buildProfileSummary(uiState),
                        testTag = TestTags.DETAIL_ROW_EDIT,
                        onClick = { onNavigateToEditInfo(med.id) }
                    )
                    DetailEntryRow(
                        icon = Icons.Default.Schedule,
                        title = stringResource(R.string.mdetail_entry_reminder),
                        subtitle = buildReminderSummary(uiState),
                        // ⚠️ 没有计划时高亮。理由见 buildReminderSummary：
                        // 这一行不能长得和"已配置"一样，否则用户以为设好了而药永远不响。
                        highlight = uiState.policy == null,
                        testTag = TestTags.DETAIL_ROW_REMINDER,
                        onClick = { onNavigateToReminder(med.id) }
                    )
                    DetailEntryRow(
                        icon = Icons.Default.Inventory2,
                        title = stringResource(R.string.mdetail_entry_inventory),
                        subtitle = buildInventorySummary(uiState),
                        highlight = uiState.isStockAlert && med.isStockTracked,
                        testTag = TestTags.DETAIL_ROW_INVENTORY,
                        onClick = { onNavigateToInventory(med.id) }
                    )
                }
            }

            // ---------- 3. 暂停提醒 ----------
            //
            // `FINAL-PRODUCT` M-02 要求「暂停至某日」，而 `reminder_settings.paused_until`
            // 有三态（null 未暂停 / "" 无限期 / 日期）。所以这里不能是一个开关：
            // 已暂停时按钮直接恢复；未暂停时弹出对话框让用户选暂停多久。
            // 若只做开关，`paused_until` 就是一个"存了却没有写路径"的列。
            item {
                OutlinedButton(
                    onClick = {
                        if (isPaused) viewModel.resumeReminder() else showPauseDialog = true
                    },
                    enabled = !uiState.isSaving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (isPaused) stringResource(R.string.mdetail_resume_reminder) else stringResource(R.string.mdetail_pause_reminder))
                }
            }

            // ---------- 4. 依从率 ----------
            item {
                AdherenceCard(
                    rate = uiState.adherenceRate,
                    completed = uiState.adherenceCompleted,
                    decided = uiState.adherenceDecided,
                    doseSum = uiState.doseSum,
                    unit = med.unit
                )
            }

            // ---------- 5. 说明与医嘱 ----------
            if (med.description.isNotBlank()) {
                item {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                stringResource(R.string.mdetail_section_description),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = med.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 20.sp
                            )
                        }
                    }
                }
            }

            // ---------- 6. 注意事项 ----------
            if (med.precautions.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = WarningAmberContainer)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.WarningAmber,
                                    contentDescription = null,
                                    tint = WarningAmber
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    stringResource(R.string.mdetail_section_precautions),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = OnWarningAmberContainer
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                med.precautions.forEach { tag ->
                                    // 注意事项是纯展示信息，不能用 AssistChip：
                                    // 它有 ripple 和按压态，点了却什么都不发生（L1）。
                                    // 静态标签用非可交互的 Surface 承载（同屏 TagChip 同款写法）。
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surface
                                    ) {
                                        Text(
                                            text = tag,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ---------- 7. 服药历史 ----------
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.mdetail_section_history),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(10.dp))
                        if (uiState.recentRecords.isEmpty()) {
                            Text(
                                stringResource(R.string.mdetail_history_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 18.sp
                            )
                        } else {
                            val shown = if (showAllRecords) {
                                uiState.recentRecords
                            } else {
                                uiState.recentRecords.take(5)
                            }
                            val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                            shown.forEach { r ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 5.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            fmt.format(Date(r.actualTs)),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        if (!r.note.isNullOrBlank()) {
                                            Text(
                                                r.note,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            "${Quantity.fmt(Dose(r.doseTaken).asFloat)} ${med.unit}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        RecordStatusChip(r.status, r.isRetrospective)
                                    }
                                }
                            }
                            if (uiState.recentRecords.size > 5) {
                                TextButton(
                                    onClick = { showAllRecords = !showAllRecords },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(if (showAllRecords) stringResource(R.string.mdetail_history_collapse) else stringResource(R.string.mdetail_history_show_all, uiState.recentRecords.size))
                                }
                            }
                        }
                    }
                }
            }

            // ---------- 8. 库存流水摘要 (完整流水去库存管理页) ----------
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.mdetail_section_transactions),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(10.dp))
                        if (uiState.transactions.isEmpty()) {
                            Text(
                                stringResource(R.string.mdetail_transactions_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                            uiState.transactions.take(3).forEach { tx ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "${fmt.format(Date(tx.createdAt))} · ${txLabel(tx.txType)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        (if (tx.changeAmount > 0) "+${Quantity.fmt(Dose(tx.changeAmount).asFloat)}" else Quantity.fmt(Dose(tx.changeAmount).asFloat)) +
                                            " ${med.unit}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (tx.changeAmount > 0) SuccessGreen
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                            TextButton(
                                onClick = { onNavigateToInventory(med.id) },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(stringResource(R.string.mdetail_go_inventory_full)) }
                        }
                    }
                }
            }

            // ---------- 9. 危险操作 ----------
            item {
                if (med.isArchived) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showArchiveDialog = true },
                            enabled = !uiState.isSaving,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.mdetail_resume_active))
                        }
                        Button(
                            onClick = { showDeleteDialog = true },
                            enabled = !uiState.isSaving,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.mdetail_delete_forever), color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = { showArchiveDialog = true },
                        enabled = !uiState.isSaving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.mdetail_archive))
                    }
                }
            }
        }
    }

    if (showPauseDialog && med != null) {
        // 暂停时长三选一，对应 paused_until 的三种非空取值。
        // 不给"默认 30 分钟"之类的隐式档位 —— 暂停是对医嘱的临时覆盖，
        // 猜一个时长比让用户明确选更危险。
        AlertDialog(
            onDismissRequest = { showPauseDialog = false },
            title = { Text(stringResource(R.string.mdetail_pause_dialog_title), fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    // ⚠️ `paused_until` 的语义是「暂停**含**这一天」
                    // （`ReminderSettingsEntity.isPausedOn` 用 `!end.isBefore(today)`）。
                    //
                    // 旧实现「暂停到明天」传 `now() + 1`，于是今天与明天**两天**都被压掉，
                    // 而徽标按同一条规则算成「2 天后恢复」—— 按钮写着"明天"、
                    // 徽标写着"2 天后"，**点完立刻自相矛盾**（M3-3）。
                    //
                    // 正确写法：用户说"暂停到明天"意思是"今天别叫我了"，
                    // 所以 `paused_until = 今天`（含今天 ⇒ 今天静音），明天自动恢复。
                    // 同理「暂停到一周后」= 静默 7 天 ⇒ `now() + 6`。
                    //
                    // 三个地方必须同一条规则：[isPausedOn]（投影）、
                    // [ReminderSettingsEntity.daysUntilResume]（徽标）、这里的按钮。
                    PauseOptionRow(stringResource(R.string.mdetail_pause_until_tomorrow)) {
                        showPauseDialog = false
                        viewModel.pauseReminderUntil(LocalDate.now())
                    }
                    PauseOptionRow(stringResource(R.string.mdetail_pause_until_week)) {
                        showPauseDialog = false
                        viewModel.pauseReminderUntil(LocalDate.now().plusDays(6))
                    }
                    PauseOptionRow(stringResource(R.string.mdetail_pause_until_date)) {
                        showPauseDialog = false
                        val cal = Calendar.getInstance()
                        DatePickerDialog(
                            context,
                            { _, y, m, d ->
                                viewModel.pauseReminderUntil(
                                    LocalDate.of(y, m + 1, d)
                                )
                            },
                            cal.get(Calendar.YEAR),
                            cal.get(Calendar.MONTH),
                            cal.get(Calendar.DAY_OF_MONTH)
                        ).apply {
                            // 不允许选过去的日期 —— 选过去等于「立即暂停但永远不到期」
                            datePicker.minDate = System.currentTimeMillis() - 1000
                        }.show()
                    }
                    PauseOptionRow(stringResource(R.string.mdetail_pause_indefinite)) {
                        showPauseDialog = false
                        viewModel.pauseReminderUntil(null)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.mdetail_pause_dialog_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showPauseDialog = false }) { Text(stringResource(R.string.mdetail_cancel)) } }
        )
    }

    if (showArchiveDialog && med != null) {
        AlertDialog(
            onDismissRequest = { showArchiveDialog = false },
            title = { Text(if (med.isArchived) stringResource(R.string.mdetail_resume_active) else stringResource(R.string.mdetail_archive), fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    if (med.isArchived) stringResource(R.string.mdetail_archive_confirm_resume)
                    else stringResource(R.string.mdetail_archive_confirm_archive)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showArchiveDialog = false
                    viewModel.toggleArchive()
                }) { Text(stringResource(R.string.mdetail_confirm), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { showArchiveDialog = false }) { Text(stringResource(R.string.mdetail_cancel)) } }
        )
    }

    if (showDeleteDialog && med != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.mdetail_delete_forever), fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    stringResource(R.string.mdetail_delete_confirm, med.name)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.deleteMedication(onNavigateBack)
                }) { Text(stringResource(R.string.mdetail_delete_forever), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.mdetail_cancel)) } }
        )
    }
}

// ---------------- 摘要构造 ----------------

@Composable
private fun buildProfileSummary(s: MedDetailUiState): String {
    val med = s.medication ?: return ""
    val parts = mutableListOf<String>()
    med.description.takeIf { it.isNotBlank() }?.let { parts += stringResource(R.string.mdetail_sum_has_description) }
    if (med.precautions.isNotEmpty()) parts += stringResource(R.string.mdetail_sum_precautions, med.precautions.size)
    if (med.expiryDate.isNotBlank()) parts += stringResource(R.string.mdetail_sum_expiry, med.expiryDate)
    if (med.noticeShort.isNotBlank()) parts += stringResource(R.string.mdetail_sum_notice_short_set)
    // form 存的是稳定 key，摘要里也要给本地化显示名；未知 key 原样回显。
    val formText = MedVocab.formRes(med.form)?.let { stringResource(it) } ?: med.form
    return if (parts.isEmpty()) {
        stringResource(
            R.string.mdetail_sum_defaults,
            formText, med.unit, Quantity.fmt(Dose(med.defaultDose).asFloat), med.unit
        )
    } else "$formText · ${med.unit} · $parts"
}

@Composable
private fun buildReminderSummary(s: MedDetailUiState): String {
    // ⚠️ 没有计划时**必须显眼地**说出来（2026-09-29 UX 改造）。
    //
    // 新建药品页不再强制配提醒（用户可以从这里点进「提醒设置」自己配），
    // 于是"没配计划"从一个**正常状态**变成了**新建后的默认状态**。
    // 如果这一行长得跟已配置的一样，用户会以为"设好了"，
    // 然后这味药永远不响 —— 而他完全不知道原因。
    //
    // 这与本项目 §2 第 6 条「静默降级 = 给用户虚假的保证」同源：
    // 诚实的空缺好过虚假的完成感。
    if (s.policy == null) {
        return stringResource(R.string.mdetail_sum_no_plan)
    }
    val policy = s.policy ?: return ""
    val freq = when (policy.policyType) {
        PolicyType.DAILY -> stringResource(R.string.mdetail_sum_freq_daily, s.times.size)
        PolicyType.INTERVAL ->
            // §一-7：口径归一到 intervalLabel（n<=1 每天 / n==2 隔天 / 其余 每 n 天）。
            // 旧实现「n <= 2 隔天，否则 每隔 n-1 天」把 intervalDays==1（每天）
            // 标成了「隔天」，与库存页/药箱页相反。
            intervalLabel(LocalContext.current, policy.intervalDays)
        PolicyType.DAYS_OF_WEEK -> stringResource(R.string.mdetail_sum_freq_weekly, policy.daysOfWeek.size)
        PolicyType.CYCLE ->
            stringResource(R.string.mdetail_sum_freq_cycle, policy.cycleOnDays, policy.cycleOffDays)
        PolicyType.PRN -> stringResource(R.string.mdetail_sum_freq_prn)
    }
    val times = if (s.times.isEmpty()) stringResource(R.string.mdetail_sum_no_fixed_times)
    else s.times.joinToString("、") { it.timeOfDay }
    val course = if (policy.endDate != null) stringResource(R.string.mdetail_sum_until, policy.endDate)
    else stringResource(R.string.mdetail_sum_indefinite)
    val flags = buildList {
        if (s.reminderSettings.isCriticalReminder) add(stringResource(R.string.mdetail_sum_flag_critical))
        // §二-24：snoozeMinutes==0 是「跟随全局」哨兵而不是「未设置」——
        // 通知实际按全局值生效（ReminderSettings.resolve：药品级 > 全局 > 默认），
        // 此前这里不显示任何推迟信息，用户看到的推迟能力与真实行为不一致。
        add(
            if (s.reminderSettings.snoozeMinutes > 0) {
                stringResource(R.string.mdetail_sum_flag_snooze, s.reminderSettings.snoozeMinutes)
            } else {
                stringResource(R.string.mdetail_sum_flag_snooze_global, s.globalSnoozeMinutes)
            }
        )
        if (s.reminderSettings.advanceMinutes > 0) add(stringResource(R.string.mdetail_sum_flag_advance, s.reminderSettings.advanceMinutes))
    }
    val flagText = if (flags.isEmpty()) "" else " · ${flags.joinToString("/")}"
    return "$freq · $times · $course$flagText"
}

@Composable
private fun buildInventorySummary(s: MedDetailUiState): String {
    val med = s.medication ?: return ""
    if (!med.isStockTracked) return stringResource(R.string.mdetail_sum_stock_off)
    // 账面为负说明账实不符（已吃的超过记录库存），文案要如实说明而不是显示"剩余 -3"
    val balance = s.stock
    val stock = if (balance < 0f) {
        stringResource(R.string.mdetail_sum_stock_negative, Quantity.fmt(balance), med.unit)
    } else {
        stringResource(R.string.mdetail_sum_stock_remaining, Quantity.fmt(balance), med.unit)
    }
    val runway = when {
        StatsEngine.isRunwayUnlimited(s.runwayDays) -> ""
        // 负天数 = 已超支（orsbf P1-4），不渲染成"约可用 -2 天"
        s.runwayDays < 0 -> stringResource(R.string.mdetail_sum_runway_overspent)
        else -> stringResource(R.string.mdetail_sum_runway, s.runwayDays)
    }
    return stock + runway
}

@Composable
private fun txLabel(t: TransactionType): String = when (t) {
    TransactionType.TAKEN_DEDUCT -> stringResource(R.string.mdetail_tx_taken)
    TransactionType.REFILL -> stringResource(R.string.mdetail_tx_refill)
    TransactionType.REVERT_ROLLBACK -> stringResource(R.string.mdetail_tx_revert)
    TransactionType.CALIBRATION_ADJUST -> stringResource(R.string.mdetail_tx_calibrate)
    TransactionType.DOSE_EDIT_ADJUST -> stringResource(R.string.mdetail_tx_dose_edit)
}

@Composable
private fun DetailEntryRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    highlight: Boolean = false,
    testTag: String? = null
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (highlight) WarningAmberContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .clickable(onClick = onClick)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(
                        if (highlight) WarningAmber.copy(alpha = 0.18f)
                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (highlight) WarningAmber else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (highlight) OnWarningAmberContainer else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (highlight) OnWarningAmberContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 17.sp,
                    maxLines = 2
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun AdherenceCard(rate: Float, completed: Int, decided: Int, doseSum: Float, unit: String) {
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
                Text(stringResource(R.string.mdetail_adherence_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                // 分母为 0 时显示"暂无到期"：0/0 在统计上等于 100%，
                // 但对用户是误导 —— 看起来像"表现完美"，实际是"还没有样本"
                if (decided > 0) {
                    Text(
                        stringResource(R.string.mdetail_adherence_rate, rate * 100),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (rate >= 0.8f) SuccessGreen else WarningAmber
                    )
                } else {
                    Text(
                        stringResource(R.string.mdetail_adherence_none),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.mdetail_adherence_summary, completed, decided, Quantity.fmt(doseSum), unit),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun TagChip(text: String) {
    Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RecordStatusChip(status: RecordStatus, isRetrospective: Boolean) {
    val (text, color) = when (status) {
        // ⭐ REVERTED 必须有自己的分支（M7-2）。
        //
        // 旧实现的 `when` 只有 `SKIPPED` 与 `else => "已服"`，
        // 于是用户**撤销**的打卡（事实行仍在，只是标了 REVERTED）
        // 在「服药历史」里显示成绿色的"已服" ——
        // 而同屏上方的消耗统计**已经把它剔除了**（`sumDoseByDate` 只算 COMPLETED）。
        //
        // 同一屏的两块数据自相矛盾：上面说没消耗，下面说已服。
        // 用户对"撤销"这件事毫无概念（这是补偿，不是删除），于是他只能理解成
        // "系统算错了" —— 而实际上是他自己刚点的撤销没生效。
        //
        // `REVERTED` 显示为灰色"已撤销"，与 `SKIPPED` 同族（都不是有效服药）。
        // 灰色走 onSurfaceVariant（对白底 7.4:1）而不是 outline（1.23:1，接近隐形）。
        RecordStatus.REVERTED -> stringResource(R.string.mdetail_status_reverted) to MaterialTheme.colorScheme.onSurfaceVariant
        RecordStatus.SKIPPED -> stringResource(R.string.mdetail_status_skipped) to MaterialTheme.colorScheme.onSurfaceVariant
        RecordStatus.COMPLETED ->
            if (isRetrospective) stringResource(R.string.mdetail_status_retro) to MaterialTheme.colorScheme.tertiary
            else stringResource(R.string.mdetail_status_taken) to SuccessGreen
    }
    Surface(shape = RoundedCornerShape(6.dp), color = color.copy(alpha = 0.14f)) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold
        )
    }
}

/** 暂停对话框里的一行可选项。 */
@Composable
private fun PauseOptionRow(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 10.dp, horizontal = 4.dp)
    ) {
        Text(label, modifier = Modifier.fillMaxWidth(), fontWeight = FontWeight.Medium)
    }
}
