package com.mcxiaoke.carromed.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mcxiaoke.carromed.core.data.entity.AppSettingEntity
import kotlinx.coroutines.flow.Flow

/**
 * 应用级 Key-Value 设置访问接口
 */
@Dao
interface AppSettingDao {

    // ⚠️ REPLACE 是**有意**的（L-2）：app_settings 以 `key` 为主键，
    // setSetting 的语义就是"按 key 覆盖"，无 id 漂移问题
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setSetting(setting: AppSettingEntity)

    @Query("SELECT * FROM app_settings WHERE `key` = :key LIMIT 1")
    suspend fun getSetting(key: String): AppSettingEntity?

    @Query("SELECT value FROM app_settings WHERE `key` = :key LIMIT 1")
    suspend fun getValue(key: String): String?

    @Query("SELECT value FROM app_settings WHERE `key` = :key LIMIT 1")
    fun observeValue(key: String): Flow<String?>

    @Query("DELETE FROM app_settings WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("SELECT * FROM app_settings ORDER BY `key` ASC")
    suspend fun getAllSettings(): List<AppSettingEntity>

    @Query("SELECT * FROM app_settings")
    fun observeAllSettings(): Flow<List<AppSettingEntity>>

    // ⚠️ REPLACE 是**有意**的（L-2）：仅供备份恢复回填按 key 覆盖整表
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(settings: List<AppSettingEntity>)

    @Query("DELETE FROM app_settings")
    suspend fun deleteAllSettings()
}
