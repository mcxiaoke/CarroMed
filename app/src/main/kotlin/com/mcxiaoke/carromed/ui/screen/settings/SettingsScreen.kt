package com.mcxiaoke.carromed.ui.screen.settings

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import com.mcxiaoke.carromed.R
import com.mcxiaoke.carromed.core.data.DataExporter
import com.mcxiaoke.carromed.core.domain.AppLog
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToPermissionCheck: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 诊断日志导出用（PLAN-LOGGING S4）：Screen 层直连 DataExporter，
    // 不经 ViewModel —— 避免给构造器加参数（§2 坑 5）
    val logExportScope = rememberCoroutineScope()
    val logExportContext = LocalContext.current

    // 选择 JSON 备份文件 → **只解析不恢复**，产出预览后弹二次确认（P1-14）
    val backupPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.inspectBackup(it) }
    }

    // 覆盖式恢复二次确认。内容必须带**真实数字**：
    // 「确定要覆盖吗？」用户只能盲点确定；
    // 「含 4 种药品、2 条服药记录，当前数据将被完全替换」才是决策依据。
    val pending = uiState.pendingRestore
    if (pending != null) {
        val preview = pending.first
        AlertDialog(
            onDismissRequest = { viewModel.cancelRestore() },
            title = { Text(stringResource(R.string.set_restore_confirm_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.set_restore_selected_file, preview.fileName))
                    Text(stringResource(R.string.set_restore_exported_at, preview.exportedAtText))
                    Text(
                        stringResource(
                            R.string.set_restore_contains,
                            preview.medicationCount,
                            preview.recordCount
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.set_restore_warning),
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        stringResource(R.string.set_restore_snapshot_note),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (preview.warnings.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        val warnContext = LocalContext.current
                        val warnSep = stringResource(R.string.csv_error_list_separator)
                        Text(
                            // 取 [BackupProblem.message]（用 Context 解析资源 + 参数）：这些条目
                            // 是结构化类型，直接 joinToString 会输出 `BackupProblem(kind=…, …)`。
                            // 能进到这里的都是**不拦住恢复**的提示（[BackupProblemKind.blocksRestore]），
                            // 所以措辞用"注意"而不是"错误"——它们不妨碍恢复。
                            stringResource(
                                R.string.set_restore_warnings,
                                preview.warnings.joinToString(warnSep) { it.message(warnContext) }
                            ),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.confirmRestore() },
                    enabled = !uiState.isRestoring
                ) {
                    Text(stringResource(R.string.set_restore_confirm_button), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelRestore() }) { Text(stringResource(R.string.set_cancel)) }
            }
        )
    }

    // 本机导出记录列表。**必须有这个入口** —— 备份写在
    // /sdcard/Android/data/<pkg>/...，SAF 明确不允许浏览该目录，
    // 所以「选择备份」永远选不到 App 自己刚生成的那份。
    if (uiState.showLocalBackupPicker) {
        AlertDialog(
            onDismissRequest = { viewModel.closeLocalBackupPicker() },
            title = { Text(stringResource(R.string.set_local_backup_picker_title)) },
            text = {
                if (uiState.localBackups.isEmpty()) {
                    Text(stringResource(R.string.set_local_backup_empty))
                } else {
                    Column {
                        uiState.localBackups.forEach { b ->
                            TextButton(
                                onClick = { viewModel.inspectLocalBackup(b.file) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(b.displayName, fontSize = 13.sp)
                                    Text(
                                        "${b.sizeKb} KB",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.closeLocalBackupPicker() }) { Text(stringResource(R.string.set_close)) }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.set_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.set_back_cd))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. 通知与提醒行为
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Notifications,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.set_section_notification),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // 默认推迟时长
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.set_snooze_title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                Text(
                                    stringResource(R.string.set_snooze_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            val snoozeOptions = listOf(5, 10, 15, 30, 60, 120)
                            // rememberSaveable：与 AddEdit / Cabinet / ReminderSettings 的
                            // 下拉展开态同一纪律 —— 进详情再返回不该丢失展开状态
                            var snoozeExpanded by rememberSaveable { mutableStateOf(false) }

                            // 下拉只能表示这 6 个档位，而库里可以是任意值
                            // （药品级步进写到 25、或从备份导入 app_settings 带来 90）。
                            // 旧实现直接把当前值塞进只读 TextField，于是 90 分钟这一档
                            // **显示得出来却选不回去** —— 用户改了别的设置再回来，
                            // 就再也回不到 90，只能被静默改成别的值。
                            // 兜底补一个「自定义」项，保证"显示什么就能选回什么"。
                            val hasCustom = uiState.snoozeMinutes !in snoozeOptions

                            ExposedDropdownMenuBox(
                                expanded = snoozeExpanded,
                                onExpandedChange = { snoozeExpanded = it },
                                modifier = Modifier.width(140.dp)
                            ) {
                                OutlinedTextField(
                                    value = stringResource(R.string.set_snooze_minutes_fmt, uiState.snoozeMinutes),
                                    onValueChange = {},
                                    readOnly = true,
                                    singleLine = true,
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = snoozeExpanded) },
                                    modifier = Modifier.menuAnchor()
                                )
                                ExposedDropdownMenu(
                                    expanded = snoozeExpanded,
                                    onDismissRequest = { snoozeExpanded = false }
                                ) {
                                    if (hasCustom) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.set_snooze_minutes_custom, uiState.snoozeMinutes)) },
                                            onClick = { snoozeExpanded = false }
                                        )
                                    }
                                    snoozeOptions.forEach { mins ->
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.set_snooze_minutes_fmt, mins)) },
                                            onClick = {
                                                viewModel.onSnoozeMinutesChange(mins)
                                                snoozeExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // 夜间免打扰 (静音)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.set_night_dnd_title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                Text(
                                    stringResource(R.string.set_night_dnd_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = uiState.nightDnd,
                                onCheckedChange = { viewModel.onNightDndChange(it) }
                            )
                        }
                    }
                }
            }

            // 2. 提醒防漏与系统保活
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.set_section_keepalive),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.set_permission_check_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = onNavigateToPermissionCheck,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(stringResource(R.string.set_permission_check_button), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // 3. 数据管理与本地导出
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Save,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.set_section_data),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.set_export_csv_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text(stringResource(R.string.set_export_csv_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            OutlinedButton(
                                onClick = { viewModel.exportCsv() },
                                enabled = !uiState.isExporting,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(stringResource(R.string.set_export_csv_button), fontSize = 12.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.set_export_backup_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text(stringResource(R.string.set_export_backup_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            OutlinedButton(
                                onClick = { viewModel.exportBackup() },
                                enabled = !uiState.isExporting,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(stringResource(R.string.set_create_backup_button), fontSize = 12.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.set_restore_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text(stringResource(R.string.set_restore_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            OutlinedButton(
                                onClick = { backupPickerLauncher.launch(arrayOf("application/json")) },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(stringResource(R.string.set_pick_file_button), fontSize = 12.sp)
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.set_local_restore_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    stringResource(R.string.set_local_restore_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            OutlinedButton(
                                onClick = { viewModel.openLocalBackupPicker() },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(stringResource(R.string.set_local_backup_button), fontSize = 12.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // 诊断日志导出（PLAN-LOGGING S4）：排查"没提醒/账不对"时
                        // 让用户一键把最近日志发给开发者。刻意放在 Screen 层直连
                        // DataExporter，不走 ViewModel——避免给构造器加参数（坑 5）。
                        // shareFile 的 chooser 标题与 Toast 在非 composable 回调里，
                        // 无法直接 stringResource —— 在 composable 作用域先取好。
                        val shareLogTitle = stringResource(R.string.set_share_log_title)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.set_export_log_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text(stringResource(R.string.set_export_log_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            OutlinedButton(
                                onClick = {
                                    logExportScope.launch {
                                        try {
                                            val file = DataExporter.exportDiagnosticLogs(logExportContext)
                                            if (file != null) {
                                                DataExporter.shareFile(logExportContext, file, "text/plain", shareLogTitle)
                                            } else {
                                                Toast.makeText(logExportContext, logExportContext.getString(R.string.set_no_log_files), Toast.LENGTH_SHORT).show()
                                            }
                                        } catch (e: Exception) {
                                            AppLog.w("SettingsScreen", "export diagnostic logs failed", e)
                                            Toast.makeText(logExportContext, logExportContext.getString(R.string.set_export_failed, e.message), Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(stringResource(R.string.set_export_log_button), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // 4. 版本号
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.set_version_format, com.mcxiaoke.carromed.BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
