package com.mcxiaoke.carromed.core.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import com.mcxiaoke.carromed.core.domain.model.PauseStatus
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * 药品的提醒运行态（与 `medications` 严格 1:1）。
 *
 * ## 为什么把这四列从 `medications` 拆出来
 *
 * `medications` 是**药品静态档案**（叫什么、什么剂型、什么单位），它只在
 * "新增 / 编辑药品信息"这一个屏幕里被整块写入。而这四列是**提醒运行态**：
 * 它们由三个互不相干的屏幕分别拥有 —— 提醒设置页（前三列）、
 * 详情页的暂停开关（`paused_until`）、`AlarmReconciler`（只读）。
 *
 * 混在一张表里的直接后果是 P0-5：档案页的 `insert(REPLACE)` 会把提醒配置
 * 一并重置，而反向的"提醒页只改三列却被迫走 20 列宽命令"则会读到陈旧快照
 * 再覆盖回去。**拆表的真正价值不是"表变漂亮"，而是让每组列只有一条写路径。**
 *
 * 见 `docs/REMINDER-DOMAIN-REDESIGN.md` §1.3 / §2.1。
 *
 * ## `paused_until` 的三态语义
 *
 * | 取值 | 含义 |
 * | --- | --- |
 * `null` | 未暂停 |
 * `""` | **无限期**暂停，须用户手动恢复（出差、住院这类"不知道哪天回来"） |
| `"2026-10-15"` | 暂停至该日**含**；`2026-10-16` 起自动恢复 |
 *
 * 存储用 `String?` 而非 `LocalDate?`，与项目既有约定一致
 * （`startDate` / `endDate` / `expiryDate` 全是 `String` + `DATE_FORMATTER`），
 * 避免在同一库里并存两套日期表示。解析失败一律**当作"未暂停"**并向上抛语义说明 ——
 * 因为"该响的不响"是比"多响一次"严重得多的故障。
 */
@Entity(
    tableName = "reminder_settings",
    foreignKeys = [
        ForeignKey(
            entity = MedicationEntity::class,
            parentColumns = ["id"],
            childColumns = ["medication_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ReminderSettingsEntity(
    /** 同时是主键与外键：与 medications 严格 1:1，删药品即删提醒配置 */
    @PrimaryKey
    @ColumnInfo(name = "medication_id")
    val medicationId: Long,

    /** 重要提醒可穿透夜间免打扰（胰岛素、抗凝药刚需） */
    @ColumnInfo(name = "is_critical_reminder", defaultValue = "0")
    val isCriticalReminder: Boolean = false,

    /** 该药的专属推迟时长，0 表示"跟随全局设置" */
    @ColumnInfo(name = "snooze_minutes", defaultValue = "0")
    val snoozeMinutes: Int = 0,

    /** 提前提醒提前多少分钟，0 表示不提前提醒 */
    @ColumnInfo(name = "advance_minutes", defaultValue = "0")
    val advanceMinutes: Int = 0,

    /** 暂停结束日，见类 KDoc 的三态表。`null` = 未暂停 */
    @ColumnInfo(name = "paused_until")
    val pausedUntil: String? = null
) {

    /**
     * 截至 [today] 是否处于暂停状态。
     *
     * ## 这必须是派生量，不能存储
     *
     * 最自然的写法是 `pausedUntil != null`，**那是错的**：到期自动恢复之后，
     * 只要还有任何一处沿用这个写法，闹钟就会**静默地不再被排** ——
     * 用户以为有提醒、实际没有。这是比误响危险得多的故障方向。
     *
     * 因此所有"该不该为它排闹钟"的判断都必须经过本函数，
     * 且必须配套一条"跨过恢复日"的测试（见 `ReminderPauseTest`）。
     */
    fun isPausedOn(today: LocalDate): Boolean {
        val until = pausedUntil ?: return false
        if (until.isBlank()) return true          // 无限期
        val end = parseDate(until) ?: return false // 解析失败按"未暂停"处理
        return !end.isBefore(today)               // 含当天：到期当天仍算暂停
    }

    /** 距自动恢复还有几天；`null` 表示不会自动恢复（未暂停或无限期）。 */
    fun daysUntilResume(today: LocalDate): Int? {
        val until = pausedUntil?.takeIf { it.isNotBlank() } ?: return null
        val end = parseDate(until) ?: return null
        if (end.isBefore(today)) return 0
        val days = (end.toEpochDay() - today.toEpochDay()).toInt()
        return days + 1   // 含当天：今天到期 ⇒ 明天恢复
    }

    /** 暂停状态的结构化结果，展示层负责格式化为本地化文案（B4，D-A）。 */
    fun pauseStatus(today: LocalDate): PauseStatus =
        PauseStatus.of(
            pausedUntil = pausedUntil,
            isPausedOn = { isPausedOn(it) },
            daysUntilResume = { daysUntilResume(it) },
            today = today
        )

    private fun parseDate(raw: String): LocalDate? = try {
        LocalDate.parse(raw.trim())
    } catch (_: DateTimeParseException) {
        null
    }
}
