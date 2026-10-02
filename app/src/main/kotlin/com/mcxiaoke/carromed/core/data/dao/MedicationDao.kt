package com.mcxiaoke.carromed.core.data.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.entity.ReminderSettingsEntity
import com.mcxiaoke.carromed.core.data.model.MedicationOverview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 读模型（药品档案 + 台账余额 + 提醒运行态）的公共 SELECT 片段。
 *
 * 提到文件顶层而不是放进 `@Dao` 接口的 companion object —— Room 会把无注解的
 * 非抽象成员当 DAO 方法处理并直接报错。
 *
 * `stock` 已在 SQL 里 `/1000.0` 从毫单位换算为展示值（`int / 1000.0` 在 SQLite 里
 * 走 REAL 运算，不会整数除法截断）。
 */
private const val OVERVIEW_SELECT = """
    SELECT m.*,
           COALESCE(t.balance, 0) / 1000.0 AS stock,
           COALESCE(rs.is_critical_reminder, 0) AS isCriticalReminder,
           COALESCE(rs.snooze_minutes, 0) AS snoozeMinutes,
           COALESCE(rs.advance_minutes, 0) AS advanceMinutes,
           rs.paused_until AS pausedUntil
    FROM medications m
    LEFT JOIN (
        SELECT medication_id, SUM(change_amount) AS balance
        FROM inventory_transactions
        GROUP BY medication_id
    ) t ON t.medication_id = m.id
    LEFT JOIN reminder_settings rs ON rs.medication_id = m.id
"""

/**
 * 药品数据访问接口
 *
 * ## 读路径与写路径的分工
 *
 * - **写路径**用 [MedicationEntity]，且一律走**细粒度局部 UPDATE**（见各方法 KDoc）。
 * - **读路径**（列表 / 详情 / 今日 / 药箱）用 [MedicationOverview]，它在档案之外还带
 *   ①由台账流水聚合出的 `stock`（库存余额的**唯一权威值**）与
 *   ②来自 `reminder_settings` 表的提醒运行态。
 *
 * `medications` 表**既不存储 `current_stock`，也不存储任何提醒运行态列**。
 * 任何"改账面"的想法都必须改写为"往 `inventory_transactions` 追加一条流水"
 * （由 `DoseTrackingService` 负责）；任何"改提醒行为"的想法都必须走
 * `ReminderSettingsDao`。
 */
@Dao
interface MedicationDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(medication: MedicationEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(medications: List<MedicationEntity>): List<Long>

    /** 统计该药品关联的不可变事实数 (服药记录 + 库存台账) */
    @Query(
        """
        SELECT (
            (SELECT COUNT(*) FROM dose_records WHERE medication_id = :medicationId) +
            (SELECT COUNT(*) FROM inventory_transactions WHERE medication_id = :medicationId)
        )
        """
    )
    suspend fun countHistoricalRecords(medicationId: Long): Int

    /**
     * ⚠️ 已删除（osbf P3-1 / DB C-04）：`@Update update(medication)`。
     *
     * 它是**整行覆盖**命令：调用方必须重传全部列，漏传任何一列就把
     * 注意事项、别名、归档标记、创建时间静默抹掉，且没有任何报错
     * （AGENTS §2「数据更新一律局部 UPDATE」的直接反面）。
     * 生产代码零调用方（改档案走 [updateProfile]、改预警线走 `updateMinStockAlert`、
     * 改追踪/归档各走各的局部命令）。将来谁想加回"整行更新"，
     * 请先解释为什么细粒度命令不够用。
     */


    // ==================== 读路径：档案 + 派生余额 + 提醒运行态 ====================

    /**
     * 单行投影的**扁平载体**。
     *
     * 为什么不直接把 `ReminderSettingsEntity` 用 `@Embedded` 嵌进 `MedicationOverview`：
     * 那要求 Room 把一个 `@Entity` 当 POJO 展开，且 `medication_id` 与外层的 `id`
     * 语义重复、极易在某次加列时静默错位。改成"扁平行 + 显式映射"后，
     * 列名与来源在 [toOverview] 里一目了然，映射关系也无法被 Room 猜错。
     */
    data class MedicationOverviewRow(
        @Embedded val medication: MedicationEntity,
        /** 台账聚合出的余额，已由 SQL 从毫单位换算为展示值 */
        val stock: Float,
        val isCriticalReminder: Boolean,
        val snoozeMinutes: Int,
        val advanceMinutes: Int,
        val pausedUntil: String?
    ) {
        fun toOverview(): MedicationOverview = MedicationOverview(
            medication = medication,
            stock = stock,
            reminderSettings = ReminderSettingsEntity(
                medicationId = medication.id,
                isCriticalReminder = isCriticalReminder,
                snoozeMinutes = snoozeMinutes,
                advanceMinutes = advanceMinutes,
                pausedUntil = pausedUntil
            )
        )
    }

    /**
     * 读模型 JOIN 的公共片段。
     *
     * 库存余额来自台账聚合子查询（`stock` 已在 SQL 里 `/1000.0` 换算为展示值），
     * 提醒运行态来自 `reminder_settings` 的 LEFT JOIN（药品刚建未写设置时补默认值）。
     *
     * 一次 JOIN 取全，避免 N+1 —— A2 拆表**不增加**任何页面的查询次数。
     *
     * SQL 片段见文件顶层的 `OVERVIEW_SELECT`（`@Dao` 接口内不允许 companion object，
     * Room 会对非抽象且无注解的方法报错）。
     */

    @Query(OVERVIEW_SELECT + " WHERE m.is_archived = 0 ORDER BY m.id DESC")
    fun observeActiveOverviewRows(): Flow<List<MedicationOverviewRow>>

    @Query(OVERVIEW_SELECT + " WHERE m.is_archived = 0 ORDER BY m.id DESC")
    suspend fun getActiveOverviewRows(): List<MedicationOverviewRow>

    @Query(OVERVIEW_SELECT + " WHERE m.is_archived = 1 ORDER BY m.updated_at DESC")
    fun observeArchivedOverviewRows(): Flow<List<MedicationOverviewRow>>

    /** 全部药品（含归档），供药箱双 Tab 与全局排序 */
    @Query(OVERVIEW_SELECT + " ORDER BY m.id DESC")
    fun observeAllOverviewRows(): Flow<List<MedicationOverviewRow>>

    @Query(OVERVIEW_SELECT + " WHERE m.id = :id")
    fun observeOverviewRowById(id: Long): Flow<MedicationOverviewRow?>

    @Query(OVERVIEW_SELECT + " WHERE m.id = :id")
    suspend fun getOverviewRowById(id: Long): MedicationOverviewRow?

    // ---- 面向调用方的读模型（映射在 Kotlin 侧完成）----

    fun observeActiveOverviews(): Flow<List<MedicationOverview>> =
        observeActiveOverviewRows().map { rows -> rows.map { it.toOverview() } }

    suspend fun getActiveOverviews(): List<MedicationOverview> =
        getActiveOverviewRows().map { it.toOverview() }

    fun observeArchivedOverviews(): Flow<List<MedicationOverview>> =
        observeArchivedOverviewRows().map { rows -> rows.map { it.toOverview() } }

    fun observeAllOverviews(): Flow<List<MedicationOverview>> =
        observeAllOverviewRows().map { rows -> rows.map { it.toOverview() } }

    fun observeOverviewById(id: Long): Flow<MedicationOverview?> =
        observeOverviewRowById(id).map { it?.toOverview() }

    suspend fun getOverviewById(id: Long): MedicationOverview? =
        getOverviewRowById(id)?.toOverview()

    // ==================== 写路径：档案实体 ====================

    @Query("SELECT * FROM medications WHERE id = :id")
    suspend fun getMedicationById(id: Long): MedicationEntity?

    @Query("SELECT * FROM medications WHERE id = :id")
    fun observeMedicationById(id: Long): Flow<MedicationEntity?>

    // ⚠️ 已删除（orsbf P3-1，零调用方）：getActiveMedications。
    // 列表消费方全部走 OVERVIEW_SELECT 的概览读模型。

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
     *
     * ⚠️ `min_stock_alert` **刻意不在** SET 列表（ocsbf P1-2 / DB C-14）：
     * 编辑页已无预警线输入框，快照写回会把用户在库存页改过的预警线静默改回旧值。
     * 预警线的唯一写入口是 [updateMinStockAlert]。
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
        updatedAt: Long
    )

    // ❗ `updateReminderBehavior` 与 `updatePauseStatus` 已删除。
    //
    // 它们写的四列现在住在 `reminder_settings` 表，由 `ReminderSettingsDao` 独占。
    // 删除的真正价值不是"拆表"，而是**P0-5 至此没有第二条写路径了**：
    // 此前这四列同时出现在 `updateProfile`（档案页）与 `updateReminderBehavior`
    // （提醒页）的 SET 列表里，两条命令各写一遍，漏传任何一边都会静默丢配置。

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
    suspend fun updateMinStockAlert(id: Long, alert: Int, updatedAt: Long = System.currentTimeMillis())

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

    @Query("DELETE FROM inventory_transactions WHERE medication_id = :medicationId")
    suspend fun deleteInventoryTransactionsByMedicationId(medicationId: Long): Int

    @Query("DELETE FROM dose_records WHERE medication_id = :medicationId")
    suspend fun deleteDoseRecordsByMedicationId(medicationId: Long): Int

    @Query("DELETE FROM dose_slots WHERE medication_id = :medicationId")
    suspend fun deleteDoseSlotsByMedicationId(medicationId: Long): Int

    @Query("DELETE FROM policy_times WHERE policy_id IN (SELECT id FROM schedule_policies WHERE medication_id = :medicationId)")
    suspend fun deletePolicyTimesByMedicationId(medicationId: Long): Int

    @Query("DELETE FROM schedule_policies WHERE medication_id = :medicationId")
    suspend fun deleteSchedulePoliciesByMedicationId(medicationId: Long): Int

    @Query("DELETE FROM reminder_settings WHERE medication_id = :medicationId")
    suspend fun deleteReminderSettingsByMedicationId(medicationId: Long): Int

    /**
     * 永久彻底删除药品及其所有关联数据 (历史记录、库存流水、排班策略、时点、提醒设置等)。
     * 在单个事务中严格按外键拓扑逆序清理，保证在 RESTRICT 约束下安全执行。
     */
    @Transaction
    suspend fun permanentlyDelete(medicationId: Long) {
        deleteInventoryTransactionsByMedicationId(medicationId)
        deleteDoseRecordsByMedicationId(medicationId)
        deleteDoseSlotsByMedicationId(medicationId)
        deletePolicyTimesByMedicationId(medicationId)
        deleteSchedulePoliciesByMedicationId(medicationId)
        deleteReminderSettingsByMedicationId(medicationId)
        deleteById(medicationId)
    }

    // ❗ `updatePauseStatus` 已删除 —— 暂停状态归 `reminder_settings.paused_until`，
    //    由 `ReminderSettingsDao.setPausedUntil` / `resume` 独占写入。

    @Query("UPDATE medications SET is_archived = :isArchived, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateArchiveStatus(id: Long, isArchived: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM medications")
    suspend fun deleteAllMedications()
}
