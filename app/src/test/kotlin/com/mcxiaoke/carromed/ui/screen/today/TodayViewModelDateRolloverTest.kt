package com.mcxiaoke.carromed.ui.screen.today

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.time.CurrentDateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class TodayViewModelDateRolloverTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val app: Application get() = context.applicationContext as Application

    private val day1 = LocalDate.of(2026, 10, 7)
    private val day2 = LocalDate.of(2026, 10, 8)
    private val pastDay = LocalDate.of(2026, 10, 5)

    @Before
    fun setup() {
        Dispatchers.setMain(mainDispatcher)
        AppDatabase.resetForTest()
        CurrentDateHolder.setTodayForTest(day1)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        CurrentDateHolder.resetForTest()
        AppDatabase.resetForTest()
    }

    @Test
    fun `TodayViewModel 跨天时通过 onResume 自动对齐到最新日期`() = runBlocking {
        val vm = TodayViewModel(app)
        val job = launch(mainDispatcher) { vm.uiState.collect {} }
        withTimeout(5_000) { vm.uiState.first { !it.isLoading } }
        assertThat(vm.uiState.value.selectedDate).isEqualTo(day1)
        assertThat(vm.uiState.value.today).isEqualTo(day1)

        // 模拟后台跨天到次日
        CurrentDateHolder.setTodayForTest(day2)
        vm.onResume()

        val updated = withTimeout(5_000) {
            vm.uiState.first { it.selectedDate == day2 && it.today == day2 }
        }
        assertThat(updated.selectedDate).isEqualTo(day2)
        assertThat(updated.today).isEqualTo(day2)
        job.cancel()
    }

    @Test
    fun `TodayViewModel 同一天内查看历史再 onResume 不会被重置`() = runBlocking {
        CurrentDateHolder.setTodayForTest(day2)
        val vm = TodayViewModel(app)
        val job = launch(mainDispatcher) { vm.uiState.collect {} }
        withTimeout(5_000) { vm.uiState.first { !it.isLoading } }

        // 用户选择查看前几天的历史
        vm.selectDate(pastDay)
        val pastState = withTimeout(5_000) { vm.uiState.first { it.selectedDate == pastDay } }
        assertThat(pastState.selectedDate).isEqualTo(pastDay)

        // 同一天内切回前台（resume）
        vm.onResume()
        assertThat(vm.uiState.value.selectedDate).isEqualTo(pastDay)
        job.cancel()
    }

    @Test
    fun `TodayViewModel 同一天内预览未来排班再 onResume 不会被弹回今天`() = runBlocking {
        // 回归守卫（2026-10-09）：`onResume` 曾带 `current > realToday` 判据，
        // 用户预览明天/后天时该判据恒为真 ⇒ 每次回前台都把整页列表换成今天的清单。
        // 判据只能落在「真实日期是否翻面」这个系统事实上，不能落在用户的选择上。
        // 复现与日志实证见 docs/DIAGNOSIS-AUTO-CLICK-LAYOUT-JUMP-20261009.md §2.1。
        val vm = TodayViewModel(app)
        val job = launch(mainDispatcher) { vm.uiState.collect {} }
        withTimeout(5_000) { vm.uiState.first { !it.isLoading } }
        assertThat(vm.uiState.value.selectedDate).isEqualTo(day1)

        // 用户翻到明天预览排班（未来日只读，但可以被选中）
        val futureDay = day1.plusDays(1)
        vm.selectDate(futureDay)
        withTimeout(5_000) { vm.uiState.first { it.selectedDate == futureDay } }

        // 同一天内切回前台（resume）：真实日期没翻面，选中日必须原样保留。
        //
        // ⚠️ 不能在 `onResume()` 之后立刻读 `uiState.value` 断言：主状态流里那条按日期的
        // 槽位查询是**异步**冷流，改选中的日期后要等它返回才会重算 —— 立刻读到的永远是
        // 改写前的旧值，于是无论有没有这个缺陷测试都会"通过"（假绿）。
        // 正确做法是留出一个窗口等状态流自己重算：若真被弹回今天，它必然发射新的选中日。
        vm.onResume()
        val flippedBack = withTimeoutOrNull(1_500) {
            vm.uiState.first { it.selectedDate != futureDay }
        }
        assertThat(flippedBack).isNull()
        assertThat(vm.uiState.value.selectedDate).isEqualTo(futureDay)
        assertThat(vm.uiState.value.today).isEqualTo(day1)
        job.cancel()
    }

    @Test
    fun `TodayViewModel 前一天查看历史跨天后 onResume 自动归位到新的今天`() = runBlocking {
        val vm = TodayViewModel(app)
        val job = launch(mainDispatcher) { vm.uiState.collect {} }
        withTimeout(5_000) { vm.uiState.first { !it.isLoading } }

        // 昨天在看历史
        vm.selectDate(pastDay)
        withTimeout(5_000) { vm.uiState.first { it.selectedDate == pastDay } }

        // 跨天后切回前台（resume）
        CurrentDateHolder.setTodayForTest(day2)
        vm.onResume()

        val updated = withTimeout(5_000) {
            vm.uiState.first { it.selectedDate == day2 && it.today == day2 }
        }
        assertThat(updated.selectedDate).isEqualTo(day2)
        assertThat(updated.today).isEqualTo(day2)
        job.cancel()
    }

    @Test
    fun `TodayViewModel 前台跨天时通过响应式流自动推进到新的今天`() = runBlocking {
        val vm = TodayViewModel(app)
        val job = launch(mainDispatcher) { vm.uiState.collect {} }
        withTimeout(5_000) { vm.uiState.first { !it.isLoading } }
        assertThat(vm.uiState.value.selectedDate).isEqualTo(day1)

        // 前台自然跨天，未发生 onResume
        CurrentDateHolder.setTodayForTest(day2)

        val updated = withTimeout(5_000) {
            vm.uiState.first { it.selectedDate == day2 && it.today == day2 }
        }
        assertThat(updated.selectedDate).isEqualTo(day2)
        assertThat(updated.today).isEqualTo(day2)
        job.cancel()
    }
}
