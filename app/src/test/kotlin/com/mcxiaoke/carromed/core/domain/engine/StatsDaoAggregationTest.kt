package com.mcxiaoke.carromed.core.domain.engine

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.testing.assertDoseValue
import com.mcxiaoke.carromed.core.domain.model.Dose
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.dao.DoseRecordDao
import com.mcxiaoke.carromed.core.data.dao.DoseSlotDao
import com.mcxiaoke.carromed.core.data.dao.MedicationDao
import com.mcxiaoke.carromed.core.data.entity.DoseRecordEntity
import com.mcxiaoke.carromed.core.data.entity.DoseSlotEntity
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.model.RecordStatus
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.LocalDate

/**
 * 统计聚合 DAO 查询测试
 *
 * 验证 `dose_slots` 的 GROUP BY 聚合与 `dose_records` 的消耗汇总，
 * 确保统计报表 / 进展矩阵 / 详情页依从率三者读的是同一份真实数据。
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class StatsDaoAggregationTest {

    private lateinit var db: AppDatabase
    private lateinit var medDao: MedicationDao
    private lateinit var slotDao: DoseSlotDao
    private lateinit var recordDao: DoseRecordDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        medDao = db.medicationDao()
        slotDao = db.doseSlotDao()
        recordDao = db.doseRecordDao()
    }

    @After
    @Throws(IOException::class)
    fun tearDown() = db.close()

    private suspend fun seedMed(name: String): Long =
        medDao.insert(MedicationEntity(name = name, unit = "片"))

    private suspend fun seedSlot(
        medId: Long,
        date: String,
        time: String,
        status: SlotStatus,
        dose: Float = 1f
    ) {
        slotDao.insert(
            DoseSlotEntity(
                medicationId = medId,
                policyId = 1L,
                scheduledDate = date,
                scheduledTime = time,
                scheduledTs = date.hashCode().toLong() * 1000 + time.hashCode(),
                doseAmount = Dose.of(dose).milli,
                status = status
            )
        )
    }

    @Test
    fun slotStatusCounts_aggregatesPerMedicationDateAndStatus() = runTest {
        val medId = seedMed("环孢素")
        seedSlot(medId, "2026-09-26", "10:30", SlotStatus.COMPLETED)
        seedSlot(medId, "2026-09-26", "22:00", SlotStatus.COMPLETED)
        seedSlot(medId, "2026-09-26", "23:30", SlotStatus.EXPIRED)
        seedSlot(medId, "2026-09-27", "10:30", SlotStatus.PENDING)

        val rows = slotDao.getSlotStatusCounts("2026-09-26", "2026-09-27")

        assertThat(rows).isNotEmpty()
        val all = rows.fold(StatsEngine.DayStatusBreakdown()) { a, r ->
            a + breakdownOf(r.status, r.count)
        }
        assertThat(all.completed).isEqualTo(2)
        assertThat(all.missed).isEqualTo(1)
        assertThat(all.pending).isEqualTo(1)
        // 依从率 = 2 / (2 + 0 + 1)，待服不进分母
        assertThat(StatsEngine.adherenceOf(all)).isWithin(1e-5f).of(2f / 3f)
    }

    @Test
    fun slotStatusCounts_excludesDatesOutsideRange() = runTest {
        val medId = seedMed("环孢素")
        seedSlot(medId, "2026-09-20", "10:30", SlotStatus.COMPLETED)
        seedSlot(medId, "2026-09-27", "10:30", SlotStatus.COMPLETED)

        val rows = slotDao.getSlotStatusCounts("2026-09-26", "2026-09-27")
        val all = StatsEngine.aggregateBreakdowns(rows)
        assertThat(all.getValue(medId)).containsKey("2026-09-27")
        assertThat(all.getValue(medId)).doesNotContainKey("2026-09-20")
    }

    @Test
    fun slotStatusCounts_isScopedToSingleMedication() = runTest {
        val a = seedMed("药A")
        val b = seedMed("药B")
        seedSlot(a, "2026-09-27", "08:00", SlotStatus.COMPLETED)
        seedSlot(b, "2026-09-27", "08:00", SlotStatus.SKIPPED)

        val rows = slotDao.observeSlotStatusCountsForMedication(a, "2026-09-27", "2026-09-27").first()
        assertThat(rows).hasSize(1)
        assertThat(rows[0].medicationId).isEqualTo(a)
    }

    @Test
    fun slotStatusCounts_flowReemitsAfterDataChange() = runBlocking {
        val medId = seedMed("环孢素")
        val today = LocalDate.now().format(SlotProjectionEngine.DATE_FORMATTER)
        seedSlot(medId, today, "10:30", SlotStatus.PENDING)

        // 真响应式断言（L-19）：旧实现 .first() 前后各查一次，Room 冷流下恒绿。
        // 这里先订阅、等首个发射（此时 InvalidationTracker 观察者已注册），
        // 再提交变更 —— 只有真正的重发射才能让 completed==1 到达，
        // 若数据变更不触发重发射，下方 withTimeout 会失败。
        val initial = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Unit>()
        val job = launch {
            slotDao.observeSlotStatusCounts(today, today)
                .onEach { rows ->
                    val breakdown =
                        StatsEngine.aggregateBreakdowns(rows).getValue(medId).getValue(today)
                    if (breakdown.completed == 0) initial.complete(Unit)
                    if (breakdown.completed == 1) completed.complete(Unit)
                }
                .collect()
        }
        try {
            withTimeout(10_000) { initial.await() }

            slotDao.forceStatusForTest(
                slotDao.getSlotsForDate(today).first().id,
                SlotStatus.COMPLETED,
                System.currentTimeMillis()
            )

            withTimeout(10_000) { completed.await() }
        } finally {
            job.cancel()
        }
    }

    @Test
    fun doseSumByMedication_ranksByRealConsumption() = runTest {
        val a = seedMed("羟氯喹")
        val b = seedMed("环孢素")
        val now = System.currentTimeMillis()

        // 羟氯喹 3 次 × 1 片；环孢素 1 次 × 2 片
        repeat(3) { i ->
            recordDao.insert(
                DoseRecordEntity(
                    medicationId = a,
                    actualTs = now - 1000L * (i + 1),
                    doseTaken = 1000,
                    status = RecordStatus.COMPLETED
                )
            )
        }
        recordDao.insert(
            DoseRecordEntity(
                medicationId = b,
                actualTs = now - 5000L,
                doseTaken = 2000,
                status = RecordStatus.COMPLETED
            )
        )
        // SKIPPED 的记录不计入消耗
        recordDao.insert(
            DoseRecordEntity(
                medicationId = b,
                actualTs = now - 6000L,
                doseTaken = 99000,
                status = RecordStatus.SKIPPED
            )
        )

        val sums = recordDao.getDoseSumByMedicationInRange(now - 60_000L, now)
        assertThat(sums).hasSize(2)
        // 环孢素 2 片 < 羟氯喹 3 片，所以排第一的是羟氯喹
        assertThat(sums[0].medicationId).isEqualTo(a)
        assertThat(sums[0].totalDose).isEqualTo(3000)
        assertThat(sums[1].medicationId).isEqualTo(b)
        assertThat(sums[1].totalDose).isEqualTo(2000)
        assertThat(sums.sumOf { it.totalDose }).isEqualTo(5000)
    }

    @Test
    fun doseSumByMedication_respectsTimeRange() = runTest {
        val medId = seedMed("老药")
        val now = System.currentTimeMillis()
        recordDao.insert(
            DoseRecordEntity(
                medicationId = medId,
                actualTs = now - 10L * 24 * 3600 * 1000,
                doseTaken = 5000,
                status = RecordStatus.COMPLETED
            )
        )
        recordDao.insert(
            DoseRecordEntity(
                medicationId = medId,
                actualTs = now - 1000L,
                doseTaken = 1000,
                status = RecordStatus.COMPLETED
            )
        )

        val week = recordDao.getDoseSumByMedicationInRange(now - 7L * 24 * 3600 * 1000, now)
        assertThat(week).hasSize(1)
        assertThat(week[0].totalDose).isEqualTo(1000)

        val year = recordDao.getDoseSumByMedicationInRange(now - 365L * 24 * 3600 * 1000, now)
        assertThat(year[0].totalDose).isEqualTo(6000)
    }

    private fun breakdownOf(status: SlotStatus, count: Int): StatsEngine.DayStatusBreakdown =
        when (status) {
            SlotStatus.COMPLETED -> StatsEngine.DayStatusBreakdown(completed = count)
            SlotStatus.EXPIRED -> StatsEngine.DayStatusBreakdown(missed = count)
            SlotStatus.PENDING, SlotStatus.SNOOZED -> StatsEngine.DayStatusBreakdown(pending = count)
            SlotStatus.SKIPPED -> StatsEngine.DayStatusBreakdown(skipped = count)
        }
}
