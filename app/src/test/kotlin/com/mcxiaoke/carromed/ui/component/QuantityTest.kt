package com.mcxiaoke.carromed.ui.component

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 数量与单位的渲染规则（纯函数，不需要 Android 环境）。
 *
 * ## 这个测试存在的直接原因
 *
 * `Quantity.withUnit` 原本是：
 *
 * ```kotlin
 * return if (unit.first().isLetter()) "$q $unit" else "$q $unit"
 * ```
 *
 * **两个分支完全相同** —— `isLetter()` 的结果从未被使用，而 KDoc 声称
 * 「西文单位（`ml`）前留空格，中文单位紧贴」。
 *
 * 不起作用的分支比没有分支更糟：它让人以为"中文/西文已被区分"，
 * 后人便不会再去核实。
 *
 * ## 这个测试还抓到了我自己的一个错误结论
 *
 * 修掉那个死代码时，我按 KDoc 写下了断言「中文单位紧贴 ⇒ `withUnit(2f, "片") == "2片"`」，
 * 测试立刻变红 —— 因为 `'片'.first().isLetter()` 返回 **`true`**
 * （汉字在 Unicode 里是 Letter 类），中文走的一直也是"加空格"那一支。
 *
 * 也就是说"中文紧贴"是**文档里的虚构行为**，实际全 App 一致地带空格
 * （今日页 `1 片`、统计页 `2 片`）。
 *
 * **教训**：我又一次凭想象下了结论（假设汉字 `isLetter()` 为 false），
 * 而正确做法是先在 REPL 里敲一下 `'片'.isLetter()`。
 * 这与 A5 那条"硬编码的外部事实会腐烂"是同一类：
 * 没验证就写下来的断言，本质上是把猜测当成了规范。
 */
class QuantityTest {

    // ---------------- 纯数量 ----------------

    @Test
    fun `整数去掉无意义尾零`() {
        assertThat(Quantity.fmt(6f)).isEqualTo("6")
        assertThat(Quantity.fmt(1f)).isEqualTo("1")
        assertThat(Quantity.fmt(100f)).isEqualTo("100")
    }

    @Test
    fun `非整数保留两位小数`() {
        assertThat(Quantity.fmt(0.5f)).isEqualTo("0.50")
        assertThat(Quantity.fmt(1.25f)).isEqualTo("1.25")
    }

    @Test
    fun `零显示为整数 0 而非带两位小数`() {
        assertThat(Quantity.fmt(0f)).isEqualTo("0")
    }

    /** 负数（账实不符，D-9 允许）正常显示，不加特殊符号 */
    @Test
    fun `负数正常显示`() {
        assertThat(Quantity.fmt(-2.5f)).isEqualTo("-2.50")
        assertThat(Quantity.fmt(-3f)).isEqualTo("-3")
    }

    // ---------------- 单位后缀：恒加一个空格 ----------------

    @Test
    fun `中文单位前同样留一个空格`() {
        // ⚠️ 不是"紧贴"。`'片'.first().isLetter()` 返回 true（汉字属 Unicode Letter），
        // 所以 KDoc 里那句"中文紧贴"从来就没实现过，全 App 一直带空格。
        assertThat(Quantity.unitSuffix("片")).isEqualTo(" 片")
        assertThat(Quantity.unitSuffix("粒")).isEqualTo(" 粒")
    }

    @Test
    fun `西文单位前留空格`() {
        assertThat(Quantity.unitSuffix("ml")).isEqualTo(" ml")
        assertThat(Quantity.unitSuffix("mg")).isEqualTo(" mg")
    }

    /**
     * 希腊字母开头的单位（微克）与拉丁单位同规则。
     *
     * 这里原先写过一条「非字母开头不额外加空格」的断言，同样是**想当然**写下的
     * —— 真正的答案是恒加空格，所以本测试守的是那个答案。
     */
    @Test
    fun `希腊字母开头的单位与拉丁单位同规则`() {
        assertThat(Quantity.unitSuffix("μg")).isEqualTo(" μg")
    }

    @Test
    fun `空单位或 null 返回空串`() {
        assertThat(Quantity.unitSuffix(null)).isEmpty()
        assertThat(Quantity.unitSuffix("")).isEmpty()
        assertThat(Quantity.unitSuffix("   ")).isEmpty()
    }

    // ---------------- withUnit 必须与 unitSuffix 同规则 ----------------

    @Test
    fun `withUnit 与 unitSuffix 永远同规则`() {
        assertThat(Quantity.withUnit(2f, "片")).isEqualTo(Quantity.fmt(2f) + Quantity.unitSuffix("片"))
        assertThat(Quantity.withUnit(5f, "ml")).isEqualTo(Quantity.fmt(5f) + Quantity.unitSuffix("ml"))
    }

    /**
     * 死代码分支的**真正守门人**。
     *
     * 上一条只检查"两者一致"，而旧实现里 `withUnit` 与 `unitSuffix` 恰好也是一致的，
     * 所以它挡不住分叉。这条把**具体字面���**钉下来，才有约束力。
     */
    @Test
    fun `withUnit 的具体输出`() {
        assertThat(Quantity.withUnit(2f, "片")).isEqualTo("2 片")
        assertThat(Quantity.withUnit(5f, "ml")).isEqualTo("5 ml")
        assertThat(Quantity.withUnit(0.5f, "片")).isEqualTo("0.50 片")
    }

    @Test
    fun `withUnit 在单位为空时只给数字`() {
        assertThat(Quantity.withUnit(3f, null)).isEqualTo("3")
        assertThat(Quantity.withUnit(3f, "")).isEqualTo("3")
        assertThat(Quantity.withUnit(3f, "  ")).isEqualTo("3")
    }
}
