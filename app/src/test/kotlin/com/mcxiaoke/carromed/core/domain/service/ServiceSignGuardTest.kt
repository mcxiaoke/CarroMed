package com.mcxiaoke.carromed.core.domain.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.InventoryTransactionEntity
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.entity.PolicyTimeEntity
import com.mcxiaoke.carromed.core.data.entity.SchedulePolicyEntity
import com.mcxiaoke.carromed.core.data.model.PolicyType
import com.mcxiaoke.carromed.core.data.model.TransactionType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId

/**
 * 服务层的**符号防御**（M2-2）与领域层的剂量校验（M2-1 第三层）。
 *
 * ## 为什么 UI 过滤不够
 *
 * UI 过滤只保护"本 App 的这几个表单"。而 `DoseTrackingService` 是**所有**入口的
 * 公共下游：补录页、通知栏 Action、未来的 Widget / 手表 / 快捷指令、备份导入。
 * 只靠 UI 挡等于默默假设"只有一种调用方"——那一天到来时没人会记得这条约定。
 *
 * ## 负数为什么比 0 更恶劣
 *
 * 扣减的实现是 `-Dose.of(doseAmount)`。于是：
 *
 * | 输入 | 台账效果 |
 * | :--- | :--- |
 * | 0 | 不变 ⇒ **库存永不扣**，而事实记录写着"已服用 0 片" |
 * | -2 | **+2** ⇒ 给库存**加药**，且没有任何线索能解释账面为什么涨了 |
 *
 * 两条都是"账面与实物长期分叉"，而分叉方向相反、都无法自查。
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class ServiceSignGuardTest {

    private lateinit var db: AppDatabase
    private lateinit var tracking: DoseTrackingService

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setup(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        tracking = DoseTrackingService(db)
    }

    @After
    @Throws(IOException::class)
    fun tearDown(): Unit = db.close()

    private suspend fun givenTrackedMed(name: String = "环孢素"): Long {
        val id = db.medicationDao().insert(MedicationEntity(name = name, isStockTracked = true))
        db.inventoryTransactionDao().insert(
            InventoryTransactionEntity(
                medicationId = id,
                changeAmount = 30000,
                balanceAfter = 30000,
                txType = TransactionType.CALIBRATION_ADJUST
            )
        )
        return id
    }

    private suspend fun balanceOf(medId: Long): Int =
        db.inventoryTransactionDao().getSumOfChanges(medId) ?: 0

    // ==================== logManualDose ====================

    @Test
    fun `负剂量补录被拒 账面不增加`() = runBlocking {
        val medId = givenTrackedMed()
        val before = balanceOf(medId)

        val thrown = runCatching {
            tracking.logManualDose(medId, actualTs = 1000L, doseAmount = -2f)
        }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        // ⭐ 关键断言：不是"抛了异常"就算完，必须确认**账面没被动过**。
        // require 写在 withTransaction 里会回滚，但断言账面才能守住这一点。
        assertThat(balanceOf(medId)).isEqualTo(before)
    }

    @Test
    fun `零剂量补录被拒`() = runBlocking {
        val medId = givenTrackedMed()
        val thrown = runCatching {
            tracking.logManualDose(medId, actualTs = 1000L, doseAmount = 0f)
        }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(balanceOf(medId)).isEqualTo(30000)
    }

    @Test
    fun `正剂量补录照常扣减（守卫没把正常路径也堵死）`() = runBlocking {
        val medId = givenTrackedMed()
        // 时刻必须落在补录窗口内：1000L（1970 年）现在会被时间窗守卫拒绝
        tracking.logManualDose(medId, actualTs = System.currentTimeMillis(), doseAmount = 0.5f)
        assertThat(balanceOf(medId)).isEqualTo(29500)
    }

    @Test
    fun `NaN 与无穷剂量被拒`() = runBlocking {
        val medId = givenTrackedMed()
        listOf(Float.NaN, Float.POSITIVE_INFINITY).forEach { bad ->
            val thrown = runCatching {
                tracking.logManualDose(medId, actualTs = 1000L, doseAmount = bad)
            }.exceptionOrNull()
            assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThat(balanceOf(medId)).isEqualTo(30000)
    }

    // ==================== 补录时间窗（MANUAL_DOSE_BACKFILL_DAYS） ====================

    /** 补录窗外的时刻：窗口下界再往前一天 */
    private fun tooOldTs(): Long =
        LocalDate.now().minusDays(MANUAL_DOSE_BACKFILL_DAYS).minusDays(1)
            .atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun `早于补录窗口的补录被拒 账面不动`() = runBlocking {
        val medId = givenTrackedMed()
        val before = balanceOf(medId)

        val thrown = runCatching {
            tracking.logManualDose(medId, actualTs = tooOldTs(), doseAmount = 1f)
        }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(balanceOf(medId)).isEqualTo(before)
    }

    @Test
    fun `未来时刻的补录被拒`() = runBlocking {
        val medId = givenTrackedMed()
        val thrown = runCatching {
            tracking.logManualDose(medId, actualTs = System.currentTimeMillis() + 120_000L, doseAmount = 1f)
        }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
    }

    /** 窗口边界（恰好第 7 天）必须放行 —— 守卫是"早于窗口下界"才拒 */
    @Test
    fun `窗口边界第 7 天可以补录`() = runBlocking {
        val medId = givenTrackedMed()
        val boundary = LocalDate.now().minusDays(MANUAL_DOSE_BACKFILL_DAYS)
            .atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        tracking.logManualDose(medId, actualTs = boundary, doseAmount = 1f)
        assertThat(balanceOf(medId)).isEqualTo(29000)
    }

    /** 改时间同样受窗口约束：先合法补录，再尝试把时刻改到窗口外 */
    @Test
    fun `改时间不能把记录挪到补录窗口之外`() = runBlocking {
        val medId = givenTrackedMed()
        val recordId = tracking.logManualDose(
            medId, actualTs = System.currentTimeMillis(), doseAmount = 1f
        )
        val thrown = runCatching {
            tracking.editDose(recordId, newActualTs = tooOldTs())
        }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
    }

    // ==================== refillStock ====================

    @Test
    fun `负数入库被拒 账面不减少`() = runBlocking {
        val medId = givenTrackedMed()
        val thrown = runCatching {
            tracking.refillStock(medId, addedAmount = -30f)
        }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(balanceOf(medId)).isEqualTo(30000)
    }

    @Test
    fun `正数入库照常增加`() = runBlocking {
        val medId = givenTrackedMed()
        tracking.refillStock(medId, addedAmount = 30f)
        assertThat(balanceOf(medId)).isEqualTo(60000)
    }

    // ==================== calibrateStock ====================

    @Test
    fun `负的实测库存被拒 账面不被动`() = runBlocking {
        val medId = givenTrackedMed()
        val thrown = runCatching {
            tracking.calibrateStock(medId, actualStock = -5f)
        }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(balanceOf(medId)).isEqualTo(30000)
    }

    @Test
    fun `实测库存为 0 是合法的 刚好用完`() = runBlocking {
        val medId = givenTrackedMed()
        // 0 必须被接受：把库存校准到"刚好用完"是完全正常的操作。
        // 误把它当非法，用户就没法清零了。
        val changed = tracking.calibrateStock(medId, actualStock = 0f)
        assertThat(changed).isTrue()
        assertThat(balanceOf(medId)).isEqualTo(0)
    }

    // ==================== saveReminderPolicy 的 dose > 0 ====================

    @Test
    fun `保存 0 剂量计划被领域层拒绝`() = runBlocking {
        val medId = db.medicationDao().insert(MedicationEntity(name = "维生素D"))
        val admin = MedicationAdminService(db)
        val thrown = runCatching {
            admin.saveReminderPolicy(
                medicationId = medId,
                draft = MedicationAdminService.PolicyDraft(
                    policyType = PolicyType.DAILY,
                    startDate = "2026-01-01",
                    times = listOf(
                        MedicationAdminService.TimeDraft("08:00", 0f, "服药时段")
                    )
                )
            )
        }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        // 前提：策略确实没写进去（事务回滚），否则"抛异常"只是形式上的拒绝
        assertThat(db.schedulePolicyDao().getActivePolicyForMedication(medId)).isNull()
    }

    @Test
    fun `保存负剂量计划被领域层拒绝 且报错指出是哪一条时点`() = runBlocking {
        val medId = db.medicationDao().insert(MedicationEntity(name = "维生素D"))
        val admin = MedicationAdminService(db)
        val thrown = runCatching {
            admin.saveReminderPolicy(
                medicationId = medId,
                draft = MedicationAdminService.PolicyDraft(
                    policyType = PolicyType.DAILY,
                    startDate = "2026-01-01",
                    times = listOf(
                        MedicationAdminService.TimeDraft("08:00", 1f, ""),
                        MedicationAdminService.TimeDraft("20:00", -1f, "")
                    )
                )
            )
        }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        // 报错必须能定位到具体时点：六个时点的表单只说"剂量非法"等于没说
        assertThat(thrown!!.message).contains("20:00")
    }

    @Test
    fun `合法剂量正常保存`() = runBlocking {
        val medId = db.medicationDao().insert(MedicationEntity(name = "维生素D"))
        val admin = MedicationAdminService(db)
        admin.saveReminderPolicy(
            medicationId = medId,
            draft = MedicationAdminService.PolicyDraft(
                policyType = PolicyType.DAILY,
                startDate = "2026-01-01",
                times = listOf(
                    MedicationAdminService.TimeDraft("08:00", 0.5f, ""),
                    MedicationAdminService.TimeDraft("20:00", 1f, "")
                )
            )
        )
        val policy = db.schedulePolicyDao().getActivePolicyForMedication(medId)
        assertThat(policy).isNotNull()
        val times = db.schedulePolicyDao().getTimesForPolicy(policy!!.id)
        // 半片被存成 500 毫单位 —— 剂量守卫没有误伤合法的半片
        assertThat(times.map { it.doseAmount }).containsExactly(500, 1000)
        // ⚠️ 末尾必须是 Unit。`containsExactly` 返回 Ordered，若它是协程体最后一个表达式，
        // 方法签名就变成 `() -> Ordered` 而不是 `() -> Unit`，JUnit 直接报
        // "Method xxx() should be void" —— 报错完全看不出是哪一行的问题。
        Unit
    }

    // ==================== takeDose 的显式剂量 ====================

    @Test
    fun `打卡时传入负剂量被拒 不会给库存加药`() = runBlocking {
        val medId = db.medicationDao().insert(MedicationEntity(name = "环孢素", isStockTracked = true))
        db.schedulePolicyDao().savePolicyWithTimes(
            SchedulePolicyEntity(
                medicationId = medId,
                policyType = PolicyType.DAILY,
                startDate = "2026-01-01"
            ),
            listOf(PolicyTimeEntity(policyId = 0, timeOfDay = "08:00", doseAmount = 1000))
        )
        db.reminderSettingsDao().ensureDefaults(medId)
        val slot = run {
            tracking.reconcileSchedule(medId)
            db.doseSlotDao().getOpenSlots().first { it.medicationId == medId }
        }

        val thrown = runCatching {
            tracking.takeDose(slot.id, takenAmount = -1f)
        }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        // 槽位必须仍是 PENDING：守卫失败时不能留下"已打卡"的半截事实
        assertThat(db.doseSlotDao().getSlotById(slot.id)!!.status)
            .isEqualTo(com.mcxiaoke.carromed.core.data.model.SlotStatus.PENDING)
    }
}
