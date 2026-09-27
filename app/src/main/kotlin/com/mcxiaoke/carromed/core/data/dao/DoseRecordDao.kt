package com.mcxiaoke.carromed.core.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mcxiaoke.carromed.core.data.entity.DoseRecordEntity
import kotlinx.coroutines.flow.Flow

/**
 * 服药事实历史记录数据访问接口
 */
@Dao
interface DoseRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: DoseRecordEntity): Long

    @Delete
    suspend fun delete(record: DoseRecordEntity)

    @Query("DELETE FROM dose_records WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM dose_records WHERE id = :id")
    suspend fun getRecordById(id: Long): DoseRecordEntity?

    @Query("SELECT * FROM dose_records WHERE slot_id = :slotId LIMIT 1")
    suspend fun getRecordBySlotId(slotId: Long): DoseRecordEntity?

    @Query("DELETE FROM dose_records WHERE slot_id = :slotId")
    suspend fun deleteBySlotId(slotId: Long): Int

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<DoseRecordEntity>): List<Long>

    @Query("DELETE FROM dose_records")
    suspend fun deleteAllRecords()
}
