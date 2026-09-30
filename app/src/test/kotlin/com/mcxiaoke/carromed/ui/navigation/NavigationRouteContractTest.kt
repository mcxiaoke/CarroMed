package com.mcxiaoke.carromed.ui.navigation

import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.ui.component.TestTags
import org.junit.Test
import kotlin.reflect.KClass

/**
 * 导航路由契约单元测试（PLAN-MODERN-NAVIGATION-MOTION-20260930-v2 §2.1）。
 *
 * 核心目标：在编译期与 JVM 单测层把 `isTopLevel` 真值表和测试标签映射钉死，
 * 防止从 String 迁移至类型安全路由时发生"底栏静默消失"或"测试标签丢失"（评审指出陷阱）。
 */
class NavigationRouteContractTest {

    private val topLevelClasses: Set<KClass<out ScreenRoute>> = setOf(
        ScreenRoute.Today::class,
        ScreenRoute.Cabinet::class,
        ScreenRoute.Progress::class,
        ScreenRoute.Stats::class
    )

    private val secondaryClasses: Set<KClass<out ScreenRoute>> = setOf(
        ScreenRoute.MedicationDetail::class,
        ScreenRoute.AddEditMedication::class,
        ScreenRoute.ReminderSettings::class,
        ScreenRoute.Inventory::class,
        ScreenRoute.ManualDose::class,
        ScreenRoute.DoseDetail::class,
        ScreenRoute.Refill::class,
        ScreenRoute.MedHistory::class,
        ScreenRoute.Settings::class,
        ScreenRoute.PermissionCheck::class
    )

    @Test
    fun bottomNavItems_containsExactlyFourTopLevelTabs() {
        assertThat(BottomNavItems).hasSize(4)
        val registeredClasses = BottomNavItems.map { it.targetClass }.toSet()
        assertThat(registeredClasses).isEqualTo(topLevelClasses)
    }

    @Test
    fun bottomNavItems_tagsAreConsistentAndDistinct() {
        val tags = BottomNavItems.map { it.testTag }
        assertThat(tags).containsExactly(
            TestTags.TAB_TODAY,
            TestTags.TAB_CABINET,
            TestTags.TAB_PROGRESS,
            TestTags.TAB_STATS
        ).inOrder()
    }

    @Test
    fun isTopLevel_truthTableVerification() {
        topLevelClasses.forEach { kclass ->
            assertThat(BottomNavItems.any { it.targetClass == kclass }).isTrue()
        }
        secondaryClasses.forEach { kclass ->
            assertThat(BottomNavItems.any { it.targetClass == kclass }).isFalse()
        }
    }
}
