package com.mcxiaoke.carromed.core.alarm

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.DoseSlotEntity
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.entity.PolicyTimeEntity
import com.mcxiaoke.carromed.core.data.entity.SchedulePolicyEntity
import com.mcxiaoke.carromed.core.data.model.PolicyType
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import com.mcxiaoke.carromed.core.domain.service.DoseTrackingService
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.LocalDate

/**
 * 推迟后未回访的槽位如何结算（修 P1-2）。
 *
 * ## 缺陷本身
 *
 * 旧实现 `getStalePendingSlots` 的条件是 `status = 'PENDING'`，于是被推迟过的槽位
 * **永远不会**被结算成 `EXPIRED`。用户推迟后如果当天没再回来，这条槽位：
 *
 * - 一直挂在今日清单上标着「已推迟」，而闹钟早已不再排 ⇒ 永不兑现的待办
 * - 在统计里与 `PENDING` 一起算作 `pending`，而 `pending` **不进依从率分母**
 *   ⇒ 这次服药既不算已服也不算漏服，**凭空蒸发**
 *
 * 这与「已修的暂停不参与投影」是同构的陷阱：**清单上写着一条永远不会兑现的待办**。
 *
 * ## 为什么选「对账时结算」而不是「推迟时另排 EXPIRED 闹钟」
 *
 * 后者要引入第三种闹钟身份，而它的触发时刻同时依赖 `snoozeMinutes` 与结算窗口
 * 两个参数 —— 判重逻辑变复杂，且多一条会静默失效的依赖链路
 * （P0-2 的教训正是"提醒依赖单一链路，断了就静默漏提醒"）。
 * 结算线是「当地当日 0 点」（PLAN-EXPIRE-WINDOW-20260929），
 * 15 分钟的对账粒度对它毫无影响。
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class SnoozedSlotSettlementTest {

    private lateinit var db: AppDatabase
    private lateinit var tracking: DoseTrackingService

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** 相对"现在"造槽位，所以断言不受运行时刻影响 */
    private val today: LocalDate = LocalDate.now()

    /** 当地当日 0 点的纪元毫秒 —— 结算线（见 AlarmReconciler 第 1 步） */
    private fun startOfTodayMs(): Long =
        today.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        tracking = DoseTrackingService(db)
    }

    @After
    @Throws(IOException::class)
    fun tearDown() = db.close()

    /** 每日 08:00 的药，槽位已经排好；返回 medId */
    private suspend fun dailyMedication(name: String = "环孢素"): Long {
        val medId = db.medicationDao().insert(
            MedicationEntity(
                name = name, unit = "片", defaultDose = 1000, isStockTracked = true
            )
        )
        db.reminderSettingsDao().ensureDefaults(medId)
        db.schedulePolicyDao().savePolicyWithTimes(
            SchedulePolicyEntity(
                medicationId = medId, policyType = PolicyType.DAILY,
                intervalDays = 2, startDate = today.minusDays(3).toString()
            ),
            listOf(PolicyTimeEntity(policyId = 0L, timeOfDay = "08:00", doseAmount = 1000))
        )
        // 排到 today+1：部分用例要同时用到今天和明天两个槽位
        tracking.reconcileSchedule(medId, today.minusDays(3), today.plusDays(1))
        return medId
    }

    private suspend fun slotOn(medId: Long, date: LocalDate) =
        db.doseSlotDao().getSlotsForDate(date.toString()).first { it.medicationId == medId }

    /**
     * 把槽位置成「已推迟」（**fixture**，不是被测行为）。
     *
     * `todayStr` 传**槽位自己的计划日**：本测试要构造的是一个"推迟状态"，
     * 与"这个槽位今天能不能被推迟"无关 —— 后者由 `FutureSlotActionGuardTest` 覆盖。
     * 传真实今天会让 fixture 里 `today+1` 那种槽位被 SQL 守卫拦下，
     * 于是测试悄悄换成"没构造成功"的另一种场景。
     */
    private suspend fun forceSnooze(slot: DoseSlotEntity, until: Long) =
        db.doseSlotDao().snoozeSlot(slot.id, until, slot.scheduledDate)

    // ================================================================
    // 1. 主线：推迟后没回访 ⇒ 被结算
    // ================================================================

    @Test
    fun `推迟后一直没回访的槽位 会被结算为逾期`() = runTest {
        val medId = dailyMedication()
        val slot = slotOn(medId, today)

        // 推迟点落在昨日深夜（今日 0 点之前）—— 模拟"用户推迟后那天再没回来"。
        // 结算线是「当地当日 0 点」（PLAN-EXPIRE-WINDOW-20260929）：推迟点跨过
        // 0 点才定案；推迟点若还在今天（哪怕早已过去），今天全天不结算。
        forceSnooze(slot, startOfTodayMs() - 3600_000L)
        assertThat(db.doseSlotDao().getSlotById(slot.id)!!.status).isEqualTo(SlotStatus.SNOOZED)

        reconcil()

        val after = db.doseSlotDao().getSlotById(slot.id)!!
        assertThat(after.status).isEqualTo(SlotStatus.EXPIRED)
        // 结算要清干净，不留过期的推迟时刻
        assertThat(after.snoozeUntilTs).isNull()
    }

    @Test
    fun `推迟时间还没到的槽位 不得被结算`() = runTest {
        val medId = dailyMedication()
        val slot = slotOn(medId, today)

        forceSnooze(slot, System.currentTimeMillis() + 20 * 60_000L)
        reconcil()

        // 仍在 SNOOZED，且推迟时刻**原样保留**（被读到就说明还是活的）
        val after = db.doseSlotDao().getSlotById(slot.id)!!
        assertThat(after.status).isEqualTo(SlotStatus.SNOOZED)
        assertThat(after.snoozeUntilTs).isNotNull()
    }

    /**
     * 关键回归：**SNOOZED 的基准是 `snooze_until_ts`，不是 `scheduled_ts`**。
     *
     * 这条药的计划时间是 30 天前（槽位早已过原定时间），但用户刚推迟到 10 分钟后 ——
     * 此时把它算成逾期是冤枉用户。这正是方案 (a) 相对"照旧按原计划时间算"的差别。
     */
    @Test
    fun `刚推迟过的槽位 即使原计划时间早已过去也不判逾期`() = runTest {
        val medId = dailyMedication()
        val oldSlot = slotOn(medId, today.minusDays(2))
        assertThat(oldSlot.scheduledTs).isLessThan(System.currentTimeMillis() - 3600_000L)

        forceSnooze(oldSlot, System.currentTimeMillis() + 10 * 60_000L)
        reconcil()

        assertThat(db.doseSlotDao().getSlotById(oldSlot.id)!!.status).isEqualTo(SlotStatus.SNOOZED)
    }

    // ================================================================
    // 2. 不能因此误伤：PENDING 路径与既成事实
    // ================================================================

    @Test
    fun `PENDING 槽位仍按原计划时间结算 未受本次改动影响`() = runTest {
        val medId = dailyMedication()
        val old = slotOn(medId, today.minusDays(2))
        assertThat(old.status).isEqualTo(SlotStatus.PENDING)

        reconcil()

        assertThat(db.doseSlotDao().getSlotById(old.id)!!.status).isEqualTo(SlotStatus.EXPIRED)
    }

    @Test
    fun `推迟窗口内打卡 槽位转已服且不再被结算`() = runTest {
        val medId = dailyMedication()
        val slot = slotOn(medId, today)
        forceSnooze(slot, System.currentTimeMillis() + 5 * 60_000L)

        assertThat(tracking.takeDose(slotId = slot.id, note = "推迟后补打卡")).isTrue()
        assertThat(db.doseSlotDao().getSlotById(slot.id)!!.status).isEqualTo(SlotStatus.COMPLETED)

        // 连跑三次对账，已完成的是既成事实，不许被改成逾期
        repeat(3) { reconcil() }
        assertThat(db.doseSlotDao().getSlotById(slot.id)!!.status).isEqualTo(SlotStatus.COMPLETED)
    }

    /**
     * 打卡 / 跳过路径**必须保留** `snooze_until_ts` ——
     * "用户推迟过这件事"是历史的一部分，只有结算才清。
     *
     * 这条是 [DoseSlotDao.markExpired] 单独存在（而不是给 `updateStatus` 加参数）的理由。
     */
    @Test
    fun `结算之外 不许任何路径清掉 snooze_until_ts`() = runTest {
        val medId = dailyMedication()
        // ⚠️ 两个槽位都必须落在**可表态**范围（过去 / 今天）：未来槽位现在会被
        // `takeDose` / `skipDose` 的日期守卫拦下，那时这条测试会退化成
        // "两个动作根本没执行、snooze_until_ts 当然没被清"的恒真断言。
        // 返回值断言正是防这种退化的那道闸。
        val a = slotOn(medId, today.minusDays(1))
        val b = slotOn(medId, today)
        val far = System.currentTimeMillis() + 30 * 60_000L

        forceSnooze(a, far)
        forceSnooze(b, far)
        assertThat(tracking.takeDose(slotId = a.id)).isTrue()
        assertThat(tracking.skipDose(slotId = b.id, reason = "不吃了")).isTrue()

        assertThat(db.doseSlotDao().getSlotById(a.id)!!.snoozeUntilTs).isEqualTo(far)
        assertThat(db.doseSlotDao().getSlotById(b.id)!!.snoozeUntilTs).isEqualTo(far)
    }

    // ================================================================
    // 3. 脏数据防御
    // ================================================================

    /**
     * `SNOOZED` 但 `snooze_until_ts` 为 NULL 的行（正常路径写不出来，只有脏数据才会）。
     *
     * SQL 里显式写了 `snooze_until_ts IS NOT NULL`：SQL 三值逻辑下 `NULL < x` 求值为
     * NULL 而非真，本来就不会命中，但依赖"NULL 比较恒不为真"这种隐式行为很脆弱。
     */
    /** 硬造脏数据。**不用**在生产 DAO 里加测试后门 —— 实体上根本没有"清空推迟时刻"这个业务操作。 */
    private fun clearSnoozeUntil(slotId: Long) {
        (db as androidx.room.RoomDatabase).openHelper.writableDatabase.execSQL(
            "UPDATE dose_slots SET snooze_until_ts = NULL WHERE id = ?",
            arrayOf<Any>(slotId)
        )
    }

    private suspend fun reconcil() = AlarmReconciler.rescheduleAll(context, db)

    @Test
    fun `SNOOZED 但没有推迟时刻的脏行 不会被结算 也不崩`() = runTest {
        val medId = dailyMedication()
        val slot = slotOn(medId, today)
        forceSnooze(slot, System.currentTimeMillis())
        // 硬造脏数据：置 SNOOZED 但清掉推迟时刻
        clearSnoozeUntil(slot.id)
        assertThat(db.doseSlotDao().getSlotById(slot.id)!!.snoozeUntilTs).isNull()

        reconcil()

        // 保持 SNOOZED：既没有被误判成逾期，也没有把空值塞进比较
        assertThat(db.doseSlotDao().getSlotById(slot.id)!!.status).isEqualTo(SlotStatus.SNOOZED)
    }

    @Test
    fun `连续多次对账 结算结果稳定不反复横跳`() = runTest {
        val medId = dailyMedication()
        // 推迟点落在昨日深夜：跨过结算线 ⇒ 第一轮对账定案，其后必须稳定
        forceSnooze(slotOn(medId, today), startOfTodayMs() - 3600_000L)
        reconcil()
        val first = db.doseSlotDao().getAllSlots()
            .filter { it.medicationId == medId }
            .map { it.id to it.status }

        repeat(5) { reconcil() }
        val again = db.doseSlotDao().getAllSlots()
            .filter { it.medicationId == medId }
            .map { it.id to it.status }

        assertThat(again).isEqualTo(first)
        assertThat(again.any { it.second == SlotStatus.EXPIRED }).isTrue()
    }
}
