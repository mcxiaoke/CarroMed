package com.mcxiaoke.carromed.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.data.entity.DoseRecordEntity
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.model.RecordStatus
import com.mcxiaoke.carromed.core.domain.model.Dose
import com.mcxiaoke.carromed.core.domain.service.DoseTrackingService
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId

/**
 * DAO 聚合的**返回量纲**测试（治 P1-3，D-7 以来第 5 次量纲混用）。
 *
 * ## 为什么这一层值得单独立一个文件
 *
 * 量纲错误的特征是**类型上完全看不出来**：`SUM(dose_taken)` 返回毫单位，
 * 但 Kotlin 的 `Int` 和"片数"也是 `Int`；声明成 `Float?` 之后，
 * 调用方 `?: 0f` 一路畅通到 UI，直到渲染出「共消耗 1000 片」。
 *
 * 而**只有在这一层**能把它挡住 —— 实体的 `doseAmount: Int` 带着单位信息，
 * 是 DAO 的返回签名把它抹掉了。上层任何断言都补不了这个洞：
 * 演示数据里每个药近 30 天都是 0 片，UI 走查看**完全看不出问题**
 * （这是本缺陷的实测经过：先在模拟器上看到「1000 片」才发现代码问题）。
 *
 * 所以这里守的不是"某个业务值"，而是**"这个 API 交出去的是毫单位"**。
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class DaoUnitScaleTest {

    private lateinit var db: AppDatabase
    private lateinit var tracking: DoseTrackingService

    private val today: LocalDate = LocalDate.of(2026, 9, 28)

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        tracking = DoseTrackingService(db)
    }

    @After
    @Throws(IOException::class)
    fun tearDown() = db.close()

    private fun tsOf(date: LocalDate, hour: Int = 9): Long =
        date.atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** 一个服过 **2.5 片** 的药（2.5 不是整数，正是为了暴露"当作片数"的错误） */
    private suspend fun medWithRecords(doseMilli: Int): Long {
        val medId = db.medicationDao().insert(
            MedicationEntity(name = "测试药", unit = "片", defaultDose = doseMilli)
        )
        db.reminderSettingsDao().ensureDefaults(medId)
        repeat(2) { i ->
            db.doseRecordDao().insert(
                DoseRecordEntity(
                    medicationId = medId,
                    actualTs = tsOf(today, 8 + i),
                    doseTaken = doseMilli,
                    status = RecordStatus.COMPLETED
                )
            )
        }
        // 一条 REVERTED 的一条 SKIPPED 的，都不该进"已服合计"
        db.doseRecordDao().insert(
            DoseRecordEntity(
                medicationId = medId, actualTs = tsOf(today, 12),
                doseTaken = doseMilli, status = RecordStatus.REVERTED
            )
        )
        db.doseRecordDao().insert(
            DoseRecordEntity(
                medicationId = medId, actualTs = tsOf(today, 13),
                doseTaken = doseMilli, status = RecordStatus.SKIPPED
            )
        )
        return medId
    }

    // ================================================================
    // I13（本轮新增）：DAO 聚合的返回值一律是整数毫单位
    // ================================================================

    @Test
    fun `getSumDoseTakenForMedication 返回整数毫单位而不是片数`() = runTest {
        val medId = medWithRecords(2500)     // 每条 2.5 片
        val sum = db.doseRecordDao().getSumDoseTakenForMedication(medId, 0L, Long.MAX_VALUE)

        // 2 条 COMPLETED × 2500 = 5000 毫单位 = 5 片
        // 若签名是 Float? 且调用方不换算，UI 会显示「5000 片」
        assertThat(sum).isEqualTo(5000)
        assertThat(Dose(sum ?: 0).asFloat).isEqualTo(5f)
    }

    @Test
    fun `该聚合只看 COMPLETED 不含 REVERTED 与 SKIPPED`() = runTest {
        val medId = medWithRecords(1000)
        val sum = db.doseRecordDao().getSumDoseTakenForMedication(medId, 0L, Long.MAX_VALUE)
        // 4 条记录里只有 2 条是 COMPLETED
        assertThat(sum).isEqualTo(2000)
    }

    @Test
    fun `区间参数真的生效 不在区间内的记录不计入`() = runTest {
        val medId = medWithRecords(1000)
        val from = tsOf(today, 9)
        // 只取 09:00 之后 ⇒ 第一条（08:00）被排除，只剩 09:00 那条
        val sum = db.doseRecordDao().getSumDoseTakenForMedication(medId, from, Long.MAX_VALUE)
        assertThat(sum).isEqualTo(1000)
    }

    @Test
    fun `台账聚合同样返回毫单位`() = runTest {
        val medId = db.medicationDao().insert(
            MedicationEntity(name = "药", unit = "片", isStockTracked = true)
        )
        tracking.setStockTracking(medId, enabled = true, initialStock = 12.5f)
        // 建档写的是 12500 毫单位
        assertThat(db.inventoryTransactionDao().getSumOfChanges(medId)).isEqualTo(12500)
        assertThat(db.inventoryTransactionDao().getSumOfChanges(medId) ?: 0)
            .isNotEqualTo(12)   // 12 是"片数"的量纲，恰好也可能是别的毫值 ⇒ 用上面那条精确断言
    }

    /**
     * 与 `getDoseSumByMedicationInRange`（返回 `MedDoseSumRow.totalDose: Int`）口径一致。
     *
     * 同一条业务事实有**两个**查询实现时，两者必须给出同一个数 ——
     * 否则"详情页说 5 片、统计页说 5000 片"这种事会发生。
     */
    @Test
    fun `两个已服合计查询 口径必须一致`() = runTest {
        val medId = medWithRecords(1500)
        val from = tsOf(today, 0)
        val to = tsOf(today, 23)

        val a = db.doseRecordDao().getSumDoseTakenForMedication(medId, from, to) ?: 0
        val b = db.doseRecordDao()
            .getDoseSumByMedicationInRange(from, to)
            .first { it.medicationId == medId }
            .totalDose

        assertThat(a).isEqualTo(b)
        assertThat(Dose(a).asFloat).isEqualTo(3f)   // 2 × 1.5 片
    }
}
