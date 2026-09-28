package com.mcxiaoke.carromed.core.data

import com.mcxiaoke.carromed.core.data.model.PolicyType
import com.mcxiaoke.carromed.core.data.model.RecordStatus
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import com.mcxiaoke.carromed.core.data.model.TransactionType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 备份文件格式（A4：`kotlinx.serialization`）。
 *
 * ## 为什么不用 `org.json` 手工拼
 *
 * 旧实现是约 150 行 `JSONObject.put` + 约 150 行 `optString`/`optInt` 解析：
 *
 * - **无 schema**：字段名是散落的字符串字面量，导出与导入各写一遍；
 * - **默认值散落**：`optInt("intervalDays", 1)` 这类默认值在几十处各写一次；
 * - **改字段必漏**：这不是理论风险 —— 旧实现的 `appSettings` 就只导出了
 *   `key` 与 `value`，`AppSettingEntity.updatedAt` **根本没进备份，恢复即永久丢失**，
 *   而且没有任何测试会发现它。
 *
 * 改成 `@Serializable data class` 后，字段名与类型由编译器检查，
 * 且有了"导出 → 导入 → 再导出"这条天然可测的往返链路（见 `BackupRoundTripTest`）。
 *
 * ## 字段名刻意与 V1（旧的 org.json 格式）保持一致
 *
 * 这样**旧备份文件仍能被读**。唯一的 wire 差异是本文件多一个 `schemaVersion` 字段，
 * 而 `Json { ignoreUnknownKeys = true }` 会忽略它。值不同的备份一律拒绝，
 * 绝不"尽力而为"地部分恢复 —— 部分恢复比拒绝更危险。
 *
 * ## 毫单位字段的命名
 *
 * 剂量、库存、结余一律带 `Milli` 后缀（1 单位 = 1000 毫单位，D-7）。
 * **数据库列名不带这个后缀**（在库上下文里它是显然的），但备份是给人看的文件，
 * 带上后缀能避免"这个数是 1 还是 1000"这种误读。
 */
@Serializable
data class BackupFile(
    /** 标识文件归属，防止把任意 JSON 当备份恢复进来 */
    val app: String,
    /** wire 上是数字，与 V1 兼容；语义由 [BackupFormatVersion] 解释 */
    val formatVersion: Int,
    val exportedAt: Long,
    val exportedAtText: String,
    val medications: List<MedicationBackup> = emptyList(),
    val reminderSettings: List<ReminderSettingsBackup> = emptyList(),
    val schedulePolicies: List<SchedulePolicyBackup> = emptyList(),
    val policyTimes: List<PolicyTimeBackup> = emptyList(),
    val doseSlots: List<DoseSlotBackup> = emptyList(),
    val doseRecords: List<DoseRecordBackup> = emptyList(),
    val inventoryTransactions: List<InventoryTransactionBackup> = emptyList(),
    val appSettings: List<AppSettingBackup> = emptyList()
)

@Serializable
data class MedicationBackup(
    val id: Long,
    val name: String,
    val alias: String? = null,
    val category: String = "常备药",
    val form: String = "片剂",
    val unit: String = "片",
    val colorHex: String = "#2563EB",
    val iconName: String = "pill",
    @SerialName("defaultDoseMilli") val defaultDose: Int = 1000,
    val description: String = "",
    val precautions: List<String> = emptyList(),
    val noticeShort: String = "",
    /**
     * 台账聚合出的当前余额（毫单位）。
     *
     * ⚠️ **仅供人工核对，不参与恢复** —— 库存余额是
     * `SUM(inventory_transactions.change_amount)` 的派生值，
     * 恢复流水即恢复余额。留着它是为了让用户打开备份文件就能核对账。
     */
    val stockMilli: Int = 0,
    @SerialName("minStockAlertMilli") val minStockAlert: Int = 0,
    val isStockTracked: Boolean = false,
    val expiryDate: String = "",
    val isArchived: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0
)

@Serializable
data class ReminderSettingsBackup(
    val medicationId: Long,
    val isCriticalReminder: Boolean = false,
    val snoozeMinutes: Int = 0,
    val advanceMinutes: Int = 0,
    /**
     * 三态必须原样保留：`null` = 未暂停 / `""` = 无限期 / `"2026-10-15"` = 暂停至该日含。
     * 把 `""` 归一成 `null` 会让"无限期暂停"变成"未暂停" —— 恢复后立刻开始响铃。
     */
    val pausedUntil: String? = null
)

@Serializable
data class SchedulePolicyBackup(
    val id: Long,
    val medicationId: Long,
    val policyType: PolicyType = PolicyType.DAILY,
    val intervalDays: Int = 1,
    /** 1 = 周一 .. 7 = 周日 */
    val daysOfWeek: List<Int> = emptyList(),
    val cycleOnDays: Int = 0,
    val cycleOffDays: Int = 0,
    val startDate: String,
    val endDate: String? = null,
    val isActive: Boolean = true,
    val version: Int = 1,
    val createdAt: Long = 0
)

@Serializable
data class PolicyTimeBackup(
    val id: Long,
    val policyId: Long,
    /** "HH:mm"（24 小时制） */
    val timeOfDay: String,
    @SerialName("doseAmountMilli") val doseAmount: Int = 1000,
    val label: String = "服药时段",
    val sortOrder: Int = 0
)

@Serializable
data class DoseSlotBackup(
    val id: Long,
    val medicationId: Long,
    val policyId: Long,
    val scheduledDate: String,
    val scheduledTime: String,
    val scheduledTs: Long,
    @SerialName("doseAmountMilli") val doseAmount: Int = 1000,
    val status: SlotStatus = SlotStatus.PENDING,
    val actualTakenTs: Long? = null,
    val snoozeUntilTs: Long? = null,
    val createdAt: Long = 0
)

@Serializable
data class DoseRecordBackup(
    val id: Long,
    /** `null` = 手动补录（非计划打卡） */
    val slotId: Long? = null,
    val medicationId: Long,
    val actualTs: Long,
    @SerialName("doseTakenMilli") val doseTaken: Int = 1000,
    val status: RecordStatus = RecordStatus.COMPLETED,
    val isRetrospective: Boolean = false,
    val note: String? = null,
    val createdAt: Long = 0
)

@Serializable
data class InventoryTransactionBackup(
    val id: Long,
    val medicationId: Long,
    val recordId: Long? = null,
    @SerialName("changeAmountMilli") val changeAmount: Int = 0,
    @SerialName("balanceAfterMilli") val balanceAfter: Int = 0,
    val txType: TransactionType,
    val note: String? = null,
    val batchNumber: String? = null,
    val expiryDate: String? = null,
    val createdAt: Long = 0
)

@Serializable
data class AppSettingBackup(
    val key: String,
    val value: String,
    /**
     * ⚠️ 旧实现**根本没有导出这一列**，恢复即永久丢失，且无任何测试发现。
     * 给默认值 `0` 是为了让 V1 备份仍能解析，但新导出的文件一定会带上真实值 ——
     * `BackupFieldPreservationTest` 用一条断言钉住这件事。
     */
    val updatedAt: Long = 0
)

/**
 * 备份格式版本。
 *
 * ## 为什么 wire 上是 Int 而这里是枚举
 *
 * 旧格式的 `formatVersion` 是数字 `1`。若直接把字段改成枚举，
 * `kotlinx` 会写出 `"V1"` 字符串，**所有旧备份立刻全部不可读**。
 * 所以 wire 保持 Int，类型化放在这一层 —— 需要做判断的地方（能不能读、怎么提示）
 * 走枚举，序列化时只写数字。
 */
enum class BackupFormatVersion(val code: Int) {
    /** org.json 手工拼接格式，字段名与 [BackupFile] 完全一致，可直接读 */
    V1(1),

    /** `kotlinx.serialization` 格式 */
    V2(2);

    companion object {
        val CURRENT = V2

        fun from(code: Int): BackupFormatVersion? = entries.firstOrNull { it.code == code }
    }
}

/** 版本判定结果 —— 面向用户的文案与可读性判断都在这里收口 */
sealed class BackupCompatibility {
    data class Ok(val version: BackupFormatVersion) : BackupCompatibility()
    data class Unsupported(val code: Int) : BackupCompatibility()
}
