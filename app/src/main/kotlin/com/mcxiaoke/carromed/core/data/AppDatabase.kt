package com.mcxiaoke.carromed.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
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
    version = 1,
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

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
