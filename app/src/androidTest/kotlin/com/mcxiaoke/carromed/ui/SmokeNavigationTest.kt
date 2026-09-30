package com.mcxiaoke.carromed.ui

import android.Manifest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.mcxiaoke.carromed.MainActivity
import com.mcxiaoke.carromed.R
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.DevSampleDataSeeder
import com.mcxiaoke.carromed.ui.component.TestTags
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI 冒烟测试（PLAN-UI-TEST-20260929.md P1）。
 *
 * ## 它守的是什么
 *
 * §2 坑 5 那一类缺陷：`viewModel()` 走 `AndroidViewModelFactory` 的**反射单参构造器**，
 * Kotlin 默认参数不生成单参 Java 构造器——加参数的 ViewModel **编译过、单测全绿、
 * 真机一点就崩**。只有走真实 Activity + 真实工厂的 instrumented 测试能抓到它。
 *
 * ## 设计纪律
 *
 * - 只做**冒烟 + 一条核心交互**，不把 Python 走查的 13 页详细断言搬进来——
 *   两套详细断言必腐化（方案 §2.5）。
 * - 数据形态用 debug 源集的 [DevSampleDataSeeder]（进程内直调，与
 *   `dev.SEED` 广播同一份机制；直调免去广播的异步等待）。
 *   每条测试前 `clearAllTables + seed`，保证确定性。
 * - 导航断言优先用**页面标题等稳定文本**；只有"无文本可依"的交互元素
 *   （tab、卡片、图标按钮）才用 `TestTags`。
 * - 异步加载的页面（详情/库存/历史）一律 [waitByText] 轮询，
 *   不用 sleep，也不假设加载已完成。
 * - 返回一律 `Espresso.pressBack()`：系统返回键走 NavController 弹栈，
 *   与用户真实路径一致，也不依赖各页返回按钮的 desc 一致。
 */
@RunWith(AndroidJUnit4::class)
class SmokeNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    /** 通知权限弹窗会遮挡断言目标，预授权（精确闹钟走 USE_EXACT_ALARM 免授权） */
    @get:Rule
    val permissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @Before
    fun resetToSeededDb() {
        val context = composeRule.activity.applicationContext
        val db = AppDatabase.getInstance(context)
        // 测试跑在与 App 同一个进程（instrumentation 默认），
        // 直接复用进程内单例清库 + 灌种子，UI 的 Flow 会自动感知刷新
        runBlocking {
            db.clearAllTables()
            DevSampleDataSeeder.seedIfNeeded(db)
        }
    }

    // ---------- helpers ----------

    /**
     * 运行时解析界面文案 —— 断言一律引用 `R.string.*`，不写中文字面量。
     *
     * 中文都放在 `res/values/`（默认目录，与设备 locale 无关），
     * 所以设备是英文环境时这里同样解析出中文，无需切 locale。
     * 用 activity 而非 `targetContext`：activity 就是真正渲染这些字符串的 Context，
     * 其 locale / 覆写与被测页面完全一致。
     */
    private fun str(id: Int): String = composeRule.activity.getString(id)

    /**
     * 参数化 tag 的前缀匹配（`dose_card_123` / `med_card_5`）。
     * 本版本 ui-test 的 `hasTestTag` 没有 substring 参数，自写一个版本无关的。
     */
    private fun hasTagStartingWith(prefix: String): SemanticsMatcher =
        SemanticsMatcher("testTag starts with $prefix") { node ->
            node.config.contains(SemanticsProperties.TestTag) &&
                node.config[SemanticsProperties.TestTag].startsWith(prefix)
        }

    private fun waitByText(text: String, substring: Boolean = false, timeoutMs: Long = 10_000) {
        composeRule.waitUntil(timeoutMillis = timeoutMs) {
            composeRule.onAllNodesWithText(text, substring = substring)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitByTagPrefix(prefix: String, timeoutMs: Long = 10_000) {
        composeRule.waitUntil(timeoutMillis = timeoutMs) {
            composeRule.onAllNodes(hasTagStartingWith(prefix))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun countNodes(prefix: String): Int =
        composeRule.onAllNodes(hasTagStartingWith(prefix)).fetchSemanticsNodes().size

    /**
     * 点击目标文本，必要时向下滚动（LazyColumn 未滚到的项**不在语义树里**，
     * 直接 onNodeWithText 会找不到——设置页的保活卡就踩过）。
     * 逐次上滑重试而不是一次滚到底：目标可能在任何深度。
     */
    private fun clickTextScrolling(text: String, substring: Boolean = false, maxSwipes: Int = 5) {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        repeat(maxSwipes + 1) { attempt ->
            if (attempt > 0) {
                device.swipe(540, 1800, 540, 700, 24)
                composeRule.waitForIdle()
            }
            val nodes = composeRule.onAllNodesWithText(text, substring = substring)
                .fetchSemanticsNodes()
            if (nodes.isNotEmpty()) {
                composeRule.onAllNodesWithText(text, substring = substring).onFirst().performClick()
                return
            }
        }
        error("text not found after scrolling: $text")
    }

    /** 同 [clickTextScrolling]，目标是参数化 testTag（dose_card_* / med_card_* 等） */
    private fun clickTagScrolling(prefix: String, maxSwipes: Int = 5) {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        repeat(maxSwipes + 1) { attempt ->
            if (attempt > 0) {
                device.swipe(540, 1800, 540, 700, 24)
                composeRule.waitForIdle()
            }
            val nodes = composeRule.onAllNodes(hasTagStartingWith(prefix)).fetchSemanticsNodes()
            if (nodes.isNotEmpty()) {
                composeRule.onAllNodes(hasTagStartingWith(prefix)).onFirst().performClick()
                return
            }
        }
        error("tag not found after scrolling: $prefix")
    }

    /** 待服卡片上「确认服药」按钮的数量——coreFlow 的守恒断言用 */
    private fun confirmButtonCount(): Int = countNodes(TestTags.DOSE_CONFIRM)

    private fun openTab(tag: String) {
        composeRule.onNodeWithTag(tag).performClick()
    }

    /**
     * 连续返回直到出现目标 tag（底栏只在四个主 Tab 存在，
     * 二级页嵌套深度靠数 back 次数必错——refill 之后需要 3 跳，首版写 2 跪在这）。
     */
    private fun pressBackUntilTag(prefix: String, maxBacks: Int = 6) {
        repeat(maxBacks) {
            if (countNodes(prefix) > 0) return
            pressBack()
            composeRule.waitForIdle()
        }
        error("tag not visible after backs: $prefix")
    }

    // ---------- tests ----------

    @Test
    fun allTabs_open_withoutCrash() {
        openTab(TestTags.TAB_CABINET)
        waitByText(str(R.string.cabinet_title))
        openTab(TestTags.TAB_PROGRESS)
        waitByText(str(R.string.prog_title))
        openTab(TestTags.TAB_STATS)
        waitByText(str(R.string.stats_title))
        openTab(TestTags.TAB_TODAY)
        waitByText(str(R.string.today_title))
    }

    /**
     * 顶栏几何回归（PLAN-TITLEBAR-STANDARDIZATION-20260930.md §8.3）。
     *
     * ## 守的是什么
     *
     * 2026-09-27 起潜伏到 09-30 的那类缺陷：**主 Tab 的顶栏标题纵向位置与其他页面不一致**
     * （当时"今日清单"比另外三个 Tab 低 63px = 一整个状态栏；根因是内层空壳
     * `Scaffold` 的 `innerPadding` 与 `.statusBarsPadding()` 把状态栏计了两次）。
     *
     * ## 为什么只能在 instrumented 层守
     *
     * Robolectric 没有真实系统栏，`WindowInsets` 恒为 0 —— 同一个缺陷在
     * Roborazzi 快照里两张图长得一模一样（2026-09-30 实测，见方案 §8.2）。
     * 换句话说：**能看见这个 bug 的只有真机语义树坐标**。
     *
     * ## 三条断言
     *
     * 1. 四个主 Tab 的标题 top 必须一致（差值 ≤ 1dp）；
     * 2. 二级页（官方 `TopAppBar`）与主 Tab 同高同位置 —— 这正是把全应用标题
     *    统一到 `titleLarge`（22sp）的目的，字号一旦回退就会被抓；
     * 3. 顶栏必须**吸顶**：滚动列表后标题位置不变（旧实现是列表第一个 item，
     *    滚一屏后标题与右上按钮整条消失）。
     */
    @Test
    fun topBarAligned_acrossTabs() {
        val tabs = listOf(
            TestTags.TAB_TODAY to R.string.today_title,
            TestTags.TAB_CABINET to R.string.cabinet_title,
            TestTags.TAB_PROGRESS to R.string.prog_title,
            TestTags.TAB_STATS to R.string.stats_title
        )

        val tops = tabs.map { (tabTag, titleRes) ->
            openTab(tabTag)
            waitByText(str(titleRes))
            waitByTagPrefix(TestTags.TOP_BAR_TITLE)
            // tag 必须落在**本页**的标题上：否则量到的可能是上一页残留的节点
            composeRule.onNodeWithTag(TestTags.TOP_BAR_TITLE).assertTextEquals(str(titleRes))
            str(titleRes) to topBarTitleTop()
        }

        val spread = tops.maxOf { it.second } - tops.minOf { it.second }
        assertWithMessage("四个主 Tab 的顶栏标题 top 必须一致，实测：$tops")
            .that(spread).isAtMost(1f)

        // ---- 2. 二级页与主 Tab 同高 ----
        // 用"手动补录服药"（man_title 全工程只此一处使用，标题文本无歧义），
        // 它走的是页面级 Scaffold + 官方 TopAppBar 的老链路。
        openTab(TestTags.TAB_TODAY)
        composeRule.onNodeWithContentDescription(str(R.string.today_cd_manual_log)).performClick()
        waitByText(str(R.string.man_title))
        val secondaryTop = composeRule.onNodeWithText(str(R.string.man_title))
            .getUnclippedBoundsInRoot().top.value
        assertWithMessage("二级页顶栏标题 top 应与主 Tab 一致（同一 titleLarge + 同一 topBar 槽位）")
            .that(kotlin.math.abs(secondaryTop - tops[0].second)).isAtMost(1f)
        pressBack()

        // ---- 3. 顶栏吸顶 ----
        waitByText(str(R.string.today_title))
        val beforeScroll = topBarTitleTop()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            .swipe(540, 1800, 540, 700, 24)
        composeRule.waitForIdle()
        // onNodeWithTag 在节点消失时会直接抛错 —— 这本身就是"顶栏滚走了"的判据
        assertWithMessage("顶栏必须吸顶：滚动列表后标题位置不得变化")
            .that(kotlin.math.abs(topBarTitleTop() - beforeScroll)).isAtMost(1f)
    }

    /** 统一顶栏标题的纵向位置（相对根节点，单位 dp） */
    private fun topBarTitleTop(): Float =
        composeRule.onNodeWithTag(TestTags.TOP_BAR_TITLE).getUnclippedBoundsInRoot().top.value

    @Test
    fun allRoutes_open_withoutCrash() {
        // --- settings + permission_check（今日页右上角进入）---
        composeRule.onNodeWithContentDescription(str(R.string.today_cd_settings)).performClick()
        waitByText(str(R.string.set_title))
        // 按钮实际文本带 " >" 后缀，必须 substring 匹配（首次运行踩过：全等匹配永远找不到）
        clickTextScrolling(str(R.string.set_permission_check_button), substring = true)
        waitByText(str(R.string.perm_title))
        pressBack()
        pressBack()

        // --- manual_dose（今日页 FAB）---
        // ⚠️ ExtendedFAB 合并语义后 Text 不可见（本版本行为），用图标 desc 点它
        composeRule.onNodeWithContentDescription(str(R.string.today_cd_manual_log)).performClick()
        waitByText(str(R.string.man_title))
        pressBack()

        // --- dose_detail（今日页任一待服卡片）---
        waitByTagPrefix(TestTags.DOSE_CARD)
        clickTagScrolling(TestTags.DOSE_CARD)
        waitByText(str(R.string.rdetail_title))
        pressBack()

        // --- med_add（药箱右上角"添加药品"，新增模式带初始库存段）---
        openTab(TestTags.TAB_CABINET)
        composeRule.onNodeWithContentDescription(str(R.string.cabinet_add_medication)).performClick()
        // SectionCard 的标题渲染成 "N. 标题"，用 substring 匹配段名
        clickTextScrolling(str(R.string.medit_section_initial_stock), substring = true)
        pressBack()

        // --- med_detail 与它的三个二级页 ---
        openTab(TestTags.TAB_CABINET)
        waitByTagPrefix(TestTags.MED_CARD)
        clickTagScrolling(TestTags.MED_CARD)
        waitByText(str(R.string.mdetail_section_settings))
        clickTextScrolling(str(R.string.mdetail_entry_profile))
        waitByText(str(R.string.medit_title_edit_medication_info))
        pressBack()
        clickTextScrolling(str(R.string.mdetail_entry_reminder))
        waitByText(str(R.string.rem_title))
        pressBack()
        clickTextScrolling(str(R.string.mdetail_entry_inventory))
        waitByText(str(R.string.inv_title))
        // --- refill（库存页"补药入库"）---
        clickTextScrolling(str(R.string.inv_refill))
        waitByText(str(R.string.refill_title))
        // refill → 库存 → 药品详情 → 药箱，共 3 跳；数错必跪，用"back 直到药箱可见"
        pressBackUntilTag(TestTags.MED_CARD)

        // --- med_history（进展页任一药品矩阵卡）---
        openTab(TestTags.TAB_PROGRESS)
        waitByText(str(R.string.prog_title))
        // 矩阵卡整卡可点、contentDescription 以药名开头（见 MedicationMatrixCard）
        clickTextScrolling(SEED_MED_NAME, substring = true)
        waitByText(str(R.string.mhist_title))
        pressBack()

        // --- stats（直开）---
        openTab(TestTags.TAB_STATS)
        waitByText(str(R.string.stats_title))
    }

    @Test
    fun coreFlow_takeDose_thenUndo() {
        waitByTagPrefix(TestTags.DOSE_CONFIRM)
        val before = confirmButtonCount()
        assertThat(before).isAtLeast(1)

        // 打卡：点第一张待服卡上的确认按钮
        composeRule.onAllNodes(hasTagStartingWith(TestTags.DOSE_CONFIRM))
            .onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            confirmButtonCount() == before - 1
        }

        // 撤销：打开"今日已服"区的第一张卡片 → 记录详情 → 撤销
        // （种子数据自带 2 条已服，onFirst 可能是其中之一——无论撤哪条，
        //   槽位都会回到 PENDING，待服按钮数守恒，这正是冒烟要断言的不变量）
        composeRule.onAllNodesWithText(str(R.string.today_badge_taken)).onFirst().performClick()
        waitByText(str(R.string.rdetail_undo))
        composeRule.onNodeWithText(str(R.string.rdetail_undo)).performClick()
        // ⚠️ 撤销成功后记录详情页**自动弹回**今日页（uiState.done → onNavigateBack），
        // 这里绝不能再 pressBack——那会把 App 整个退出（首次运行踩过）

        composeRule.waitUntil(timeoutMillis = 10_000) {
            confirmButtonCount() == before
        }
    }

    private companion object {
        /**
         * 种子数据里的药名，**不是界面文案**：`环孢素` 是写进 Room 的 `medication.name`，
         * 由 [DevSampleDataSeeder] 灌入，从来没有对应的 `R.string.*`（药品名是用户数据，
         * 不可能资源化）。这里匹配的是库里的实际值，故保留字面量并显式说明出处。
         */
        const val SEED_MED_NAME = "环孢素"
    }
}
