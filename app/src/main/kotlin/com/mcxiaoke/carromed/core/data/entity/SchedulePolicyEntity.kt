package com.mcxiaoke.carromed.core.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mcxiaoke.carromed.core.data.model.PolicyType

/**
 * 服药排班策略表 (SchedulePolicy)
 * 记录药物服药频次规则（每天、隔日、每隔N天、每周特定几天、周期性等）
 */
@Entity(
    tableName = "schedule_policies",
    foreignKeys = [
        ForeignKey(
            entity = MedicationEntity::class,
            parentColumns = ["id"],
            childColumns = ["medication_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["medication_id"])
    ]
)
data class SchedulePolicyEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "medication_id")
    val medicationId: Long,

    @ColumnInfo(name = "policy_type")
    val policyType: PolicyType = PolicyType.DAILY,

    @ColumnInfo(name = "interval_days")
    val intervalDays: Int = 1, // 当 policyType=INTERVAL 时生效 (2代表隔天，3代表每隔2天)

    @ColumnInfo(name = "days_of_week")
    val daysOfWeek: List<Int> = emptyList(), // 当 policyType=DAYS_OF_WEEK 时生效 (1=周一 .. 7=周日)

    @ColumnInfo(name = "cycle_on_days")
    val cycleOnDays: Int = 0, // 周期用药: 用药天数 (如 21)

    @ColumnInfo(name = "cycle_off_days")
    val cycleOffDays: Int = 0, // 周期用药: 停药天数 (如 7)

    @ColumnInfo(name = "start_date")
    val startDate: String, // 生效起始日期: YYYY-MM-DD

    @ColumnInfo(name = "end_date")
    val endDate: String? = null, // 结束日期 (可选，抗生素等疗程结束后自动结束)

    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,

    @ColumnInfo(name = "version")
    val version: Int = 1, // 改计划版本号 (每次修改版本递增，用于平滑过渡)

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
