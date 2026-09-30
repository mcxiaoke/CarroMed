package com.mcxiaoke.carromed.core.domain.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.DoseRecordEntity
import com.mcxiaoke.carromed.core.data.entity.DoseSlotEntity
import com.mcxiaoke.carromed.core.data.entity.InventoryTransactionEntity
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
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
import java.time.ZoneId

/**
 * **未来槽位不可表态**（服务层，`docs/PLAN-FUTURE-SLOT-20260929.md`）。
 *
 * ## 缺陷
 *
 * `markCompletedIfOpen` / `markSkippedIfOpen` / `snoozeSlot` 的守卫只看 `status`，
 * 于是今日页翻到明天、点一下 ✓ 就会：
 *
 * 1. 凭空生成一条"明天已服"的事实（`actual_ts` 是今天、计划日是明天，自相矛盾）；
 * 2. 扣一次库存（实物没少，账面少了）；
 * 3. 撤掉明天的闹钟 —— 槽位变成 COMPLETED 后，`AlarmReconciler` 只给
 *    `getOpenSlots()` 排闹钟，**这一撤就再也排不回来**。
 *
 * ## 断言纪律
 *
 * ⚠️ **不能只断言返回值。** 本项目已有两例"测试全绿但实现是错的"，
 * 其中恒真断言正是从"只看返回值"来的。所以每条拒绝用例都同时断言：
 * `dose_slots` 状态未变、`dose_records` **零新增**、`inventory_transactions` **零新增**
 * —— "零写入"才是那个能被实现改坏而变红的断言。
 *
 * 时钟显式注入（[DoseTrackingService] 的 `todayProvider`），
 * 所以这些用例的成败与运行日历**无关**。
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class FutureSlotActionGuardTest {

    private lateinit var db: AppDatabase
    private lateinit var tracking: DoseTrackingService

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** fixture 的"今天"。相对真实时间取值，避免与日历耦合 */
    private val today: LocalDate = LocalDate.now()

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        tracking = DoseTrackingService(db, todayProvider = { today })
    }

    @After
    @Throws(IOException::class)
    fun tearDown() = db.close()

    // ==================== fixture ====================

    /** 建档流水：10 片 */
    private suspend fun givenMedication(): Long {
        val medId = db.medicationDao().insert(
            MedicationEntity(name = "环孢素", unit = "片", isStockTracked = true)
        )
        db.inventoryTransactionDao().insert(
            InventoryTransactionEntity(
                medicationId = medId,
                changeAmount = 10000,
                balanceAfter = 10000,
                txType = TransactionType.CALIBRATION_ADJUST,
                note = "建档"
            )
        )
        return medId
    }

    private suspend fun slotOn(
        medId: Long,
        date: LocalDate,
        status: SlotStatus = SlotStatus.PENDING,
        time: String = "10:30"
    ): DoseSlotEntity {
        val id = db.doseSlotDao().insert(
            DoseSlotEntity(
                medicationId = medId,
                policyId = 1L,
                scheduledDate = date.toString(),
                scheduledTime = time,
                scheduledTs = date.atTime(10, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                doseAmount = 1000,
                status = status,
                actualTakenTs = if (status == SlotStatus.COMPLETED) System.currentTimeMillis() else null
            )
        )
        return db.doseSlotDao().getSlotById(id)!!
    }

    /** 故意写一个非规范的计划日串（月不零填充） */
    private suspend fun slotWithRawDate(medId: Long, rawDate: String): DoseSlotEntity {
        val id = db.doseSlotDao().insert(
            DoseSlotEntity(
                medicationId = medId,
                policyId = 1L,
                scheduledDate = rawDate,
                scheduledTime = "10:30",
                scheduledTs = System.currentTimeMillis(),
                doseAmount = 1000,
                status = SlotStatus.PENDING
            )
        )
        return db.doseSlotDao().getSlotById(id)!!
    }

    /** 给一条"已产生结论"的槽位补上事实与台账（未来坏数据的样子） */
    private suspend fun givenConclusion(slot: DoseSlotEntity, medId: Long): Long {
        val recordId = db.doseRecordDao().insert(
            DoseRecordEntity(
                slotId = slot.id,
                medicationId = medId,
                actualTs = System.currentTimeMillis(),
                doseTaken = 1000,
                status = RecordStatus.COMPLETED,
                note = "未来已服（坏数据）"
            )
        )
        db.inventoryTransactionDao().insert(
            InventoryTransactionEntity(
                medicationId = medId,
                recordId = recordId,
                changeAmount = -1000,
                balanceAfter = 9000,
                txType = TransactionType.TAKEN_DEDUCT,
                note = "未来已服（坏数据）"
            )
        )
        return recordId
    }

    private suspend fun recordCount() = db.doseRecordDao().getAllRecords().size
    private suspend fun ledgerCount() = db.inventoryTransactionDao().getAllTransactions().size

    // ==================== 拒绝：未来 ====================

    @Test
    fun `未来槽位打卡被拒 且不产生任何写入`() = runTest {
        val medId = givenMedication()
        val slot = slotOn(medId, today.plusDays(1))
        val recordsBefore = recordCount()
        val ledgerBefore = ledgerCount()

        assertThat(tracking.takeDose(slot.id)).isFalse()

        val after = db.doseSlotDao().getSlotById(slot.id)!!
        assertThat(after.status).isEqualTo(SlotStatus.PENDING)
        assertThat(after.actualTakenTs).isNull()
        assertThat(recordCount()).isEqualTo(recordsBefore)
        assertThat(ledgerCount()).isEqualTo(ledgerBefore)
        assertThat(db.inventoryTransactionDao().getSumOfChanges(medId)).isEqualTo(10000)
    }

    @Test
    fun `未来槽位跳过被拒 且不产生任何写入`() = runTest {
        val medId = givenMedication()
        val slot = slotOn(medId, today.plusDays(1))
        val recordsBefore = recordCount()

        assertThat(tracking.skipDose(slot.id)).isFalse()

        val after = db.doseSlotDao().getSlotById(slot.id)!!
        assertThat(after.status).isEqualTo(SlotStatus.PENDING)
        assertThat(after.actualTakenTs).isNull()
        assertThat(recordCount()).isEqualTo(recordsBefore)
    }

    @Test
    fun `未来槽位推迟被拒 且 snooze_until_ts 仍为空`() = runTest {
        val medId = givenMedication()
        val slot = slotOn(medId, today.plusDays(1))

        assertThat(tracking.snoozeDose(slot.id, 30)).isFalse()

        val after = db.doseSlotDao().getSlotById(slot.id)!!
        assertThat(after.status).isEqualTo(SlotStatus.PENDING)
        assertThat(after.snoozeUntilTs).isNull()
    }

    /**
     * 非规范计划日串（月不零填充）：字典序排在规范串之后 ⇒ 判为未来 ⇒ 拒绝。
     * SQL 与 Kotlin 走的是同一套字符串比较，**结论必然一致**。
     *
     * 用「明年 + 单位数月份」构造坏串，而不是把今天的 ISO 串去掉零填充：
     * 后者在 10 月以后就退化成规范串（`2026-10-30`），测试会随月份自己变绿。
     */
    @Test
    fun `非规范计划日串被判为未来 拒绝且零写入`() = runTest {
        val medId = givenMedication()
        val raw = "${today.year + 1}-1-1"
        val slot = slotWithRawDate(medId, raw)
        val recordsBefore = recordCount()

        assertThat(tracking.takeDose(slot.id)).isFalse()

        assertThat(db.doseSlotDao().getSlotById(slot.id)!!.status).isEqualTo(SlotStatus.PENDING)
        assertThat(recordCount()).isEqualTo(recordsBefore)
    }

    // ==================== 防"修过头"：今天 / 过去照常 ====================

    @Test
    fun `今天的槽位照常可打卡 库存照常扣减`() = runTest {
        val medId = givenMedication()
        val slot = slotOn(medId, today)

        assertThat(tracking.takeDose(slot.id)).isTrue()

        assertThat(db.doseSlotDao().getSlotById(slot.id)!!.status).isEqualTo(SlotStatus.COMPLETED)
        assertThat(db.doseRecordDao().getAllRecords().single().status).isEqualTo(RecordStatus.COMPLETED)
        assertThat(db.inventoryTransactionDao().getSumOfChanges(medId)).isEqualTo(9000)
    }

    @Test
    fun `过去的槽位照常可跳过与推迟（静默待办补记）`() = runTest {
        val medId = givenMedication()
        val yesterday = slotOn(medId, today.minusDays(1))
        val olderSlot = slotOn(medId, today.minusDays(3), time = "20:00")

        assertThat(tracking.skipDose(yesterday.id)).isTrue()
        assertThat(tracking.snoozeDose(olderSlot.id, 15)).isTrue()

        assertThat(db.doseSlotDao().getSlotById(yesterday.id)!!.status).isEqualTo(SlotStatus.SKIPPED)
        val snoozed = db.doseSlotDao().getSlotById(olderSlot.id)!!
        assertThat(snoozed.status).isEqualTo(SlotStatus.SNOOZED)
        assertThat(snoozed.snoozeUntilTs).isNotNull()
    }

    // ==================== 修复通道不能被误伤 ====================

    /**
     * ⭐ 未来的"已服"坏记录（本缺陷的产物）必须仍可**撤销**。
     *
     * 若把日期守卫也加到 `revertToPending` 上，用户会被自己造出来的垃圾数据
     * **永久锁死**：记录显示已服、库存少了一片、而唯一的修复入口失败。
     * 撤销是修复通道，不是"施加新结论"。
     */
    @Test
    fun `未来槽位的已服坏记录仍然可以撤销`() = runTest {
        val medId = givenMedication()
        val slot = slotOn(medId, today.plusDays(1), status = SlotStatus.COMPLETED)
        givenConclusion(slot, medId)
        assertThat(db.inventoryTransactionDao().getSumOfChanges(medId)).isEqualTo(9000)

        assertThat(tracking.undoDose(slot.id)).isTrue()

        assertThat(db.doseSlotDao().getSlotById(slot.id)!!.status).isEqualTo(SlotStatus.PENDING)
        assertThat(db.doseRecordDao().getAllRecordsBySlotId(slot.id).single().status)
            .isEqualTo(RecordStatus.REVERTED)
        // 台账按净额冲正 ⇒ 回到建档时的 10 片
        assertThat(db.inventoryTransactionDao().getSumOfChanges(medId)).isEqualTo(10000)
    }

    /**
     * ⭐ 未来槽位**改判**被拒，且必须**在作废之前**就返回。
     *
     * 改判是"先作废、再施加"，而施加用的 `markCompletedIfOpen` 已带日期守卫。
     * 若把判断留给那一步：作废已经落库（`withTransaction` 正常返回即提交），
     * 于是槽位停在 PENDING、事实全标 REVERTED、台账被冲正 —— 用户看到半截状态。
     * 这条测试钉的就是"拒绝必须是无副作用的拒绝"。
     */
    @Test
    fun `未来槽位改判被拒 且不留下半截状态`() = runTest {
        val medId = givenMedication()
        val slot = slotOn(medId, today.plusDays(1), status = SlotStatus.COMPLETED)
        givenConclusion(slot, medId)
        val ledgerBefore = db.inventoryTransactionDao().getAllTransactions().map { it.id }

        assertThat(tracking.restateSlot(slot.id, RecordStatus.SKIPPED)).isFalse()

        assertThat(db.doseSlotDao().getSlotById(slot.id)!!.status).isEqualTo(SlotStatus.COMPLETED)
        assertThat(db.doseRecordDao().getAllRecordsBySlotId(slot.id).single().status)
            .isEqualTo(RecordStatus.COMPLETED)
        assertThat(db.inventoryTransactionDao().getAllTransactions().map { it.id })
            .isEqualTo(ledgerBefore)
        assertThat(db.inventoryTransactionDao().getSumOfChanges(medId)).isEqualTo(9000)
    }

    @Test
    fun `计划日等于今天的槽位可以改判（边界取等号）`() = runTest {
        val medId = givenMedication()
        val slot = slotOn(medId, today, status = SlotStatus.COMPLETED)
        givenConclusion(slot, medId)

        assertThat(tracking.restateSlot(slot.id, RecordStatus.SKIPPED)).isTrue()

        assertThat(db.doseSlotDao().getSlotById(slot.id)!!.status).isEqualTo(SlotStatus.SKIPPED)
        assertThat(db.inventoryTransactionDao().getSumOfChanges(medId)).isEqualTo(10000)
    }
}
