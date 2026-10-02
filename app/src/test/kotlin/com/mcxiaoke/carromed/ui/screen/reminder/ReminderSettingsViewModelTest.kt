package com.mcxiaoke.carromed.ui.screen.reminder

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.R
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.domain.service.MedicationAdminService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * ReminderSettingsViewModel 直接测试（T-1 / orsbf P1-19）。
 *
 * 钉住的回归锚：
 * - `snoozeMinutes == 0` 是「跟随全局」哨兵，load 后**原样保留**（M7-5：旧实现
 *   `0 → 30` 的翻译让"本药固定 30 分钟"不可表达）；
 * - save 把计划与时点、提醒行为写进对应表（计划在 schedule_policies、行为只在
 *   reminder_settings 三列，ProfileDraft 刻意不含提醒字段）；
 * - 非法剂量在保存前被拦下，不产生半份计划。
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class ReminderSettingsViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val app: Application get() = context.applicationContext as Application

    @Before
    fun setup() {
        Dispatchers.setMain(mainDispatcher)
        AppDatabase.resetForTest()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        AppDatabase.resetForTest()
    }

    private suspend fun seedMed(name: String): Long {
        val db = AppDatabase.getInstance(app)
        val id = db.medicationDao().insert(MedicationEntity(name = name, unit = "片"))
        db.reminderSettingsDao().ensureDefaults(id)
        return id
    }

    private suspend fun awaitReady(vm: ReminderSettingsViewModel) {
        withTimeout(10_000) { vm.uiState.first { !it.isLoading && it.medication != null } }
    }

    private suspend fun awaitUntil(timeoutMs: Long = 10_000, cond: suspend () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!cond()) {
            if (System.currentTimeMillis() - start > timeoutMs) error("awaitUntil timed out")
            delay(50)
        }
    }

    @Test
    fun `snoozeMinutes 为 0 时按跟随全局哨兵原样保留`() = runBlocking {
        val medId = seedMed("提醒药")
        // 预置"跟随全局"（0）。旧实现 load 时会把它翻译成 30（M7-5）。
        MedicationAdminService(AppDatabase.getInstance(app)).saveReminderBehavior(
            MedicationAdminService.ReminderBehaviorDraft(medId = medId, snoozeMinutes = 0)
        )

        val vm = ReminderSettingsViewModel(app, medId)
        awaitReady(vm)

        assertThat(vm.uiState.value.snoozeMinutes).isEqualTo(0)
    }

    @Test
    fun `save 把计划 时点与提醒行为落库`() = runBlocking {
        val medId = seedMed("提醒药")
        val vm = ReminderSettingsViewModel(app, medId)
        awaitReady(vm)
        vm.addTime()
        vm.onIntervalDaysChange(3)
        vm.onSnoozeMinutesChange(45)

        var callbackFired = false
        vm.save { _ -> callbackFired = true }
        awaitUntil {
            AppDatabase.getInstance(app).schedulePolicyDao()
                .getActivePolicyForMedication(medId) != null
        }

        val db = AppDatabase.getInstance(app)
        val policy = db.schedulePolicyDao().getActivePolicyForMedication(medId)!!
        assertThat(policy.policyType).isEqualTo(com.mcxiaoke.carromed.core.data.model.PolicyType.DAILY)
        assertThat(policy.intervalDays).isEqualTo(3)
        val times = db.schedulePolicyDao().getTimesForPolicy(policy.id)
        assertThat(times).hasSize(1)
        assertThat(times.first().doseAmount).isEqualTo(1000) // 默认剂量 1 片
        val rs = db.reminderSettingsDao().getByMedicationId(medId)!!
        assertThat(rs.snoozeMinutes).isEqualTo(45)
        // scheduleDegraded 的取值取决于环境是否授予精确闹钟权限（Robolectric 下恒降级），
        // 不是本测试的对象；只断言保存回调确实到达。
        awaitUntil { callbackFired }
        assertThat(vm.uiState.value.error).isNull()
    }

    @Test
    fun `剂量非法时 save 报错且不产生计划`() = runBlocking {
        val medId = seedMed("提醒药")
        val vm = ReminderSettingsViewModel(app, medId)
        awaitReady(vm)
        vm.addTime()
        vm.updateTime(0, doseText = "abc")

        vm.save { }

        assertThat(vm.uiState.value.error).isNotNull()
        assertThat(
            AppDatabase.getInstance(app).schedulePolicyDao().getActivePolicyForMedication(medId)
        ).isNull()
    }
}
