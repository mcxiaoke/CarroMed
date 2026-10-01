package com.mcxiaoke.carromed.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.mcxiaoke.carromed.BuildConfig
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
 * ## `version` 的版本史（改 schema 就必须 +1，不是可选项）
 *
 * | 版本 | 步骤 | 变更 |
 * | :---: | --- | --- |
 * | 1 | 初版 | 7 张表 |
 * | 2 | — | （历史迁移已随"不需要迁移代码"的决策删除） |
 * | 3 | A1 | 删 `medications.current_stock`；6 列 `REAL` → 整数毫单位；`RecordStatus.RETROSPECTIVE` 改布尔列 |
 * | 4 | A2 | 新增 `reminder_settings` 表；`medications` 删 4 列 |
 * | 5 | A3 | `dose_slots` 的 `(medication_id, scheduled_date, scheduled_time)` 改 **UNIQUE 索引** |
 * | 6 | M8-5 | 删 `medications.icon_name`（有列、有备份字段，但全链路**无写入、无消费**） |
 * | 7 | osbf P3-4 | `inventory_transactions` 加 `Index(record_id)`（改剂量/撤销按事实 id 聚合流水的高频过滤） |
 * | 8 | B5 | `inventory_transactions` 加 `note_key` 列（PLAN-I18N-20260930 D-C） |
 * | 9 | CODE-REVIEW | `dose_records` & `inventory_transactions` 外键改为 `RESTRICT` 阻止级联删历史 |
 *
 * ⚠️ **A1 当时漏升了版本（3 → 3）**，靠 A2 的 3 → 4 顺带补救。
 * 这属于**运气**不是设计。核实依据（反编译 `room-runtime-2.6.1.aar` 的
 * `androidx/room/RoomOpenHelper.class`）：
 *
 * - `onOpen` 只有两条指令：`super.onOpen(db); checkIdentity(db);`，
 *   **没有 Exception table** ⇒ 异常直接向上抛。
 * - `checkIdentity` 在 identity hash 不匹配时 `athrow IllegalStateException`，
 *   同样**没有 Exception table**。
 * - 破坏性回退只挂在 `onUpgrade`（版本**变大**时）这条路径上。
 *   版本不变 ⇒ `onUpgrade` 根本不会被调用 ⇒ `fallbackToDestructiveMigration()`
 *   **完全不覆盖**这个身份校验。
 *
 * 后果：版本不变而 schema 变了 ⇒ 冷启动直接崩
 * `IllegalStateException: Room cannot verify the data integrity`。
 * 走查脚本永远发现不了，因为每次都 `--clear` 清库。
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
    // v9：dose_records 与 inventory_transactions 外键改为 RESTRICT（阻止删药时级联抹杀核心历史数据）。
    // 未发布不写迁移：schema 变更一律删库重装。
    version = 9,
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
         * ⚠️ schema v1 → v2 迁移**已删除**（M5-9）。发布前不需要它，且它本身是坏的。
         *
         * ## 为什么删除而不是保留
         *
         * 两份独立审查（sba / DB）各自发现、修复计划交叉确认。
         *
         * 1. **它建的索引与实体声明冲突**：迁移里
         *    `CREATE INDEX IF NOT EXISTS index_dose_slots_medication_id_scheduled_date_scheduled_time`
         *    建的是一个**非 UNIQUE** 索引，而 [DoseSlotEntity] 上同名索引声明
         *    `unique = true`。Room 的 schema 校验会看到这个矛盾并抛异常 ——
         *    也就是说这段"迁移"一旦执行，就把用户的库变成打不开的状态。
         * 2. **迁移链本身不完整**：`version = 5` 而链上只有 1→2。
         *    Room 的 `findMigrationPath(1, 5)` 要求**完整链路**，缺 2→3/3→4/4→5
         *    时返回 null，于是走 `fallbackToDestructiveMigration()` 整库重建。
         *
         * 关于第 2 条，审查之间有分歧（一份认为"部分链路会执行"、
         * 一份认为"因缺边而从不执行"），`exportSchema = false` 也没有 v1 schema JSON
         * 可供在真库上重放，**当前无法实证**。但两说的**修复动作完全相同**，
         * 分歧只影响"风险是否可达"的表述 —— 所以直接删掉，不留一个
         * "可能可达、可能不可达、但一旦可达就炸库"的代码。
         *
         * `AGENTS.md` 已明确：项目未公开发布，**不需要任何迁移或兼容旧版本的代码**。
         * 发布前按 §2 的纪律重建迁移并逐条验证。
         */

        /**
         * ⚠️ **破坏式重建仅限 debug 构建**
         *
         * ## 为什么开发期打开破坏式重建
         *
         * v2 → v3 删了 `medications.current_stock` 一列，并把 5 个金额列从 `REAL`
         * 改成了整数毫单位。这类改写在 SQLite 里必须靠"建新表 + 拷数据 + 改名"实现，
         * 是一整套迁移代码 —— 而 `AGENTS.md` 已明确：项目尚未公开发布，
         * **不需要任何迁移或兼容旧版本的代码**。
         *
         * 与其写一段 60 行、只在开发期被执行一次、此后永远不被覆盖的迁移 SQL，
         * 不如让 Room 直接重建库：开发期数据可从 `dev.SEED` 广播一键重建，损失为零。
         * （对比方案"静默崩溃"更糟：开发机上换台机器 clone 就起不来。）
         *
         * ## 为什么不能让 release 也走这条路
         *
         * 过去这里是裸调用 + "TODO(发布前删除)" —— 忘了删，release 版本不匹配时
         * 就会**静默清空用户全部服药历史**。现在改成 debug 门控：
         * release 构建不再回退，schema 不匹配会显式抛异常
         * （`IllegalStateException: Room cannot verify the data integrity`），
         * 比静默删库诚实。真正发布前，按 AGENTS §2 的纪律重建 `MIGRATION_*`
         * 并逐条验证。
         */
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .apply {
                        if (BuildConfig.DEBUG) {
                            fallbackToDestructiveMigration()
                        }
                    }
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
