package com.mcxiaoke.carromed.core.data.converter

import androidx.room.TypeConverter
import com.mcxiaoke.carromed.core.data.model.PolicyType
import com.mcxiaoke.carromed.core.data.model.RecordStatus
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import com.mcxiaoke.carromed.core.data.model.TransactionType
import com.mcxiaoke.carromed.core.domain.AppLog
import org.json.JSONArray

/**
 * Room 类型转换器集合
 */
class AppConverters {

    /** 旧的分隔符，仅用于**读**旧数据。见 [fromStringList] 的 KDoc。 */
    private val legacyListSeparator = "|||"

    /**
     * 未知枚举值的降级必须**留痕**（DB B-08 / sba P2-12）。
     *
     * 静默降级的恶劣之处：枚举改名（重构顺手 rename）会让**所有历史行**当场
     * 变成降级值 —— 槽位全部回到 PENDING、流水全部变扣减，用户毫无察觉。
     * 兜底必须保留（抛错会让整个库打不开，比静默更糟），但至少要留一条
     * `AppLog.w`，诊断导出里能对上"数据是什么时候变形的"。
     */
    private inline fun <reified T : Enum<T>> parseEnum(raw: String, fallback: T): T =
        runCatching { enumValueOf<T>(raw) }.getOrElse {
            AppLog.w(
                TAG,
                "unknown enum value \"$raw\" for ${T::class.simpleName}, falling back to $fallback"
            )
            fallback
        }

    @TypeConverter
    fun fromPolicyType(value: PolicyType?): String? = value?.name

    @TypeConverter
    fun toPolicyType(value: String?): PolicyType? =
        value?.let { parseEnum(it, PolicyType.DAILY) }

    @TypeConverter
    fun fromSlotStatus(value: SlotStatus?): String? = value?.name

    @TypeConverter
    fun toSlotStatus(value: String?): SlotStatus? =
        value?.let { parseEnum(it, SlotStatus.PENDING) }

    @TypeConverter
    fun fromRecordStatus(value: RecordStatus?): String? = value?.name

    @TypeConverter
    fun toRecordStatus(value: String?): RecordStatus? =
        value?.let { parseEnum(it, RecordStatus.COMPLETED) }

    @TypeConverter
    fun fromTransactionType(value: TransactionType?): String? = value?.name

    @TypeConverter
    fun toTransactionType(value: String?): TransactionType? =
        value?.let { parseEnum(it, TransactionType.TAKEN_DEDUCT) }

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
    fun toIntList(data: String?): List<Int> {
        if (data.isNullOrEmpty()) return emptyList()
        val tokens = data.split(",").filter { it.isNotBlank() }
        val values = tokens.mapNotNull { it.trim().toIntOrNull() }
        // 写路径（fromIntList + 服务层 1..7 校验）不会产出脏 token，这里出现丢 token
        // 只可能是库被人改过或旧版本残留 —— 静默丢会让 daysOfWeek 变形且无迹可循（L-7）。
        if (values.size < tokens.size) {
            AppLog.w(TAG, "toIntList dropped invalid token(s): \"$data\"")
        }
        return values
    }

    private companion object {
        const val TAG = "AppConverters"
    }
}
