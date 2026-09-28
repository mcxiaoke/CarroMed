package com.mcxiaoke.carromed.core.domain.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.entity.PolicyTimeEntity
import com.mcxiaoke.carromed.core.data.entity.SchedulePolicyEntity
import com.mcxiaoke.carromed.core.data.model.PolicyType
import com.mcxiaoke.carromed.core.data.model.RecordStatus
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import com.mcxiaoke.carromed.core.data.model.TransactionType
import com.mcxiaoke.carromed.core.domain.model.Dose
import com.mcxiaoke.carromed.core.testing.assertLedgerBalance
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.LocalDate

/**
 * 「打卡 → 撤销」往返多次的库存守恒（治 P0-1 / P1-4）。
 *
 * ## 缺陷
 *
 * `undoDose` 用 `recordDao.getRecordBySlotId(slotId)` 取一条**"代表事实"**来决定要不要冲正，
 * 而那个查询是 `ORDER BY id ASC LIMIT 1` —— 取的是**最早那条**。
 * 于是一次完整的"打卡 → 撤销 → 再打卡 → 再撤销"之后：
 *
 * | 步骤 | 事实 #1 | 事实 #2 | 台账 |
 * | :--- | :--- | :--- | :--- |
 * | 打卡 | `COMPLETED` | — | −2 |
 * | 撤销 | `REVERTED` | — | +2（回到原值）|
 * | 再打卡 | `REVERTED` | `COMPLETED` | −2 |
 * | **再撤销** | `REVERTED` | `REVERTED` | **不冲正** ⇒ 账面凭空少 2 |
 *
 * 终态没有任何异常，`SUM(change_amount) == balance` 依然成立（I1 是恒等式，拦不住），
 * 台账里"扣减 / 冲正 / 扣减"三行看起来完全合理。**账面与事实不符，且无任何提示。**
 *
 * 既有测试 `I4 重复撤销不会二次冲正` 只覆盖了"**不重新打卡**就再撤销"的情形 ——
 * 那种情况下"不冲正"恰恰是正确行为，所以缺陷完全藏住了。
 *
 * ## P1-4 同一个函数的第二个缺陷
 *
 * 冲正判据里的 `medication.isStockTracked` 读的是**药品当前的开关**，
 * 而扣减发生时记录的是**当时的开关**。于是两个方向都错：
 *
 * - 关闭追踪时打卡（不扣）→ 事后开启追踪 → 撤销 ⇒ **账面凭空多出**
 * - 开启追踪时打卡（扣了）→ 事后关闭追踪 → 撤销 ⇒ **扣减不回补**
 *
 * ## 正解：判据是"这条事实实际还欠多少扣"，不是"药品现在设没设追踪"
 *
 * 对每条 `COMPLETED` 事实求它关联流水的净额 `SUM(change_amount WHERE record_id = ?)`：
 * 负数表示仍欠扣，补一条等额冲正；非负表示已回补过，不动。
 * 这样同时解决了两个缺陷，而且天然幂等 —— 冲正后净额变 0，再撤销就不会重复冲。
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class UndoRoundTripLedgerTest {

    private lateinit var db: AppDatabase
    private lateinit var service: DoseTrackingService

    private val today: LocalDate = LocalDate.of(2026, 9, 28)

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
    fun tearDown() = db.close()

    /** 每日 08:00、追踪库存、初始 20 片的药 */
    private suspend fun newMed(stock: Float = 20f): Pair<Long, Long> {
        val medId = db.medicationDao().insert(
            MedicationEntity(
                name = "环孢素", unit = "片",
                defaultDose = 1000, isStockTracked = true
            )
        )
        db.schedulePolicyDao().savePolicyWithTimes(
            SchedulePolicyEntity(
                medicationId = medId, policyType = PolicyType.DAILY,
                intervalDays = 2, startDate = today.toString()
            ),
            listOf(PolicyTimeEntity(policyId = 0L, timeOfDay = "08:00", doseAmount = 1000))
        )
        if (stock != 0f) {
            db.inventoryTransactionDao().insert(
                com.mcxiaoke.carromed.core.data.entity.InventoryTransactionEntity(
                    medicationId = medId, changeAmount = Dose.of(stock).milli,
                    balanceAfter = Dose.of(stock).milli, txType = TransactionType.REFILL
                )
            )
        }
        service.reconcileSchedule(medId, today, today)
        val slotId = db.doseSlotDao().getSlotsForDate(today.toString()).first { it.medicationId == medId }.id
        return medId to slotId
    }

    /** 台账余额（展示值）。`DoseTrackingService.balanceOf` 是私有的，这里走 DAO 复算。 */
    private suspend fun balanceOf(medId: Long): Float =
        Dose(db.inventoryTransactionDao().getSumOfChanges(medId) ?: 0).asFloat

    // ================================================================
    // P0-1：多轮往返
    // ================================================================

    @Test
    fun `打卡与撤销往返两轮后账面精确回到原值`() = runTest {
        val (medId, slotId) = newMed(stock = 20f)

        service.takeDose(slotId, takenAmount = 2f)
        db.assertLedgerBalance(medId, 18f)

        service.undoDose(slotId)
        db.assertLedgerBalance(medId, 20f)

        // ⚠️ 第二轮：这就是缺陷的触发点
        service.takeDose(slotId, takenAmount = 2f)
        db.assertLedgerBalance(medId, 18f)

        service.undoDose(slotId)
        // ★ 必须回到 20。旧实现这里停在 18 —— 账面凭空少 2 片，且无任何异常
        db.assertLedgerBalance(medId, 20f)
    }

    @Test
    fun `往返三轮 账面仍精确回到原值`() = runTest {
        val (medId, slotId) = newMed(stock = 20f)
        repeat(3) {
            service.takeDose(slotId, takenAmount = 2f)
            assertThat(balanceOf(medId)).isEqualTo(18f)
            service.undoDose(slotId)
            assertThat(balanceOf(medId)).isEqualTo(20f)
        }
    }

    @Test
    fun `每轮往返都留下一条冲正流水 且扣减与冲正条数相等`() = runTest {
        val (medId, slotId) = newMed(stock = 20f)
        repeat(3) {
            service.takeDose(slotId, takenAmount = 2f)
            service.undoDose(slotId)
        }
        val txs = db.inventoryTransactionDao().getTransactionsForMedication(medId)
        val deductions = txs.count { it.txType == TransactionType.TAKEN_DEDUCT }
        val rollbacks = txs.count { it.txType == TransactionType.REVERT_ROLLBACK }
        assertThat(deductions).isEqualTo(rollbacks)
        assertThat(rollbacks).isEqualTo(3)
    }

    /**
     * 两次打卡用**不同剂量**时，冲正必须各自等额，不能拿错。
     *
     * 旧实现取"代表事实"的 `doseTaken`（最早那条），第二轮会按 2f 冲正而实际欠扣 3f。
     */
    @Test
    fun `第二轮用不同剂量时 冲正额必须等于该轮实际欠扣`() = runTest {
        val (medId, slotId) = newMed(stock = 20f)

        service.takeDose(slotId, takenAmount = 2f)
        service.undoDose(slotId)
        service.takeDose(slotId, takenAmount = 3f)
        assertThat(balanceOf(medId)).isEqualTo(17f)
        service.undoDose(slotId)

        // ★ 精确回到 20。若冲正额取了"代表事实"的 2f，会停在 18
        db.assertLedgerBalance(medId, 20f)
    }

    // ================================================================
    // P1-4：追踪开关在打卡与撤销之间变化
    // ================================================================

    @Test
    fun `追踪关闭时打卡不扣 事后开启追踪再撤销 不得凭空增加账面`() = runTest {
        val (medId, slotId) = newMed(stock = 0f)     // 账面 0
        service.setStockTracking(medId, enabled = true, initialStock = 20f)
        db.assertLedgerBalance(medId, 20f)

        // 关掉追踪后打卡 ⇒ 不产生扣减流水
        service.setStockTracking(medId, enabled = false)
        service.takeDose(slotId, takenAmount = 2f)
        db.assertLedgerBalance(medId, 20f)

        // 事后重新开启追踪（不写流水），再撤销
        service.setStockTracking(medId, enabled = true)
        service.undoDose(slotId)

        // ★ 仍应是 20。旧实现读"当前 isStockTracked=true" ⇒ 冲正 +2 ⇒ 账面变 22
        db.assertLedgerBalance(medId, 20f)
    }

    @Test
    fun `追踪开启时打卡扣了 事后关闭追踪再撤销 扣减必须回补`() = runTest {
        val (medId, slotId) = newMed(stock = 20f)
        service.takeDose(slotId, takenAmount = 2f)
        assertThat(balanceOf(medId)).isEqualTo(18f)

        // 事后关闭追踪（不写流水），再撤销
        service.setStockTracking(medId, enabled = false)
        service.undoDose(slotId)

        // ★ 回到 20。旧实现读"当前 isStockTracked=false" ⇒ 不冲正 ⇒ 停在 18
        db.assertLedgerBalance(medId, 20f)
    }

    // ================================================================
    // 回归：不许因修上面两条而破坏原有语义
    // ================================================================

    @Test
    fun `不重新打卡时重复撤销 不会二次冲正`() = runTest {
        val (medId, slotId) = newMed(stock = 20f)
        service.takeDose(slotId, takenAmount = 2f)
        assertThat(service.undoDose(slotId)).isTrue()
        db.assertLedgerBalance(medId, 20f)

        // 槽位已回到 PENDING ⇒ 后续撤销直接被状态守卫挡掉
        assertThat(service.undoDose(slotId)).isFalse()
        db.assertLedgerBalance(medId, 20f)
    }

    @Test
    fun `跳过的槽位也可以撤销 但不产生任何库存流水`() = runTest {
        val (medId, slotId) = newMed(stock = 20f)
        service.skipDose(slotId)
        val before = db.inventoryTransactionDao().getTransactionsForMedication(medId).size

        assertThat(service.undoDose(slotId)).isTrue()
        assertThat(db.doseSlotDao().getSlotById(slotId)?.status).isEqualTo(SlotStatus.PENDING)
        // 跳过从不扣库存，撤销也不该凭空补一条
        assertThat(db.inventoryTransactionDao().getTransactionsForMedication(medId)).hasSize(before)
        db.assertLedgerBalance(medId, 20f)
    }

    @Test
    fun `事实全部转为 REVERTED 但一条都不删除`() = runTest {
        val (medId, slotId) = newMed(stock = 20f)
        repeat(2) {
            service.takeDose(slotId, takenAmount = 2f)
            service.undoDose(slotId)
        }
        val records = db.doseRecordDao().getAllRecordsBySlotId(slotId)
        // 两条事实都在，且都已 REVERTED（"吃过的药永不丢失"）
        assertThat(records).hasSize(2)
        assertThat(records.all { it.status == RecordStatus.REVERTED }).isTrue()
    }
}
