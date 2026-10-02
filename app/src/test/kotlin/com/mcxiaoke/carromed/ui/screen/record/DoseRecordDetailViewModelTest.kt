package com.mcxiaoke.carromed.ui.screen.record

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.DoseRecordEntity
import com.mcxiaoke.carromed.core.data.entity.DoseSlotEntity
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import kotlinx.coroutines.Dispatchers
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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * DoseRecordDetailViewModel 直接测试（T-1 / orsbf P1-19）。
 *
 * 钉住两类载入路径的关键行为：
 * - **槽位是主数据源**（observeSlotById 流）：对账把槽位结算、别的入口打卡，
 *   页面形态都要跟着变；
 * - **isActionable 是纯数据判据**（SlotActionPolicy 按日期算，未来槽位不可操作）
 *   —— 这正是 L-12 修复后"状态类不读挂钟"的可测性前提；
 * - 手动补录记录（slot_id == null）走一次性读取路径，剂量/备注正确灌入。
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class DoseRecordDetailViewModelTest {

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

    private suspend fun awaitReady(vm: DoseRecordDetailViewModel): DoseEntryUiState =
        withTimeout(10_000) { vm.uiState.first { !it.isLoading } }

    @Test
    fun `手动补录记录走一次性读取 剂量与备注正确灌入`() = runBlocking {
        val medId = seedMed("补录药")
        val db = AppDatabase.getInstance(app)
        val recordId = db.doseRecordDao().insert(
            DoseRecordEntity(
                slotId = null,
                medicationId = medId,
                actualTs = LocalDateTime.now().minusHours(2).atZone(ZoneId.systemDefault())
                    .toInstant().toEpochMilli(),
                doseTaken = 1500,
                note = "外出补服"
            )
        )

        val vm = DoseRecordDetailViewModel(app)
        vm.load(slotId = null, recordId = recordId)
        val state = awaitReady(vm)

        assertThat(state.notFound).isFalse()
        assertThat(state.record!!.id).isEqualTo(recordId)
        assertThat(state.medication!!.id).isEqualTo(medId)
        assertThat(state.doseInput).isEqualTo("1.50")
        assertThat(state.noteInput).isEqualTo("外出补服")
    }

    @Test
    fun `不存在的记录 notFound 而不是无限加载`() = runBlocking {
        val vm = DoseRecordDetailViewModel(app)
        vm.load(slotId = null, recordId = 9999L)

        val state = awaitReady(vm)

        assertThat(state.notFound).isTrue()
    }

    @Test
    fun `未来槽位不可操作 历史当日槽位可操作`() = runBlocking {
        val medId = seedMed("槽位药")
        val db = AppDatabase.getInstance(app)
        val today = LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val future = LocalDate.now().plusDays(3)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val futureSlotId = db.doseSlotDao().insert(
            DoseSlotEntity(
                medicationId = medId,
                policyId = 1L,
                scheduledDate = future,
                scheduledTime = "08:00",
                scheduledTs = System.currentTimeMillis() + 3L * 24 * 3600_000,
                doseAmount = 1000,
                status = SlotStatus.PENDING
            )
        )
        val todaySlotId = db.doseSlotDao().insert(
            DoseSlotEntity(
                medicationId = medId,
                policyId = 1L,
                scheduledDate = today,
                scheduledTime = "08:00",
                scheduledTs = System.currentTimeMillis(),
                doseAmount = 1000,
                status = SlotStatus.PENDING
            )
        )

        val vm = DoseRecordDetailViewModel(app)
        vm.load(slotId = futureSlotId, recordId = null)
        withTimeout(10_000) { vm.uiState.first { it.slot?.id == futureSlotId && !it.isLoading } }
        assertThat(vm.uiState.value.isActionable).isFalse()

        // 二次 load：等新槽位真正到达，避免拿到上一次载入的旧状态
        vm.load(slotId = todaySlotId, recordId = null)
        val todayState = withTimeout(10_000) {
            vm.uiState.first { it.slot?.id == todaySlotId && !it.isLoading }
        }
        assertThat(todayState.isActionable).isTrue()
        assertThat(todayState.slot!!.id).isEqualTo(todaySlotId)
    }
}
