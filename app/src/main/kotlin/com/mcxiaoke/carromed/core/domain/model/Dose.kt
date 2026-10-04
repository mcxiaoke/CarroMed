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

        /**
         * 单次剂量的**业务上界**（毫单位）：1000 个药品单位。
         *
         * 存在的理由不是"规定用户能吃多少"，而是把**溢出**关掉：
         * 毫单位是 `Int`，`of(value: Int)` 的 `value * 1000` 在 `value > 2_147_483`
         * 时会**回绕成负数** —— 负剂量打卡 = 扣减变加药（M2-2 点名的"最恶劣失败模式"）。
         * 1000 个单位对任何单次剂量都荒谬地宽松（1000 片 / 1000 ml），
         * 越界只可能是输入错误或坏数据，不可能误伤真实用药。
         *
         * 服务层仍应对**原始输入**显式 `require(≤ MAX_MILLI)`：钳制是防御纵深，
         * 不是校验的替代品（静默钳制会掩盖用户输入错误）。
         */
        const val MAX_MILLI: Int = 1_000_000

        /**
         * 量化并**钳制到合法量程**。
         *
         * 钳制到 `.milli` 的物理量程而不是 `Int` 量程：越界值一律落到 [MAX_MILLI]，
         * 于是"忘记校验"最坏也只是记录一条上界值，不会变成负数扣减。
         */
        fun of(value: Float): Dose =
            Dose(Math.round(value * 1000f).coerceIn(-MAX_MILLI, MAX_MILLI))

        /** 用 `Long` 做乘法再钳制，杜绝 `value * 1000` 的 Int 回绕。 */
        fun of(value: Int): Dose = Dose(
            (value.toLong() * 1000L)
                .coerceIn(-MAX_MILLI.toLong(), MAX_MILLI.toLong())
                .toInt()
        )

        /**
         * 原始输入（单位）是否是一个**合法的单次剂量**。
         *
         * 判据只有这一份：服务层的写入口（时点剂量 / 打卡 / 补录 / 改剂量 / 库存盘点 /
         * 补货 / 建档）全部调它，避免"某一条漏了上界"这类静默分叉。
         *
         * ⚠️ 判据必须作用在**量化后的毫单位**上，不能拿 `of(value)` 的结果判 ——
         * `of` 会把越界值**钳制**到 [MAX_MILLI]，于是 `2000` 被钳成 `MAX_MILLI` 而误判合法。
         * 这里直接用 `Math.round(value * 1000f)`：该实现在溢出处**饱和**到 `Int.MAX_VALUE`
         * （不回绕为负），因此 `milli in 1..MAX_MILLI` 一次挡住四类非法输入 ——
         * 量化归零（如 `0.0004`）、非正、超上界、溢出。
         *
         * 上界取**闭区间**：`1000` 是合法上界（与各入口提示文案「不超过 1000」一致），
         * 旧实现用 `until` 把它误判为非法。
         */
        fun isWithinRange(value: Float): Boolean {
            if (!value.isFinite()) return false
            val milli = Math.round(value * 1000f)
            return milli in 1..MAX_MILLI
        }

        /**
         * 库存场景的量程判据：与 [isWithinRange] 同源，但**放行 0**。
         *
         * 「盘点 / 建档到 0」是合法状态（药用完了），因此不能直接用 [isWithinRange]。
         * 但上界必须与剂量同口径 —— 越界会被 `of` 静默钳到 [MAX_MILLI]，
         * 于是"盘点 5000"被记成 1000，库存凭空少 80% 且台账守恒照样成立、无任何报错。
         * 单独判 `>= 0f` 又拦不住这个越界，所以要两者合起来。
         */
        fun isWithinStockRange(value: Float): Boolean {
            if (!value.isFinite() || value < 0f) return false
            val milli = Math.round(value * 1000f)
            return milli in 0..MAX_MILLI
        }
    }
}

/** 领域层的常用剂量常量。 */
object Doses {
    val ONE = Dose(1000)
    val HALF = Dose(500)
    /** 「按需 / 不追踪」这类"无固定消耗"场景的哨兵值 */
    val UNLIMITED = Dose(0)
}
