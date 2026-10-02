package com.mcxiaoke.carromed.core.alarm

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.AppSettingEntity
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 提醒行为解析测试
 *
 * 背景：设置页的「夜间免打扰 / 推迟时长 / 重要提醒」此前**完全没有被任何代码消费** ——
 * 通知栏的「推迟30分钟」是写死的字面量，夜间静音开关形同虚设。
 *
 * 跑在 Robolectric 上（而不是纯 JVM）：下面两条用例要真开一个内存 SQLite，
 * 走的是和 `AlarmReceiver` 同一份解析路径。
 */
@RunWith(RobolectricTestRunner::class)
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

    /**
     * 原先这里是 `assertThat(ReminderSettings.Behavior().fullScreenAlert).isTrue()` ——
     * 断言的是**本测试自己声明的默认值**。
     *
     * 这样的断言守不住任何东西：它和实现在同一个文件里各写各的，实现怎么坏都过。
     * 它当年守的缺陷正是 M1-4 那一类 —— `fullScreenAlert` 端到端**从未被消费**
     * （写库 ✓ 读库 ✓ 传入 ✓，`Notifications` 里没有 `setFullScreenIntent`），
     * 而这条断言照样全绿。
     *
     * 现在改为守**真正的消费点**：[ReminderSettings.resolve] 从 `app_settings`
     * 读出来的值必须真的改变通知里显示的推迟分钟数。
     * 把 `snooze_minutes` 改成别的值 → 行为必须跟着变；读成默认值就是回归。
     */
    @Test
    fun `resolve 从库读推迟时长 改库必改行为（不是读自己声明的默认值）】`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val medId = db.medicationDao().insert(MedicationEntity(name = "环孢素"))
            db.reminderSettingsDao().ensureDefaults(medId)

            db.appSettingDao().setSetting(AppSettingEntity(ReminderSettings.KEY_SNOOZE_MINUTES, "45"))
            assertThat(ReminderSettings.resolve(context, db, medId).snoozeMinutes).isEqualTo(45)

            // 夜间静音同样必须真的从库里读出来
            db.appSettingDao().setSetting(AppSettingEntity(ReminderSettings.KEY_NIGHT_DND, "false"))
            assertThat(ReminderSettings.resolve(context, db, medId).nightDnd).isFalse()
        } finally {
            db.close()
        }
    }

    /** 药品级配置覆盖全局：两级存储必须真的分层解析 */
    @Test
    fun `药品级推迟时长覆盖全局值`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val medId = db.medicationDao().insert(MedicationEntity(name = "维生素D"))
            val settings = db.reminderSettingsDao().ensureDefaults(medId)
            db.appSettingDao().setSetting(AppSettingEntity(ReminderSettings.KEY_SNOOZE_MINUTES, "45"))

            // 0 = 跟随全局
            assertThat(settings.snoozeMinutes).isEqualTo(0)
            assertThat(ReminderSettings.resolve(context, db, medId).snoozeMinutes).isEqualTo(45)

            db.reminderSettingsDao().updateBehavior(medId, false, 20, 0)
            assertThat(ReminderSettings.resolve(context, db, medId).snoozeMinutes).isEqualTo(20)
        } finally {
            db.close()
        }
    }

    @Test
    fun `默认行为是 30 分钟且夜间静音开启`() {
        val d = ReminderSettings.Behavior()
        assertThat(d.snoozeMinutes).isEqualTo(30)
        assertThat(d.nightDnd).isTrue()
        assertThat(d.repeatReminderEnabled).isFalse()
        assertThat(ReminderSettings.DEFAULT_SNOOZE_MINUTES).isEqualTo(30)
    }
}
