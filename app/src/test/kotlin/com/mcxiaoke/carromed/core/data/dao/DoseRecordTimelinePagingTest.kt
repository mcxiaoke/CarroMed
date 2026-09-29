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

/**
 * 服药流水翻页的 **keyset 游标**测试（`DoseRecordDao.getRecordsBefore`）。
 *
 * ## 为什么测游标而不是测 offset
 *
 * OFFSET 分页按行号定位，而服药事实在本项目里会被就地改状态
 * （撤销 → `REVERTED`）。翻页途中前面少一行，后面每一页都会**整段错位**，
 * 表现为"静默漏记录"——不报错，只是内容不对。所以这里构造的正是
 * "翻页中途数据被改动"的场景。
 *
 * ## 断言用 id 集合，不用条数
 *
 * 见 AGENTS.md 的时点陷阱：断言"共 N 条"会随时钟变色。这里一律比
 * **id 集合**，这样实现怎么改都不会让断言变得脆弱。
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class DoseRecordTimelinePagingTest {

    private lateinit var db: AppDatabase
    private lateinit var recordDao: DoseRecordDao
    private lateinit var medDao: com.mcxiaoke.carromed.core.data.dao.MedicationDao
    private var medId: Long = 0

    @Before
    fun setup() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        recordDao = db.doseRecordDao()
        medDao = db.medicationDao()
        medId = medDao.insert(MedicationEntity(name = "环孢素", unit = "片"))
    }

    @After
    fun teardown() {
        if (::db.isInitialized) db.close()
    }

    /**
     * 造 `count` 条记录，间隔 `stepMs`，时间**从早到晚**递增。
     *
     * `slotId` 给的是合成值：`dose_records.slot_id` **没有外键约束**
     * （`DoseRecordEntity` 的 `foreignKeys` 只声明了 `medication_id`），
     * 所以这里不需要真去造槽位就能表达"这条来自定时打卡"。
     * 用 `null` 则表达"临时用药 / 手动补录"。
     */
    private suspend fun seed(
        count: Int,
        startTs: Long,
        stepMs: Long,
        status: RecordStatus = RecordStatus.COMPLETED,
        slotIdBase: Long? = 1L
    ) {
        repeat(count) { i ->
            recordDao.insert(
                DoseRecordEntity(
                    slotId = slotIdBase?.plus(i),
                    medicationId = medId,
                    actualTs = startTs + i * stepMs,
                    doseTaken = 1000,
                    status = status
                )
            )
        }
    }

    @Test
    fun `翻页能取完所有记录且无重复无遗漏`() = runTest {
        val base = 1_700_000_000_000L
        seed(count = 25, startTs = base, stepMs = 60_000L)

        // 每页 10 条翻到底
        val collected = mutableListOf<Long>()
        var cursor = Long.MAX_VALUE
        var guard = 0
        while (true) {
            val page = recordDao.getRecordsBefore(cursor, 10)
            if (page.isEmpty()) break
            collected += page.map { it.id }
            cursor = page.last().actualTs
            if (++guard > 20) error("翻页未终止，说明游标没有前进")
        }

        assertThat(collected).hasSize(25)
        assertThat(collected.toSet()).hasSize(25)          // 无重复
        assertThat(recordDao.getRecordsForMedication(medId).map { it.id }.toSet())
            .isEqualTo(collected.toSet())                    // 无遗漏
    }

    @Test
    fun `结果按 actual_ts 倒序`() = runTest {
        val base = 1_700_000_000_000L
        seed(count = 5, startTs = base, stepMs = 60_000L)

        val page = recordDao.getRecordsBefore(Long.MAX_VALUE, 10)
        assertThat(page.map { it.actualTs })
            .isInOrder(Comparator<Long> { a, b -> b.compareTo(a) })
    }

    /**
     * ⭐ 核心用例：翻页途中把**更早**的记录撤销。
     *
     * 撤销不删行（append-only），所以 keyset 仍然取全；
     * 但如果实现换成 OFFSET，页 2 就会从"错位的行号"开始，漏掉一条。
     * 这里的 `REVERTED` 记录**仍应出现**在翻页结果里（事实保留、可追溯），
     * 只是 UI 会把它置灰。
     */
    @Test
    fun `翻页途中撤销某条记录 仍不漏不重`() = runTest {
        val base = 1_700_000_000_000L
        seed(count = 25, startTs = base, stepMs = 60_000L)

        val firstPage = recordDao.getRecordsBefore(Long.MAX_VALUE, 10)
        val cursor = firstPage.last().actualTs
        val page2 = recordDao.getRecordsBefore(cursor, 10)

        // 翻页期间：把第 1 页里最早那条标记为已撤销
        recordDao.markReverted(firstPage.first().id)

        val third = recordDao.getRecordsBefore(page2.last().actualTs, 10)
        val all = (firstPage + page2 + third).map { it.id }

        assertThat(all).containsNoDuplicates()
        assertThat(all).hasSize(25)
        // 被撤销的那条仍然在历史里（事实不删除）
        assertThat(all).contains(firstPage.first().id)
        assertThat(
            recordDao.getRecordById(firstPage.first().id)?.status
        ).isEqualTo(RecordStatus.REVERTED)
    }

    /**
     * 同毫秒的多条记录：`<` 而非 `<=` 才能既不重也不漏。
     *
     * 时间戳精确到毫秒，同一次操作写多行是完全可能的。
     * 若游标用 `<=`，这些同刻记录会在相邻两页各出现一次。
     */
    @Test
    fun `同一毫秒的多条记录不会在相邻页重复出现`() = runTest {
        val sameTs = 1_700_000_000_000L
        repeat(5) {
            recordDao.insert(
                DoseRecordEntity(
                    medicationId = medId,
                    actualTs = sameTs,     // 全部同刻
                    doseTaken = 1000,
                    status = RecordStatus.COMPLETED
                )
            )
        }
        // 造一条更早的，保证"同刻页"不是最后一页
        recordDao.insert(
            DoseRecordEntity(
                medicationId = medId,
                actualTs = sameTs - 60_000L,
                doseTaken = 1000,
                status = RecordStatus.COMPLETED
            )
        )

        val page1 = recordDao.getRecordsBefore(Long.MAX_VALUE, 3)
        assertThat(page1).hasSize(3)
        assertThat(page1.map { it.actualTs }.distinct()).hasSize(1)

        val page2 = recordDao.getRecordsBefore(sameTs, 3)
        assertThat(page2).isNotEmpty()

        val ids = (page1 + page2).map { it.id }
        assertThat(ids).containsNoDuplicates()
    }

    /** 手动补录（`slot_id == null`）必须能被流水取到 —— 这正是本轮修掉的缺陷。 */
    @Test
    fun `手动补录的记录 slotId 为 null 也能进流水`() = runTest {
        val base = 1_700_000_000_000L
        recordDao.insert(
            DoseRecordEntity(
                slotId = null,                       // 临时用药：没有槽位
                medicationId = medId,
                actualTs = base,
                doseTaken = 2000,
                status = RecordStatus.COMPLETED,
                isRetrospective = true
            )
        )
        seed(count = 3, startTs = base - 600_000L, stepMs = 60_000L, slotIdBase = 100L)

        val page = recordDao.getRecordsBefore(Long.MAX_VALUE, 10)
        assertThat(page).hasSize(4)
        assertThat(page.count { it.slotId == null }).isEqualTo(1)
        assertThat(page.first().slotId).isNull()
    }

    /** 到底判定：返回条数少于 limit 即"没有更多"。 */
    @Test
    fun `不足一页时 返回数少于 limit`() = runTest {
        seed(count = 3, startTs = 1_700_000_000_000L, stepMs = 60_000L)
        val page = recordDao.getRecordsBefore(Long.MAX_VALUE, 60)
        assertThat(page).hasSize(3)          // < 60 ⇒ 列表应显示"没有更早的记录了"
    }
}
