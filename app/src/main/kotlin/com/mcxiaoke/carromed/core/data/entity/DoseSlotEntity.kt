package com.mcxiaoke.carromed.core.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mcxiaoke.carromed.core.data.model.SlotStatus

/**
 * 前向生成的服药排班槽位表 (DoseSlot)
 * 核心设计：
 * 1. 主键 id 直接作为 AlarmManager 的 requestCode，根除哈希算法碰撞隐患
 * 2. 以 scheduled_date (本地日期字符串 YYYY-MM-DD) 建立索引，保证今日清单与历史查询瞬时响应
 */
@Entity(
    tableName = "dose_slots",
    foreignKeys = [
        ForeignKey(
            entity = MedicationEntity::class,
            parentColumns = ["id"],
            childColumns = ["medication_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["medication_id"]),
        Index(value = ["scheduled_date", "status"]),
        Index(value = ["scheduled_ts"]),
        // v2: 支撑 "按药品 + 计划日期区间" 的聚合统计与打卡矩阵走索引
        Index(value = ["medication_id", "scheduled_date", "scheduled_time"])
    ]
)
data class DoseSlotEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "medication_id")
    val medicationId: Long,

    @ColumnInfo(name = "policy_id")
    val policyId: Long,

    @ColumnInfo(name = "scheduled_date")
    val scheduledDate: String, // 本地日期: "YYYY-MM-DD" (不受系统时区漂移影响)

    @ColumnInfo(name = "scheduled_time")
    val scheduledTime: String, // 计划时间: "HH:mm"

    @ColumnInfo(name = "scheduled_ts")
    val scheduledTs: Long, // 计划精确绝对时间戳 (毫秒)，用于 AlarmManager 唤醒

    @ColumnInfo(name = "dose_amount")
    val doseAmount: Float,

    @ColumnInfo(name = "status")
    val status: SlotStatus = SlotStatus.PENDING,

    @ColumnInfo(name = "actual_taken_ts")
    val actualTakenTs: Long? = null, // 实际打卡时间戳 (毫秒)

    @ColumnInfo(name = "snooze_until_ts")
    val snoozeUntilTs: Long? = null, // 若推迟，推迟唤醒的目标时间戳

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
