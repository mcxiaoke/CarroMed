package com.mcxiaoke.carromed.core.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 药品主体表 (Medication)
 * 遵循 D-16 低摩擦录入原则：除 name 必填外，其余字段在业务上均可选并提供合理默认值
 */
@Entity(tableName = "medications")
data class MedicationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "alias")
    val alias: String? = null,

    @ColumnInfo(name = "category")
    val category: String = "常备药",

    @ColumnInfo(name = "form")
    val form: String = "片剂", // 剂型: 片剂 / 胶囊 / 口服液 / 滴剂 / 外用

    @ColumnInfo(name = "unit")
    val unit: String = "片", // 单位: 片 / 粒 / 袋 / ml / 滴

    @ColumnInfo(name = "color_hex")
    val colorHex: String = "#2563EB",

    @ColumnInfo(name = "icon_name")
    val iconName: String = "pill",

    @ColumnInfo(name = "default_dose")
    /** 单次默认剂量，整数毫单位（1 片 = 1000）。见 core.domain.model.Dose */
    val defaultDose: Int = 1000,

    @ColumnInfo(name = "description")
    val description: String = "",

    @ColumnInfo(name = "precautions")
    val precautions: List<String> = emptyList(),

    @ColumnInfo(name = "notice_short")
    val noticeShort: String = "", // 通知栏单行简述 (如 "温水送服 · 忌葡萄柚")

    // ❗ 账面余额 `current_stock` 已从本表删除。
    //
    // 库存余额的唯一权威定义是 `SUM(inventory_transactions.change_amount)`，
    // 见 docs/REMINDER-DOMAIN-REDESIGN.md §2.3。
    // 改用派生余额后，P0-3（打卡扣减超库存时 `coerceAtLeast(0f)` 把账面钳到 0、
    // 而流水记全额，导致 `SUM(change_amount) != current_stock` 守恒被打破）
    // 从"靠纪律维持的约束"变成"由定义成立"。
    // 读列表请用 `MedicationOverview`（映射 medication_overview 视图，带 `stock` 字段）。

    @ColumnInfo(name = "min_stock_alert")
    /** 低库存预警线，整数毫单位。0 = 关闭低库存告警（见 FINAL-PRODUCT 与 TodayViewModel 的判定） */
    val minStockAlert: Int = 0,

    @ColumnInfo(name = "is_stock_tracked")
    val isStockTracked: Boolean = false,

    // ---- 有效期：属于药品档案本身（跟着药盒走），留在本表 ----

    /** 药品有效期至 (yyyy-MM-dd)，空串表示未记录。用于库存临期提醒 */
    @ColumnInfo(name = "expiry_date")
    val expiryDate: String = "",

    // ❗ 以下四列已迁到 `reminder_settings` 表（1:1）：
    // `is_critical_reminder` / `snooze_minutes` / `advance_minutes` / `is_paused`
    //
    // 迁出理由见 docs/REMINDER-DOMAIN-REDESIGN.md §1.3 —— 关键不是"表变漂亮"，
    // 而是**每组列只有一条写路径**。混表时，档案页的整行覆盖会抹掉提醒配置（P0-5
    // 漏传型），而提醒页为了少写几行改用 20 列宽命令，又会拿陈旧快照覆盖回档案（P0-5
    // 被迫重传型）。
    //
    // `is_paused` 还顺带升级为 `reminder_settings.paused_until`（带结束日），
    // 以满足 FINAL-PRODUCT M-02「暂停至某日」。**"是否暂停"是派生量不是存储量**，
    // 判断必须走 `ReminderSettingsEntity.isPausedOn(today)`，不要写
    // `paused_until != null` —— 到期后那样写会让闹钟静默消失。

    @ColumnInfo(name = "is_archived")
    val isArchived: Boolean = false,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
