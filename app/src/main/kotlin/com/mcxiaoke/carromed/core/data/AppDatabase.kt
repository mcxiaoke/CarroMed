package com.mcxiaoke.carromed.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mcxiaoke.carromed.core.data.converter.AppConverters
import com.mcxiaoke.carromed.core.data.dao.AppSettingDao
import com.mcxiaoke.carromed.core.data.dao.DoseRecordDao
import com.mcxiaoke.carromed.core.data.dao.DoseSlotDao
import com.mcxiaoke.carromed.core.data.dao.InventoryTransactionDao
import com.mcxiaoke.carromed.core.data.dao.MedicationDao
import com.mcxiaoke.carromed.core.data.dao.ReminderSettingsDao
import com.mcxiaoke.carromed.core.data.dao.SchedulePolicyDao
import com.mcxiaoke.carromed.core.data.entity.AppSettingEntity
import com.mcxiaoke.carromed.core.data.entity.DoseRecordEntity
import com.mcxiaoke.carromed.core.data.entity.DoseSlotEntity
import com.mcxiaoke.carromed.core.data.entity.InventoryTransactionEntity
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.entity.PolicyTimeEntity
import com.mcxiaoke.carromed.core.data.entity.ReminderSettingsEntity
import com.mcxiaoke.carromed.core.data.entity.SchedulePolicyEntity

/**
 * CarroMed Room 数据库单例定义
 * 包含 7 张实体表及 6 个对应 DAO
 */
@Database(
    entities = [
        MedicationEntity::class,
        SchedulePolicyEntity::class,
        PolicyTimeEntity::class,
        DoseSlotEntity::class,
        DoseRecordEntity::class,
        InventoryTransactionEntity::class,
        AppSettingEntity::class,
        ReminderSettingsEntity::class
    ],
    version = 4,
    exportSchema = false
)
@TypeConverters(AppConverters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun medicationDao(): MedicationDao
    abstract fun schedulePolicyDao(): SchedulePolicyDao
    abstract fun doseSlotDao(): DoseSlotDao
    abstract fun doseRecordDao(): DoseRecordDao
    abstract fun inventoryTransactionDao(): InventoryTransactionDao
    abstract fun appSettingDao(): AppSettingDao
    abstract fun reminderSettingsDao(): ReminderSettingsDao

    companion object {
        const val DATABASE_NAME = "carromed.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * schema v1 → v2 迁移（历史保留）
         *
         * 全部为 `ADD COLUMN` / `CREATE INDEX`，对既有数据零破坏。
         * 保留仅为让 git 里的旧安装不至于直接崩溃；当前开发主线已不再依赖它。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE medications ADD COLUMN expiry_date TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE medications ADD COLUMN is_critical_reminder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE medications ADD COLUMN snooze_minutes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE medications ADD COLUMN advance_minutes INTEGER NOT NULL DEFAULT 0")

                db.execSQL("ALTER TABLE inventory_transactions ADD COLUMN batch_number TEXT")
                db.execSQL("ALTER TABLE inventory_transactions ADD COLUMN expiry_date TEXT")

                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_dose_slots_medication_id_scheduled_date_scheduled_time " +
                        "ON dose_slots (medication_id, scheduled_date, scheduled_time)"
                )
            }
        }

        /**
         * ⚠️⚠️ **发布前必须删除这一行** ⚠️⚠️
         *
         * ## 为什么开发期打开了破坏式重建
         *
         * v2 → v3 删了 `medications.current_stock` 一列，并把 5 个金额列从 `REAL`
         * 改成了整数毫单位。这类改写在 SQLite 里必须靠"建新表 + 拷数据 + 改名"实现，
         * 是一整套迁移代码 —— 而 `AGENTS.md` 已明确：项目尚未公开发布，
         * **不需要任何迁移或兼容旧版本的代码**。
         *
         * 过去这里刻意禁用破坏式回退（理由是"服药事实不可再生"）。那条理由
         * 成立的前提是**已经有真实用户在用**。现在这个前提被明文否定，
         * 于是"禁止破坏"与"不写迁移"变成了两条互斥要求。
         *
         * ## 选它的理由
         *
         * 与其写一段 60 行、只在开发期被执行一次、此后永远不被覆盖的迁移 SQL，
         * 不如让 Room 直接重建库：
         * - 开发期数据可从 `dev.SEED` 广播一键重建，损失为零；
         * - 不会留下一段"看起来在保护数据、实际只保护了浮点转整数"的假保障；
         * - 真正发布前，`MIGRATION_*` 会连同 `MigrationTest` 一起重新补齐并逐条验证。
         *
         * 对比方案（静默崩溃）更糟：开发机上换台机器 clone 就起不来。
         */
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration() // TODO(发布前删除)：见上方 KDoc
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
