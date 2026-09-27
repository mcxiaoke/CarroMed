package com.mcxiaoke.carromed.core.alarm

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 提醒行为解析测试
 *
 * 背景：设置页的「夜间免打扰 / 推迟时长 / 重要提醒」此前**完全没有被任何代码消费** ——
 * 通知栏的「推迟30分钟」是写死的字面量，夜间静音开关形同虚设。
 */
class ReminderSettingsTest {

    @Test
    fun nightDndWindow_covers23to07() {
        assertThat(ReminderSettings.inNightDndWindow(23)).isTrue()
        assertThat(ReminderSettings.inNightDndWindow(0)).isTrue()
        assertThat(ReminderSettings.inNightDndWindow(3)).isTrue()
        assertThat(ReminderSettings.inNightDndWindow(6)).isTrue()
        // 07:00 结束（不含）
        assertThat(ReminderSettings.inNightDndWindow(7)).isFalse()
        assertThat(ReminderSettings.inNightDndWindow(12)).isFalse()
        assertThat(ReminderSettings.inNightDndWindow(22)).isFalse()
    }

    @Test
    fun shouldSilence_respectsGlobalNightDndSwitch() {
        val on = ReminderSettings.Behavior(nightDnd = true)
        val off = ReminderSettings.Behavior(nightDnd = false)
        assertThat(ReminderSettings.shouldSilence(on, isCritical = false, hourOfDay = 23)).isTrue()
        assertThat(ReminderSettings.shouldSilence(off, isCritical = false, hourOfDay = 23)).isFalse()
    }

    @Test
    fun shouldSilence_criticalReminderPunchesThroughNightDnd() {
        val on = ReminderSettings.Behavior(nightDnd = true)
        // 重要提醒（胰岛素、抗凝药）必须穿透夜间静音
        assertThat(ReminderSettings.shouldSilence(on, isCritical = true, hourOfDay = 2)).isFalse()
    }

    @Test
    fun shouldSilence_neverSilencesDuringDaytime() {
        val on = ReminderSettings.Behavior(nightDnd = true)
        assertThat(ReminderSettings.shouldSilence(on, isCritical = false, hourOfDay = 10)).isFalse()
    }

    @Test
    fun defaultBehavior_isThirtyMinutesAndDndOn() {
        val d = ReminderSettings.Behavior()
        assertThat(d.snoozeMinutes).isEqualTo(30)
        assertThat(d.nightDnd).isTrue()
        assertThat(d.fullScreenAlert).isTrue()
        assertThat(ReminderSettings.DEFAULT_SNOOZE_MINUTES).isEqualTo(30)
    }
}
