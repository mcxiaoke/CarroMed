package com.mcxiaoke.carromed.core.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 应用级 Key-Value 设置表 (AppSetting)
 */
@Entity(tableName = "app_settings")
data class AppSettingEntity(
    @PrimaryKey
    val key: String,

    @ColumnInfo(name = "value")
    val value: String,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
