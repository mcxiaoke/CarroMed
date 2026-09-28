package com.mcxiaoke.carromed.core.domain.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.testing.assertDoseValue
import com.mcxiaoke.carromed.core.domain.model.Dose
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.DoseSlotEntity
import com.mcxiaoke.carromed.core.data.entity.InventoryTransactionEntity
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
 * 库存账本不变量测试 —— I1 / I2 / I3 / I4 / I12
 *
 * 设计见 docs/REMINDER-DOMAIN-REDESIGN.md §4。
 *
 * ## 这次重构移除了什么
 *
 * 旧实现在 `medications.current_stock` 里存一份账面，同时在
 * `inventory_transactions.change_amount` 里存一份变动，靠"两者必须相等"这个
 * **不变式**维持一致。P0-3 证明它会被打破：`takeDose` 里
 * `(currentStock - finalDose).coerceAtLeast(0f)` 把账面钳到 0，而流水仍记全额，
 * 于是 `SUM(change_amount) = -1.0 ≠ current_stock = 0.0`。
 *
 * 现在 `current_stock` 列**已删除**，余额只有一份权威定义：
 *
 * > `balance(medicationId) := COALESCE(SUM(change_amount), 0)`
 *
 * 守恒不再是需要维护的约束，而是恒等式。下列测试是这个决定的验收门。
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class InventoryLedgerInvariantTest {

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

    // ---------------- 测试夹具 ----------------

    private suspend fun newMed(
        name: String = "测试药",
        stock: Float = 0f,
        tracked: Boolean = true,
        alert: Float = 10f,
        date: String = "2026-09-27"
    ): Pair<Long, Long> {
        val medId = db.medicationDao().insert(
            MedicationEntity(
                name = name,
                unit = "片",
                minStockAlert = Dose.of(alert).milli,
                isStockTracked = tracked
            )
        )
        if (stock != 0f) {
            db.inventoryTransactionDao().insert(
                InventoryTransactionEntity(
                    medicationId = medId,
                    changeAmount = Dose.of(stock).milli,
                    balanceAfter = Dose.of(stock).milli,
                    txType = TransactionType.CALIBRATION_ADJUST,
                    note = "测试建档"
                )
            )
        }
        val slotId = db.doseSlotDao().insert(
            DoseSlotEntity(
                medicationId = medId,
                policyId = 1L,
                scheduledDate = date,
                scheduledTime = "08:00",
                scheduledTs = 1790467200000L,
                doseAmount = 1000,
                status = SlotStatus.PENDING
            )
        )
        return medId to slotId
    }
    /** 台账余额（展示值，毫单位 -> Float） */
    private suspend fun balanceOf(medId: Long): Float =
        Dose(db.inventoryTransactionDao().getSumOfChanges(medId) ?: 0).asFloat
    private suspend fun snapshotOf(medId: Long): Float? =
        db.inventoryTransactionDao().getLatestTransaction(medId)?.balanceAfter?.let { Dose(it).asFloat }

    // ==================== I1 台账守恒 ====================

    @Test
    fun `I1 打卡后账面等于流水和`() = runTest {
        val (medId, slotId) = newMed(stock = 20f)

        assertThat(balanceOf(medId)).isEqualTo(20f)

        assertThat(service.takeDose(slotId, takenAmount = 2f)).isTrue()
        assertThat(balanceOf(medId)).isEqualTo(18f)
    }

    @Test
    fun `I1 补录服药后账面等于流水和`() = runTest {
        val (medId, _) = newMed(stock = 10f)
        service.logManualDose(medId, System.currentTimeMillis(), 3f)
        assertThat(balanceOf(medId)).isEqualTo(7f)
    }

    @Test
    fun `I1 补货与盘点后账面等于流水和`() = runTest {
        val (medId, _) = newMed(stock = 10f)
        service.refillStock(medId, 30f)
        assertThat(balanceOf(medId)).isEqualTo(40f)

        assertThat(service.calibrateStock(medId, 25f)).isTrue()
        assertThat(balanceOf(medId)).isEqualTo(25f)
    }

    @Test
    fun `I1 批量操作后账面仍等于流水和`() = runTest {
        val repeats = 12
        val (medId, _) = newMed(stock = 100f)
        repeat(repeats) { i ->
            when (i % 4) {
                0 -> service.refillStock(medId, 7f)
                1 -> service.logManualDose(medId, System.currentTimeMillis() + i, 2f)
                2 -> service.calibrateStock(medId, 50f)
                else -> service.calibrateStock(medId, 12.5f)
            }
            // 每一步都必须守恒 —— 不是只在最后检查
            assertThat(balanceOf(medId)).isEqualTo(snapshotOf(medId) ?: 0f)
        }
    }

    // ==================== I2 快照与权威值一致 ====================

    @Test
    fun `I2 最后一条流水的 balanceAfter 等于 SUM`() = runTest {
        val repeats = 12
        val (medId, _) = newMed(stock = 30f)
        repeat(repeats) { i ->
            if (i % 2 == 0) service.refillStock(medId, 5f) else service.logManualDose(medId, i.toLong(), 1f)
        }
        // balanceAfter 是展示用快照；权威值是 SUM。两者一旦分叉，这条立刻失败。
        assertThat(snapshotOf(medId)).isEqualTo(balanceOf(medId))
    }

    @Test
    fun `I2 流水按时间顺序的 balanceAfter 构成前缀和`() = runTest {
        val (medId, _) = newMed(stock = 0f)
        service.refillStock(medId, 10f)
        service.logManualDose(medId, 1L, 3f)

        val ascending = db.inventoryTransactionDao()
            .getTransactionsForMedication(medId).reversed()   // DAO 按 id DESC 返回
        var running = 0
        ascending.forEach { tx ->
            running += tx.changeAmount
            assertThat(tx.balanceAfter).isEqualTo(running)
        }

    }

    @Test
    fun `I3 同一槽位连打卡 3 次只扣一次库存`() = runTest {
        val (medId, slotId) = newMed(stock = 20f)

        val results = (1..3).map { service.takeDose(slotId, takenAmount = 2f) }

        assertThat(results).containsExactly(true, false, false)
        assertThat(balanceOf(medId)).isEqualTo(18f)

        val records = db.doseRecordDao().getAllRecordsBySlotId(slotId)
        assertThat(records).hasSize(1)
    }

    @Test
    fun `I3 打卡后重复调用不产生第二条事实`() = runTest {
        val (medId, slotId) = newMed(stock = 20f)
        service.takeDose(slotId, takenAmount = 2f)
        val firstRecordId = db.doseRecordDao().getRecordBySlotId(slotId)!!.id

        repeat(5) { service.takeDose(slotId, takenAmount = 2f) }

        val records = db.doseRecordDao().getAllRecordsBySlotId(slotId)
        assertThat(records).hasSize(1)
        assertThat(records.single().id).isEqualTo(firstRecordId)
        assertThat(balanceOf(medId)).isEqualTo(18f)
    }

    @Test
    fun `I3 重复跳过同样幂等`() = runTest {
        val (medId, slotId) = newMed(stock = 20f)
        val results = (1..3).map { service.skipDose(slotId) }
        assertThat(results).containsExactly(true, false, false)
        assertThat(balanceOf(medId)).isEqualTo(20f)   // 跳过永不扣库存
    }

    /**
     * I3 的**穷举**版：重复次数 0..6 全跑一遍，每次重复都带**不同的剂量**。
     *
     * ## 为什么穷举而不是随机
     *
     * 设计文档 §4 写的是"属性化测试（随机重复次数 0..5）"。但这个定义域只有 6 个元素 ——
     * 穷举严格强于随机采样，而 `markCompletedIfOpen` 的幂等锚点在
     * 真实 SQLite 上跑，jqwik（JUnit Platform）也搭不起 Robolectric 环境。
     * 在一个 6 元素的定义域上追求"随机"，只是把确定性换成了不确定性。
     *
     * ## 为什么要每次都换剂量
     *
     * 前面那条 `I3 打卡后重复调用不产生第二条事实` 用的是同一个 `takenAmount = 2f`，
     * 于是"后一次把前一次的剂量改掉"这类缺陷在它眼里和没发生一样。
     * 这里第 i 次传 `1f + i`，任何"后来者覆盖先来者"的实现都会立刻现形。
     */
    @Test
    fun `I3 任意重复次数下 只记一条事实 只扣一次 且以首次剂量为准`() = runTest {
        for (n in 0..6) {
            val (medId, slotId) = newMed(name = "药$n", stock = 20f)

            val results = (0 until n).map { i ->
                service.takeDose(slotId, takenAmount = 1f + i, note = "第${i + 1}次")
            }
            // 恰好一次成功，且是第一次（n=0 时压根没调用，成功 0 次才是对的）
            assertThat(results.count { it }).isEqualTo(if (n == 0) 0 else 1)
            if (n > 0) assertThat(results.first()).isTrue()

            val records = db.doseRecordDao().getAllRecordsBySlotId(slotId)
            assertThat(records).hasSize(if (n == 0) 0 else 1)
            if (n == 0) {
                // 一次都没打 ⇒ 不该产生任何扣减
                assertThat(balanceOf(medId)).isEqualTo(20f)
                continue
            }

            // ★ 剂量取**第一次**的值（1.0），不是最后一次（1.0 + n - 1）
            assertDoseValue(records.single().doseTaken, 1f)

            // 扣减流水恰好一条，且指向那条事实
            val deduction = db.inventoryTransactionDao()
                .getTransactionsForMedication(medId)
                .filter { it.recordId == records.single().id }
            assertThat(deduction).hasSize(1)
            assertDoseValue(deduction.single().changeAmount, -1f)

            assertThat(balanceOf(medId)).isEqualTo(19f)
        }
    }

    // ==================== I4 撤销对称 ====================

    @Test
    fun `I4 撤销后账面精确回到原值且事实保留为 REVERTED`() = runTest {
        val (medId, slotId) = newMed(stock = 20f)
        service.takeDose(slotId, takenAmount = 2f)
        assertThat(balanceOf(medId)).isEqualTo(18f)

        assertThat(service.undoDose(slotId)).isTrue()

        // 账面精确回到 20
        assertThat(balanceOf(medId)).isEqualTo(20f)
        // 槽位回到待服
        assertThat(db.doseSlotDao().getSlotById(slotId)?.status).isEqualTo(SlotStatus.PENDING)
        // ⚠️ 事实**不被删除**，而是标记为 REVERTED（"吃过的药永不丢失"）
        val records = db.doseRecordDao().getAllRecordsBySlotId(slotId)
        assertThat(records).hasSize(1)
        assertThat(records.single().status).isEqualTo(RecordStatus.REVERTED)
        assertThat(records.single().doseTaken).isEqualTo(2000)
    }

    @Test
    fun `I4 台账里留下冲正流水而不是抹掉扣减`() = runTest {
        val (medId, slotId) = newMed(stock = 20f)
        service.takeDose(slotId, takenAmount = 2f)
        service.undoDose(slotId)

        val types = db.inventoryTransactionDao()
            .getTransactionsForMedication(medId).map { it.txType }
        assertThat(types).contains(TransactionType.TAKEN_DEDUCT)
        assertThat(types).contains(TransactionType.REVERT_ROLLBACK)
    }

    @Test
    fun `I4 重复撤销不会二次冲正`() = runTest {
        val (medId, slotId) = newMed(stock = 20f)
        service.takeDose(slotId, takenAmount = 2f)
        assertThat(service.undoDose(slotId)).isTrue()
        val afterFirst = balanceOf(medId)

        // 第二次撤销：事实已是 REVERTED 且槽位已回 PENDING，应返回 false 且不改账面
        assertThat(service.undoDose(slotId)).isFalse()
        assertThat(balanceOf(medId)).isEqualTo(afterFirst)
        assertThat(balanceOf(medId)).isEqualTo(20f)
    }

    // ==================== I12 负库存合法 ====================

    @Test
    fun `I12 库存不足时允许扣成负数且守恒仍成立`() = runTest {
        // 账面上只有 0.5 片，却要吃 1.0 片 —— 旧实现在这里会打破守恒（P0-3）
        val (medId, slotId) = newMed(stock = 0.5f)

        assertThat(service.takeDose(slotId, takenAmount = 1f)).isTrue()

        val balance = balanceOf(medId)
        assertThat(balance).isEqualTo(-0.5f)          // 允许为负（FINAL-PRODUCT D-9）
        assertThat(snapshotOf(medId)).isEqualTo(balance)  // 守恒 + 快照一致
    }

    @Test
    fun `I12 连续超量扣减不会把余额钳在 0`() = runTest {
        val (medId, slotId) = newMed(stock = 1f)
        service.takeDose(slotId, takenAmount = 1f)
        // 再补一个槽位继续扣
        val secondSlot = db.doseSlotDao().insert(
            DoseSlotEntity(
                medicationId = medId, policyId = 1L,
                scheduledDate = "2026-09-28", scheduledTime = "08:00",
                scheduledTs = 1790553600000L, doseAmount = 3000, status = SlotStatus.PENDING
            )
        )
        service.takeDose(secondSlot, takenAmount = 3f)

        assertThat(balanceOf(medId)).isEqualTo(-3f)
    }

    @Test
    fun `I12 负库存可以通过盘点校准拉回正数`() = runTest {
        val (medId, slotId) = newMed(stock = 0.5f)
        service.takeDose(slotId, takenAmount = 1f)
        assertThat(balanceOf(medId)).isEqualTo(-0.5f)

        // 用户拿药盒一数是 12 片，盘点校准
        assertThat(service.calibrateStock(medId, 12f)).isTrue()
        assertThat(balanceOf(medId)).isEqualTo(12f)
        assertThat(snapshotOf(medId)).isEqualTo(12f)
    }

    @Test
    fun `I12 盘点到相同数量不产生流水`() = runTest {
        val (medId, _) = newMed(stock = 10f)
        val before = db.inventoryTransactionDao().getTransactionsForMedication(medId).size
        assertThat(service.calibrateStock(medId, 10f)).isFalse()
        assertThat(db.inventoryTransactionDao().getTransactionsForMedication(medId)).hasSize(before)
    }

    // ==================== 关闭库存追踪 ====================

    @Test
    fun `关闭追踪后打卡不再产生流水`() = runTest {
        val (medId, slotId) = newMed(stock = 20f)
        service.takeDose(slotId, takenAmount = 2f)
        val afterTake = balanceOf(medId)
        assertThat(afterTake).isEqualTo(18f)

        service.setStockTracking(medId, false)
        val second = db.doseSlotDao().insert(
            DoseSlotEntity(
                medicationId = medId, policyId = 1L,
                scheduledDate = "2026-09-28", scheduledTime = "08:00",
                scheduledTs = 1790553600000L, doseAmount = 1000, status = SlotStatus.PENDING
            )
        )
        service.takeDose(second, takenAmount = 1f)

        // 服药事实照记（服药是不可否认的事实），但账面不再变动
        assertThat(balanceOf(medId)).isEqualTo(18f)
        assertThat(db.doseRecordDao().getRecordBySlotId(second)).isNotNull()
    }

    @Test
    fun `开启追踪且账面为零时写入一条建档流水`() = runTest {
        val (medId, _) = newMed(stock = 0f, tracked = false)
        assertThat(balanceOf(medId)).isEqualTo(0f)

        service.setStockTracking(medId, true, initialStock = 30f)

        assertThat(balanceOf(medId)).isEqualTo(30f)
        assertThat(snapshotOf(medId)).isEqualTo(30f)
    }

    @Test
    fun `开启追踪时给出的初值与账面不同则走校准而非直接改账面`() = runTest {
        val (medId, _) = newMed(stock = 10f, tracked = false)
        service.setStockTracking(medId, true, initialStock = 25f)
        // 关键：账面从 10 变成 25 是通过一条 +15 的流水实现的，不是 UPDATE
        assertThat(balanceOf(medId)).isEqualTo(25f)
        val lastTx = db.inventoryTransactionDao().getLatestTransaction(medId)!!
        assertThat(lastTx.txType).isEqualTo(TransactionType.CALIBRATION_ADJUST)
        assertDoseValue(lastTx.changeAmount, 15f)
    }

    // ==================== 改计划不冲历史 ====================

    @Test
    fun `reconcileSchedule 保留已完成的槽位与事实`() = runTest {
        val (medId, slotId) = newMed(stock = 20f)
        val policyId = db.schedulePolicyDao().savePolicyWithTimes(
            SchedulePolicyEntity(
                medicationId = medId,
                policyType = PolicyType.DAILY,
                startDate = LocalDate.now().format(
                    com.mcxiaoke.carromed.core.domain.engine.SlotProjectionEngine.DATE_FORMATTER
                )
            ),
            listOf(PolicyTimeEntity(policyId = 0, timeOfDay = "08:00", doseAmount = 1000))
        )
        db.doseSlotDao().insert(
            DoseSlotEntity(
                id = 9001L, medicationId = medId, policyId = policyId,
                scheduledDate = LocalDate.now().toString(), scheduledTime = "08:00",
                scheduledTs = System.currentTimeMillis(), doseAmount = 1000, status = SlotStatus.PENDING
            )
        )
        service.takeDose(9001L, takenAmount = 1f)
        val balanceBefore = balanceOf(medId)

        service.reconcileSchedule(medId, LocalDate.now(), LocalDate.now().plusDays(3))

        assertThat(db.doseSlotDao().getSlotById(9001L)?.status).isEqualTo(SlotStatus.COMPLETED)
        assertThat(db.doseRecordDao().getRecordBySlotId(9001L)).isNotNull()
        assertThat(balanceOf(medId)).isEqualTo(balanceBefore)
    }
}
