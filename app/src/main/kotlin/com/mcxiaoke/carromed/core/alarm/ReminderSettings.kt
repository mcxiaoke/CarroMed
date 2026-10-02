package com.mcxiaoke.carromed.core.alarm

import android.content.Context
import com.mcxiaoke.carromed.core.data.AppDatabase

/**
 * 提醒行为配置读取器 (ReminderSettings)
 *
 * 修复的关键缺陷：`SettingsViewModel` 早就把「默认推迟时长 / 夜间免打扰」
 * 写进了 `app_settings` 表，但**全工程没有任何一处读取它们** ——
 * 通知栏的「推迟30分钟」是写死的字面量，夜间静音开关完全无效。
 * 本对象是这些设置唯一的消费入口。
 *
 * ## 已摘除的「灭屏全屏弹窗」开关（P0-2）
 *
 * 原先还有 `full_screen_alert`：写库 ✓、读库 ✓、一路传进
 * [Notifications.showDoseNotification] 的 `Behavior`，然后**从不被消费** ——
 * Manifest 里没有 `USE_FULL_SCREEN_INTENT`，`showDoseNotification` 里也没有
 * `setFullScreenIntent`。用户以为开了锁屏全屏弹窗，实际什么都没有。
 *
 * 给用户一个不生效的开关，比没有这个开关更糟：它是一句**虚假的保证**。
 * 正确做法是把它连同 Android 14 的 `canUseFullScreenIntent` 授权流程一起
 * 另立 feature，而不是留一个永远为真的假开关。相关字段已从本对象删除。
 */
object ReminderSettings {

    const val KEY_SNOOZE_MINUTES = "snooze_minutes"
    const val KEY_NIGHT_DND = "night_dnd"
    const val KEY_NIGHT_DND_START = "night_dnd_start"
    const val KEY_NIGHT_DND_END = "night_dnd_end"
    const val KEY_REPEAT_REMINDER_ENABLED = "repeat_reminder_enabled"
    const val KEY_REPEAT_REMINDER_INTERVAL = "repeat_reminder_interval"
    const val KEY_REPEAT_REMINDER_MAX_COUNT = "repeat_reminder_max_count"
    const val KEY_COMPLETION_SOUND = "completion_sound"
    const val KEY_COMPLETION_HAPTIC = "completion_haptic"

    const val DEFAULT_SNOOZE_MINUTES = 30
    const val DEFAULT_NIGHT_DND_START = "23:00"
    const val DEFAULT_NIGHT_DND_END = "07:00"
    const val DEFAULT_REPEAT_INTERVAL_MINUTES = 30
    const val DEFAULT_REPEAT_MAX_COUNT = 3

    private const val NIGHT_DND_START_HOUR = 23
    private const val NIGHT_DND_END_HOUR = 7

    data class Behavior(
        val snoozeMinutes: Int = DEFAULT_SNOOZE_MINUTES,
        val nightDnd: Boolean = true,
        val nightDndStart: String = DEFAULT_NIGHT_DND_START,
        val nightDndEnd: String = DEFAULT_NIGHT_DND_END,
        val repeatReminderEnabled: Boolean = true,
        val repeatReminderIntervalMinutes: Int = DEFAULT_REPEAT_INTERVAL_MINUTES,
        val repeatReminderMaxCount: Int = DEFAULT_REPEAT_MAX_COUNT
    )

    /** 全局行为：按药品优先级解析 (药品专属 > 全局 > 默认) */
    suspend fun resolve(context: Context, db: AppDatabase, medicationId: Long): Behavior {
        val dao = db.appSettingDao()
        val globalSnooze = dao.getValue(KEY_SNOOZE_MINUTES)?.toIntOrNull() ?: DEFAULT_SNOOZE_MINUTES
        val nightDnd = dao.getValue(KEY_NIGHT_DND)?.toBoolean() ?: true
        val nightDndStart = dao.getValue(KEY_NIGHT_DND_START) ?: DEFAULT_NIGHT_DND_START
        val nightDndEnd = dao.getValue(KEY_NIGHT_DND_END) ?: DEFAULT_NIGHT_DND_END
        val repeatEnabled = dao.getValue(KEY_REPEAT_REMINDER_ENABLED)?.toBoolean() ?: true
        val repeatInterval = dao.getValue(KEY_REPEAT_REMINDER_INTERVAL)?.toIntOrNull() ?: DEFAULT_REPEAT_INTERVAL_MINUTES
        val repeatMaxCount = dao.getValue(KEY_REPEAT_REMINDER_MAX_COUNT)?.toIntOrNull() ?: DEFAULT_REPEAT_MAX_COUNT

        // 专属推迟时长已随 A2 迁到 `reminder_settings` 表（1:1）
        val medSnooze = db.reminderSettingsDao().getByMedicationId(medicationId)?.snoozeMinutes ?: 0

        return Behavior(
            snoozeMinutes = if (medSnooze > 0) medSnooze else globalSnooze,
            nightDnd = nightDnd,
            nightDndStart = nightDndStart,
            nightDndEnd = nightDndEnd,
            repeatReminderEnabled = repeatEnabled,
            repeatReminderIntervalMinutes = repeatInterval,
            repeatReminderMaxCount = repeatMaxCount
        )
    }

    /** 当前是否处于夜间免打扰时段，支持跨午夜和同日时间段 */
    fun inNightDndWindow(
        now: java.time.LocalTime,
        start: java.time.LocalTime,
        end: java.time.LocalTime
    ): Boolean = if (start <= end) {
        now >= start && now < end
    } else {
        now >= start || now < end
    }

    /** 兼容旧版仅传 hourOfDay 的重载 */
    fun inNightDndWindow(hourOfDay: Int): Boolean =
        hourOfDay >= NIGHT_DND_START_HOUR || hourOfDay < NIGHT_DND_END_HOUR

    /**
     * 是否应当静默：夜间免打扰开启 且 当前在夜间 且 该药未标记为「重要提醒」。
     * 重要提醒会穿透夜间静音 —— 这是关键药品 (胰岛素、抗凝药) 的刚需。
     */
    fun shouldSilence(
        behavior: Behavior,
        isCritical: Boolean,
        nowTime: java.time.LocalTime = java.time.LocalTime.now()
    ): Boolean {
        if (!behavior.nightDnd || isCritical) return false
        val start = runCatching { java.time.LocalTime.parse(behavior.nightDndStart) }
            .getOrDefault(java.time.LocalTime.of(NIGHT_DND_START_HOUR, 0))
        val end = runCatching { java.time.LocalTime.parse(behavior.nightDndEnd) }
            .getOrDefault(java.time.LocalTime.of(NIGHT_DND_END_HOUR, 0))
        return inNightDndWindow(nowTime, start, end)
    }

    fun shouldSilence(behavior: Behavior, isCritical: Boolean, hourOfDay: Int): Boolean =
        shouldSilence(behavior, isCritical, java.time.LocalTime.of(hourOfDay.coerceIn(0, 23), 0))
}
