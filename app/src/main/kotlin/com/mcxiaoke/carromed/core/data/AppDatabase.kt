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
 * 包含 8 张实体表及 7 个对应 DAO
 *
 * ## 版本策略：钉死在 `version = 1`，不写任何迁移/升级代码
 *
 * 项目**尚未公开发布**（AGENTS §2）：不存在"用户的旧库"这回事，因此不存在升级路径。
 * 与其维护一条永远只在开发机上跑一次、此后无人覆盖的迁移链，不如把版本固定为 1：
 *
 * - **改 schema 直接删库重装** —— 走查脚本每次 `--clear`；真机卸载重装。
 * - **不开任何 `fallbackToDestructiveMigration()`** —— 它只在版本**变大**时才生效，
 *   与"版本恒为 1"的前提矛盾；留着只会让下一个人误以为 schema 变更会被自动兜住。
 * - 版本不变而 schema 变了 ⇒ `checkIdentity` 不匹配 ⇒ 冷启动抛
 *   `IllegalStateException: Room cannot verify the data integrity`（**故意**，比静默删库诚实）。
 *
 * `exportSchema = true`：schema 落盘到 `app/schemas/`，作为将来重建迁移的真相起点
 * （发布前才需要，现在先攒着）。改 schema 后重新构建会更新这份 JSON。
 */
@Database(
    entities = [
        DoseRecordEntity::class,
        InventoryTransactionEntity::class,
        DoseSlotEntity::class,
        PolicyTimeEntity::class,
        SchedulePolicyEntity::class,
        ReminderSettingsEntity::class,
        MedicationEntity::class,
        AppSettingEntity::class
    ],
    // 未发布：版本钉在 1，不写迁移/升级代码。改 schema 直接删库重装（见类 KDoc）。
    version = 1,
    // schema 落盘 app/schemas/，为将来的迁移重建留真相起点
    exportSchema = true
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
         * 没有任何迁移代码，也不开破坏式回退（AGENTS §2：未发布，改 schema 直接删库重装）。
         *
         * 历史迁移链（v1→v2 等）已全部删除：它们与实体声明冲突、链路本身也不完整，
         * 属于"可能不可达、但一旦可达就炸库"的代码，留着只有负价值。
         * 版本已归零为 1，不存在"旧库 → 新库"的路径，因此也不需要
         * `fallbackToDestructiveMigration()`（它只在版本变大时生效）。
         */
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .build()
                    .also { INSTANCE = it }
            }
        }

        /**
         * 仅测试用：关闭并丢弃单例。
         *
         * Robolectric 每个**测试方法**都重建沙箱文件系统，而 companion 单例跨方法
         * 存活 —— 复用的实例持有指向已删除库文件的失效句柄，事务状态错乱
         * （症状是第二条测试起 `clearAllTables` 报 "no current transaction"）。
         * 与 `CurrentDateHolder.resetForTest` 同一模式。生产代码不得调用。
         */
        fun resetForTest() {
            synchronized(this) {
                try {
                    INSTANCE?.close()
                } catch (_: Throwable) {
                    // 连接已失效时 close 本身也可能抛；目标是丢掉引用，不必留痕
                }
                INSTANCE = null
            }
        }
    }
}
