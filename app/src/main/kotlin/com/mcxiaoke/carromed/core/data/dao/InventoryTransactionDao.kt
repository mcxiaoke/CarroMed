package com.mcxiaoke.carromed.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mcxiaoke.carromed.core.data.entity.InventoryTransactionEntity
import kotlinx.coroutines.flow.Flow

/**
 * 不可变库存台账流水数据访问接口
 * 只支持追加写入与聚合查询，严格禁止 UPDATE 操作
 */
@Dao
interface InventoryTransactionDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(tx: InventoryTransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(txs: List<InventoryTransactionEntity>): List<Long>

    @Query("SELECT * FROM inventory_transactions WHERE medication_id = :medicationId ORDER BY id DESC")
    fun observeTransactionsForMedication(medicationId: Long): Flow<List<InventoryTransactionEntity>>

    @Query("SELECT * FROM inventory_transactions WHERE medication_id = :medicationId ORDER BY id DESC")
    suspend fun getTransactionsForMedication(medicationId: Long): List<InventoryTransactionEntity>

    @Query("SELECT * FROM inventory_transactions WHERE medication_id = :medicationId ORDER BY id DESC LIMIT 1")
    suspend fun getLatestTransaction(medicationId: Long): InventoryTransactionEntity?

    /**
     * 核心台账核算：计算该药物全量流水的代数和
     * 绝对不变式要求：SUM(change_amount) 必须严格等于 medications.current_stock
     */
    @Query("SELECT SUM(change_amount) FROM inventory_transactions WHERE medication_id = :medicationId")
    suspend fun getSumOfChanges(medicationId: Long): Float?

    @Query("SELECT * FROM inventory_transactions ORDER BY id DESC")
    suspend fun getAllTransactions(): List<InventoryTransactionEntity>

    @Query("DELETE FROM inventory_transactions")
    suspend fun deleteAllTransactions()
}
