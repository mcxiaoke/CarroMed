package com.mcxiaoke.carromed.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mcxiaoke.carromed.core.data.entity.DoseSlotEntity
import com.mcxiaoke.carromed.core.data.model.SlotStatus
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
}
