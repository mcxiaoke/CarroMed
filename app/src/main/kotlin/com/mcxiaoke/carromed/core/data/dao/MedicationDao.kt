package com.mcxiaoke.carromed.core.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import kotlinx.coroutines.flow.Flow

/**
 * 药品数据访问接口
 */
@Dao
interface MedicationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(medication: MedicationEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(medications: List<MedicationEntity>): List<Long>

    @Update
    suspend fun update(medication: MedicationEntity)

    @Delete
    suspend fun delete(medication: MedicationEntity)

    @Query("DELETE FROM medications WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM medications WHERE id = :id")
    suspend fun getMedicationById(id: Long): MedicationEntity?

    @Query("SELECT * FROM medications WHERE id = :id")
    fun observeMedicationById(id: Long): Flow<MedicationEntity?>

    @Query("SELECT * FROM medications WHERE is_archived = 0 ORDER BY id DESC")
    fun observeActiveMedications(): Flow<List<MedicationEntity>>

    @Query("SELECT * FROM medications WHERE is_archived = 0 ORDER BY id DESC")
    suspend fun getActiveMedications(): List<MedicationEntity>

    @Query("SELECT * FROM medications WHERE is_archived = 1 ORDER BY updated_at DESC")
    fun observeArchivedMedications(): Flow<List<MedicationEntity>>

    @Query("SELECT * FROM medications ORDER BY id DESC")
    suspend fun getAllMedications(): List<MedicationEntity>

    @Query("UPDATE medications SET current_stock = :newStock, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateStock(id: Long, newStock: Float, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE medications SET is_paused = :isPaused, updated_at = :updatedAt WHERE id = :id")
    suspend fun updatePauseStatus(id: Long, isPaused: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE medications SET is_archived = :isArchived, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateArchiveStatus(id: Long, isArchived: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM medications")
    suspend fun deleteAllMedications()
}
