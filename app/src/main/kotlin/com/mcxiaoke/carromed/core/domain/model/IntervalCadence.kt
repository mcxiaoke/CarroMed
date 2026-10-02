package com.mcxiaoke.carromed.core.domain.model

/**
 * INTERVAL 频次的**口径归一**（ocsbf P1-5）。
 *
 * `SchedulePolicyEntity.intervalDays` 是引擎语义下的**周期天数**：
 * 每 n 天一个服药日（不变量 I7）。但中文界面有三套历史写法并存过：
 *
 * - 「n <= 2 隔天，否则 每隔 n-1 天」（详情页旧实现）—— `n == 1`（每天）
 *   被标成「隔天」，与实际排班**相反**；
 * - 「n <= 1 每天 / n == 2 隔天 / 其余 每 n 天」（库存页 / 药箱页）—— 正确口径。
 *
 * 本枚举是唯一的语义裁决处：显示层（`ui.component.intervalLabel`）按它映射文案，
 * 三个页面（详情 / 库存 / 药箱）不许再各写 `when` 分支。
 * 本文件位于 `core/domain`，零 `android.*` 依赖。
 */
enum class IntervalCadence {
    /** 周期 ≤ 1 天：每天都排 */
    EVERY_DAY,

    /** 周期恰为 2 天：隔天一次 */
    EVERY_OTHER_DAY,

    /** 周期 ≥ 3 天：每 n 天一次 */
    EVERY_N_DAYS;

    companion object {
        fun of(intervalDays: Int): IntervalCadence = when {
            intervalDays <= 1 -> EVERY_DAY
            intervalDays == 2 -> EVERY_OTHER_DAY
            else -> EVERY_N_DAYS
        }
    }
}
