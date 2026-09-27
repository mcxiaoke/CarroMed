package com.mcxiaoke.carromed.core.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mcxiaoke.carromed.core.data.model.RecordStatus

/**
 * 服药历史事实表 (DoseRecord)
 * 记录客观真实发生过的用药动作（计划打卡、临时服药、事后补录、主动跳过）
 */
@Entity(
    tableName = "dose_records",
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
        Index(value = ["slot_id"]),
        Index(value = ["actual_ts"])
    ]
)
data class DoseRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "slot_id")
    val slotId: Long? = null, // 若由计划打卡生成，关联对应 dose_slots.id；若为无计划临时/补录服药，可为 null

    @ColumnInfo(name = "medication_id")
    val medicationId: Long,

    @ColumnInfo(name = "actual_ts")
    val actualTs: Long, // 实际发生的时间戳 (毫秒)

    @ColumnInfo(name = "dose_taken")
    val doseTaken: Float, // 实际服药剂量

    @ColumnInfo(name = "status")
    val status: RecordStatus = RecordStatus.COMPLETED,

    @ColumnInfo(name = "is_retrospective")
    val isRetrospective: Boolean = false, // 是否为事后补录

    @ColumnInfo(name = "note")
    val note: String? = null, // 服药备注 (如 "随早餐服下", "头痛临时加服")

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
