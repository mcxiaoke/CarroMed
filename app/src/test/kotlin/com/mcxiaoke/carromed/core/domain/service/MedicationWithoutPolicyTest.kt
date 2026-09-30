package com.mcxiaoke.carromed.core.domain.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.model.PolicyType
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import com.mcxiaoke.carromed.core.domain.service.MedicationAdminService.TimeDraft
import com.mcxiaoke.carromed.core.testing.assertLedgerBalance
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * 「有药」与「有计划」是**两件事**（2026-09-29 UX 改造第 4 步）。
 *
 * ## 守的不变量
 *
 * 新建药品页不再强制配提醒，所以「一个药存在、但没有 active policy」
 * 从此是**合法且常见**的中间状态。下面逐条守它成立时下游不出错：
 *
 * 1. 药箱 / 详情页读得到这味药；
 * 2. `reminder_settings` 仍有**恰好一行**（A2 不变量）——
 *    行为设置（推迟、暂停、重要提醒）与"有没有排班"是**两回事**；
 * 3. 用户之后从「提醒设置」配了计划，一切回到正常；
 * 4. 全程没有任何闹钟被排出去。
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class MedicationWithoutPolicyTest {

    private lateinit var db: AppDatabase
    private lateinit var admin: MedicationAdminService
    private lateinit var tracking: DoseTrackingService

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
    fun teardown() {
        if (::db.isInitialized) db.close()
    }

    /** 模拟"新建页只填档案、不碰提醒"的落库结果。 */
    private suspend fun newMedWithoutPolicy(name: String = "新药"): Long =
        admin.saveProfile(MedicationAdminService.ProfileDraft(name = name, unit = "片"))

    @Test
    fun `只建档案时 药存在但没有 active policy`() = runTest {
        val medId = newMedWithoutPolicy()

        assertThat(db.medicationDao().getMedicationById(medId)).isNotNull()
        assertThat(db.schedulePolicyDao().getActivePolicyForMedication(medId)).isNull()
        // 药箱列表必须能显示它 —— 不可见等于"药丢了"
        assertThat(db.medicationDao().getAllMedications().map { it.id }).contains(medId)
    }

    /**
     * ⭐ A2 不变量不能因为"没配计划"而破。
     *
     * `reminder_settings` 存的是**行为设置**（推迟分钟、暂停、重要提醒），
     * 不是排班。所以没计划时它照样该有一行，行为设置才有地方可写。
     * 若这里破成 0 行，用户第一次改「推迟时长」会因 UPDATE 命中 0 行而**静默失败**。
     */
    @Test
    fun `没有计划时 reminder_settings 仍有恰好一行`() = runTest {
        val medId = newMedWithoutPolicy()

        val rows = db.reminderSettingsDao().getAll()
        assertThat(rows.count { it.medicationId == medId }).isEqualTo(1)
    }

    @Test
    fun `没有计划时 不会排任何闹钟`() = runTest {
        val medId = newMedWithoutPolicy()
        tracking.reconcileSchedule(medId)

        // 投影为空 ⇒ 没有槽位 ⇒ 闹钟无从谈起
        assertThat(db.doseSlotDao().getAllSlots().count { it.medicationId == medId })
            .isEqualTo(0)
    }

    @Test
    fun `之后补上计划 即恢复正常排班`() = runTest {
        val medId = newMedWithoutPolicy()

        admin.saveReminderPolicy(
            medicationId = medId,
            draft = MedicationAdminService.PolicyDraft(
                policyType = PolicyType.DAILY,
                startDate = "2026-09-29",
                times = listOf(TimeDraft("08:00", 1f, "早"))
            )
        )
        tracking.reconcileSchedule(medId)

        val policy = db.schedulePolicyDao().getActivePolicyForMedication(medId)
        assertThat(policy).isNotNull()
        assertThat(db.schedulePolicyDao().getTimesForPolicy(policy!!.id).map { it.timeOfDay })
            .containsExactly("08:00")
    }

    @Test
    fun `PRN 药 有档案有提醒设置但没有排班`() = runTest {
        // 「没有计划」有两种合法形态：完全没配，和显式选了按需服用。
        // 两者在数据层都必须站得住。
        val medId = newMedWithoutPolicy("临时药")
        admin.saveReminderPolicy(
            medicationId = medId,
            draft = MedicationAdminService.PolicyDraft(policyType = PolicyType.PRN)
        )
        tracking.reconcileSchedule(medId)

        val policy = db.schedulePolicyDao().getActivePolicyForMedication(medId)
        assertThat(policy).isNotNull()
        assertThat(policy!!.policyType).isEqualTo(PolicyType.PRN)
        // PRN 不产生槽位，但**政策行仍然存在**（它是"用户明确选了按需"的凭据）
        assertThat(db.doseSlotDao().getAllSlots().count { it.medicationId == medId })
            .isEqualTo(0)
    }

    @Test
    fun `建档时填了初始库存 账实仍然守恒`() = runTest {
        val medId = newMedWithoutPolicy()
        tracking.setStockTracking(medId, enabled = true, initialStock = 30f)
        db.assertLedgerBalance(medId, 30f)
    }

    @Test
    fun `有计划的药删除计划后 状态干净回退为无计划且未来待决槽位被清空`() = runTest {
        val medId = newMedWithoutPolicy("抗生素")
        admin.saveReminderPolicy(
            medicationId = medId,
            draft = MedicationAdminService.PolicyDraft(
                policyType = PolicyType.DAILY,
                startDate = "2026-09-29",
                times = listOf(TimeDraft("08:00", 1f, "早"), TimeDraft("20:00", 1f, "晚"))
            )
        )
        tracking.reconcileSchedule(medId)

        // 验证初始状态：有活跃计划，有生成的开放槽位
        val activePolicy = db.schedulePolicyDao().getActivePolicyForMedication(medId)
        assertThat(activePolicy).isNotNull()
        val slotsBefore = db.doseSlotDao().getAllSlots().filter { it.medicationId == medId }
        assertThat(slotsBefore).isNotEmpty()

        // 模拟打卡一次：记录一条 COMPLETED 事实
        val firstSlot = slotsBefore.first()
        tracking.takeDose(firstSlot.id)
        val recordsBefore = db.doseRecordDao().getRecordsForMedication(medId)
        assertThat(recordsBefore).hasSize(1)

        // 执行删除提醒计划
        admin.deleteReminderPolicy(medId)
        tracking.reconcileSchedule(medId)

        // 守不变量：
        // 1. active policy 为 null
        assertThat(db.schedulePolicyDao().getActivePolicyForMedication(medId)).isNull()
        // 2. 策略时点被清空
        assertThat(db.schedulePolicyDao().getTimesForPolicy(activePolicy!!.id)).isEmpty()
        // 3. reminder_settings 依然恰好有一行（A2 不变量）
        assertThat(db.reminderSettingsDao().getAll().count { it.medicationId == medId }).isEqualTo(1)
        // 4. 历史打卡事实完整无损
        assertThat(db.doseRecordDao().getRecordsForMedication(medId)).hasSize(1)
        // 5. 未来所有开放待决槽位（PENDING/SNOOZED）被彻底清空，仅保留已打卡事实槽位
        val openSlotsAfter = db.doseSlotDao().getAllSlots()
            .filter { it.medicationId == medId && (it.status == SlotStatus.PENDING || it.status == SlotStatus.SNOOZED) }
        assertThat(openSlotsAfter).isEmpty()
    }
}
