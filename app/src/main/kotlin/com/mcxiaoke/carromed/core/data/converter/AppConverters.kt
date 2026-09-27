package com.mcxiaoke.carromed.core.data.converter

import androidx.room.TypeConverter
import com.mcxiaoke.carromed.core.data.model.PolicyType
import com.mcxiaoke.carromed.core.data.model.RecordStatus
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import com.mcxiaoke.carromed.core.data.model.TransactionType

/**
 * Room 类型转换器集合
 */
class AppConverters {

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

    @TypeConverter
    fun fromStringList(list: List<String>?): String? =
        list?.joinToString(separator = "|||")

    @TypeConverter
    fun toStringList(data: String?): List<String> =
        if (data.isNullOrEmpty()) emptyList() else data.split("|||")

    @TypeConverter
    fun fromIntList(list: List<Int>?): String? =
        list?.joinToString(separator = ",")

    @TypeConverter
    fun toIntList(data: String?): List<Int> =
        if (data.isNullOrEmpty()) emptyList() else data.split(",").mapNotNull { it.trim().toIntOrNull() }
}
