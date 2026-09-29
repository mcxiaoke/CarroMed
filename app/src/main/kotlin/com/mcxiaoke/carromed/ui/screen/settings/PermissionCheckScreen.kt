package com.mcxiaoke.carromed.ui.screen.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mcxiaoke.carromed.core.alarm.AlarmScheduler
import com.mcxiaoke.carromed.ui.theme.SuccessGreen
import com.mcxiaoke.carromed.ui.theme.WarningAmber

/**
 * 系统特权自检与保活指引
 *
 * ## 为什么这一页必须接真实查询（P0-3，三份审查独立报告）
 *
 * 旧实现 4 项全部**硬编码**：精确闹钟与通知恒显示「已授权」，两个按钮
 * `onClick = {}` 空实现。而这恰恰是"精确闹钟静默降级"（P0-1）状态下
 * 唯一能让用户发现并自救的入口 —— 它却给出**相反**的结论：
 * 用户看到全绿，认定提醒已配置妥当，而实际上每个闹钟都带 1 小时窗口。
 *
 * 给用户虚假的保证，比不提供这个页面更糟。所以现在：
 * - 三项接系统真实查询（精确闹钟 / 通知 / 电池优化白名单）；
 * - 两个按钮跳到系统里**真正能改变结果**的设置页；
 * - 厂商开关（自启动 / 锁屏显示）程序化无法检测，如实标注「需手动确认」
 *   而不是假装检测过了。
 *
 * ## 为什么用 `LifecycleEventObserver` 而不是 `LaunchedEffect`
 *
 * 用户点了按钮去系统设置再返回，本页必须**重新查一遍**并把状态刷新掉。
 * `ON_RESUME` 是唯一可靠的"回来了"的信号 —— `LaunchedEffect` 只在首次组合时跑一次。
 * 电池优化白名单甚至要靠这一跳才可能变化。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionCheckScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    var facts by remember { mutableStateOf(PermissionFacts.query(context)) }

    // 从系统设置返回时重新探测：这些开关只有用户离开本页去改才会变。
    val lifecycleOwner = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                facts = PermissionFacts.query(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("系统特权自检与保活指引", fontWeight = FontWeight.Bold) },
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
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                PermissionItemCard(
                    title = "1. 精确闹钟权限 (Exact Alarm)",
                    desc = "决定提醒是「到点必响」还是「系统择机送达」。用药提醒属于" +
                        "《Android 闹钟类应用》标准场景，CarroMed 已声明 USE_EXACT_ALARM，" +
                        "正常安装即自动授予；若这里显示未授权，说明系统把它降级处理了。",
                    icon = Icons.Default.Alarm,
                    statusText = facts.precision.label,
                    isGranted = !facts.precision.isDegraded,
                    actionText = if (facts.needsExactAlarmRequest) "去授权" else null,
                    onAction = { openExactAlarmSettings(context) }
                )
            }
            item {
                PermissionItemCard(
                    title = "2. 发送通知权限 (Notification)",
                    desc = "Android 13+ 必须显式允许通知，否则闹钟会照常唤醒但屏幕上什么都不会出现。",
                    icon = Icons.Default.Notifications,
                    statusText = if (facts.notificationsEnabled) "已授权" else "未授权",
                    isGranted = facts.notificationsEnabled,
                    actionText = if (facts.notificationsEnabled) null else "去开启",
                    onAction = { openNotificationSettings(context) }
                )
            }
            item {
                PermissionItemCard(
                    title = "3. 忽略电池优化 (Doze 白名单)",
                    desc = "强烈推荐。防止手机在夜间待机灭屏时冻结后台 AlarmManager。",
                    icon = Icons.Default.BatteryAlert,
                    statusText = if (facts.ignoringBatteryOptimizations) "已加入白名单" else "未加入",
                    isGranted = facts.ignoringBatteryOptimizations,
                    actionText = if (facts.ignoringBatteryOptimizations) null else "去设置",
                    onAction = { openBatteryOptimizationSettings(context) }
                )
            }
            item {
                PermissionItemCard(
                    title = "4. 锁屏显示与后台自启动",
                    desc = "国内主流厂商（小米/华为/OPPO/vivo/荣耀）需手动在手机管家中开启" +
                        "【自启动】与【锁屏显示】。该项无法通过系统 API 程序化检测，" +
                        "请自行到手机管家确认。",
                    icon = Icons.Default.Lock,
                    statusText = "需手动确认",
                    isGranted = false,
                    actionText = null,
                    onAction = null
                )
            }
        }
    }
}

/**
 * 一项特权的事实快照。
 *
 * @param precision 当前实际生效的闹钟投递档位（不只是"有没有权限"）。
 * @param needsExactAlarmRequest 是否有系统授权入口可跳。声明了 `USE_EXACT_ALARM`
 *   的应用**不会**出现这个入口（系统直接授予），此时若 [precision] 已降级，
 *   只能靠厂商白名单/保活页面去排查 —— 文案上如实区分，不给一个点了没用的按钮。
 */
private data class PermissionFacts(
    val precision: AlarmScheduler.Precision,
    val notificationsEnabled: Boolean,
    val ignoringBatteryOptimizations: Boolean,
    val needsExactAlarmRequest: Boolean
) {
    companion object {
        fun query(context: Context): PermissionFacts {
            val pkg = context.packageName
            val pm = context.getSystemService(PowerManager::class.java)
            return PermissionFacts(
                precision = AlarmScheduler.currentPrecision(context),
                notificationsEnabled = NotificationManagerCompat.from(context)
                    .areNotificationsEnabled(),
                ignoringBatteryOptimizations = pm?.isIgnoringBatteryOptimizations(pkg) == true,
                needsExactAlarmRequest = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    context.packageManager
                        .resolveActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$pkg")), 0) != null
            )
        }
    }
}

private fun openExactAlarmSettings(context: Context) {
    val pkg = context.packageName
    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$pkg"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        // 部分 ROM 没有这个 Activity，退到应用详情页让用户自己找
        openAppDetails(context)
    }
}

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure { openAppDetails(context) }
}

private fun openBatteryOptimizationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure { openAppDetails(context) }
}

private fun openAppDetails(context: Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@Composable
fun PermissionItemCard(
    title: String,
    desc: String,
    icon: ImageVector,
    statusText: String,
    isGranted: Boolean,
    /** 非 null 时渲染跳转按钮（点了能真正改变 [statusText]） */
    actionText: String? = null,
    onAction: (() -> Unit)? = null
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.width(8.dp))
                // 状态文字本身**始终**显示 —— 即使没有按钮，用户也要知道结论。
                // 旧实现把它塞进按钮里，于是一个空 `onClick = {}` 的按钮既是
                // 状态显示也是唯一入口，等于把"不知道"伪装成"知道了"。
                Text(
                    text = statusText,
                    color = if (isGranted) SuccessGreen else WarningAmber,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
            if (actionText != null && onAction != null) {
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onAction,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(actionText, fontSize = 12.sp, maxLines = 1)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
