package com.mcxiaoke.carromed.core.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mcxiaoke.carromed.core.data.model.SlotStatus

/**
 * 前向生成的服药排班槽位表 (DoseSlot)
 *
 * 核心设计：
 * 1. 以 scheduled_date (本地日期字符串 YYYY-MM-DD) 建立索引，保证今日清单与历史查询瞬时响应
 * 2. `(medication_id, scheduled_date, scheduled_time)` 是**唯一**约束 —— 槽位唯一性从
 *    「`reconcileSchedule` 里的内存去重」升级为数据库不变量。
 *
 * ## 为什么第 2 条是本轮最容易被低估的一处
 *
 * 此前唯一性靠 `DoseTrackingService.reconcileSchedule` 把已存在槽位的 key 收进
 * `existingSlotKeys` 再过滤。**任何绕过那个方法的插入路径都能造出重复槽位**，
 * 而重复槽位 = 重复闹钟 + 重复扣库存，且不会报错。
 *
 * 改成 DB 约束后：
 * - 漏写去重逻辑的后果从"静默产生重复"变成"插入直接抛异常"——**失败得很响**；
 * - A3 的幂等 diff 重排（只插新增、只删真正消失的）才真正安全，
 *   因为"只插新增"这一步本身就由数据库兜底。
 *
 * `insertAll` 改用 `OnConflictStrategy.IGNORE` 配合本约束，
 * 让"重物化同一窗口"天然幂等 —— 重复键被静默忽略而不是让整批插入失败。
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
        // ⭐ 槽位唯一性：同一药品的同一计划日 + 同一时刻只能有一条槽位
        Index(
            value = ["medication_id", "scheduled_date", "scheduled_time"],
            unique = true
        )
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
    val doseAmount: Int, // 计划剂量，整数毫单位

    @ColumnInfo(name = "status")
    val status: SlotStatus = SlotStatus.PENDING,

    @ColumnInfo(name = "actual_taken_ts")
    val actualTakenTs: Long? = null, // 实际打卡时间戳 (毫秒)

    @ColumnInfo(name = "snooze_until_ts")
    val snoozeUntilTs: Long? = null, // 若推迟，推迟唤醒的目标时间戳

    @ColumnInfo(name = "last_main_notified_ts")
    val lastMainNotifiedTs: Long? = null, // 主提醒成功弹出的时间戳，用于消除划掉通知后的周期补响骚扰 (P1-1)

    @ColumnInfo(name = "last_snooze_notified_ts")
    val lastSnoozeNotifiedTs: Long? = null, // 推迟提醒成功弹出的时间戳 (P1-1)

    @ColumnInfo(name = "reminder_count")
    val reminderCount: Int = 0, // 已提醒次数 (用于忽略/划掉后的重复提醒次数上限判断)

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
