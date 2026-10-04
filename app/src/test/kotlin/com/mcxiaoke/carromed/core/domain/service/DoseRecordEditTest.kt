package com.mcxiaoke.carromed.core.domain.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.DoseSlotEntity
import com.mcxiaoke.carromed.core.data.entity.InventoryTransactionEntity
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.model.RecordStatus
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

/**
 * 修改 / 撤销服药记录（UX 方案第 2 步）。
 *
 * 守的是**台账与事实的一致性**。改剂量如果不碰台账，会出现
 * "记录上写着吃了 2 片、账上只扣了 1 片"——库存页与消耗排行永远对不上，
 * 而且**没有任何报错**。这是本文件存在的全部理由。
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class DoseRecordEditTest {

    private lateinit var db: AppDatabase
    private lateinit var tracking: DoseTrackingService
    private var medId: Long = 0

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

    private suspend fun manualRecord(
        ts: Long,
        dose: Float,
        deduct: Boolean = true
    ): Long = tracking.logManualDose(
        medicationId = medId,
        actualTs = ts,
        doseAmount = dose,
        deductStock = deduct
    )

    /**
     * 补录时间窗（[MANUAL_DOSE_BACKFILL_DAYS] 天）内的合法时刻：昨天 09:00。
     * 相对今天取值 —— 固定历日的 fixture 会在窗口滑过后随钟变红
     * （AGENTS §3「下午全绿早上全红」），且 `logManualDose` 现在会拒绝窗外的时刻。
     */
    private fun recentTs(): Long =
        java.time.LocalDate.now().minusDays(1).atTime(9, 0)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    /**
     * 造一个来自排班的记录（`slot_id != null`），用于验证"计划来源的事实可改"与"槽位同步"。
     *
     * 槽位必须以 `PENDING` 插入再由 [DoseTrackingService.takeDose] 置为完成：
     * 直接插 `COMPLETED` 会被 `markCompletedIfOpen` 的幂等锚点挡掉，
     * `takeDose` 返回 false —— 那是**正确行为**，本测试第一版就踩了这个。
     */
    private suspend fun slotRecord(doseMilli: Int = 1000): Long {
        val now = System.currentTimeMillis()
        val slotId = db.doseSlotDao().insert(
            DoseSlotEntity(
                medicationId = medId,
                policyId = 0,
                scheduledDate = "2026-09-29",
                scheduledTime = "08:00",
                scheduledTs = now,
                doseAmount = doseMilli,
                status = com.mcxiaoke.carromed.core.data.model.SlotStatus.PENDING
            )
        )
        check(tracking.takeDose(slotId = slotId, actualTs = now)) { "打卡失败" }
        return db.doseRecordDao().getAllRecordsBySlotId(slotId).single().id
    }

    /**
     * 某条服药事实关联的台账流水，按 `id` **升序**（= 写入顺序）。
     *
     * ⚠️ 必须显式排序：[DoseRecordDao] 之外的
     * `InventoryTransactionDao.getAllTransactions()` 是 `ORDER BY id DESC`
     * （最新在前，给库存页用）。直接用它的 `.last()` 会拿到**最早**那一行，
     * "断言最后补了一条差额流水"就会变成"断言没有差额流水"——一个反向的假通过。
     */
    private suspend fun ledgerFor(recordId: Long) =
        db.inventoryTransactionDao().getAllTransactions()
            .filter { it.recordId == recordId }
            .sortedBy { it.id }

    // ==================== editDose ====================

    @Test
    fun `改剂量 补差额流水且账实相符`() = runTest {
        val rid = manualRecord(ts = recentTs(), dose = 1f)
        db.assertLedgerBalance(medId, 29f)              // 30 - 1

        assertThat(tracking.editDose(rid, newDoseAmount = 3f)).isTrue()

        // 账：30 - 3 = 27，而不是 29（否则"记录说 3 片、账只扣 1 片"）
        db.assertLedgerBalance(medId, 27f)
        assertThat(db.doseRecordDao().getRecordById(rid)!!.doseTaken).isEqualTo(3000)
        // 事实上的剂量与台账净额必须互为相反数
        assertThat(Dose(db.inventoryTransactionDao().getSumOfChangeByRecordId(rid)!!).asFloat)
            .isEqualTo(-3f)
    }

    @Test
    fun `改剂量后 I2 仍成立（最后一条流水 balanceAfter 等于 SUM）`() = runTest {
        val rid = manualRecord(ts = recentTs(), dose = 1f)
        tracking.editDose(rid, newDoseAmount = 2.5f)

        val last = db.inventoryTransactionDao().getLatestTransaction(medId)!!
        assertThat(last.balanceAfter).isEqualTo(db.inventoryTransactionDao().getSumOfChanges(medId)!!)
    }

    @Test
    fun `改小剂量 补正数冲正`() = runTest {
        val rid = manualRecord(ts = recentTs(), dose = 5f)
        db.assertLedgerBalance(medId, 25f)

        assertThat(tracking.editDose(rid, newDoseAmount = 2f)).isTrue()
        db.assertLedgerBalance(medId, 28f)              // 30 - 2
        assertThat(ledgerFor(rid).last().changeAmount).isEqualTo(3000)  // 退回多扣的 3 片
    }

    @Test
    fun `台账只增不改 改剂量是补一行而不是改旧行`() = runTest {
        val rid = manualRecord(ts = recentTs(), dose = 1f)
        val before = ledgerFor(rid).map { it.changeAmount }

        tracking.editDose(rid, newDoseAmount = 2f)
        val after = ledgerFor(rid).map { it.changeAmount }

        // 旧行一个都不能变（新行只能追加在后面）
        assertThat(after.subList(0, before.size)).isEqualTo(before)
        assertThat(after).hasSize(before.size + 1)
        assertThat(after.last()).isEqualTo(-1000)
    }

    /**
     * ⭐ 关键反例：当初**没扣库存**的记录，改剂量**不得凭空造账**。
     *
     * 补录时关掉「联动扣减库存」是合法的（用户只是记个事实）。
     * 这时 `getSumOfChangeByRecordId` 为 0，若实现无条件补一条差额流水，
     * 账面就会凭空少一片——用户没买药、没吃药，库存却少了。
     */
    @Test
    fun `未扣库存的记录改剂量不产生流水`() = runTest {
        val rid = manualRecord(ts = recentTs(), dose = 1f, deduct = false)
        db.assertLedgerBalance(medId, 30f)              // 没扣过

        assertThat(tracking.editDose(rid, newDoseAmount = 4f)).isTrue()

        assertThat(db.doseRecordDao().getRecordById(rid)!!.doseTaken).isEqualTo(4000) // 事实改了
        db.assertLedgerBalance(medId, 30f)              // 账没动 —— 这才对
        assertThat(ledgerFor(rid)).isEmpty()
    }

    @Test
    fun `非法剂量被拒且不写库`() = runTest {
        val rid = manualRecord(ts = recentTs(), dose = 1f)
        for (bad in listOf(0f, -1f, Float.NaN)) {
            val thrown = runCatching { tracking.editDose(rid, newDoseAmount = bad) }.exceptionOrNull()
            assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThat(db.doseRecordDao().getRecordById(rid)!!.doseTaken).isEqualTo(1000)
        assertThat(ledgerFor(rid)).hasSize(1)
    }

    @Test
    fun `已撤销的记录不能被改回来`() = runTest {
        val rid = manualRecord(ts = recentTs(), dose = 1f)
        assertThat(tracking.undoManualDose(rid)).isTrue()

        assertThat(tracking.editDose(rid, newDoseAmount = 9f)).isFalse()
        assertThat(db.doseRecordDao().getRecordById(rid)!!.doseTaken).isEqualTo(1000)
    }

    @Test
    fun `改备注不产生流水`() = runTest {
        val rid = manualRecord(ts = recentTs(), dose = 1f)
        val before = ledgerFor(rid).size

        assertThat(tracking.editDose(rid, newNote = "随餐温水送服")).isTrue()

        assertThat(db.doseRecordDao().getRecordById(rid)!!.note).isEqualTo("随餐温水送服")
        assertThat(ledgerFor(rid)).hasSize(before)      // 备注不改变"吃了多少"
    }

    @Test
    fun `槽位来源的记录 可以改剂量且计划剂量不被带着走`() = runTest {
        // 2026-10-04 口径修订：`dose_slots.dose_amount` 是**计划**剂量，
        // `dose_records.dose_taken` 是**事实**剂量 —— "其实只吃了半片"必须能被如实记录。
        // 旧实现（UX 方案 §3.3）直接拒绝，用户只能绕道「单次服用」，反而制造了新的歧义。
        val rid = slotRecord(doseMilli = 1000)
        db.assertLedgerBalance(medId, 29f)              // 30 - 1

        assertThat(tracking.editDose(rid, newDoseAmount = 0.5f)).isTrue()

        val record = db.doseRecordDao().getRecordById(rid)!!
        assertThat(record.doseTaken).isEqualTo(500)
        // ★ 计划剂量不随事实修改而变
        assertThat(db.doseSlotDao().getSlotById(record.slotId!!)!!.doseAmount).isEqualTo(1000)
        // 台账补差额流水：30 - 0.5 = 29.5
        db.assertLedgerBalance(medId, 29.5f)
    }

    @Test
    fun `槽位来源的记录 改时间会同步槽位的实际时刻`() = runTest {
        val rid = slotRecord()
        val record = db.doseRecordDao().getRecordById(rid)!!
        val slotId = record.slotId!!
        val moved = record.actualTs - 3_600_000L

        assertThat(tracking.editDose(rid, newActualTs = moved)).isTrue()

        assertThat(db.doseRecordDao().getRecordById(rid)!!.actualTs).isEqualTo(moved)
        // ★ 今日清单读 `slot.actualTakenTs`、记录详情读 `record.actualTs` ——
        // 只改一边会让同一次服药在两处各说一套，所以必须同步。
        assertThat(db.doseSlotDao().getSlotById(slotId)!!.actualTakenTs).isEqualTo(moved)
    }

    // ==================== 改时间 ====================

    @Test
    fun `手动补录的记录可以改时间且不产生流水`() = runTest {
        val ts = recentTs()
        val rid = manualRecord(ts = ts, dose = 1f)
        val before = ledgerFor(rid).size

        val moved = ts - 2 * 60 * 60 * 1000L    // 往前挪两小时
        assertThat(tracking.editDose(rid, newActualTs = moved)).isTrue()

        assertThat(db.doseRecordDao().getRecordById(rid)!!.actualTs).isEqualTo(moved)
        // 时间不影响"吃了多少" ⇒ 台账一行都不该多
        assertThat(ledgerFor(rid)).hasSize(before)
        db.assertLedgerBalance(medId, 29f)
    }

    @Test
    fun `不许把服药时间改到未来`() = runTest {
        val rid = manualRecord(ts = recentTs(), dose = 1f)
        val original = db.doseRecordDao().getRecordById(rid)!!.actualTs
        val future = System.currentTimeMillis() + 60_000L

        val thrown = runCatching { tracking.editDose(rid, newActualTs = future) }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(db.doseRecordDao().getRecordById(rid)!!.actualTs).isEqualTo(original)
    }

    @Test
    fun `已撤销的记录不能改时间`() = runTest {
        val rid = manualRecord(ts = recentTs(), dose = 1f)
        val original = db.doseRecordDao().getRecordById(rid)!!.actualTs
        assertThat(tracking.undoManualDose(rid)).isTrue()

        assertThat(tracking.editDose(rid, newActualTs = original - 3_600_000L)).isFalse()
        assertThat(db.doseRecordDao().getRecordById(rid)!!.actualTs).isEqualTo(original)
    }

    // ==================== undoManualDose ====================

    @Test
    fun `撤销临时用药 事实保留且账目回补`() = runTest {
        val rid = manualRecord(ts = recentTs(), dose = 2f)
        db.assertLedgerBalance(medId, 28f)

        assertThat(tracking.undoManualDose(rid)).isTrue()

        // 事实**没有**被删除，只是改了状态
        val rec = db.doseRecordDao().getRecordById(rid)
        assertThat(rec).isNotNull()
        assertThat(rec!!.status).isEqualTo(RecordStatus.REVERTED)
        assertThat(rec.doseTaken).isEqualTo(2000)      // 剂量列原样保留

        db.assertLedgerBalance(medId, 30f)             // 账回到出库前
    }

    @Test
    fun `撤销临时用药是幂等的`() = runTest {
        val rid = manualRecord(ts = recentTs(), dose = 2f)
        assertThat(tracking.undoManualDose(rid)).isTrue()
        assertThat(tracking.undoManualDose(rid)).isFalse()   // 已撤销 ⇒ 拒绝

        // ⭐ 账面不能被"撤销两次"冲正两次
        db.assertLedgerBalance(medId, 30f)
    }

    @Test
    fun `撤销未扣库存的临时用药不会平白加库存`() = runTest {
        val rid = manualRecord(ts = recentTs(), dose = 2f, deduct = false)
        assertThat(tracking.undoManualDose(rid)).isTrue()
        db.assertLedgerBalance(medId, 30f)             // 本来就是 30，不能变 32
    }

    @Test
    fun `撤销已撤销的记录后统计不计入`() = runTest {
        val today = java.time.LocalDate.now().minusDays(1)
        val ts = today.atTime(9, 0).atZone(java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        val rid = manualRecord(ts = ts, dose = 1f)

        val before = db.doseRecordDao()
            .getSumDoseTakenForMedication(medId, ts - 1000, ts + 1000) ?: 0
        assertThat(before).isEqualTo(1000)

        tracking.undoManualDose(rid)

        val after = db.doseRecordDao()
            .getSumDoseTakenForMedication(medId, ts - 1000, ts + 1000) ?: 0
        assertThat(after).isEqualTo(0)
    }

    /** ⭐ 按记录 id 撤销有槽位的记录时，必须连槽位一起退回 PENDING。 */
    @Test
    fun `槽位来源的记录走 undoDose 同样能撤销`() = runTest {
        val rid = slotRecord()
        db.assertLedgerBalance(medId, 29f)
        val slotId = db.doseRecordDao().getRecordById(rid)!!.slotId!!

        assertThat(tracking.undoManualDose(rid)).isTrue()

        assertThat(db.doseRecordDao().getRecordById(rid)!!.status).isEqualTo(RecordStatus.REVERTED)
        // 槽位必须退回 PENDING，否则下一次打卡会被幂等锚点挡掉、
        // 用户看着"待服用"却怎么点都记不上
        assertThat(db.doseSlotDao().getSlotById(slotId)!!.status)
            .isEqualTo(com.mcxiaoke.carromed.core.data.model.SlotStatus.PENDING)
        db.assertLedgerBalance(medId, 30f)
    }

    @Test
    fun `撤销不存在的记录返回 false`() = runTest {
        assertThat(tracking.undoManualDose(recordId = 999_999L)).isFalse()
    }

    /** 校准类流水与改剂量流水都必须在导出文案里有名字（穷尽 when 的回归防护）。 */
    @Test
    fun `改剂量流水可被导出识别`() = runTest {
        val rid = manualRecord(ts = recentTs(), dose = 1f)
        tracking.editDose(rid, newDoseAmount = 2f)
        val adjust = ledgerFor(rid).first { it.txType == TransactionType.DOSE_EDIT_ADJUST }
        assertThat(adjust).isNotNull()
        assertThat(adjust.changeAmount).isEqualTo(-1000)
        // balanceAfter 快照由 appendLedger 统一算，不允许手填错
        assertThat(adjust.balanceAfter)
            .isEqualTo(db.inventoryTransactionDao().getSumOfChanges(medId)!!)
    }
}
