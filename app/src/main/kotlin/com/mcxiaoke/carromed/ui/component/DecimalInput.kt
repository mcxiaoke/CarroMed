package com.mcxiaoke.carromed.ui.component

/**
 * 数量输入框的字符过滤与解析。
 *
 * ## 存在的理由（M2-1，P0）
 *
 * 剂量输入框此前是全 App **唯一**没有字符过滤的数字框，而它恰好是全 App
 * **后果最重**的一个字段：清空一次就写 0，0 剂量可以保存 ⇒ 闹钟照响、打卡照记、
 * **库存永不扣**；粘贴一个负数则让台账的符号翻转 ⇒ 打卡反而**加**库存。
 * 一个输入框能同时做到"漏记"和"虚增"两条路径，这在记账系统里是致命组合。
 *
 * 更基础的问题是：数字框的 `value` 是 `Float` 而不是 `String`。
 * 数值类型天然表达不了"用户正在输入 `0.`"这个中间态 ——
 * 它会被立刻归一成 `0`，于是**小数点根本打不出来**（0.5 片不可录入）。
 * `defaultDose` / `minStockAlert` 早就改成了 `String`，剂量框是漏网的。
 *
 * ## 三层防线
 *
 * | 层 | 位置 | 作用 |
 * | :--- | :--- | :--- |
 * | 1 过滤 | [filter] | 拒绝负号 / 字母 / 第二个小数点 —— 错误根本不进状态 |
 * | 2 解析 | [parsePositive] | 提交前把 0、空串、无法解析都判为非法 |
 * | 3 领域层 | `saveReminderPolicy` 的 `require` | 任何未来入口（备份导入、Widget、手表）都绕不过 |
 *
 * 三层缺一不可：只有 1 层的话，状态仍能被别人构造成 0；
 * 只有 2 层的话，用户会看到"打不出小数点"这种莫名其妙的现象。
 */
object DecimalInput {

    /**
     * 过滤成"最多一个小数点的非负数字串"。
     *
     * 刻意**保留** `""` / `"0"` / `"0."` / `"."` 这类中间态 ——
     * 输入框在用户输完之前显示它们是正常的，合法性由 [parsePositive] 在提交时判定。
     * 在这里就把它们清空，用户会得到"打不出 0"这种反向故障。
     *
     * 负号被剔除而不是"取绝对值"：静默把 `-2` 变成 `2` 会让用户以为自己填的是 2，
     * 而实际结果是加了两次库存。
     */
    fun filter(input: String): String {
        val sb = StringBuilder(input.length)
        var seenDot = false
        for (ch in input) {
            when {
                ch.isDigit() -> sb.append(ch)
                (ch == '.' || ch == '。' || ch == '．') && !seenDot -> {
                    seenDot = true
                    sb.append('.')
                }
                else -> Unit   // 负号、字母、空格、一切非数字非小数点：丢弃
            }
        }
        return sb.toString()
    }

    /**
     * 解析为**严格大于 0** 的值，否则返回 null。
     *
     * `> 0` 而非 `>= 0`：0 剂量能保存是一条完整的数据损坏路径
     * （闹钟照响、打卡照记、库存永不扣）。
     */
    fun parsePositive(raw: String?): Float? {
        val v = raw?.trim()?.toFloatOrNull() ?: return null
        return if (v > 0f && v.isFinite()) v else null
    }

    /**
     * 解析为**大于等于 0** 的值，否则返回 null。
     *
     * 用于那些"0 有明确业务含义"的字段。当前唯一的调用点是低库存预警线：
     * `minStockAlert = 0` 在本项目里的约定是**关闭低库存告警**
     * （见 `ReminderSettingsEntity` / `MedicationEntity` 的 KDoc），
     * 把它判成"非法"会让用户**无法关闭告警** —— 那比误报更糟。
     *
     * 剂量类字段则必须用 [parsePositive]：那里 0 不是"关闭"，是"静默损坏"。
     * 这个区别是本项目里最容易搞混的一条，所以单列一个函数而不是加参数。
     */
    fun parseNonNegative(raw: String?): Float? {
        val v = raw?.trim()?.toFloatOrNull() ?: return null
        return if (v >= 0f && v.isFinite()) v else null
    }

    /** 展示用：把数值转回输入框文本（`1.0` → `"1"`，`0.5` → `"0.5"`） */
    fun display(value: Float): String = Quantity.fmt(value)
}
