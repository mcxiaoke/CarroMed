package com.mcxiaoke.carromed.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mcxiaoke.carromed.core.data.entity.DoseRecordEntity
import com.mcxiaoke.carromed.core.data.model.MedDoseSumRow
import kotlinx.coroutines.flow.Flow

/**
 * 服药事实历史记录数据访问接口
 *
 * ## 不可删除约束（不变量 I11）
 *
 * 本 DAO **刻意不提供任何 `@Delete` 或物理删除方法**。
 * 用户撤销打卡时，走的是 [markRevertedBySlot] 把事实状态改为 `REVERTED`，
 * 而不是 `DELETE FROM dose_records`。
 *
 * 原因：产品第二承诺是「记录真实 —— 吃过的药永不丢失、永不串改」，
 * `FINAL-PRODUCT` 场景 2 也要求"事实层追加 REVERT 修正…全程留痕"。
 * 物理删除还会让库存台账里指向该记录的 `record_id` 变成悬空引用。
 *
 * 删药品本身仍会级联清空（外键 `ON DELETE CASCADE`），那是用户的明确意图，与本约束不冲突。
 */
@Dao
interface DoseRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: DoseRecordEntity): Long

    /**
     * 撤销：把该槽位对应的服药事实标记为 `REVERTED`（保留记录，仅改状态）。
     * 返回受影响行数。
     */
    @Query("UPDATE dose_records SET status = 'REVERTED' WHERE slot_id = :slotId AND status != 'REVERTED'")
    suspend fun markRevertedBySlot(slotId: Long): Int

    @Query("SELECT * FROM dose_records WHERE id = :id")
    suspend fun getRecordById(id: Long): DoseRecordEntity?

    @Query("SELECT * FROM dose_records WHERE slot_id = :slotId ORDER BY id ASC LIMIT 1")
    suspend fun getRecordBySlotId(slotId: Long): DoseRecordEntity?

    /** 该槽位的全部事实（含已撤销），用于审计与"连点几次"排查 */
    @Query("SELECT * FROM dose_records WHERE slot_id = :slotId ORDER BY id ASC")
    suspend fun getAllRecordsBySlotId(slotId: Long): List<DoseRecordEntity>

    /** 一批槽位的已完成事实，供列表页一次性取回（避免 N+1） */
    @Query(
        """
        SELECT * FROM dose_records
        WHERE slot_id IN (:slotIds) AND status = 'COMPLETED'
        """
    )
    suspend fun getCompletedRecordsForSlots(slotIds: List<Long>): List<DoseRecordEntity>

    @Query("SELECT * FROM dose_records WHERE medication_id = :medicationId ORDER BY actual_ts DESC")
    suspend fun getRecordsForMedication(medicationId: Long): List<DoseRecordEntity>

    @Query("SELECT * FROM dose_records WHERE actual_ts BETWEEN :startTs AND :endTs ORDER BY actual_ts DESC")
    fun observeRecordsInRange(startTs: Long, endTs: Long): Flow<List<DoseRecordEntity>>

    @Query("SELECT * FROM dose_records WHERE actual_ts BETWEEN :startTs AND :endTs ORDER BY actual_ts DESC")
    suspend fun getRecordsInRange(startTs: Long, endTs: Long): List<DoseRecordEntity>

    @Query("SELECT SUM(dose_taken) FROM dose_records WHERE medication_id = :medicationId AND status = 'COMPLETED' AND actual_ts BETWEEN :startTs AND :endTs")
    suspend fun getSumDoseTakenForMedication(medicationId: Long, startTs: Long, endTs: Long): Float?

    @Query("SELECT COUNT(*) FROM dose_records WHERE medication_id = :medicationId AND status = 'COMPLETED' AND actual_ts BETWEEN :startTs AND :endTs")
    suspend fun countDoseRecordsForMedication(medicationId: Long, startTs: Long, endTs: Long): Int

    @Query("SELECT * FROM dose_records ORDER BY actual_ts ASC")
    suspend fun getAllRecords(): List<DoseRecordEntity>

    /**
     * 区间内按药品汇总的 **实际消耗剂量** (仅 COMPLETED 事实)。
     * 与依从率口径不同：消耗量描述"真实吃掉了多少"，按实际服药时刻归属。
     */
    @Query(
        """
        SELECT medication_id AS medId, SUM(dose_taken) AS total
        FROM dose_records
        WHERE status = 'COMPLETED' AND actual_ts BETWEEN :startTs AND :endTs
        GROUP BY medication_id
        ORDER BY total DESC
        """
    )
    fun observeDoseSumByMedicationInRange(
        startTs: Long,
        endTs: Long
    ): Flow<List<MedDoseSumRow>>

    @Query(
        """
        SELECT medication_id AS medId, SUM(dose_taken) AS total
        FROM dose_records
        WHERE status = 'COMPLETED' AND actual_ts BETWEEN :startTs AND :endTs
        GROUP BY medication_id
        ORDER BY total DESC
        """
    )
    suspend fun getDoseSumByMedicationInRange(
        startTs: Long,
        endTs: Long
    ): List<MedDoseSumRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<DoseRecordEntity>): List<Long>

    /**
     * ⚠️ **唯一的例外**：整库清空，仅供 `DataExporter.importBackup` 的"覆盖式恢复"使用。
     *
     * 恢复是用户明确发起的破坏性操作（且恢复前会做本地快照 + 二次确认），
     * 与"撤销打卡不得抹除事实"是两条不同的语义路径。
     * 除此之外，本 DAO 不提供任何按 id / 按 slot 的删除方法。
     */
    @Query("DELETE FROM dose_records")
    suspend fun deleteAllRecords()
}
