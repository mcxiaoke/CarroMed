package com.mcxiaoke.carromed.core.data.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.entity.ReminderSettingsEntity
import kotlinx.coroutines.flow.Flow

/**
 * 药品的提醒运行态读写。
 *
 * ## 三条命令，三块屏幕所有权
 *
 * 本 DAO **刻意只提供细粒度命令**，不提供"整块更新"：
 *
 * | 命令 | 唯一拥有者 | 绝不能顺带改的 |
 * | --- | --- | --- |
 * | [updateBehavior] | 提醒设置页 | 暂停状态（那是详情页的开关） |
 * | [pauseUntil] / [resume] | 详情页的暂停开关 | 三项提醒行为 |
 * | [ensureDefaults] | 新建药品时 | — |
 *
 * 宽命令（"把提醒设置整块写一遍"）是 P0-5「漏传型」的温床：
 * 调用方为了少写几行而重传全部列时，只要有一列漏传，用户的配置就没了。
 * **屏幕拥有几列，就只给它几列的写命令** —— 这条约束是本 DAO 存在的理由，
 * 不是风格偏好。
 */
@Dao
interface ReminderSettingsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(settings: ReminderSettingsEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(settings: ReminderSettingsEntity): Long

    @Query("SELECT * FROM reminder_settings WHERE medication_id = :medicationId")
    suspend fun getByMedicationId(medicationId: Long): ReminderSettingsEntity?

    @Query("SELECT * FROM reminder_settings WHERE medication_id = :medicationId")
    fun observeByMedicationId(medicationId: Long): Flow<ReminderSettingsEntity?>

    @Query("SELECT * FROM reminder_settings")
    fun observeAll(): Flow<List<ReminderSettingsEntity>>

    @Query("SELECT * FROM reminder_settings")
    suspend fun getAll(): List<ReminderSettingsEntity>

    /**
     * 只写提醒行为三列。
     *
     * ⚠️ SET 列表必须与"提醒设置页"表单暴露的字段**一一对应**。
     * 表单加字段而这里不加 ⇒ 该字段永远存不进去（静默）；
     * 这里加而表单不加 ⇒ 用户改的值会被默认值覆盖（更隐蔽）。
     * 两边必须同一次改完。`ReminderSettingsFieldsTest` 是这条契约的守卫。
     */
    @Query(
        """
        UPDATE reminder_settings SET
            is_critical_reminder = :isCriticalReminder,
            snooze_minutes = :snoozeMinutes,
            advance_minutes = :advanceMinutes
        WHERE medication_id = :medicationId
        """
    )
    suspend fun updateBehavior(
        medicationId: Long,
        isCriticalReminder: Boolean,
        snoozeMinutes: Int,
        advanceMinutes: Int
    ): Int

    /** 只写暂停结束日。`until` 为 null 表示未暂停，`""` 表示无限期。 */
    @Query("UPDATE reminder_settings SET paused_until = :until WHERE medication_id = :medicationId")
    suspend fun setPausedUntil(medicationId: Long, until: String?): Int

    @Query("UPDATE reminder_settings SET paused_until = NULL WHERE medication_id = :medicationId")
    suspend fun resume(medicationId: Long): Int

    // ⚠️ 已删除（orsbf P3-1，零调用方）：deleteForMedication。
    // 删药品时"顺带清设置"由 FK CASCADE（ReminderSettingsEntity 的外键）完成；
    // 两条删除路并存总有一天会有人走错那条。

    @Query("DELETE FROM reminder_settings")
    suspend fun deleteAll()

    /**
     * 确保存在一行（未设置时用默认行为创建）。
     *
     * 新建药品后必须调用，否则提醒设置页第一次保存会因 UPDATE 命中 0 行而**静默失败** ——
     * 用户改了设置、点了保存、回到详情页发现什么都没变，且没有任何报错。
     */
    suspend fun ensureDefaults(medicationId: Long): ReminderSettingsEntity {
        val existing = getByMedicationId(medicationId)
        if (existing != null) return existing
        val fresh = ReminderSettingsEntity(medicationId = medicationId)
        insertIfAbsent(fresh)
        return getByMedicationId(medicationId) ?: fresh
    }

    // ==================== 读模型 ====================

    /**
     * 药品 + 提醒设置的一次性 JOIN 投影。
     *
     * 用它取代"先查药、再查设置"的两次查询。`AlarmReceiver` 与药箱列表都走这里，
     * 因此拆分**不带来查询次数增加**。
     */
    data class MedicationWithReminder(
        @Embedded val medication: MedicationEntity,
        val isCriticalReminder: Boolean,
        val snoozeMinutes: Int,
        val advanceMinutes: Int,
        val pausedUntil: String?
    ) {
        fun settings(): ReminderSettingsEntity = ReminderSettingsEntity(
            medicationId = medication.id,
            isCriticalReminder = isCriticalReminder,
            snoozeMinutes = snoozeMinutes,
            advanceMinutes = advanceMinutes,
            pausedUntil = pausedUntil
        )
    }

    @Query(
        """
        SELECT m.*, COALESCE(r.is_critical_reminder, 0) AS isCriticalReminder,
               COALESCE(r.snooze_minutes, 0) AS snoozeMinutes,
               COALESCE(r.advance_minutes, 0) AS advanceMinutes,
               r.paused_until AS pausedUntil
        FROM medications m
        LEFT JOIN reminder_settings r ON r.medication_id = m.id
        WHERE m.id = :medicationId
        """
    )
    suspend fun getMedicationWithReminder(medicationId: Long): MedicationWithReminder?

    @Query(
        """
        SELECT m.*, COALESCE(r.is_critical_reminder, 0) AS isCriticalReminder,
               COALESCE(r.snooze_minutes, 0) AS snoozeMinutes,
               COALESCE(r.advance_minutes, 0) AS advanceMinutes,
               r.paused_until AS pausedUntil
        FROM medications m
        LEFT JOIN reminder_settings r ON r.medication_id = m.id
        WHERE m.is_archived = 0
        ORDER BY m.id DESC
        """
    )
    suspend fun getActiveWithReminder(): List<MedicationWithReminder>

    /**
     * 截至 [today] 需要排闹钟的药品（未归档且未暂停）。
     *
     * ⚠️ 已删除（osbf P3-1 / ds P2-3）：`getAllForReconcile` / `getSchedulableOn`。
     *
     * 旧 KDoc 自称"该不该为它排闹钟的**唯一数据库级判据**"，但生产对账
     * 实际走 `MedicationDao.getActiveOverviews()` + `MedicationOverview.isPausedOn`
     * （见 `AlarmReconciler`），这两个方法**零调用方** —— 是一条从未成立的
     * 文档承诺。留着它，后人按 KDoc 找"判据"会找到一条死路。
     * 暂停判断的现行唯一判据仍是 `ReminderSettingsEntity.isPausedOn`，
     * 消费方在 `MedicationOverview` 的代理字段上。
     */
}
