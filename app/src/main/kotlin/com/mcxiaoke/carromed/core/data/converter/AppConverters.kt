package com.mcxiaoke.carromed.core.data.converter

import androidx.room.TypeConverter
import com.mcxiaoke.carromed.core.data.model.PolicyType
import com.mcxiaoke.carromed.core.data.model.RecordStatus
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import com.mcxiaoke.carromed.core.data.model.TransactionType
import org.json.JSONArray

/**
 * Room 类型转换器集合
 */
class AppConverters {

    /** 旧的分隔符，仅用于**读**旧数据。见 [fromStringList] 的 KDoc。 */
    private val legacyListSeparator = "|||"

    @TypeConverter
    fun fromPolicyType(value: PolicyType?): String? = value?.name

    @TypeConverter
    fun toPolicyType(value: String?): PolicyType? =
        value?.let { runCatching { PolicyType.valueOf(it) }.getOrDefault(PolicyType.DAILY) }

    @TypeConverter
    fun fromSlotStatus(value: SlotStatus?): String? = value?.name

    @TypeConverter
    fun toSlotStatus(value: String?): SlotStatus? =
        value?.let { runCatching { SlotStatus.valueOf(it) }.getOrDefault(SlotStatus.PENDING) }

    @TypeConverter
    fun fromRecordStatus(value: RecordStatus?): String? = value?.name

    @TypeConverter
    fun toRecordStatus(value: String?): RecordStatus? =
        value?.let { runCatching { RecordStatus.valueOf(it) }.getOrDefault(RecordStatus.COMPLETED) }

    @TypeConverter
    fun fromTransactionType(value: TransactionType?): String? = value?.name

    @TypeConverter
    fun toTransactionType(value: String?): TransactionType? =
        value?.let { runCatching { TransactionType.valueOf(it) }.getOrDefault(TransactionType.TAKEN_DEDUCT) }

    /**
     * `List<String>` 的持久化。
     *
     * ## 为什么不用 `|||` 分隔（M5-7）
     *
     * 旧实现 `joinToString("|||")` / `split("|||")`。分��符本身是**用户数据里的普通字符**：
     * 写一条注意事项「计量单位 ||| mL」，存进去再读出来就变成**两条**：
     *
     * ```
     * ["计量单位 ||| mL"]  →  "计量单位 ||| mL"
     *                        →  ["计量单位 ", " mL"]     ← 静默损坏
     * ```
     *
     * 而且是**静默**的：没有异常、没有日志，用户下次打开看到两条莫名其妙的注意事项。
     * `precautions` 是自由文本，这种输入完全可达（从别处复制一段带竖线的说明）。
     *
     * 顺带修掉另一个更隐蔽的丢数据：旧 `split` 对 `"a|||"` 会产出 `["a", ""]`
     * —— 尾部空串变成一条**空白的注意事项**。
     *
     * ## 换成 JSON 数组
     *
     * `org.json.JSONArray` 是 Android 自带的，不需要新依赖。
     * 它对**任意字符串**都安全（内部自行转义），于是"内容里含分隔符"不再是一个类别。
     *
     * ⚠️ 读侧保留对旧 `|||` 格式的**向下兼容**：本项目未发布，但已有库可能写着旧格式。
     * 判据：新格式总是以 `[` 开头，旧的永远不会。判错方向选"当成旧格式"，
     * 因为旧格式本来是本项目自己写出来的。
     */
    @TypeConverter
    fun fromStringList(list: List<String>?): String? =
        list?.let { JSONArray().apply { list.forEach { put(it) } }.toString() }

    @TypeConverter
    fun toStringList(data: String?): List<String> {
        if (data.isNullOrEmpty()) return emptyList()
        if (data.startsWith("[")) {
            val arr = runCatching { JSONArray(data) }.getOrNull() ?: return emptyList()
            return (0 until arr.length()).mapNotNull { arr.optString(it, null) }
        }
        // 旧格式（`|||` 分隔）。`split` 会给尾随分隔符产出空串，必须过滤掉。
        return data.split(legacyListSeparator)
            .filter { it.isNotEmpty() }
    }

    @TypeConverter
    fun fromIntList(list: List<Int>?): String? =
        list?.joinToString(separator = ",")

    @TypeConverter
    fun toIntList(data: String?): List<Int> =
        if (data.isNullOrEmpty()) emptyList() else data.split(",").mapNotNull { it.trim().toIntOrNull() }
}
