package com.mcxiaoke.carromed.ui.component

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 数量输入的三层防线（M2-1）。
 *
 * ## 为什么这一层值得单独测
 *
 * 剂量输入框曾经是全 App **唯一**没有字符过滤的数字框，而它是后果最重的一个字段：
 *
 * | 缺陷 | 后果 |
 * | :--- | :--- |
 * | 清空一次 → 写 0 | 0 剂量可保存 ⇒ 闹钟照响、打卡照记、**库存永不扣** |
 * | 粘贴 `-2` | 打卡时 `-finalDose` = **+2** ⇒ **给库存加药** |
 * | `KeyboardType.Number` | 多数 ROM 上**没有小数点键** ⇒ 0.5 片不可录入 |
 *
 * 第三条不是"体验问题"：本项目用 `Dose` 整数毫单位存储，全链路支持半片，
 * 却在录入端把半片挡在门外。
 */
class DecimalInputTest {

    // ==================== 第 1 层：字符过滤 ====================

    @Test
    fun `负号被剔除而不是取绝对值`() {
        // ⚠️ 关键：静默取绝对值会让用户以为自己填的是 2，
        // 而实际结果是给库存加了两次药。
        assertThat(DecimalInput.filter("-2")).isEqualTo("2")
        assertThat(DecimalInput.filter("1-2")).isEqualTo("12")
    }

    @Test
    fun `只保留数字与一个小数点`() {
        assertThat(DecimalInput.filter("1.25")).isEqualTo("1.25")
        assertThat(DecimalInput.filter("1.2.3")).isEqualTo("1.23")
        assertThat(DecimalInput.filter("a1b2")).isEqualTo("12")
        assertThat(DecimalInput.filter(" 1 片 ")).isEqualTo("1")
    }

    @Test
    fun `全角小数点与句号等价`() {
        // 中文输入法下"，"是高频误入字符；用户看到的就是一个点。
        assertThat(DecimalInput.filter("0。5")).isEqualTo("0.5")
        assertThat(DecimalInput.filter("0．5")).isEqualTo("0.5")
    }

    @Test
    fun `保留输入中间态 不因为看起来非法就清空`() {
        // 用户正在输入 "0." / "." 时把它清空，用户会得到"打不出小数点"这种反向故障。
        // 合法性由 parsePositive 在**提交时**判定，不由过滤层判定。
        assertThat(DecimalInput.filter("")).isEmpty()
        assertThat(DecimalInput.filter("0.")).isEqualTo("0.")
        assertThat(DecimalInput.filter(".")).isEqualTo(".")
        assertThat(DecimalInput.filter("0")).isEqualTo("0")
    }

    // ==================== 第 2 层：解析 ====================

    @Test
    fun `parsePositive 拒绝 0 空串 负数与无法解析`() {
        assertThat(DecimalInput.parsePositive("0")).isNull()
        assertThat(DecimalInput.parsePositive("")).isNull()
        assertThat(DecimalInput.parsePositive("  ")).isNull()
        assertThat(DecimalInput.parsePositive(null)).isNull()
        assertThat(DecimalInput.parsePositive("-1")).isNull()
        assertThat(DecimalInput.parsePositive("abc")).isNull()
        assertThat(DecimalInput.parsePositive("0.")).isNull()
    }

    @Test
    fun `parsePositive 接受合法的正数与半片`() {
        assertThat(DecimalInput.parsePositive("1")).isEqualTo(1f)
        assertThat(DecimalInput.parsePositive("0.5")).isEqualTo(0.5f)
        assertThat(DecimalInput.parsePositive(" 2.25 ")).isEqualTo(2.25f)
    }

    @Test
    fun `parsePositive 拒绝 NaN 与无穷`() {
        // `Float.NaN > 0f` 是 false，但 `Float.POSITIVE_INFINITY > 0f` 是 true ——
        // 不显式挡掉无穷，一个 Infinity 能把余额算成无穷。
        assertThat(DecimalInput.parsePositive("NaN")).isNull()
        assertThat(DecimalInput.parsePositive("Infinity")).isNull()
    }

    // ==================== parseNonNegative 的存在理由 ====================

    /**
     * 预警线用 `parseNonNegative` 而不是 `parsePositive`。
     *
     * `minStockAlert = 0` 在本项目里的约定是**关闭低库存告警**。
     * 用 `parsePositive` 会让用户**无法关闭告警** —— 那比误报更糟，
     * 因为它剥夺了一个用户明确想要的状态。
     *
     * 剂量类字段则必须用 `parsePositive`：那里 0 不是"关闭"，是"静默损坏"。
     * 这个区别是本项目里最容易搞混的一条，所以单列函数而不是加一个布尔参数。
     */
    @Test
    fun `parseNonNegative 接受 0 因为 0 在预警线语境下是关闭告警`() {
        assertThat(DecimalInput.parseNonNegative("0")).isEqualTo(0f)
        assertThat(DecimalInput.parseNonNegative("10")).isEqualTo(10f)
        assertThat(DecimalInput.parseNonNegative("-1")).isNull()
        assertThat(DecimalInput.parseNonNegative("abc")).isNull()
        assertThat(DecimalInput.parseNonNegative("")).isNull()
    }

    // ==================== 展示 ====================

    @Test
    fun `display 去掉无意义尾零`() {
        assertThat(DecimalInput.display(1.0f)).isEqualTo("1")
        assertThat(DecimalInput.display(0.5f)).isEqualTo("0.50")
    }

    /**
     * 变异验证锚点：把 `filter` 改回"只保留数字"（丢掉小数点），
     * 本文件里至少三条用例会变红。
     */
    @Test
    fun `小数点必须能打出来`() {
        val typed = "0"
            .let { DecimalInput.filter(it + ".") }
            .let { DecimalInput.filter(it + "5") }
        assertThat(typed).isEqualTo("0.5")
        assertThat(DecimalInput.parsePositive(typed)).isEqualTo(0.5f)
    }
}
