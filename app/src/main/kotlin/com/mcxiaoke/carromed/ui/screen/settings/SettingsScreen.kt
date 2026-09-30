package com.mcxiaoke.carromed.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
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
            title = { Text("确认覆盖当前数据？") },
            text = {
                Column {
                    Text("所选文件：${preview.fileName}")
                    Text("生成时间：${preview.exportedAtText}")
                    Text("包含 ${preview.medicationCount} 种药品、${preview.recordCount} 条服药记录")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "当前数据将被完全替换，此操作不可撤销。",
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        "覆盖前会自动保存一份当前数据的快照。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (preview.warnings.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            // 取 [BackupProblem.message]：这些条目是结构化类型，
                            // 直接 joinToString 会输出 `BackupProblem(kind=…, message=…)`。
                            // 能进到这里的都是**不拦住恢复**的提示（[BackupProblemKind.blocksRestore]），
                            // 所以措辞用"注意"而不是"错误"——它们不妨碍恢复。
                            "注意：${preview.warnings.joinToString("；") { it.message }}",
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
                    Text("确认覆盖", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelRestore() }) { Text("取消") }
            }
        )
    }

    // 本机导出记录列表。**必须有这个入口** —— 备份写在
    // /sdcard/Android/data/<pkg>/...，SAF 明确不允许浏览该目录，
    // 所以「选择备份」永远选不到 App 自己刚生成的那份。
    if (uiState.showLocalBackupPicker) {
        AlertDialog(
            onDismissRequest = { viewModel.closeLocalBackupPicker() },
            title = { Text("选择本机备份") },
            text = {
                if (uiState.localBackups.isEmpty()) {
                    Text("还没有本机备份。请先用上面的「生成备份」。")
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
                TextButton(onClick = { viewModel.closeLocalBackupPicker() }) { Text("关闭") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("系统设置", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
                                text = "通知与提醒行为",
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
                                Text("默认推迟时长", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "通知栏与今日清单长按菜单的默认推迟分钟数（单个药品可在「提醒设置」中单独覆盖）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            val snoozeOptions = listOf(5, 10, 15, 30, 60, 120)
                            var snoozeExpanded by remember { mutableStateOf(false) }

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
                                    value = "${uiState.snoozeMinutes} 分钟",
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
                                            text = { Text("${uiState.snoozeMinutes} 分钟 (自定义)") },
                                            onClick = { snoozeExpanded = false }
                                        )
                                    }
                                    snoozeOptions.forEach { mins ->
                                        DropdownMenuItem(
                                            text = { Text("$mins 分钟") },
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
                                Text("夜间免打扰 (静音)", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "23:00 至 07:00 之间改为静默渠道，不响铃不震动；标记为「重要提醒」的药品不受影响",
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
                                text = "提醒防漏与系统保活",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "排查精确闹钟、系统白名单与自启权限，确保灭屏休眠零漏报。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = onNavigateToPermissionCheck,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("查看 4 项系统特权自检与保活指引 >", fontWeight = FontWeight.Bold)
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
                                text = "数据管理与本地导出",
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
                                Text("导出服药明细报表 (CSV)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text("明文数据，可用 Excel / WPS 打开分析", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            OutlinedButton(
                                onClick = { viewModel.exportCsv() },
                                enabled = !uiState.isExporting,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("导出 CSV", fontSize = 12.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("备份全量数据库 (JSON)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text("包含药品、计划、打卡与库存流水完整存档", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            OutlinedButton(
                                onClick = { viewModel.exportBackup() },
                                enabled = !uiState.isExporting,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("生成备份", fontSize = 12.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("从备份恢复 (覆盖式)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text("整库快照替换还原，当前数据将被完全覆盖", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            OutlinedButton(
                                onClick = { backupPickerLauncher.launch(arrayOf("application/json")) },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("选择文件", fontSize = 12.sp)
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("从本机备份恢复", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "直接选用 App 刚生成的备份（系统文件选择器看不到这个目录）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            OutlinedButton(
                                onClick = { viewModel.openLocalBackupPicker() },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("本机备份", fontSize = 12.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // 诊断日志导出（PLAN-LOGGING S4）：排查"没提醒/账不对"时
                        // 让用户一键把最近日志发给开发者。刻意放在 Screen 层直连
                        // DataExporter，不走 ViewModel——避免给构造器加参数（坑 5）。
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("导出诊断日志", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text("最近 7 天的运行日志，排查提醒与记账问题用", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            OutlinedButton(
                                onClick = {
                                    logExportScope.launch {
                                        try {
                                            val file = DataExporter.exportDiagnosticLogs(logExportContext)
                                            if (file != null) {
                                                DataExporter.shareFile(logExportContext, file, "text/plain", "分享诊断日志")
                                            } else {
                                                Toast.makeText(logExportContext, "还没有日志文件", Toast.LENGTH_SHORT).show()
                                            }
                                        } catch (e: Exception) {
                                            AppLog.w("SettingsScreen", "export diagnostic logs failed", e)
                                            Toast.makeText(logExportContext, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("导出日志", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // 4. 关于 CarroMed
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "关于 CarroMed",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "CarroMed v1.0.0 (Native Compose)\n• 100% 纯本地离线单机运行，零网络权限申请\n• 免账号登录、无后台云端追踪、无广告干扰\n• 数据完全受控于您本人的手机私有沙箱存储",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 20.sp
                        )
                    }
                }
            }
        }
    }
}
