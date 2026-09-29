package com.mcxiaoke.carromed.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.data.dao.DoseRecordDao
import com.mcxiaoke.carromed.core.data.dao.InventoryTransactionDao
import com.mcxiaoke.carromed.core.data.model.RecordStatus
import com.mcxiaoke.carromed.core.domain.service.DoseTrackingService
import com.mcxiaoke.carromed.core.domain.service.MedicationAdminService
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId

/**
 * 事实表只增不改（不变量 **I11**，治 P1-1 与 P1-2）。
 *
 * ## 这条不变量在文档里被写错了，本测试把真相固定下来
 *
 * 设计文档 §4 原写的是「`dose_records` / `inventory_transactions` **永不 DELETE / UPDATE**」。
 * 实际代码不是这样，而且**不该**是这样：
 *
 * | 表 | 真实情况 |
 * | :--- | :--- |
 * | `inventory_transactions` | 运行期**绝对只增**。唯一的 `DELETE` 是 `deleteAllTransactions()`，仅供恢复备份前清库 |
 * | `dose_records` | 行永不删、**除 `status` 外**的列永不改。`status` 允许 `COMPLETED → REVERTED` 单向翻转（撤销打卡） |
 *
 * 撤销如果靠物理删除实现，"我误点了打卡"就会把历史抹掉 ——
 * 而剂量台账是**用药安全**的唯一凭据，抹掉它等于让用户失去
 * "我今天到底吃没吃"的答案。所以撤销必须是**追加冲正流水 + 标记事实**。
 *
 * ## 为什么不写"永不 UPDATE"
 *
 * 真写成那样，`undoDose` 就没法实现，或者说只能靠"删了重插" ——
 * 那更糟：主键会变，`dose_slots` 与 `dose_records` 的关联就断了。
 * ## 为什么 DAO 上不写"永不 UPDATE"这种粗粒度禁令
 *
 * 真写成那样，`undoDose` 就没法实现，或者说只能靠"删了重插" ——
 * 那更糟：主键会变，`dose_slots` 与 `dose_records` 的关联就断了。
 * 所以本测试分成两半：
 *
 * - **静态半**：DAO 上不得有 `@Delete` / `@Update` 注解（它们会生成"按实体覆盖"入口）；
 *   `@Query` 里的改写语句必须与显式白名单**集合完全相等**（多了要回来讨论，少了不留僵尸豁免）。
 * - **行为半**：跑一整套日常操作后，既有行**逐列相等**、id 集合只增不减。
 *
 * 行为半才是真正拦得住缺陷的那一半 —— 静态扫描只能拦住"形状"不对的写法。
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class AppendOnlyFactTableTest {

    private lateinit var db: AppDatabase
    private lateinit var admin: MedicationAdminService
    private lateinit var tracking: DoseTrackingService

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val today: LocalDate = LocalDate.of(2026, 9, 28)

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

    /** 一个"正常用药"的药：每日 08:00，1 片，**开启**库存追踪 */
    private suspend fun newDailyMedication(): Long {
        val id = admin.saveProfile(
            MedicationAdminService.ProfileDraft(
                name = "哨兵药",
                unit = "片",
                defaultDose = 1f,
                minStockAlert = 3f
            )
        )
        admin.saveReminderPolicy(
            medicationId = id,
            draft = MedicationAdminService.PolicyDraft(
                startDate = today.toString(),
                times = listOf(MedicationAdminService.TimeDraft("08:00", 1f, "早间"))
            )
        )
        // ⚠️ 必须显式开追踪：`ProfileDraft` 里**没有** `isStockTracked` 字段，
        // 新药默认不追踪，于是 `takeDose` 根本不写流水。
        // 本测试第一版就漏了这一句，导致"流水逐列不变"变成恒真断言。
        tracking.setStockTracking(id, enabled = true, initialStock = 30f)
        tracking.reconcileSchedule(id, today, today.plusDays(6))
        return id
    }

    private suspend fun slotOf(medId: Long, date: LocalDate): Long =
        db.doseSlotDao().getSlotsForDate(date.toString())
            .first { it.medicationId == medId }
            .id

    // ==================================================================
    // 1. 台账：逐列只增不改
    // ==================================================================

    @Test
    fun `台账流水在全部日常操作后 逐列与操作前完全相同`() = runTest {
        val medId = newDailyMedication()

        // —— 建一批流水：打卡 / 补录 / 入库 / 盘点 ——
        tracking.takeDose(slotId = slotOf(medId, today), note = "早间")
        tracking.logManualDose(medId, actualTs = tsOf(today, 12), doseAmount = 0.5f, note = "临时加服")
        tracking.refillStock(medId, addedAmount = 10f, note = "补货")
        tracking.calibrateStock(medId, actualStock = 33f, note = "盘点")

        val before = db.inventoryTransactionDao().getAllTransactions().sortedBy { it.id }
        assertThat(before).isNotEmpty()

        // —— 再跑一整套与"事实"无关的操作 ——
        admin.saveProfile(
            MedicationAdminService.ProfileDraft(
                medId = medId, name = "改名了", unit = "粒", defaultDose = 2f,
                category = "常备药", form = "片剂", colorHex = "#2563EB",
                description = "", precautions = emptyList(), noticeShort = "",
                expiryDate = "", minStockAlert = 5f
            )
        )
        admin.setPausedUntil(medId, today.plusDays(2).toString())
        admin.resume(medId)
        tracking.setStockTracking(medId, enabled = false)
        tracking.setStockTracking(medId, enabled = true, initialStock = 33f)
        tracking.reconcileSchedule(medId, today, today.plusDays(6))
        tracking.reconcileSchedule(medId, today, today.plusDays(6))

        // 撤销一次打卡 —— 这是**唯一**会改动既有事实的操作
        tracking.undoDose(slotId = slotOf(medId, today))

        val after = db.inventoryTransactionDao().getAllTransactions().sortedBy { it.id }
        // 原有行**一条不少、一列不改**（撤销只允许追加冲正流水）
        assertThat(after.map { it.id }).containsAtLeastElementsIn(before.map { it.id })
        before.forEach { old ->
            assertThat(after.firstOrNull { it.id == old.id }).isEqualTo(old)
        }
    }

    @Test
    fun `台账的余额快照列从未被就地改写（I2 靠这个成立）`() = runTest {
        val medId = newDailyMedication()
        tracking.takeDose(slotId = slotOf(medId, today), note = "早间")
        val original = db.inventoryTransactionDao().getAllTransactions().single { it.recordId != null }
        val balanceAtThatTime = original.balanceAfter

        // 后续任何操作都不能回头改写那一行的 balance_after
        tracking.refillStock(medId, addedAmount = 5f)
        tracking.calibrateStock(medId, actualStock = 1f)
        tracking.undoDose(slotId = slotOf(medId, today))
        tracking.takeDose(slotId = slotOf(medId, today.plusDays(1)))

        val reread = db.inventoryTransactionDao().getAllTransactions().first { it.id == original.id }
        assertThat(reread.balanceAfter).isEqualTo(balanceAtThatTime)
        assertThat(reread).isEqualTo(original)
    }

    // ==================================================================
    // 2. 服药事实：行不删、列不改，只有 status 能单向翻
    // ==================================================================

    @Test
    fun `服药事实的行永不消失 撤销后仍在库中并被标记为 REVERTED`() = runTest {
        val medId = newDailyMedication()
        val slotId = slotOf(medId, today)
        tracking.takeDose(slotId = slotId, note = "早间")

        val before = db.doseRecordDao().getAllRecords()
        assertThat(before).hasSize(1)
        assertThat(before.single().status).isEqualTo(RecordStatus.COMPLETED)

        assertThat(tracking.undoDose(slotId)).isTrue()

        val after = db.doseRecordDao().getAllRecords()
        // ★ 行还在，只是状态翻转
        assertThat(after).hasSize(1)
        assertThat(after.single().id).isEqualTo(before.single().id)
        assertThat(after.single().status).isEqualTo(RecordStatus.REVERTED)
        // ★ 除 status 外逐列不变
        assertThat(after.single().copy(status = before.single().status)).isEqualTo(before.single())
    }

    @Test
    fun `status 只能从 COMPLETED 单向翻到 REVERTED 不可逆转`() = runTest {
        val medId = newDailyMedication()
        val slotId = slotOf(medId, today)
        tracking.takeDose(slotId = slotId)
        tracking.undoDose(slotId)
        assertThat(db.doseRecordDao().getAllRecords().single().status)
            .isEqualTo(RecordStatus.REVERTED)

        // 再次撤销：不能把 REVERTED 翻回 COMPLETED（否则"撤销"会变成"恢复"）
        tracking.undoDose(slotId)
        assertThat(db.doseRecordDao().getAllRecords().single().status)
            .isEqualTo(RecordStatus.REVERTED)

        // 跳过记的是新事实，不是翻转
        tracking.skipDose(slotId = slotOf(medId, today.plusDays(1)), reason = "忘了")
        assertThat(db.doseRecordDao().getAllRecords().map { it.status })
            .containsExactly(RecordStatus.REVERTED, RecordStatus.SKIPPED)
    }

    @Test
    fun `收窄疗程时删掉的只是待服槽位 既成事实与流水一行不少`() = runTest {
        val medId = newDailyMedication()
        // 先造一批事实：今天的打卡 + 明天的跳过 + 一条补录
        tracking.takeDose(slotId = slotOf(medId, today), note = "早间")
        tracking.skipDose(slotId = slotOf(medId, today.plusDays(1)), reason = "忘了")
        tracking.logManualDose(medId, actualTs = tsOf(today, 20), doseAmount = 1f, note = "临时")

        val recordsBefore = db.doseRecordDao().getAllRecords().sortedBy { it.id }
        val ledgerBefore = db.inventoryTransactionDao().getAllTransactions().sortedBy { it.id }
        val slotsBefore = db.doseSlotDao().getAllSlots().size
        assertThat(recordsBefore).hasSize(3)
        assertThat(slotsBefore).isEqualTo(7)

        // 把疗程收窄到"只有今天" ⇒ 第 2~7 天的待服槽位不再被投影命中
        admin.saveReminderPolicy(
            medicationId = medId,
            draft = MedicationAdminService.PolicyDraft(
                startDate = today.toString(),
                endDate = today.toString(),
                times = listOf(MedicationAdminService.TimeDraft("08:00", 1f, "早间"))
            )
        )
        tracking.reconcileSchedule(medId, today, today.plusDays(6))

        // 待服槽位确实被清了
        assertThat(db.doseSlotDao().getAllSlots().size).isLessThan(slotsBefore)
        // ★ 事实与流水**逐列完全相同**
        assertThat(db.doseRecordDao().getAllRecords().sortedBy { it.id }).isEqualTo(recordsBefore)
        assertThat(db.inventoryTransactionDao().getAllTransactions().sortedBy { it.id })
            .isEqualTo(ledgerBefore)
    }

    @Test
    fun `过去的槽位永不删除 连同已逾期的既成事实一起留存`() = runTest {
        val medId = newDailyMedication()
        val past = today.minusDays(3)
        admin.saveReminderPolicy(
            medicationId = medId,
            draft = MedicationAdminService.PolicyDraft(
                startDate = past.toString(),
                times = listOf(MedicationAdminService.TimeDraft("08:00", 1f, "早间"))
            )
        )
        tracking.reconcileSchedule(medId, past, today.plusDays(3))
        val oldSlot = slotOf(medId, past)
        tracking.takeDose(slotId = oldSlot)
        val recordId = db.doseRecordDao().getRecordBySlotId(oldSlot)!!.id

        repeat(3) { tracking.reconcileSchedule(medId, past, today.plusDays(3)) }

        // ⚠️ "过去绝不触碰"是刻意规则：已逾期的槽位是既成事实的一部分，
        // 删了它统计口径与历史就断了。所以它必须还在。
        assertThat(db.doseSlotDao().getSlotById(oldSlot)).isNotNull()
        assertThat(db.doseRecordDao().getRecordById(recordId)).isNotNull()
    }

    // ==================================================================
    // 3. DAO 静态扫描：改写入口必须逐个显式登记
    // ==================================================================

    /**
     * ⚠️ **这一节原本是错的，先说清楚为什么不能用反射。**
     *
     * 初版写成：
     * ```kotlin
     * dao.declaredMethods.mapNotNull { m ->
     *     m.annotations.filterIsInstance<Query>().firstOrNull()?.value
     * }
     * ```
     * 结果 `filterIsInstance` 恒返回空 —— Room 的 `@Query` 是
     * `@Retention(AnnotationRetention.BINARY)`，**运行期反射看不见**。
     * 同样的写法套在 `@Delete` / `@Update` 上也一样看不见。
     *
     * 结果就是：那条测试**无论 DAO 写成什么样都会绿**。
     * 这正是 `AGENTS.md` §3 点名的"恒真断言" ——
     * 上一轮已有两例"测试全绿但实现是错的"，这里是第三例，
     * 而且是**我自己刚写出来的**。所以下面两条都改成扫源码，
     * 并且给扫描器加了自检（必须扫出已知的那几条，否则报"扫描器坏了"）。
     */

    @Test
    fun `事实表 DAO 源码里不出现 @Delete 或 @Update 注解`() {
        listOf(DoseRecordDao::class.java, InventoryTransactionDao::class.java).forEach { dao ->
            val src = readDaoSource(dao)
            assertThat(src).doesNotContain("@Delete")
            assertThat(src).doesNotContain("@Upsert")
            assertThat(src).doesNotContain("@Update(")
        }
    }

    /**
     * `@Query` 里出现的改写语句必须落在**显式白名单**里，且集合**完全相等**。
     *
     * ⚠️ 断言"完全相等"而不是"是子集"：
     * 子集断言下，废弃一条旧豁免永远不会有人发现（僵尸豁免）；
     * 相等断言下，新增一条改写查询会红 —— 逼人回来讨论它是否正当。
     *
     * 语义上只有这三类入口是正当的：
     * - 整表清空：只在恢复备份前调用（`DataExporter.restoreBackup`）
     * - 撤销打卡：只翻 `status` 一列，且 WHERE 卡死 `status != 'REVERTED'`
     *
     * 2026-09-29 新增 [DoseRecordDao.markReverted]：与 [DoseRecordDao.markRevertedBySlot]
     * **语义完全相同**，只是寻址键从 `slot_id` 换成记录 `id`。
     * 新增它的理由是后者对手动补录的服药（`slot_id == null`）**完全无效** ——
     * 那类记录没有槽位，用户因此没有任何撤销路径。
     * 下面 [DoseRecordDao.markReverted] 的专属用例把它的语义钉死。
     */
    @Test
    fun `事实表上的改写型 Query 全部落在显式白名单内`() {
        val allowed = mapOf(
            DoseRecordDao::class.java to setOf(
                "markRevertedBySlot",
                "markReverted",
                "deleteAllRecords"
            ),
            InventoryTransactionDao::class.java to setOf("deleteAllTransactions")
        )

        allowed.forEach { (dao, whitelist) ->
            // 集合相等这一条同时兜住两种失败：
            // ① 扫描器坏了（路径变了 / 正则失配）⇒ found 为空 ⇒ 不等
            // ② 有人新加了改写查询 ⇒ found 多了 ⇒ 不等
            // 所以不需要额外写一条"扫描器非空"的自检断言。
            assertThat(scanMutatingQueries(dao)).isEqualTo(whitelist)
        }
    }

    /**
     * 撤销打卡只能翻 `status` 一列，且不能作用于已 REVERTED 的行。
     *
     * 这条同时把白名单里那条 `@Query UPDATE` 的**语义**钉死 ——
     * 白名单只管"允许存在"，管不了"改了什么"。
     */
    @Test
    fun `撤销打卡只翻 status 一列且幂等`() = runTest {
        val medId = newDailyMedication()
        val slotId = slotOf(medId, today)
        tracking.takeDose(slotId = slotId, note = "原文备注")
        val original = db.doseRecordDao().getAllRecords().single()

        val affected = db.doseRecordDao().markRevertedBySlot(slotId)
        assertThat(affected).isEqualTo(1)
        val once = db.doseRecordDao().getAllRecords().single()
        assertThat(once.copy(status = original.status)).isEqualTo(original)
        assertThat(once.status).isEqualTo(RecordStatus.REVERTED)

        // 再调一次受影响行数为 0 ⇒ 天然幂等，不存在"翻两次"的路径
        assertThat(db.doseRecordDao().markRevertedBySlot(slotId)).isEqualTo(0)
    }

    /**
     * `markReverted`（按记录 id 撤销）与 `markRevertedBySlot` 同语义。
     *
     * 差别只在寻址键，但正因为键换了，**必须单独钉一遍**：
     * 白名单只保证"允许存在"，管不了"翻的是哪一列、是不是幂等"。
     * 白拿一个"看起来一样"的豁免，是这个白名单最容易腐化的地方。
     */
    @Test
    fun `按记录 id 撤销只翻 status 一列且幂等`() = runTest {
        val medId = newDailyMedication()
        // 手动补录：slot_id == null，旧入口对它完全无效
        val recordId = tracking.logManualDose(
            medicationId = medId,
            actualTs = tsOf(today, 12),
            doseAmount = 0.5f,
            note = "原文备注",
            deductStock = false
        )
        val original = db.doseRecordDao().getRecordById(recordId)!!

        // 旧入口对这条记录**必须**无效 —— 这正是新增 markReverted 的理由
        assertThat(db.doseRecordDao().markRevertedBySlot(slotId = 0)).isEqualTo(0)

        val affected = db.doseRecordDao().markReverted(recordId)
        assertThat(affected).isEqualTo(1)
        val once = db.doseRecordDao().getRecordById(recordId)!!
        // 除 status 外**每一列**都不变（含 note —— 撤销不是清空备注）
        assertThat(once.copy(status = original.status)).isEqualTo(original)
        assertThat(once.status).isEqualTo(RecordStatus.REVERTED)

        // 幂等：第二次受影响行数为 0
        assertThat(db.doseRecordDao().markReverted(recordId)).isEqualTo(0)
    }

    // ==================================================================
    // 源码扫描实现
    // ==================================================================

    private val daoSourceDir = File("src/main/kotlin/com/mcxiaoke/carromed/core/data/dao")

    /**
     * 读 DAO 源文件。**先剥注释** ——
     * `InventoryTransactionDao` 的 KDoc 里就写着
     * 「本 DAO **刻意不提供任何 `@Delete` 或物理删除方法**」，
     * 不剥注释的话正则会把这句文档当成真的注解。
     */
    private fun readDaoSource(dao: Class<*>): String {
        val file = File(daoSourceDir, "${dao.simpleName}.kt")
        assertThat(file.exists()).isTrue()
        return stripComments(file.readText(Charsets.UTF_8))
    }

    /**
     * 扫出所有 `@Query` 里含 `DELETE FROM` / `UPDATE ... SET` / `INSERT INTO` /
     * `REPLACE INTO` 的方法名。
     *
     * 状态机很粗：见到 `@Query` 就开始攒文本，见到下一个 `fun xxx(` 就结账。
     * 粗是故意的 —— 这个 DAO 的写法是"注解紧挨着下一行就是函数声明"，
     * 精确解析多行字符串字面量反而更容易写错。
     */
    private fun scanMutatingQueries(dao: Class<*>): Set<String> {
        val lines = stripComments(readDaoSource(dao)).lines()
        val mutating = mutableSetOf<String>()
        val mutatingPattern = Regex(
            """\b(DELETE\s+FROM|UPDATE\s+\w+\s+SET|INSERT\s+INTO|REPLACE\s+INTO)\b""",
            RegexOption.IGNORE_CASE
        )

        var inQuery = false
        val buf = StringBuilder()

        for (raw in lines) {
            val line = raw.trim()
            if (!inQuery && line.startsWith("@Query")) {
                inQuery = true
                buf.setLength(0)
            }
            if (!inQuery) continue

            buf.append(line).append(' ')

            val decl = FUN_DECL.find(line)
            if (decl != null) {
                if (mutatingPattern.containsMatchIn(buf)) mutating += decl.groupValues[1]
                inQuery = false
            }
        }
        assertThat(inQuery).isFalse()   // 收尾时不应还停在某个 @Query 里
        return mutating
    }

    private val FUN_DECL = Regex("""(?:suspend\s+)?fun\s+(\w+)\s*\(""")

    private fun stripComments(src: String): String =
        src.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""//[^\n]*"""), " ")

    // ==================================================================
    // 辅助
    // ==================================================================

    private fun tsOf(date: LocalDate, hour: Int): Long =
        date.atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
