package com.mcxiaoke.carromed.core.domain.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.data.AppDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 跨夏令时的端到端行为 —— [SlotProjectionDstPropertyTest]（纯引擎属性）的**落库版**。
 *
 * ## 为什么要多这一层
 *
 * 属性测试守的是 `SlotProjectionEngine` 的数学。它证明不了两件事：
 *
 * 1. `scheduled_ts` 那个 `Long` 列**存得下、读得回**（毫单位时间戳到 `Long` 上限还有 11 位余量，
 *    但 `scheduledDate` / `scheduledTime` 是 `TEXT`，往返时被截断/补零也不是没 conceivable 的）
 * 2. `reconcileSchedule` 的 diff 键到底是**日历**还是**瞬时** ——
 *    这决定了"重复对账会不会把时间戳改掉"，而这是闹钟身份的前提
 *
 * 两者都必须过一遍真库，因为 bug 只在"投影 → 落库 → 再读出来"这条链上才现形。
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class DoseSlotDstServiceTest {

    private lateinit var db: AppDatabase
    private lateinit var admin: MedicationAdminService
    private lateinit var tracking: DoseTrackingService

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** 北半球：2027-03-14 是春季前跳（凌晨 2 点不存在），2027-11-07 是秋季回拨 */
    private val newYork = ZoneId.of("America/New_York")
    private val springForward = LocalDate.of(2027, 3, 14)
    private val fallBack = LocalDate.of(2027, 11, 7)

    private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        admin = MedicationAdminService(db)
        tracking = DoseTrackingService(db)
    }

    @After
    @Throws(IOException::class)
    fun tearDown() = db.close()

    private suspend fun dailyMedication(start: LocalDate, time: String = "08:00"): Long {
        val id = admin.saveProfile(
            MedicationAdminService.ProfileDraft(name = "哨兵药", unit = "片", defaultDose = 1f)
        )
        admin.saveReminderPolicy(
            medicationId = id,
            draft = MedicationAdminService.PolicyDraft(
                startDate = start.toString(),
                times = listOf(MedicationAdminService.TimeDraft(time, 1f, "早间"))
            )
        )
        return id
    }

    private suspend fun slotsOf(medId: Long) = db.doseSlotDao().getAllSlots()
        .filter { it.medicationId == medId }
        .sortedBy { it.scheduledDate }

    // ================================================================
    // 1. 跨切换日：一天一个槽位，本地时刻恒定
    // ================================================================

    @Test
    fun `春季前跳与秋季回拨前后 每天各一个槽位且本地时刻恒为 08 00`() = runTest {
        val from = springForward.minusDays(3)
        val medId = dailyMedication(from)
        tracking.reconcileSchedule(medId, from, fallBack.plusDays(3), zoneId = newYork)

        val slots = slotsOf(medId)
        // 3 天前 → 回拨后 3 天，含两个切换日
        val expectedDays = Duration.between(from.atStartOfDay(), fallBack.plusDays(4).atStartOfDay()).toDays()
        assertThat(slots).hasSize(expectedDays.toInt())

        // 一天一条，且日期连续无空洞无重复
        assertThat(slots.map { it.scheduledDate }.distinct()).hasSize(slots.size)
        slots.zipWithNext { a, b ->
            val gap = Duration.between(
                LocalDate.parse(a.scheduledDate).atStartOfDay(),
                LocalDate.parse(b.scheduledDate).atStartOfDay()
            ).toDays()
            assertThat(gap).isEqualTo(1L)
        }

        // ★ 落库后反解回本地时刻仍是 08:00（跨两个切换点都不许漂）
        val intended = LocalTime.of(8, 0)
        slots.forEach { s ->
            assertThat(Instant.ofEpochMilli(s.scheduledTs).atZone(newYork).toLocalTime())
                .isEqualTo(intended)
            // 文本列也必须是"08:00"，不能被时区换算改写成别的
            assertThat(s.scheduledTime).isEqualTo("08:00")
        }
    }

    @Test
    fun `时间戳经数据库往返后不被截断或补零`() = runTest {
        val from = springForward.minusDays(1)
        val medId = dailyMedication(from)
        tracking.reconcileSchedule(medId, from, springForward.plusDays(1), zoneId = newYork)

        val before = slotsOf(medId).associate { it.id to it.scheduledTs }
        // 重新查一次（走 Room 的 cursor → Long 映射）
        val after = slotsOf(medId).associate { it.id to it.scheduledTs }
        assertThat(after).isEqualTo(before)

        // 时间戳必须落在 2027 年的合理范围内（不是 0、不是毫秒被当成秒）
        before.values.forEach { ts ->
            assertThat(ts).isGreaterThan(1_700_000_000_000L)
            assertThat(ts).isLessThan(2_000_000_000_000L)
        }
    }

    // ================================================================
    // 2. 幂等：重复对账不改动已落库的瞬时
    // ================================================================

    @Test
    fun `重复对账跨 DST 边界不改动已存槽位的时间戳与 id`() = runTest {
        val from = springForward.minusDays(5)
        val medId = dailyMedication(from)
        tracking.reconcileSchedule(medId, from, springForward.plusDays(5), zoneId = newYork)

        val first = slotsOf(medId).associate { it.id to it.scheduledTs }
        assertThat(first).isNotEmpty()

        repeat(5) { tracking.reconcileSchedule(medId, from, springForward.plusDays(5), zoneId = newYork) }

        val after = slotsOf(medId).associate { it.id to it.scheduledTs }
        // ★ id 与时间戳都一模一样 —— diff 键是日历字符串，所以时区变化碰不到既有行
        assertThat(after).isEqualTo(first)
    }

    /**
     * 同一批日历日期，换一个时区重算会得到**不同**的瞬时 ——
     * 而 `reconcileSchedule` 不会去改既有行。
     *
     * ⚠️ 这条测试**不是在说这是正确行为**，它是在把当前的真实行为钉住，
     * 因为这个行为有一个尚未处理的产品后果（见下面的 KDoc）。
     * 如果将来决定处理出差跨时区，这条会红，届时应当**改名**成断言新行为，
     * 而不是把断言反过来。
     */
    @Test
    fun `当前行为 换时区重算不会改写既有槽位（已知限制 非正确性断言）`() = runTest {
        val from = springForward.minusDays(1)
        val medId = dailyMedication(from)
        tracking.reconcileSchedule(medId, from, springForward.plusDays(1), zoneId = newYork)
        val inNewYork = slotsOf(medId).associate { it.scheduledDate to it.scheduledTs }

        // 用户"飞"到东京，用东京时区重算
        val tokyo = ZoneId.of("Asia/Tokyo")
        tracking.reconcileSchedule(medId, from, springForward.plusDays(1), zoneId = tokyo)
        val afterTokyo = slotsOf(medId).associate { it.scheduledDate to it.scheduledTs }

        // 槽位集合（日期）与时间戳都没变
        assertThat(afterTokyo.keys).isEqualTo(inNewYork.keys)
        assertThat(afterTokyo).isEqualTo(inNewYork)
        // 而如果当初按东京时区算，瞬时本该差 13/14 小时 —— 说明时区确实是有意义的输入
        val date = from
        val asTokyo = date.atTime(LocalTime.of(8, 0)).atZone(tokyo).toInstant().toEpochMilli()
        assertThat(inNewYork.getValue(date.toString())).isNotEqualTo(asTokyo)
    }

    // ================================================================
    // 3. 闹钟身份不受时区影响
    // ================================================================

    /**
     * 同一日历槽位在不同时区下**仍是同一个闹钟身份**。
     *
     * 闹钟 URI 是 `carromed://alarm/{medId}/{date}/{time}/{kind}`，纯日历寻址
     * （P0-1 修法）。所以时区变化不会让 `PendingIntent` 变成"另一个" ——
     * 这正是我们想要的：换了时区，闹钟被**替换**而不是**多出一份**。
     */
    @Test
    fun `同一日历槽位在不同时区下产生同一个闹钟身份`() = runTest {
        val from = springForward.minusDays(1)
        val medId = dailyMedication(from)
        tracking.reconcileSchedule(medId, from, springForward.plusDays(1), zoneId = newYork)
        val inNewYork = slotsOf(medId).map { it.id to it.scheduledDate to it.scheduledTime }

        tracking.reconcileSchedule(medId, from, springForward.plusDays(1), zoneId = ZoneId.of("Asia/Tokyo"))

        val after = slotsOf(medId).map { it.id to it.scheduledDate to it.scheduledTime }
        assertThat(after).isEqualTo(inNewYork)
    }
}
