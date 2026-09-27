package com.mcxiaoke.carromed.core.alarm

import android.content.Context
import com.mcxiaoke.carromed.core.data.AppDatabase

/**
 * 提醒行为配置读取器 (ReminderSettings)
 *
 * 修复的关键缺陷：`SettingsViewModel` 早就把「默认推迟时长 / 夜间免打扰 / 全屏弹窗」
 * 写进了 `app_settings` 表，但**全工程没有任何一处读取它们** ——
 * 通知栏的「推迟30分钟」是写死的字面量，夜间静音开关完全无效。
 * 本对象是这些设置唯一的消费入口。
 */
object ReminderSettings {

    const val KEY_SNOOZE_MINUTES = "snooze_minutes"
    const val KEY_NIGHT_DND = "night_dnd"
    const val KEY_FULL_SCREEN = "full_screen_alert"
    const val KEY_SOUND_MODE = "sound_mode"
    const val KEY_LEAD_MINUTES = "lead_minutes"

    const val DEFAULT_SNOOZE_MINUTES = 30
    private const val NIGHT_DND_START_HOUR = 23
    private const val NIGHT_DND_END_HOUR = 7

    data class Behavior(
        val snoozeMinutes: Int = DEFAULT_SNOOZE_MINUTES,
        val nightDnd: Boolean = true,
        val fullScreenAlert: Boolean = true
    )

    /** 全局行为：按药品优先级解析 (药品专属 > 全局 > 默认) */
    suspend fun resolve(context: Context, db: AppDatabase, medicationId: Long): Behavior {
        val dao = db.appSettingDao()
        val globalSnooze = dao.getValue(KEY_SNOOZE_MINUTES)?.toIntOrNull() ?: DEFAULT_SNOOZE_MINUTES
        val nightDnd = dao.getValue(KEY_NIGHT_DND)?.toBoolean() ?: true
        val fullScreen = dao.getValue(KEY_FULL_SCREEN)?.toBoolean() ?: true
        val medSnooze = db.medicationDao().getMedicationById(medicationId)?.snoozeMinutes ?: 0

        return Behavior(
            snoozeMinutes = if (medSnooze > 0) medSnooze else globalSnooze,
            nightDnd = nightDnd,
            fullScreenAlert = fullScreen
        )
    }

    /** 当前是否处于夜间免打扰时段 (23:00 - 07:00) */
    fun inNightDndWindow(hourOfDay: Int): Boolean =
        hourOfDay >= NIGHT_DND_START_HOUR || hourOfDay < NIGHT_DND_END_HOUR

    /**
     * 是否应当静默：夜间免打扰开启 且 当前在夜间 且 该药未标记为「重要提醒」。
     * 重要提醒会穿透夜间静音 —— 这是关键药品 (胰岛素、抗凝药) 的刚需。
     */
    fun shouldSilence(behavior: Behavior, isCritical: Boolean, hourOfDay: Int): Boolean =
        behavior.nightDnd && !isCritical && inNightDndWindow(hourOfDay)
}
