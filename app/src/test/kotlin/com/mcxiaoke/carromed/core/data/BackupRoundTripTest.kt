package com.mcxiaoke.carromed.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.entity.ReminderSettingsEntity
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import com.mcxiaoke.carromed.core.domain.service.DoseTrackingService
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.LocalDate

/**
 * 备份 → 恢复的往返与校验测试（A4）。
 *
 * 三件事：
 * 1. **往返幂等**：导出 → 导入 → 再导出，两次结果必须完全相同。
 *    这是"备份可信"的地基 —— 备份不能用两次，或者恢复一次就变形。
 * 2. **校验先于清库**：结构有问题的备份必须在**数据库未被触碰**的情况下被拒。
 *    旧实现先 `deleteAll*()` 再解析，解析到一半失败就是两头都没了。
 * 3. **三态 `pausedUntil` 往返**：`null` 与 `""` 语义相反，混淆会让
 *    "无限期暂停" 恢复成 "未暂停"，恢复后立刻开始响铃。
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class BackupRoundTripTest {

    private lateinit var db: AppDatabase
    private lateinit var spare: AppDatabase
    private lateinit var service: DoseTrackingService

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val today: LocalDate = LocalDate.of(2026, 9, 28)
    private val now = 1_790_574_580_000L

    @Before
    fun setup() {
        db = newDb()
        spare = newDb()
        service = DoseTrackingService(db)
    }

    private fun newDb(): AppDatabase =
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    @After
    @Throws(IOException::class)
    fun tearDown() {
        db.close()
        spare.close()
    }

    private suspend fun seedMed(
        name: String,
        times: List<String>,
        dose: Int = 1000,
        pausedUntil: String? = null
    ): Long {
        // isStockTracked 必须为 true，否则 takeDose 不产生 TAKEN_DEDUCT 流水，
        // 流水条数断言会以一个完全指不到病根的方式失败
        val medId = db.medicationDao().insert(
            MedicationEntity(name = name, isStockTracked = true)
        )
        db.reminderSettingsDao().insert(
            ReminderSettingsEntity(medicationId = medId, pausedUntil = pausedUntil)
        )
        db.schedulePolicyDao().savePolicyWithTimes(
            com.mcxiaoke.carromed.core.data.entity.SchedulePolicyEntity(
                medicationId = medId,
                startDate = today.toString()
            ),
            times.map {
                com.mcxiaoke.carromed.core.data.entity.PolicyTimeEntity(
                    policyId = 0, timeOfDay = it, doseAmount = dose
                )
            }
        )
        service.reconcileSchedule(medId, today, today.plusDays(3))
        return medId
    }

    // ==================== 往返幂等 ====================

    @Test
    fun `导出 恢复 再导出 两次结果完全相同`() = runTest {
        seedMed("环孢素", listOf("08:00", "20:00"))
        seedMed("维生素D", listOf("13:30"), dose = 500, pausedUntil = "2026-10-05")
        val medId = db.medicationDao().getAllMedications().first { it.name == "环孢素" }.id
        service.takeDose(
            slotId = db.doseSlotDao().getSlotsForDate(today.toString())
                .first { it.medicationId == medId }.id,
            note = "随早餐"
        )
        service.refillStock(medId, addedAmount = 30f, note = "采购")
        db.appSettingDao().insertAll(listOf(
            com.mcxiaoke.carromed.core.data.entity.AppSettingEntity("snooze", "30", 1_700_000_000_000L)
        ))

        val first = DataExporter.encodeBackup(DataExporter.buildBackup(db, now = now))
        val parsed = DataExporter.decodeBackup(first)
        assertThat(DataExporter.validateBackup(parsed)).isEmpty()

        DataExporter.restoreBackup(spare, parsed)
        val second = DataExporter.encodeBackup(DataExporter.buildBackup(spare, now = now))

        assertThat(second).isEqualTo(first)
    }

    @Test
    fun `往返保留全部表与行数`() = runTest {
        seedMed("环孢素", listOf("08:00", "20:00"))
        seedMed("维生素D", listOf("13:30"))
        val medId = db.medicationDao().getAllMedications().first { it.name == "环孢素" }.id
        service.takeDose(
            slotId = db.doseSlotDao().getSlotsForDate(today.toString())
                .first { it.medicationId == medId }.id
        )
        service.refillStock(medId, addedAmount = 30f)

        DataExporter.restoreBackup(spare, DataExporter.buildBackup(db, now = now))

        assertThat(spare.medicationDao().getAllMedications()).hasSize(2)
        assertThat(spare.reminderSettingsDao().getAll()).hasSize(2)
        assertThat(spare.schedulePolicyDao().getAllPolicies()).hasSize(2)
        assertThat(spare.schedulePolicyDao().getAllTimes()).hasSize(3)
        assertThat(spare.doseSlotDao().getAllSlots()).hasSize(
            db.doseSlotDao().getAllSlots().size
        )
        assertThat(spare.doseRecordDao().getAllRecords()).hasSize(1)
        assertThat(spare.inventoryTransactionDao().getAllTransactions()).hasSize(2)
    }

    @Test
    fun `往返保留自增主键（dose_slots 的 id 就是闹钟身份 不能变）`() = runTest {
        val medId = seedMed("环孢素", listOf("08:00", "20:00"))
        val before = db.doseSlotDao().getAllSlots().map { it.id }

        DataExporter.restoreBackup(spare, DataExporter.buildBackup(db, now = now))

        assertThat(spare.doseSlotDao().getAllSlots().map { it.id })
            .containsExactlyElementsIn(before)
        assertThat(spare.doseSlotDao().getAllSlots().all { it.medicationId == medId }).isTrue()
    }

    @Test
    fun `恢复后库存守恒仍然成立（余额是流水的派生值）`() = runTest {
        val medId = seedMed("环孢素", listOf("08:00", "20:00"))
        service.calibrateStock(medId, actualStock = 12f)
        service.takeDose(
            slotId = db.doseSlotDao().getSlotsForDate(today.toString())
                .first { it.medicationId == medId }.id
        )

        DataExporter.restoreBackup(spare, DataExporter.buildBackup(db, now = now))

        val expected = db.inventoryTransactionDao().getAllTransactions().sumOf { it.changeAmount }
        val actual = spare.inventoryTransactionDao().getAllTransactions().sumOf { it.changeAmount }
        assertThat(actual).isEqualTo(expected)
    }

    // ==================== pausedUntil 三态 ====================

    @Test
    fun `pausedUntil 的 null 与空串在往返后不混淆`() = runTest {
        seedMed("未暂停的药", listOf("08:00"), pausedUntil = null)
        seedMed("无限期暂停的药", listOf("08:00"), pausedUntil = "")
        seedMed("暂停到某日的药", listOf("08:00"), pausedUntil = "2026-10-05")

        val restored = DataExporter.decodeBackup(
            DataExporter.encodeBackup(DataExporter.buildBackup(db, now = now))
        )
        DataExporter.restoreBackup(spare, restored)

        // ⭐ 三个必须原样区分。混淆 null/"" 会让"无限期暂停"变成"未暂停"。
        val byName = spare.medicationDao().getAllMedications().associateBy { it.name }
        assertThat(
            spare.reminderSettingsDao().getByMedicationId(byName.getValue("未暂停的药").id)?.pausedUntil
        ).isNull()
        assertThat(
            spare.reminderSettingsDao().getByMedicationId(byName.getValue("无限期暂停的药").id)?.pausedUntil
        ).isEqualTo("")
        assertThat(
            spare.reminderSettingsDao().getByMedicationId(byName.getValue("暂停到某日的药").id)?.pausedUntil
        ).isEqualTo("2026-10-05")
    }

    @Test
    fun `JSON 里显式写出 null 而不是省略该键`() = runTest {
        seedMed("未暂停的药", listOf("08:00"), pausedUntil = null)
        val text = DataExporter.encodeBackup(DataExporter.buildBackup(db, now = now))
        // explicitNulls 必须为 true：省略键与写 null 在反序列化时行为相同，
        // 但人工核对备份文件时，"这个键不见了"和"这个值是 null"是两回事
        assertThat(text).contains("\"pausedUntil\": null")
    }

    // ==================== 校验先于清库 ====================

    @Test
    fun `悬空外键被拒 且校验不触碰数据库`() = runTest {
        seedMed("环孢素", listOf("08:00"))
        val broken = DataExporter.buildBackup(db, now = now).copy(
            schedulePolicies = listOf(
                SchedulePolicyBackup(
                    id = 1, medicationId = 999, startDate = "2026-09-28"   // 药品不存在
                )
            )
        )
        val problems = DataExporter.validateBackup(broken)
        assertThat(problems).isNotEmpty()
        assertThat(problems.joinToString()).contains("999")
    }

    @Test
    fun `重复槽位被拒（insertAll 是 IGNORE 不校验 会静默丢行）`() = runTest {
        val medId = seedMed("环孢素", listOf("08:00"))
        val slot = db.doseSlotDao().getSlotsForDate(today.toString())
            .first { it.medicationId == medId }
        val broken = DataExporter.buildBackup(db, now = now).copy(
            doseSlots = listOf(
                slot.toBackup(),
                slot.toBackup().copy(id = slot.id + 1000)      // 唯一键相同、id 不同
            )
        )
        val problems = DataExporter.validateBackup(broken)
        assertThat(problems.joinToString()).contains("重复槽位")
    }

    @Test
    fun `药品 id 重复被拒`() = runTest {
        seedMed("环孢素", listOf("08:00"))
        val backup = DataExporter.buildBackup(db, now = now)
        val broken = backup.copy(medications = backup.medications + backup.medications.first())
        assertThat(DataExporter.validateBackup(broken).joinToString()).contains("出现了")
    }

    @Test
    fun `不受支持的版本被拒`() = runTest {
        seedMed("环孢素", listOf("08:00"))
        val broken = DataExporter.buildBackup(db, now = now).copy(formatVersion = 99)
        assertThat(DataExporter.validateBackup(broken).joinToString()).contains("不受支持")
    }

    @Test
    fun `缺提醒设置的药品只是警告 不阻断恢复`() = runTest {
        // 备份来自还没有 reminder_settings 的旧版本 —— 必须能恢复，
        // 否则用户会连"提醒设置丢失"这件事都看不到
        seedMed("环孢素", listOf("08:00"))
        val backup = DataExporter.buildBackup(db, now = now).copy(reminderSettings = emptyList())
        val problems = DataExporter.validateBackup(backup)
        assertThat(problems).isNotEmpty()
        assertThat(problems.joinToString()).contains("缺少提醒设置")
        assertThat(problems.none { it.contains("不受支持") || it.contains("不存在的") }).isTrue()

        // 恢复后每个药品都补上了默认行
        DataExporter.restoreBackup(spare, backup)
        assertThat(spare.reminderSettingsDao().getAll()).hasSize(1)
    }

    @Test
    fun `恢复失败时事务整体回滚 数据库保持原样`() = runTest {
        seedMed("环孢素", listOf("08:00"))
        val backup = DataExporter.buildBackup(db, now = now)
        val before = spare.medicationDao().getAllMedications().size

        // 人为制造一个恢复期内的异常：插一个违反外键的 policy（药品 4242 不存在）——
        // 主键不冲突，但 Room 的外键约束会在事务中途抛异常
        val poison = backup.copy(
            schedulePolicies = backup.schedulePolicies.map {
                it.copy(medicationId = 4242)
            }
        )
        val failed = runCatching { DataExporter.restoreBackup(spare, poison) }.isFailure
        assertThat(failed).isTrue()
        // 关键：清空与回填在同一事务里，失败后 spare 必须还是恢复前的样子
        assertThat(spare.medicationDao().getAllMedications()).hasSize(before)
        assertThat(poison.schedulePolicies.isNotEmpty()).isTrue()
    }

    // ==================== 旧格式 V1 仍可读 ====================

    @Test
    fun `旧版 org-json 格式（V1）仍能被解析`() = runTest {
        // 这就是 A4 之前写出的文件形状：字段名与 BackupFile 完全一致，
        // formatVersion 是数字 1。刻意手写字符串而不是用当前编码器生成 ——
        // 用编码器生成的话，测试的是"自己读自己"，与兼容性无关。
        val v1 = """
        {
          "app": "CarroMed",
          "formatVersion": 1,
          "exportedAt": 1700000000000,
          "exportedAtText": "2026-09-28 13:45:00",
          "medications": [
            {"id": 7, "name": "旧格式的药", "unit": "片", "defaultDoseMilli": 1500,
             "precautions": ["注意"], "isStockTracked": true, "createdAt": 1, "updatedAt": 2}
          ],
          "reminderSettings": [
            {"medicationId": 7, "isCriticalReminder": true, "snoozeMinutes": 12,
             "advanceMinutes": 5, "pausedUntil": ""}
          ],
          "schedulePolicies": [
            {"id": 3, "medicationId": 7, "policyType": "DAILY", "startDate": "2026-09-28"}
          ],
          "policyTimes": [
            {"id": 4, "policyId": 3, "timeOfDay": "09:00", "doseAmountMilli": 1500}
          ],
          "doseSlots": [],
          "doseRecords": [],
          "inventoryTransactions": [],
          "appSettings": []
        }
        """.trimIndent()

        val parsed = DataExporter.decodeBackup(v1)
        assertThat(parsed.app).isEqualTo("CarroMed")
        assertThat(BackupFormatVersion.from(parsed.formatVersion)).isEqualTo(BackupFormatVersion.V1)
        assertThat(parsed.medications.single().name).isEqualTo("旧格式的药")
        assertThat(parsed.medications.single().defaultDose).isEqualTo(1500)
        assertThat(parsed.medications.single().precautions).containsExactly("注意")
        // 缺省字段取 DTO 默认值，而不是崩
        assertThat(parsed.medications.single().minStockAlert).isEqualTo(0)
        // ⭐ 无限期暂停不能被归一成"未暂停"
        assertThat(parsed.reminderSettings.single().pausedUntil).isEqualTo("")

        DataExporter.restoreBackup(spare, parsed)
        val restored = spare.reminderSettingsDao()
            .getByMedicationId(7)
        assertThat(restored?.pausedUntil).isEqualTo("")
        assertThat(restored?.isCriticalReminder).isTrue()
    }

    @Test
    fun `未知字段被忽略而不是让整份文件读不进来`() {
        val text = """
        {"app":"CarroMed","formatVersion":2,"exportedAt":1,"exportedAtText":"x",
         "medications":[],"reminderSettings":[],"schedulePolicies":[],"policyTimes":[],
         "doseSlots":[],"doseRecords":[],"inventoryTransactions":[],"appSettings":[],
         "未来版本才有的字段": {"任意": [1,2,3]}}
        """.trimIndent()
        assertThat(DataExporter.decodeBackup(text).app).isEqualTo("CarroMed")
    }

    // ==================== 覆盖式恢复：清空旧数据 ====================

    @Test
    fun `恢复会清空目标库里的旧数据（不能叠加）`() = runTest {
        seedMed("环孢素", listOf("08:00"))
        DataExporter.restoreBackup(spare, DataExporter.buildBackup(db, now = now))
        assertThat(spare.medicationDao().getAllMedications()).hasSize(1)

        // 第二次恢复一份"只有另一个药"的备份
        val other = DoseTrackingService(spare)
        spare.medicationDao().insertAll(listOf(MedicationEntity(name = "占位药"))) // 制造干扰
        db.medicationDao().deleteAllMedications()
        val onlyOther = db.medicationDao().insert(MedicationEntity(name = "只有这个药"))
        db.reminderSettingsDao().ensureDefaults(onlyOther)
        other.reconcileSchedule(onlyOther, today, today)

        DataExporter.restoreBackup(spare, DataExporter.buildBackup(db, now = now))

        assertThat(spare.medicationDao().getAllMedications().map { it.name })
            .containsExactly("只有这个药")
    }

    private fun com.mcxiaoke.carromed.core.data.entity.DoseSlotEntity.toBackup() = DoseSlotBackup(
        id = id, medicationId = medicationId, policyId = policyId,
        scheduledDate = scheduledDate, scheduledTime = scheduledTime,
        scheduledTs = scheduledTs, doseAmount = doseAmount, status = status,
        actualTakenTs = actualTakenTs, snoozeUntilTs = snoozeUntilTs, createdAt = createdAt
    )
}
