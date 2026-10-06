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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.mcxiaoke.carromed.R
import com.mcxiaoke.carromed.core.alarm.CompletionSoundPlayer
import com.mcxiaoke.carromed.core.alarm.DoseActionReceiver
import com.mcxiaoke.carromed.core.alarm.Notifications
import com.mcxiaoke.carromed.core.alarm.ReminderSettings
import com.mcxiaoke.carromed.core.data.AppDatabase
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcxiaoke.carromed.ui.component.CarroMedTopAppBar
import com.mcxiaoke.carromed.ui.theme.CarroMedTheme
import com.mcxiaoke.carromed.ui.theme.ThemePreference
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 锁屏全屏提醒强交互界面 (Full-Screen Intent)
 *
 * 当设备处于锁屏或静置状态且闹钟触发时，以全屏高优先级 Activity 形式亮屏弹出。
 * 提供大触控面积的「确认已服」、「推迟」、「跳过」操作，避免普通 Heads-up 通知
 * 在锁屏下被弱化折叠或误划。
 *
 * ## 展示内容由 [payload] 驱动，而不是 `onCreate` 里读一次的局部常量（3-1）
 *
 * 本页是 `singleTop` + `FLAG_ACTIVITY_NEW_TASK` + `taskAffinity=""`：第二条提醒到达时
 * 系统**复用**同一个实例并回调 [onNewIntent]（实测连续两次拉起仍是 1 个 ActivityRecord，
 * 把 launchMode 改成 `standard` 也一样 —— NEW_TASK 的任务复用优先于 launchMode）。
 * 旧实现只在 `onCreate` 读一次 extras，于是第二条提醒弹出的仍是第一条的药名，
 * 「确认已服」会落库到**第一条**的槽位 —— 提醒串改。
 *
 * 现在全部内容收进 [payload] 这份 Compose 状态，[onNewIntent] 整体替换它即可，
 * **不调用 `recreate()`**（整屏重建会闪一下，而内容本就是状态驱动的）。
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

        /**
         * 全屏提醒上固定的推迟档位（分钟）。
         *
         * 用户要求：在原有单档之上补 60 / 90 / 120。
         * 调用方还会并上该药品在「提醒设置」里的自定义时长（见 [alertPayloadOf]）——
         * 配了 45 分钟这类值时不能被这套固定档位静默吞掉。
         */
        val SNOOZE_CHOICES = listOf(30, 60, 90, 120)

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

    /**
     * 当前要展示的提醒内容。`null` 只在 `onCreate` 赋值之前存在一瞬。
     */
    private val payload = mutableStateOf<AlertPayload?>(null)

    /**
     * 当前展示的操作反馈（确认已服 / 推迟 / 跳过）。
     * 非空时在顶层覆盖不透明浮层拦截手势，1 秒后自动关闭。
     */
    private val feedbackState = mutableStateOf<AlertFeedback?>(null)
    private var autoDismissJob: Job? = null

    private var completionSound: String = ReminderSettings.DEFAULT_COMPLETION_SOUND
    private var completionHaptic: Boolean = true

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
        // ⚠️ 刻意不加 FLAG_KEEP_SCREEN_ON：它会无期限压制系统息屏，
        // 用户不操作时整夜亮屏 + 持 wakelock（明确耗电发热）。
        // 亮屏需求由上面的 setTurnScreenOn(true) 满足（唤醒一次，之后遵循系统息屏策略）。

        payload.value = alertPayloadOf(intent)
        // 全屏提醒是独立 Activity，同样要跟随用户在「设置 → 外观」里选的主题，
        // 否则 App 内是深色、半夜弹出来的提醒却是浅色。
        ThemePreference.init(applicationContext)

        lifecycleScope.launch {
            val db = AppDatabase.getInstance(applicationContext)
            completionSound = db.appSettingDao().getValue(ReminderSettings.KEY_COMPLETION_SOUND)
                ?: ReminderSettings.DEFAULT_COMPLETION_SOUND
            completionHaptic = db.appSettingDao().getValue(ReminderSettings.KEY_COMPLETION_HAPTIC)?.toBoolean()
                ?: true
        }

        setContent {
            val themeMode by ThemePreference.mode.collectAsStateWithLifecycle()
            CarroMedTheme(darkTheme = themeMode.resolveDark(isSystemInDarkTheme())) {
                Scaffold(
                    topBar = {
                        CarroMedTopAppBar(title = stringResource(R.string.alert_screen_title))
                    },
                    containerColor = MaterialTheme.colorScheme.background
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        // 读 payload.value 即建立组合依赖：onNewIntent 替换它 → 自动重组到新药品。
                        payload.value?.let { alert ->
                            AlarmAlertContent(
                                medName = alert.medName,
                                scheduledTime = alert.scheduledTime,
                                doseText = alert.doseText,
                                notice = alert.notice,
                                isCritical = alert.isCritical,
                                snoozeOptions = alert.snoozeOptions,
                                onTake = { dispatchAction(Notifications.ACTION_TAKE, alert) },
                                onSnooze = { minutes ->
                                    dispatchAction(Notifications.ACTION_SNOOZE, alert) {
                                        putExtra(Notifications.EXTRA_MINUTES, minutes)
                                    }
                                },
                                onSkip = { dispatchAction(Notifications.ACTION_SKIP, alert) }
                            )
                        }

                        // 顶层不透明全屏反馈浮层（拦截点击，1秒自动关闭，点屏幕也可提前退出）
                        feedbackState.value?.let { feedback ->
                            AlertFeedbackOverlay(
                                feedback = feedback,
                                onDismiss = {
                                    autoDismissJob?.cancel()
                                    finishAndRemoveTask()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * 第二条全屏提醒到达时的入口（3-1）。
     *
     * 必须 `setIntent()`：`getIntent()` 不会自动更新，仍是**原始** Intent。
     * 然后整体替换 [payload] —— 界面随即显示第二条的药品与剂量，
     * 三个操作按钮也都绑定到第二条的 `medId / date / time / slotId`。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        autoDismissJob?.cancel()
        feedbackState.value = null
        payload.value = alertPayloadOf(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        autoDismissJob?.cancel()
    }

    private fun dispatchAction(
        action: String,
        alert: AlertPayload,
        extras: Intent.() -> Unit = {}
    ) {
        if (feedbackState.value != null) return // 防连击守卫

        val uri = Uri.parse("carromed://action/${alert.medId}/${alert.scheduledDate}/${alert.scheduledTime}/dose")
        val intent = Intent(this, DoseActionReceiver::class.java)
            .setAction(action)
            .setData(uri)
            .apply(extras)
        sendBroadcast(intent)
        Notifications.cancelDoseNotification(this, alert.slotId)

        when (action) {
            Notifications.ACTION_TAKE -> {
                CompletionSoundPlayer.play(
                    context = this,
                    soundKey = completionSound,
                    soundEnabled = completionSound != "none",
                    hapticEnabled = completionHaptic
                )
                feedbackState.value = AlertFeedback.Taken(alert.medName, alert.doseText)
            }
            Notifications.ACTION_SNOOZE -> {
                val minutes = intent.getIntExtra(Notifications.EXTRA_MINUTES, ReminderSettings.DEFAULT_SNOOZE_MINUTES)
                feedbackState.value = AlertFeedback.Snoozed(alert.medName, minutes)
            }
            Notifications.ACTION_SKIP -> {
                feedbackState.value = AlertFeedback.Skipped(alert.medName)
            }
            else -> {
                finishAndRemoveTask()
                return
            }
        }

        autoDismissJob?.cancel()
        autoDismissJob = lifecycleScope.launch {
            delay(1000L)
            finishAndRemoveTask()
        }
    }
}

/**
 * 一次全屏提醒要展示与回传的全部内容。
 *
 * 抽成不可变快照而不是一堆局部变量，是为了让"第二条提醒"能整体替换 ——
 * 局部变量只能靠 `recreate()` 才能更新。
 */
private data class AlertPayload(
    val slotId: Long,
    val medId: Long,
    val scheduledDate: String,
    val scheduledTime: String,
    val medName: String,
    val doseText: String,
    val notice: String,
    val isCritical: Boolean,
    val snoozeOptions: List<Int>
)

/**
 * 从 Intent 解析出 [AlertPayload]。
 *
 * 推迟档位 = 固定四档 ∪ 该药品配置的时长（去重升序）。
 * 配置值并进来而不是替换固定档位：用户把默认改成 45 分钟后，
 * 全屏页仍应给得出他设定的那个数，否则就是"设置被界面吞掉"。
 */
private fun alertPayloadOf(intent: Intent): AlertPayload {
    val configuredSnooze = intent
        .getIntExtra(
            AlarmAlertActivity.EXTRA_SNOOZE_MINUTES,
            ReminderSettings.DEFAULT_SNOOZE_MINUTES
        )
        .coerceIn(1, 240)
    return AlertPayload(
        slotId = intent.getLongExtra(AlarmAlertActivity.EXTRA_SLOT_ID, 0L),
        medId = intent.getLongExtra(AlarmAlertActivity.EXTRA_MED_ID, 0L),
        scheduledDate = intent.getStringExtra(AlarmAlertActivity.EXTRA_SCHEDULED_DATE) ?: "",
        scheduledTime = intent.getStringExtra(AlarmAlertActivity.EXTRA_SCHEDULED_TIME) ?: "",
        medName = intent.getStringExtra(AlarmAlertActivity.EXTRA_MED_NAME) ?: "",
        doseText = intent.getStringExtra(AlarmAlertActivity.EXTRA_DOSE_TEXT) ?: "",
        notice = intent.getStringExtra(AlarmAlertActivity.EXTRA_NOTICE) ?: "",
        isCritical = intent.getBooleanExtra(AlarmAlertActivity.EXTRA_IS_CRITICAL, false),
        snoozeOptions = (AlarmAlertActivity.SNOOZE_CHOICES + configuredSnooze).distinct().sorted()
    )
}

@Composable
fun AlarmAlertContent(
    medName: String,
    scheduledTime: String,
    doseText: String,
    notice: String,
    isCritical: Boolean,
    /** 推迟档位（分钟），至少 4 档；由调用方按固定档位 ∪ 药品配置去重升序给出 */
    snoozeOptions: List<Int>,
    onTake: () -> Unit,
    onSnooze: (Int) -> Unit,
    onSkip: () -> Unit
) {
    // ⚠️ 可滚动：推迟档位从 1 个变成多档后，小屏（如 360×640dp）会把
    // 「确认已服」挤出屏幕 —— 那是**主操作不可达**，比多滚一下严重得多。
    // SpaceBetween 在可滚动容器里没有多余空间可分配，等价于零间距，
    // 所以这里改用固定间距（spacedBy），并靠 SpaceBetween 的"顶/中/底"结构
    // 换成自然流式排布：高屏上内容偏上，但所有操作一定可达。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
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

        // 底部操作：确认已服（主）→ 推迟档位 → 跳过本次
        //
        // 三块职责分明：
        // - 「确认已服」仍是唯一的主按钮（绿色、56dp、最显眼），语义不变；
        // - 「跳过本次」从原来 44dp 的灰色小字升级为**等宽等高**的独立按钮 ——
        //   它和"确认"是同级表态（这一剂不吃了），不该藏在底部当装饰文字；
        // - 推迟给出多档，按**两列**排布：档位数固定（4~5 个），两列不会随屏宽
        //   换行错位，也比一行四颗更耐点（中老年用户的手指点不准窄按钮）。
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
                colors = ButtonDefaults.buttonColors()
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.alert_action_take), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }

            Text(
                text = stringResource(R.string.alert_snooze_section),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start
            )

            snoozeOptions.chunked(2).forEach { rowOptions ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    rowOptions.forEach { minutes ->
                        OutlinedButton(
                            onClick = { onSnooze(minutes) },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(
                                stringResource(R.string.alert_snooze_minutes_fmt, minutes),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    // 档位数为奇数时补一个等宽占位，保持左列对齐（不要让最后一颗居中成"半个按钮"）
                    if (rowOptions.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            OutlinedButton(
                onClick = onSkip,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    stringResource(R.string.alert_action_skip),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * 全屏提醒操作反馈状态
 */
sealed interface AlertFeedback {
    data class Taken(val medName: String, val doseText: String) : AlertFeedback
    data class Snoozed(val medName: String, val minutes: Int) : AlertFeedback
    data class Skipped(val medName: String) : AlertFeedback
}

/**
 * 顶层不透明操作反馈浮层。
 *
 * 1. 背景完全不透明（background 颜色），彻底遮挡底层按钮，物理截断 1 秒延时内的二次连击；
 * 2. 居中展示醒目的图标、反馈标题与药品信息，给用户明确的操作闭环；
 * 3. 消费全屏点击事件，用户点击屏幕任意处可立即调用 [onDismiss] 提前关闭退出。
 */
@Composable
private fun AlertFeedbackOverlay(
    feedback: AlertFeedback,
    onDismiss: () -> Unit
) {
    val config = when (feedback) {
        is AlertFeedback.Taken -> FeedbackConfig(
            icon = Icons.Default.Check,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            title = stringResource(R.string.alert_feedback_taken),
            subtitle = if (feedback.doseText.isNotBlank()) {
                "${feedback.medName} · ${feedback.doseText}"
            } else {
                feedback.medName
            }
        )
        is AlertFeedback.Snoozed -> FeedbackConfig(
            icon = Icons.Default.Alarm,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            title = stringResource(R.string.alert_feedback_snoozed, feedback.minutes),
            subtitle = feedback.medName
        )
        is AlertFeedback.Skipped -> FeedbackConfig(
            icon = Icons.Default.NotificationsActive,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            title = stringResource(R.string.alert_feedback_skipped),
            subtitle = feedback.medName
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(config.containerColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = config.icon,
                    contentDescription = null,
                    tint = config.contentColor,
                    modifier = Modifier.size(52.dp)
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = config.title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )
            if (config.subtitle.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = config.subtitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(36.dp))
            Text(
                text = stringResource(R.string.alert_feedback_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center
            )
        }
    }
}

private data class FeedbackConfig(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val containerColor: androidx.compose.ui.graphics.Color,
    val contentColor: androidx.compose.ui.graphics.Color,
    val title: String,
    val subtitle: String
)

