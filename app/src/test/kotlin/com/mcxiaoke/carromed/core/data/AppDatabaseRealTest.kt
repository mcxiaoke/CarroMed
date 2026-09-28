package com.mcxiaoke.carromed.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.testing.assertBalanceAfter
import com.mcxiaoke.carromed.core.testing.assertDoseValue
import com.mcxiaoke.carromed.core.testing.assertLedgerBalance
import com.mcxiaoke.carromed.core.domain.model.Dose
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
import com.mcxiaoke.carromed.core.data.model.PolicyType
import com.mcxiaoke.carromed.core.data.model.RecordStatus
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import com.mcxiaoke.carromed.core.data.model.TransactionType
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * 真实内存 SQLite 数据库测试
 * 覆盖外键约束、级联删除、不可变库存台账守恒定律、Flow 响应式更新及各 DAO 真实操作
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class AppDatabaseRealTest {

    private lateinit var db: AppDatabase
    private lateinit var medDao: MedicationDao
    private lateinit var policyDao: SchedulePolicyDao
    private lateinit var slotDao: DoseSlotDao
    private lateinit var recordDao: DoseRecordDao
    private lateinit var inventoryDao: InventoryTransactionDao
    private lateinit var settingDao: AppSettingDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        medDao = db.medicationDao()
        policyDao = db.schedulePolicyDao()
        slotDao = db.doseSlotDao()
        recordDao = db.doseRecordDao()
        inventoryDao = db.inventoryTransactionDao()
        settingDao = db.appSettingDao()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    @Test
    fun insertAndQueryMedication_flowEmitsCorrectList() = runTest {
        val med = MedicationEntity(
            name = "阿司匹林肠溶片",
            alias = "拜阿司匹灵",
            category = "心血管",
            form = "肠溶片",
            unit = "片",
            colorHex = "#E53935",
            iconName = "pill",
            defaultDose = 1000,
            description = "用于预防心肌梗死",
            precautions = listOf("饭后半小时服用", "避免与布洛芬同服"),
            noticeShort = "饭后温水送服",
            minStockAlert = 7000,
            isStockTracked = true
        )
        val medId = medDao.insert(med)
        assertThat(medId).isGreaterThan(0L)

        val retrieved = medDao.getMedicationById(medId)
        assertThat(retrieved).isNotNull()
        assertThat(retrieved?.name).isEqualTo("阿司匹林肠溶片")
        assertThat(retrieved?.precautions).containsExactly("饭后半小时服用", "避免与布洛芬同服").inOrder()

        val activeList = medDao.observeActiveOverviews().first()
        assertThat(activeList).hasSize(1)
        assertThat(activeList[0].id).isEqualTo(medId)

        // 软删除（归档）测试
        medDao.updateArchiveStatus(medId, true)
        val afterDeactivate = medDao.observeActiveOverviews().first()
        assertThat(afterDeactivate).isEmpty()

        val archivedList = medDao.observeArchivedOverviews().first()
        assertThat(archivedList).hasSize(1)
        assertThat(archivedList[0].id).isEqualTo(medId)
    }

    @Test
    fun schedulePolicyAndTimes_foreignKeyCascadeDeletes() = runTest {
        val med = MedicationEntity(name = "二甲双胍片", unit = "片")
        val medId = medDao.insert(med)

        val policy = SchedulePolicyEntity(
            medicationId = medId,
            policyType = PolicyType.DAILY,
            intervalDays = 1,
            startDate = "2026-09-27"
        )
        val times = listOf(
            PolicyTimeEntity(policyId = 0, timeOfDay = "08:00", doseAmount = 1000, sortOrder = 0),
            PolicyTimeEntity(policyId = 0, timeOfDay = "12:30", doseAmount = 1000, sortOrder = 1),
            PolicyTimeEntity(policyId = 0, timeOfDay = "18:30", doseAmount = 2000, sortOrder = 2)
        )
        val policyId = policyDao.savePolicyWithTimes(policy, times)
        assertThat(policyId).isGreaterThan(0L)

        val retrievedTimes = policyDao.getTimesForPolicy(policyId)
        assertThat(retrievedTimes).hasSize(3)
        assertThat(retrievedTimes.map { it.timeOfDay }).containsExactly("08:00", "12:30", "18:30").inOrder()

        // 级联删除验证：删除药品时，相关策略和用药时间必须同时被数据库外键级联物理清除
        val toDelete = medDao.getMedicationById(medId)!!
        medDao.deleteById(toDelete.id)

        val policiesAfterDelete = policyDao.getAllPoliciesForMedication(medId)
        assertThat(policiesAfterDelete).isEmpty()

        val timesAfterDelete = policyDao.getTimesForPolicy(policyId)
        assertThat(timesAfterDelete).isEmpty()
    }

    @Test
    fun doseSlotsAndRecordTracking_lifecycleTransitionsCorrectly() = runTest {
        val med = MedicationEntity(name = "络活喜", unit = "片")
        val medId = medDao.insert(med)

        val slot = DoseSlotEntity(
            id = 1001L,
            medicationId = medId,
            policyId = 1L,
            scheduledDate = "2026-09-27",
            scheduledTime = "08:00",
            scheduledTs = 1790467200000L,
            doseAmount = 1000,
            status = SlotStatus.PENDING
        )
        slotDao.insert(slot)

        // 查询未服药插槽
        val pendingSlots = slotDao.getSlotsForDate("2026-09-27")
        assertThat(pendingSlots).hasSize(1)
        assertThat(pendingSlots[0].status).isEqualTo(SlotStatus.PENDING)

        // 用户打卡服药：创建 DoseRecordEntity 并更新 SlotStatus 为 COMPLETED
        val actualTs = 1790467500000L
        val record = DoseRecordEntity(
            slotId = 1001L,
            medicationId = medId,
            actualTs = actualTs,
            status = RecordStatus.COMPLETED,
            doseTaken = 1000,
            note = "早餐后正常服用"
        )
        val recordId = recordDao.insert(record)
        assertThat(recordId).isGreaterThan(0L)

        slotDao.updateStatus(1001L, SlotStatus.COMPLETED, actualTs)

        // 验证打卡记录写入
        val savedRecord = recordDao.getRecordBySlotId(1001L)
        assertThat(savedRecord).isNotNull()
        assertThat(savedRecord?.status).isEqualTo(RecordStatus.COMPLETED)
        assertThat(savedRecord?.actualTs).isEqualTo(actualTs)

        // 验证插槽状态变更
        val updatedSlot = slotDao.getSlotById(1001L)
        assertThat(updatedSlot?.status).isEqualTo(SlotStatus.COMPLETED)
        assertThat(updatedSlot?.actualTakenTs).isEqualTo(actualTs)
    }

    @Test
    fun inventoryLedger_mathematicalInvariantPreserved() = runTest {
        val med = MedicationEntity(
            name = "阿托伐他汀钙片",
            unit = "片",
            minStockAlert = 5000,
            isStockTracked = true
        )
        val medId = medDao.insert(med)

        // 1. 初始建档盘点: +30 片
        inventoryDao.insert(
            InventoryTransactionEntity(
                medicationId = medId,
                changeAmount = 30000,
                balanceAfter = 30000,
                txType = TransactionType.CALIBRATION_ADJUST,
                note = "开药建档初始录入"
            )
        )

        // 2. 正常按时服药扣减: -1 片
        inventoryDao.insert(
            InventoryTransactionEntity(
                medicationId = medId,
                changeAmount = -1000,
                balanceAfter = 29000,
                txType = TransactionType.TAKEN_DEDUCT,
                note = "按时服药"
            )
        )

        // 3. 用户补货 60 片: +60 片
        inventoryDao.insert(
            InventoryTransactionEntity(
                medicationId = medId,
                changeAmount = 60000,
                balanceAfter = 89000,
                txType = TransactionType.REFILL,
                note = "药房取药补仓"
            )
        )

        // 4. 用户点错“已服药”，进行撤销回滚: +1 片冲正
        inventoryDao.insert(
            InventoryTransactionEntity(
                medicationId = medId,
                changeAmount = 1000,
                balanceAfter = 90000,
                txType = TransactionType.REVERT_ROLLBACK,
                note = "撤销点错打卡"
            )
        )

        // 核心实证：余额的唯一权威值就是台账流水累加和
        val ledgerSum = inventoryDao.getSumOfChanges(medId)?.let { Dose(it).asFloat } ?: 0f
        val overview = medDao.getOverviewById(medId)
        assertThat(ledgerSum).isEqualTo(90.0f)
        assertThat(overview?.stock).isEqualTo(ledgerSum)

        // 验证台账条数按时间降序完整查询
        val txList = inventoryDao.getTransactionsForMedication(medId)
        assertThat(txList).hasSize(4)
        assertThat(txList.map { it.changeAmount }).containsExactly(1000, 60000, -1000, 30000).inOrder()
    }

    @Test
    fun `medications 表已无 current_stock 列 余额只由台账聚合`() = runTest {
        // 这是"余额只有一份权威定义"的结构性保证：不变量 I1 不是靠纪律维持的。
        val columns = db.openHelper.readableDatabase
            .query("PRAGMA table_info(medications)").use { c ->
                val nameIdx = c.getColumnIndexOrThrow("name")
                buildSet { while (c.moveToNext()) add(c.getString(nameIdx)) }
            }
        assertThat(columns).doesNotContain("current_stock")
        // 台账的三个金额列仍在
        val txColumns = db.openHelper.readableDatabase
            .query("PRAGMA table_info(inventory_transactions)").use { c ->
                val nameIdx = c.getColumnIndexOrThrow("name")
                buildSet { while (c.moveToNext()) add(c.getString(nameIdx)) }
            }
        assertThat(txColumns).containsAtLeast("change_amount", "balance_after", "tx_type")
    }

    @Test
    fun updateProfile_preservesStatusFlagsAndCreatedAt() = runTest {
        val medId = medDao.insert(
            MedicationEntity(
                name = "环孢素",
                alias = "新赛斯平",
                precautions = listOf("整粒吞服禁嚼碎"),
                noticeShort = "温水吞服",
                minStockAlert = 10000,
                isStockTracked = true
            )
        )
        // 建账 30 片，验证"档案编辑绝不能波及账面"
        inventoryDao.insert(
            InventoryTransactionEntity(
                medicationId = medId, changeAmount = 30000, balanceAfter = 30000,
                txType = TransactionType.CALIBRATION_ADJUST, note = "建档"
            )
        )
        db.reminderSettingsDao().ensureDefaults(medId)
        db.reminderSettingsDao().setPausedUntil(medId, "2026-12-31")
        db.reminderSettingsDao().updateBehavior(medId, isCriticalReminder = true, snoozeMinutes = 15, advanceMinutes = 10)
        medDao.updateArchiveStatus(medId, true)
        val before = medDao.getMedicationById(medId)!!

        // 只改名字与档案字段
        medDao.updateProfile(
            id = medId,
            name = "环孢素 缓释",
            alias = "新赛斯平",
            category = "处方药 · 免疫",
            form = "软胶囊",
            unit = "粒",
            colorHex = "#8B5CF6",
            defaultDose = 2000,
            description = "说明",
            precautions = listOf("整粒吞服禁嚼碎", "禁葡萄柚"),
            noticeShort = "温水吞服",
            expiryDate = "2027-12-31",
            minStockAlert = 20000,
            updatedAt = System.currentTimeMillis()
        )

        val after = medDao.getMedicationById(medId)!!
        assertThat(after.name).isEqualTo("环孢素 缓释")
        assertThat(after.unit).isEqualTo("粒")
        assertThat(after.precautions).containsExactly("整粒吞服禁嚼碎", "禁葡萄柚")
        assertThat(after.expiryDate).isEqualTo("2027-12-31")
        assertDoseValue(after.minStockAlert, 20f)
        // 状态位与账面绝不能被档案编辑波及
        assertThat(after.isArchived).isTrue()
        assertThat(after.isStockTracked).isTrue()
        assertThat(medDao.getOverviewById(medId)?.stock).isEqualTo(30f)
        assertThat(after.createdAt).isEqualTo(before.createdAt)
        // ⚠️ A2 的核心断言：提醒运行态已不在 medications 表上，
        // 档案编辑**在结构上就不可能**碰到它（不是"测出来没变"，是"没有这条写路径"）
        val rs = db.reminderSettingsDao().getByMedicationId(medId)!!
        assertThat(rs.isCriticalReminder).isTrue()
        assertThat(rs.snoozeMinutes).isEqualTo(15)
        assertThat(rs.advanceMinutes).isEqualTo(10)
        assertThat(rs.pausedUntil).isEqualTo("2026-12-31")
    }

    @Test
    fun updateReminderBehavior_doesNotTouchProfileOrStock() = runTest {
        val medId = medDao.insert(
            MedicationEntity(name = "胰岛素", isStockTracked = true, minStockAlert = 5000)
        )
        inventoryDao.insert(
            InventoryTransactionEntity(
                medicationId = medId, changeAmount = 8000, balanceAfter = 8000,
                txType = TransactionType.CALIBRATION_ADJUST, note = "建档"
            )
        )
        val before = medDao.getMedicationById(medId)!!
        db.reminderSettingsDao().ensureDefaults(medId)

        // 提醒行为三列归 reminder_settings 表独占
        db.reminderSettingsDao().updateBehavior(
            medicationId = medId,
            isCriticalReminder = true,
            snoozeMinutes = 10,
            advanceMinutes = 5
        )
        db.reminderSettingsDao().setPausedUntil(medId, "")

        val after = medDao.getMedicationById(medId)!!
        val rs = db.reminderSettingsDao().getByMedicationId(medId)!!
        assertThat(rs.isCriticalReminder).isTrue()
        assertThat(rs.snoozeMinutes).isEqualTo(10)
        assertThat(rs.advanceMinutes).isEqualTo(5)
        assertThat(rs.isPausedOn(LocalDate.now())).isTrue()   // "" = 无限期
        // 档案、库存、预警线、归档位一律不动
        assertThat(after.name).isEqualTo(before.name)
        assertThat(medDao.getOverviewById(medId)?.stock).isEqualTo(8f)
        assertDoseValue(after.minStockAlert, 5f)
        assertThat(after.isArchived).isEqualTo(before.isArchived)
    }

    @Test
    fun `reminder_settings 表已无 medications 上的提醒列`() = runTest {
        // A2 的结构性保证：不是靠"两条写路径都写全了"，而是"只有一条写路径"。
        val medCols = db.openHelper.readableDatabase
            .query("PRAGMA table_info(medications)").use { c ->
                val n = c.getColumnIndexOrThrow("name")
                buildSet { while (c.moveToNext()) add(c.getString(n)) }
            }
        assertThat(medCols).doesNotContain("is_critical_reminder")
        assertThat(medCols).doesNotContain("snooze_minutes")
        assertThat(medCols).doesNotContain("advance_minutes")
        assertThat(medCols).doesNotContain("is_paused")

        val rsCols = db.openHelper.readableDatabase
            .query("PRAGMA table_info(reminder_settings)").use { c ->
                val n = c.getColumnIndexOrThrow("name")
                buildSet { while (c.moveToNext()) add(c.getString(n)) }
            }
        assertThat(rsCols).containsAtLeast(
            "medication_id", "is_critical_reminder", "snooze_minutes",
            "advance_minutes", "paused_until"
        )
    }

    @Test
    fun `删除药品时提醒设置随外键级联清除`() = runTest {
        val medId = medDao.insert(MedicationEntity(name = "待删药"))
        db.reminderSettingsDao().ensureDefaults(medId)
        db.reminderSettingsDao().setPausedUntil(medId, "2026-12-31")
        assertThat(db.reminderSettingsDao().getByMedicationId(medId)).isNotNull()

        medDao.deleteById(medId)

        assertThat(db.reminderSettingsDao().getByMedicationId(medId)).isNull()
    }

    @Test
    fun appSettings_keyValueStorageSupportsAllTypes() = runTest {
        settingDao.setSetting(AppSettingEntity(key = "theme_mode", value = "DARK"))
        settingDao.setSetting(AppSettingEntity(key = "low_stock_reminder", value = "true"))
        settingDao.setSetting(AppSettingEntity(key = "snooze_interval_minutes", value = "15"))
        settingDao.setSetting(AppSettingEntity(key = "target_adherence_rate", value = "0.95"))

        assertThat(settingDao.getValue("theme_mode")).isEqualTo("DARK")
        assertThat(settingDao.getValue("low_stock_reminder")?.toBoolean()).isTrue()
        assertThat(settingDao.getValue("snooze_interval_minutes")?.toInt()).isEqualTo(15)
        assertThat(settingDao.getValue("target_adherence_rate")?.toFloat()).isEqualTo(0.95f)

        // 默认值与不存在 key 验证
        assertThat(settingDao.getValue("unknown_key")).isNull()
    }
}
