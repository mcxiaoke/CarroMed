package com.mcxiaoke.carromed.core.data.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mcxiaoke.carromed.core.data.entity.InventoryTransactionEntity
import kotlinx.coroutines.flow.Flow

/**
 * 不可变库存台账流水数据访问接口
 *
 * **只支持追加写入与聚合查询，接口层面就不存在 UPDATE / 按 id 删除方法** ——
 * 这是"库存账本不可篡改"这条产品承诺的物理保证，比靠 code review 盯着强得多。
 *
 * 唯一的例外是 [deleteAllTransactions]：仅供 `DataExporter.importBackup` 的
 * 覆盖式恢复使用（用户明确发起的整库替换）。
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
     * 核心台账核算：计算该药物全量流水的代数和。
     *
     * ## 这是库存余额的**唯一权威定义**
     *
     * > `balance(medicationId) := COALESCE(SUM(change_amount), 0)`
     *
     * `medications` 表不再存储 `current_stock`（见 `MedicationEntity`）。
     * 任何"修改库存余额"的需求都必须表达为"追加一条流水"，
     * 由 `DoseTrackingService` 统一执行。这样守恒不再是需要维护的约束，而是恒等式。
     *
     * 未建账的药品返回 `null`（而非 0），以便调用方区分"从未有流水"与"流水恰好抵消为 0"。
     *
     * 返回值是**整数毫单位**（1 片 = 1000），与流水列同口径 —— 全程无浮点（D-7）。
     */
    @Query("SELECT SUM(change_amount) FROM inventory_transactions WHERE medication_id = :medicationId")
    suspend fun getSumOfChanges(medicationId: Long): Int?

    /**
     * 批量取回全部药品的账面余额（一次查询，避免 N+1）。
     * 返回 `medicationId -> balance` 映射，供列表页与导出使用。
     */
    @Query("SELECT medication_id, SUM(change_amount) AS balance FROM inventory_transactions GROUP BY medication_id")
    suspend fun getAllBalances(): List<BalanceRow>

    @Query("SELECT * FROM inventory_transactions ORDER BY id DESC")
    suspend fun getAllTransactions(): List<InventoryTransactionEntity>

    /**
     * ⚠️ 唯一例外：整库清空，仅供 `DataExporter.importBackup` 的覆盖式恢复使用。
     * 与"日常记账不得改账"是两条不同语义路径。
     */
    @Query("DELETE FROM inventory_transactions")
    suspend fun deleteAllTransactions()

    /** [getAllBalances] 的行载体 */
    data class BalanceRow(
        @ColumnInfo(name = "medication_id") val medicationId: Long,
        @ColumnInfo(name = "balance") val balance: Int
    )
}
