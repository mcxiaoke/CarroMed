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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcxiaoke.carromed.R
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import com.mcxiaoke.carromed.ui.component.CarroMedTopAppBar
import com.mcxiaoke.carromed.ui.component.MedVocab
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
        // 顶栏必须走 Scaffold 的 topBar 槽位（PLAN-TITLEBAR-STANDARDIZATION-20260930.md）：
        // 旧实现是本 Scaffold 不带 topBar、标题手绘在 LazyColumn 第一个 item 里，
        // 于是 Scaffold 按「没有顶栏」把状态栏算进 innerPadding.top，
        // 页面又自加 .statusBarsPadding() —— 状态栏被计两次，标题偏下 63px。
        // 进了 topBar 槽位后 TopAppBar 自己吃掉状态栏，innerPadding.top 归零。
        topBar = {
            CarroMedTopAppBar(
                title = stringResource(R.string.today_title)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNavigateToManualDose,
                icon = { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.today_cd_manual_log)) },
                text = { Text(stringResource(R.string.today_manual_log), fontWeight = FontWeight.Bold) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text(
                    text = uiState.selectedDate.format(
                        DateTimeFormatter.ofPattern(
                            stringResource(R.string.today_date_pattern),
                            Locale.CHINESE
                        )
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                DateSelectorRow(
                    dates = uiState.weekDates,
                    selectedDate = uiState.selectedDate,
                    today = uiState.today,
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
                                    contentDescription = stringResource(R.string.today_cd_stock_alert),
                                    tint = WarningAmber
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = if (isOverspent) {
                                        stringResource(
                                            R.string.today_stock_overspent,
                                            med.name, Quantity.fmt(stock), unit
                                        )
                                    } else {
                                        stringResource(
                                            R.string.today_stock_low,
                                            med.name, Quantity.fmt(stock), unit, Quantity.fmt(alert)
                                        )
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
                                    text = if (isOverspent) {
                                        stringResource(R.string.today_go_inventory)
                                    } else {
                                        stringResource(R.string.today_go_refill)
                                    },
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
                        text = stringResource(R.string.today_pending_title, uiState.pendingItems.size),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    if (uiState.pendingItems.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            // 未来日是**只读预览**：如实说明"为什么没有 ✓"，
                            // 而不是让用户以为卡片坏了 / App 卡了
                            text = if (uiState.isActionable) {
                                stringResource(R.string.today_pending_hint_actionable)
                            } else {
                                stringResource(R.string.today_pending_hint_future)
                            },
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
                                    text = if (uiState.selectedDate == uiState.today) {
                                        stringResource(R.string.today_empty_all_done)
                                    } else {
                                        stringResource(R.string.today_empty_no_schedule)
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
                        isActionable = uiState.isActionable,
                        today = uiState.today,
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
                            // 翻到 9-30 时写"今日已服"是**事实错误**：那天的记录不是今天服的。
                            // 与"今日清单"这个页名无关 —— 它标的是当前查看的那一天。
                            text = if (uiState.selectedDate == uiState.today) {
                                stringResource(R.string.today_completed_title, uiState.completedItems.size)
                            } else {
                                stringResource(
                                    R.string.today_completed_title_date,
                                    uiState.selectedDate.monthValue,
                                    uiState.selectedDate.dayOfMonth,
                                    uiState.completedItems.size
                                )
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.today_completed_hint),
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
                        text = stringResource(R.string.today_skipped_section, uiState.skippedItems.size),
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
    isActionable: Boolean,
    today: LocalDate,
    onTakeDose: () -> Unit,
    onClick: () -> Unit
) {
    val med = item.medication
    val unit = med?.unit ?: stringResource(R.string.today_default_unit)
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
                        text = med?.name ?: stringResource(R.string.today_unknown_medication),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    med?.category?.takeIf { it.isNotBlank() }?.let { cat ->
                        Spacer(Modifier.width(8.dp))
                        Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Text(
                                // key → 本地化显示名；未知 key（自由文本/旧数据）原样回显。
                                text = MedVocab.categoryRes(cat)?.let { stringResource(it) } ?: cat,
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
                        contentDescription = stringResource(R.string.today_cd_scheduled_time),
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(
                            R.string.today_dose_time_amount,
                            item.slot.scheduledTime,
                            Quantity.fmt(Dose(item.slot.doseAmount).asFloat),
                            unit
                        ),
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
                                stringResource(R.string.today_stock_overspent_inline, Quantity.fmt(stock), unit)
                            } else {
                                stringResource(R.string.today_stock_low_inline, Quantity.fmt(stock), unit)
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
                            text = stringResource(R.string.today_snoozed_until, formatSnooze(item.slot.snoozeUntilTs)),
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }

                    SlotStatus.EXPIRED -> {
                        Spacer(Modifier.height(4.dp))
                        StatusBadge(
                            icon = Icons.Default.WarningAmber,
                            text = stringResource(
                                R.string.today_expired_unconfirmed,
                                Quantity.fmt(Dose(item.slot.doseAmount).asFloat),
                                unit
                            ),
                            color = WarningAmber
                        )
                    }

                    else -> Unit
                }
            }

            // 未来槽位：**不渲染 ✓**，换一句只读说明。
            //
            // 按本项目既有约定**不置灰**（`DoseRecordDetailViewModel` 的 KDoc 记了理由）：
            // 置灰会让人以为"再等等就能用"，而这条槽位今天永远不会变可用 ——
            // 它要等到自己那天。卡片本体仍可点开，进详情页预览。
            if (isActionable) {
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
                        contentDescription = stringResource(R.string.today_cd_confirm_dose),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            } else {
                // 两行而不是一行滚动的长句：单行「明天 10:30 服用」在 44dp 的
                // 右侧位宽里必然折行，而折行点落在"服用"两个字上时看起来像个孤儿
                // （2026-09-30 走查截图实测）。这里显式分两行：日期一行、时点一行。
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier
                        .width(84.dp)
                        .testTag(TestTags.doseFuture(item.slot.id))
                ) {
                    Text(
                        text = futureDayLabel(item.slot.scheduledDate, today),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.today_future_take_at, item.slot.scheduledTime),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End
                    )
                }
            }
        }
    }
}

/**
 * 未来槽位的日期说明：「明天」/「9月30日」。
 *
 * 计划日串安全解析：库里理论上总是规范 `yyyy-MM-dd`，但这段代码会被渲染
 * **每一条**未来卡片调用，一旦 `LocalDate.parse` 抛异常就是整页崩溃。
 * 解析失败时原样回显计划日，绝不抛。
 */
@Composable
private fun futureDayLabel(scheduledDate: String, today: LocalDate): String {
    val date = runCatching { LocalDate.parse(scheduledDate) }.getOrNull() ?: return scheduledDate
    return if (date == today.plusDays(1)) stringResource(R.string.today_future_tomorrow)
    else stringResource(R.string.today_date_md, date.monthValue, date.dayOfMonth)
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
                text = stringResource(R.string.today_first_run_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.today_first_run_body),
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
                Text(stringResource(R.string.today_first_run_cta))
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
                    contentDescription = stringResource(R.string.today_cd_completed),
                    tint = SuccessGreen,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = med?.name ?: stringResource(R.string.today_fallback_medication),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.width(6.dp))
                    Surface(shape = RoundedCornerShape(4.dp), color = SuccessGreen.copy(alpha = 0.15f)) {
                        Text(
                            stringResource(R.string.today_badge_taken),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = SuccessGreen,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                val recNote = item.record?.let { r ->
                    MedVocab.recordNoteDisplay(LocalContext.current, r.noteKey, r.note)
                }
                Text(
                    text = buildString {
                        append(stringResource(R.string.today_completed_at, item.slot.scheduledTime))
                        item.record?.let { r ->
                            append(
                                stringResource(
                                    R.string.today_completed_dose_part,
                                    Quantity.fmt(Dose(r.doseTaken).asFloat),
                                    med?.unit ?: stringResource(R.string.today_default_unit)
                                )
                            )
                            if (recNote != null) {
                                append(stringResource(R.string.today_completed_note_part, recNote))
                            }
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
                contentDescription = stringResource(R.string.today_cd_skipped),
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        R.string.today_skipped_card_title,
                        med?.name ?: stringResource(R.string.today_fallback_medication),
                        item.slot.scheduledTime
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.today_skipped_detail),
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
    today: LocalDate,
    onSelectDate: (LocalDate) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        dates.forEach { date ->
            val isSelected = date == selectedDate
            val isToday = date == today
            val dayOfWeekChinese = when (date.dayOfWeek.value) {
                1 -> stringResource(R.string.today_weekday_1)
                2 -> stringResource(R.string.today_weekday_2)
                3 -> stringResource(R.string.today_weekday_3)
                4 -> stringResource(R.string.today_weekday_4)
                5 -> stringResource(R.string.today_weekday_5)
                6 -> stringResource(R.string.today_weekday_6)
                else -> stringResource(R.string.today_weekday_7)
            }
            // stringResource 只能在组合期调用，而 semantics 块不是 composable 上下文，
            // 所以描述串先在这里（组合期）算好，再放进 semantics。
            val dateDescription = buildString {
                append(
                    stringResource(
                        R.string.today_cd_date,
                        date.monthValue, date.dayOfMonth, dayOfWeekChinese
                    )
                )
                if (isToday) append(stringResource(R.string.today_cd_today_suffix))
                if (date > today) append(stringResource(R.string.today_cd_future_suffix))
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
                    // 语义描述（可访问性 + 走查脚本的定位锚点）：
                    // 未来日**仍然可点**（预览排班是产品功能），所以刻意不写"已禁用"——
                    // 只如实标注它是未来。日期格没有稳定文本（只有一个"30"这种数字），
                    // uiautomator 按文本定位会命中一堆无关节点。
                    .semantics {
                        contentDescription = dateDescription
                    }
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
