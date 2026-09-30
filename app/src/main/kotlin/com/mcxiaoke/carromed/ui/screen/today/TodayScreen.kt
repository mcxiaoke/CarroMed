package com.mcxiaoke.carromed.ui.screen.today
import com.mcxiaoke.carromed.core.domain.model.Dose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import com.mcxiaoke.carromed.ui.component.HomeTabHeader
import com.mcxiaoke.carromed.ui.component.Quantity
import com.mcxiaoke.carromed.ui.component.TestTags
import com.mcxiaoke.carromed.ui.theme.OnWarningAmberContainer
import com.mcxiaoke.carromed.ui.theme.SuccessGreen
import com.mcxiaoke.carromed.ui.theme.WarningAmber
import com.mcxiaoke.carromed.ui.theme.WarningAmberContainer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 今日清单
 *
 * 交互：**任意一条 item 点开都进记录详情页**（待服 / 已服 / 已跳过 共用一个页面），
 * 推迟、跳过、撤销、改判这些低频操作全在那里。列表上只留一个高频动作 ——
 * 待服卡右侧的 ✓ 快捷打卡。
 *
 * 此前列表卡上直接挂着「撤销」按钮、长按还会弹一个操作 sheet：那等于把同一批操作
 * 在两处各实现一遍，而两者必然会漂移（sheet 里连「确认」都没有）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    viewModel: TodayViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToAddMedication: () -> Unit,
    onNavigateToManualDose: () -> Unit,
    onNavigateToRefill: (Long) -> Unit,
    /** 账面为负时引导去库存管理页做盘点校准（而不是补药） */
    onNavigateToInventory: (Long) -> Unit,
    /** 点开任意一条 item 都进记录详情页（待服 / 已服 / 已跳过 同一个页面） */
    onOpenDose: (Long) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 一次性提示：VM 的 6 处失败分支全靠这条通道上报。
    //
    // 旧实现**全工程零 collect** —— 事件被 `trySend` 进缓冲区后无人消费，
    // 于是"打卡失败""无法推迟"这类提示**永远静默**：卡片不动、没红字、
    // 也没 toast，用户只能理解为"App 卡了"。哑渠道比没有渠道更糟，
    // 因为它让失败与"成功但界面没刷新"变得不可区分。
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.events.collect { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNavigateToManualDose,
                icon = { Icon(Icons.Default.Add, contentDescription = "补录") },
                text = { Text("手动补录", fontWeight = FontWeight.Bold) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .statusBarsPadding()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                HomeTabHeader(
                    title = "今日清单",
                    actionIcon = Icons.Outlined.Settings,
                    actionContentDescription = "系统设置",
                    onActionClick = onNavigateToSettings
                )
            }

            item {
                Text(
                    text = uiState.selectedDate.format(
                        DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", Locale.CHINESE)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                DateSelectorRow(
                    dates = uiState.weekDates,
                    selectedDate = uiState.selectedDate,
                    onSelectDate = { viewModel.selectDate(it) }
                )
            }

            // 低库存告警：显示全部告急药品，不再只显示第一个
            if (uiState.lowStockAlertMeds.isNotEmpty()) {
                // ⚠️ key 必须带类型前缀，不能直接用药品 id。
                // 本 LazyColumn 里同时挂着 `lowStockAlertMeds`（药品）与
                // `pendingItems` / `completedItems` / `skippedItems`（槽位），
                // 而 medications.id 与 dose_slots.id 是**两条独立的自增序列**，
                // 首条记录都是 1。直接用裸 id 会撞号，Compose 在渲染到第二个
                // 冲突项时抛 `IllegalArgumentException: Key "1" was already used`
                // 直接崩掉 —— 而且只在**滚动到该项时**才崩，第一屏看起来完全正常。
                items(uiState.lowStockAlertMeds, key = { "lowstock-${it.id}" }) { overview ->
                    val med = overview.medication
                    val unit = med.unit
                    val stock = overview.stock
                    val alert = overview.minStockAlert   // 展示值，不是毫单位
                    // 账面为负说明账实不符（已吃的比记录的库存还多），这比"快没药了"更严重，
                    // 文案与配色都要区分开，并引导去盘点。见 FINAL-PRODUCT D-9。
                    val isOverspent = stock < 0f
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = WarningAmberContainer),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    Icons.Default.WarningAmber,
                                    contentDescription = "库存告警",
                                    tint = WarningAmber
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = if (isOverspent) {
                                        "${med.name} 账面 ${Quantity.fmt(stock)} $unit，已超出记录库存，请盘点校准"
                                    } else {
                                        "${med.name} 仅剩 ${Quantity.fmt(stock)} $unit" +
                                            " (低于警戒线 ${Quantity.fmt(alert)})"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = OnWarningAmberContainer
                                )
                            }
                            TextButton(
                                onClick = {
                                    if (isOverspent) onNavigateToInventory(med.id) else onNavigateToRefill(med.id)
                                },
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(
                                    text = if (isOverspent) "去盘点 >" else "去补药 >",
                                    fontWeight = FontWeight.Bold,
                                    color = WarningAmber
                                )
                            }
                        }
                    }
                }
            }

            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text(
                        text = "待服药 (${uiState.pendingItems.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    if (uiState.pendingItems.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "点开可推迟或跳过",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (uiState.isLoading) {
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }
                }
            } else if (uiState.pendingItems.isEmpty()) {
                item {
                    // 全新用户（药箱为空）与"这一天恰好没排班"是两回事，
                    // 必须区分开：前者要引导去添加药品，后者才是真的完成了。
                    if (!uiState.hasAnyMedication) {
                        FirstRunGuideCard(onNavigateToAddMedication = onNavigateToAddMedication)
                    } else {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (uiState.selectedDate == LocalDate.now()) {
                                        "这一天没有待服任务 🎉"
                                    } else {
                                        "这一天没有排班"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            } else {
                items(uiState.pendingItems, key = { "slot-${it.slot.id}" }) { item ->
                    PendingDoseCard(
                        item = item,
                        onTakeDose = { viewModel.takeDose(item.slot.id) },
                        onClick = { onOpenDose(item.slot.id) }
                    )
                }
            }

            if (uiState.completedItems.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "今日已服 (${uiState.completedItems.size})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "点开可撤销或改判",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                items(uiState.completedItems, key = { "slot-${it.slot.id}" }) { item ->
                    CompletedDoseCard(item = item, onClick = { onOpenDose(item.slot.id) })
                }
            }

            if (uiState.skippedItems.isNotEmpty()) {
                item {
                    Text(
                        text = "已跳过 (${uiState.skippedItems.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                items(uiState.skippedItems, key = { "slot-${it.slot.id}" }) { item ->
                    SkippedDoseCard(item = item, onClick = { onOpenDose(item.slot.id) })
                }
            }
        }
    }

}

@Composable
private fun PendingDoseCard(
    item: DoseSlotItem,
    onTakeDose: () -> Unit,
    onClick: () -> Unit
) {
    val med = item.medication
    val unit = med?.unit ?: "片"
    val medColor = med?.colorHex?.let {
        runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull()
    } ?: MaterialTheme.colorScheme.primary

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.doseCard(item.slot.id))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(medColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(medColor)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = med?.name ?: "未知药品",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    med?.category?.takeIf { it.isNotBlank() }?.let { cat ->
                        Spacer(Modifier.width(8.dp))
                        Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Text(
                                text = cat,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Schedule,
                        contentDescription = "计划时间",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "${item.slot.scheduledTime} · ${Quantity.fmt(Dose(item.slot.doseAmount).asFloat)} $unit",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (med?.isStockTracked == true && item.stock != null) {
                        Spacer(Modifier.width(6.dp))
                        val stock = item.stock
                        val alert = item.minStockAlert   // 展示值，不是毫单位
                        val isOverspent = stock < 0f
                        val isLow = isOverspent || (alert > 0f && stock <= alert)
                        Text(
                            text = if (isOverspent) {
                                "· 账面 ${Quantity.fmt(stock)} $unit (待盘点)"
                            } else {
                                "· 剩 ${Quantity.fmt(stock)} $unit"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isLow) FontWeight.Bold else FontWeight.Normal,
                            color = if (isLow) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 状态徽标：已推迟 / 已逾期
                when (item.slot.status) {
                    SlotStatus.SNOOZED -> {
                        Spacer(Modifier.height(4.dp))
                        StatusBadge(
                            icon = Icons.Default.Alarm,
                            text = "已推迟至 ${formatSnooze(item.slot.snoozeUntilTs)}",
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }

                    SlotStatus.EXPIRED -> {
                        Spacer(Modifier.height(4.dp))
                        StatusBadge(
                            icon = Icons.Default.WarningAmber,
                            text = "已逾期 ${Quantity.fmt(Dose(item.slot.doseAmount).asFloat)} $unit，尚未确认",
                            color = WarningAmber
                        )
                    }

                    else -> Unit
                }
            }

            IconButton(
                onClick = onTakeDose,
                modifier = Modifier
                    .size(42.dp)
                    .testTag(TestTags.doseConfirm(item.slot.id))
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                    .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), CircleShape)
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "确认服药",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** 首启引导卡：药箱为空时给出明确的下一步，而不是干瘪一句"今天没有待服任务" */
@Composable
private fun FirstRunGuideCard(onNavigateToAddMedication: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Medication,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = "药箱还是空的",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "添加第一个药品后，系统会按你设定的时点自动排班并准时提醒；" +
                    "所有数据只存在本机，不联网、不上传。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 18.sp
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onNavigateToAddMedication,
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("添加第一个药品")
            }
        }
    }
}

@Composable
private fun StatusBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    color: Color
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CompletedDoseCard(
    item: DoseSlotItem,
    onClick: () -> Unit
) {
    val med = item.medication
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(SuccessGreen.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "已完成",
                    tint = SuccessGreen,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = med?.name ?: "药品",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.width(6.dp))
                    Surface(shape = RoundedCornerShape(4.dp), color = SuccessGreen.copy(alpha = 0.15f)) {
                        Text(
                            "已服",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = SuccessGreen,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = buildString {
                        append("${item.slot.scheduledTime} 完成")
                        item.record?.let { r ->
                            append(" · ${Quantity.fmt(Dose(r.doseTaken).asFloat)} ${med?.unit ?: "片"}")
                            if (!r.note.isNullOrBlank()) append(" · ${r.note}")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // ⚠️ 这里**不再**放「撤销」按钮（2026-09-29）：低频操作统一收进记录详情页，
            // 列表卡上放操作按钮等于把同一批动作实现第二遍，两处必然会漂移。
        }
    }
}

/** 已跳过卡片：单独成区，语义不再与「已服」混在一起 */
@Composable
private fun SkippedDoseCard(
    item: DoseSlotItem,
    onClick: () -> Unit
) {
    val med = item.medication
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "已跳过",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "${med?.name ?: "药品"} · ${item.slot.scheduledTime}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "已主动跳过 · 未扣减库存",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
private fun DateSelectorRow(
    dates: List<LocalDate>,
    selectedDate: LocalDate,
    onSelectDate: (LocalDate) -> Unit
) {
    val today = LocalDate.now()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        dates.forEach { date ->
            val isSelected = date == selectedDate
            val isToday = date == today
            val dayOfWeekChinese = when (date.dayOfWeek.value) {
                1 -> "一"; 2 -> "二"; 3 -> "三"; 4 -> "四"; 5 -> "五"; 6 -> "六"; else -> "日"
            }

            Column(
                modifier = Modifier
                    .width(44.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    )
                    .clickable { onSelectDate(date) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = dayOfWeekChinese,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isSelected -> MaterialTheme.colorScheme.onPrimary
                                isToday -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.outlineVariant
                            }
                        )
                )
            }
        }
    }
}

private fun formatSnooze(ts: Long?): String {
    if (ts == null) return "--:--"
    val dt = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault())
    return String.format(Locale.getDefault(), "%02d:%02d", dt.hour, dt.minute)
}

// 数量格式化已统一到 com.mcxiaoke.carromed.ui.component.Quantity（消除全库四套写法）。
