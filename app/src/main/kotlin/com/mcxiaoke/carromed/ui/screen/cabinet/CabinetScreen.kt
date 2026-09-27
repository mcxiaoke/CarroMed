package com.mcxiaoke.carromed.ui.screen.cabinet

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
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcxiaoke.carromed.ui.component.HomeTabHeader
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

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. 顶部 Header (与其他主 Tab 统一规格)
        item {
            HomeTabHeader(
                title = "我的药箱",
                actionIcon = Icons.Default.Add,
                actionContentDescription = "添加药品",
                onActionClick = onNavigateToAddMedication
            )
        }

        // 2. 搜索 + 排序
        item {
            var sortExpanded by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = uiState.keyword,
                    onValueChange = { viewModel.setKeyword(it) },
                    placeholder = { Text("搜索药名 / 别名 / 类别", fontSize = 13.sp) },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = "搜索", modifier = Modifier.size(18.dp))
                    },
                    trailingIcon = {
                        if (uiState.keyword.isNotBlank()) {
                            IconButton(onClick = { viewModel.setKeyword("") }) {
                                Icon(Icons.Default.Close, contentDescription = "清除", modifier = Modifier.size(16.dp))
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
                        Icon(Icons.Default.Sort, contentDescription = "排序", modifier = Modifier.size(20.dp))
                    }
                    DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                        CabinetSortOrder.entries.forEach { order ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        order.label,
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
                    "正在服用 (${uiState.activeList.size})",
                    "已停药归档 (${uiState.archivedList.size})"
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
                        contentDescription = "提示",
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "点击药品进入详情页，药品信息 / 提醒设置 / 库存管理三者分开管理",
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
                                isSearchMiss && uiState.selectedTab == 0 -> "没有匹配「${uiState.keyword}」的在服药品"
                                isSearchMiss -> "归档中没有匹配的药品"
                                uiState.selectedTab == 0 && uiState.activeList.isEmpty() ->
                                    "药箱还是空的\n点右上角「+」添加第一个药品"
                                else -> "暂无归档停药记录"
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
                                Text("添加药品")
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

@Composable
fun CabinetMedCard(
    item: MedicationItemUi,
    onClick: () -> Unit
) {
    val med = item.medication
    val medColor = med.colorHex.let {
        runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull()
    } ?: MaterialTheme.colorScheme.primary

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
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
                            text = med.category,
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
                if (med.isPaused) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        Text(
                            text = "提醒已暂停",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }

            // 右侧库存胶囊徽章与箭头
            if (med.isStockTracked) {
                val isLow = med.currentStock <= med.minStockAlert && med.minStockAlert > 0f
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (isLow) WarningAmberContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    val stockText = if (med.currentStock % 1f == 0f) {
                        med.currentStock.toInt().toString()
                    } else {
                        String.format(java.util.Locale.getDefault(), "%.2f", med.currentStock)
                    }
                    Text(
                        text = if (isLow) "⚠️ 剩 $stockText ${med.unit}" else "剩 $stockText ${med.unit}",
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
                contentDescription = "查看详情",
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}
