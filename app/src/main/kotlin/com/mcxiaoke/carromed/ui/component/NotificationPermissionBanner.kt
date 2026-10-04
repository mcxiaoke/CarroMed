package com.mcxiaoke.carromed.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mcxiaoke.carromed.R

/**
 * 通知权限被永久拒绝时的引导横幅（3-3）。
 *
 * ## 为什么必须有它
 *
 * Android 13+ 的用户在权限弹框里勾「不再询问」后，系统**不再弹框**，
 * 而 `NotificationManagerCompat.notify()` 会**静默失败**（不抛异常、返回成功）。
 * 后果是连锁的：通知发不出 → 对账把"托盘已有通知"当作补响判据时全部落空 →
 * 全屏强提醒也一并失效。整条提醒链路静默死亡，而 App 界面上**没有任何提示** ——
 * 用户以为提醒已配好，实则什么都收不到。
 *
 * 这里给出可见的结论 + 一条真正能改变结果的入口（跳系统通知设置）。
 * 展示时机由调用方用 [com.mcxiaoke.carromed.core.time.DailyOnceGate] 节流（每天至多一次），
 * 因此它只是**低频**的一次性提示，不是常驻骚扰。
 *
 * 用 `errorContainer` 而非 info 色：这不是"建议优化"，而是"当前功能已失效"。
 */
@Composable
fun NotificationPermissionBanner(
    onGoToSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer
    ) {
        Column(
            modifier = Modifier
                // 横幅延伸到状态栏下方，内容避开状态栏
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)
        ) {
            Text(
                text = stringResource(R.string.perm_banner_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.perm_banner_body),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onGoToSettings) {
                    Text(
                        text = stringResource(R.string.perm_action_enable),
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.perm_banner_dismiss))
                }
            }
        }
    }
}
