package com.mcxiaoke.carromed.core.alarm

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.entity.PolicyTimeEntity
import com.mcxiaoke.carromed.core.data.entity.SchedulePolicyEntity
import com.mcxiaoke.carromed.core.data.model.PolicyType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.LocalDate

/**
 * 全量对账的幂等性测试（A6 的前提，兑现 `FINAL-PRODUCT` D-14 第三档）。
 *
 * ## 为什么这条测试必须存在
 *
 * A6 让 WorkManager 每 15 分钟重跑一次 `AlarmReconciler.rescheduleAll`。
 * 一旦它**不幂等**，后果是灾难性的而非缓慢的：
 *
 * | 不幂等的表现 | 用户看到什么 |
 * | :--- | :--- |
 * | 每次对账换一批 slot id | 闹钟 `requestCode` 漂移，旧闹钟成孤儿 ⇒ 同一次服药反复响 |
 * | 每次对账多插一批槽位 | 「今日清单」出现重复条目、统计翻倍 |
 * | 每次对账清空已登记的闹钟 | 提醒随机漏响，且**越是重排越容易漏** |
 *
 * 15 分钟一轮意味着一天 96 次。前两次可能看不出来，一天以后就完全失控。
 *
 * ## 为什么显式传库而不是用 `AppDatabase.getInstance`
 *
 * 那是 `companion object` 的静态单例，**跨 Robolectric 测试方法不会重置**，
 * 而每个方法都会重建 `Application` 与沙箱文件系统。用它会让本测试
 * 读到上一个测试留下的坏句柄而假红。用内存库则每次都是干净的。
 * 同样的坑也导致 `ReconcileWorkerTest` 里不能测 `doWork()`（见该文件的 KDoc）。
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class AlarmReconcilerIdempotencyTest {

    private lateinit var db: AppDatabase

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val today: LocalDate get() = LocalDate.now()

    @Before
    fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        seedMedication(name = "环孢素", times = listOf("08:00", "20:00"))
        seedMedication(name = "维生素D", times = listOf("13:30"))
    }

    @After
    @Throws(IOException::class)
    fun tearDown() = db.close()

    private suspend fun seedMedication(name: String, times: List<String>) {
        val medId = db.medicationDao().insert(MedicationEntity(name = name))
        db.schedulePolicyDao().savePolicyWithTimes(
            SchedulePolicyEntity(
                medicationId = medId,
                policyType = PolicyType.DAILY,
                startDate = today.toString()
            ),
            times.map { PolicyTimeEntity(policyId = 0, timeOfDay = it, doseAmount = 1000) }
        )
        // A2 起提醒运行态在独立表，必须显式建行，否则 getActiveOverviews 拿不到
        db.reminderSettingsDao().ensureDefaults(medId)
    }

    private suspend fun allSlots() = db.doseSlotDao().getAllSlots()
        .sortedWith(compareBy({ it.medicationId }, { it.scheduledDate }, { it.scheduledTime }))

    // ==================== 幂等 ====================

    @Test
    fun `连续 5 次全量对账 槽位集合与 id 完全不变`() = runBlocking {
        AlarmReconciler.rescheduleAll(context, db)
        val first = allSlots()
        assertThat(first).isNotEmpty()

        repeat(5) { AlarmReconciler.rescheduleAll(context, db) }
        val after = allSlots()

        assertThat(after).hasSize(first.size)
        // ⭐ id 逐一相同：id 就是闹钟身份，变了就意味着闹钟漂移
        assertThat(after.map { it.id }).isEqualTo(first.map { it.id })
        assertThat(after.map { it.scheduledDate to it.scheduledTime })
            .isEqualTo(first.map { it.scheduledDate to it.scheduledTime })
    }

    @Test
    fun `连续对账不会造出重复槽位`() = runBlocking {
        repeat(3) { AlarmReconciler.rescheduleAll(context, db) }
        val keys = allSlots().map { Triple(it.medicationId, it.scheduledDate, it.scheduledTime) }
        assertThat(keys).containsNoDuplicates()
    }

    @Test
    fun `视野覆盖满 HORIZON_DAYS 天且不多不少`() = runBlocking {
        AlarmReconciler.rescheduleAll(context, db)
        val dates = allSlots().map { it.scheduledDate }.toSortedSet()
        assertThat(dates.first()).isEqualTo(today.toString())
        assertThat(dates.last()).isEqualTo(today.plusDays(AlarmReconciler.HORIZON_DAYS).toString())
        // 中间每一天都必须有槽位（2 个药 → 每天 3 个槽位）
        assertThat(dates).hasSize(AlarmReconciler.HORIZON_DAYS.toInt() + 1)
        assertThat(allSlots()).hasSize((AlarmReconciler.HORIZON_DAYS.toInt() + 1) * 3)
    }

    @Test
    fun `HORIZON_DAYS 至少 14 天（低于它就回到 P0-2 的静默漏提醒）`() {
        // 旧实现是 7 天，且没有自续期 ⇒ 装好 App 后不碰它，第 8 天开始静默漏提醒
        assertThat(AlarmReconciler.HORIZON_DAYS).isAtLeast(14L)
    }

    // ==================== 暂停：不新增、不补排 ====================
    //
    // ⚠️ 这里**不断言**"暂停后槽位被清空"。当前实现是：`AlarmReconciler` 把暂停的药
    // 从 `activeMeds` 里剔掉，于是它的 `reconcileSchedule` 根本不会被调用，
    // 旧槽位原样留在库里。而 `TodayViewModel` 不过滤暂停（只过滤 `is_archived`），
    // 于是今日清单会列出这些"永远不会有闹钟"的待服项。
    //
    // 这是**既有问题**（A2 加的 `paused_until` 与 A3 之前的对账器都是这个行为），
    // 修法有两条路且都要动产品语义，所以**没有顺手改**：
    //   (a) 对账时也清理暂停药的未来 PENDING 槽位 —— 清单变得诚实，但"暂停中"不可见；
    //   (b) 今日清单保留这些槽位但标注"已暂停" —— 需要新的 UI 状态。
    // 已登记为待拍板项，见 `docs/REMINDER-DOMAIN-REDESIGN.md` §9。

    @Test
    fun `暂停中的药品不再新增槽位`() = runBlocking {
        val medId = db.medicationDao().getActiveOverviews().first { it.name == "维生素D" }.id
        AlarmReconciler.rescheduleAll(context, db)
        val before = allSlots().count { it.medicationId == medId }
        assertThat(before).isGreaterThan(0)

        db.reminderSettingsDao().setPausedUntil(medId, today.plusDays(1).toString())
        repeat(3) { AlarmReconciler.rescheduleAll(context, db) }

        // 暂停期间不增长（其余的药照常排班，说明是按药品粒度过滤）
        assertThat(allSlots().count { it.medicationId == medId }).isEqualTo(before)
        assertThat(allSlots().any { it.medicationId != medId }).isTrue()
    }

    @Test
    fun `暂停结束后对账立刻把窗口补满 且 id 稳定`() = runBlocking {
        val medId = db.medicationDao().getActiveOverviews().first { it.name == "维生素D" }.id
        AlarmReconciler.rescheduleAll(context, db)
        val before = allSlots().filter { it.medicationId == medId }
            .sortedWith(compareBy({ it.scheduledDate }, { it.scheduledTime }))

        db.reminderSettingsDao().setPausedUntil(medId, today.toString())
        AlarmReconciler.rescheduleAll(context, db)
        // 暂停到今天 ⇒ `isPausedOn(today)` 为真，不排新槽位
        assertThat(allSlots().count { it.medicationId == medId }).isEqualTo(before.size)

        db.reminderSettingsDao().resume(medId)
        AlarmReconciler.rescheduleAll(context, db)
        val after = allSlots().filter { it.medicationId == medId }
            .sortedWith(compareBy({ it.scheduledDate }, { it.scheduledTime }))

        // 恢复后窗口完整，且既有槽位一行不动（闹钟身份因此稳定）
        assertThat(after).hasSize(before.size)
        assertThat(after.map { it.id }).isEqualTo(before.map { it.id })
    }

    // ==================== 删掉服用计划 ====================

    @Test
    fun `删掉服用计划后 窗口内的待服槽位被清空`() = runBlocking {
        val medId = db.medicationDao().getActiveOverviews().first { it.name == "维生素D" }.id
        AlarmReconciler.rescheduleAll(context, db)
        assertThat(allSlots().count { it.medicationId == medId }).isGreaterThan(0)

        // 只删这一个药的计划
        db.schedulePolicyDao().deactivatePoliciesForMedication(medId)
        AlarmReconciler.rescheduleAll(context, db)

        // 没有计划 ⇒ 不该再有"该吃没吃"的待服槽位（它们不是既成事实）
        assertThat(allSlots().count { it.medicationId == medId }).isEqualTo(0)
    }

    @Test
    fun `删掉服用计划不影响另一个药`() = runBlocking {
        val target = db.medicationDao().getActiveOverviews().first { it.name == "维生素D" }.id
        AlarmReconciler.rescheduleAll(context, db)
        val otherCountBefore = allSlots().count { it.medicationId != target }
        assertThat(otherCountBefore).isGreaterThan(0)

        db.schedulePolicyDao().deactivatePoliciesForMedication(target)
        AlarmReconciler.rescheduleAll(context, db)

        assertThat(allSlots().count { it.medicationId != target }).isEqualTo(otherCountBefore)
    }

    // ==================== 停药归档 ====================

    @Test
    fun `归档的药品不再参与排班`() = runBlocking {
        AlarmReconciler.rescheduleAll(context, db)
        val medId = db.medicationDao().getActiveOverviews().first { it.name == "维生素D" }.id
        val before = allSlots().count { it.medicationId == medId }
        assertThat(before).isGreaterThan(0)

        db.medicationDao().updateArchiveStatus(medId, isArchived = true)
        AlarmReconciler.rescheduleAll(context, db)

        // 归档药不再被排新槽位，槽位总数不会增加
        assertThat(allSlots().count { it.medicationId == medId }).isAtMost(before)
        // 另一味药照常排班，说明过滤是按药品粒度的，没有误伤
        assertThat(allSlots().count { it.medicationId != medId }).isGreaterThan(0)
    }
}
