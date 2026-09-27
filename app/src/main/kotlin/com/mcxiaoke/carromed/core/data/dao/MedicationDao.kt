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

    /**
     * 局部更新"药品档案"字段。
     *
     * **这是修复数据丢失缺陷的关键**：此前编辑药品走 `insert()`(REPLACE) 整行覆盖，
     * 会把 alias / precautions / noticeShort / isPaused / isArchived / createdAt
     * 全部重置为默认值 —— 用户点一次"保存"就丢掉注意事项与暂停/归档状态。
     * 本查询只写可编辑字段，其余列一律不动。
     */
    @Query(
        """
        UPDATE medications SET
            name = :name,
            alias = :alias,
            category = :category,
            form = :form,
            unit = :unit,
            color_hex = :colorHex,
            default_dose = :defaultDose,
            description = :description,
            precautions = :precautions,
            notice_short = :noticeShort,
            expiry_date = :expiryDate,
            is_critical_reminder = :isCriticalReminder,
            snooze_minutes = :snoozeMinutes,
            advance_minutes = :advanceMinutes,
            min_stock_alert = :minStockAlert,
            updated_at = :updatedAt
        WHERE id = :id
        """
    )
    suspend fun updateProfile(
        id: Long,
        name: String,
        alias: String?,
        category: String,
        form: String,
        unit: String,
        colorHex: String,
        defaultDose: Float,
        description: String,
        precautions: List<String>,
        noticeShort: String,
        expiryDate: String,
        isCriticalReminder: Boolean,
        snoozeMinutes: Int,
        advanceMinutes: Int,
        minStockAlert: Float,
        updatedAt: Long
    )

    /** 仅更新库存预警线 (库存管理页专用) */
    @Query("UPDATE medications SET min_stock_alert = :alert, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateMinStockAlert(id: Long, alert: Float, updatedAt: Long = System.currentTimeMillis())

    /** 库存追踪开关位（不触碰 current_stock，账面由 DoseTrackingService 负责平账） */
    @Query("UPDATE medications SET is_stock_tracked = :tracked, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateStockTracking(id: Long, tracked: Boolean, updatedAt: Long = System.currentTimeMillis())

    /**
     * 局部更新「提醒行为」维度 (提醒设置页专用)。
     * 与 [updateProfile] 一样是局部 UPDATE —— 提醒设置绝不能顺手改掉药品名称或库存。
     */
    @Query(
        """
        UPDATE medications SET
            is_critical_reminder = :isCriticalReminder,
            snooze_minutes = :snoozeMinutes,
            advance_minutes = :advanceMinutes,
            is_paused = :isPaused,
            updated_at = :updatedAt
        WHERE id = :id
        """
    )
    suspend fun updateReminderBehavior(
        id: Long,
        isCriticalReminder: Boolean,
        snoozeMinutes: Int,
        advanceMinutes: Int,
        isPaused: Boolean,
        updatedAt: Long = System.currentTimeMillis()
    )

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
