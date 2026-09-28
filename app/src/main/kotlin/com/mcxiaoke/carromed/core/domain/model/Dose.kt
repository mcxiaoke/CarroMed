package com.mcxiaoke.carromed.core.domain.model

/**
 * 剂量 / 数量的领域值对象（整数毫单位）。
 *
 * ## 为什么需要它
 *
 * `FINAL-PRODUCT` D-7 拍板：「**整数毫单位记账**（如 1.5 片 = 1500），**全程无浮点**；
 * 杜绝账本对账漂移；UI 层做单位换算展示」。此前实体层全部用 `Float`，
 * 而 `Float` 只有 24 位有效尾数（约 7 位十进制精度），浮点加法不满足结合律 ——
 * 长期累加必然漂移，而台账的绝对不变式恰恰依赖 `SUM(change_amount)` 的精确性。
 *
 * ## 换算基准
 *
 * > **1 个药品单位 = 1000 毫单位**
 * >
 * > 1 片 = 1000 · 0.5 片 = 500 · 1.25 ml = 1250
 *
 * ## 关键性质：毫单位是「每个药品自己的单位」
 *
 * 库存台账永远只在**一个药品内部**求和（`WHERE medication_id = ?`），
 * 因此**不存在跨单位相加的存储问题**。
 * 跨单位求和只可能发生在**展示/聚合层**（统计报表 Hero 区的"累计用量"），
 * 那是纯展示问题，与存储层正交。
 *
 * ## 使用约定
 *
 * - **Entity 层**用 `Int` 存（Room 不需要自定义 TypeConverter，读写都是整数）
 * - **领域层与 UI 层**用 [Dose]，转换只发生在两个边界
 * - 这样"忘了除以 1000"会变成编译期错误或明显异常，而不是静默的数量级错误
 */
@JvmInline
value class Dose(val milli: Int) {

    /** 转成展示/计算用的浮点数。仅在 UI 渲染与统计计算处使用。 */
    val asFloat: Float get() = milli / 1000f

    val isNegative: Boolean get() = milli < 0
    val isZero: Boolean get() = milli == 0

    operator fun plus(other: Dose): Dose = Dose(milli + other.milli)
    operator fun minus(other: Dose): Dose = Dose(milli - other.milli)
    operator fun unaryMinus(): Dose = Dose(-milli)

    /** 与展示值比较时用这个，避免调用方自己写 `/ 1000f`。 */
    fun equalsWithin(other: Float, epsilon: Float = 0.0001f): Boolean =
        kotlin.math.abs(asFloat - other) < epsilon

    companion object {
        val ZERO = Dose(0)

        fun of(value: Float): Dose = Dose(Math.round(value * 1000f))
        fun of(value: Int): Dose = Dose(value * 1000)
    }
}

/** 领域层的常用剂量常量。 */
object Doses {
    val ONE = Dose(1000)
    val HALF = Dose(500)
    /** 「按需 / 不追踪」这类"无固定消耗"场景的哨兵值 */
    val UNLIMITED = Dose(0)
}
