package com.mcxiaoke.carromed.core.domain.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.AppSettingEntity
import com.mcxiaoke.carromed.core.data.entity.DoseSlotEntity
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.entity.PolicyTimeEntity
import com.mcxiaoke.carromed.core.data.entity.ReminderSettingsEntity
import com.mcxiaoke.carromed.core.data.entity.SchedulePolicyEntity
import com.mcxiaoke.carromed.core.data.model.PolicyType
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.LocalDate

/**
 * 写路径隔离的**穷举式**字段保全测试（不变量 **I9**，治 P0-5 两种症状）。
 *
 * ## 这个模式为什么能防复发
 *
 * 哨兵构造 [fullSentinelMedication] 对**每一列显式赋值**，用 `42` 这类平凡值是不行的 ——
 * 将来给 `MedicationEntity` 加一列并给它默认值 `42`，而哨兵也是 `42`，
 * 那一列就被漏检了。所以哨兵必须用可辨识常量。
 *
 * 更关键的是：Kotlin 的**具名参数构造**会强制调用方为新加的列传值，
 * 而这个函数**必须**跟着改 —— 改不动就编译不过。
 * 于是"新增列忘了加断言"从「靠人记得」变成**编译期强制**。
 *
 * ## 两条症状都要覆盖
 *
 * | 症状 | 表现 |
 * | :--- | :--- |
 * | 漏字段（读-改-写丢数据） | 改个药名，`precautions` 被抹成空 |
 * | 串字段（写错列） | 改药名把 `is_archived` 一起置了 |
 *
 * 只断言"某几个字段没变"不够 —— **逐列**断言才拦得住串列。
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class FieldPreservationInvariantTest {

    private lateinit var db: AppDatabase
    private lateinit var admin: MedicationAdminService
    private lateinit var tracking: DoseTrackingService

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val today: LocalDate = LocalDate.of(2026, 9, 28)

    // ---------------- 哨兵常量：一律可辨识，不用平凡值 ----------------
    private object Sentinel {
        const val NAME = "⚠哨兵-药名"
        const val ALIAS = "⚠哨兵-别名"
        const val CATEGORY = "⚠哨兵-类别"
        const val FORM = "⚠哨兵-剂型"
        const val UNIT = "⚠哨兵-单位"
        const val COLOR = "#0BADC0"
        const val ICON = "⚠哨兵-图标"
        const val DESCRIPTION = "⚠哨兵-详细说明"
        const val NOTICE = "⚠哨兵-通知简述"
        const val EXPIRY = "2099-12-31"
        val PRECAUTIONS = listOf("⚠哨兵-注意事项甲", "⚠哨兵-注意事项乙")

        /** 毫单位。用一个既不是 0、也不是默认 1000、更不是 1.0 的值 */
        const val DEFAULT_DOSE = 1357          // = 1.357 片
        const val MIN_STOCK_ALERT = 24680      // = 24.68 片

        const val CREATED_AT = 1_500_000_000_001L
        const val UPDATED_AT = 1_500_000_000_002L

        const val KEY = "⚠哨兵-设置键"
        const val VALUE = "⚠哨兵-设置值"
        const val UPDATED_AT_SETTING = 1_500_000_000_003L

        // reminder_settings
        const val SNOOZE_MINUTES = 37
        const val ADVANCE_MINUTES = 19
// ⚠️ 必须取一个**已经过去**的日子。
        //
        // 哨兵曾用 "2099-06-01"，而暂停现在参与投影（SlotProjectionEngine 收 pausedUntil），
        // 于是**整个窗口的槽位都被压掉** ⇒ C5/C8 报 "expected not to be: null"，
        // 完全指不到"是我自己设的暂停"这个病根。
        // 取过去的日期既覆盖了"这一列被原样保留"的断言，又不影响投影。
        const val PAUSED_UNTIL = "2020-01-01"

        // schedule_policies
        const val POLICY_START = "2026-01-01"
        const val POLICY_END = "2099-12-31"
        const val INTERVAL_DAYS = 11
        const val CYCLE_ON = 22
        const val CYCLE_OFF = 9
        val DAYS_OF_WEEK: List<Int> = listOf(2, 4, 6)
        const val POLICY_VERSION = 42
        const val POLICY_CREATED = 1_500_000_000_004L
        const val POLICY_TIME = "07:07"
        const val POLICY_DOSE = 1111
        const val POLICY_LABEL = "⚠哨兵-时段"
    }

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

    /**
     * 每一列都显式给哨兵值的药品。
     *
     * ⚠️ **新增 `MedicationEntity` 的列时必须在此加一行**，否则编译不过 ——
     * 这正是这个模式的价值。漏了的话测试会因为"断言集合少一列"而放过。
     */
    private fun fullSentinelMedication(id: Long = 0L) = MedicationEntity(
        id = id,
        name = Sentinel.NAME,
        alias = Sentinel.ALIAS,
        category = Sentinel.CATEGORY,
        form = Sentinel.FORM,
        unit = Sentinel.UNIT,
        colorHex = Sentinel.COLOR,
        iconName = Sentinel.ICON,
        defaultDose = Sentinel.DEFAULT_DOSE,
        description = Sentinel.DESCRIPTION,
        precautions = Sentinel.PRECAUTIONS,
        noticeShort = Sentinel.NOTICE,
        minStockAlert = Sentinel.MIN_STOCK_ALERT,
        isStockTracked = true,
        expiryDate = Sentinel.EXPIRY,
        isArchived = true,
        createdAt = Sentinel.CREATED_AT,
        updatedAt = Sentinel.UPDATED_AT
    )

    /** 同上，`reminder_settings` 每一列都给哨兵值 */
    private fun fullSentinelReminderSettings(medId: Long) = ReminderSettingsEntity(
        medicationId = medId,
        isCriticalReminder = true,
        snoozeMinutes = Sentinel.SNOOZE_MINUTES,
        advanceMinutes = Sentinel.ADVANCE_MINUTES,
        pausedUntil = Sentinel.PAUSED_UNTIL
    )

    /**
     * 哨兵药品的提醒计划。同样每一列显式赋值。
     *
     * ⚠️ `SchedulePolicyEntity` 新增列时必须在此加一行。
     */
    private fun fullSentinelPolicy(medId: Long) = SchedulePolicyEntity(
        id = 0L,
        medicationId = medId,
        policyType = PolicyType.INTERVAL,
        intervalDays = Sentinel.INTERVAL_DAYS,
        daysOfWeek = Sentinel.DAYS_OF_WEEK,
        cycleOnDays = Sentinel.CYCLE_ON,
        cycleOffDays = Sentinel.CYCLE_OFF,
        startDate = Sentinel.POLICY_START,
        endDate = Sentinel.POLICY_END,
        isActive = true,
        version = Sentinel.POLICY_VERSION,
        createdAt = Sentinel.POLICY_CREATED
    )

    /** ⚠️ `PolicyTimeEntity` 新增列时必须在此加一行 */
    private fun fullSentinelPolicyTimes() = listOf(
        PolicyTimeEntity(
            id = 0L,
            policyId = 0L,
            timeOfDay = Sentinel.POLICY_TIME,
            doseAmount = Sentinel.POLICY_DOSE,
            label = Sentinel.POLICY_LABEL,
            sortOrder = 7
        )
    )

    /** 一次装好：哨兵药品 + 哨兵提醒设置 + 哨兵计划 + 哨兵库存 + 哨兵 app setting */
    private suspend fun seedFullSentinelWorld(): Long {
        val medId = db.medicationDao().insert(fullSentinelMedication())
        db.reminderSettingsDao().insert(fullSentinelReminderSettings(medId))
        db.schedulePolicyDao().savePolicyWithTimes(fullSentinelPolicy(medId), fullSentinelPolicyTimes())
        tracking.refillStock(medId, addedAmount = 99f, note = "哨兵建账")
        db.appSettingDao().insertAll(
            listOf(AppSettingEntity(Sentinel.KEY, Sentinel.VALUE, Sentinel.UPDATED_AT_SETTING))
        )
        return medId
    }

    /**
     * 排一个**一定落在窗口内**的槽位 id。
     *
     * 哨兵计划故意用 `INTERVAL/11`（非默认值），于是"今天"未必是排班日 ——
     * `2026-01-01` 到 `2026-09-28` 相距 260 天，`260 % 11 ≠ 0`。
     * 所以这里排一个足够宽的窗口再取第一条，而不是假设 `getSlotsForDate(today)` 非空。
     *
     * （这个坑本轮已经踩过一次：早先用 `INTERVAL/3` + 起始日 −5 天，
     *   `5 % 3 ≠ 0` ⇒ 槽位列表为空 ⇒ 测试报 "List is empty"，完全指不到病根。）
     *
     * ⚠️ 仅适用于**未归档**药品：P2#4 后归档药按"无计划"投影，
     * reconcileSchedule 不会为它物化任何槽位（需要槽位的 C5 因此改为直插）。
     */
    private suspend fun seedAnySlot(medId: Long): Long {
        tracking.reconcileSchedule(medId, today, today.plusDays(90))
        val slot = db.doseSlotDao().getAllSlots()
            .filter { it.medicationId == medId }
            .minByOrNull { it.scheduledTs }
        assertThat(slot).isNotNull()
        return slot!!.id
    }

    // ==================================================================
    // 断言辅助：逐列比对
    // ==================================================================

    /**
     * 断言药品档案的**每一列**都还是哨兵值。
     *
     * ⚠️ 加列时这里也要加一行 —— 与 [fullSentinelMedication] 配对。
     *
     * @param archived 哨兵药的归档位期望值。哨兵默认 `true`（可辨识非默认值）；
     *   C8 因 P2#4（归档药不再物化排班）而必须先取消归档，按 `false` 断言。
     */
    private suspend fun assertMedicationUnchanged(medId: Long, archived: Boolean = true) {
        val m = db.medicationDao().getMedicationById(medId)!!
        assertThat(m.name).isEqualTo(Sentinel.NAME)
        assertThat(m.alias).isEqualTo(Sentinel.ALIAS)
        assertThat(m.category).isEqualTo(Sentinel.CATEGORY)
        assertThat(m.form).isEqualTo(Sentinel.FORM)
        assertThat(m.unit).isEqualTo(Sentinel.UNIT)
        assertThat(m.colorHex).isEqualTo(Sentinel.COLOR)
        assertThat(m.iconName).isEqualTo(Sentinel.ICON)
        assertThat(m.defaultDose).isEqualTo(Sentinel.DEFAULT_DOSE)
        assertThat(m.description).isEqualTo(Sentinel.DESCRIPTION)
        assertThat(m.precautions).isEqualTo(Sentinel.PRECAUTIONS)
        assertThat(m.noticeShort).isEqualTo(Sentinel.NOTICE)
        assertThat(m.minStockAlert).isEqualTo(Sentinel.MIN_STOCK_ALERT)
        assertThat(m.isStockTracked).isTrue()
        assertThat(m.expiryDate).isEqualTo(Sentinel.EXPIRY)
        assertThat(m.isArchived).isEqualTo(archived)
        assertThat(m.createdAt).isEqualTo(Sentinel.CREATED_AT)
        assertThat(m.updatedAt).isEqualTo(Sentinel.UPDATED_AT)
    }

    /**
     * 断言提醒运行态的**每一列**都还是哨兵值。
     *
     * ⚠️ 加列时这里也要加一行。
     */
    private suspend fun assertReminderSettingsUnchanged(medId: Long) {
        val s = db.reminderSettingsDao().getByMedicationId(medId)!!
        assertThat(s.isCriticalReminder).isTrue()
        assertThat(s.snoozeMinutes).isEqualTo(Sentinel.SNOOZE_MINUTES)
        assertThat(s.advanceMinutes).isEqualTo(Sentinel.ADVANCE_MINUTES)
        assertThat(s.pausedUntil).isEqualTo(Sentinel.PAUSED_UNTIL)
    }

    /**
     * 断言计划与时点**每一列**都还是哨兵值。
     *
     * ⚠️ `SchedulePolicyEntity` / `PolicyTimeEntity` 加列时这里也要加一行。
     */
    private suspend fun assertPolicyUnchanged(medId: Long) {
        val p = db.schedulePolicyDao().getActivePolicyForMedication(medId)!!
        assertThat(p.policyType).isEqualTo(PolicyType.INTERVAL)
        assertThat(p.intervalDays).isEqualTo(Sentinel.INTERVAL_DAYS)
        assertThat(p.daysOfWeek).isEqualTo(Sentinel.DAYS_OF_WEEK)
        assertThat(p.cycleOnDays).isEqualTo(Sentinel.CYCLE_ON)
        assertThat(p.cycleOffDays).isEqualTo(Sentinel.CYCLE_OFF)
        assertThat(p.startDate).isEqualTo(Sentinel.POLICY_START)
        assertThat(p.endDate).isEqualTo(Sentinel.POLICY_END)
        assertThat(p.isActive).isTrue()
        assertThat(p.version).isEqualTo(Sentinel.POLICY_VERSION)
        assertThat(p.createdAt).isEqualTo(Sentinel.POLICY_CREATED)

        val t = db.schedulePolicyDao().getTimesForPolicy(p.id).single()
        assertThat(t.timeOfDay).isEqualTo(Sentinel.POLICY_TIME)
        assertThat(t.doseAmount).isEqualTo(Sentinel.POLICY_DOSE)
        assertThat(t.label).isEqualTo(Sentinel.POLICY_LABEL)
        assertThat(t.sortOrder).isEqualTo(7)
    }

    private suspend fun assertAppSettingUnchanged() {
        val s = db.appSettingDao().getSetting(Sentinel.KEY)
        assertThat(s).isNotNull()
        assertThat(s!!.value).isEqualTo(Sentinel.VALUE)
        assertThat(s.updatedAt).isEqualTo(Sentinel.UPDATED_AT_SETTING)
    }

    /**
     * 台账余额（毫单位）。`DoseTrackingService.balanceOf` 是私有的，
     * 这里走 `getAllBalances` 复算 —— 顺带也验证了 I1 的派生定义。
     */
    private suspend fun balanceOf(medId: Long): Int =
        db.inventoryTransactionDao().getAllBalances()
            .firstOrNull { it.medicationId == medId }?.balance ?: 0

    // ==================================================================
    // 6 条写命令：每条只允许动自己的列
    // ==================================================================

    @Test
    fun `C1 档案编辑不触碰任何非档案列`() = runTest {
        val medId = seedFullSentinelWorld()

        admin.saveProfile(
            MedicationAdminService.ProfileDraft(
                medId = medId,
                name = "新药名",
                alias = null,                       // ⭐ 唯一带 patch 语义的列
                // 下面全部原样传回哨兵值。`ProfileDraft` 对档案列是**全量替换**语义，
                // 真实编辑页（`AddEditMedicationViewModel`）也是加载完整档案再整体提交的，
                // 所以传局部草稿不是合法的调用方式，不能拿来当"字段保全"的证据。
                category = Sentinel.CATEGORY,
                form = Sentinel.FORM,
                colorHex = Sentinel.COLOR,
                description = Sentinel.DESCRIPTION,
                precautions = Sentinel.PRECAUTIONS,
                noticeShort = Sentinel.NOTICE,
                expiryDate = Sentinel.EXPIRY,
                unit = "ml",
                defaultDose = 2.5f,
                minStockAlert = 9f
            )
        )

        // 档案自有列按草稿走
        val after = db.medicationDao().getMedicationById(medId)!!
        assertThat(after.name).isEqualTo("新药名")
        assertThat(after.unit).isEqualTo("ml")
        assertThat(after.defaultDose).isEqualTo(2500)
        assertThat(after.minStockAlert).isEqualTo(9000)
        // alias 是 null（不修改），必须沿用哨兵值 —— 一次部分提交不该抹掉别名
        assertThat(after.alias).isEqualTo(Sentinel.ALIAS)

        // ★ 档案**非**自有列逐列不变
        assertThat(after.category).isEqualTo(Sentinel.CATEGORY)
        assertThat(after.form).isEqualTo(Sentinel.FORM)
        assertThat(after.colorHex).isEqualTo(Sentinel.COLOR)
        assertThat(after.iconName).isEqualTo(Sentinel.ICON)
        assertThat(after.description).isEqualTo(Sentinel.DESCRIPTION)
        assertThat(after.precautions).isEqualTo(Sentinel.PRECAUTIONS)
        assertThat(after.noticeShort).isEqualTo(Sentinel.NOTICE)
        assertThat(after.expiryDate).isEqualTo(Sentinel.EXPIRY)
        // 最容易被"顺手"重置的三个状态位
        assertThat(after.isStockTracked).isTrue()
        assertThat(after.isArchived).isTrue()
        assertThat(after.createdAt).isEqualTo(Sentinel.CREATED_AT)

        // ★ 跨表：拆表后提醒运行态在别的表，结构上就碰不到
        assertReminderSettingsUnchanged(medId)
        assertPolicyUnchanged(medId)
        assertAppSettingUnchanged()
    }

    @Test
    fun `C2 提醒行为保存不触碰档案与暂停状态`() = runTest {
        val medId = seedFullSentinelWorld()

        admin.saveReminderBehavior(
            MedicationAdminService.ReminderBehaviorDraft(
                medId = medId,
                isCriticalReminder = false,
                snoozeMinutes = 5,
                advanceMinutes = 6
            )
        )

        // 只动了自己那三列
        val s = db.reminderSettingsDao().getByMedicationId(medId)!!
        assertThat(s.isCriticalReminder).isFalse()
        assertThat(s.snoozeMinutes).isEqualTo(5)
        assertThat(s.advanceMinutes).isEqualTo(6)
        // ★ 暂停状态不在这条命令的射程内
        assertThat(s.pausedUntil).isEqualTo(Sentinel.PAUSED_UNTIL)

        // ★ 档案一列都不动
        assertMedicationUnchanged(medId)
        assertPolicyUnchanged(medId)
        assertAppSettingUnchanged()
    }

    @Test
    fun `C3 暂停与恢复不触碰提醒行为三列`() = runTest {
        val medId = seedFullSentinelWorld()

        admin.setPausedUntil(medId, "2099-01-01")
        var s = db.reminderSettingsDao().getByMedicationId(medId)!!
        assertThat(s.pausedUntil).isEqualTo("2099-01-01")
        assertThat(s.isCriticalReminder).isTrue()
        assertThat(s.snoozeMinutes).isEqualTo(Sentinel.SNOOZE_MINUTES)
        assertThat(s.advanceMinutes).isEqualTo(Sentinel.ADVANCE_MINUTES)

        admin.resume(medId)
        s = db.reminderSettingsDao().getByMedicationId(medId)!!
        assertThat(s.pausedUntil).isNull()
        // ★ 恢复只清 paused_until，另外三列必须原封不动
        assertThat(s.isCriticalReminder).isTrue()
        assertThat(s.snoozeMinutes).isEqualTo(Sentinel.SNOOZE_MINUTES)
        assertThat(s.advanceMinutes).isEqualTo(Sentinel.ADVANCE_MINUTES)

        assertMedicationUnchanged(medId)
        assertPolicyUnchanged(medId)
        assertAppSettingUnchanged()
    }

    @Test
    fun `C4 服用计划保存不触碰档案与提醒行为`() = runTest {
        val medId = seedFullSentinelWorld()

        admin.saveReminderPolicy(
            medicationId = medId,
            draft = MedicationAdminService.PolicyDraft(
                policyType = PolicyType.DAILY,
                startDate = today.toString(),
                times = listOf(MedicationAdminService.TimeDraft("09:09", 1f, "新时段"))
            )
        )

        // 计划确实换了
        val p = db.schedulePolicyDao().getActivePolicyForMedication(medId)!!
        assertThat(p.policyType).isEqualTo(PolicyType.DAILY)
        assertThat(db.schedulePolicyDao().getTimesForPolicy(p.id).single().timeOfDay)
            .isEqualTo("09:09")

        // ★ 档案与提醒运行态一列都不动
        assertMedicationUnchanged(medId)
        assertReminderSettingsUnchanged(medId)
        assertAppSettingUnchanged()
    }

    @Test
    fun `C5 打卡不触碰档案 提醒设置与计划`() = runTest {
        val medId = seedFullSentinelWorld()
        // P2#4 后归档药按"无计划"投影，reconcile 不再为它物化槽位，
        // 因此直接落一条槽位供打卡（顺带覆盖：归档药的槽位打卡同样不碰档案）。
        // 直插不会触碰 updated_at / is_archived 等任何档案列。
        val policyId = db.schedulePolicyDao().getActivePolicyForMedication(medId)!!.id
        val slotId = db.doseSlotDao().insert(
            DoseSlotEntity(
                medicationId = medId,
                policyId = policyId,
                scheduledDate = Sentinel.POLICY_START,
                scheduledTime = Sentinel.POLICY_TIME,
                scheduledTs = 1_770_000_000_000L,
                doseAmount = Sentinel.POLICY_DOSE,
                status = SlotStatus.PENDING
            )
        )
        val planBefore = db.schedulePolicyDao().getActivePolicyForMedication(medId)!!.version

        assertThat(tracking.takeDose(slotId = slotId, note = "哨兵打卡")).isTrue()

        // ★ 打卡只该动 dose_slots / dose_records / inventory_transactions
        assertMedicationUnchanged(medId)
        assertReminderSettingsUnchanged(medId)
        // 计划版本号也不该被动 —— 改计划才递增
        assertThat(db.schedulePolicyDao().getActivePolicyForMedication(medId)!!.version)
            .isEqualTo(planBefore)
        assertAppSettingUnchanged()
    }

    @Test
    fun `C6 库存追踪开关不触碰档案其余列与提醒设置`() = runTest {
        val medId = seedFullSentinelWorld()
        val stockBefore = balanceOf(medId)

        tracking.setStockTracking(medId, enabled = false)

        assertThat(db.medicationDao().getMedicationById(medId)!!.isStockTracked).isFalse()
        // ★ 余额是流水聚合出来的，关闭追踪不写流水 ⇒ 余额不变
        assertThat(balanceOf(medId)).isEqualTo(stockBefore)

        // ★ 档案其余列 + 提醒设置 + 计划都不动
        val m = db.medicationDao().getMedicationById(medId)!!
        assertThat(m.name).isEqualTo(Sentinel.NAME)
        assertThat(m.alias).isEqualTo(Sentinel.ALIAS)
        assertThat(m.precautions).isEqualTo(Sentinel.PRECAUTIONS)
        assertThat(m.isArchived).isTrue()
        assertThat(m.createdAt).isEqualTo(Sentinel.CREATED_AT)
        assertReminderSettingsUnchanged(medId)
        assertAppSettingUnchanged()
    }

    @Test
    fun `C7 建档流水不触碰提醒设置与档案`() = runTest {
        val medId = seedFullSentinelWorld()
        val before = db.inventoryTransactionDao().getAllTransactions().map { it.id }

        // 哨兵药已有 99 的账面 ⇒ 这个调用按设计是 no-op（已在建账则不重复写）
        admin.ensureInitialStockLedger(medId, stock = 12f)
        assertThat(db.inventoryTransactionDao().getAllTransactions().map { it.id })
            .isEqualTo(before)

        // 真正该写的场景：账面为 0 的新药，必须补一条流水
        val fresh = db.medicationDao().insert(fullSentinelMedication())
        db.reminderSettingsDao().insert(fullSentinelReminderSettings(fresh))
        admin.ensureInitialStockLedger(fresh, stock = 12f)
        assertThat(db.inventoryTransactionDao().getAllTransactions().map { it.id }.size)
            .isGreaterThan(before.size)

        // ★ 两条路径都不该动档案 / 提醒设置 / 计划 / app setting
        assertMedicationUnchanged(medId)
        assertReminderSettingsUnchanged(medId)
        assertPolicyUnchanged(medId)
        assertAppSettingUnchanged()
    }

    // ==================================================================
    // 对账：只该动 dose_slots
    // ==================================================================

    @Test
    fun `C8 对账只动槽位表 档案提醒计划设置全部原封不动`() = runTest {
        val medId = seedFullSentinelWorld()
        // P2#4 后归档药按"无计划"投影，对账会清掉它的开放槽位——C8 守的是
        // "对账不外溢"，需要一个有排班的药，故先取消归档再验证。
        // isArchived 列自身的保全由 C1/C6/C7 覆盖；这里按未归档口径断言，
        // 并显式传哨兵 updated_at，保证 updated_at 列仍是逐列可比的。
        db.medicationDao().updateArchiveStatus(medId, isArchived = false, updatedAt = Sentinel.UPDATED_AT)
        val slotId = seedAnySlot(medId)
        // 再跑三次，验证"多次对账"同样不外溢
        repeat(3) { tracking.reconcileSchedule(medId, today, today.plusDays(90)) }
        // 幂等：首条槽位仍在
        assertThat(db.doseSlotDao().getSlotById(slotId)).isNotNull()

        assertMedicationUnchanged(medId, archived = false)
        assertReminderSettingsUnchanged(medId)
        assertPolicyUnchanged(medId)
        assertAppSettingUnchanged()
    }
}
