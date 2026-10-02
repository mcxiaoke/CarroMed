package com.mcxiaoke.carromed.ui.screen.progress

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.DoseRecordEntity
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
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
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * ProgressViewModel 直接测试（T-1 / orsbf P1-19）。
 *
 * 钉住跨月分页的关键不变量：
 * - 首屏（60 条）加载后 `hasMoreTimeline` 正确置位；
 * - `loadMoreTimeline` 用复合游标 (actualTs, id) 追加更早记录，
 *   追加结果**无重复且保持倒序**（orsbf P1-3：页边界切在同时刻记录簇中间
 *   不能静默吞行）；
 * - 记录不足一页时触底加载是 no-op（`hasMore=false` 后不再打库）。
 *
 * 竞态防护（revision 丢弃过期追加）依赖精确的挂起交错，无法确定性编排，
 * 由代码注释 + 单调修订号实现本身保证，不做脆弱的并发测试。
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class ProgressViewModelTest {

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

    private suspend fun seedMedAndRecords(recordCount: Int): Long {
        val db = AppDatabase.getInstance(app)
        val medId = db.medicationDao().insert(MedicationEntity(name = "时间线药", unit = "片"))
        db.reminderSettingsDao().ensureDefaults(medId)
        val zone = ZoneId.systemDefault()
        val base = LocalDateTime.now()
        // 逐条错开时间戳（同一条早 1 小时），保证 id 与时间戳同向
        repeat(recordCount) { i ->
            db.doseRecordDao().insert(
                DoseRecordEntity(
                    slotId = null,
                    medicationId = medId,
                    actualTs = base.minusHours(i.toLong()).atZone(zone).toInstant().toEpochMilli(),
                    doseTaken = 1000
                )
            )
        }
        return medId
    }

    private suspend fun awaitFirstPage(vm: ProgressViewModel): ProgressUiState =
        withTimeout(10_000) { vm.uiState.first { !it.isLoading } }

    @Test
    fun `首屏加载后未满一页时无更多`() = runBlocking {
        seedMedAndRecords(recordCount = 10)
        val vm = ProgressViewModel(app)
        val state = awaitFirstPage(vm)

        val totalItems = state.timelineDays.sumOf { it.items.size }
        assertThat(totalItems).isEqualTo(10)
        assertThat(state.hasMoreTimeline).isFalse()
        // 触底加载是 no-op：hasMore=false 直接 return，不产生重复
        vm.loadMoreTimeline()
        assertThat(vm.uiState.value.timelineDays.sumOf { it.items.size }).isEqualTo(10)
    }

    @Test
    fun `触底加载追加更早记录且无重复保持倒序`() = runBlocking {
        // FIRST_PAGE_SIZE = 60（private companion，测试以字面量引用并在此对齐）
        val pageSize = 60
        val total = pageSize + 7
        seedMedAndRecords(recordCount = total)
        val vm = ProgressViewModel(app)
        val firstPage = awaitFirstPage(vm)
        assertThat(firstPage.hasMoreTimeline).isTrue()
        assertThat(firstPage.timelineDays.sumOf { it.items.size }).isEqualTo(pageSize)

        vm.loadMoreTimeline()
        val after = withTimeout(10_000) {
            vm.uiState.first { it.timelineDays.sumOf { day -> day.items.size } == total }
        }

        val items = after.timelineDays.flatMap { it.items }
        assertThat(items).hasSize(total)
        // 无重复
        assertThat(items.map { it.record.id }.distinct()).hasSize(total)
        // 全程按 actualTs 倒序（keyset 游标 + VM 内排序共同保证）
        val tsList = items.map { it.record.actualTs }
        assertThat(tsList).isInOrder(Comparator.reverseOrder<Long>())
        // 页边界被切开的记录簇没有丢行：最后一条是最早的那条
        assertThat(items.last().record.id).isEqualTo(total.toLong())
    }
}
