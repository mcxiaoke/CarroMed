package com.mcxiaoke.carromed.core.alarm

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.InventoryTransactionEntity
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.entity.PolicyTimeEntity
import com.mcxiaoke.carromed.core.data.entity.SchedulePolicyEntity
import com.mcxiaoke.carromed.core.data.model.PolicyType
import com.mcxiaoke.carromed.core.data.model.RecordStatus
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import com.mcxiaoke.carromed.core.data.model.TransactionType
import com.mcxiaoke.carromed.core.domain.service.DoseTrackingService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.LocalDate

/**
 * 过期结算与打卡的**并发交错**测试（M1-3 / P0-1）。
 *
 * ## 被守的不变量
 *
 * > 一次服药只能产生**一条** COMPLETED 事实与**一次**扣减；
 * > 一旦槽位 COMPLETED，任何结算路径都不得把它改写回 EXPIRED。
 *
 * ## 旧实现为什么守不住
 *
 * 旧的 `markExpired` 是 `WHERE id = :slotId` —— **无状态守卫**。
 * 而同文件其余 5 个写入口（`markCompletedIfOpen` / `markSkippedIfOpen` /
 * `snoozeSlot` / `revertToPending` / `updateDerivedColumns`）都把
 * `status IN (...)` 下沉进 SQL 当幂等锚点。只有它没有。
 *
 * 交错序列（`getStaleOpenSlots` 与 `markExpired` 之间插一次打卡）：
 *
 * ```
 * T1: getStaleOpenSlots  读出 slot=7 (PENDING)
 * T2: takeDose(7)        → COMPLETED，事实入库，库存 -1
 * T3: markExpired(7)     → 无守卫，直接覆写成 EXPIRED
 * ```
 *
 * 结果是一条**已经生效的服药事实，对应的槽位却显示逾期**。二阶后果更糟：
 * 今日页把 EXPIRED 渲染成待办（徽标「已逾期…尚未确认」），用户再点一次确认
 * ⇒ `markCompletedIfOpen` 允许 EXPIRED ⇒ **第二条事实 + 二次扣库存**。
 * 而且此时撤销路径（`undoDose` 判 `status == COMPLETED`）已失效，用户想纠错也纠不回来。
 *
 * ## 本测试怎么做到"修复前先红"
 *
 * 不模拟真并发（那在单线程 Robolectric 上不稳定），而是**直接复现交错后的
 * 状态**：先打卡拿到 COMPLETED，再对同一槽位调用 `markExpired`。
 * 无守卫的旧实现会返回 1 并把状态改坏；修复后返回 0 且状态不变。
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class SlotSettlementGuardTest {

    private lateinit var db: AppDatabase
    private lateinit var tracking: DoseTrackingService

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val today: LocalDate get() = LocalDate.now()

    @Before
    fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        tracking = DoseTrackingService(db)
        val medId = db.medicationDao().insert(
            MedicationEntity(name = "环孢素", isStockTracked = true)
        )
        db.schedulePolicyDao().savePolicyWithTimes(
            SchedulePolicyEntity(
                medicationId = medId,
                policyType = PolicyType.DAILY,
                startDate = today.toString()
            ),
            listOf(PolicyTimeEntity(policyId = 0, timeOfDay = "23:30", doseAmount = 1000))
        )
        db.reminderSettingsDao().ensureDefaults(medId)
        AlarmReconciler.rescheduleAll(context, db)
    }

    @After
    @Throws(IOException::class)
    fun tearDown() = db.close()

    private suspend fun anyOpenSlot() = db.doseSlotDao().getOpenSlots().first()

    @Test
    fun `打卡后 结算不得覆写槽位状态`() = runBlocking {
        val slot = anyOpenSlot()

        // 先打卡：COMPLETED + 事实入库 + 库存扣减
        assertThat(tracking.takeDose(slot.id)).isTrue()
        assertThat(db.doseSlotDao().getSlotById(slot.id)!!.status).isEqualTo(SlotStatus.COMPLETED)

        // 再结算同一槽位：必须是 0 行（幂等锚点），状态原样保留
        val affected = db.doseSlotDao().markExpired(slot.id)

        assertThat(affected).isEqualTo(0)
        assertThat(db.doseSlotDao().getSlotById(slot.id)!!.status).isEqualTo(SlotStatus.COMPLETED)
    }

    @Test
    fun `打卡后 结算不得让用户二次打卡（不产生第二条事实 不二次扣库存）`() = runBlocking {
        val slot = anyOpenSlot()
        val medId = slot.medicationId
        assertThat(tracking.takeDose(slot.id)).isTrue()
        db.doseSlotDao().markExpired(slot.id)   // 旧实现下这里会覆写成 EXPIRED

        // 今日页此时会把这槽位渲染成待办；用户点确认
        val secondTap = tracking.takeDose(slot.id)

        assertThat(secondTap).isFalse()
        val records = db.doseRecordDao().getCompletedRecordsBySlot(slot.id)
            .filter { it.status == RecordStatus.COMPLETED }
        assertThat(records).hasSize(1)
        // 台账只有一条扣减：I1 守恒
        val sum = db.inventoryTransactionDao().getSumOfChanges(medId) ?: 0
        assertThat(sum).isEqualTo(-1000)
    }

    @Test
    fun `跳过后 结算同样不得覆写`() = runBlocking {
        val slot = anyOpenSlot()
        assertThat(tracking.skipDose(slot.id)).isTrue()
        assertThat(db.doseSlotDao().getSlotById(slot.id)!!.status).isEqualTo(SlotStatus.SKIPPED)

        assertThat(db.doseSlotDao().markExpired(slot.id)).isEqualTo(0)
        assertThat(db.doseSlotDao().getSlotById(slot.id)!!.status).isEqualTo(SlotStatus.SKIPPED)
    }

    @Test
    fun `重复结算幂等（第二次拿 0 行）`() = runBlocking {
        val slot = anyOpenSlot()
        assertThat(db.doseSlotDao().markExpired(slot.id)).isEqualTo(1)
        assertThat(db.doseSlotDao().getSlotById(slot.id)!!.status).isEqualTo(SlotStatus.EXPIRED)
        assertThat(db.doseSlotDao().markExpired(slot.id)).isEqualTo(0)
    }

    @Test
    fun `逾期槽位清干净 snooze_until_ts 不留脏值`() = runBlocking {
        val slot = anyOpenSlot()
        // fixture：`todayStr` 传槽位自己的计划日，构造的是"已推迟"状态本身
        db.doseSlotDao().snoozeSlot(slot.id, System.currentTimeMillis() + 600_000L, slot.scheduledDate)
        assertThat(db.doseSlotDao().getSlotById(slot.id)!!.snoozeUntilTs).isNotNull()

        assertThat(db.doseSlotDao().markExpired(slot.id)).isEqualTo(1)
        val after = db.doseSlotDao().getSlotById(slot.id)!!
        assertThat(after.status).isEqualTo(SlotStatus.EXPIRED)
        assertThat(after.snoozeUntilTs).isNull()
        assertThat(after.actualTakenTs).isNull()
    }

    /**
     * 库存守恒不能因为结算守卫被改坏。
     *
     * 选这个断言点是因为旧实现的故障恰好会让"账面"看起来仍然自洽
     * （第二次 `takeDose` 返回 false 不写流水）—— 唯一暴露问题的是**第二条事实**。
     * 两边都断言，且断言的是不变量本身，不是实现细节。
     */
    @Test
    fun `结算守卫不破坏库存守恒`() = runBlocking {
        val slot = anyOpenSlot()
        val medId = slot.medicationId
        db.inventoryTransactionDao().insert(
            InventoryTransactionEntity(
                medicationId = medId,
                changeAmount = 5000,
                balanceAfter = 5000,
                txType = TransactionType.CALIBRATION_ADJUST
            )
        )
        assertThat(tracking.takeDose(slot.id)).isTrue()
        db.doseSlotDao().markExpired(slot.id)

        assertThat(db.inventoryTransactionDao().getSumOfChanges(medId) ?: 0).isEqualTo(4000)
    }
}
