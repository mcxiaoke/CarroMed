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
import com.mcxiaoke.carromed.core.data.model.SlotStatus
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
 * 改剂量后，已排好的未来槽位必须跟着改（治 P0-2）。
 *
 * ## 缺陷
 *
 * `reconcileSchedule` 的 diff 键是**日历** `(scheduledDate, scheduledTime)`，
 * 命中 `existingKeys` 的槽位既不删也不改，只"只插新增"。
 * 而 `DoseSlotDao.update` **在生产代码里零调用** ——
 * 也就是说槽位的 `dose_amount` 在其整个生命周期内是**创建时冻结**的。
 *
 * 触发场景（正是用户最常做的操作）：
 *
 * 1. 药 A 每天 08:00 服 **1 片**，`reconcileSchedule` 排好今天 ~ +14 天；
 * 2. 用户进「提醒设置」把 08:00 改成 **2 片**并保存（换版写入新的 `policy_times`）；
 * 3. `ReminderSettingsViewModel` 调 `reconcileSchedule(medId)`：
 *    投影出的 `(今天,"08:00")` 已在 `existingKeys` 里 ⇒ **一行都不动**；
 * 4. 接下来 14 天，每次打卡 `takeDose(takenAmount = null)` 取 `slot.doseAmount = 1000`
 *    ⇒ **每剂只扣 1 片**，而计划是 2 片。
 *
 * 后果是双重的：`dose_records.dose_taken` 这个**不可变事实**被写错
 * （且按 I11 永远不能事后修正），台账也长期少扣。
 *
 * 为什么改「服药时刻」能生效而改「剂量」不能：改时刻会让 key 变化，
 * 旧槽位被删、新槽位被插，`dose_amount` 自然带上新值。
 * **只有"时刻不变、剂量变了"这一条路是漏的** —— 而它恰恰是最高频的那条。
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class DoseChangeUpdatesSlotsTest {

    private lateinit var db: AppDatabase
    private lateinit var admin: MedicationAdminService
    private lateinit var tracking: DoseTrackingService

    private val today: LocalDate = LocalDate.of(2026, 9, 28)

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        admin = MedicationAdminService(db)
        tracking = DoseTrackingService(db)
    }

    @After
    @Throws(IOException::class)
    fun tearDown() = db.close()

    /** 每日 08:00、1 片、追踪库存 20 片的药 */
    private suspend fun newMed(): Long {
        val medId = admin.saveProfile(
            MedicationAdminService.ProfileDraft(
                name = "环孢素", unit = "片", defaultDose = 1f
            )
        )
        admin.saveReminderPolicy(
            medicationId = medId,
            draft = MedicationAdminService.PolicyDraft(
                startDate = today.toString(),
                times = listOf(MedicationAdminService.TimeDraft("08:00", 1f, "早间"))
            )
        )
        tracking.setStockTracking(medId, enabled = true, initialStock = 20f)
        tracking.reconcileSchedule(medId, today, today.plusDays(13))
        return medId
    }

    private suspend fun openSlots(medId: Long) = db.doseSlotDao().getAllSlots()
        .filter { it.medicationId == medId && it.status == SlotStatus.PENDING }

    private suspend fun setDose(medId: Long, time: String, dose: Float) {
        admin.saveReminderPolicy(
            medicationId = medId,
            draft = MedicationAdminService.PolicyDraft(
                policyType = PolicyType.DAILY,
                startDate = today.toString(),
                times = listOf(MedicationAdminService.TimeDraft(time, dose, "早间"))
            )
        )
        tracking.reconcileSchedule(medId, today, today.plusDays(13))
    }

    // ================================================================
    // 主线
    // ================================================================

    @Test
    fun `改剂量后 已排好的未来槽位 剂量必须跟着变`() = runTest {
        val medId = newMed()
        val before = openSlots(medId)
        assertThat(before).isNotEmpty()
        assertThat(before.all { it.doseAmount == 1000 }).isTrue()

        setDose(medId, "08:00", 2f)

        val after = openSlots(medId)
        // ★ 槽位**数量**不变（key 是日历，命中即保留）
        assertThat(after).hasSize(before.size)
        // ★ 但剂量必须是新值 2 片
        assertThat(after.all { it.doseAmount == 2000 }).isTrue()
    }

    /** 这个测试的意义在下一条：光看"剂量对了"不够，得看**打卡真的按新剂量扣**。 */
    @Test
    fun `改剂量后 打卡按新剂量扣库存`() = runTest {
        val medId = newMed()
        val slot = openSlots(medId).first()

        setDose(medId, "08:00", 2f)
        val slotAfter = openSlots(medId).first { it.id == slot.id }

        tracking.takeDose(slotId = slotAfter.id)
        // 20 - 2 = 18。若槽位剂量被冻结在 1000，这里会是 19
        db.assertLedgerBalance(medId, 18f)
    }

    @Test
    fun `连改两次剂量 槽位跟到最后一次`() = runTest {
        val medId = newMed()
        setDose(medId, "08:00", 2f)
        setDose(medId, "08:00", 0.5f)
        assertThat(openSlots(medId).all { it.doseAmount == 500 }).isTrue()
    }

    // ================================================================
    // 不得因此破坏的既有语义
    // ================================================================

    /**
     * 已完成 / 已跳过的槽位是**既成事实**，改剂量绝不许动它们 ——
     * 否则 `dose_records` 就对不上槽位了。
     */
    @Test
    fun `改剂量不得改动已产生结论的槽位`() = runTest {
        val medId = newMed()
        val done = openSlots(medId).first()
        tracking.takeDose(slotId = done.id)
        assertThat(db.doseSlotDao().getSlotById(done.id)!!.doseAmount).isEqualTo(1000)

        setDose(medId, "08:00", 3f)

        // 已完成的槽位剂量仍是 1 片（它对应的历史事实就是 1 片）
        assertThat(db.doseSlotDao().getSlotById(done.id)!!.doseAmount).isEqualTo(1000)
        // 其余待服槽位变成 3 片
        assertThat(openSlots(medId).filter { it.id != done.id }.all { it.doseAmount == 3000 }).isTrue()
    }

    @Test
    fun `改剂量后重新对账 槽位 id 与数量都不变（幂等 不产生重复排班）`() = runTest {
        val medId = newMed()
        setDose(medId, "08:00", 2f)
        val idsOnce = openSlots(medId).map { it.id }.sorted()

        repeat(3) { tracking.reconcileSchedule(medId, today, today.plusDays(13)) }

        val idsAgain = openSlots(medId).map { it.id }.sorted()
        assertThat(idsAgain).isEqualTo(idsOnce)
        assertThat(db.doseSlotDao().getAllSlots().count { it.medicationId == medId })
            .isEqualTo(idsOnce.size)
    }

    @Test
    fun `未改剂量时对账不得改动任何槽位（回归）`() = runTest {
        val medId = newMed()
        val before = db.doseSlotDao().getAllSlots()
            .filter { it.medicationId == medId }
            .sortedBy { it.id }
        repeat(3) { tracking.reconcileSchedule(medId, today, today.plusDays(13)) }
        val after = db.doseSlotDao().getAllSlots()
            .filter { it.medicationId == medId }
            .sortedBy { it.id }
        assertThat(after).isEqualTo(before)
    }

    /** 剂量单位是整数毫单位，0.5 片必须存成 500 而不是 0 */
    @Test
    fun `小数剂量按毫单位精确存储`() = runTest {
        val medId = newMed()
        setDose(medId, "08:00", 0.5f)
        val slot = openSlots(medId).first()
        assertThat(slot.doseAmount).isEqualTo(Dose.of(0.5f).milli)
        assertThat(slot.doseAmount).isEqualTo(500)
    }
}
