package com.mcxiaoke.carromed.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.mcxiaoke.carromed.core.data.entity.PolicyTimeEntity
import com.mcxiaoke.carromed.core.data.entity.SchedulePolicyEntity
import kotlinx.coroutines.flow.Flow

/**
 * 提醒计划策略与时点数据访问接口
 */
@Dao
interface SchedulePolicyDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPolicy(policy: SchedulePolicyEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAllPolicies(policies: List<SchedulePolicyEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTimes(times: List<PolicyTimeEntity>): List<Long>

    /**
     * ⚠️ 已删除（M8-1）：`@Update updatePolicy(policy)`。
     *
     * 零生产调用方。它是**整行覆盖**命令：调用方必须重传全部列，
     * 漏传任何一列就把用户的配置抹掉 —— 而"改计划"的正确路径是
     * [savePolicyWithTimes]（它会递增 `version` 并在事务里 deactivate 旧计划）。
     *
     * 留着一个看起来更省事的整行覆盖入口，就是在给 P0-5「漏传型」 defect 开门。
     * `MedicationAdminServiceTest` 曾直接调它，测试改走 [savePolicyWithTimes]。
     */

    /**
     * 取该药品的活跃计划。
     *
     * ## `ORDER BY` 为什么必须有（M5-2）
     *
     * 旧写法是 `LIMIT 1` **没有 `ORDER BY`**。SQL 不保证无 `ORDER BY` 的行序，
     * 于是"同一药品存在多条 active 计划"时取到哪一条**由存储布局决定**。
     *
     * "同一药品同时只有一条 active"从来只是一条**调用顺序维持的约定**：
     * 没有 DB 约束（没有部分唯一索引），备份校验也不查。
     * 一旦某条路径漏了 `deactivatePoliciesForMedication`，用户就会拿到
     * 一个**随机的**服药计划 —— 而界面不会报任何错，只是提醒时间不对。
     *
     * 排序键取 `version DESC, id DESC` 而不是单纯 `id DESC`：
     * `version` 是 [com.mcxiaoke.carromed.core.domain.service.MedicationAdminService]
     * 每次改计划时递增的，语义上就是"最新那次配置"。
     * `id DESC` 作为次键，保证 `version` 相同时（同一次保存的产物）也确定。
     *
     * ⚠️ 加 `ORDER BY` 只让结果**确定**，不解决"确定地取错"——
     * 所以 [com.mcxiaoke.carromed.core.data.DataExporter.validateBackup]
     * 也会以 `MULTIPLE_ACTIVE_POLICIES` 在**导入前**拦住这种备份。
     */
    @Query(
        """
        SELECT * FROM schedule_policies
        WHERE medication_id = :medicationId AND is_active = 1
        ORDER BY version DESC, id DESC
        LIMIT 1
        """
    )
    suspend fun getActivePolicyForMedication(medicationId: Long): SchedulePolicyEntity?

    @Query("SELECT * FROM schedule_policies WHERE medication_id = :medicationId ORDER BY id DESC")
    suspend fun getAllPoliciesForMedication(medicationId: Long): List<SchedulePolicyEntity>

    /**
     * 全部药品的 active 计划，一次取回（zcg #19：药箱页曾是"每药 2 查询"的 N+1）。
     *
     * 排序与 [getActivePolicyForMedication] 同键（`version DESC, id DESC`）：
     * 按序分组后取每组第一条，与逐药 `LIMIT 1` 的结果一致。
     */
    @Query("SELECT * FROM schedule_policies WHERE is_active = 1 ORDER BY version DESC, id DESC")
    suspend fun getAllActivePolicies(): List<SchedulePolicyEntity>

    /**
     * 该药品的计划行数，**只作为变化探针**（osbf P2-3 / DB C-27）。
     *
     * 详情页 / 库存页改造为"探针触发重载"后，改计划（保存 / 清除结束日）必须
     * 是一个可被观察的事件 —— `schedule_policies` 表的写入不会使 `medications`
     * 的 Flow 失效，没有这条探针，改完计划返回详情页看到的还是旧计划。
     */
    @Query("SELECT COUNT(*) FROM schedule_policies WHERE medication_id = :medicationId")
    fun observePolicyCountForMedication(medicationId: Long): Flow<Int>

    @Query("SELECT * FROM policy_times WHERE policy_id = :policyId ORDER BY sort_order ASC, time_of_day ASC")
    suspend fun getTimesForPolicy(policyId: Long): List<PolicyTimeEntity>

    /** 多条计划的时点一次取回；空 id 集合由 Room 的 IN 展开处理、返回空结果，无需调用方短路
     *  （L-10：旧注释称"必须短路"与事实相反 —— 唯一调用方 `CabinetViewModel` 未短路，空库实测正常） */
    @Query("SELECT * FROM policy_times WHERE policy_id IN (:policyIds) ORDER BY sort_order ASC, time_of_day ASC")
    suspend fun getTimesForPolicies(policyIds: List<Long>): List<PolicyTimeEntity>

    @Query("UPDATE schedule_policies SET is_active = 0 WHERE medication_id = :medicationId")
    suspend fun deactivatePoliciesForMedication(medicationId: Long)

    @Query("SELECT * FROM schedule_policies ORDER BY id ASC")
    suspend fun getAllPolicies(): List<SchedulePolicyEntity>

    @Query("SELECT * FROM policy_times ORDER BY id ASC")
    suspend fun getAllTimes(): List<PolicyTimeEntity>

    @Query("DELETE FROM schedule_policies")
    suspend fun deleteAllPolicies()

    @Query("DELETE FROM policy_times")
    suspend fun deleteAllTimes()

    /**
     * 保存一条计划及其时点。
     *
     * ## 为什么必须**删掉**旧时点（M5-5）
     *
     * 旧实现只 `deactivate` 旧计划、然后插入新时点，**从不删旧的 `policy_times`**。
     * 而导出用的是 [getAllTimes]（**含非 active 计划**），于是：
     *
     * | 用户操作 | 库里累计的 `policy_times` | 备份体积 |
     * | :--- | :--- | :--- |
     * | 首次保存 | 2 行 | 2 行 |
     * | 改一次剂量 | +2 行 | 4 行 |
     * | 改十次 | +20 行 | 22 行 |
     *
     * 每改一次计划，备份就**线性膨胀**一截，而绝大部分行永远不会被读 ——
     * 恢复时它们还会被一并写回，让垃圾数据再复制一份。
     *
     * 删旧时点不会丢信息：时点属于计划，计划被 deactivate 后它的时点
     * 就不再是"当前的服药计划"，没有任何查询路径会读它
     * （[getTimesForPolicy] 只按 `policy_id` 查，而调用方只传 active 的 id）。
     */
    @Transaction
    suspend fun savePolicyWithTimes(policy: SchedulePolicyEntity, times: List<PolicyTimeEntity>): Long {
        deactivatePoliciesForMedication(policy.medicationId)
        // 先清掉该药品**所有**历史计划的时点。
        // 只删将被 deactivate 的那些不够：新计划插入前它还没有 id。
        deleteTimesForMedication(policy.medicationId)
        val policyId = insertPolicy(policy.copy(id = 0))
        val timesWithPolicyId = times.map { it.copy(id = 0, policyId = policyId) }
        insertTimes(timesWithPolicyId)
        return policyId
    }

    /** 删掉该药品名下**所有**计划的时点（含已 inactive 的历史计划） */
    @Query(
        """
        DELETE FROM policy_times
        WHERE policy_id IN (SELECT id FROM schedule_policies WHERE medication_id = :medicationId)
        """
    )
    suspend fun deleteTimesForMedication(medicationId: Long): Int
}
