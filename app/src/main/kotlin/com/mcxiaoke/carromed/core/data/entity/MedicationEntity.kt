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
    val defaultDose: Float = 1.0f,

    @ColumnInfo(name = "description")
    val description: String = "",

    @ColumnInfo(name = "precautions")
    val precautions: List<String> = emptyList(),

    @ColumnInfo(name = "notice_short")
    val noticeShort: String = "", // 通知栏单行简述 (如 "温水送服 · 忌葡萄柚")

    @ColumnInfo(name = "current_stock")
    val currentStock: Float = 0f,

    @ColumnInfo(name = "min_stock_alert")
    val minStockAlert: Float = 0f,

    @ColumnInfo(name = "is_stock_tracked")
    val isStockTracked: Boolean = false,

    // ---- 以下为 schema v2 新增：按药品维度的提醒行为与有效期 ----

    /** 药品有效期至 (yyyy-MM-dd)，空串表示未记录。用于库存临期提醒 */
    @ColumnInfo(name = "expiry_date")
    val expiryDate: String = "",

    /** 重要提醒：置 true 时该药的闹钟走更激进的唤醒策略 (响铃/全屏)，忽略夜间静音 */
    @ColumnInfo(name = "is_critical_reminder")
    val isCriticalReminder: Boolean = false,

    /** 该药专属的"稍后提醒"时长 (分钟)。0 表示跟随全局设置 */
    @ColumnInfo(name = "snooze_minutes")
    val snoozeMinutes: Int = 0,

    /** 该药专属的"提前提醒"时长 (分钟)。0 表示准点提醒 */
    @ColumnInfo(name = "advance_minutes")
    val advanceMinutes: Int = 0,

    @ColumnInfo(name = "is_paused")
    val isPaused: Boolean = false,

    @ColumnInfo(name = "is_archived")
    val isArchived: Boolean = false,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
