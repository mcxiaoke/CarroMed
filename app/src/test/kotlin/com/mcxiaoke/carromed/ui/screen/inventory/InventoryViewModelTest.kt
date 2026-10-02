package com.mcxiaoke.carromed.ui.screen.inventory

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
 * InventoryViewModel 直接测试（T-1 / orsbf P1-19）。
 *
 * 钉住的回归锚（全部来自此前的真实缺陷）：
 * - saveSettings 对非法输入**报错**而不是静默回退旧值还提示"已保存"（M7-3）；
 * - 预警线唯一写入口是列级 `updateMinStockAlert`（§一-3：整行覆盖会抹掉
 *   表单没暴露的字段）；
 * - 草稿 dirty 保护：用户编辑期间的后台刷新不许吃掉输入（ocsbf P1-1）；
 * - 盘点校准走流水而非直接改账面（台账不变量 I2）。
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class InventoryViewModelTest {

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

    private suspend fun seedMed(name: String, stock: Float = 20f): Long {
        val db = AppDatabase.getInstance(app)
        val id = db.medicationDao().insert(
            MedicationEntity(name = name, unit = "片", isStockTracked = true)
        )
        db.reminderSettingsDao().ensureDefaults(id)
        // 期初余额：使账面 = stock（盘点/预警判定都依赖它）
        db.inventoryTransactionDao().insert(
            com.mcxiaoke.carromed.core.data.entity.InventoryTransactionEntity(
                medicationId = id,
                recordId = null,
                changeAmount = Dose.of(stock).milli,
                balanceAfter = Dose.of(stock).milli,
                txType = TransactionType.REFILL,
                note = null
            )
        )
        return id
    }

    private suspend fun awaitReady(vm: InventoryViewModel) {
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
    fun `加载后状态来自真实库值`() = runBlocking {
        val medId = seedMed("库存药", stock = 30f)
        val vm = InventoryViewModel(app, medId)
        awaitReady(vm)

        assertThat(vm.uiState.value.currentStock).isEqualTo(30f)
        assertThat(vm.uiState.value.isTracked).isTrue()
    }

    @Test
    fun `saveSettings 非法预警线报错且不落库`() = runBlocking {
        val medId = seedMed("库存药")
        val vm = InventoryViewModel(app, medId)
        awaitReady(vm)
        vm.onMinStockAlertChange("abc")

        vm.saveSettings()

        assertThat(vm.uiState.value.error)
            .isEqualTo(context.getString(R.string.inv_err_alert_invalid))
        val med = AppDatabase.getInstance(app).medicationDao().getMedicationById(medId)!!
        // 库值仍是默认（种子未设置预警线），非法输入没有产生任何写入
        assertThat(med.minStockAlert).isEqualTo(0)
    }

    @Test
    fun `saveSettings 合法值走列级更新 落库且不整行覆盖`() = runBlocking {
        val medId = seedMed("库存药")
        val vm = InventoryViewModel(app, medId)
        awaitReady(vm)
        vm.onMinStockAlertChange("5")
        vm.onExpiryDateChange("2030-06-30")

        vm.saveSettings()
        awaitUntil {
            val med = AppDatabase.getInstance(app).medicationDao().getMedicationById(medId)!!
            med.minStockAlert == Dose.of(5f).milli && med.expiryDate == "2030-06-30"
        }

        val med = AppDatabase.getInstance(app).medicationDao().getMedicationById(medId)!!
        assertThat(med.minStockAlert).isEqualTo(Dose.of(5f).milli)
        assertThat(med.expiryDate).isEqualTo("2030-06-30")
        assertThat(vm.uiState.value.error).isNull()
    }

    @Test
    fun `用户编辑中的草稿不被后台刷新覆盖`() = runBlocking {
        val medId = seedMed("库存药", stock = 30f)
        val vm = InventoryViewModel(app, medId)
        awaitReady(vm)
        // 用户在有效期输入框敲了一半
        vm.onExpiryDateChange("2030-01-01")
        assertThat(vm.isDirty()).isTrue()

        // 模拟探针触发的后台重载（保存成功/返回都会触发 load）
        vm.load()
        awaitReady(vm)

        // ocsbf P1-1：dirty 草稿归用户所有，后台刷新不许吃掉输入
        assertThat(vm.uiState.value.expiryDate).isEqualTo("2030-01-01")
    }

    @Test
    fun `盘点校准走流水 账面拉回实物真实值`() = runBlocking {
        val medId = seedMed("库存药", stock = 30f)
        val vm = InventoryViewModel(app, medId)
        awaitReady(vm)
        vm.onCalibrateInputChange("12")

        vm.calibrate(note = "清点")

        val db = AppDatabase.getInstance(app)
        awaitUntil { db.inventoryTransactionDao().getSumOfChanges(medId) == Dose.of(12f).milli }
        assertThat(db.inventoryTransactionDao().getSumOfChanges(medId))
            .isEqualTo(Dose.of(12f).milli)
        val txs = db.inventoryTransactionDao().getTransactionsForMedication(medId)
        // 期初 + 一条校准流水（差额 -8）
        assertThat(txs).hasSize(2)
        assertThat(txs.first().txType).isEqualTo(TransactionType.CALIBRATION_ADJUST)
    }

    @Test
    fun `盘点输入非法时报错不落库`() = runBlocking {
        val medId = seedMed("库存药", stock = 30f)
        val vm = InventoryViewModel(app, medId)
        awaitReady(vm)
        vm.onCalibrateInputChange("abc")

        vm.calibrate(note = null)

        assertThat(vm.uiState.value.error)
            .isEqualTo(context.getString(R.string.inv_err_calibrate_invalid))
        val db = AppDatabase.getInstance(app)
        assertThat(db.inventoryTransactionDao().getTransactionsForMedication(medId)).hasSize(1)
    }
}
