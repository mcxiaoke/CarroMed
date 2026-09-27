package com.mcxiaoke.carromed.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.mcxiaoke.carromed.core.data.entity.PolicyTimeEntity
import com.mcxiaoke.carromed.core.data.entity.SchedulePolicyEntity

/**
 * 提醒计划策略与时点数据访问接口
 */
@Dao
interface SchedulePolicyDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPolicy(policy: SchedulePolicyEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTimes(times: List<PolicyTimeEntity>): List<Long>

    @Update
    suspend fun updatePolicy(policy: SchedulePolicyEntity)

    @Query("SELECT * FROM schedule_policies WHERE medication_id = :medicationId AND is_active = 1 LIMIT 1")
    suspend fun getActivePolicyForMedication(medicationId: Long): SchedulePolicyEntity?

    @Query("SELECT * FROM schedule_policies WHERE medication_id = :medicationId ORDER BY id DESC")
    suspend fun getAllPoliciesForMedication(medicationId: Long): List<SchedulePolicyEntity>

    @Query("SELECT * FROM policy_times WHERE policy_id = :policyId ORDER BY sort_order ASC, time_of_day ASC")
    suspend fun getTimesForPolicy(policyId: Long): List<PolicyTimeEntity>

    @Query("UPDATE schedule_policies SET is_active = 0 WHERE medication_id = :medicationId")
    suspend fun deactivatePoliciesForMedication(medicationId: Long)

    @Transaction
    suspend fun savePolicyWithTimes(policy: SchedulePolicyEntity, times: List<PolicyTimeEntity>): Long {
        deactivatePoliciesForMedication(policy.medicationId)
        val policyId = insertPolicy(policy)
        val timesWithPolicyId = times.map { it.copy(policyId = policyId) }
        insertTimes(timesWithPolicyId)
        return policyId
    }
}
