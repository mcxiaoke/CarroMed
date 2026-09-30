package com.mcxiaoke.carromed.core.data.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.InventoryTransactionEntity
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.model.TransactionType
import com.mcxiaoke.carromed.core.domain.service.MedicationAdminService
import com.mcxiaoke.carromed.core.domain.model.Dose
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.LocalDate

/**
 * 提醒运行态与药品档案的**隔离性**测试（不变量 I9，A2 落地）。
 *
 * ## 这组测试守的是什么
 *
 * P0-5 有两种症状，A2 之后只剩一种可能：
 *
 * - **漏传型**：档案页的整行写入把提醒配置抹掉；
 * - **被迫重传型**：提醒页为了少写几行改用 20 列宽命令，把档案覆盖回去。
 *
 * 拆表之后这两者都**在结构上不可能**发生 —— 但那正是最需要测试的时刻：
 * 结构性的保证最容易在后续迭代里被"顺手加个参数"破坏，而破坏时**不报错**。
 * 所以这里逐条断言"写 A 不动 B"，让任何回归立刻变红。
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class ReminderSettingsIsolationTest {

    private lateinit var db: AppDatabase
    private lateinit var service: MedicationAdminService

    /**
     * "远期未来"的暂停截止日。相对今天取值 —— 硬编码 `2026-12-31` 的版本
     * 会在跨过那个日子后整体假红（AGENTS §3「下午全绿早上全红」）。
     */
    private val pausedUntilDate: String = LocalDate.now().plusDays(90).toString()

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        service = MedicationAdminService(db)
    }

    @After
    @Throws(IOException::class)
    fun tearDown() = db.close()

    private suspend fun newMed(name: String = "测试药", stock: Int = 0, alert: Int = 5000): Long {
        val id = db.medicationDao().insert(
            MedicationEntity(name = name, unit = "片", minStockAlert = alert, isStockTracked = true)
        )
        db.reminderSettingsDao().ensureDefaults(id)
        if (stock != 0) {
            db.inventoryTransactionDao().insert(
                InventoryTransactionEntity(
                    medicationId = id, changeAmount = stock, balanceAfter = stock,
                    txType = TransactionType.CALIBRATION_ADJUST, note = "建档"
                )
            )
        }
        return id
    }

    // ---------------- 档案编辑不碰提醒运行态 ----------------

    @Test
    fun `档案编辑完全不影响提醒运行态`() = runTest {
        val id = newMed()
        service.saveReminderBehavior(
            MedicationAdminService.ReminderBehaviorDraft(
                medId = id, isCriticalReminder = true, snoozeMinutes = 25, advanceMinutes = 15
            )
        )
        service.setPausedUntil(id, pausedUntilDate)

        // 档案页改一堆字段
        service.saveProfile(
            MedicationAdminService.ProfileDraft(
                medId = id, name = "改名后", alias = "新别名", unit = "粒",
                precautions = listOf("新注意事项"), noticeShort = "新简述",
                expiryDate = "2028-01-01", minStockAlert = 20f
            )
        )

        val rs = db.reminderSettingsDao().getByMedicationId(id)!!
        assertThat(rs.isCriticalReminder).isTrue()
        assertThat(rs.snoozeMinutes).isEqualTo(25)
        assertThat(rs.advanceMinutes).isEqualTo(15)
        assertThat(rs.pausedUntil).isEqualTo(pausedUntilDate)
    }

    @Test
    fun `档案编辑不影响库存台账与预警线以外的档案字段`() = runTest {
        val id = newMed(stock = 30000)
        val before = db.medicationDao().getMedicationById(id)!!

        service.saveProfile(
            MedicationAdminService.ProfileDraft(
                medId = id, name = "改名后", unit = "粒", minStockAlert = 3f
            )
        )

        val after = db.medicationDao().getMedicationById(id)!!
        assertThat(after.createdAt).isEqualTo(before.createdAt)
        assertThat(after.isArchived).isFalse()
        assertThat(after.isStockTracked).isTrue()
        assertThat(db.medicationDao().getOverviewById(id)?.stock).isEqualTo(30f)
        assertThat(Dose(after.minStockAlert).asFloat).isEqualTo(3f)
    }

    // ---------------- 提醒行为不碰档案与库存 ----------------

    @Test
    fun `保存提醒行为不碰档案与库存`() = runTest {
        val id = newMed(stock = 8000)
        val before = db.medicationDao().getMedicationById(id)!!

        service.saveReminderBehavior(
            MedicationAdminService.ReminderBehaviorDraft(
                medId = id, isCriticalReminder = true, snoozeMinutes = 45, advanceMinutes = 30
            )
        )

        val after = db.medicationDao().getMedicationById(id)!!
        assertThat(after.name).isEqualTo(before.name)
        assertThat(after.unit).isEqualTo(before.unit)
        assertThat(after.minStockAlert).isEqualTo(before.minStockAlert)
        assertThat(after.createdAt).isEqualTo(before.createdAt)
        assertThat(db.medicationDao().getOverviewById(id)?.stock).isEqualTo(8f)
    }

    @Test
    fun `保存提醒行为不碰暂停状态`() = runTest {
        val id = newMed()
        service.setPausedUntil(id, pausedUntilDate)

        service.saveReminderBehavior(
            MedicationAdminService.ReminderBehaviorDraft(
                medId = id, isCriticalReminder = true, snoozeMinutes = 10, advanceMinutes = 5
            )
        )

        assertThat(db.reminderSettingsDao().getByMedicationId(id)?.pausedUntil).isEqualTo(pausedUntilDate)
    }

    // ---------------- 暂停不碰提醒行为 ----------------

    @Test
    fun `暂停与恢复都不碰提醒行为三列`() = runTest {
        val id = newMed()
        service.saveReminderBehavior(
            MedicationAdminService.ReminderBehaviorDraft(
                medId = id, isCriticalReminder = true, snoozeMinutes = 20, advanceMinutes = 10
            )
        )

        service.setPausedUntil(id, "")
        assertPaused(id, true)
        var rs = db.reminderSettingsDao().getByMedicationId(id)!!
        assertThat(rs.snoozeMinutes).isEqualTo(20)
        assertThat(rs.advanceMinutes).isEqualTo(10)
        assertThat(rs.isCriticalReminder).isTrue()

        service.setPausedUntil(id, "2026-10-15")
        service.resume(id)
        assertPaused(id, false)
        rs = db.reminderSettingsDao().getByMedicationId(id)!!
        assertThat(rs.snoozeMinutes).isEqualTo(20)
        assertThat(rs.advanceMinutes).isEqualTo(10)
        assertThat(rs.isCriticalReminder).isTrue()
    }

    private suspend fun assertPaused(id: Long, expected: Boolean) {
        val rs = db.reminderSettingsDao().getByMedicationId(id)!!
        assertThat(rs.isPausedOn(LocalDate.now())).isEqualTo(expected)
    }

    // ---------------- 新建药品必须带出默认提醒设置 ----------------

    @Test
    fun `新建药品自动建出默认提醒设置行`() = runTest {
        val id = service.saveProfile(MedicationAdminService.ProfileDraft(name = "新药"))
        val rs = db.reminderSettingsDao().getByMedicationId(id)
        // 这条如果缺了，提醒设置页第一次保存会 UPDATE 命中 0 行，
        // 用户改了设置点保存却毫无变化且不报错 —— 静默失败
        assertThat(rs).isNotNull()
        assertThat(rs!!.isCriticalReminder).isFalse()
        assertThat(rs.snoozeMinutes).isEqualTo(0)
        assertThat(rs.advanceMinutes).isEqualTo(0)
        assertThat(rs.pausedUntil).isNull()
    }

    @Test
    fun `ensureDefaults 幂等 不会覆盖已有配置`() = runTest {
        val id = newMed()
        service.saveReminderBehavior(
            MedicationAdminService.ReminderBehaviorDraft(
                medId = id, isCriticalReminder = true, snoozeMinutes = 33
            )
        )
        // 反复调用不应把用户配置重置
        repeat(3) { db.reminderSettingsDao().ensureDefaults(id) }
        val rs = db.reminderSettingsDao().getByMedicationId(id)!!
        assertThat(rs.snoozeMinutes).isEqualTo(33)
        assertThat(rs.isCriticalReminder).isTrue()
    }

    @Test
    fun `对尚无设置行的药品保存提醒行为会先补默认行`() = runTest {
        // 模拟"老数据没有 reminder_settings 行"（如恢复旧备份）
        val id = db.medicationDao().insert(MedicationEntity(name = "老药"))
        assertThat(db.reminderSettingsDao().getByMedicationId(id)).isNull()

        service.saveReminderBehavior(
            MedicationAdminService.ReminderBehaviorDraft(medId = id, isCriticalReminder = true)
        )

        val rs = db.reminderSettingsDao().getByMedicationId(id)
        assertThat(rs).isNotNull()
        assertThat(rs!!.isCriticalReminder).isTrue()
    }

    // ---------------- 读模型：一次 JOIN 拿全 ----------------

    @Test
    fun `Overview 同时带出档案 余额与提醒运行态`() = runTest {
        val id = newMed(stock = 12000, alert = 3000)
        service.saveReminderBehavior(
            MedicationAdminService.ReminderBehaviorDraft(
                medId = id, isCriticalReminder = true, snoozeMinutes = 15, advanceMinutes = 5
            )
        )
        service.setPausedUntil(id, pausedUntilDate)

        val o = db.medicationDao().getOverviewById(id)!!
        assertThat(o.name).isEqualTo("测试药")
        assertThat(o.stock).isEqualTo(12f)
        assertThat(o.minStockAlert).isEqualTo(3f)          // 展示值，不是 3000
        assertThat(o.isCriticalReminder).isTrue()
        assertThat(o.snoozeMinutes).isEqualTo(15)
        assertThat(o.advanceMinutes).isEqualTo(5)
        assertThat(o.isPausedOn(LocalDate.now())).isTrue()
    }

    @Test
    fun `无提醒设置行时读模型补默认值而不是崩溃`() = runTest {
        val id = db.medicationDao().insert(MedicationEntity(name = "无设置药"))
        val o = db.medicationDao().getOverviewById(id)!!
        assertThat(o.isCriticalReminder).isFalse()
        assertThat(o.snoozeMinutes).isEqualTo(0)
        assertThat(o.isPausedOn(LocalDate.now())).isFalse()
    }

    /** 原 `getSchedulableOn` 的判据（该方法已随死代码清理删除，osbf P3-1）：排闹钟的药 = active 且当天未暂停 */
    @Test
    fun `active 且未暂停的药才算可排班 已到期自动恢复`() = runTest {
        val a = newMed(name = "在服")
        val b = newMed(name = "暂停中")
        val c = newMed(name = "已到期自动恢复")
        service.setPausedUntil(b, "")
        // 已到期：结束日是昨天 ⇒ 今天起不再算暂停
        service.setPausedUntil(c, LocalDate.now().minusDays(1).toString())

        val schedulable = db.medicationDao().getActiveOverviews()
            .filter { !it.isPausedOn(LocalDate.now()) }
            .map { it.medication.id }
        assertThat(schedulable).containsExactly(a, c)
        assertThat(schedulable).doesNotContain(b)
    }
}
