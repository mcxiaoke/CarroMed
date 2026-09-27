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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcxiaoke.carromed.ui.theme.SuccessGreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionCheckScreen(onNavigateBack: () -> Unit) {
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
                    desc = "必须权限。允许应用在设定的准点精确唤醒 CPU 发出用药提醒，避免被系统延迟对齐。",
                    icon = Icons.Default.Alarm,
                    statusText = "已授权",
                    isGranted = true
                )
            }
            item {
                PermissionItemCard(
                    title = "2. 发送通知权限 (Notification)",
                    desc = "必须权限。Android 13+ 必须显式允许横幅通知与全屏弹窗。",
                    icon = Icons.Default.Notifications,
                    statusText = "已授权",
                    isGranted = true
                )
            }
            item {
                PermissionItemCard(
                    title = "3. 忽略电池优化 (Doze 白名单)",
                    desc = "强烈推荐。防止手机在夜间待机灭屏时冻结应用后台 AlarmManager。",
                    icon = Icons.Default.BatteryAlert,
                    statusText = "去设置",
                    isGranted = false
                )
            }
            item {
                PermissionItemCard(
                    title = "4. 锁屏显示与后台自启动",
                    desc = "国内主流厂商（小米/华为/OPPO/vivo/荣耀）需手动在手机管家中开启【自启动】与【锁屏显示】。",
                    icon = Icons.Default.Lock,
                    statusText = "查看指引",
                    isGranted = false
                )
            }
        }
    }
}

@Composable
fun PermissionItemCard(
    title: String,
    desc: String,
    icon: ImageVector,
    statusText: String,
    isGranted: Boolean
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                if (isGranted) {
                    Text(statusText, color = SuccessGreen, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                } else {
                    OutlinedButton(onClick = {}, shape = RoundedCornerShape(8.dp)) {
                        Text(statusText, fontSize = 12.sp)
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
