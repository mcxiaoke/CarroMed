package com.mcxiaoke.carromed

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mcxiaoke.carromed.core.alarm.AlarmReconciler
import com.mcxiaoke.carromed.core.alarm.Notifications
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.domain.AppLog
import com.mcxiaoke.carromed.core.time.DailyOnceGate
import com.mcxiaoke.carromed.ui.component.NotificationPermissionBanner
import com.mcxiaoke.carromed.ui.navigation.AppNavigation
import com.mcxiaoke.carromed.ui.theme.CarroMedTheme
import com.mcxiaoke.carromed.ui.theme.ThemePreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_PERMISSION = "permission_banner"
        private const val KEY_LAST_BANNER_EPOCH_DAY = "last_banner_epoch_day"
    }

    /**
     * 通知权限是否已被**永久拒绝**（勾了「不再询问」/ 系统连续拒绝）。
     * 置位后由 RESUMED 低频展示引导横幅。
     */
    private val notificationPermissionBlocked = MutableStateFlow(false)

    /** 引导横幅当前是否显示（Compose 侧消费）。 */
    private val showPermissionBanner = MutableStateFlow(false)

    /** 存放"上次展示引导横幅是哪天"的轻量存储（纯 UI 节流状态，不进业务库）。 */
    private val permissionPrefs by lazy {
        getSharedPreferences(PREFS_PERMISSION, Context.MODE_PRIVATE)
    }

    /** 横幅的"每天至多一次"节流（3-3）；判据可单测，见 `core/time/DailyOnceGate`。 */
    private val bannerGate by lazy {
        DailyOnceGate(
            lastAllowedEpochDay = {
                val v = permissionPrefs.getLong(KEY_LAST_BANNER_EPOCH_DAY, Long.MIN_VALUE)
                if (v == Long.MIN_VALUE) null else v
            },
            todayEpochDay = { LocalDate.now().toEpochDay() },
            markAllowed = { day ->
                permissionPrefs.edit().putLong(KEY_LAST_BANNER_EPOCH_DAY, day).apply()
            }
        )
    }

    /**
     * 通知权限申请结果（3-3）。
     *
     * 旧实现的回调体只有一句注释「结果不阻塞主流程」—— 结果被**完全丢弃**。
     * 而"永久拒绝"恰恰是让整条提醒链路静默死亡的状态：系统不再弹框、
     * `notify()` 静默失败 → 对账的"托盘已有通知"判据恒假 → 全屏提醒一并失效，
     * 界面上却没有任何提示。现在区分「拒绝（可再问）」与「永久拒绝」，
     * 后者置位 [notificationPermissionBlocked]，交由 RESUMED 低频横幅引导自救。
     */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val blocked = !granted && isPermanentlyDenied()
            notificationPermissionBlocked.value = blocked
            if (blocked) {
                AppLog.w(TAG, "POST_NOTIFICATIONS permanently denied: reminders will stay silent")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // 主题模式必须在 setContent 之前同步读出来，否则冷启动会先按系统主题渲染一帧
        // 再翻转（`values-night/themes.xml` 当初就是为了消除这个白闪才加的）。
        ThemePreference.init(applicationContext)

        Notifications.ensureChannel(this)
        requestNotificationPermissionIfNeeded()

        // 每次回到前台都做一次闹钟全量对账 (含冷启动)。
        // 节流入口（§二-22）：5 分钟内反复前后台切换不再重复全量跑；
        // 显式路径（保存/恢复/打卡）不受此限制。
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch(Dispatchers.IO) {
                    runCatching {
                        AlarmReconciler.rescheduleAllOnResume(applicationContext, AppDatabase.getInstance(applicationContext))
                    }
                }
            }
        }

        // 3-3：通知权限永久拒绝的引导横幅。
        // - 每次回到前台先核对一次权限（用户可能刚从系统设置里把通知重新打开）：
        //   恢复了就立即收起横幅并清掉"被拒"状态，不留过期结论；
        // - 仍被拒时按 [bannerGate] 节流（每天至多一次）展示，避免反复骚扰。
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                if (notificationsGranted()) {
                    notificationPermissionBlocked.value = false
                    showPermissionBanner.value = false
                }
                notificationPermissionBlocked.collect { blocked ->
                    if (blocked && bannerGate.tryPass()) {
                        AppLog.w(TAG, "showing notification-permission guidance banner")
                        showPermissionBanner.value = true
                    }
                }
            }
        }

        setContent {
            // 主题模式由「设置 → 外观」控制；「跟随系统」时仍走 isSystemInDarkTheme()。
            val themeMode by ThemePreference.mode.collectAsStateWithLifecycle()
            CarroMedTheme(darkTheme = themeMode.resolveDark(isSystemInDarkTheme())) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val showBanner by showPermissionBanner.collectAsStateWithLifecycle()
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (showBanner) {
                            NotificationPermissionBanner(
                                onGoToSettings = { openNotificationSettings(this@MainActivity) },
                                onDismiss = { showPermissionBanner.value = false }
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                // 横幅已占据状态栏区域：把它下方内容对状态栏的内边距标记为
                                // 已消费，否则每个页面的 TopAppBar 会再让出一整个状态栏高度
                                // （横幅下方出现一条空白带）。横幅不在时不消费，各页面自管。
                                .then(
                                    if (showBanner) {
                                        Modifier.consumeWindowInsets(WindowInsets.statusBars)
                                    } else {
                                        Modifier
                                    }
                                )
                        ) {
                            AppNavigation()
                        }
                    }
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !notificationsGranted()
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** 通知权限当前是否已授予（Android 13 以下不存在该运行时权限，恒为 true）。 */
    private fun notificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 是否处于"永久拒绝"：权限已被拒，且系统**不再愿意**展示申请理由。
     *
     * ⚠️ `shouldShowRequestPermissionRationale` 在"从未申请过"时同样返回 false，
     * 所以本判据只在**申请回调返回 false 之后**调用才有意义
     * （见 [notificationPermissionLauncher]），不可前移到申请之前用于判断。
     */
    private fun isPermanentlyDenied(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
}

/**
 * 跳到本应用的通知设置页。
 *
 * `ACTION_APP_NOTIFICATION_SETTINGS` 在个别 ROM 上可能缺失，退到应用详情页 ——
 * 与 `PermissionCheckScreen` 的入口同口径：**绝不出现"点了没反应"**。
 */
private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
