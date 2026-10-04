package com.mcxiaoke.carromed.core.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.domain.service.DoseTrackingService
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.time.LocalDate

/**
 * 备份恢复的**端到端容错**测试（A5 补）。
 *
 * ## 为什么要单独一个文件
 *
 * `BackupRoundTripTest` 守的是"备份可信"（往返幂等、校验先于清库）。
 * 本文件守的是另一条、当时**完全没有测试**的性质：
 *
 * > **一条脏数据不能让用户的整个数据库变得不可用。**
 *
 * 这个要求在旧实现下是**不成立**的，而且失败方式很隐蔽：
 *
 * ```kotlin
 * // inspectText（预览）
 * val fatal = problems.filter { it.contains("不受支持") || it.contains("不存在的") }
 * // importBackup（实际恢复）
 * if (problems.isNotEmpty()) return Invalid(...)
 * ```
 *
 * 两处判据**不同源**。于是「药品缺少提醒设置」这条 warning（文案自己写着
 * "恢复时会补默认值"，`restoreBackup` 里也确实写了兜底）会：
 *
 * 1. 预览页显示"可以恢复，只是有 N 条提示"，用户点确认
 * 2. `importBackup` 返回 `Invalid`，**没有任何办法绕过**
 *
 * 用户在自己急需把数据救回来的时候被卡住，而错误信息读起来像是文件坏了。
 *
 * 这类缺陷结构上的特点是：**任何单点测试都测不到**。
 * 测 `validateBackup` 只能证明"它返回了哪些问题"，
 * 测 `restoreBackup` 只能证明"纯函数能补默认值"——
 * 两者都绿，而它们**组合起来**是坏的。所以必须有一组测试同时走完两条路径。
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class BackupResilienceTest {

    private lateinit var db: AppDatabase
    private lateinit var spare: AppDatabase
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val today: LocalDate = LocalDate.of(2026, 9, 28)

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        spare = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    @Throws(IOException::class)
    fun tearDown() {
        db.close()
        spare.close()
    }

    /** 一个有提醒设置、有槽位、有流水的药，够撑起"真的有数据要恢复"的场景 */
    private suspend fun seedMed(name: String): Long {
        val medId = db.medicationDao().insert(
            MedicationEntity(
                name = name, unit = "片", defaultDose = 1000,
                isStockTracked = true, minStockAlert = 5000
            )
        )
        db.reminderSettingsDao().ensureDefaults(medId)
        db.schedulePolicyDao().savePolicyWithTimes(
            com.mcxiaoke.carromed.core.data.entity.SchedulePolicyEntity(
                medicationId = medId, policyType = com.mcxiaoke.carromed.core.data.model.PolicyType.DAILY,
                intervalDays = 2, startDate = today.toString()
            ),
            listOf(
                com.mcxiaoke.carromed.core.data.entity.PolicyTimeEntity(
                    policyId = 0L, timeOfDay = "08:00", doseAmount = 1000
                )
            )
        )
        DoseTrackingService(db).reconcileSchedule(medId, today, today.plusDays(2))
        // ⚠️ 必须真的打一次卡：`dose_records` / `inventory_transactions` 为空时，
        // 构造"悬空 slotId / 重复主键"那几类脏数据就没有素材（第一版踩了这个坑，
        // 断言报的是 `MISSING_REMINDER_SETTINGS` 而不是目标 kind，完全指不到病根）。
        DoseTrackingService(db).takeDose(
            slotId = db.doseSlotDao().getSlotsForDate(today.toString())
                .first { it.medicationId == medId }.id,
            note = "哨兵打卡"
        )
        return medId
    }

    private fun writeBackupFile(json: String): Uri {
        val f = File(context.cacheDir, "backup-${System.nanoTime()}.json")
        f.writeText(json, Charsets.UTF_8)
        return Uri.fromFile(f)
    }

    // ================================================================
    // 1. 预览与恢复必须给出同一个决定
    // ================================================================

    /**
     * 核心防回归：任何被 `inspectBackup` **放行**的备份，`importBackup` 都不得拒绝。
     *
     * 这正是旧实现里"用户卡死"的那条路径。遍历所有 kind 逐一验证，
     * 所以将来新增一种 kind 时，这条测试会自动把它纳入检查。
     */
    @Test
    fun `预览放行的备份 实际恢复一定不被拒（两条路径判据同源）`() = runTest {
        val medId = seedMed("环孢素")
        val good = DataExporter.buildBackup(db, now = 1_757_000_000_000L)
        assertThat(DataExporter.validateBackup(good)).isEmpty()

        // 逐个 kind 造一份"只有这一类问题"的备份
        val rec = good.doseRecords.firstOrNull()
        val led = good.inventoryTransactions.firstOrNull()
        val pol = good.schedulePolicies.firstOrNull()
        val tim = good.policyTimes.firstOrNull()
        val dirty: Map<BackupProblemKind, BackupFile> = mapOf(
            BackupProblemKind.UNSUPPORTED_VERSION to good.copy(formatVersion = 99),
            BackupProblemKind.DANGLING_FK to good.copy(
                schedulePolicies = good.schedulePolicies.map { it.copy(medicationId = 9999) }
            ),
            BackupProblemKind.DANGLING_SLOT_REF to
                (if (rec != null) good.copy(
                    doseRecords = listOf(rec.copy(slotId = 987654))
                ) else good.copy(reminderSettings = emptyList())),
            BackupProblemKind.DANGLING_RECORD_REF to
                (if (led != null) good.copy(
                    inventoryTransactions = listOf(led.copy(recordId = 987654L))
                ) else good.copy(reminderSettings = emptyList())),
            BackupProblemKind.DUPLICATE_MEDICATION_ID to
                good.copy(medications = good.medications + good.medications.first()),
            BackupProblemKind.DUPLICATE_SLOT_KEY to good.copy(
                doseSlots = good.doseSlots + good.doseSlots.first().copy(id = 99999L)
            ),
            BackupProblemKind.DUPLICATE_RECORD_ID to
                (if (rec != null) good.copy(doseRecords = listOf(rec, rec.copy())) else good),
            BackupProblemKind.DUPLICATE_LEDGER_ID to
                (if (led != null) good.copy(inventoryTransactions = listOf(led, led.copy()))
                 else good.copy(reminderSettings = emptyList())),
            BackupProblemKind.DUPLICATE_POLICY_ID to
                (if (pol != null) good.copy(schedulePolicies = listOf(pol, pol.copy()))
                 else good.copy(reminderSettings = emptyList())),
            BackupProblemKind.DUPLICATE_POLICY_TIME_ID to
                (if (tim != null) good.copy(policyTimes = listOf(tim, tim.copy()))
                 else good.copy(reminderSettings = emptyList())),
            // ↓ M5-3：这三类的键**不是**自增 id，上面那个"id 重复"检查覆盖不到它们。
            // 缺了这里的 case，`assertThat(dirty.keys).containsExactlyElementsIn(entries)`
            // 会立刻报出"新增的 kind 没有对应用例" —— 这就是全遍历的价值。
            BackupProblemKind.DUPLICATE_REMINDER_SETTINGS to
                (if (good.reminderSettings.isNotEmpty()) good.copy(
                    reminderSettings = good.reminderSettings +
                        good.reminderSettings.first().copy(advanceMinutes = 30)
                ) else good.copy(reminderSettings = emptyList())),
            BackupProblemKind.DUPLICATE_APP_SETTING_KEY to good.copy(
                // 刻意**自己造**两行，而不是复制 `good.appSettings` ——
                // 全新库可能一条 `app_settings` 都没有（设置页还没被写过），
                // 那样这条 case 会静默退化成"什么都没造"，断言报的是
                // `expected to contain: DUPLICATE_APP_SETTING_KEY but was: []`，
                // 指不到"fixture 为空"这个真实原因。
                appSettings = listOf(
                    good.appSettings.firstOrNull()?.copy(key = "snooze_minutes", value = "30")
                        ?: AppSettingBackup("snooze_minutes", "30", 1L),
                    good.appSettings.firstOrNull()?.copy(key = "snooze_minutes", value = "45")
                        ?: AppSettingBackup("snooze_minutes", "45", 1L)
                )
            ),
            BackupProblemKind.MULTIPLE_ACTIVE_POLICIES to
                (if (pol != null) good.copy(
                    schedulePolicies = listOf(pol, pol.copy(id = pol.id + 1000, version = pol.version + 1))
                ) else good),
            // osbf P3-5：同计划 + 同时刻、**不同主键** —— 主键重复检查（上一行）
            // 抓不到它，IGNORE 也不会拦，只有 (policyId, timeOfDay) 检查能指到病根
            BackupProblemKind.DUPLICATE_POLICY_TIME to
                (if (tim != null) good.copy(
                    policyTimes = listOf(tim, tim.copy(id = tim.id + 5000))
                ) else good.copy(reminderSettings = emptyList())),
            BackupProblemKind.MISSING_REMINDER_SETTINGS to good.copy(reminderSettings = emptyList()),
            // §二-18：剂量量程。槽位剂量 0 会造出"闹钟照响、库存永不扣"的静默损坏，
            // 恢复是直写实体、绕过服务层守卫，只能在校验层拦。
            BackupProblemKind.INVALID_DOSE to good.copy(
                doseSlots = listOf(good.doseSlots.first().copy(doseAmount = 0))
            ),
            // §二-17：活跃且非 PRN 的计划没有任何时点 ⇒ 该药永远不提醒，必须拦住
            BackupProblemKind.EMPTY_SCHEDULE to good.copy(policyTimes = emptyList())
        )
        assertThat(dirty.keys).containsExactlyElementsIn(BackupProblemKind.entries.toSet())

        for ((kind, file) in dirty) {
            val problems = DataExporter.validateBackup(file)
            assertThat(problems.map { it.kind }).contains(kind)
            val fatal = problems.any { it.blocksRestore }

            val uri = writeBackupFile(DataExporter.encodeBackup(file))
            val preview = DataExporter.inspectBackup(context, uri)
            val restored = DataExporter.importBackup(context, spare, uri)

            // ★ 预览说能恢复（success）⇒ 恢复就**不能**是 Invalid
            if (preview.isSuccess) {
                assertThat(restored).isNotInstanceOf(DataExporter.RestoreResult.Invalid::class.java)
            } else {
                // 预览拒绝 ⇒ 恢复也必须拒绝，两边一致
                assertThat(restored).isInstanceOf(DataExporter.RestoreResult.Invalid::class.java)
            }

            // 分类与 kind 的语义一致：只有 MISSING_REMINDER_SETTINGS / DANGLING_SLOT_REF / DANGLING_RECORD_REF 不致命
            assertThat(fatal).isEqualTo(kind !in setOf(
                BackupProblemKind.MISSING_REMINDER_SETTINGS,
                BackupProblemKind.DANGLING_SLOT_REF,
                BackupProblemKind.DANGLING_RECORD_REF
            ))
            // 每轮之后清掉，避免相互影响（外键已改为 RESTRICT，通过 clearAllTables 按拓扑清空）
            spare.clearAllTables()
        }
        assertThat(medId).isGreaterThan(0L)
    }

    // ================================================================
    // 2. 致命问题必须拦住，且**在碰库之前**就拒
    // ================================================================

    @Test
    fun `药品 id 重复会静默丢子数据 所以必须拦住恢复`() = runTest {
        val medId = seedMed("环孢素")
        val good = DataExporter.buildBackup(db, now = 1_757_000_000_000L)
        val slotCountBefore = good.doseSlots.count { it.medicationId == medId }
        assertThat(slotCountBefore).isGreaterThan(0)

        val dup = good.copy(medications = good.medications + good.medications.first())
        val uri = writeBackupFile(DataExporter.encodeBackup(dup))

        assertThat(DataExporter.inspectBackup(context, uri).isFailure).isTrue()
        assertThat(DataExporter.importBackup(context, spare, uri))
            .isInstanceOf(DataExporter.RestoreResult.Invalid::class.java)
        // ★ 库没被动过
        assertThat(spare.medicationDao().getAllMedications()).isEmpty()
    }

    @Test
    fun `槽位唯一键重复会被 IGNORE 静默吞掉 所以必须拦住恢复`() = runTest {
        val medId = seedMed("环孢素")
        val good = DataExporter.buildBackup(db, now = 1_757_000_000_000L)
        val slot = good.doseSlots.first { it.medicationId == medId }

        val dup = good.copy(doseSlots = good.doseSlots + slot.copy(id = slot.id + 50_000))
        val uri = writeBackupFile(DataExporter.encodeBackup(dup))

        assertThat(DataExporter.inspectBackup(context, uri).isFailure).isTrue()
        assertThat(DataExporter.importBackup(context, spare, uri))
            .isInstanceOf(DataExporter.RestoreResult.Invalid::class.java)
        assertThat(spare.doseSlotDao().getAllSlots()).isEmpty()
    }

    // ================================================================
    // 3. 可恢复的脏数据：必须真能恢复，且结果正确
    // ================================================================

    @Test
    fun `缺提醒设置时恢复成功 且默认值被补上`() = runTest {
        val medId = seedMed("环孢素")
        val good = DataExporter.buildBackup(db, now = 1_757_000_000_000L).copy(reminderSettings = emptyList())
        val uri = writeBackupFile(DataExporter.encodeBackup(good))

        val preview = DataExporter.inspectBackup(context, uri)
        assertThat(preview.isSuccess).isTrue()

        val result = DataExporter.importBackup(context, spare, uri)
        assertThat(result).isInstanceOf(DataExporter.RestoreResult.Success::class.java)
        assertThat(spare.reminderSettingsDao().getByMedicationId(medId)).isNotNull()
        // 补的是默认值，不是乱码
        assertThat(spare.reminderSettingsDao().getByMedicationId(medId)!!.snoozeMinutes).isEqualTo(0)
    }

    /**
     * 一条脏的服药记录（指向不存在的药品）不该让**其他**数据一起陪葬 ——
     * 前提是它属于致命类，所以要么整库拒（当前行为），要么跳过它。
     *
     * 两种都算"没让整库不可用"，但**必须一致**：不能出现
     * "预览放行 → 恢复崩在事务里 → 快照白留"这种组合。
     */
    @Test
    fun `悬空服药记录被判为致命 且不会留下半恢复的库`() = runTest {
        val medId = seedMed("环孢素")
        val good = DataExporter.buildBackup(db, now = 1_757_000_000_000L)
        DoseTrackingService(db).takeDose(
            db.doseSlotDao().getSlotsForDate(today.toString()).first { it.medicationId == medId }.id
        )
        val withRecord = DataExporter.buildBackup(db, now = 1_757_000_000_000L)
        assertThat(withRecord.doseRecords).isNotEmpty()

        val poisoned = withRecord.copy(
            doseRecords = withRecord.doseRecords.map { it.copy(medicationId = 88888L) }
        )
        val uri = writeBackupFile(DataExporter.encodeBackup(poisoned))

        assertThat(DataExporter.inspectBackup(context, uri).isFailure).isTrue()
        val result = DataExporter.importBackup(context, spare, uri)
        assertThat(result).isInstanceOf(DataExporter.RestoreResult.Invalid::class.java)
        // ★ 拒绝发生时库必须是**原样**，不能是"清空了但没填回来"
        assertThat(spare.medicationDao().getAllMedications()).isEmpty()
        assertThat(good.medications).isNotEmpty()
    }

    // ================================================================
    // 4. 文件兼容性：BOM（osbf P3-6）
    // ================================================================

    /**
     * Windows 记事本"另存为 UTF-8"会给文件头加 BOM（`\uFEFF`）。
     * 旧实现不剥 BOM，一份内容完全正确的备份因三个不可见字节整份解析失败。
     */
    @Test
    fun `带 BOM 的备份文件照常恢复`() = runTest {
        seedMed("环孢素")
        val good = DataExporter.buildBackup(db, now = 1_757_000_000_000L)
        val uri = writeBackupFile("\uFEFF" + DataExporter.encodeBackup(good))

        val result = DataExporter.importBackup(context, spare, uri)
        assertThat(result).isInstanceOf(DataExporter.RestoreResult.Success::class.java)
        assertThat(spare.medicationDao().getAllMedications()).isNotEmpty()
    }
}
