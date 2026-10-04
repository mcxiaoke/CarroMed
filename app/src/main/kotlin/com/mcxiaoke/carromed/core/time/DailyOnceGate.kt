package com.mcxiaoke.carromed.core.time

/**
 * 「每自然日至多放行一次」的节流判据。
 *
 * ## 为什么抽成一个类而不是写在 `MainActivity` 里
 *
 * 3-3 的引导横幅要求"低频：仅在 RESUMED、每天至多一次"。而 `MainActivity`
 * 依赖真实的 `Activity` / `SharedPreferences` / 系统时钟，在 JVM 单测里跑不起来
 * （AGENTS §二 的教训：判定逻辑塞进不可测的壳里，等于没有测试）。
 *
 * 这里把"是否放行"抽成**纯逻辑**：存储（上次放行是哪天）与"今天是哪天"都由调用方注入，
 * 于是"同一天只放行一次、跨天重新放行、时钟回拨不误判"都能在单测里验证。
 *
 * @param lastAllowedEpochDay 读取"上次放行"的纪元日（从未放行过时返回 `null`）
 * @param todayEpochDay 读取"今天"的纪元日（`LocalDate.toEpochDay()`）
 * @param markAllowed 放行后写入"上次放行"的纪元日（调用方负责持久化）
 */
class DailyOnceGate(
    private val lastAllowedEpochDay: () -> Long?,
    private val todayEpochDay: () -> Long,
    private val markAllowed: (Long) -> Unit
) {

    /**
     * 今天是否应当放行。
     *
     * ⚠️ **放行的同一次调用里就会记账**（`markAllowed`），而不是等调用方回头再记 ——
     * 否则"同一天被 RESUMED 触发多次"会在记账前连续放行多次。
     * 记账用 `==` 而不是 `>`：用户把系统日期往回调再调回来时，
     * 同一天仍应只放行一次。
     */
    fun tryPass(): Boolean {
        val today = todayEpochDay()
        if (lastAllowedEpochDay() == today) return false
        markAllowed(today)
        return true
    }
}
