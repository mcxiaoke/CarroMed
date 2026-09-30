package com.mcxiaoke.carromed.ui.snapshot

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.DevSampleDataSeeder
import com.mcxiaoke.carromed.ui.component.TestTags
import com.mcxiaoke.carromed.ui.screen.cabinet.CabinetScreen
import com.mcxiaoke.carromed.ui.screen.cabinet.CabinetViewModel
import com.mcxiaoke.carromed.ui.screen.detail.MedicationDetailScreen
import com.mcxiaoke.carromed.ui.screen.detail.MedicationDetailViewModel
import com.mcxiaoke.carromed.ui.screen.settings.SettingsScreen
import com.mcxiaoke.carromed.ui.screen.settings.SettingsViewModel
import com.mcxiaoke.carromed.ui.screen.stats.StatsScreen
import com.mcxiaoke.carromed.ui.screen.stats.StatsViewModel
import com.mcxiaoke.carromed.ui.screen.today.TodayScreen
import com.mcxiaoke.carromed.ui.screen.today.TodayViewModel
import com.mcxiaoke.carromed.ui.theme.CarroMedTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 整页视觉回归快照（PLAN-UI-TEST-20260929.md P2）。
 *
 * ## 为什么是「真 VM + 真库 + 手工组装」而不是启动 MainActivity
 *
 * 首版用 `createAndroidComposeRule<MainActivity>`，DB 重置与活动自带的
 * 对账协程在**同一写连接上踩事务**（`clearAllTables` 在调用线程开事务，
 * 对账在 Room 事务执行器线程上事务 —— "no current transaction"，真实踩过）。
 * 快照测试只需要**确定性渲染**，不需要导航与工厂反射路径（后者是
 * 模拟器上 `SmokeNavigationTest` 的职责），所以这里直接构造真实
 * ViewModel + 真实 Screen 组合：同一个 `AppDatabase` 单例、同一批种子数据，
 * 零并发源。
 *
 * ## 确定性
 *
 * 每条测试先清库/灌种子（`@Before` 在活动缺席时跑，无竞态）；
 * 渲染固定 `@Config(sdk = [34])`，字体渲染随 SDK 走，全员一致。
 *
 * ## 工作流
 *
 * - `./gradlew recordRoborazziDebug`：有意改 UI 后更新基线（PNG 提交进 git）
 * - `./gradlew verifyRoborazziDebug`：常规比对，diff 即红
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
@RunWith(AndroidJUnit4::class)
class ScreenSnapshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** 参数化 tag 的前缀匹配（与 SmokeNavigationTest 同一份判据；本版本 hasTestTag 无 substring） */
    private fun hasTagStartingWith(prefix: String): SemanticsMatcher =
        SemanticsMatcher("testTag starts with $prefix") { node ->
            node.config.contains(SemanticsProperties.TestTag) &&
                node.config[SemanticsProperties.TestTag].startsWith(prefix)
        }

    private fun waitUntilTagPrefix(prefix: String, timeoutMs: Long = 15_000) {
        composeRule.waitUntil(timeoutMillis = timeoutMs) {
            composeRule.onAllNodes(hasTagStartingWith(prefix)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitUntilAnyText(vararg texts: String, timeoutMs: Long = 15_000) {
        composeRule.waitUntil(timeoutMillis = timeoutMs) {
            texts.any { t ->
                composeRule.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    private fun clickFirstTag(prefix: String) {
        composeRule.onAllNodes(hasTagStartingWith(prefix)).onFirst().performClick()
    }

    private val app: android.app.Application by lazy {
        ApplicationProvider.getApplicationContext()
    }

    private fun resetDb(seed: Boolean, today: java.time.LocalDate = FIXED_DATE) {
        val db = AppDatabase.getInstance(app)
        // compose rule 下 @Before 跑在主线程，Room 的 clearAllTables 有主线程断言
        runBlocking(Dispatchers.IO) {
            db.clearAllTables()
            if (seed) DevSampleDataSeeder.seedIfNeeded(db, today)
        }
    }

    /** 种子库中第一个药品的 id（clearAllTables 不重置自增序列，id 不能写死） */
    private fun firstMedId(): Long = runBlocking(Dispatchers.IO) {
        AppDatabase.getInstance(app).medicationDao().getAllMedications().first().id
    }

    private fun capture(path: String) {
        // 截图前再钉一次"今天"：防 CurrentDateHolder 的 60s tick 用真实日期覆盖
        pinToday()
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage(File("src/test/snapshots/screen/$path"))
    }

    @Before
    fun setUp() {
        // Robolectric 逐方法重建沙箱，静态单例必须先复位（AGENTS.md §3 坑表），
        // 否则第二条测试起拿到失效句柄、事务状态错乱
        AppDatabase.resetForTest()
        // ⚠️ 钉死"今天"：Robolectric 的时钟默认跟随真实时间，而今日页有日期选择器
        // —— 不钉的话基线每天因日期数字/星期排布漂移而误报 diff。
        // SystemClock.setCurrentTimeMillis 影响不到 LocalDate.now()（实测无效），
        // 所以走项目自己的注入点：种子器显式 today + CurrentDateHolder（M3-2 的测试钩子）。
        pinToday()
        resetDb(seed = true, today = FIXED_DATE)
    }

    /** UI 的"今天"由 CurrentDateHolder 统一供给（M3-2），测试钩子直接覆盖 */
    private fun pinToday() {
        com.mcxiaoke.carromed.core.domain.CurrentDateHolder.setTodayForTest(FIXED_DATE)
    }

    companion object {
        /** 2026-09-30 周三，与种子数据的设计时点同源 */
        val FIXED_DATE: java.time.LocalDate = java.time.LocalDate.of(2026, 9, 30)
    }

    @Test
    fun todayScreen_seeded() {
        composeRule.setContent {
            CarroMedTheme {
                TodayScreen(
                    viewModel = TodayViewModel(app),
                    onNavigateToSettings = {}, onNavigateToAddMedication = {},
                    onNavigateToManualDose = {}, onNavigateToRefill = {},
                    onNavigateToInventory = {}, onOpenDose = {}
                )
            }
        }
        waitUntilTagPrefix(TestTags.DOSE_CARD)
        capture("today_seeded.png")
    }

    @Test
    fun todayScreen_empty() {
        resetDb(seed = false)
        composeRule.setContent {
            CarroMedTheme {
                TodayScreen(
                    viewModel = TodayViewModel(app),
                    onNavigateToSettings = {}, onNavigateToAddMedication = {},
                    onNavigateToManualDose = {}, onNavigateToRefill = {},
                    onNavigateToInventory = {}, onOpenDose = {}
                )
            }
        }
        // 空库 → 今日页显示首启引导卡（"药箱还是空的"）
        waitUntilAnyText("空的")
        capture("today_empty.png")
    }

    @Test
    fun cabinetScreen_seeded() {
        composeRule.setContent {
            CarroMedTheme {
                CabinetScreen(
                    viewModel = CabinetViewModel(app),
                    onNavigateToAddMedication = {}, onNavigateToMedDetail = {}
                )
            }
        }
        waitUntilTagPrefix(TestTags.MED_CARD)
        capture("cabinet_seeded.png")
    }

    @Test
    fun statsScreen_seeded() {
        composeRule.setContent {
            CarroMedTheme { StatsScreen(viewModel = StatsViewModel(app)) }
        }
        waitUntilAnyText("依从率")
        capture("stats_seeded.png")
    }

    @Test
    fun settingsScreen() {
        composeRule.setContent {
            CarroMedTheme {
                SettingsScreen(
                    viewModel = SettingsViewModel(app),
                    onNavigateBack = {}, onNavigateToPermissionCheck = {}
                )
            }
        }
        // ⚠️ LazyColumn 只组合可见项：快照截的是首屏（推迟/免打扰卡），
        // 不能等"数据管理"——它在折叠线以下，压根不在语义树里（真实踩过）
        waitUntilAnyText("夜间免打扰")
        capture("settings.png")
    }

    @Test
    fun medicationDetailScreen_seeded() {
        val medId = firstMedId()
        composeRule.setContent {
            CarroMedTheme {
                MedicationDetailScreen(
                    viewModel = MedicationDetailViewModel(app, medId),
                    onNavigateBack = {}, onNavigateToEditInfo = {},
                    onNavigateToReminder = {}, onNavigateToInventory = {}
                )
            }
        }
        waitUntilAnyText("药品设置")
        capture("med_detail_seeded.png")
    }
}
