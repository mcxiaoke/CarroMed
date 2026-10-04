package com.mcxiaoke.carromed.ui.theme

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 主题模式（用户可见的「外观」设置）。
 *
 * 为什么需要它：`CarroMedTheme` 的 `darkTheme` 原先只跟 `isSystemInDarkTheme()` 走，
 * App 内没有任何开关 —— 要在真机/模拟器上验证深色形态，只能去改系统设置，
 * 而改系统设置会连带影响设备上所有应用，且无法截图对比"同一页面两种形态"。
 */
enum class ThemeMode {
    /** 跟随系统（默认） */
    SYSTEM,
    LIGHT,
    DARK;

    /** 结合系统当前状态，解析出这次到底该不该用深色。 */
    fun resolveDark(systemInDarkTheme: Boolean): Boolean = when (this) {
        SYSTEM -> systemInDarkTheme
        LIGHT -> false
        DARK -> true
    }

    companion object {
        /**
         * 从持久化字符串还原。
         *
         * 未知/空值一律回落到 [SYSTEM] —— 存储里的脏数据不该让 App 起不来，
         * 也不该把用户锁在一个他没选过的模式里。
         */
        fun fromStorage(raw: String?): ThemeMode =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: SYSTEM
    }
}

/**
 * 主题模式的读写入口。
 *
 * ## 为什么用 SharedPreferences 而不是 `app_settings` 表
 *
 * 1. `MainActivity.onCreate` 必须在 `setContent` **之前**同步拿到值。
 *    Room 是异步的，用 Flow 收集会在冷启动时先按系统主题渲染一帧再翻转 ——
 *    而 `values-night/themes.xml`（见 `CHANGES-20261002` §二-31）正是为了
 *    「消除深色模式冷启动白闪」才加的，用 Room 会把那条改动打回去。
 * 2. 主题模式是**设备级外观偏好**，不属于要进备份/恢复的业务数据。
 *
 * [mode] 对外是一个进程级 [StateFlow]：设置页写入后 `MainActivity` 无需重启即可重组。
 * 它只在 `init` 时从磁盘读一次，所以不要在别处再调用 [read] 去覆盖它。
 */
object ThemePreference {

    private const val PREFS_NAME = "carromed_ui"
    private const val KEY_THEME_MODE = "theme_mode"

    private val _mode = MutableStateFlow(ThemeMode.SYSTEM)

    /** 当前主题模式（进程级，供 Compose 订阅）。 */
    val mode: StateFlow<ThemeMode> = _mode.asStateFlow()

    private var initialized = false

    /** 冷启动时调用一次，把磁盘上的值灌入 [mode]（必须在 `setContent` 之前）。 */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        _mode.value = read(context)
    }

    /** 直接读磁盘（不经过 [mode]，供测试与冷启动使用）。 */
    fun read(context: Context): ThemeMode =
        ThemeMode.fromStorage(prefs(context).getString(KEY_THEME_MODE, null))

    /** 写入并立即推送给订阅方。 */
    fun write(context: Context, mode: ThemeMode) {
        prefs(context).edit { putString(KEY_THEME_MODE, mode.name) }
        _mode.value = mode
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
