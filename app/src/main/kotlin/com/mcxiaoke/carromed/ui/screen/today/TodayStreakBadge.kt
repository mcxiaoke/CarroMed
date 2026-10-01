package com.mcxiaoke.carromed.ui.screen.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcxiaoke.carromed.R
import com.mcxiaoke.carromed.ui.theme.OnWarningAmberContainer
import com.mcxiaoke.carromed.ui.theme.WarningAmber
import com.mcxiaoke.carromed.ui.theme.WarningAmberContainer

/**
 * 顶栏连续服药天数胶囊徽章（StreakBadge）。
 *
 * 参考 MyTherapy 顶栏设计，以微型胶囊形态呈现连续达标天数。
 * 图标采用五角星（[Icons.Filled.Star]），点击可打开月度打卡日历。
 */
@Composable
fun TodayStreakBadge(
    streakDays: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isActive = streakDays > 0
    val containerColor = if (isActive) WarningAmberContainer else MaterialTheme.colorScheme.surfaceVariant
    val iconColor = if (isActive) WarningAmber else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val textColor = if (isActive) OnWarningAmberContainer else MaterialTheme.colorScheme.onSurfaceVariant

    val cdText = stringResource(R.string.today_streak_cd, streakDays)

    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(containerColor)
            .clickable(
                role = Role.Button,
                onClick = onClick
            )
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics {
                role = Role.Button
                contentDescription = cdText
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Star,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            // 资源化（orsbf P3-8）：字符串模板硬编码绕过了 scan_hardcoded_strings 的扫描
            text = stringResource(R.string.today_streak_badge, streakDays),
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            ),
            color = textColor
        )
    }
}
