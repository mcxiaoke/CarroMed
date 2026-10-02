package com.mcxiaoke.carromed.ui.screen.manual

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.R
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.domain.model.Dose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * ManualDoseViewModel 直接测试（T-1 / orsbf P1-19）。
 *
 * 补录页是「数据安全路径」的用户入口：剂量校验、时间窗（上界防未来、下界防
 * 超出补录窗）与扣减开关都在这里做最后一道把关。服务层不变量已有
 * DoseTrackingServiceTest 钉住，本文件钉的是 **VM 的守卫顺序与落库效果**。
 *
 * VM 走 `AppDatabase.getInstance` 单例（不能注内存库——加构造参数会踩
 * AGENTS §四 的反射坑），Robolectric 每个测试方法重建沙箱文件系统，
 * 配合 `resetForTest` 每条测试都从干净的文件库开始。
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class ManualDoseViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

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

    private suspend fun seedTrackedMed(name: String): Long {
        val db = AppDatabase.getInstance(context.applicationContext as Application)
        val id = db.medicationDao().insert(
            MedicationEntity(name = name, unit = "片", isStockTracked = true)
        )
        db.reminderSettingsDao().ensureDefaults(id)
        return id
    }

    private suspend fun awaitReady(vm: ManualDoseViewModel) {
        withTimeout(10_000) { vm.uiState.first { it.medications.isNotEmpty() } }
    }

    /** VM 的落库在独立协程上收尾，轮询等待条件成立（真实时间，10s 兜底） */
    private suspend fun awaitUntil(timeoutMs: Long = 10_000, cond: suspend () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!cond()) {
            if (System.currentTimeMillis() - start > timeoutMs) error("awaitUntil timed out")
            kotlinx.coroutines.delay(50)
        }
    }

    @Test
    fun `非法剂量被拒绝且不落库`() = kotlinx.coroutines.runBlocking {
        val medId = seedTrackedMed("补录药")
        val vm = ManualDoseViewModel(context.applicationContext as Application, medId)
        awaitReady(vm)
        vm.onDoseAmountChange("abc")

        var called = false
        vm.save { called = true }

        assertThat(vm.uiState.value.error)
            .isEqualTo(context.getString(R.string.man_error_invalid_dose))
        assertThat(called).isFalse()
        assertThat(AppDatabase.getInstance(context).doseRecordDao().getAllRecordsBySlotId(0)).isEmpty()
    }

    @Test
    fun `未来时刻被拒绝`() = kotlinx.coroutines.runBlocking {
        val medId = seedTrackedMed("补录药")
        val vm = ManualDoseViewModel(context.applicationContext as Application, medId)
        awaitReady(vm)
        vm.onActualDateTimeChange(LocalDateTime.now().plusMinutes(30))

        vm.save { }

        assertThat(vm.uiState.value.error)
            .isEqualTo(context.getString(R.string.man_error_future_time))
    }

    @Test
    fun `超出补录时间窗被拒绝（与领域层同源 7 天下界）`() = kotlinx.coroutines.runBlocking {
        val medId = seedTrackedMed("补录药")
        val vm = ManualDoseViewModel(context.applicationContext as Application, medId)
        awaitReady(vm)
        // 上界 7 天：8 天前必须被拒
        vm.onActualDateTimeChange(LocalDateTime.now().minusDays(8))

        vm.save { }

        assertThat(vm.uiState.value.error)
            .isEqualTo(
                context.getString(
                    R.string.man_error_backfill_days,
                    com.mcxiaoke.carromed.core.domain.service.MANUAL_DOSE_BACKFILL_DAYS
                )
            )
    }

    @Test
    fun `保存成功落事实行 扣减开关真正透传到台账`() = kotlinx.coroutines.runBlocking {
        val medId = seedTrackedMed("补录药")
        val vm = ManualDoseViewModel(context.applicationContext as Application, medId)
        awaitReady(vm)
        vm.onDoseAmountChange("2")
        vm.onActualDateTimeChange(LocalDateTime.now().minusMinutes(10))
        vm.onNoteChange("外出补服")
        vm.onDeductStockChange(true)

        var called = false
        vm.save { called = true }

        assertThat(vm.uiState.value.error).isNull()
        val db = AppDatabase.getInstance(context)
        awaitUntil { db.doseRecordDao().getRecordsForMedication(medId).isNotEmpty() }
        // onSuccess 在保存协程收尾时回调，等它落地再断言
        awaitUntil { called }
        assertThat(called).isTrue()
        val record = db.doseRecordDao().getRecordsForMedication(medId).single()
        assertThat(record.medicationId).isEqualTo(medId)
        assertThat(record.doseTaken).isEqualTo(Dose.of(2f).milli)
        assertThat(record.note).isEqualTo("外出补服")
        // 台账出现一条负流水（MILLI 刻度）
        assertThat(db.inventoryTransactionDao().getSumOfChanges(medId))
            .isEqualTo(-Dose.of(2f).milli)
    }

    @Test
    fun `扣减开关关闭时保存成功但不写扣减流水`() = kotlinx.coroutines.runBlocking {
        val medId = seedTrackedMed("补录药")
        val vm = ManualDoseViewModel(context.applicationContext as Application, medId)
        awaitReady(vm)
        vm.onActualDateTimeChange(LocalDateTime.now().minusMinutes(5))
        vm.onDeductStockChange(false)

        vm.save { }

        assertThat(vm.uiState.value.error).isNull()
        val db = AppDatabase.getInstance(context)
        awaitUntil { db.doseRecordDao().getRecordsForMedication(medId).isNotEmpty() }
        // 事实行落了，但台账没有任何扣减流水
        assertThat(db.inventoryTransactionDao().getSumOfChanges(medId)).isNull()
    }
}
