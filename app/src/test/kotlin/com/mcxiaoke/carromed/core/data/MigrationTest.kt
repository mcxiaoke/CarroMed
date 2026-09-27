package com.mcxiaoke.carromed.core.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * schema v1 → v2 迁移测试
 *
 * 背景：本次移除了 `fallbackToDestructiveMigration()`。
 * 迁移失败必须显式崩溃，而不是静默清空用户一年份的服药历史 ——
 * 所以"迁移后既有数据仍在、新列默认值正确"必须被测试锁死。
 *
 * 实现说明：`MigrationTestHelper` 依赖 instrumentation，JVM 单元测试里不可用；
 * 因此这里用 Robolectric 的真实 SQLite 内核手工建 v1 库，再直接调用
 * [AppDatabase.MIGRATION_1_2] 的 `migrate()`，验证的正是生产环境会执行的那段 SQL。
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class MigrationTest {

    private lateinit var context: Context
    private val dbName = "migration-test.db"

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    /** 按 v1 的实体定义手工建表（与 Room 在 version=1 时生成的 DDL 保持一致） */
    private fun createV1Database(): SupportSQLiteOpenHelper {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `medications` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `name` TEXT NOT NULL,
                            `alias` TEXT,
                            `category` TEXT NOT NULL,
                            `form` TEXT NOT NULL,
                            `unit` TEXT NOT NULL,
                            `color_hex` TEXT NOT NULL,
                            `icon_name` TEXT NOT NULL,
                            `default_dose` REAL NOT NULL,
                            `description` TEXT NOT NULL,
                            `precautions` TEXT NOT NULL,
                            `notice_short` TEXT NOT NULL,
                            `current_stock` REAL NOT NULL,
                            `min_stock_alert` REAL NOT NULL,
                            `is_stock_tracked` INTEGER NOT NULL,
                            `is_paused` INTEGER NOT NULL,
                            `is_archived` INTEGER NOT NULL,
                            `created_at` INTEGER NOT NULL,
                            `updated_at` INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `inventory_transactions` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `medication_id` INTEGER NOT NULL,
                            `record_id` INTEGER,
                            `change_amount` REAL NOT NULL,
                            `balance_after` REAL NOT NULL,
                            `tx_type` TEXT NOT NULL,
                            `note` TEXT,
                            `created_at` INTEGER NOT NULL,
                            FOREIGN KEY(`medication_id`) REFERENCES `medications`(`id`)
                                ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `dose_slots` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `medication_id` INTEGER NOT NULL,
                            `policy_id` INTEGER NOT NULL,
                            `scheduled_date` TEXT NOT NULL,
                            `scheduled_time` TEXT NOT NULL,
                            `scheduled_ts` INTEGER NOT NULL,
                            `dose_amount` REAL NOT NULL,
                            `status` TEXT NOT NULL,
                            `actual_taken_ts` INTEGER,
                            `snooze_until_ts` INTEGER,
                            `created_at` INTEGER NOT NULL,
                            FOREIGN KEY(`medication_id`) REFERENCES `medications`(`id`)
                                ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config)
    }

    @Test
    @Throws(IOException::class)
    fun migrate_1_to_2_preservesDataAndAddsColumns() {
        // 1. 建 v1 库并塞入既有数据（含 precautions / isPaused / isArchived / 库存）
        val helper = createV1Database()
        helper.writableDatabase.apply {
            execSQL(
                """
                INSERT INTO medications
                (id, name, alias, category, form, unit, color_hex, icon_name,
                 default_dose, description, precautions, notice_short,
                 current_stock, min_stock_alert, is_stock_tracked,
                 is_paused, is_archived, created_at, updated_at)
                VALUES (1, '环孢素', '新赛斯平', '处方药 · 免疫', '软胶囊', '粒', '#8B5CF6', 'pill',
                        1.0, '说明文字', '整粒吞服禁嚼碎|||严禁与葡萄柚同食', '温水吞服',
                        30.0, 10.0, 1, 1, 1, 1000, 2000)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO inventory_transactions
                (id, medication_id, record_id, change_amount, balance_after, tx_type, note, created_at)
                VALUES (1, 1, NULL, 30.0, 30.0, 'REFILL', '初始入库', 1500)
                """.trimIndent()
            )

            // 2. 执行生产环境同一份迁移
            AppDatabase.MIGRATION_1_2.migrate(this)

            // 3. 既有业务数据必须完好
            query("SELECT name, alias, precautions, notice_short, is_paused, is_archived, current_stock FROM medications WHERE id = 1")
                .use { c ->
                    c.moveToFirst()
                    assertThat(c.getString(0)).isEqualTo("环孢素")
                    assertThat(c.getString(1)).isEqualTo("新赛斯平")
                    assertThat(c.getString(2)).isEqualTo("整粒吞服禁嚼碎|||严禁与葡萄柚同食")
                    assertThat(c.getString(3)).isEqualTo("温水吞服")
                    assertThat(c.getInt(4)).isEqualTo(1) // is_paused 保留
                    assertThat(c.getInt(5)).isEqualTo(1) // is_archived 保留
                    assertThat(c.getFloat(6)).isEqualTo(30.0f)
                }

            // 4. 新列存在且默认值正确
            query(
                "SELECT expiry_date, is_critical_reminder, snooze_minutes, advance_minutes FROM medications WHERE id = 1"
            ).use { c ->
                c.moveToFirst()
                assertThat(c.getString(0)).isEmpty()  // expiry_date
                assertThat(c.getInt(1)).isEqualTo(0) // is_critical_reminder
                assertThat(c.getInt(2)).isEqualTo(0) // snooze_minutes
                assertThat(c.getInt(3)).isEqualTo(0) // advance_minutes
            }

            // 5. inventory_transactions 新增列（老数据应为 NULL）
            query("SELECT batch_number, expiry_date FROM inventory_transactions WHERE id = 1")
                .use { c ->
                    c.moveToFirst()
                    assertThat(c.isNull(0)).isTrue()
                    assertThat(c.isNull(1)).isTrue()
                }

            // 6. 复合索引已建立
            var found = false
            query("PRAGMA index_list(dose_slots)").use { c ->
                while (c.moveToNext()) {
                    if (c.getString(1).contains("medication_id_scheduled_date_scheduled_time")) found = true
                }
            }
            assertThat(found).isTrue()
        }
        helper.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate_1_to_2_leavesEmptyDatabaseUsable() {
        // 空库迁移：用户首次安装后从 v1 升级（v1 库存在但没数据）也必须能正常建表，
        // 否则升级即崩溃。对应"装了旧版但没录入任何药品"这类真实场景。
        val helper = createV1Database()
        helper.writableDatabase.apply {
            AppDatabase.MIGRATION_1_2.migrate(this)
            query("SELECT COUNT(*) FROM medications").use { c ->
                c.moveToFirst()
                assertThat(c.getInt(0)).isEqualTo(0)
            }
            // 新列在空行上也可读
            execSQL(
                """
                INSERT INTO medications
                (id, name, alias, category, form, unit, color_hex, icon_name,
                 default_dose, description, precautions, notice_short,
                 current_stock, min_stock_alert, is_stock_tracked,
                 is_paused, is_archived, created_at, updated_at,
                 expiry_date, is_critical_reminder, snooze_minutes, advance_minutes)
                VALUES (2, '胰岛素', NULL, '慢病处方', '注射液', '支', '#3B82F6', 'pill',
                        1.0, '', '', '', 0.0, 5.0, 1, 0, 0, 3000, 3000,
                        '2027-06-30', 1, 15, 10)
                """.trimIndent()
            )
            query("SELECT expiry_date, is_critical_reminder, snooze_minutes, advance_minutes FROM medications WHERE id = 2")
                .use { c ->
                    c.moveToFirst()
                    assertThat(c.getString(0)).isEqualTo("2027-06-30")
                    assertThat(c.getInt(1)).isEqualTo(1)
                    assertThat(c.getInt(2)).isEqualTo(15)
                    assertThat(c.getInt(3)).isEqualTo(10)
                }
        }
        helper.close()
    }
}
