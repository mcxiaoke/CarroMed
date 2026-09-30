package com.mcxiaoke.carromed.ui.screen.cabinet
import com.mcxiaoke.carromed.core.domain.CurrentDateHolder
import com.mcxiaoke.carromed.core.domain.engine.StatsEngine
import com.mcxiaoke.carromed.core.domain.model.PauseStatus

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcxiaoke.carromed.R
import com.mcxiaoke.carromed.ui.component.CarroMedTopAppBar
import com.mcxiaoke.carromed.ui.component.MedVocab
import com.mcxiaoke.carromed.ui.component.Quantity
import com.mcxiaoke.carromed.ui.component.TestTags
import java.time.LocalDate
import com.mcxiaoke.carromed.ui.theme.OnWarningAmberContainer
import com.mcxiaoke.carromed.ui.theme.WarningAmberContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CabinetScreen(
    viewModel: CabinetViewModel,
    onNavigateToAddMedication: () -> Unit,
    onNavigateToMedDetail: (Long) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            CarroMedTopAppBar(
                title = stringResource(R.string.cabinet_title),
                actionIcon = Icons.Default.Add,
                actionContentDescription = stringResource(R.string.cabinet_add_medication),
                onActionClick = onNavigateToAddMedication
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
            // 1. 搜索 + 排序
            item {
                // ⚠️ `rememberSaveable`（M7-6）。写在 `LazyColumn` 的 `item {}` 内的
                // `remember` 会随 item 滚出视口而销毁 —— 药品列表很长时这个 item
                // 很容易被回收，用户点开排序菜单、往下滑两屏再回来，**菜单自己合上了**。
                var sortExpanded by rememberSaveable { mutableStateOf(false) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = uiState.keyword,
                        onValueChange = { viewModel.setKeyword(it) },
                        placeholder = { Text(stringResource(R.string.cabinet_search_placeholder), fontSize = 13.sp) },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.cabinet_cd_search), modifier = Modifier.size(18.dp))
                        },
                        trailingIcon = {
                            if (uiState.keyword.isNotBlank()) {
                                IconButton(onClick = { viewModel.setKeyword("") }) {
                                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cabinet_cd_clear), modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    )

                    Box {
                        IconButton(
                            onClick = { sortExpanded = true },
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Icon(Icons.Default.Sort, contentDescription = stringResource(R.string.cabinet_cd_sort), modifier = Modifier.size(20.dp))
                        }
                        DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                            CabinetSortOrder.entries.forEach { order ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(order.labelRes),
                                            fontWeight = if (order == uiState.sortOrder) FontWeight.Bold
                                            else FontWeight.Normal
                                        )
                                    },
                                    onClick = {
                                        viewModel.setSortOrder(order)
                                        sortExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // 3. Tab 分段胶囊选择器
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val tabs = listOf(
                        stringResource(R.string.cabinet_tab_active, uiState.activeList.size),
                        stringResource(R.string.cabinet_tab_archived, uiState.archivedList.size)
                    )
                    tabs.forEachIndexed { index, title ->
                        val selected = uiState.selectedTab == index
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.surface
                                    else Color.Transparent
                                )
                                .clickable { viewModel.selectTab(index) }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 3. 操作指引提示
            item {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lightbulb,
                            contentDescription = stringResource(R.string.cabinet_cd_hint),
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.cabinet_hint_text),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 4. 药品卡片列表
            val displayList = if (uiState.selectedTab == 0) uiState.filteredActive else uiState.filteredArchived

            if (uiState.isLoading) {
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(40.dp),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }
                }
            } else if (displayList.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            val isSearchMiss = uiState.keyword.isNotBlank()
                            Text(
                                text = when {
                                    isSearchMiss && uiState.selectedTab == 0 ->
                                        stringResource(R.string.cabinet_empty_search_active, uiState.keyword)
                                    isSearchMiss -> stringResource(R.string.cabinet_empty_search_archived)
                                    uiState.selectedTab == 0 && uiState.activeList.isEmpty() ->
                                        stringResource(R.string.cabinet_empty_active)
                                    else -> stringResource(R.string.cabinet_empty_archived)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            if (!isSearchMiss && uiState.selectedTab == 0 && uiState.activeList.isEmpty()) {
                                Spacer(modifier = Modifier.height(14.dp))
                                Button(
                                    onClick = onNavigateToAddMedication,
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(stringResource(R.string.cabinet_add_medication))
                                }
                            }
                        }
                    }
                }
            } else {
                items(displayList, key = { it.medication.id }) { item ->
                    CabinetMedCard(
                        item = item,
                        onClick = { onNavigateToMedDetail(item.medication.id) }
                    )
                }
            }
        }
    }
}

@Composable
fun CabinetMedCard(
    item: MedicationItemUi,
    onClick: () -> Unit
) {
    val med = item.medication
    val stock = item.stock
    // 暂停徽标是**墙上时钟**驱动的：跨过恢复日而进程仍活着时，
    // 它必须自动消失（药其实早已恢复提醒）。直接调 `LocalDate.now()` 能拿到新值，
    // 但**不会**触发重组 —— 那正是 M3-4 这个 bug 的原形。
    val today by CurrentDateHolder.today.collectAsStateWithLifecycle(LocalDate.now())
    val medColor = med.colorHex.let {
        runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull()
    } ?: MaterialTheme.colorScheme.primary

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.medCard(med.id))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧药点标记
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(medColor)
            )

            Spacer(modifier = Modifier.width(14.dp))

            // 药品名称与频次时段
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = med.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            // 类别存的是稳定 key（B3 key 化），显示时映射成本地化名；
                            // 未知 key（自由文本/旧数据）原样回显。
                            text = MedVocab.categoryRes(med.category)?.let { stringResource(it) } ?: med.category,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.frequencyDescription,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 暂停说明由 ReminderSettingsEntity 统一生成（含"N 天后恢复"），
                // 不要在 UI 里重写一遍日期比较 —— 两处实现必然漂移。
                //
                // ⚠️ "今天"取 [CurrentDateHolder.today]（M3-4）：它是墙上时钟驱动的，
                // 跨过恢复日而进程仍活着时，`LocalDate.now()` 之外的任何缓存都会
                // 让徽标停留在「已暂停，N 天后恢复」，而药其实早已恢复提醒。
                val pauseText = when (val ps = item.overview.pauseStatus(today)) {
                    PauseStatus.NotPaused -> null
                    PauseStatus.PausedIndefinitely -> stringResource(R.string.common_pause_paused)
                    is PauseStatus.PausedWithResume ->
                        if (ps.days <= 1) stringResource(R.string.common_pause_resume_tomorrow)
                        else stringResource(R.string.common_pause_resume_days, ps.days)
                }
                if (pauseText != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        Text(
                            text = pauseText,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }

            // 右侧库存胶囊徽章与箭头
            if (med.isStockTracked) {
                // ⚠️ 必须用 MedicationOverview 的 Float 代理，不能直接读实体的
                // `medication.minStockAlert`（那是整数毫单位）。
                // 直接比较会让 50 片 <= 15000 恒成立，导致**每个药都误报低库存**。
                val alert = item.overview.minStockAlert
                // 判据走 StatsEngine 的唯一实现（M4-1）。本页原先自己写
                // `stock <= alert && alert > 0f`，而补药页写的是 `stock <= alert` ——
                // 五页各写一份，差异就在这些"看起来一样"的地方。
                val isLow = StatsEngine.isLowStock(med.isStockTracked, stock, alert)
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (isLow) WarningAmberContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    val stockText = Quantity.fmt(stock)
                    Text(
                        text = if (isLow) stringResource(R.string.cabinet_stock_low, stockText, med.unit)
                        else stringResource(R.string.cabinet_stock_normal, stockText, med.unit),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isLow) FontWeight.Bold else FontWeight.Normal,
                        color = if (isLow) OnWarningAmberContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.cabinet_cd_view_detail),
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}
