package com.mcxiaoke.carromed.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.entity.PolicyTimeEntity
import com.mcxiaoke.carromed.core.data.entity.SchedulePolicyEntity
import com.mcxiaoke.carromed.core.data.model.PolicyType
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import com.mcxiaoke.carromed.core.data.model.TransactionType
import com.mcxiaoke.carromed.core.domain.engine.SlotProjectionEngine
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
 * 备份的**字段保全**测试（对应 A5 计划里的 I9，先在备份路径上落地）。
 *
 * ## 这条测试是为了钉死一个已经发生过的真实数据丢失
 *
 * A4 之前的 `appSettings` 导出只写了 `key` 与 `value`，
 * 而 `AppSettingEntity` 还有一列 `updatedAt` —— **恢复即永久丢失，且毫无迹象**。
 *
 * 之所以没人发现，是因为旧实现没有任何测试跑过"导出 → 导入 → 再导出"，
 * 而且手写 `JSONObject` 映射**没有 schema**，导出与导入两个方向要人肉保持同步。
 *
 * ## 为什么用手写期望值而不是"导出→导入→再导出相等"
 *
 * 往返相等只能发现**单向**的遗漏（导出漏了 A、也漏了导入 A，往返仍相等）。
 * 本测试把期望的 [BackupFile] **手写出来**，于是：
 * - 导出漏字段 ⇒ 实际值与手写期望不符 ⇒ 变红
 * - 字段张冠李戴（`changeAmount` 拷到了 `balanceAfter`）⇒ 变红
 * - 字段顺序/类型不匹配 ⇒ 编译或断言变红
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class BackupFieldPreservationTest {

    private lateinit var db: AppDatabase
    private lateinit var service: DoseTrackingService

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val today: LocalDate = LocalDate.of(2026, 9, 28)
    /** 固定时间戳，让"导出→导入→再导出"可比对 */
    private val now = 1_790_574_580_000L

    @Before
    fun setup() = runTest {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        service = DoseTrackingService(db)
    }

    @After
    @Throws(IOException::class)
    fun tearDown() = db.close()

    /**
     * 塞一个**每一列都取非默认值**的药品。
     *
     * 用默认值的话，"这一列根本没被导出" 与 "这一列恰好等于默认值" 在断言里长得一样，
     * 测试就成了恒真断言 —— 这类恒真测试本项目已经吃过两次亏。
     */
    private suspend fun seedEverythingDistinctive(): Long {
        val medId = db.medicationDao().insert(
            MedicationEntity(
                name = "环孢素",
                alias = "别名≠空",
                category = "处方药",
                form = "胶囊",
                unit = "粒",
                colorHex = "#FF00AA",
                defaultDose = 2500,            // 非默认 1000
                description = "每日空腹",
                precautions = listOf("忌葡萄柚", "监测血药浓度"),
                noticeShort = "温水送服",
                minStockAlert = 30000,          // 非默认 0
                isStockTracked = true,          // 非默认 false
                expiryDate = "2027-01-31",      // 非默认 ""
                // ⚠️ 不能为 true：P2#4 修复后归档药按"无计划"投影，reconcileSchedule
                // 不再为它物化槽位，下面的槽位/事实/流水种子全都建不起来。
                // isArchived=true 的导出保全由本文件的 `归档位原样导出` 测试单独覆盖。
                isArchived = false,
                createdAt = 1_700_000_000_000L,
                updatedAt = 1_700_000_111_111L
            )
        )
        db.reminderSettingsDao().insert(
            com.mcxiaoke.carromed.core.data.entity.ReminderSettingsEntity(
                medicationId = medId,
                isCriticalReminder = true,
                snoozeMinutes = 17,
                advanceMinutes = 23,
                // ⚠️ 必须取一个**已经过去**的日子。
                // 若写成未来日期（如 2026-10-05），暂停会参与投影把这个药今天的
                // 槽位**全部压掉**，于是下面 `getSlotsForDate(today).first()`
                // 抛 "List is empty" —— 报错完全指不到病根。
                // 取过去的日期既覆盖了 `pausedUntil` 的非空往返，又不影响投影。
                pausedUntil = "2026-09-20"
            )
        )
        val policyId = db.schedulePolicyDao().savePolicyWithTimes(
            SchedulePolicyEntity(
                medicationId = medId,
                policyType = PolicyType.INTERVAL,
                intervalDays = 3,               // 非默认 1
                daysOfWeek = listOf(1, 3, 5),   // 非默认 emptyList
                cycleOnDays = 21,
                cycleOffDays = 7,
                // 起始日取今天往前 6 天：INTERVAL/3 下 daysDiff=6 ⇒ 6%3==0 ⇒ **今天确实是排班日**。
                // 取 −5 会让 5%3≠0，槽位列表为空，测试会以 "List is empty" 这种
                // 完全指不到病根的方式失败。
                startDate = today.minusDays(6).toString(),
                endDate = today.plusDays(60).toString(),   // 非默认 null
                isActive = true,
                version = 9,                     // 非默认 1
                createdAt = 1_700_000_222_222L
            ),
            listOf(
                PolicyTimeEntity(
                    policyId = 0,
                    timeOfDay = "07:45",         // 非默认
                    doseAmount = 1750,            // 非默认 1000
                    label = "早餐前",
                    sortOrder = 4                 // 非默认 0
                )
            )
        )

        // 槽位：由投影引擎生成，保证与生产路径一致
        service.reconcileSchedule(medId, today, today.plusDays(1))
        db.doseSlotDao().forceStatusForTest(
            db.doseSlotDao().getSlotsForDate(today.toString()).first().id,
            SlotStatus.SNOOZED,
            1_790_000_000_000L
        )

        // 服药事实 + 库存流水：走真实服务路径，不手写表
        val slot = db.doseSlotDao().getSlotsForDate(today.toString()).first()
        service.takeDose(slotId = slot.id, note = "随早餐服下")
        service.refillStock(medId, addedAmount = 30f, note = "药房采购", batchNumber = "B-2026-01", expiryDate = "2027-06-30")
        // 再手动补录一条（slotId 为 null，验证该字段能往返）
        service.logManualDose(medId, actualTs = now - 86_400_000L, doseAmount = 0.5f, note = "头痛临时加服")

        db.appSettingDao().insertAll(listOf(
            com.mcxiaoke.carromed.core.data.entity.AppSettingEntity(
                key = "night_silent_start",
                value = "23",
                updatedAt = 1_700_000_333_333L      // ⭐ 这一列 A4 之前根本没被导出
            )
        ))
        return policyId
    }

    @Test
    fun `每一列都被导出 且 值与手写期望完全一致`() = runTest {
        val policyId = seedEverythingDistinctive()
        val actual = DataExporter.buildBackup(db, now = now)

        val med = db.medicationDao().getAllMedications().single()
        val settings = db.reminderSettingsDao().getAll().single()
        val policy = db.schedulePolicyDao().getActivePolicyForMedication(med.id)!!
        val time = db.schedulePolicyDao().getTimesForPolicy(policyId).single()
        val slot = db.doseSlotDao().getSlotsForDate(today.toString()).first()
        val record = db.doseRecordDao().getAllRecords().first { it.slotId == slot.id }
        val manual = db.doseRecordDao().getAllRecords().first { it.slotId == null }
        val txs = db.inventoryTransactionDao().getAllTransactions()
        val setting = db.appSettingDao().getAllSettings().single()

        // 手写期望：任何一列漏导出 / 拷错字段都会让这一条变红
        val expected = BackupFile(
            app = "CarroMed",
            formatVersion = BackupFormatVersion.CURRENT.code,
            exportedAt = now,
            exportedAtText = actual.exportedAtText,
            medications = listOf(
                MedicationBackup(
                    id = med.id,
                    name = "环孢素",
                    alias = "别名≠空",
                    category = "处方药",
                    form = "胶囊",
                    unit = "粒",
                    colorHex = "#FF00AA",
                    defaultDose = 2500,
                    description = "每日空腹",
                    precautions = listOf("忌葡萄柚", "监测血药浓度"),
                    noticeShort = "温水送服",
                    stockMilli = txs.sumOf { it.changeAmount },
                    minStockAlert = 30000,
                    isStockTracked = true,
                    expiryDate = "2027-01-31",
                    isArchived = false,
                    createdAt = 1_700_000_000_000L,
                    updatedAt = 1_700_000_111_111L
                )
            ),
            reminderSettings = listOf(
                ReminderSettingsBackup(
                    medicationId = med.id,
                    isCriticalReminder = true,
                    snoozeMinutes = 17,
                    advanceMinutes = 23,
                    pausedUntil = "2026-09-20"
                )
            ),
            schedulePolicies = listOf(
                SchedulePolicyBackup(
                    id = policy.id,
                    medicationId = med.id,
                    policyType = PolicyType.INTERVAL,
                    intervalDays = 3,
                    daysOfWeek = listOf(1, 3, 5),
                    cycleOnDays = 21,
                    cycleOffDays = 7,
                    startDate = today.minusDays(6).toString(),
                    endDate = today.plusDays(60).toString(),
                    isActive = policy.isActive,
                    version = 9,
                    createdAt = 1_700_000_222_222L
                )
            ),
            policyTimes = listOf(
                PolicyTimeBackup(
                    id = time.id,
                    policyId = policyId,
                    timeOfDay = "07:45",
                    doseAmount = 1750,
                    label = "早餐前",
                    sortOrder = 4
                )
            ),
            doseSlots = listOf(
                DoseSlotBackup(
                    id = slot.id,
                    medicationId = slot.medicationId,
                    policyId = slot.policyId,
                    scheduledDate = slot.scheduledDate,
                    scheduledTime = slot.scheduledTime,
                    scheduledTs = slot.scheduledTs,
                    doseAmount = slot.doseAmount,
                    status = SlotStatus.COMPLETED,
                    actualTakenTs = slot.actualTakenTs,
                    snoozeUntilTs = slot.snoozeUntilTs,
                    createdAt = slot.createdAt
                )
            ),
            // 顺序必须与 `DoseRecordDao.getAllRecords()`（`ORDER BY actual_ts ASC`）一致。
            // 手动补录那条的 actualTs 是**昨天**，排在今天的计划打卡**前面** ——
            // 这里写反了会得到一个看起来毫不相关的 diff。
            doseRecords = listOf(
                DoseRecordBackup(
                    id = manual.id,
                    slotId = null,
                    medicationId = med.id,
                    actualTs = manual.actualTs,
                    doseTaken = manual.doseTaken,
                    status = manual.status,
                    isRetrospective = manual.isRetrospective,
                    note = manual.note,
                    createdAt = manual.createdAt
                ),
                DoseRecordBackup(
                    id = record.id,
                    slotId = slot.id,
                    medicationId = med.id,
                    actualTs = record.actualTs,
                    doseTaken = record.doseTaken,
                    status = record.status,
                    isRetrospective = false,
                    note = "随早餐服下",
                    createdAt = record.createdAt
                )
            ),
            inventoryTransactions = txs.map {
                InventoryTransactionBackup(
                    id = it.id,
                    medicationId = it.medicationId,
                    recordId = it.recordId,
                    changeAmount = it.changeAmount,
                    balanceAfter = it.balanceAfter,
                    txType = it.txType,
                    note = it.note,
                    batchNumber = it.batchNumber,
                    expiryDate = it.expiryDate,
                    createdAt = it.createdAt
                )
            },
            appSettings = listOf(
                AppSettingBackup(
                    key = setting.key,
                    value = setting.value,
                    updatedAt = 1_700_000_333_333L
                )
            )
        )

        assertThat(actual).isEqualTo(expected)
    }

    @Test
    fun `appSettings 的 updatedAt 不再丢失（这是本测试存在的直接原因）`() = runTest {
        seedEverythingDistinctive()
        val text = DataExporter.encodeBackup(DataExporter.buildBackup(db, now = now))
        // 直接在 JSON 文本里找这一列：它在文件里必须真实存在
        assertThat(text).contains("\"updatedAt\": 1700000333333")
    }

    @Test
    fun `别名与备注里的特殊字符能原样往返`() = runTest {
        val medId = db.medicationDao().insert(
            MedicationEntity(
                name = "含\"引号\"与,逗号",
                alias = "换行\n制表\t",
                precautions = listOf("a\"b", "c,d")
            )
        )
        db.reminderSettingsDao().ensureDefaults(medId)
        val backup = DataExporter.buildBackup(db, now = now)
        val round = DataExporter.decodeBackup(DataExporter.encodeBackup(backup))
        assertThat(round.medications.single().name).isEqualTo("含\"引号\"与,逗号")
        assertThat(round.medications.single().alias).isEqualTo("换行\n制表\t")
        assertThat(round.medications.single().precautions).containsExactly("a\"b", "c,d").inOrder()
    }

    /**
     * `isArchived` 的非默认值覆盖（原 fixture 曾用 `isArchived = true` 兼任，
     * P2#4 修复后归档药不再物化排班，fixture 改回 false——这一列的
     * "漏导出 ≡ 等于默认值" 盲区由本测试单独钉住）。
     */
    @Test
    fun `归档位原样导出`() = runTest {
        val medId = db.medicationDao().insert(
            MedicationEntity(name = "已停药的药", isArchived = true)
        )
        db.reminderSettingsDao().ensureDefaults(medId)

        val backup = DataExporter.buildBackup(db, now = now)
        assertThat(backup.medications.single().isArchived).isTrue()
    }
}
