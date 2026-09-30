package com.mcxiaoke.carromed.core.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mcxiaoke.carromed.core.domain.model.SlotLabel

/**
 * 排班策略的具体时段与单次剂量表 (PolicyTime)
 * 一条策略可关联 1 到 N 个具体提醒时刻（如早 08:00、中 12:30、晚 20:00）
 */
@Entity(
    tableName = "policy_times",
    foreignKeys = [
        ForeignKey(
            entity = SchedulePolicyEntity::class,
            parentColumns = ["id"],
            childColumns = ["policy_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["policy_id"])
    ]
)
data class PolicyTimeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "policy_id")
    val policyId: Long,

    @ColumnInfo(name = "time_of_day")
    val timeOfDay: String, // 时间点格式: "HH:mm" (24小时制，如 "08:30", "20:00")

    @ColumnInfo(name = "dose_amount")
    val doseAmount: Int = 1000, // 单次剂量，整数毫单位

    @ColumnInfo(name = "label")
    val label: String = SlotLabel.GENERIC.name, // 标签稳定 key（SlotLabel），显示走 strings_vocab

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0
)
