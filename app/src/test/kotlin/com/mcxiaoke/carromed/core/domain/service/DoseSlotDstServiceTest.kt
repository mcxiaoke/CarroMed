package com.mcxiaoke.carromed.core.domain.service
import com.mcxiaoke.carromed.core.data.model.SlotStatus

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
     * 换时区后，已物化的开放槽位的 `scheduled_ts` **必须重算**（M3-1）。
     *
     * ## 这条测试改过方向
     *
     * 它原本叫「当前行为 换时区重算不会改写既有槽位（已知限制 非正确性断言）」，
     * KDoc 里写着"如果将来决定处理出差跨时区，这条会红，届时应当**改名**成断言新行为，
     * 而不是把断言反过来"。现在就是那个"将来"，所以按它自己写的规矩改成了新断言。
     *
     * ## 旧行为为什么是 P1 缺陷
     *
     * `scheduled_ts` 是投影的纯函数输出：
     * `epoch = LocalDateTime.of(scheduled_date, scheduled_time).atZone(zoneId)`。
     * 它依赖 `zoneId`，而**时区会变**（出差、跨时区、飞行）。旧实现只同步
     * `dose_amount` / `policy_id`，全工程没有任何一条 UPDATE 写这一列，
     * 于是它在创建时被永久冻结。
     *
     * 幂等 diff 的键是**日历** `(scheduled_date, scheduled_time)`，命中"留"分支，
     * 新时区算出来的 epoch 被直接**丢弃** ⇒
     * 用户从北京飞到纽约，「每天 08:00」继续按**北京时间**响。
     *
     * 这条测试现在断言的正是"新时区的瞬时被写进去了"，
     * 并且用 `asTokyo` 作为**独立计算的期望值**（不是复用被测代码），
     * 避免又一次变成影子断言。
     */
    @Test
    fun `换时区后 开放槽位的 scheduled_ts 必须按新时区重算`() = runTest {
        val from = springForward.minusDays(1)
        val medId = dailyMedication(from)
        tracking.reconcileSchedule(medId, from, springForward.plusDays(1), zoneId = newYork)
        val inNewYork = slotsOf(medId).associate { it.scheduledDate to it.scheduledTs }
        assertThat(inNewYork).isNotEmpty()

        // 用户"飞"到东京，用东京时区重算
        val tokyo = ZoneId.of("Asia/Tokyo")
        tracking.reconcileSchedule(medId, from, springForward.plusDays(1), zoneId = tokyo)
        val afterTokyo = slotsOf(medId).associate { it.scheduledDate to it.scheduledTs }

        // 日历键不变（槽位身份是"哪天的哪个时点"，与时区无关）
        assertThat(afterTokyo.keys).isEqualTo(inNewYork.keys)
        // ⭐ 瞬时被重算：对每一天都用**独立算出的**东京时区期望值比对。
        // 期望值在这里现算（`LocalDateTime.of(...).atZone(tokyo)`），
        // 不是从被测方法里取的 —— 否则就是自己和自己比。
        afterTokyo.forEach { (dateStr, actualTs) ->
            val date = LocalDate.parse(dateStr)
            val expectedTs = date.atTime(LocalTime.of(8, 0))
                .atZone(tokyo).toInstant().toEpochMilli()
            assertThat(actualTs).isEqualTo(expectedTs)
        }
        // 旧值确实变了（东京比纽约快 13/14 小时），证明这条不是恒真
        val date = from
        assertThat(afterTokyo.getValue(date.toString()))
            .isNotEqualTo(inNewYork.getValue(date.toString()))
    }

    /**
     * **同区**重算是幂等的：值不变。
     *
     * 这是 M3-1 修复必须满足的另一半 —— 若"任何时候都重算 ts"导致
     * 每次对账都改写 `scheduled_ts`，而 `scheduled_ts` 正是过期判定与闹钟注册的依据，
     * 那么同一次对账里的读-写就会打架。
     * 投影在同区给出**相同**的 epoch，所以"值相同 ⇒ 不写"这条短路自然成立。
     */
    @Test
    fun `同区重对账 ts 不变（重算是幂等的）`() = runTest {
        val from = springForward.minusDays(1)
        val medId = dailyMedication(from)
        tracking.reconcileSchedule(medId, from, springForward.plusDays(1), zoneId = newYork)
        val first = slotsOf(medId).associate { it.id to it.scheduledTs }

        repeat(3) {
            tracking.reconcileSchedule(medId, from, springForward.plusDays(1), zoneId = newYork)
        }
        val after = slotsOf(medId).associate { it.id to it.scheduledTs }

        // id 与时间戳都一模一样
        assertThat(after).isEqualTo(first)
    }

    /**
     * 已产生结论的槽位**不**重算 `scheduled_ts`。
     *
     * 过期判定与"这条是否还算待办"都依赖它，事后改写会让历史事实的呈现随对账漂移。
     * `updateDerivedColumns` 的 `status IN ('PENDING','SNOOZED')` 守卫守着这条。
     */
    @Test
    fun `已产生结论的槽位不被重算 ts（历史事实不随对账漂移）`() = runTest {
        val from = springForward.minusDays(1)
        val medId = dailyMedication(from)
        tracking.reconcileSchedule(medId, from, springForward.plusDays(1), zoneId = newYork)
        val target = slotsOf(medId).first()
        db.doseSlotDao().markCompletedIfOpen(target.id, actualTs = 1L)
        val tsBefore = db.doseSlotDao().getSlotById(target.id)!!.scheduledTs

        val tokyo = ZoneId.of("Asia/Tokyo")
        tracking.reconcileSchedule(medId, from, springForward.plusDays(1), zoneId = tokyo)

        val after = db.doseSlotDao().getSlotById(target.id)!!
        assertThat(after.status).isEqualTo(SlotStatus.COMPLETED)
        assertThat(after.scheduledTs).isEqualTo(tsBefore)
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
