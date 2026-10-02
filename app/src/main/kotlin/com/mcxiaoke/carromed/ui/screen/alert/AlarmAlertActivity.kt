package com.mcxiaoke.carromed.ui.screen.alert

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcxiaoke.carromed.R
import com.mcxiaoke.carromed.core.alarm.DoseActionReceiver
import com.mcxiaoke.carromed.core.alarm.Notifications
import com.mcxiaoke.carromed.ui.theme.CarroMedTheme
import com.mcxiaoke.carromed.ui.theme.SuccessGreen
import com.mcxiaoke.carromed.ui.theme.WarningAmber

/**
 * 锁屏全屏提醒强交互界面 (Full-Screen Intent)
 *
 * 当设备处于锁屏或静置状态且闹钟触发时，以全屏高优先级 Activity 形式亮屏弹出。
 * 提供大触控面积的「确认已服」、「推迟」、「跳过」操作，避免普通 Heads-up 通知
 * 在锁屏下被弱化折叠或误划。
 */
class AlarmAlertActivity : ComponentActivity() {

    companion object {
        const val EXTRA_SLOT_ID = "extra_slot_id"
        const val EXTRA_MED_ID = "extra_med_id"
        const val EXTRA_SCHEDULED_DATE = "extra_scheduled_date"
        const val EXTRA_SCHEDULED_TIME = "extra_scheduled_time"
        const val EXTRA_MED_NAME = "extra_med_name"
        const val EXTRA_DOSE_TEXT = "extra_dose_text"
        const val EXTRA_NOTICE = "extra_notice"
        const val EXTRA_IS_CRITICAL = "extra_is_critical"
        const val EXTRA_SNOOZE_MINUTES = "extra_snooze_minutes"

        fun createIntent(
            context: Context,
            slotId: Long,
            medId: Long,
            scheduledDate: String,
            scheduledTime: String,
            medName: String,
            doseText: String,
            notice: String,
            isCritical: Boolean,
            snoozeMinutes: Int
        ): Intent = Intent(context, AlarmAlertActivity::class.java).apply {
            putExtra(EXTRA_SLOT_ID, slotId)
            putExtra(EXTRA_MED_ID, medId)
            putExtra(EXTRA_SCHEDULED_DATE, scheduledDate)
            putExtra(EXTRA_SCHEDULED_TIME, scheduledTime)
            putExtra(EXTRA_MED_NAME, medName)
            putExtra(EXTRA_DOSE_TEXT, doseText)
            putExtra(EXTRA_NOTICE, notice)
            putExtra(EXTRA_IS_CRITICAL, isCritical)
            putExtra(EXTRA_SNOOZE_MINUTES, snoozeMinutes)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 亮屏与锁屏上方显示设置
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val slotId = intent.getLongExtra(EXTRA_SLOT_ID, 0L)
        val medId = intent.getLongExtra(EXTRA_MED_ID, 0L)
        val scheduledDate = intent.getStringExtra(EXTRA_SCHEDULED_DATE) ?: ""
        val scheduledTime = intent.getStringExtra(EXTRA_SCHEDULED_TIME) ?: ""
        val medName = intent.getStringExtra(EXTRA_MED_NAME) ?: ""
        val doseText = intent.getStringExtra(EXTRA_DOSE_TEXT) ?: ""
        val notice = intent.getStringExtra(EXTRA_NOTICE) ?: ""
        val isCritical = intent.getBooleanExtra(EXTRA_IS_CRITICAL, false)
        val snoozeMinutes = intent.getIntExtra(EXTRA_SNOOZE_MINUTES, 30)

        setContent {
            CarroMedTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AlarmAlertContent(
                        medName = medName,
                        scheduledTime = scheduledTime,
                        doseText = doseText,
                        notice = notice,
                        isCritical = isCritical,
                        snoozeMinutes = snoozeMinutes,
                        onTake = {
                            dispatchAction(
                                action = Notifications.ACTION_TAKE,
                                medId = medId,
                                date = scheduledDate,
                                time = scheduledTime,
                                slotId = slotId
                            )
                        },
                        onSnooze = {
                            dispatchAction(
                                action = Notifications.ACTION_SNOOZE,
                                medId = medId,
                                date = scheduledDate,
                                time = scheduledTime,
                                slotId = slotId
                            ) {
                                putExtra(Notifications.EXTRA_MINUTES, snoozeMinutes)
                            }
                        },
                        onSkip = {
                            dispatchAction(
                                action = Notifications.ACTION_SKIP,
                                medId = medId,
                                date = scheduledDate,
                                time = scheduledTime,
                                slotId = slotId
                            )
                        }
                    )
                }
            }
        }
    }

    private fun dispatchAction(
        action: String,
        medId: Long,
        date: String,
        time: String,
        slotId: Long,
        extras: Intent.() -> Unit = {}
    ) {
        val uri = Uri.parse("carromed://action/$medId/$date/$time/dose")
        val intent = Intent(this, DoseActionReceiver::class.java)
            .setAction(action)
            .setData(uri)
            .apply(extras)
        sendBroadcast(intent)
        Notifications.cancelDoseNotification(this, slotId)
        finishAndRemoveTask()
    }
}

@Composable
fun AlarmAlertContent(
    medName: String,
    scheduledTime: String,
    doseText: String,
    notice: String,
    isCritical: Boolean,
    snoozeMinutes: Int,
    onTake: () -> Unit,
    onSnooze: () -> Unit,
    onSkip: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // 顶部警示图标与状态标签
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.height(32.dp))
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(CircleShape)
                    .background(
                        if (isCritical) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.primaryContainer
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isCritical) Icons.Default.NotificationsActive else Icons.Default.Alarm,
                    contentDescription = null,
                    tint = if (isCritical) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(48.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(
                    if (isCritical) R.string.alert_title_critical else R.string.alert_title_normal
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (isCritical) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
            if (scheduledTime.isNotBlank()) {
                Text(
                    text = scheduledTime,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }

        // 中部卡片：药品名称、剂量与注意事项
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = medName,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.alert_dose_label, doseText),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                if (notice.isNotBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // 底部快捷操作按钮
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = onTake,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen)
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.alert_action_take), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
                onClick = onSnooze,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Snooze, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.alert_action_snooze, snoozeMinutes), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }

            TextButton(
                onClick = onSkip,
                modifier = Modifier.height(44.dp)
            ) {
                Text(
                    stringResource(R.string.alert_action_skip),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}
