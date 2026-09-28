package com.mcxiaoke.carromed.ui.component

import java.util.Locale

/**
 * 数量的统一格式化。
 *
 * ## 为什么要抽这一层
 *
 * 改造前全库有**四套并存的**数量格式化写法（`TodayScreen.fmtQty` / `CabinetScreen` 内联
 * `%.2f` / `StatsScreen.fmt` 用 `%.1f` / `ProgressScreen` 原值直出），
 * 同一个数值在四个页面显示成四种样子（`1 片` / `1.0 片` / `1.00 片`）。
 * 这也是硬编码单位「片」的直接诱因 —— 各自拼字符串，没人统一。
 *
 * 统一后：
 * - 整数去掉无意义尾零（`6.0` → `6`）
 * - 非整数保留两位小数（`0.5` → `0.50`，`1.25` → `1.25`）
 * - 负数（账实不符，D-9 允许）正常显示，不加特殊符号
 */
object Quantity {

    /** 纯数量格式化，不带单位。 */
    fun fmt(value: Float): String =
        if (value == 0f) "0"
        else if (value % 1f == 0f) value.toInt().toString()
        else String.format(Locale.getDefault(), "%.2f", value)

    /**
     * 数量 + 单位，中间**恒有一个空格**（`6 片`、`5 ml`）。
     *
     * ## 曾经写错的地方
     *
     * 原本是：
     *
     * ```kotlin
     * return if (unit.first().isLetter()) "$q $unit" else "$q $unit"
     * ```
     *
     * 两个分支返回同一个字符串，`isLetter()` 的结果**从未被使用**，
     * 而 KDoc 声称「西文单位前留空格、中文单位紧贴」。
     *
     * 更糟的是那条 KDoc 描述**从来就不成立**：`'片'.isLetter()` 返回 `true`
     * （汉字在 Unicode 里是 Letter 类），所以中文单位走的一直也是"加空格"那一支。
     * 也就是说"中文紧贴"是文档里的**虚构行为**，实际全 App 一致地带空格。
     *
     * （这一点是被 `QuantityTest` 抓出来的：我在修这个"死代码"时，
     * 顺手按 KDoc 写了个"中文紧贴"的断言，结果测试立刻变红，
     * 才发现自己的假设是错的 —— 又一次凭想象下结论。）
     *
     * ## 现在的做法
     *
     * 规则收敛为**一条**：恒加空格。理由是中英文混排时数字与单位之间留空更易读，
     * 且与既有 UI（今日页 `1 片`、统计页 `2 片`）一致，不需要改任何截图。
     * 那个不起作用的 `isLetter()` 分支直接删掉 ——
     * 不起作用的分支比没有分支更糟：它让人以为"已按字形区分过"，
     * 于是下一个改动不会再去核实。
     */
    fun withUnit(value: Float, unit: String?): String = fmt(value) + unitSuffix(unit)

    /**
     * 展示用后缀，供"把单位与数值分开渲染"的场合使用（如统计页大数字）。
     * 返回 `" 片"` / `" ml"`，空单位返回空串。
     *
     * [withUnit] 直接复用它，所以"带空格这条规则"全 App 只有一份实现。
     */
    fun unitSuffix(unit: String?): String {
        if (unit.isNullOrBlank()) return ""
        return " $unit"
    }
}

