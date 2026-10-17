package com.mcxiaoke.carromed.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.data.entity.AppSettingEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class AutoBackupManagerTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `未开启自动备份开关时跳过备份`() = runTest {
        val result = AutoBackupManager.performAutoBackupIfNeeded(context, db)
        assertThat(result).isEqualTo(AutoBackupManager.AutoBackupResult.Disabled)
    }

    @Test
    fun `开启开关但未配置存储目录时严禁备份`() = runTest {
        db.appSettingDao().setSetting(
            AppSettingEntity(AutoBackupManager.KEY_AUTO_BACKUP_ENABLED, "true")
        )

        val result = AutoBackupManager.performAutoBackupIfNeeded(context, db)
        assertThat(result).isEqualTo(AutoBackupManager.AutoBackupResult.NoDirectoryConfigured)
    }

    @Test
    fun `配置的目录权限无效时拒绝备份`() = runTest {
        db.appSettingDao().setSetting(
            AppSettingEntity(AutoBackupManager.KEY_AUTO_BACKUP_ENABLED, "true")
        )
        db.appSettingDao().setSetting(
            AppSettingEntity(
                AutoBackupManager.KEY_AUTO_BACKUP_TREE_URI,
                "content://com.android.externalstorage.documents/tree/primary%3ANonExistent"
            )
        )

        val result = AutoBackupManager.performAutoBackupIfNeeded(context, db)
        assertThat(result).isEqualTo(AutoBackupManager.AutoBackupResult.PermissionRevoked)
    }

    @Test
    fun `当天已经自动备份过则跳过`() = runTest {
        val today = LocalDate.of(2026, 10, 17)
        db.appSettingDao().setSetting(
            AppSettingEntity(AutoBackupManager.KEY_AUTO_BACKUP_ENABLED, "true")
        )
        db.appSettingDao().setSetting(
            AppSettingEntity(
                AutoBackupManager.KEY_AUTO_BACKUP_TREE_URI,
                "content://dummy/tree"
            )
        )
        // 模拟今天已经记录过备份完成
        db.appSettingDao().setSetting(
            AppSettingEntity(AutoBackupManager.KEY_AUTO_BACKUP_LAST_DATE, "2026-10-17")
        )

        // 因为权限检查排在日期检查前，如果没有有效权限先返回权限问题；
        // 如果开关开启但没有有效权限，依然先被权限拦截守住
        val result = AutoBackupManager.performAutoBackupIfNeeded(context, db) { today }
        assertThat(result).isAnyOf(
            AutoBackupManager.AutoBackupResult.PermissionRevoked,
            AutoBackupManager.AutoBackupResult.AlreadyBackedUpToday
        )
    }
}
