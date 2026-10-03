package com.mcxiaoke.carromed.ui.component

import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/**
 * 星期文案的**唯一实现**（OPEN-ISSUES L-9）。
 *
 * 此前 6 处各自为政：今日页日期条用字符串资源（`today_weekday_*`）、
 * 提醒设置用另一套资源（`rem_weekday_*`）、月历表头硬编码中文
 * `listOf("周一",…)`、库存页/进展页又各自调 `DayOfWeek.getDisplayName` ——
 * 同一个星期几在四类界面有四种来源，改一处漏五处。
 *
 * 统一走 [DayOfWeek.getDisplayName]：中文 SHORT =「周一」、NARROW =「一」，
 * 其他语言自动跟随系统（"Mon" / "M"），且省掉 7 条 × N 套字符串资源的维护。
 * 输出与原资源值逐字相同（中文环境），纯展示文案，不进走查断言锚点。
 */
object WeekLabels {

    /** 「周一」..「周日」/ "Mon".. — 月历表头、进展页矩阵、提醒设置的曜日汇总 */
    fun short(day: DayOfWeek, locale: Locale = Locale.getDefault()): String =
        day.getDisplayName(TextStyle.SHORT, locale)

    /** 「一」..「日」/ "M".. — 今日页日期条、库存页频次摘要（单字宽度） */
    fun narrow(day: DayOfWeek, locale: Locale = Locale.getDefault()): String =
        day.getDisplayName(TextStyle.NARROW, locale)
}
