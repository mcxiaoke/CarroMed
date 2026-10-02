package com.mcxiaoke.carromed.ui.screen.refill

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.R
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.model.TransactionType
import com.mcxiaoke.carromed.core.domain.model.Dose
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
 * RefillViewModel 直接测试（T-1 / orsbf P1-19）。
 *
 * 钉住的回归锚：
 * - 非法数量必须报错，且**不**产生任何流水、**不**回调 onSuccess（导航丢弃
 *   错误横幅是一类 P0，见 ocsbf P0-3）；
 * - 正常入库走领域层 `refillStock`：台账 REFILL 流水 + 入库即自动开启追踪；
 * - 表单默认值：渠道不预填（orsbf P2-6），有效期不预填假日期。
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class RefillViewModelTest {

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

    private suspend fun awaitReady(vm: RefillViewModel) {
        withTimeout(10_000) { vm.uiState.first { it.medication != null } }
    }

    private suspend fun awaitUntil(timeoutMs: Long = 10_000, cond: suspend () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!cond()) {
            if (System.currentTimeMillis() - start > timeoutMs) error("awaitUntil timed out")
            delay(50)
        }
    }

    @Test
    fun `非法数量报错且不产生流水不回调 onSuccess`() = runBlocking {
        val medId = seedMed("补药对象")
        val vm = RefillViewModel(app, medId)
        awaitReady(vm)
        vm.onAddAmountChange("abc")

        var called = false
        vm.confirmRefill { called = true }

        assertThat(vm.uiState.value.error)
            .isEqualTo(context.getString(R.string.refill_error_amount_invalid))
        assertThat(called).isFalse()
        val db = AppDatabase.getInstance(app)
        assertThat(db.inventoryTransactionDao().getTransactionsForMedication(medId)).isEmpty()
    }

    @Test
    fun `有效期格式非法时报错`() = runBlocking {
        val medId = seedMed("补药对象")
        val vm = RefillViewModel(app, medId)
        awaitReady(vm)
        vm.onExpiryDateChange("2030/05/01")

        vm.confirmRefill { }

        assertThat(vm.uiState.value.error)
            .isEqualTo(context.getString(R.string.refill_error_expiry_format))
    }

    @Test
    fun `正常入库落 REFILL 流水并自动开启库存追踪`() = runBlocking {
        val medId = seedMed("补药对象")
        val vm = RefillViewModel(app, medId)
        awaitReady(vm)
        vm.onAddAmountChange("15")
        vm.onChannelChange("社区药店")
        vm.onBatchNumberChange("B20261002")

        var called = false
        vm.confirmRefill { called = true }
        awaitUntil { called }
        assertThat(called).isTrue()

        val db = AppDatabase.getInstance(app)
        val tx = db.inventoryTransactionDao().getTransactionsForMedication(medId).single()
        assertThat(tx.txType).isEqualTo(TransactionType.REFILL)
        assertThat(tx.changeAmount).isEqualTo(Dose.of(15f).milli)
        assertThat(tx.batchNumber).isEqualTo("B20261002")
        assertThat(tx.note).contains("社区药店")
        // 入库即自动开启追踪（ocsbf P0-3 路径的正常侧）
        assertThat(db.medicationDao().getMedicationById(medId)!!.isStockTracked).isTrue()
    }

    @Test
    fun `表单默认不预填渠道与有效期`() = runBlocking {
        val medId = seedMed("补药对象")
        val vm = RefillViewModel(app, medId)
        awaitReady(vm)

        // orsbf P2-6：渠道预填机构名会被用户不经确认地存进流水与备份
        assertThat(vm.uiState.value.channel).isEmpty()
        assertThat(vm.uiState.value.expiryDate).isEmpty()
    }
}
