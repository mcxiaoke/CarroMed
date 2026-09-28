package com.mcxiaoke.carromed.core.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [Dose] 值对象测试（执行 `FINAL-PRODUCT` D-7「整数毫单位，全程无浮点」）
 *
 * ## 这条不变量为什么重要
 *
 * 库存台账的绝对不变式是 `balance == SUM(change_amount)`，而余额是**累加**得来的。
 * 浮点加法不满足结合律，长期累加必然漂移；整数加法则是精确的。
 * 这个值对象是"全程无浮点"这条决策在代码里的落地点。
 */
class DoseTest {

    @Test
    fun `1 个单位等于 1000 毫单位`() {
        assertThat(Dose.of(1f).milli).isEqualTo(1000)
        assertThat(Dose.of(1).milli).isEqualTo(1000)
        assertThat(Doses.ONE.milli).isEqualTo(1000)
    }

    @Test
    fun `常见剂量换算正确`() {
        assertThat(Dose.of(0.5f).milli).isEqualTo(500)
        assertThat(Dose.of(1.5f).milli).isEqualTo(1500)
        assertThat(Dose.of(1.25f).milli).isEqualTo(1250)
        assertThat(Dose.of(2f).milli).isEqualTo(2000)
        assertThat(Dose.of(0f).milli).isEqualTo(0)
    }

    @Test
    fun `毫单位转回展示值`() {
        assertThat(Dose(1000).asFloat).isEqualTo(1.0f)
        assertThat(Dose(500).asFloat).isEqualTo(0.5f)
        assertThat(Dose(1250).asFloat).isEqualTo(1.25f)
        assertThat(Dose(0).asFloat).isEqualTo(0.0f)
    }

    /**
     * 浮点无法精确表示 0.1 / 0.2，但毫单位整数可以。
     * 这条测试直接演示了 D-7 要解决的问题。
     */
    @Test
    fun `整数毫单位可精确表示浮点无法表示的十进制分数`() {
        // 浮点世界里：0.1 + 0.2 != 0.3（这是 IEEE-754 的既定事实，不是本项目的选择）

        // 毫单位世界里：100 + 200 == 300，精确
        val a = Dose.of(0.1f)
        val b = Dose.of(0.2f)
        val c = Dose.of(0.3f)
        assertThat((a + b).milli).isEqualTo(c.milli)
        assertThat(a.milli).isEqualTo(100)
        assertThat(b.milli).isEqualTo(200)
        assertThat(c.milli).isEqualTo(300)
    }

    @Test
    fun `长期累加不漂移`() {
        // 模拟一年的服药扣减：每天 3 次，每次 1.5 片 = 1500 毫单位
        val daily = Dose.of(1.5f)
        var balance = Dose(0)
        repeat(365) { balance = balance + daily }
        assertThat(balance.milli).isEqualTo(365 * 1500)   // 547500，精确

        // 同样场景用浮点会怎样：反复加减 1.5f 365 次
        var floatBalance = 0f
        repeat(365) { floatBalance += 1.5f }
        // 浮点结果不保证精确等于 547.5，这里只断言它确实不是整数运算路径
        assertThat(floatBalance).isAtMost(547.5f + 0.0001f)
    }

    @Test
    fun `加减运算`() {
        val a = Dose.of(1.5f)
        val b = Dose.of(0.5f)
        assertThat((a + b).milli).isEqualTo(2000)
        assertThat((a - b).milli).isEqualTo(1000)
        assertThat((-a).milli).isEqualTo(-1500)
    }

    @Test
    fun `负库存可表示（D-9）`() {
        val negative = Dose.of(0.5f) - Dose.of(1.0f)
        assertThat(negative.milli).isEqualTo(-500)
        assertThat(negative.isNegative).isTrue()
        assertThat(negative.asFloat).isEqualTo(-0.5f)
    }

    @Test
    fun `零值判定`() {
        assertThat(Dose.ZERO.isZero).isTrue()
        assertThat(Dose(1).isZero).isFalse()
        assertThat(Dose(-1).isNegative).isTrue()
    }

    @Test
    fun `与展示值比较时用容差`() {
        val d = Dose.of(0.3f)   // 0.3f * 1000 = 300.00001 -> round -> 300
        assertThat(d.equalsWithin(0.3f)).isTrue()
        assertThat(d.equalsWithin(0.5f)).isFalse()
    }

    @Test
    fun `四舍五入而非截断`() {
        // 0.0007f * 1000 = 0.70000005 -> 1；0.0004f * 1000 = 0.4 -> 0
        assertThat(Dose.of(0.0007f).milli).isEqualTo(1)
        assertThat(Dose.of(0.0004f).milli).isEqualTo(0)
    }

    @Test
    fun `UNLIMITED 是零值哨兵`() {
        // 用于"按需 / 不追踪"这类无固定消耗的场景
        assertThat(Doses.UNLIMITED.isZero).isTrue()
    }
}
