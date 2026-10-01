package com.mcxiaoke.carromed.core.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mcxiaoke.carromed.core.data.model.TransactionType

/**
 * 不可变库存台账流水表 (InventoryTransaction)
 * 核心设计：
 * 1. 纯 Append-Only 只增账本，禁止任何 UPDATE/DELETE
 * 2. 负数代表消耗扣减，正数代表补药或误触撤销退回（冲正）
 * 3. 守恒不变式：**账面余额 := SUM(change_amount)**（余额只由本表聚合而来，
 *    `medications` 表不存任何余额列）
 */
@Entity(
    tableName = "inventory_transactions",
    foreignKeys = [
        ForeignKey(
            entity = MedicationEntity::class,
            parentColumns = ["id"],
            childColumns = ["medication_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["medication_id"]),
        Index(value = ["created_at"]),
        // record_id 有真实过滤查询（getSumOfChangeByRecordId：改剂量 / 撤销时
        // 按"这条事实欠多少扣"聚合流水），且改剂量是高频路径（osbf P3-4）
        Index(value = ["record_id"])
    ]
)
data class InventoryTransactionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "medication_id")
    val medicationId: Long,

    @ColumnInfo(name = "record_id")
    val recordId: Long? = null, // 关联的 dose_records.id (补药入库或盘点时为 null)

    @ColumnInfo(name = "change_amount")
    val changeAmount: Int, // 变动数值，整数毫单位: -1000 (打卡扣除), +30000 (入库), +1000 (撤销冲正)

    @ColumnInfo(name = "balance_after")
    /** 交易完成后的库存结余**快照**（展示用，非权威值）。
     *  权威值恒为 SUM(change_amount)，见 InventoryTransactionDao.getSumOfChanges。
     *  minSdk 26 的 SQLite 3.18 不支持窗口函数，无法生成累计和，故保留本列。 */
    val balanceAfter: Int,

    @ColumnInfo(name = "tx_type")
    val txType: TransactionType,

    @ColumnInfo(name = "note")
    val note: String? = null, // 备注。程序化流水只放数据载荷（数量/批次/用户附加文本），文案由 noteKey 资源化；用户自由文本原样

    /** schema v8：程序化备注的分类 key（[LedgerNoteKey] name），null = 用户自由文本。 */
    @ColumnInfo(name = "note_key")
    val noteKey: String? = null,

    /** schema v2：采购批次号 (仅 REFILL 入库流水有意义) */
    @ColumnInfo(name = "batch_number")
    val batchNumber: String? = null,

    /** schema v2：本批入库药品的有效期至 (yyyy-MM-dd)，用于临期提醒 */
    @ColumnInfo(name = "expiry_date")
    val expiryDate: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
