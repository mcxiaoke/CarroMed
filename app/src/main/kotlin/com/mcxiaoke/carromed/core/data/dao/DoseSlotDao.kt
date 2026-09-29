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
     * 同步**可从投影完全派生**的三列：剂量、所属策略、计划时间戳。
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
     * ## `scheduled_ts` 为什么也在其中（M3-1）
     *
     * 改「服药时刻」会换 key，所以 diff 天然会删旧插新 —— 这掩盖了一个更深的洞：
     * **`scheduled_ts` 是投影的纯函数输出**，`epochMilli = LocalDateTime.of(date, time).atZone(zoneId)`。
     * 它依赖 `zoneId`，而时区是会变的。
     *
     * 旧实现只写 `dose_amount` / `policy_id`，于是改时区后：
     * diff 命中"留"分支 ⇒ 新时区算出来的 epoch 被**丢弃** ⇒
     * 用户从北京飞到纽约，「每天 08:00」继续按**北京时间**响。
     *
     * 全工程没有任何一条 UPDATE 写这一列，所以它此前是"创建时冻结"的。
     * 加上它之后同区重算结果相同（天然幂等），
     * 而 `DoseSlotDstServiceTest` 的 DST 语义也不受影响 ——
     * DST 是**同一天内**的时刻漂移，diff 命中"留"分支时同样需要重算。
     *
     * ## 为什么只写这三列
     *
     * 它们是投影的**纯函数输出** —— 只要当前策略与当前时区是权威的，写回就是幂等的。
     * 其余列（`status` / `actual_taken_ts` / `snooze_until_ts`）
     * 是事实、由其它命令管理，混进这里就会让对账**重置用户操作**。
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
        SET dose_amount = :doseMilli, policy_id = :policyId, scheduled_ts = :scheduledTs
        WHERE id = :slotId AND status IN ('PENDING', 'SNOOZED')
        """
    )
    suspend fun updateDerivedColumns(
        slotId: Long,
        doseMilli: Int,
        policyId: Long,
        scheduledTs: Long
    ): Int

    /** 幂等 diff 的"删"步骤：只删真正不再被投影命中的槽位 */
    @Query("DELETE FROM dose_slots WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>): Int

    @Query("SELECT * FROM dose_slots WHERE id = :id")
    suspend fun getSlotById(id: Long): DoseSlotEntity?

    /**
     * 按**业务键**取开放槽位的 id。
     *
     * ## 为什么需要它（M5-1）
     *
     * 闹钟身份是内容寻址的 `carromed://alarm/{medId}/{date}/{time}/{kind}`，
     * 而 `dose_slots.id` 是**会变**的：备份恢复用备份里的 id 覆盖当前库。
     * 于是"恢复前排下的闹钟"带着旧 id、"恢复后的库"用新 id，两边 id 空间交叠。
     *
     * `AlarmReceiver` 若按 extras 里的 slotId 反查，就会**取到另一个槽位** ——
     * 给完全不相干的药发提醒。所以它改为按 `(medId, date, time)` 反查。
     *
     * 顺带解决第二个问题：槽位已被打卡/结算时返回 `null`，
     * Receiver 直接静默返回，不需要再单独查一次状态。
     */
    @Query(
        """
        SELECT id FROM dose_slots
        WHERE medication_id = :medicationId
          AND scheduled_date = :scheduledDate
          AND scheduled_time = :scheduledTime
          AND status IN ('PENDING', 'SNOOZED')
        LIMIT 1
        """
    )
    suspend fun findOpenSlotId(
        medicationId: Long,
        scheduledDate: String,
        scheduledTime: String
    ): Long?

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
     * 幂等打卡：`PENDING` / `SNOOZED` / `EXPIRED` 的槽位都可被置为 COMPLETED。
     *
     * 返回受影响的行数 —— 0 表示该槽位已被处理过，调用方应放弃后续记账。
     * 把幂等锚点放在 SQL 的 WHERE 里（而不是"先查后写"）有两个好处：
     * 1. 读与写合成一个原子操作，不存在"查完还没写"的竞态窗口；
     * 2. 所有调用方（App 内打卡 / 通知栏 Action / 手表 / 未来任何入口）自动受益。
     *
     * ## 为什么 EXPIRED 也在守卫里（P2#5）
     *
     * 今日页对逾期槽位渲染着确认按钮（徽标"已逾期…尚未确认"），按钮必须有效；
     * 且 [markSkippedIfOpen] 一直允许 EXPIRED ⇒ SKIPPED（"补记跳过"）——
     * 补记已服没有理由被单独禁止，否则按钮存在却永远失败、toast 只能撒谎
     * （"该提醒已处理过"，事实是从未有机会处理）。补记后逾期转入已服，
     * 事实照常入库、库存照常扣减；撤销路径（[revertToPending]）随之可用。
     */
    @Query(
        """
        UPDATE dose_slots
        SET status = 'COMPLETED', actual_taken_ts = :actualTs
        WHERE id = :slotId AND status IN ('PENDING', 'SNOOZED', 'EXPIRED')
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

    /**
     * 统计报表 / 详情页依从率用的一次性聚合查询。
     *
     * ## 归档过滤（决策 E / M4-3）
     *
     * **已归档的药品不进分母。** 此前这个查询没有归档过滤，而排行榜与
     * "在服药品数"都只算 active —— 同一屏的数字来自**两个不同的集合**：
     * 药已停用三个月，它的旧槽位仍然把统计页的依从率往下拉，
     * 而用户在同一屏看到的"在服药品 2 种"告诉他只剩两种药在吃。
     *
     * 用 INNER JOIN 而不是 `NOT IN (归档 id 列表)`：归档判定住在 `medications` 表里，
     * JOIN 让"什么算 active"只有**一个**定义（`is_archived = 0`），
     * 不会与 `getActiveOverviews` 的口径各写一份然后漂移。
     *
     * ⚠️ 这与 [observeSlotStatusCounts]（进展页打卡矩阵）**故意不同** ——
     * 那张矩阵要展示全部历史，包括已归档的药（用户要看到"我过去吃了什么"）。
     * 两处口径不同是**有意的**，不是遗漏。
     */
    @Query(
        """
        SELECT s.medication_id AS medId,
               s.scheduled_date AS date,
               s.status AS status,
               COUNT(*) AS cnt
        FROM dose_slots s
        INNER JOIN medications m ON m.id = s.medication_id
        WHERE s.scheduled_date BETWEEN :startDate AND :endDate
          AND m.is_archived = 0
        GROUP BY s.medication_id, s.scheduled_date, s.status
        """
    )
    suspend fun getSlotStatusCounts(
        startDate: String,
        endDate: String
    ): List<SlotStatusCountRow>

    @Query("SELECT * FROM dose_slots WHERE status IN ('PENDING', 'SNOOZED') ORDER BY scheduled_ts ASC")
    suspend fun getOpenSlots(): List<DoseSlotEntity>

    /**
     * 「已产生结论」且**药品未归档**的槽位总数，**只作为变化探针**。
     *
     * ## 它解决的是"统计页不刷新"（M4-2）
     *
     * 统计页的依从率全部来自 `dose_slots`、累计用量来自 `dose_records`，
     * 而 `StatsViewModel` 的 `combine` 只挂了「药品概览」——
     * 那张表在打卡 / 跳过 / 结算时**一行都不变**，于是 Flow 不发射、数字不刷新。
     *
     * 与其把按周期变化的聚合查询接进 `combine`（区间会随周期变，Flow 要重建），
     * 不如挂一句与周期无关的 `COUNT(*)`：它只负责"有事发生了，叫醒 combine"，
     * 真正的读取仍由调用方按当前周期自己做。
     *
     * ## 为什么带 `is_archived = 0`
     *
     * 归档这个动作**不改任何槽位状态**，所以只数状态的探针看不到它 ——
     * 用户归档一个药，统计页的依从率立刻就该变（该药不再计入分母），
     * 而探针不会发射。JOIN 上 `medications` 让"归档"也成为一个可被观察到的事件。
     *
     * 只数**已产生结论**的：PENDING/SNOOZED 会随 14 天窗口每天新增，
     * 那样每次对账都触发一次无谓的重新聚合。
     */
    @Query(
        """
        SELECT COUNT(*)
        FROM dose_slots s
        INNER JOIN medications m ON m.id = s.medication_id
        WHERE s.status IN ('COMPLETED', 'SKIPPED', 'EXPIRED')
          AND m.is_archived = 0
        """
    )
    fun observeDecidedSlotCount(): Flow<Int>

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
     * ## `status` 守卫为什么必须有（与 [snoozeSlot] 同一条纪律）
     *
     * 旧实现只有 `WHERE id = :slotId`。于是"结算"与"打卡"并发交错时，
     * 结算会把一个**已 COMPLETED、事实已入库、库存已扣减**的槽位覆写成 EXPIRED：
     *
     * | 交错 | 结局 |
     * | :--- | :--- |
     * | `getStaleOpenSlots` 读出 PENDING → 用户打卡 → `markExpired` | 槽位变 EXPIRED，而台账里那条 COMPLETED 事实与扣减还在 |
     *
     * 后果是二阶的：`EXPIRED` 在今日页被渲染成待办（徽标「已逾期…尚未确认」），
     * 用户点一次确认就产生**第二条**服药事实并**二次扣库存**；
     * 而 `takeDose` 的撤销路径此时也已失效——用户想纠错都纠不回来。
     *
     * 守卫下沉到 SQL 后，结算与打卡成为同一条原子判据上的互斥操作，
     * 谁先落地谁生效，后到者拿到 0 行并放弃。调用方不必、也不能绕过。
     *
     * @return 受影响行数；0 表示该槽位已不再开放（已被打卡/跳过/结算）
     */
    @Query(
        """
        UPDATE dose_slots
        SET status = 'EXPIRED', actual_taken_ts = NULL, snooze_until_ts = NULL
        WHERE id = :slotId AND status IN ('PENDING', 'SNOOZED')
        """
    )
    suspend fun markExpired(slotId: Long): Int

    @Query("DELETE FROM dose_slots")
    suspend fun deleteAllSlots()
}
