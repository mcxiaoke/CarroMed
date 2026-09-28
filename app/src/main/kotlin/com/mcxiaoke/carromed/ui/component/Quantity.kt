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
     * 数量 + 单位。
     *
     * 西文单位（`ml`）前留一个空格更易读（`5 ml`），中文单位紧贴（`6 片`）。
     * 这与统计页 Hero 区的 `unitSuffix` 是同一套规则。
     */
    fun withUnit(value: Float, unit: String?): String {
        val q = fmt(value)
        if (unit.isNullOrBlank()) return q
        return if (unit.first().isLetter()) "$q $unit" else "$q $unit"
    }

    /**
     * 展示用后缀，供"把单位与数值分开渲染"的场合使用（如统计页大数字）。
     * 中文单位返回空串（直接紧贴），西文单位返回 " ml"。
     */
    fun unitSuffix(unit: String?): String {
        if (unit.isNullOrBlank()) return ""
        return if (unit.first().isLetter()) " $unit" else unit
    }
}
