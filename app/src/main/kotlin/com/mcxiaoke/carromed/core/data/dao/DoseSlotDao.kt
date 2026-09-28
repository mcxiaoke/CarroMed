package com.mcxiaoke.carromed.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mcxiaoke.carromed.core.data.entity.DoseSlotEntity
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import com.mcxiaoke.carromed.core.data.model.SlotStatusCountRow
import kotlinx.coroutines.flow.Flow

/**
 * 服药排班槽位数据访问接口
 */
@Dao
interface DoseSlotDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(slot: DoseSlotEntity): Long

    /**
     * 批量插入。**冲突策略是 IGNORE 而非 REPLACE**（配合 `(medication_id, scheduled_date,
     * scheduled_time)` 的 UNIQUE 索引）：
     *
     * - `REPLACE` 会把已有行删掉再插入新行 → **id 改变 → 闹钟 requestCode 改变 →
     *   旧闹钟变成永远不会被取消的孤儿**。这是 P1-5 闹钟泄漏的直接成因。
     * - `IGNORE` 保留已有行（连同它的 id 与状态），重复键只跳过这一行，
     *   于是"重物化同一窗口"天然幂等。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(slots: List<DoseSlotEntity>): List<Long>

    @Update
    suspend fun update(slot: DoseSlotEntity)

    /**
     * 同步**可从投影完全派生**的两列：剂量与所属策略。
     *
     * ## 为什么必须存在
     *
     * 幂等 diff 的键是**日历** `(scheduled_date, scheduled_time)`，
     * 命中的槽位走"留"分支。而 [update] 在生产代码里从不调用 ——
     * 也就是槽位的 `dose_amount` 在其**整个生命周期内被冻结在创建时的值**。
     *
     * 后果：用户把剂量从 1 片改成 2 片，之后 14 天每次打卡都按 1 片扣库存。
     * 而 `dose_records.dose_taken` 是不可变事实（I11），错误被永久固化。
     *
     * 改「服药时刻」不受影响（key 变化 ⇒ 删旧插新 ⇒ 剂量自然新），
     * 漏的恰恰是「时刻不变、剂量变了」这条最高频的路径。
     *
     * ## 为什么只写这两列
     *
     * 它们是投影的**纯函数输出** —— 只要当前策略是权威的，写回就是幂等的。
     * 其余列（`status` / `actual_taken_ts` / `snooze_until_ts` / `scheduled_ts`）
     * 要么是事实、要么由其它命令管理，混进这里就会让对账**重置用户操作**。
     *
     * `status IN ('PENDING','SNOOZED')` 的守卫写在 SQL 里而不是调用方 ——
     * 与 [markCompletedIfOpen] / [markSkippedIfOpen] 同一套风格，
     * 这样"已产生结论的槽位不会被对账改动"就成了一条**结构性**保证。
     *
     * @return 受影响行数；0 表示该槽位已不再开放（已被打卡/跳过/结算）
     */
    @Query(
        """
        UPDATE dose_slots
        SET dose_amount = :doseMilli, policy_id = :policyId
        WHERE id = :slotId AND status IN ('PENDING', 'SNOOZED')
        """
    )
    suspend fun updateDerivedColumns(slotId: Long, doseMilli: Int, policyId: Long): Int

    /** 幂等 diff 的"删"步骤：只删真正不再被投影命中的槽位 */
    @Query("DELETE FROM dose_slots WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>): Int

    @Query("SELECT * FROM dose_slots WHERE id = :id")
    suspend fun getSlotById(id: Long): DoseSlotEntity?

    @Query("SELECT * FROM dose_slots WHERE id = :id")
    fun observeSlotById(id: Long): Flow<DoseSlotEntity?>

    @Query("SELECT * FROM dose_slots WHERE scheduled_date = :dateStr ORDER BY scheduled_ts ASC")
    fun observeSlotsForDate(dateStr: String): Flow<List<DoseSlotEntity>>

    @Query("SELECT * FROM dose_slots WHERE scheduled_date = :dateStr ORDER BY scheduled_ts ASC")
    suspend fun getSlotsForDate(dateStr: String): List<DoseSlotEntity>

    @Query("SELECT * FROM dose_slots WHERE scheduled_date BETWEEN :startDate AND :endDate ORDER BY scheduled_date ASC, scheduled_ts ASC")
    fun observeSlotsInRange(startDate: String, endDate: String): Flow<List<DoseSlotEntity>>

    @Query("SELECT * FROM dose_slots WHERE scheduled_date BETWEEN :startDate AND :endDate ORDER BY scheduled_date ASC, scheduled_ts ASC")
    suspend fun getSlotsInRange(startDate: String, endDate: String): List<DoseSlotEntity>

    @Query("SELECT * FROM dose_slots WHERE status = 'PENDING' AND scheduled_ts >= :fromTs ORDER BY scheduled_ts ASC")
    suspend fun getPendingSlotsAfter(fromTs: Long): List<DoseSlotEntity>

    @Query("SELECT * FROM dose_slots WHERE medication_id = :medicationId AND status = 'PENDING' AND scheduled_ts >= :fromTs ORDER BY scheduled_ts ASC")
    suspend fun getPendingSlotsForMedicationAfter(medicationId: Long, fromTs: Long): List<DoseSlotEntity>

    @Query("UPDATE dose_slots SET status = :status, actual_taken_ts = :actualTs WHERE id = :slotId")
    suspend fun updateStatus(slotId: Long, status: SlotStatus, actualTs: Long? = null)

    /**
     * 幂等打卡：只有仍在等待的槽位才被置为 COMPLETED。
     *
     * 返回受影响的行数 —— 0 表示该槽位已被处理过，调用方应放弃后续记账。
     * 把幂等锚点放在 SQL 的 WHERE 里（而不是"先查后写"）有两个好处：
     * 1. 读与写合成一个原子操作，不存在"查完还没写"的竞态窗口；
     * 2. 所有调用方（App 内打卡 / 通知栏 Action / 手表 / 未来任何入口）自动受益。
     */
    @Query(
        """
        UPDATE dose_slots
        SET status = 'COMPLETED', actual_taken_ts = :actualTs
        WHERE id = :slotId AND status IN ('PENDING', 'SNOOZED')
        """
    )
    suspend fun markCompletedIfOpen(slotId: Long, actualTs: Long): Int

    /** 幂等跳过：允许对已逾期(EXPIRED)的槽位补记跳过，但不允许覆盖已完成/已跳过。 */
    @Query(
        """
        UPDATE dose_slots
        SET status = 'SKIPPED', actual_taken_ts = :actualTs
        WHERE id = :slotId AND status IN ('PENDING', 'SNOOZED', 'EXPIRED')
        """
    )
    suspend fun markSkippedIfOpen(slotId: Long, actualTs: Long): Int

    /**
     * 推迟：置 `SNOOZED` 并记下推迟到期时刻。
     *
     * ## 状态守卫为什么必须有
     *
     * 同文件的 [markCompletedIfOpen] / [markSkippedIfOpen] / [revertToPending] 都把
     * `status IN (...)` 下沉进 SQL 当幂等锚点，唯独这里原先没有。
     *
     * 缺了守卫的可达路径：长按待服卡片 → 弹窗停留期间，通知栏的「确认已吃」
     * 被点了（通知 / 手表 / 小组件都算另一个入口）→ 槽位变 `COMPLETED`、
     * 事实入库、**库存已扣** → 用户再点「10 分」⇒ 槽位被改回 `SNOOZED`。
     *
     * 之后 SNOOZE 闹钟再响一次，用户点「确认已吃」——
     * [markCompletedIfOpen] 允许 `SNOOZED` ⇒ **为同一槽位插入第二条 COMPLETED 事实
     * 并二次扣减库存**。
     *
     * 把守卫放进 SQL 而不是调用方，与那三条保持同一套风格：
     * 判据落在数据上，调用方不必也不能绕过。
     */
    @Query("UPDATE dose_slots SET status = 'SNOOZED', snooze_until_ts = :snoozeUntilTs WHERE id = :slotId AND status IN ('PENDING', 'SNOOZED')")
    suspend fun snoozeSlot(slotId: Long, snoozeUntilTs: Long): Int

    /**
     * 撤销打卡：把槽位置回 PENDING 并清空实际服药时刻。
     * 仅对已 COMPLETED / SKIPPED 的槽位生效（受影响行数为 0 表示本就无需撤销）。
     */
    @Query(
        """
        UPDATE dose_slots
        SET status = 'PENDING', actual_taken_ts = NULL, snooze_until_ts = NULL
        WHERE id = :slotId AND status IN ('COMPLETED', 'SKIPPED')
        """
    )
    suspend fun revertToPending(slotId: Long): Int

    /**
     * 丢弃**投机区**（`scheduled_date > :afterDate`）里仍开放的槽位。
     *
     * 投机区 = 对账窗口 `toDate` 之外的未来。之所以整段丢弃而不是逐个 diff：
     * 我们**只对窗口内做过权威投影**，窗口外那些行是上一次对账按当时计划版本
     * 顺手多排的猜测。计划一改（改时刻、改频次、停药），这些猜测就变成了
     * 「已不存在的时刻」上的待服槽位。
     *
     * 若不清理，`AlarmReconciler` 的"给所有开放槽位排闹钟"会给它们注册闹钟，
     * 而后续对账再也不会去改它们 —— 用户收到一个**已不存在的服药时间的提醒**，
     * 且该闹钟永远不会被取消。
     *
     * 只删 `PENDING` / `SNOOZED`：已完成的槽位是既成事实，投机与否都得留着。
     */
    @Query(
        """
        DELETE FROM dose_slots
        WHERE medication_id = :medicationId
          AND scheduled_date > :afterDate
          AND status IN ('PENDING', 'SNOOZED')
        """
    )
    suspend fun deleteSpeculativeFutureSlots(medicationId: Long, afterDate: String): Int

    @Query("SELECT COUNT(*) FROM dose_slots WHERE scheduled_date = :dateStr AND status = 'COMPLETED'")
    suspend fun countCompletedSlotsForDate(dateStr: String): Int

    @Query("SELECT COUNT(*) FROM dose_slots WHERE scheduled_date = :dateStr")
    suspend fun countTotalSlotsForDate(dateStr: String): Int

    @Query("SELECT * FROM dose_slots ORDER BY id ASC")
    suspend fun getAllSlots(): List<DoseSlotEntity>

    /**
     * 区间内按 [药品 + 计划日期 + 状态] 聚合的槽位计数。
     * 统计报表的依从率与进展页的打卡矩阵共用此查询，保证两者口径完全一致，
     * 且"依从率"以 **计划时间 (scheduled_date)** 归属，不受补录时刻影响。
     */
    @Query(
        """
        SELECT medication_id AS medId,
               scheduled_date AS date,
               status AS status,
               COUNT(*) AS cnt
        FROM dose_slots
        WHERE scheduled_date BETWEEN :startDate AND :endDate
        GROUP BY medication_id, scheduled_date, status
        """
    )
    fun observeSlotStatusCounts(
        startDate: String,
        endDate: String
    ): Flow<List<SlotStatusCountRow>>

    /** 单个药品的区间槽位状态计数 (药品详情页的依从率) */
    @Query(
        """
        SELECT medication_id AS medId,
               scheduled_date AS date,
               status AS status,
               COUNT(*) AS cnt
        FROM dose_slots
        WHERE scheduled_date BETWEEN :startDate AND :endDate
          AND medication_id = :medicationId
        GROUP BY medication_id, scheduled_date, status
        """
    )
    fun observeSlotStatusCountsForMedication(
        medicationId: Long,
        startDate: String,
        endDate: String
    ): Flow<List<SlotStatusCountRow>>

    /** 统计报表 / 详情页依从率用的一次性聚合查询 */
    @Query(
        """
        SELECT medication_id AS medId,
               scheduled_date AS date,
               status AS status,
               COUNT(*) AS cnt
        FROM dose_slots
        WHERE scheduled_date BETWEEN :startDate AND :endDate
        GROUP BY medication_id, scheduled_date, status
        """
    )
    suspend fun getSlotStatusCounts(
        startDate: String,
        endDate: String
    ): List<SlotStatusCountRow>

    @Query("SELECT * FROM dose_slots WHERE status IN ('PENDING', 'SNOOZED') ORDER BY scheduled_ts ASC")
    suspend fun getOpenSlots(): List<DoseSlotEntity>

    /**
     * 取出"计划时间已经过去、仍开放"的槽位，供对账器结算为 `EXPIRED`。
     *
     * ## 为什么两类槽位要用**各自**的基准时间
     *
     * | 状态 | 基准 | 理由 |
     * | :--- | :--- | :--- |
     * | `PENDING` | `scheduled_ts` | 没人在乎它，按原计划时间算逾期 |
     * | `SNOOZED` | `snooze_until_ts` | **用户主动改期了** —— 按原计划时间算会把"我明确说了 10:30 再叫我"也算成逾期，那是冤枉 |
     *
     * ## 修的是什么（P1-2）
     *
     * 旧实现只查 `status = 'PENDING'`，于是被推迟过的槽位**永远不会**被结算：
     *
     * - 它一直挂在今日清单上，标着「已推迟」，而闹钟早已不再排
     * - `StatsEngine` 把 `PENDING` 与 `SNOOZED` 一起算作 `pending`，
     *   而 `pending` **不进依从率分母** ⇒ 这次服药既不算已服也不算漏服，直接消失
     *
     * 也就是说：用户推迟后如果当天没再回来，这一条既不会兑现提醒，
     * 也不会进入任何统计 —— 变成一条凭空蒸发的记录。
     *
     * 采取「对账时结算」而不是「推迟时另排一个 EXPIRED 定时任务」，
     * 是因为后者要引入第三种闹钟身份（而它的触发时刻同时依赖 `snoozeMinutes`
     * 与逾期窗口两个参数），也就多一条会静默失效的依赖链路。
     * 逾期窗口长达 2 小时，15 分钟的对账粒度对它毫无影响。
     *
     * ## 为什么不宽限
     *
     * 两个 cutoff 传的是**同一个** `now - EXPIRE_WINDOW_MS`，逾期宽限
     * 在整个 App 里只有这一个定义（`AlarmReconciler.EXPIRE_WINDOW_MS`）。
     * 推迟不再额外加一轮宽限 —— 用户主动给的期限就是期限。
     */
    @Query(
        """
        SELECT * FROM dose_slots
        WHERE (status = 'PENDING' AND scheduled_ts < :pendingCutoffTs)
           OR (status = 'SNOOZED' AND snooze_until_ts IS NOT NULL
               AND snooze_until_ts < :snoozeCutoffTs)
        """
    )
    suspend fun getStaleOpenSlots(pendingCutoffTs: Long, snoozeCutoffTs: Long): List<DoseSlotEntity>

    /**
     * 把过期槽位置为 `EXPIRED`，并清空 `actual_taken_ts` 与 `snooze_until_ts`。
     *
     * 单独一个方法而不是给 [updateStatus] 加参数：[updateStatus] 也被
     * `takeDose` / `skipDose` 走，那两条路径**必须**保留 `snooze_until_ts`
     *（用户推迟过这件事是历史的一部分）。而结算的语义就是"清干净"。
     *
     * 顺带清 `snooze_until_ts` 是为了不留脏值：`SNOOZED` 的槽位带着
     * 一个早已过期的推迟时刻，读代码的人会以为它还生效。
     *
     * @return 受影响行数；0 表示已被别的路径结算过（天然幂等）
     */
    @Query("UPDATE dose_slots SET status = 'EXPIRED', actual_taken_ts = NULL, snooze_until_ts = NULL WHERE id = :slotId")
    suspend fun markExpired(slotId: Long): Int

    @Query("DELETE FROM dose_slots")
    suspend fun deleteAllSlots()
}
