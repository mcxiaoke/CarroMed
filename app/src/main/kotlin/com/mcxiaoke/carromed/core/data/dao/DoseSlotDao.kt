package com.mcxiaoke.carromed.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mcxiaoke.carromed.core.data.entity.DoseSlotEntity
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import com.mcxiaoke.carromed.core.data.model.SlotStatusCountRow
import kotlinx.coroutines.flow.Flow

/**
 * 服药排班槽位数据访问接口
 */
@Dao
interface DoseSlotDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(slot: DoseSlotEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(slots: List<DoseSlotEntity>): List<Long>

    @Update
    suspend fun update(slot: DoseSlotEntity)

    @Query("SELECT * FROM dose_slots WHERE id = :id")
    suspend fun getSlotById(id: Long): DoseSlotEntity?

    @Query("SELECT * FROM dose_slots WHERE id = :id")
    fun observeSlotById(id: Long): Flow<DoseSlotEntity?>

    @Query("SELECT * FROM dose_slots WHERE scheduled_date = :dateStr ORDER BY scheduled_ts ASC")
    fun observeSlotsForDate(dateStr: String): Flow<List<DoseSlotEntity>>

    @Query("SELECT * FROM dose_slots WHERE scheduled_date = :dateStr ORDER BY scheduled_ts ASC")
    suspend fun getSlotsForDate(dateStr: String): List<DoseSlotEntity>

    @Query("SELECT * FROM dose_slots WHERE scheduled_date BETWEEN :startDate AND :endDate ORDER BY scheduled_date ASC, scheduled_ts ASC")
    fun observeSlotsInRange(startDate: String, endDate: String): Flow<List<DoseSlotEntity>>

    @Query("SELECT * FROM dose_slots WHERE scheduled_date BETWEEN :startDate AND :endDate ORDER BY scheduled_date ASC, scheduled_ts ASC")
    suspend fun getSlotsInRange(startDate: String, endDate: String): List<DoseSlotEntity>

    @Query("SELECT * FROM dose_slots WHERE status = 'PENDING' AND scheduled_ts >= :fromTs ORDER BY scheduled_ts ASC")
    suspend fun getPendingSlotsAfter(fromTs: Long): List<DoseSlotEntity>

    @Query("SELECT * FROM dose_slots WHERE medication_id = :medicationId AND status = 'PENDING' AND scheduled_ts >= :fromTs ORDER BY scheduled_ts ASC")
    suspend fun getPendingSlotsForMedicationAfter(medicationId: Long, fromTs: Long): List<DoseSlotEntity>

    @Query("UPDATE dose_slots SET status = :status, actual_taken_ts = :actualTs WHERE id = :slotId")
    suspend fun updateStatus(slotId: Long, status: SlotStatus, actualTs: Long? = null)

    @Query("UPDATE dose_slots SET status = 'SNOOZED', snooze_until_ts = :snoozeUntilTs WHERE id = :slotId")
    suspend fun snoozeSlot(slotId: Long, snoozeUntilTs: Long)

    @Query("DELETE FROM dose_slots WHERE medication_id = :medicationId AND status = 'PENDING' AND scheduled_ts >= :fromTs")
    suspend fun deleteFuturePendingSlots(medicationId: Long, fromTs: Long): Int

    @Query("SELECT COUNT(*) FROM dose_slots WHERE scheduled_date = :dateStr AND status = 'COMPLETED'")
    suspend fun countCompletedSlotsForDate(dateStr: String): Int

    @Query("SELECT COUNT(*) FROM dose_slots WHERE scheduled_date = :dateStr")
    suspend fun countTotalSlotsForDate(dateStr: String): Int

    @Query("SELECT * FROM dose_slots ORDER BY id ASC")
    suspend fun getAllSlots(): List<DoseSlotEntity>

    /**
     * 区间内按 [药品 + 计划日期 + 状态] 聚合的槽位计数。
     * 统计报表的依从率与进展页的打卡矩阵共用此查询，保证两者口径完全一致，
     * 且"依从率"以 **计划时间 (scheduled_date)** 归属，不受补录时刻影响。
     */
    @Query(
        """
        SELECT medication_id AS medId,
               scheduled_date AS date,
               status AS status,
               COUNT(*) AS cnt
        FROM dose_slots
        WHERE scheduled_date BETWEEN :startDate AND :endDate
        GROUP BY medication_id, scheduled_date, status
        """
    )
    fun observeSlotStatusCounts(
        startDate: String,
        endDate: String
    ): Flow<List<SlotStatusCountRow>>

    /** 单个药品的区间槽位状态计数 (药品详情页的依从率) */
    @Query(
        """
        SELECT medication_id AS medId,
               scheduled_date AS date,
               status AS status,
               COUNT(*) AS cnt
        FROM dose_slots
        WHERE scheduled_date BETWEEN :startDate AND :endDate
          AND medication_id = :medicationId
        GROUP BY medication_id, scheduled_date, status
        """
    )
    fun observeSlotStatusCountsForMedication(
        medicationId: Long,
        startDate: String,
        endDate: String
    ): Flow<List<SlotStatusCountRow>>

    /** 统计报表 / 详情页依从率用的一次性聚合查询 */
    @Query(
        """
        SELECT medication_id AS medId,
               scheduled_date AS date,
               status AS status,
               COUNT(*) AS cnt
        FROM dose_slots
        WHERE scheduled_date BETWEEN :startDate AND :endDate
        GROUP BY medication_id, scheduled_date, status
        """
    )
    suspend fun getSlotStatusCounts(
        startDate: String,
        endDate: String
    ): List<SlotStatusCountRow>

    @Query("SELECT * FROM dose_slots WHERE status IN ('PENDING', 'SNOOZED') ORDER BY scheduled_ts ASC")
    suspend fun getOpenSlots(): List<DoseSlotEntity>

    @Query("SELECT * FROM dose_slots WHERE status = 'PENDING' AND scheduled_ts < :cutoffTs")
    suspend fun getStalePendingSlots(cutoffTs: Long): List<DoseSlotEntity>

    @Query("DELETE FROM dose_slots")
    suspend fun deleteAllSlots()
}
