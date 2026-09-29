package com.mcxiaoke.carromed.core.data.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.DoseRecordEntity
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.model.RecordStatus
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
 * 单药历史的数据源（UX 方案 §4.2 用的查询）。
 *
 * 这一层是既有查询，所以这里守的是**它被新页面依赖时必须成立的那些性质**：
 * 倒序、只含本药、含已撤销的记录。
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class MedHistoryQueryTest {

    private lateinit var db: AppDatabase
    private lateinit var recordDao: DoseRecordDao
    private var medA: Long = 0
    private var medB: Long = 0

    @Before
    fun setup() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        recordDao = db.doseRecordDao()
        medA = db.medicationDao().insert(MedicationEntity(name = "环孢素", unit = "片"))
        medB = db.medicationDao().insert(MedicationEntity(name = "羟氯喹", unit = "片"))
    }

    @After
    fun teardown() {
        if (::db.isInitialized) db.close()
    }

    private fun ts(date: LocalDate, hour: Int): Long =
        date.atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun `按实际时刻倒序返回`() = runTest {
        val base = LocalDate.of(2026, 9, 1)
        // 故意乱序插入：插入顺序与期望返回顺序必须无关
        recordDao.insert(DoseRecordEntity(medicationId = medA, actualTs = ts(base, 22), doseTaken = 1000))
        recordDao.insert(DoseRecordEntity(medicationId = medA, actualTs = ts(base, 8), doseTaken = 1000))
        recordDao.insert(DoseRecordEntity(medicationId = medA, actualTs = ts(base, 13), doseTaken = 1000))

        val rows = recordDao.getRecordsForMedication(medA)
        assertThat(rows.map { it.actualTs })
            .isInOrder(Comparator<Long> { a, b -> b.compareTo(a) })
    }

    @Test
    fun `只返回本药的记录`() = runTest {
        val d = LocalDate.of(2026, 9, 1)
        recordDao.insert(DoseRecordEntity(medicationId = medA, actualTs = ts(d, 8), doseTaken = 1000))
        recordDao.insert(DoseRecordEntity(medicationId = medB, actualTs = ts(d, 9), doseTaken = 1000))
        recordDao.insert(DoseRecordEntity(medicationId = medA, actualTs = ts(d, 20), doseTaken = 1000))

        val rows = recordDao.getRecordsForMedication(medA)
        assertThat(rows).hasSize(2)
        assertThat(rows.map { it.medicationId }.distinct()).containsExactly(medA)
    }

    /**
     * 已撤销的记录**不出现在历史里**（2026-09-29 用户决定，推翻了原来的规则）。
     *
     * 原规则是"显示但置灰加「已撤销」标签"，理由是"用户可能想知道这条为什么没了"。
     * 实际用起来不是这样：测试撤销几次之后，流水里连着四条「已撤销」。
     * 用户的原话是"我感觉撤销只是一个动作不是一个状态，所以这里面不应该显示撤销"。
     *
     * 事实本身**不删**（append-only / I11）：`getAllRecords`（备份 / 导出用）
     * 仍能取到它，只是不进"我吃了什么"这个视图。
     */
    @Test
    fun `已撤销的记录不出现在历史里`() = runTest {
        val d = LocalDate.of(2026, 9, 1)
        val id1 = recordDao.insert(
            DoseRecordEntity(medicationId = medA, actualTs = ts(d, 8), doseTaken = 1000)
        )
        recordDao.insert(DoseRecordEntity(medicationId = medA, actualTs = ts(d, 20), doseTaken = 1000))
        recordDao.markReverted(id1)

        val rows = recordDao.getRecordsForMedication(medA)
        assertThat(rows).hasSize(1)
        assertThat(rows.single().status).isEqualTo(RecordStatus.COMPLETED)

        // 事实仍在库里 —— 只是不进这个视图
        assertThat(recordDao.getRecordById(id1)?.status).isEqualTo(RecordStatus.REVERTED)
        assertThat(recordDao.getAllRecords().map { it.id }).contains(id1)
    }

    @Test
    fun `跨月的记录都能取到（历史页按月分组的前提）`() = runTest {
        recordDao.insert(
            DoseRecordEntity(medicationId = medA, actualTs = ts(LocalDate.of(2026, 7, 15), 9), doseTaken = 1000)
        )
        recordDao.insert(
            DoseRecordEntity(medicationId = medA, actualTs = ts(LocalDate.of(2026, 8, 15), 9), doseTaken = 1000)
        )
        recordDao.insert(
            DoseRecordEntity(medicationId = medA, actualTs = ts(LocalDate.of(2026, 9, 15), 9), doseTaken = 1000)
        )

        val rows = recordDao.getRecordsForMedication(medA)
        assertThat(rows).hasSize(3)
        // 用 `monthValue`（Int）而不是 `month`（枚举）—— 后者比的是月份**名**，
        // 于是"跨了 3 个月"这个断言会变成"跨了 JULY/AUGUST/SEPTEMBER 三个枚举"，
        // 断言意图被悄悄换掉。
        val months = rows.map {
            java.time.Instant.ofEpochMilli(it.actualTs)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .monthValue
        }.toSet()
        assertThat(months).containsExactly(7, 8, 9)
    }
}
