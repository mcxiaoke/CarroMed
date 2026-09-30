package com.mcxiaoke.carromed.core.domain.model

import java.time.LocalDate

/**
 * 提醒暂停状态的结构化结果（B4，PLAN-I18N-20260930 §3 D-A）。
 *
 * ## 为什么不是 String
 *
 * 此前 `ReminderSettingsEntity.pauseDescription()` 在 domain 层直接拼中文
 * 「提醒已暂停，N 天后恢复」——domain 零 `android.*` 依赖拿不到
 * `Context.getString`，这条文案永远无法本地化。
 * 现在 domain 只回答"什么状态、还剩几天"，格式化交给展示层
 * （`stringResource` / `Context.getString`）。
 */
sealed interface PauseStatus {
    /** 未暂停（含从未暂停、已到恢复日自动恢复）。 */
    data object NotPaused : PauseStatus

    /** 暂停中，且不会自动恢复（无限期，或恢复日解析失败按无限期处理）。 */
    data object PausedIndefinitely : PauseStatus

    /** 暂停中，距自动恢复还有 [days] 天（含当天，1 = 明天恢复）。 */
    data class PausedWithResume(val days: Int) : PauseStatus

    companion object {
        /** 由实体侧三态（null / "" / 日期）判定的便捷入口。 */
        fun of(
            pausedUntil: String?,
            isPausedOn: (LocalDate) -> Boolean,
            daysUntilResume: (LocalDate) -> Int?,
            today: LocalDate
        ): PauseStatus {
            if (pausedUntil == null) return NotPaused
            if (!isPausedOn(today)) return NotPaused
            if (pausedUntil.isBlank()) return PausedIndefinitely
            val days = daysUntilResume(today) ?: return PausedIndefinitely
            return PausedWithResume(days)
        }
    }
}
