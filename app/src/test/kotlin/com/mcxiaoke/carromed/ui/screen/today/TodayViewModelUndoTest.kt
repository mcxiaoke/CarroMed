package com.mcxiaoke.carromed.ui.screen.today

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.data.AppDatabase
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

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class TodayViewModelUndoTest {

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

    @Test
    fun `TodayViewModel 完成提示音默认值为 chime`() = runBlocking {
        val vm = TodayViewModel(app)
        assertThat(vm.uiState.value.completionSound).isEqualTo("chime")
    }

    @Test
    fun `TodayViewModel takeDose 后 undoDose 槽位能成功回退为 PENDING`() = runBlocking {
        val medId = seedMed("测试药")
        val db = AppDatabase.getInstance(app)
        val today = LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val slotId = db.doseSlotDao().insert(
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

        val vm = TodayViewModel(app)
        withTimeout(10_000) { vm.uiState.first { !it.isLoading } }

        vm.takeDose(slotId)
        val slotAfterTake = withTimeout(10_000) {
            db.doseSlotDao().observeSlotById(slotId).first { it?.status == SlotStatus.COMPLETED }
        }
        assertThat(slotAfterTake?.status).isEqualTo(SlotStatus.COMPLETED)

        vm.undoDose(slotId)
        val slotAfterUndo = withTimeout(10_000) {
            db.doseSlotDao().observeSlotById(slotId).first { it?.status == SlotStatus.PENDING }
        }
        assertThat(slotAfterUndo?.status).isEqualTo(SlotStatus.PENDING)
    }
}
