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
 * 3. 守恒不变式：medications.current_stock 恒等于 SUM(change_amount)
 */
@Entity(
    tableName = "inventory_transactions",
    foreignKeys = [
        ForeignKey(
            entity = MedicationEntity::class,
            parentColumns = ["id"],
            childColumns = ["medication_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["medication_id"]),
        Index(value = ["created_at"])
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
    val changeAmount: Float, // 变动数值: -1.0f (打卡扣除), +30.0f (入库), +1.0f (撤销冲正)

    @ColumnInfo(name = "balance_after")
    val balanceAfter: Float, // 交易完成后的库存结余快照

    @ColumnInfo(name = "tx_type")
    val txType: TransactionType,

    @ColumnInfo(name = "note")
    val note: String? = null, // 备注 (如 "每日打卡扣减", "同仁堂药房采购30片", "误触打卡撤销")

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
