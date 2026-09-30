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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcxiaoke.carromed.R
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

    /**
     * 「注意事项」是否展开。
     *
     * ⚠️ `rememberSaveable` 而不是 `remember`（与 M7-6 同一纪律）：
     * 这一段写在 `LazyColumn` 的 `item {}` 内，item 滚出视口会被销毁。
     * 用 `remember` 的话，用户展开后往下滑两屏再回来，
     * 折叠状态会**自己合上**——他刚展开的东西凭空消失。
     *
     * 已经有内容时默认展开：折叠是为了"别占版面"，
     * 而**已经填过的用户是来找这些内容的**，不该再让他多点一次。
     */
    var precautionsExpanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(uiState.precautions) {
        if (uiState.precautions.isNotEmpty() && !precautionsExpanded) precautionsExpanded = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.title, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) {
                        Text(stringResource(R.string.medit_cancel), style = MaterialTheme.typography.bodyLarge)
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
                            Text(stringResource(R.string.medit_save), fontWeight = FontWeight.Bold)
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
                            text = stringResource(R.string.medit_error_prefix, uiState.error ?: ""),
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
                    title = if (uiState.isEdit) stringResource(R.string.medit_section_medication_info)
                    else stringResource(R.string.medit_section_basic_info),
                    required = true
                ) {
                    OutlinedTextField(
                        value = uiState.name,
                        onValueChange = { viewModel.onNameChange(it) },
                        label = { Text(stringResource(R.string.medit_label_med_name)) },
                        placeholder = { Text(stringResource(R.string.medit_placeholder_med_name)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = uiState.alias,
                        onValueChange = { viewModel.onAliasChange(it) },
                        label = { Text(stringResource(R.string.medit_label_alias)) },
                        placeholder = { Text(stringResource(R.string.medit_placeholder_alias)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(Modifier.height(12.dp))

                    Row(Modifier.fillMaxWidth()) {
                        OptionDropdown(
                            label = stringResource(R.string.medit_label_category),
                            value = uiState.category,
                            options = MedicationFormOptions.CATEGORIES,
                            onSelect = { viewModel.onCategoryChange(it) },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(10.dp))
                        OptionDropdown(
                            label = stringResource(R.string.medit_label_form),
                            value = uiState.form,
                            options = MedicationFormOptions.FORMS,
                            onSelect = { viewModel.onFormChange(it) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OptionDropdown(
                            label = stringResource(R.string.medit_label_unit),
                            value = uiState.unit,
                            options = MedicationFormOptions.UNITS,
                            onSelect = { viewModel.onUnitChange(it) },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(10.dp))
                        OutlinedTextField(
                            value = uiState.defaultDose,
                            onValueChange = { viewModel.onDefaultDoseChange(it) },
                            label = { Text(stringResource(R.string.medit_label_default_dose)) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    Text(
                        text = stringResource(R.string.medit_label_color),
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

            // ============ 2. 初始库存 (选填) ============
            //
            // 放在"注意事项"**之前**：库存是常用操作（买药顺手记一下），
            // 注意事项是低频且可选的。段位也因此连号 1→2→3，
            // 中间跳过 2 会让人以为漏了一屏。
            if (!isInfoOnly) {
                item { InitialStockCard(viewModel, uiState) }
            }

            // ============ 3. 注意事项与医嘱 (默认折叠) ============
            //
            // ⚠️ 折叠而非删除（2026-09-29 UX 改造）：注意事项对慢病用药是**安全相关**的
            // （"整粒吞服禁嚼碎"、"严禁与葡萄柚同食"），删掉是错的。
            // 但它是**低频**操作，而它原来占掉整页一大半 ——
            // 用户为了一句话得滚过十几行点选区。
            //
            // 折叠时显示"已填 N 条"，这样已经填过的用户仍能一眼看到自己填了东西，
            // 不会以为折叠把内容弄丢了。
            item {
                val filledCount = uiState.precautions.size
                val expanded = precautionsExpanded
                SectionCard(
                    index = 3,
                    title = stringResource(R.string.medit_section_precautions),
                    trailing = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            if (filledCount > 0) {
                                Text(
                                    text = stringResource(R.string.medit_precautions_filled_count, filledCount),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(2.dp))
                            }
                            Icon(
                                imageVector = if (expanded) Icons.Default.ExpandLess
                                else Icons.Default.ExpandMore,
                                contentDescription = if (expanded) stringResource(R.string.medit_cd_collapse_precautions)
                                else stringResource(R.string.medit_cd_expand_precautions),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onHeaderClick = { precautionsExpanded = !expanded }
                ) {
                    if (!expanded) {
                        Text(
                            text = if (filledCount > 0) stringResource(R.string.medit_precautions_collapsed_hint)
                            else stringResource(R.string.medit_precautions_empty_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        return@SectionCard
                    }
                    Text(
                        text = stringResource(R.string.medit_label_common_tags),
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
                                                contentDescription = stringResource(R.string.medit_cd_remove),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    )
                                }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // ⚠️ `rememberSaveable` 而不是 `remember`（M7-6）。
                    //
                    // 这一行写在 `LazyColumn` 的 `item { }` **内部** ——
                    // 而 `item` 的内容是**滚出视口就被销毁、滚回来就重建**的。
                    // `remember` 的存储跟着组合走，于是用户往下滚一点再滚回来，
                    // 敲了一半的「自定义注意事项」**凭空消失**。
                    //
                    // 而且它消失得很隐蔽：用户已经看到自己打的字，
                    // 滚一下就没了，只能理解成"App 把我的输入吃了"。
                    //
                    // `rememberSaveable` 额外扛得住配置变更（旋转屏幕）与
                    // 进程被系统回收后的重建 —— 后者对"新建药品"表单尤其致命：
                    // 填了 5 分钟的表单，回收一次全没。
                    var customTag by rememberSaveable { mutableStateOf("") }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = customTag,
                            onValueChange = { customTag = it },
                            label = { Text(stringResource(R.string.medit_label_custom_precaution)) },
                            placeholder = { Text(stringResource(R.string.medit_placeholder_custom_precaution)) },
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
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.medit_cd_add_tag))
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = uiState.description,
                        onValueChange = { viewModel.onDescriptionChange(it) },
                        label = { Text(stringResource(R.string.medit_label_description)) },
                        placeholder = { Text(stringResource(R.string.medit_placeholder_description)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3
                    )

                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = uiState.noticeShort,
                        onValueChange = { viewModel.onNoticeShortChange(it) },
                        label = { Text(stringResource(R.string.medit_label_notice_short)) },
                        placeholder = { Text(stringResource(R.string.medit_placeholder_notice_short)) },
                        supportingText = { Text(stringResource(R.string.medit_hint_notice_short)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(Modifier.height(12.dp))

                    ReadOnlyDateField(
                        label = stringResource(R.string.medit_label_expiry_date),
                        dateStr = uiState.expiryDate,
                        onDateChange = { viewModel.onExpiryDateChange(it) },
                        onClear = { viewModel.onExpiryDateChange("") }
                    )
                }
            }

            // ============ 提醒计划 (已移出新建页) ============
            //
            // ⚠️ 2026-09-29 UX 改造：**新建页不再要求配提醒**。
            //
            // 旧实现把「药品信息 + 提醒计划 + 初始库存」合成一次录入，
            // 而用户的真实心智是"我今天要吃这个药"，不是"我要配置一个排班系统"。
            // 三件事互相独立，合成一屏的后果是：想改个药名也得滚过一整套闹钟计划。
            //
            // 现在：建完药**直接进详情页**，用户从已有的「提醒设置」入口自己配。
            // 这与 `ReminderSettingsScreen` 早就独立存在的事实一致 ——
            // 之前只是入口被塞在了新建表单里。
            //
            // 配套的诚实提示在药品详情页：`getActivePolicyForMedication == null`
            // 时提醒卡片会显式写「尚未设置服药计划」，
            // 而不是让它长得和已配置的一样（见 MedicationDetailScreen）。
            if (isInfoOnly) {
                item {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                    ) {
                        Text(
                            text = stringResource(R.string.medit_info_only_hint),
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
            // ⚠️ 2026-09-29：这句原先写"只需填写【药品名称】并选择【提醒频次/时间】"，
            // 而提醒段已经整个移出这一页。留着不改就是**当场说谎** ——
            // 用户照着这句话找"提醒频次"，找不到，而文案还在让他找。
            // 同理，"保存后进入详情页设置提醒"必须写出来，否则用户以为存完就完事了，
            // 然后这味药永远不响而他毫不知情（AGENTS §2 第 6 条）。
            text = stringResource(R.string.medit_low_friction_tip),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            lineHeight = 18.sp
        )
    }
}

@Composable
/**
 * 表单分节卡。
 *
 * @param trailing 标题右侧的附加内容（折叠箭头、"已填 N 条"之类）。
 * @param onHeaderClick 标题行的点击回调。**只给标题行加点击**，
 *   不给整卡加 —— 否则内容区的输入框会被整卡的点击抢走焦点。
 */
private fun SectionCard(
    index: Int,
    title: String,
    required: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onHeaderClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = if (onHeaderClick != null) {
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onHeaderClick)
                } else {
                    Modifier.fillMaxWidth()
                },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$index. $title" + if (required) " *" else "",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                trailing?.invoke()
            }
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
    // ⚠️ `rememberSaveable`（M7-6）。下拉的展开态写在 `LazyColumn` 的 `item {}` 内，
    // 而 `item` 滚出视口就被销毁、滚回来重建 ⇒ `remember` 跟着组合走，
    // 下拉会**自己合上**。用户刚点开、往下滑一点再回来，发现菜单没了。
    // `rememberSaveable` 由 `SaveableStateHolder` 按 item key 保管，滚出去再回来仍在。
    var expanded by rememberSaveable { mutableStateOf(false) }
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
    placeholder: String = stringResource(R.string.medit_date_not_set),
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
                            contentDescription = stringResource(R.string.medit_cd_clear_field, label),
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
@Composable
private fun InitialStockCard(
    viewModel: AddEditMedicationViewModel,
    uiState: AddEditUiState
) {
    SectionCard(index = 2, title = stringResource(R.string.medit_section_initial_stock)) {
        Text(
            text = stringResource(R.string.medit_initial_stock_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 18.sp
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = uiState.currentStock,
            onValueChange = { viewModel.onCurrentStockChange(it) },
            label = { Text(stringResource(R.string.medit_label_current_stock, uiState.unit)) },
            placeholder = { Text(stringResource(R.string.medit_placeholder_current_stock)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
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
                Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.medit_cd_decrease), modifier = Modifier.size(18.dp))
            }
            Text(
                text = "$value",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(30.dp),
                textAlign = TextAlign.Center
            )
            IconButton(onClick = onIncrement, enabled = canIncrement) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.medit_cd_increase), modifier = Modifier.size(18.dp))
            }
        }
    }
}
