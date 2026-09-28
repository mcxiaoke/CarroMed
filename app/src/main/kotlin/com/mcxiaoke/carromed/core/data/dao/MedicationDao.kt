package com.mcxiaoke.carromed.core.data.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.model.MedicationOverview
import kotlinx.coroutines.flow.Flow

/**
 * 药品数据访问接口
 *
 * ## 读路径与写路径的分工
 *
 * - **写路径**用 [MedicationEntity]，且一律走**细粒度局部 UPDATE**（见各方法 KDoc）。
 * - **读路径**（列表 / 详情 / 今日 / 药箱）用 [MedicationOverview]，它比实体多一个 `stock` 字段 ——
 *   该字段由台账流水聚合而来，是库存余额的**唯一权威值**。
 *
 * `medications` 表**不再存储 `current_stock`**。任何"改账面"的想法都必须改写为
 * "往 `inventory_transactions` 追加一条流水"（由 `DoseTrackingService` 负责）。
 */
@Dao
interface MedicationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(medication: MedicationEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(medications: List<MedicationEntity>): List<Long>

    @Update
    suspend fun update(medication: MedicationEntity)

    // ==================== 读路径：带派生库存余额 ====================

    /**
     * 在服药品列表（含聚合出的 `stock`）。
     *
     * 用 LEFT JOIN 一次取回，避免 N+1：余额来自台账聚合子查询，未建账的药品返回 0。
     */
    @Query(
        """
        SELECT m.*, COALESCE(t.balance, 0) / 1000.0 AS stock
        FROM medications m
        LEFT JOIN (
            SELECT medication_id, SUM(change_amount) AS balance
            FROM inventory_transactions
            GROUP BY medication_id
        ) t ON t.medication_id = m.id
        WHERE m.is_archived = 0
        ORDER BY m.id DESC
        """
    )
    fun observeActiveOverviews(): Flow<List<MedicationOverview>>

    @Query(
        """
        SELECT m.*, COALESCE(t.balance, 0) / 1000.0 AS stock
        FROM medications m
        LEFT JOIN (
            SELECT medication_id, SUM(change_amount) AS balance
            FROM inventory_transactions
            GROUP BY medication_id
        ) t ON t.medication_id = m.id
        WHERE m.is_archived = 0
        ORDER BY m.id DESC
        """
    )
    suspend fun getActiveOverviews(): List<MedicationOverview>

    @Query(
        """
        SELECT m.*, COALESCE(t.balance, 0) / 1000.0 AS stock
        FROM medications m
        LEFT JOIN (
            SELECT medication_id, SUM(change_amount) AS balance
            FROM inventory_transactions
            GROUP BY medication_id
        ) t ON t.medication_id = m.id
        WHERE m.is_archived = 1
        ORDER BY m.updated_at DESC
        """
    )
    fun observeArchivedOverviews(): Flow<List<MedicationOverview>>

    /** 全部药品（含归档），带余额。用于药箱双 Tab 与全局排序。 */
    @Query(
        """
        SELECT m.*, COALESCE(t.balance, 0) / 1000.0 AS stock
        FROM medications m
        LEFT JOIN (
            SELECT medication_id, SUM(change_amount) AS balance
            FROM inventory_transactions
            GROUP BY medication_id
        ) t ON t.medication_id = m.id
        ORDER BY m.id DESC
        """
    )
    fun observeAllOverviews(): Flow<List<MedicationOverview>>

    @Query(
        """
        SELECT m.*, COALESCE(t.balance, 0) / 1000.0 AS stock
        FROM medications m
        LEFT JOIN (
            SELECT medication_id, SUM(change_amount) AS balance
            FROM inventory_transactions
            GROUP BY medication_id
        ) t ON t.medication_id = m.id
        WHERE m.id = :id
        """
    )
    fun observeOverviewById(id: Long): Flow<MedicationOverview?>

    @Query(
        """
        SELECT m.*, COALESCE(t.balance, 0) / 1000.0 AS stock
        FROM medications m
        LEFT JOIN (
            SELECT medication_id, SUM(change_amount) AS balance
            FROM inventory_transactions
            GROUP BY medication_id
        ) t ON t.medication_id = m.id
        WHERE m.id = :id
        """
    )
    suspend fun getOverviewById(id: Long): MedicationOverview?

    // ==================== 写路径：档案实体 ====================

    @Query("SELECT * FROM medications WHERE id = :id")
    suspend fun getMedicationById(id: Long): MedicationEntity?

    @Query("SELECT * FROM medications WHERE id = :id")
    fun observeMedicationById(id: Long): Flow<MedicationEntity?>

    @Query("SELECT * FROM medications WHERE is_archived = 0 ORDER BY id DESC")
    suspend fun getActiveMedications(): List<MedicationEntity>

    @Query("SELECT * FROM medications ORDER BY id DESC")
    suspend fun getAllMedications(): List<MedicationEntity>

    /**
     * 局部更新"药品档案"字段。
     *
     * **这是修复数据丢失缺陷的关键**：此前编辑药品走 `insert()`(REPLACE) 整行覆盖，
     * 会把 alias / precautions / noticeShort / isPaused / isArchived / createdAt
     * 全部重置为默认值 —— 用户点一次"保存"就丢掉注意事项与暂停/归档状态。
     * 本查询只写可编辑字段，其余列一律不动。
     *
     * ⚠️ 集合契约：本 SQL 的 SET 列表**必须**与"药品信息"页表单暴露的字段一一对应。
     * 新增列时若不同时加进表单，就会重演 P0-5（静默抹掉用户配置）。
     * 见 `MedicationAdminServiceTest` 的穷举式字段保全测试。
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
        defaultDose: Int,
        description: String,
        precautions: List<String>,
        noticeShort: String,
        expiryDate: String,
        isCriticalReminder: Boolean,
        snoozeMinutes: Int,
        advanceMinutes: Int,
        minStockAlert: Int,
        updatedAt: Long
    )

    /**
     * 局部更新「提醒行为」维度 (提醒设置页专用)。
     *
     * 与 [updateProfile] 一样是局部 UPDATE —— 提醒设置绝不能顺手改掉药品名称或库存。
     *
     * ⚠️ P0-5 尚未在本 DAO 层根除：`is_critical_reminder` 等三列同时出现在
     * [updateProfile] 与本方法的 SET 列表里，两条写路径可能不同步。
     * 阶段 A2 会把这三列（连同 `is_paused`）迁到独立的 `reminder_settings` 表，
     * 使每组列只有一条写路径。见 `docs/REMINDER-DOMAIN-REDESIGN.md` §1.1。
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

    /**
     * 仅更新有效期（库存页专用）。
     *
     * 单独成一条命令而不是复用 [updateProfile]：库存页只拥有这一个字段，
     * 复用宽命令会迫使调用方手工重传全部档案字段 —— 那正是 P0-5 的第二种症状
     * （`InventoryViewModel` 曾被迫重传 19 个无关字段，内含 read-modify-write 竞态）。
     */
    @Query("UPDATE medications SET expiry_date = :expiryDate, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateExpiryDate(id: Long, expiryDate: String, updatedAt: Long = System.currentTimeMillis())

    /** 仅更新库存预警线 (库存管理页专用) */
    @Query("UPDATE medications SET min_stock_alert = :alert, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateMinStockAlert(id: Long, alert: Float, updatedAt: Long = System.currentTimeMillis())

    /**
     * 库存追踪开关位。
     *
     * **注意：本方法不触碰账面余额** —— 余额已从 `medications` 删除，由台账聚合得出。
     * 开启追踪时若账面需要建档，由 `DoseTrackingService` 追加一条建档流水。
     */
    @Query("UPDATE medications SET is_stock_tracked = :tracked, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateStockTracking(id: Long, tracked: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM medications WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE medications SET is_paused = :isPaused, updated_at = :updatedAt WHERE id = :id")
    suspend fun updatePauseStatus(id: Long, isPaused: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE medications SET is_archived = :isArchived, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateArchiveStatus(id: Long, isArchived: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM medications")
    suspend fun deleteAllMedications()
}
