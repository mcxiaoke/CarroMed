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
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        // 复合索引 (medication_id, actual_ts)：keyset 分页（单药历史按
        // medication_id 过滤 + actual_ts 排序）此前只吃主键序扫描（L-17）。
        // 最左前缀覆盖外键 medication_id，替代原单列索引，不新增冗余。
        Index(value = ["medication_id", "actual_ts"]),
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
    val doseTaken: Int, // 实际服药剂量，整数毫单位（1 片 = 1000）

    @ColumnInfo(name = "status")
    val status: RecordStatus = RecordStatus.COMPLETED,

    @ColumnInfo(name = "is_retrospective")
    val isRetrospective: Boolean = false, // 是否为事后补录

    @ColumnInfo(name = "note")
    val note: String? = null, // 服药备注。程序化记录只放用户/数据文本，文案由 noteKey 资源化；用户自由备注原样

    /** schema v8：程序化备注的分类 key（[RecordNoteKey] name），null = 用户自由文本。 */
    @ColumnInfo(name = "note_key")
    val noteKey: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
