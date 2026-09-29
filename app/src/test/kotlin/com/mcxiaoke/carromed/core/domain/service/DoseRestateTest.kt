package com.mcxiaoke.carromed.core.domain.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.DoseSlotEntity
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.model.RecordStatus
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import com.mcxiaoke.carromed.core.data.model.TransactionType
import com.mcxiaoke.carromed.core.testing.assertLedgerBalance
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 「改判」（已服 ↔ 已跳过）的台账守恒与幂等。
 *
 * ## 为什么要单独一个文件
 *
 * 改判是本项目**唯一**会"回补已有扣减"的路径：`revertSlotInternal` 先按净额冲正、
 * 再施加新结论。任何一步出错，`SUM(change_amount)` 与事实就会分叉 ——
 * 而且**不会有任何报错**，只是库存页的数字从此对不上。
 *
 * 直接调用 `takeDose` / `skipDose` 是**改不动**已产生结论的槽位的
 * （它们的幂等锚点在 SQL 的 WHERE 里，守卫是 `PENDING/SNOOZED/EXPIRED`），
 * 所以这条通路必须自己守。
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class DoseRestateTest {

    private lateinit var db: AppDatabase
    private lateinit var tracking: DoseTrackingService
    private var medId: Long = 0

    /** 固定的服药时刻，避免断言随"此刻"变化 */
    private val slotTs = 1_700_000_000_000L

    @Before
    fun setup() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        tracking = DoseTrackingService(db)
        medId = db.medicationDao().insert(MedicationEntity(name = "环孢素", unit = "片"))
        tracking.setStockTracking(medId, enabled = true, initialStock = 30f)
    }

    @After
    fun teardown() {
        if (::db.isInitialized) db.close()
    }

    private suspend fun newSlot(doseMilli: Int = 1000): Long =
        db.doseSlotDao().insert(
            DoseSlotEntity(
                medicationId = medId,
                policyId = 0,
                scheduledDate = "2026-09-29",
                scheduledTime = "08:00",
                scheduledTs = slotTs,
                doseAmount = doseMilli,
                status = SlotStatus.PENDING
            )
        )

    /** 账上还剩多少（**展示值**） */
    private suspend fun balance(): Float =
        com.mcxiaoke.carromed.core.domain.model.Dose(
            db.inventoryTransactionDao().getSumOfChanges(medId) ?: 0
        ).asFloat

    /** I2：最后一条流水的 `balanceAfter` 必须等于 SUM(change_amount) */
    private suspend fun assertI2() {
        val last = db.inventoryTransactionDao().getLatestTransaction(medId)
        if (last == null) {
            assertThat(db.inventoryTransactionDao().getSumOfChanges(medId) ?: 0).isEqualTo(0)
        } else {
            assertThat(last.balanceAfter)
                .isEqualTo(db.inventoryTransactionDao().getSumOfChanges(medId) ?: 0)
        }
    }

    // ==================== 已跳过 → 已服 ====================

    @Test
    fun `跳过改判为已服 扣一次库存且台账守恒`() = runTest {
        val slotId = newSlot()
        assertThat(tracking.skipDose(slotId)).isTrue()
        db.assertLedgerBalance(medId, 30f)              // 跳过不扣库存

        assertThat(tracking.restateSlot(slotId, RecordStatus.COMPLETED)).isTrue()

        db.assertLedgerBalance(medId, 29f)              // 改判成已服 ⇒ 补扣一次
        assertThat(db.doseSlotDao().getSlotById(slotId)!!.status)
            .isEqualTo(SlotStatus.COMPLETED)

        val records = db.doseRecordDao().getAllRecordsBySlotId(slotId)
        // 事实只增不删：一条 SKIPPED 被标 REVERTED，一条新的 COMPLETED
        assertThat(records.map { it.status })
            .containsExactly(RecordStatus.REVERTED, RecordStatus.COMPLETED)
        assertThat(records.count { it.status == RecordStatus.COMPLETED }).isEqualTo(1)
        assertThat(records.first { it.status == RecordStatus.COMPLETED }.doseTaken).isEqualTo(1000)
        assertI2()
    }

    // ==================== 已服 → 已跳过 ====================

    @Test
    fun `已服改判为跳过 回补库存且不产生悬空引用`() = runTest {
        val slotId = newSlot()
        assertThat(tracking.takeDose(slotId, actualTs = slotTs)).isTrue()
        db.assertLedgerBalance(medId, 29f)

        assertThat(tracking.restateSlot(slotId, RecordStatus.SKIPPED)).isTrue()

        db.assertLedgerBalance(medId, 30f)              // 回补那一扣
        assertThat(db.doseSlotDao().getSlotById(slotId)!!.status)
            .isEqualTo(SlotStatus.SKIPPED)

        val records = db.doseRecordDao().getAllRecordsBySlotId(slotId)
        assertThat(records.map { it.status })
            .containsExactly(RecordStatus.REVERTED, RecordStatus.SKIPPED)
        // 跳过事实不许扣库存：它的台账净额必须是 0
        val skipRecord = records.first { it.status == RecordStatus.SKIPPED }
        assertThat(skipRecord.doseTaken).isEqualTo(0)
        assertThat(db.inventoryTransactionDao().getSumOfChangeByRecordId(skipRecord.id) ?: 0)
            .isEqualTo(0)
        assertI2()
    }

    @Test
    fun `改判为已服 补的是 TAKEN_DEDUCT 流水`() = runTest {
        val slotId = newSlot()
        tracking.skipDose(slotId)
        tracking.restateSlot(slotId, RecordStatus.COMPLETED)

        val deducts = db.inventoryTransactionDao().getAllTransactions()
            .filter { it.medicationId == medId && it.txType == TransactionType.TAKEN_DEDUCT }
        assertThat(deducts).hasSize(1)
        assertThat(deducts.single().changeAmount).isEqualTo(-1000)
    }

    // ==================== 幂等与边界 ====================

    @Test
    fun `已服改判成已服 直接拒绝且账面不动`() = runTest {
        val slotId = newSlot()
        tracking.takeDose(slotId, actualTs = slotTs)

        assertThat(tracking.restateSlot(slotId, RecordStatus.COMPLETED)).isFalse()

        db.assertLedgerBalance(medId, 29f)              // 不能二次扣减
        assertThat(db.doseRecordDao().getAllRecordsBySlotId(slotId)).hasSize(1)
    }

    @Test
    fun `已跳过改判成已跳过 直接拒绝`() = runTest {
        val slotId = newSlot()
        tracking.skipDose(slotId)

        assertThat(tracking.restateSlot(slotId, RecordStatus.SKIPPED)).isFalse()
        assertThat(db.doseRecordDao().getAllRecordsBySlotId(slotId)).hasSize(1)
    }

    /**
     * ⭐ 开放槽位不许走改判。
     *
     * 它还没有结论，走 [DoseTrackingService.takeDose] / [DoseTrackingService.skipDose] 即可；
     * 若改判也接受开放槽位，就会绕过那两条路径的幂等锚点，凭空多一条事实。
     */
    @Test
    fun `待服槽位不能改判`() = runTest {
        val slotId = newSlot()
        assertThat(tracking.restateSlot(slotId, RecordStatus.COMPLETED)).isFalse()
        assertThat(tracking.restateSlot(slotId, RecordStatus.SKIPPED)).isFalse()

        assertThat(db.doseSlotDao().getSlotById(slotId)!!.status).isEqualTo(SlotStatus.PENDING)
        assertThat(db.doseRecordDao().getAllRecordsBySlotId(slotId)).isEmpty()
        db.assertLedgerBalance(medId, 30f)
    }

    @Test
    fun `改判保留原服药时刻 不跳到此刻`() = runTest {
        val slotId = newSlot()
        val takenAt = slotTs - 3 * 60 * 60 * 1000L      // 假设当天早些时候服的
        tracking.takeDose(slotId, actualTs = takenAt)

        tracking.restateSlot(slotId, RecordStatus.SKIPPED)

        val records = db.doseRecordDao().getAllRecordsBySlotId(slotId)
        val skip = records.first { it.status == RecordStatus.SKIPPED }
        // 改判的语义是"我其实没吃"，不是"我现在吃药了" ——
        // 用 now 会让一条昨天的记录跳到今天，污染当日统计
        assertThat(skip.actualTs).isEqualTo(takenAt)
    }

    @Test
    fun `改判不存在的槽位返回 false`() = runTest {
        assertThat(tracking.restateSlot(999_999L, RecordStatus.COMPLETED)).isFalse()
    }

    // ==================== 与撤销的组合 ====================

    /**
     * ⭐ 改判之后仍然可以撤销，且账面回到出库前。
     *
     * 这是最容易出错的一条：改判会留下三条事实（REVERTED / REVERTED / COMPLETED），
     * 撤销时必须按**每条事实的净额**分别冲正，用"取一条事实的剂量"那种写法会漏账。
     */
    @Test
    fun `已服改判跳过再撤销 账面回到出库前`() = runTest {
        val slotId = newSlot()
        tracking.takeDose(slotId, actualTs = slotTs)     // -1
        tracking.restateSlot(slotId, RecordStatus.SKIPPED) // +1
        db.assertLedgerBalance(medId, 30f)

        assertThat(tracking.undoDose(slotId)).isTrue()

        assertThat(db.doseSlotDao().getSlotById(slotId)!!.status).isEqualTo(SlotStatus.PENDING)
        db.assertLedgerBalance(medId, 30f)              // 不能变成 31 或 29
        assertThat(
            db.doseRecordDao().getAllRecordsBySlotId(slotId)
                .all { it.status == RecordStatus.REVERTED }
        ).isTrue()
        assertI2()
    }

    @Test
    fun `跳过改判已服再撤销 账面回到出库前`() = runTest {
        val slotId = newSlot()
        tracking.skipDose(slotId)
        tracking.restateSlot(slotId, RecordStatus.COMPLETED)  // -1
        db.assertLedgerBalance(medId, 29f)

        assertThat(tracking.undoDose(slotId)).isTrue()

        db.assertLedgerBalance(medId, 30f)
        assertThat(db.doseSlotDao().getSlotById(slotId)!!.status).isEqualTo(SlotStatus.PENDING)
        assertI2()
    }

    // ==================== 未追踪库存 ====================

    @Test
    fun `未开启库存追踪时改判不产生流水`() = runTest {
        val plainMedId = db.medicationDao().insert(MedicationEntity(name = "维生素D", unit = "粒"))
        val slotId = db.doseSlotDao().insert(
            DoseSlotEntity(
                medicationId = plainMedId,
                policyId = 0,
                scheduledDate = "2026-09-29",
                scheduledTime = "08:00",
                scheduledTs = slotTs,
                doseAmount = 1000,
                status = SlotStatus.PENDING
            )
        )
        tracking.skipDose(slotId)

        assertThat(tracking.restateSlot(slotId, RecordStatus.COMPLETED)).isTrue()

        assertThat(db.doseSlotDao().getSlotById(slotId)!!.status)
            .isEqualTo(SlotStatus.COMPLETED)
        assertThat(db.inventoryTransactionDao().getSumOfChanges(plainMedId) ?: 0).isEqualTo(0)
        // 事实照样入库：没开追踪也要记录"吃没吃"
        assertThat(db.doseRecordDao().getAllRecordsBySlotId(slotId).size).isEqualTo(2)
    }

    /** 改判后账面上的"这次实际扣了多少"必须与事实对得上（净额 = -剂量） */
    @Test
    fun `改判为已服后 事实净额等于负剂量`() = runTest {
        val slotId = newSlot(doseMilli = 2500)
        tracking.skipDose(slotId)
        tracking.restateSlot(slotId, RecordStatus.COMPLETED)

        val completed = db.doseRecordDao().getAllRecordsBySlotId(slotId)
            .first { it.status == RecordStatus.COMPLETED }
        assertThat(balance()).isEqualTo(27.5f)
        assertThat(db.inventoryTransactionDao().getSumOfChangeByRecordId(completed.id))
            .isEqualTo(-2500)
        assertI2()
    }
}
