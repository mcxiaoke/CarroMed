package com.mcxiaoke.carromed.ui.screen.detail
import com.mcxiaoke.carromed.core.domain.CurrentDateHolder
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcxiaoke.carromed.core.domain.model.Dose
import com.mcxiaoke.carromed.core.data.model.PolicyType
import com.mcxiaoke.carromed.core.data.model.RecordStatus
import com.mcxiaoke.carromed.core.data.model.TransactionType
import com.mcxiaoke.carromed.ui.component.Quantity
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
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("药品详情", fontWeight = FontWeight.Bold) },
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
        if (med == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                if (uiState.isLoading) CircularProgressIndicator()
                else Text(uiState.error ?: "正在加载药品信息...")
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
        val pauseText = uiState.reminderSettings.pauseDescription(today)
        val isPaused = pauseText != null
        val statusText = when {
            med.isArchived -> "已停药归档"
            isPaused -> pauseText!!
            isPrn -> "按需服用 · 无定时提醒"
            else -> "提醒进行中"
        }
        val statusColor = when {
            med.isArchived -> MaterialTheme.colorScheme.outline
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
                                TagChip(med.category)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "${med.form} · ${med.unit}",
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
                    text = "药品设置",
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
                        title = "药品信息",
                        subtitle = buildProfileSummary(uiState),
                        onClick = { onNavigateToEditInfo(med.id) }
                    )
                    DetailEntryRow(
                        icon = Icons.Default.Schedule,
                        title = "提醒设置",
                        subtitle = buildReminderSummary(uiState),
                        onClick = { onNavigateToReminder(med.id) }
                    )
                    DetailEntryRow(
                        icon = Icons.Default.Inventory2,
                        title = "库存管理",
                        subtitle = buildInventorySummary(uiState),
                        highlight = uiState.isStockAlert && med.isStockTracked,
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (isPaused) "恢复提醒" else "暂停提醒")
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
                                "详细说明 / 医嘱",
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
                                    "注意事项与禁忌",
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
                                    AssistChip(
                                        onClick = {},
                                        label = { Text(tag, fontWeight = FontWeight.Medium) },
                                        colors = AssistChipDefaults.assistChipColors(
                                            containerColor = MaterialTheme.colorScheme.surface,
                                            labelColor = MaterialTheme.colorScheme.onSurface
                                        )
                                    )
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
                            "服药历史",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(10.dp))
                        if (uiState.recentRecords.isEmpty()) {
                            Text(
                                "还没有服药记录。完成打卡或用「手动补录」记录后会显示在这里。",
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
                                    Text(if (showAllRecords) "收起" else "查看全部 ${uiState.recentRecords.size} 条")
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
                            "库存流水",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(10.dp))
                        if (uiState.transactions.isEmpty()) {
                            Text(
                                "暂无出入库记录",
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
                            ) { Text("前往库存管理查看全部流水") }
                        }
                    }
                }
            }

            // ---------- 9. 危险操作 ----------
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { showArchiveDialog = true },
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (med.isArchived) "恢复在服" else "停药归档")
                    }
                    Button(
                        onClick = { showDeleteDialog = true },
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
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
                        Text("删除药品", color = MaterialTheme.colorScheme.onErrorContainer)
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
            title = { Text("暂停提醒到什么时候？", fontWeight = FontWeight.Bold) },
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
                    PauseOptionRow("暂停到明天") {
                        showPauseDialog = false
                        viewModel.pauseReminderUntil(LocalDate.now())
                    }
                    PauseOptionRow("暂停到一周后") {
                        showPauseDialog = false
                        viewModel.pauseReminderUntil(LocalDate.now().plusDays(6))
                    }
                    PauseOptionRow("暂停到指定日期") {
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
                    PauseOptionRow("无限期暂停（需手动恢复）") {
                        showPauseDialog = false
                        viewModel.pauseReminderUntil(null)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "暂停期间不排闹钟；到期后自动恢复，历史服药记录与库存流水完整保留。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showPauseDialog = false }) { Text("取消") } }
        )
    }

    if (showArchiveDialog && med != null) {
        AlertDialog(
            onDismissRequest = { showArchiveDialog = false },
            title = { Text(if (med.isArchived) "恢复在服" else "停药归档", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    if (med.isArchived) "确认恢复为在服状态？其提醒计划将重新生效。"
                    else "确认停药归档？未来提醒会被取消，历史打卡与库存流水完整保留，可随时恢复。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showArchiveDialog = false
                    viewModel.toggleArchive()
                }) { Text("确认", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { showArchiveDialog = false }) { Text("取消") } }
        )
    }

    if (showDeleteDialog && med != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除药品", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "确认删除「${med.name}」？其全部排班槽位、打卡记录与库存流水将一并删除，且不可恢复。\n\n" +
                        "如只是不再服用，建议使用「停药归档」保留历史数据。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.deleteMedication(onNavigateBack)
                }) { Text("永久删除", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("取消") } }
        )
    }
}

// ---------------- 摘要构造 ----------------

private fun buildProfileSummary(s: MedDetailUiState): String {
    val med = s.medication ?: return ""
    val parts = mutableListOf<String>()
    med.description.takeIf { it.isNotBlank() }?.let { parts += "含医嘱说明" }
    if (med.precautions.isNotEmpty()) parts += "${med.precautions.size} 条注意事项"
    if (med.expiryDate.isNotBlank()) parts += "效期 ${med.expiryDate}"
    if (med.noticeShort.isNotBlank()) parts += "通知简述已设"
    return if (parts.isEmpty()) "${med.form} · ${med.unit} · 默认 ${Quantity.fmt(Dose(med.defaultDose).asFloat)} ${med.unit}/次"
    else "${med.form} · ${med.unit} · $parts"
}

private fun buildReminderSummary(s: MedDetailUiState): String {
    val policy = s.policy ?: return "未设置提醒计划 · 不会自动提醒"
    val freq = when (policy.policyType) {
        PolicyType.DAILY -> "每天 ${s.times.size} 次"
        PolicyType.INTERVAL -> if (policy.intervalDays <= 2) "隔天" else "每隔 ${policy.intervalDays - 1} 天"
        PolicyType.DAYS_OF_WEEK -> "每周 ${policy.daysOfWeek.size} 天"
        PolicyType.CYCLE -> "周期 ${policy.cycleOnDays}服/${policy.cycleOffDays}停"
        PolicyType.PRN -> "按需服用"
    }
    val times = if (s.times.isEmpty()) "无固定时点" else s.times.joinToString("、") { it.timeOfDay }
    val course = if (policy.endDate != null) "至 ${policy.endDate}" else "无限期"
    val flags = buildList {
        if (s.reminderSettings.isCriticalReminder) add("重要提醒")
        if (s.reminderSettings.snoozeMinutes > 0) add("推迟 ${s.reminderSettings.snoozeMinutes} 分")
        if (s.reminderSettings.advanceMinutes > 0) add("提前 ${s.reminderSettings.advanceMinutes} 分")
    }
    val flagText = if (flags.isEmpty()) "" else " · ${flags.joinToString("/")}"
    return "$freq · $times · $course$flagText"
}

private fun buildInventorySummary(s: MedDetailUiState): String {
    val med = s.medication ?: return ""
    if (!med.isStockTracked) return "未开启库存追踪"
    // 账面为负说明账实不符（已吃的超过记录库存），文案要如实说明而不是显示"剩余 -3"
    val balance = s.stock
    val stock = if (balance < 0f) {
        "账面 ${Quantity.fmt(balance)} ${med.unit}，已超出记录库存，请盘点校准"
    } else {
        "${Quantity.fmt(balance)} ${med.unit} 剩余"
    }
    val runway = if (StatsEngine.isRunwayUnlimited(s.runwayDays)) "" else " · 约可用 ${s.runwayDays} 天"
    return stock + runway
}

private fun txLabel(t: TransactionType): String = when (t) {
    TransactionType.TAKEN_DEDUCT -> "服药扣减"
    TransactionType.REFILL -> "购药入库"
    TransactionType.REVERT_ROLLBACK -> "撤销冲正"
    TransactionType.CALIBRATION_ADJUST -> "盘点调整"
}

@Composable
private fun DetailEntryRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    highlight: Boolean = false
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (highlight) WarningAmberContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
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
                Text("近 30 天用药", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                // 分母为 0 时显示"暂无到期"：0/0 在统计上等于 100%，
                // 但对用户是误导 —— 看起来像"表现完美"，实际是"还没有样本"
                if (decided > 0) {
                    Text(
                        String.format(Locale.getDefault(), "依从率 %.0f%%", rate * 100),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (rate >= 0.8f) SuccessGreen else WarningAmber
                    )
                } else {
                    Text(
                        "暂无到期",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "按时服药 $completed 次 / 已到期 $decided 次 · 近 30 天共消耗 ${Quantity.fmt(doseSum)} $unit",
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
        RecordStatus.REVERTED -> "已撤销" to MaterialTheme.colorScheme.outline
        RecordStatus.SKIPPED -> "已跳过" to MaterialTheme.colorScheme.outline
        RecordStatus.COMPLETED ->
            if (isRetrospective) "补录" to MaterialTheme.colorScheme.tertiary
            else "已服" to SuccessGreen
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
