package com.mcxiaoke.carromed.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mcxiaoke.carromed.core.data.entity.DoseRecordEntity
import com.mcxiaoke.carromed.core.data.model.MedDoseSumRow
import kotlinx.coroutines.flow.Flow

/**
 * 服药事实历史记录数据访问接口
 *
 * ## 不可删除约束（不变量 I11）
 *
 * 本 DAO **刻意不提供任何 `@Delete` 或物理删除方法**。
 * 用户撤销打卡时，走的是 [markRevertedBySlot] 把事实状态改为 `REVERTED`，
 * 而不是 `DELETE FROM dose_records`。
 *
 * 原因：产品第二承诺是「记录真实 —— 吃过的药永不丢失、永不串改」，
 * `FINAL-PRODUCT` 场景 2 也要求"事实层追加 REVERT 修正…全程留痕"。
 * 物理删除还会让库存台账里指向该记录的 `record_id` 变成悬空引用。
 *
 * 删药品本身仍会级联清空（外键 `ON DELETE CASCADE`），那是用户的明确意图，与本约束不冲突。
 */
@Dao
interface DoseRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: DoseRecordEntity): Long

    /**
     * 撤销：把该槽位对应的服药事实标记为 `REVERTED`（保留记录，仅改状态）。
     * 返回受影响行数。
     */
    @Query("UPDATE dose_records SET status = 'REVERTED' WHERE slot_id = :slotId AND status != 'REVERTED'")
    suspend fun markRevertedBySlot(slotId: Long): Int

    /**
     * 按**记录 id** 撤销，而不是按槽位。
     *
     * [markRevertedBySlot] 依赖 `slot_id`，而手动补录的服药
     * （`slot_id == null`，即 PRN / 临时用药）**没有槽位**——
     * 也就是说旧写法对这类记录完全无效，用户没有任何撤销路径。
     * 服药记录详情页需要按 id 撤销，所以补上这个入口。
     *
     * `status != 'REVERTED'` 保证**幂等**：重复撤销不会覆盖已经记过的状态。
     * 返回受影响行数，供调用方区分"确实撤销了"与"本来就不可撤销"。
     */
    @Query("UPDATE dose_records SET status = 'REVERTED' WHERE id = :recordId AND status != 'REVERTED'")
    suspend fun markReverted(recordId: Long): Int

    /**
     * 改剂量。**只动这一列**。
     *
     * 条件里的 `status != 'REVERTED'` 是幂等锚点：已撤销的记录不允许再被改回来
     * （撤销是既成事实，补偿记录，不是草稿）。
     * 返回受影响行数，0 表示"没这条 / 已撤销 / 值没变"。
     */
    @Query(
        """
        UPDATE dose_records SET dose_taken = :doseMilli
        WHERE id = :recordId AND status != 'REVERTED'
        """
    )
    suspend fun updateDose(recordId: Long, doseMilli: Int): Int

    /**
     * 改备注。**只动这一列**，且同样拒绝已撤销的记录。
     *
     * 备注不产生任何台账流水 —— 它不改变"吃了多少"，只改变"怎么描述的"。
     */
    @Query("UPDATE dose_records SET note = :note WHERE id = :recordId AND status != 'REVERTED'")
    suspend fun updateNote(recordId: Long, note: String?): Int

    /**
     * 改实际服药时刻。**只动这一列**，同样拒绝已撤销的记录。
     *
     * 改时间**不产生台账流水** —— 时间不影响"吃了多少"，只影响"什么时候吃的"，
     * 而库存台账记的是数量。
     */
    @Query("UPDATE dose_records SET actual_ts = :actualTs WHERE id = :recordId AND status != 'REVERTED'")
    suspend fun updateActualTs(recordId: Long, actualTs: Long): Int

    @Query("SELECT * FROM dose_records WHERE id = :id")
    suspend fun getRecordById(id: Long): DoseRecordEntity?

    @Query("SELECT * FROM dose_records WHERE slot_id = :slotId ORDER BY id ASC LIMIT 1")
    suspend fun getRecordBySlotId(slotId: Long): DoseRecordEntity?

    /**
     * 该槽位下**全部**仍是 `COMPLETED` 的事实。
     *
     * ## 为什么撤销必须用这个而不是 [getRecordBySlotId]
     *
     * 一个槽位可以有多条事实：每次 `takeDose` 插一条，而 `undoDose` 从不删行
     * （I11 要求「吃过的药永不丢失」）。而 [getRecordBySlotId] 是
     * `ORDER BY id ASC LIMIT 1`，取的是**最早那条** ——
     * 一次「打卡 → 撤销 → 再打卡」之后，最早那条已是 `REVERTED`，
     * 于是第二次撤销拿到它、据此判定「这条没扣过、不必冲正」，
     * **账面凭空少一次扣减**，且 `SUM(change_amount) == balance` 依然成立（I1 拦不住）。
     *
     * 撤销要冲正的是「**这轮实际还欠多少扣**」，可能涉及多条事实，所以必须一次取全。
     */
    @Query("SELECT * FROM dose_records WHERE slot_id = :slotId AND status = 'COMPLETED' ORDER BY id ASC")
    suspend fun getCompletedRecordsBySlot(slotId: Long): List<DoseRecordEntity>

    /** 该槽位的全部事实（含已撤销），用于审计与"连点几次"排查 */
    @Query("SELECT * FROM dose_records WHERE slot_id = :slotId ORDER BY id ASC")
    suspend fun getAllRecordsBySlotId(slotId: Long): List<DoseRecordEntity>

    /** 一批槽位的已完成事实，供列表页一次性取回（避免 N+1） */
    @Query(
        """
        SELECT * FROM dose_records
        WHERE slot_id IN (:slotIds) AND status = 'COMPLETED'
        """
    )
    suspend fun getCompletedRecordsForSlots(slotIds: List<Long>): List<DoseRecordEntity>

    /**
     * 一批槽位的**未撤销**事实（`COMPLETED` + `SKIPPED`），按 id 升序（避免 N+1）。
     *
     * 供"已服 / 已跳过"列表展示：同一槽位可能有多条事实（改判/撤销后重新表态），
     * 展示要取**最新那条** —— 取最早条会在"跳过 → 撤销 → 再跳过"后展示出
     * 已作废的旧事实（DB C-21）。调用方按 `slot_id` 分组后取每组末条。
     */
    @Query(
        """
        SELECT * FROM dose_records
        WHERE slot_id IN (:slotIds) AND status != 'REVERTED'
        ORDER BY id ASC
        """
    )
    suspend fun getActiveRecordsForSlots(slotIds: List<Long>): List<DoseRecordEntity>

    /**
     * 未归档药品的服药事实总数，**只作为变化探针**（sba P1-1 残留）。
     *
     * 槽位探针（`DoseSlotDao.observeDecidedSlotCount`）盯的是 `dose_slots` 的状态列，
     * 而**手动补录**不产生槽位 —— 只写 `dose_records`。没有这条探针，
     * 纯补录之后统计页的"累计用量 / 排行榜"不会刷新。
     * JOIN `medications` 让"归档"同样成为可观察事件（与槽位探针同一条纪律）。
     */
    @Query(
        """
        SELECT COUNT(*)
        FROM dose_records r
        INNER JOIN medications m ON m.id = r.medication_id
        WHERE m.is_archived = 0
        """
    )
    fun observeRecordCount(): Flow<Int>

    /**
     * 单药品的服药事实计数，**只作为变化探针**（osbf P2-3）：
     * 历史页订阅它，补录 / 撤销 / 改剂量后返回列表即时重算。
     * 不 JOIN 归档过滤 —— 归档药的历史页同样要能反映新变化。
     */
    @Query("SELECT COUNT(*) FROM dose_records WHERE medication_id = :medicationId")
    fun observeRecordCountForMedication(medicationId: Long): Flow<Int>

    /**
     * 单药历史（进展 → 点某个药）。
     *
     * ⚠️ 与流水同口径：**不返回已撤销的事实**。撤销是动作不是状态，
     * 用户撤销掉的那条不该继续出现在"我吃了什么"的清册里。
     *
     * 药品详情页的「最近服药记录」也走这个查询，所以两处口径天然一致 ——
     * 不必在 UI 层再各写一份过滤（那必然漂移）。
     */
    @Query(
        """
        SELECT * FROM dose_records
        WHERE medication_id = :medicationId AND status != 'REVERTED'
        ORDER BY actual_ts DESC
        """
    )
    suspend fun getRecordsForMedication(medicationId: Long): List<DoseRecordEntity>

    /**
     * 服药流水翻页：**keyset 游标**取一批比 [beforeTs] 更早的服药事实。
     *
     * ## 为什么不用 `LIMIT :offset, :limit`
     *
     * 翻页期间用户随时可能撤销/删除记录。OFFSET 分页是按**行号**定位的，
     * 一旦前面少了一行，后面每一页都会**整段错位**——结果是静默漏记录。
     * 游标分页按**时间戳**定位，对数据的并发变更免疫。
     *
     * 本项目服药事实是 append-only（撤销只改状态、不删行，见本文件顶部），
     * 这与游标分页的取向天然一致。
     *
     * ## 严格小于而不是 `<=`
     *
     * 用 `<=` 时，同一时间戳的记录会在相邻两页各出现一次。
     * 时间戳精确到毫秒，理论上会有同刻记录（同一次操作写多行），
     * 所以 `<` 不是可有可无的严谨，而是**正确性要求**。
     *
     * ## 索引
     *
     * `DoseRecordEntity` 已有 `Index(value = ["actual_ts"])`，
     * `ORDER BY actual_ts DESC LIMIT` 走索引，**无需新增索引**。
     *
     * ## 为什么不返回已撤销的事实
     *
     * 见 [observeLatestRecords] 的说明：撤销是**动作**而不是状态，
     * 用户撤销过的记录不该继续占用流水的位置。过滤同样落在 SQL 里，
     * 否则 keyset 分页的页大小会不稳定。
     */
    @Query(
        """
        SELECT * FROM dose_records
        WHERE actual_ts < :beforeTs AND status != 'REVERTED'
        ORDER BY actual_ts DESC
        LIMIT :limit
        """
    )
    suspend fun getRecordsBefore(beforeTs: Long, limit: Int): List<DoseRecordEntity>

    /**
     * [getRecordsBefore] 的 reactive 版本，供流水页首屏跟随写入刷新。
     *
     * 只用于首屏（`beforeTs = Long.MAX_VALUE`），翻页仍走 suspend 版本 ——
     * 分页不该每一页都订阅一个 Flow，那会让"已加载"这个状态无处安放。
     *
     * ## ⚠️ 不返回已撤销的事实（2026-09-29 用户决定）
     *
     * 撤销是一个**动作**，不是一个状态：用户点「撤销」的意思是"这条不该存在"，
     * 而流水里留一条标着「已撤销」的灰条，等于把他刚撤销掉的东西又摆回眼前
     * （测试撤销几次后，流水里连着四条"已撤销"，用户的原话是"我感觉撤销只是一个动作"）。
     *
     * 事实本身仍然留在库里（append-only，不变量 I11），只是不进这**两个视图**；
     * 需要完整事实的地方走 [getAllRecords]（备份 / 导出 / 诊断）。
     *
     * ## 过滤必须落在 SQL 里，不能取回后再 filter
     *
     * 后者会让**页大小不稳定**：一页 20 条里若有 17 条被丢掉，首屏只剩 3 条，
     * 而"是否还有更早"的判断（`items.size >= PAGE_SIZE`）也跟着失真 ——
     * 用户会看到一个永远填不满、还提前说"没有更早的记录"的列表。
     */
    @Query("SELECT * FROM dose_records WHERE status != 'REVERTED' ORDER BY actual_ts DESC LIMIT :limit")
    fun observeLatestRecords(limit: Int): Flow<List<DoseRecordEntity>>

    @Query("SELECT * FROM dose_records WHERE actual_ts BETWEEN :startTs AND :endTs ORDER BY actual_ts DESC")
    fun observeRecordsInRange(startTs: Long, endTs: Long): Flow<List<DoseRecordEntity>>

    @Query("SELECT * FROM dose_records WHERE actual_ts BETWEEN :startTs AND :endTs ORDER BY actual_ts DESC")
    suspend fun getRecordsInRange(startTs: Long, endTs: Long): List<DoseRecordEntity>

    /**
     * 区间内已服剂量的合计，**整数毫单位**（1 片 = 1000）。
     *
     * ## 为什么签名必须是 `Int?` 而不是 `Float?`
     *
     * 原先声明成 `Float?`，而 `SUM(dose_taken)` 返回的是毫单位 ——
     * 类型上**完全无法与展示值区分**，于是调用方 `?: 0f` 直接当成片数用了。
     * 实测后果：吃过 1 片的药，药品详情页显示「近 30 天共消耗 **1000 片**」。
     *
     * 这是 D-7 落地以来的**第 5 次量纲混用**。前 4 次都在 UI 层（手写 `/1000f`），
     * 这次落在 DAO 边界上 —— 而 DAO 边界是唯一**本该拦住**它的地方，
     * 因为实体的 `doseAmount: Int` 本来就带着单位信息，是 `Float?` 这个签名把它抹掉了。
     *
     * 同文件的 `getDoseSumByMedicationInRange` 返回 `MedDoseSumRow.totalDose: Int`，
     * 那个才是正确范式。
     */
    @Query("SELECT SUM(dose_taken) FROM dose_records WHERE medication_id = :medicationId AND status = 'COMPLETED' AND actual_ts BETWEEN :startTs AND :endTs")
    suspend fun getSumDoseTakenForMedication(medicationId: Long, startTs: Long, endTs: Long): Int?

    @Query("SELECT COUNT(*) FROM dose_records WHERE medication_id = :medicationId AND status = 'COMPLETED' AND actual_ts BETWEEN :startTs AND :endTs")
    suspend fun countDoseRecordsForMedication(medicationId: Long, startTs: Long, endTs: Long): Int

    /**
     * **全部**服药事实，含已撤销的那些。
     *
     * ⚠️ 这是唯一不过滤 `REVERTED` 的读取入口，**只给备份 / 导出 / 诊断用**。
     *
     * 别拿它当列表数据源：撤销是动作不是状态，流水与历史都不该出现
     * 「已撤销」的行（见 [observeLatestRecords]）。而备份必须完整 ——
     * 事实被撤销过这件事本身也是历史的一部分，恢复时不能丢。
     */
    @Query("SELECT * FROM dose_records ORDER BY actual_ts ASC")
    suspend fun getAllRecords(): List<DoseRecordEntity>

    /**
     * 区间内按药品汇总的 **实际消耗剂量** (仅 COMPLETED 事实)。
     * 与依从率口径不同：消耗量描述"真实吃掉了多少"，按实际服药时刻归属。
     */
    @Query(
        """
        SELECT medication_id AS medId, SUM(dose_taken) AS total
        FROM dose_records
        WHERE status = 'COMPLETED' AND actual_ts BETWEEN :startTs AND :endTs
        GROUP BY medication_id
        ORDER BY total DESC
        """
    )
    fun observeDoseSumByMedicationInRange(
        startTs: Long,
        endTs: Long
    ): Flow<List<MedDoseSumRow>>

    @Query(
        """
        SELECT medication_id AS medId, SUM(dose_taken) AS total
        FROM dose_records
        WHERE status = 'COMPLETED' AND actual_ts BETWEEN :startTs AND :endTs
        GROUP BY medication_id
        ORDER BY total DESC
        """
    )
    suspend fun getDoseSumByMedicationInRange(
        startTs: Long,
        endTs: Long
    ): List<MedDoseSumRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<DoseRecordEntity>): List<Long>

    /**
     * ⚠️ **唯一的例外**：整库清空，仅供 `DataExporter.importBackup` 的"覆盖式恢复"使用。
     *
     * 恢复是用户明确发起的破坏性操作（且恢复前会做本地快照 + 二次确认），
     * 与"撤销打卡不得抹除事实"是两条不同的语义路径。
     * 除此之外，本 DAO 不提供任何按 id / 按 slot 的删除方法。
     */
    @Query("DELETE FROM dose_records")
    suspend fun deleteAllRecords()
}
