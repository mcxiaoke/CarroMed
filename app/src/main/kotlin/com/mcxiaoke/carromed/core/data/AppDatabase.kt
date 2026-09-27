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
import com.mcxiaoke.carromed.core.data.dao.SchedulePolicyDao
import com.mcxiaoke.carromed.core.data.entity.AppSettingEntity
import com.mcxiaoke.carromed.core.data.entity.DoseRecordEntity
import com.mcxiaoke.carromed.core.data.entity.DoseSlotEntity
import com.mcxiaoke.carromed.core.data.entity.InventoryTransactionEntity
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.entity.PolicyTimeEntity
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
        AppSettingEntity::class
    ],
    version = 2,
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

    companion object {
        const val DATABASE_NAME = "carromed.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * schema v1 → v2 迁移
         *
         * 全部为 `ADD COLUMN` / `CREATE INDEX`，对既有数据零破坏。
         * **刻意不保留 `fallbackToDestructiveMigration()`**：吃药 App 的历史服药事实
         * 是不可再生资产，静默清库不可接受；迁移失败必须让 App 显式崩溃暴露问题。
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

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
