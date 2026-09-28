package com.mcxiaoke.carromed.core.domain.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.testing.assertBalanceAfter
import com.mcxiaoke.carromed.core.testing.assertDoseValue
import com.mcxiaoke.carromed.core.testing.assertLedgerBalance
import com.mcxiaoke.carromed.core.domain.model.Dose
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.InventoryTransactionEntity
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.model.PolicyType
import com.mcxiaoke.carromed.core.data.model.TransactionType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.LocalDate

/**
 * 药品档案 / 提醒计划 写入服务测试
 *
 * 本文件针对本次修复的**数据丢失缺陷**：
 * 旧实现用 `insert(REPLACE)` 整行覆盖药品，编辑一次就会清空
 * alias / precautions / noticeShort / isPaused / isArchived / createdAt；
 * 并且每次保存提醒计划都把 startDate 重置为今天、丢弃 endDate。
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class MedicationAdminServiceTest {

    private lateinit var db: AppDatabase
    private lateinit var service: MedicationAdminService
    private lateinit var medDao: com.mcxiaoke.carromed.core.data.dao.MedicationDao
    private lateinit var policyDao: com.mcxiaoke.carromed.core.data.dao.SchedulePolicyDao
    private lateinit var inventoryDao: com.mcxiaoke.carromed.core.data.dao.InventoryTransactionDao
    private lateinit var recordDao: com.mcxiaoke.carromed.core.data.dao.DoseRecordDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        service = MedicationAdminService(db)
        medDao = db.medicationDao()
        policyDao = db.schedulePolicyDao()
        inventoryDao = db.inventoryTransactionDao()
        recordDao = db.doseRecordDao()
    }

    @After
    @Throws(IOException::class)
    fun tearDown() = db.close()

    @Test
    fun saveProfile_createsNewMedicationWithAllFields() = runTest {
        val id = service.saveProfile(
            MedicationAdminService.ProfileDraft(
                name = "阿司匹林肠溶片",
                alias = "拜阿司匹灵",
                unit = "片",
                precautions = listOf("饭后半小时服用", "避免与布洛芬同服"),
                noticeShort = "饭后温水送服",
                expiryDate = "2027-12-31",
                minStockAlert = 7f
            )
        )
        val saved = medDao.getMedicationById(id)!!
        assertThat(saved.name).isEqualTo("阿司匹林肠溶片")
        assertThat(saved.alias).isEqualTo("拜阿司匹灵")
        assertThat(saved.precautions).containsExactly("饭后半小时服用", "避免与布洛芬同服")
        assertThat(saved.noticeShort).isEqualTo("饭后温水送服")
        assertThat(saved.expiryDate).isEqualTo("2027-12-31")
        assertDoseValue(saved.minStockAlert, 7f)
    }

    @Test
    fun saveProfile_editDoesNotWipeUnrelatedFields() = runTest {
        val id = service.saveProfile(
            MedicationAdminService.ProfileDraft(
                name = "环孢素",
                alias = "新赛斯平",
                precautions = listOf("整粒吞服禁嚼碎", "严禁与葡萄柚同食"),
                noticeShort = "温水吞服",
                minStockAlert = 10f
            )
        )
        medDao.updatePauseStatus(id, true)
        medDao.updateArchiveStatus(id, true)
        medDao.updateStockTracking(id, true)
        inventoryDao.insert(
            InventoryTransactionEntity(
                medicationId = id, changeAmount = 42000, balanceAfter = 42000,
                txType = TransactionType.CALIBRATION_ADJUST, note = "建档"
            )
        )
        val before = medDao.getMedicationById(id)!!

        service.saveProfile(
            MedicationAdminService.ProfileDraft(
                medId = id,
                name = "环孢素 缓释",
                category = "处方药 · 免疫",
                form = "软胶囊",
                unit = "粒",
                colorHex = "#8B5CF6",
                defaultDose = 2f,
                description = "改个名字看看会不会把别的字段冲掉",
                precautions = listOf("仅保留一条"),
                noticeShort = "改过的简述",
                minStockAlert = 20f
            )
        )

        val after = medDao.getMedicationById(id)!!
        // 可编辑字段应更新
        assertThat(after.name).isEqualTo("环孢素 缓释")
        assertThat(after.category).isEqualTo("处方药 · 免疫")
        assertThat(after.unit).isEqualTo("粒")
        assertDoseValue(after.defaultDose, 2f)
        assertThat(after.precautions).containsExactly("仅保留一条")
        assertDoseValue(after.minStockAlert, 20f)
        // 不可编辑/未提交的字段必须原样保留
        assertThat(after.alias).isEqualTo("新赛斯平")
        assertThat(after.isPaused).isTrue()
        assertThat(after.isArchived).isTrue()
        assertThat(after.isStockTracked).isTrue()
        db.assertLedgerBalance(id, 42f)
        assertThat(after.createdAt).isEqualTo(before.createdAt)
    }

    @Test
    fun saveReminderPolicy_blankStartDateKeepsOriginalPhase() = runTest {
        val medId = medDao.insert(MedicationEntity(name = "隔日药"))
        val originalStart = "2026-09-01"

        service.saveReminderPolicy(
            medId,
            MedicationAdminService.PolicyDraft(
                policyType = PolicyType.INTERVAL,
                intervalDays = 2,
                startDate = originalStart,
                endDate = "2026-12-31",
                times = listOf(MedicationAdminService.TimeDraft("08:00", 1f, "随早餐"))
            )
        )

        // 第二次保存：startDate 传空串表示"用户没改起始日"
        service.saveReminderPolicy(
            medId,
            MedicationAdminService.PolicyDraft(
                policyType = PolicyType.INTERVAL,
                intervalDays = 3,
                startDate = "",
                times = listOf(MedicationAdminService.TimeDraft("09:00", 1f, "随早餐"))
            )
        )

        val policy = policyDao.getActivePolicyForMedication(medId)!!
        // 起始日与疗程结束日都不该被静默改掉
        assertThat(policy.startDate).isEqualTo(originalStart)
        assertThat(policy.endDate).isEqualTo("2026-12-31")
        // 用户确实改的部分要生效
        assertThat(policy.intervalDays).isEqualTo(3)
        // 版本号递增，便于审计
        assertThat(policy.version).isEqualTo(2)
    }

    @Test
    fun saveReminderPolicy_replacesTimesAndPreservesLabels() = runTest {
        val medId = medDao.insert(MedicationEntity(name = "多时点药"))
        service.saveReminderPolicy(
            medId,
            MedicationAdminService.PolicyDraft(
                policyType = PolicyType.DAILY,
                times = listOf(
                    MedicationAdminService.TimeDraft("22:00", 1f, "睡前"),
                    MedicationAdminService.TimeDraft("10:30", 1f, "随早餐")
                )
            )
        )
        val policy = policyDao.getActivePolicyForMedication(medId)!!
        val times = policyDao.getTimesForPolicy(policy.id)
        // 按时间升序排列，标签原样保存
        assertThat(times.map { it.timeOfDay }).containsExactly("10:30", "22:00").inOrder()
        assertThat(times.map { it.label }).containsExactly("随早餐", "睡前").inOrder()

        // 只保留一个活跃策略；旧策略被 deactivate（is_active=0）而不是物理删除，
        // 这样"改过几次计划"仍可审计，且历史槽位的 policy_id 不会变成悬空引用。
        val allPolicies = policyDao.getAllPoliciesForMedication(medId)
        assertThat(allPolicies.count { it.isActive }).isEqualTo(1)
        assertThat(policyDao.getActivePolicyForMedication(medId)?.id).isEqualTo(policy.id)
    }

    @Test
    fun ensureInitialStockLedger_isIdempotentAndKeepsInvariant() = runTest {
        val medId = medDao.insert(MedicationEntity(name = "建档药", unit = "片"))

        service.ensureInitialStockLedger(medId, 30f)
        db.assertLedgerBalance(medId, 30f)

        // 重复调用不应重复记账
        service.ensureInitialStockLedger(medId, 30f)
        assertThat(db.inventoryTransactionDao().getTransactionsForMedication(medId)).hasSize(1)
    }

    @Test
    fun calibrateStock_writesLedgerAndKeepsInvariant() = runTest {
        val medId = medDao.insert(MedicationEntity(name = "盘点药", unit = "片"))
        inventoryDao.insert(
            InventoryTransactionEntity(
                medicationId = medId, changeAmount = 20000, balanceAfter = 20000,
                txType = TransactionType.CALIBRATION_ADJUST, note = "建档"
            )
        )

        val tracking = DoseTrackingService(db)
        assertThat(tracking.calibrateStock(medId, 17.5f, "实物比账面少 2.5 片")).isTrue()

        // 余额的唯一权威值就是台账流水累加和
        db.assertLedgerBalance(medId, 17.5f)
        assertThat(medDao.getOverviewById(medId)?.stock).isEqualTo(17.5f)
        // 校准流水必须是 CALIBRATION_ADJUST
        val last = inventoryDao.getTransactionsForMedication(medId).first()
        assertThat(last.txType).isEqualTo(TransactionType.CALIBRATION_ADJUST)
        assertDoseValue(last.changeAmount, -2.5f)
    }

    @Test
    fun calibrateStock_noOpWhenAlreadyMatching() = runTest {
        val medId = medDao.insert(MedicationEntity(name = "无需校准", unit = "片"))
        inventoryDao.insert(
            InventoryTransactionEntity(
                medicationId = medId, changeAmount = 12000, balanceAfter = 12000,
                txType = TransactionType.CALIBRATION_ADJUST, note = "建档"
            )
        )

        val tracking = DoseTrackingService(db)
        assertThat(tracking.calibrateStock(medId, 12f)).isFalse()
        assertThat(inventoryDao.getTransactionsForMedication(medId)).hasSize(1)
    }

    @Test
    fun logManualDose_deductStockFalse_keepsStockIntact() = runTest {
        val medId = medDao.insert(
            MedicationEntity(name = "补录药", unit = "片", isStockTracked = true)
        )
        inventoryDao.insert(
            InventoryTransactionEntity(
                medicationId = medId, changeAmount = 10000, balanceAfter = 10000,
                txType = TransactionType.CALIBRATION_ADJUST, note = "建档"
            )
        )
        val tracking = DoseTrackingService(db)
        val now = System.currentTimeMillis()

        tracking.logManualDose(medId, now, 2f, isRetrospective = true, note = "已在别处吃过", deductStock = false)
        // 事实记录要写
        assertThat(recordDao.getRecordsForMedication(medId)).hasSize(1)
        // 但库存与流水都不动
        db.assertLedgerBalance(medId, 10f)

        // 默认 deductStock=true 时才扣减
        tracking.logManualDose(medId, now + 1000, 2f, isRetrospective = true, note = "确实没吃")
        db.assertLedgerBalance(medId, 8f)
    }

    @Test
    fun setStockTracking_enablingFromZeroWritesOpeningLedger() = runTest {
        val medId = medDao.insert(MedicationEntity(name = "后开追踪", unit = "片"))
        val tracking = DoseTrackingService(db)

        tracking.setStockTracking(medId, true, initialStock = 50f)

        assertThat(medDao.getMedicationById(medId)!!.isStockTracked).isTrue()
        db.assertLedgerBalance(medId, 50f)
    }

    @Test
    fun setStockTracking_disablingKeepsLedgerIntact() = runTest {
        val medId = medDao.insert(
            MedicationEntity(name = "关闭追踪", unit = "片", isStockTracked = true)
        )
        inventoryDao.insert(
            InventoryTransactionEntity(
                medicationId = medId, changeAmount = 30000, balanceAfter = 30000,
                txType = TransactionType.CALIBRATION_ADJUST, note = "建档"
            )
        )
        val tracking = DoseTrackingService(db)

        tracking.setStockTracking(medId, false)

        assertThat(medDao.getMedicationById(medId)!!.isStockTracked).isFalse()
        // 账面与流水仍守恒，只是不再自动扣减
        db.assertLedgerBalance(medId, 30f)
    }

    @Test
    fun reminderSettingsPreserveEndDateWhenUserClearsIt() = runTest {
        val medId = medDao.insert(MedicationEntity(name = "抗生素", unit = "片"))
        service.saveReminderPolicy(
            medId,
            MedicationAdminService.PolicyDraft(
                policyType = PolicyType.DAILY,
                startDate = LocalDate.now().toString(),
                endDate = LocalDate.now().plusDays(6).toString(),
                times = listOf(MedicationAdminService.TimeDraft("08:00", 1f, "随早餐"))
            )
        )
        val endDate = policyDao.getActivePolicyForMedication(medId)!!.endDate
        assertThat(endDate).isNotNull()

        // 用户只想改时点，不应丢掉疗程
        service.saveReminderPolicy(
            medId,
            MedicationAdminService.PolicyDraft(
                policyType = PolicyType.DAILY,
                times = listOf(MedicationAdminService.TimeDraft("09:00", 1f, "随早餐"))
            )
        )
        assertThat(policyDao.getActivePolicyForMedication(medId)!!.endDate).isEqualTo(endDate)
    }
}
