package com.mcxiaoke.carromed.core.domain.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.DoseSlotEntity
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.entity.PolicyTimeEntity
import com.mcxiaoke.carromed.core.data.entity.SchedulePolicyEntity
import com.mcxiaoke.carromed.core.data.model.PolicyType
import com.mcxiaoke.carromed.core.data.model.RecordStatus
import com.mcxiaoke.carromed.core.data.model.SlotStatus
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
 * 核心用药追踪与调度核算服务测试 (真实内存数据库)
 * 实证验证：打卡扣减、误触撤销冲正、跳过、补药以及改计划后的排班平滑重投影
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class DoseTrackingServiceTest {

    private lateinit var db: AppDatabase
    private lateinit var service: DoseTrackingService

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        service = DoseTrackingService(db)
    }

    @After
    @Throws(IOException::class)
    fun tearDown() {
        db.close()
    }

    @Test
    fun takeDoseAndUndoDose_preservesLedgerInvariantAndRestoresState() = runTest {
        val medDao = db.medicationDao()
        val slotDao = db.doseSlotDao()
        val recordDao = db.doseRecordDao()
        val inventoryDao = db.inventoryTransactionDao()

        // 1. 初始化药品，初始库存 20 片，开启库存追踪
        val medId = medDao.insert(
            MedicationEntity(
                name = "立普妥",
                unit = "片",
                currentStock = 20.0f,
                isStockTracked = true
            )
        )
        // 记录初始建档台账流水
        inventoryDao.insert(
            com.mcxiaoke.carromed.core.data.entity.InventoryTransactionEntity(
                medicationId = medId,
                changeAmount = 20.0f,
                balanceAfter = 20.0f,
                txType = TransactionType.CALIBRATION_ADJUST,
                note = "初始录入"
            )
        )

        // 2. 创建一个待服药槽位 (08:00，剂量 2 片)
        val slotId = slotDao.insert(
            DoseSlotEntity(
                medicationId = medId,
                policyId = 1L,
                scheduledDate = "2026-09-27",
                scheduledTime = "08:00",
                scheduledTs = 1790467200000L,
                doseAmount = 2.0f,
                status = SlotStatus.PENDING
            )
        )

        // 3. 用户确认服药打卡
        val takeSuccess = service.takeDose(slotId, actualTs = 1790467300000L, takenAmount = 2.0f)
        assertThat(takeSuccess).isTrue()

        // 验证打卡后状态：slot 为 COMPLETED，生成打卡事实，库存扣减为 18 片
        val slotAfterTake = slotDao.getSlotById(slotId)
        assertThat(slotAfterTake?.status).isEqualTo(SlotStatus.COMPLETED)
        assertThat(slotAfterTake?.actualTakenTs).isEqualTo(1790467300000L)

        val recordAfterTake = recordDao.getRecordBySlotId(slotId)
        assertThat(recordAfterTake).isNotNull()
        assertThat(recordAfterTake?.doseTaken).isEqualTo(2.0f)
        assertThat(recordAfterTake?.status).isEqualTo(RecordStatus.COMPLETED)

        val medAfterTake = medDao.getMedicationById(medId)
        assertThat(medAfterTake?.currentStock).isEqualTo(18.0f)

        // 验证台账不变式：SUM(change_amount) == currentStock (20 - 2 = 18)
        assertThat(inventoryDao.getSumOfChanges(medId)).isEqualTo(18.0f)

        // 4. 用户反馈点错了，执行【撤销打卡 (undoDose)】
        val undoSuccess = service.undoDose(slotId)
        assertThat(undoSuccess).isTrue()

        // 验证撤销后状态：
        // a. slot 回复为 PENDING，actualTakenTs 清空
        val slotAfterUndo = slotDao.getSlotById(slotId)
        assertThat(slotAfterUndo?.status).isEqualTo(SlotStatus.PENDING)
        assertThat(slotAfterUndo?.actualTakenTs).isNull()

        // b. 该 slot 对应的历史打卡记录已清除
        val recordAfterUndo = recordDao.getRecordBySlotId(slotId)
        assertThat(recordAfterUndo).isNull()

        // c. 药品库存精准恢复回 20 片
        val medAfterUndo = medDao.getMedicationById(medId)
        assertThat(medAfterUndo?.currentStock).isEqualTo(20.0f)

        // d. 台账产生了一条 REVERT_ROLLBACK (+2.0f) 冲正记录，全量台账求和仍然严格守恒 (20 - 2 + 2 = 20)
        assertThat(inventoryDao.getSumOfChanges(medId)).isEqualTo(20.0f)
        val transactions = inventoryDao.getTransactionsForMedication(medId)
        assertThat(transactions).hasSize(3) // 初始 + 扣除 + 冲正
        assertThat(transactions[0].txType).isEqualTo(TransactionType.REVERT_ROLLBACK)
        assertThat(transactions[0].changeAmount).isEqualTo(2.0f)
    }

    @Test
    fun skipDose_marksSkippedWithoutTouchingStock() = runTest {
        val medDao = db.medicationDao()
        val slotDao = db.doseSlotDao()
        val recordDao = db.doseRecordDao()

        val medId = medDao.insert(
            MedicationEntity(
                name = "降压药",
                unit = "片",
                currentStock = 10.0f,
                isStockTracked = true
            )
        )
        val slotId = slotDao.insert(
            DoseSlotEntity(
                medicationId = medId,
                policyId = 1L,
                scheduledDate = "2026-09-27",
                scheduledTime = "12:00",
                scheduledTs = 1790481600000L,
                doseAmount = 1.0f,
                status = SlotStatus.PENDING
            )
        )

        val skipSuccess = service.skipDose(slotId, reason = "外出未携带")
        assertThat(skipSuccess).isTrue()

        val slot = slotDao.getSlotById(slotId)
        assertThat(slot?.status).isEqualTo(SlotStatus.SKIPPED)

        val record = recordDao.getRecordBySlotId(slotId)
        assertThat(record?.status).isEqualTo(RecordStatus.SKIPPED)
        assertThat(record?.doseTaken).isEqualTo(0.0f)

        // 跳过不应扣减库存
        val med = medDao.getMedicationById(medId)
        assertThat(med?.currentStock).isEqualTo(10.0f)
    }

    @Test
    fun refillStock_increasesStockAndAddsRefillTransaction() = runTest {
        val medDao = db.medicationDao()
        val inventoryDao = db.inventoryTransactionDao()

        val medId = medDao.insert(
            MedicationEntity(
                name = "维生素C",
                unit = "片",
                currentStock = 5.0f,
                isStockTracked = true
            )
        )

        val success = service.refillStock(medId, addedAmount = 100.0f, note = "新开一瓶")
        assertThat(success).isTrue()

        val med = medDao.getMedicationById(medId)
        assertThat(med?.currentStock).isEqualTo(105.0f)

        val txList = inventoryDao.getTransactionsForMedication(medId)
        assertThat(txList).hasSize(1)
        assertThat(txList[0].txType).isEqualTo(TransactionType.REFILL)
        assertThat(txList[0].changeAmount).isEqualTo(100.0f)
        assertThat(txList[0].balanceAfter).isEqualTo(105.0f)
    }

    @Test
    fun reconcileSchedule_preservesCompletedHistoryWhileReschedulingFuturePending() = runTest {
        val medDao = db.medicationDao()
        val policyDao = db.schedulePolicyDao()
        val slotDao = db.doseSlotDao()

        val medId = medDao.insert(
            MedicationEntity(name = "二甲双胍", unit = "片", currentStock = 30.0f)
        )

        val today = LocalDate.of(2026, 10, 1)

        // 原策略：每日 1 次 (08:00)
        val oldPolicy = SchedulePolicyEntity(
            medicationId = medId,
            policyType = PolicyType.DAILY,
            startDate = "2026-10-01"
        )
        val oldTimes = listOf(
            PolicyTimeEntity(policyId = 0, timeOfDay = "08:00", doseAmount = 1.0f)
        )
        val policyId = policyDao.savePolicyWithTimes(oldPolicy, oldTimes)

        // 先生成旧计划的槽位
        val oldSlotToday = DoseSlotEntity(
            id = 501L,
            medicationId = medId,
            policyId = policyId,
            scheduledDate = "2026-10-01",
            scheduledTime = "08:00",
            scheduledTs = today.atTime(8, 0).atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli(),
            doseAmount = 1.0f,
            status = SlotStatus.PENDING
        )
        slotDao.insert(oldSlotToday)

        // 用户早上 08:00 已经打卡吃完了！
        service.takeDose(501L, actualTs = oldSlotToday.scheduledTs + 60000L)

        // 中午时，医生突然改方案：改为每日 2 次 (08:00 1片, 18:00 1片)
        val newPolicy = SchedulePolicyEntity(
            id = policyId,
            medicationId = medId,
            policyType = PolicyType.DAILY,
            startDate = "2026-10-01",
            version = 2
        )
        val newTimes = listOf(
            PolicyTimeEntity(policyId = policyId, timeOfDay = "08:00", doseAmount = 1.0f, sortOrder = 0),
            PolicyTimeEntity(policyId = policyId, timeOfDay = "18:00", doseAmount = 1.0f, sortOrder = 1)
        )
        policyDao.updatePolicy(newPolicy)
        policyDao.insertTimes(newTimes)

        // 执行计划变更调和 (Reconcile)
        service.reconcileSchedule(
            medicationId = medId,
            fromDate = today,
            toDate = today.plusDays(1),
            zoneId = java.time.ZoneId.of("Asia/Shanghai")
        )

        // 核心实证验证：
        // 1. 早上已经打卡完成的 501 槽位依然完好无损处于 COMPLETED 状态！打卡历史毫发无伤！
        val morningSlot = slotDao.getSlotById(501L)
        assertThat(morningSlot).isNotNull()
        assertThat(morningSlot?.status).isEqualTo(SlotStatus.COMPLETED)

        // 2. 查询 2026-10-01 的所有槽位：包含已完成的 08:00，以及重新生成的 18:00 PENDING 槽位！
        val todaySlots = slotDao.getSlotsForDate("2026-10-01")
        assertThat(todaySlots.map { it.scheduledTime }).contains("18:00")
        assertThat(todaySlots.first { it.scheduledTime == "08:00" }.status).isEqualTo(SlotStatus.COMPLETED)
    }
}
