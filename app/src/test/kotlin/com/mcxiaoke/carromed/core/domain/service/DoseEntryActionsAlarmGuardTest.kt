package com.mcxiaoke.carromed.core.domain.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.alarm.AlarmScheduler
import com.mcxiaoke.carromed.core.alarm.DoseActionResult
import com.mcxiaoke.carromed.core.alarm.DoseEntryActions
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.DoseSlotEntity
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId

/**
 * ⭐ **最容易漏掉、后果最严重**的那一半：未来的槽位被拒绝时，**明天的闹钟必须还在**。
 *
 * ## 为什么这条测试单独存在
 *
 * `DoseEntryActions.confirm` / `skip` 的旧设计是"无论成败都撤闹钟"——
 * 对"已处理过"的槽位这是对的（它不该再提醒）。但对一条**未来**的槽位，
 * 这一撤就是灾难：槽位仍是 PENDING，而闹钟身份是内容寻址的
 * `(medId, date, time, kind)`，cancel 掉之后 `AlarmReconciler` **不会再排回来**
 * （它只给 `getOpenSlots()` 排，而这条槽位并没有变成"已处理"，
 * 只是当时的 cancel 已经生效）—— 用户以为自己在提前处理，
 * 实际上**把明天的提醒弄丢了，且毫无察觉**。
 *
 * 只加 SQL 守卫、忘了同时改这一半，就会造出一个比原缺陷更隐蔽的新缺陷：
 * 卡片不再变已服、库存不再乱扣，看起来"修好了"，而提醒照样丢。
 * 这条测试是唯一会为此报警的测试。
 *
 * 闹钟集合用 `PendingIntent` 本身（`ShadowAlarmManager.scheduledAlarms`）比较，
 * 与 `AlarmIdentityTest` / `AlarmPrecisionAndGraceTest` 同一套做法 ——
 * `PendingIntent.equals` 就是 Android 真实的判重语义。
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class DoseEntryActionsAlarmGuardTest {

    private lateinit var db: AppDatabase
    private lateinit var actions: DoseEntryActions

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val today: LocalDate get() = LocalDate.now()

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // 编排层的时钟与真实时间一致（槽位用 today / today+1 构造，天然确定）
        actions = DoseEntryActions(context, db)
    }

    @After
    @Throws(IOException::class)
    fun tearDown() {
        // `setCanScheduleExactAlarms` 是 static 的，跨测试方法不重置（见 AlarmPrecisionAndGraceTest）
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        db.close()
    }

    // ==================== fixture ====================

    private suspend fun givenSlot(date: LocalDate): DoseSlotEntity {
        val medId = db.medicationDao().insert(MedicationEntity(name = "环孢素", unit = "片"))
        val id = db.doseSlotDao().insert(
            DoseSlotEntity(
                medicationId = medId,
                policyId = 1L,
                scheduledDate = date.toString(),
                scheduledTime = "10:30",
                scheduledTs = date.atTime(10, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                doseAmount = 1000,
                status = SlotStatus.PENDING
            )
        )
        return db.doseSlotDao().getSlotById(id)!!
    }

    private fun alarmManager(): AlarmManager = context.getSystemService(AlarmManager::class.java)

    @Suppress("DEPRECATION")
    private fun shadowAlarms(): Set<PendingIntent> {
        val shadow = Shadows.shadowOf(alarmManager()) as ShadowAlarmManager
        return shadow.scheduledAlarms.mapNotNullTo(mutableSetOf()) { it.operation }
    }

    /**
     * 生产调度器会给某槽位注册的**准点**闹钟身份。
     *
     * 走 [AlarmScheduler.alarmIntent]（生产路径）+ 与生产相同的 requestCode / flags，
     * 而不是在测试里重写一遍 Uri 构造 —— 那样守的是测试里的影子（`AlarmIdentityTest` 的坑）。
     */
    private fun pendingMainOf(slot: DoseSlotEntity): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        AlarmScheduler.alarmIntent(
            context, slot.medicationId, slot.scheduledDate, slot.scheduledTime, slot.id,
            AlarmScheduler.Kind.MAIN
        ),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    // ==================== 未来：闹钟必须活着 ====================

    @Test
    fun `未来槽位打卡被拒时 明天的闹钟仍然在`() = runTest {
        val slot = givenSlot(today.plusDays(1))
        AlarmScheduler.schedule(context, slot, slot.scheduledTs, AlarmScheduler.Kind.MAIN)
        // 前置断言：闹钟确实排上了（否则后面的"还在"是恒真断言）
        assertThat(shadowAlarms()).contains(pendingMainOf(slot))

        assertThat(actions.confirm(slot.id)).isEqualTo(DoseActionResult.FUTURE_SLOT)

        assertThat(shadowAlarms()).contains(pendingMainOf(slot))
        assertThat(db.doseSlotDao().getSlotById(slot.id)!!.status).isEqualTo(SlotStatus.PENDING)
    }

    @Test
    fun `未来槽位跳过被拒时 明天的闹钟仍然在`() = runTest {
        val slot = givenSlot(today.plusDays(1))
        AlarmScheduler.schedule(context, slot, slot.scheduledTs, AlarmScheduler.Kind.MAIN)
        assertThat(shadowAlarms()).contains(pendingMainOf(slot))

        assertThat(actions.skip(slot.id)).isEqualTo(DoseActionResult.FUTURE_SLOT)

        assertThat(shadowAlarms()).contains(pendingMainOf(slot))
        assertThat(db.doseSlotDao().getSlotById(slot.id)!!.status).isEqualTo(SlotStatus.PENDING)
    }

    // ==================== 防"修过头"：今天照常撤 ====================

    @Test
    fun `今天的槽位打卡成功后 闹钟照常被撤`() = runTest {
        val slot = givenSlot(today)
        AlarmScheduler.schedule(context, slot, slot.scheduledTs, AlarmScheduler.Kind.MAIN)
        assertThat(shadowAlarms()).contains(pendingMainOf(slot))

        assertThat(actions.confirm(slot.id)).isEqualTo(DoseActionResult.APPLIED)

        // 槽位已产生结论 ⇒ 闹钟不该再响（撤掉是刻意的设计，不能被本次改动误伤）
        assertThat(shadowAlarms()).doesNotContain(pendingMainOf(slot))
        assertThat(db.doseSlotDao().getSlotById(slot.id)!!.status).isEqualTo(SlotStatus.COMPLETED)
    }

    @Test
    fun `重复打卡第二次返回已处理过 且不重复产生事实`() = runTest {
        val slot = givenSlot(today)

        assertThat(actions.confirm(slot.id)).isEqualTo(DoseActionResult.APPLIED)
        assertThat(actions.confirm(slot.id)).isEqualTo(DoseActionResult.ALREADY_HANDLED)
        assertThat(db.doseRecordDao().getAllRecords()).hasSize(1)
    }
}
