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

    /**
     * ⚠️ 单条插入用 REPLACE 且**仅限测试种子数据**（L-1）：生产代码零调用，
     * 生产路径一律走 [insertAll]（IGNORE）。REPLACE 换 id 的危害见下一条 KDoc ——
     * 槽位 id 漂移 = 闹钟身份漂移。仅供测试的事实在此显式声明。
     */
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

    /**
     * ⚠️ 已删除（osbf P3-1 / DB C-03 / zcg #15）：`@Update update(slot)`。
     *
     * 整行覆盖命令，零调用方 —— 槽位状态的每一次变更都有各自的
     * **带状态守卫的局部命令**（`markCompletedIfOpen` / `markSkipped` /
     * `snoozeSlot` / `markExpired` / `updateDerivedColumns`），
     * 绕过守卫的整行覆盖就是给"重复扣库存 / 既成事实被改写"开门。
     * 另见 [forceStatusForTest] 的 KDoc：那是唯一一个**有意**无守卫的入口，
     * 意图写在签名里、只供测试 fixture 使用。
     */

    /**
     * 同步**可从投影完全派生**的三列：剂量、所属策略、计划时间戳。
     *
     * ## 为什么必须存在
     *
     * 幂等 diff 的键是**日历** `(scheduled_date, scheduled_time)`，
     * 命中的槽位走"留"分支。旧实现里这份同步不存在 ——
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

    /**
     * 观察指定日期的槽位：包括计划在该日的槽位，以及从更早日期推迟到该日的开放槽位（SNOOZED）。
     */
    @Query(
        """
        SELECT * FROM dose_slots
        WHERE scheduled_date = :dateStr
           OR (status = 'SNOOZED' AND snooze_until_ts >= :dayStartTs AND snooze_until_ts < :dayEndTs)
        ORDER BY scheduled_ts ASC
        """
    )
    fun observeSlotsForDateWithSnoozed(dateStr: String, dayStartTs: Long, dayEndTs: Long): Flow<List<DoseSlotEntity>>

    @Query("SELECT * FROM dose_slots WHERE scheduled_date = :dateStr ORDER BY scheduled_ts ASC")
    suspend fun getSlotsForDate(dateStr: String): List<DoseSlotEntity>

    // observeSlotsInRange / getSlotsInRange 仍保留：后者是测试 fixture 的读取入口
    //（PolicyEndDateTest 等）。已删除的是零调用方（orsbf P3-1）：
    // observeSlotsInRange、getPendingSlotsAfter、countCompletedSlotsForDate、
    // countTotalSlotsForDate —— 需要时从 git 历史找回。
    @Query("SELECT * FROM dose_slots WHERE scheduled_date BETWEEN :startDate AND :endDate ORDER BY scheduled_date ASC, scheduled_ts ASC")
    suspend fun getSlotsInRange(startDate: String, endDate: String): List<DoseSlotEntity>

    /**
     * 单药品的窗口查询（osbf P3-7 / sba P1-6）。
     *
     * `reconcileSchedule` 是**逐药**调用的：旧实现每次都拉全窗口槽位再在内存里
     * `filter { it.medicationId == ... }`，一轮全量对账就是 O(药品数 × 窗口槽位数)
     * 次行的传输。SQL 里带上 `medication_id` 后走现有 `Index(medication_id)`，
     * 每药只取自己名下的行。
     */
    @Query("SELECT * FROM dose_slots WHERE medication_id = :medicationId AND scheduled_date BETWEEN :startDate AND :endDate ORDER BY scheduled_date ASC, scheduled_ts ASC")
    suspend fun getSlotsInRangeForMedication(medicationId: Long, startDate: String, endDate: String): List<DoseSlotEntity>

    @Query("SELECT * FROM dose_slots WHERE medication_id = :medicationId AND status = 'PENDING' AND scheduled_ts >= :fromTs ORDER BY scheduled_ts ASC")
    suspend fun getPendingSlotsForMedicationAfter(medicationId: Long, fromTs: Long): List<DoseSlotEntity>

    /**
     * ⚠️ **无状态守卫的写入口，只供测试构造 fixture 使用**（M8-1）。
     *
     * 旧的 `updateStatus(slotId, status, actualTs)` 是本文件**唯一**不带
     * `status IN (...)` 守卫的 UPDATE。它的返回值是 `Unit`，
     * 调用方**无法知道**自己是不是把一个已 COMPLETED 的槽位覆写成了别的状态。
     *
     * 零生产调用方，却是一个**复活型 footgun**：将来任何人在"临时改一下状态"的
     * 冲动下找到它，就会绕过全部 5 条幂等锚点 ——
     * 而绕过它们的后果是数据损坏（重复扣库存、既成事实被改写），不是报错。
     *
     * 改名 `forceStatusForTest` 而不是直接删除：它有 6 处**测试**调用方
     * （构造 EXPIRED / SNOOZED 的前置状态），删掉要连带重写 6 处 fixture。
     * 改名的收益是**意图写进了签名**：`force` + `ForTest` 两个词都在说
     * "这不是业务路径"，而原来的 `updateStatus` 看起来像个正经 API。
     *
     * 将来若测试不再需要，删掉即可 —— 那时它已经不会误导任何人了。
     */
    @Query("UPDATE dose_slots SET status = :status, actual_taken_ts = :actualTs WHERE id = :slotId")
    suspend fun forceStatusForTest(slotId: Long, status: SlotStatus, actualTs: Long? = null)

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
     *
     * ## 为什么还要 `scheduled_date <= :todayStr`（未来槽位不可打卡）
     *
     * 只有 `status` 守卫时，**未来的待服槽位与今天的待服槽位长得一模一样**：
     * 今日页翻到明天，卡片上的 ✓ 可点，点下去就凭空生成一条"明天已服"的事实、
     * 扣一次库存，并撤掉当天的闹钟。判据必须落在**数据**上，而不是"调用方都记得先查日期"。
     *
     * 与 `status` 守卫并列、由 `SlotActionPolicy.isActionableOn` 同一份语义驱动：
     * 字符串比较（零填充 `yyyy-MM-dd` 的字典序 == 时序），非规范串 fail-closed。
     * 传 `todayStr` 而不是在 SQL 里取日期：SQLite 的 `date('now')` 是 **UTC**，
     * 在东八区会把当天 08:00 之前的"今天"判成昨天。
     * 撤销路径（[revertToPending]）**刻意不带这个守卫**：它是修复通道，
     * 必须能清掉"未来已服"这类坏数据，否则用户被永久锁死。
     */
    @Query(
        """
        UPDATE dose_slots
        SET status = 'COMPLETED', actual_taken_ts = :actualTs
        WHERE id = :slotId
          AND status IN ('PENDING', 'SNOOZED', 'EXPIRED')
          AND scheduled_date <= :todayStr
        """
    )
    suspend fun markCompletedIfOpen(slotId: Long, actualTs: Long, todayStr: String): Int

    /** 幂等跳过：允许对已逾期(EXPIRED)的槽位补记跳过，但不允许覆盖已完成/已跳过；同样拒未来。 */
    @Query(
        """
        UPDATE dose_slots
        SET status = 'SKIPPED', actual_taken_ts = :actualTs
        WHERE id = :slotId
          AND status IN ('PENDING', 'SNOOZED', 'EXPIRED')
          AND scheduled_date <= :todayStr
        """
    )
    suspend fun markSkippedIfOpen(slotId: Long, actualTs: Long, todayStr: String): Int

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
     *
     * ## 未来的槽位为什么不能推迟
     *
     * `snooze_until_ts = now + N 分钟`。对一条**明天**的槽位"推迟 30 分钟"，
     * 算出来的是**今天**的唤醒时刻 —— 等于凭空造出一个今天就响的"未来服药提醒"，
     * 与「槽位存在 ⟺ 这个时点会响」的排班语义直接冲突。
     */
    @Query(
        """
        UPDATE dose_slots
        SET status = 'SNOOZED', snooze_until_ts = :snoozeUntilTs, last_snooze_notified_ts = NULL
        WHERE id = :slotId
          AND status IN ('PENDING', 'SNOOZED')
          AND scheduled_date <= :todayStr
        """
    )
    suspend fun snoozeSlot(slotId: Long, snoozeUntilTs: Long, todayStr: String): Int

    /**
     * 撤销打卡：把槽位置回 PENDING 并清空实际服药时刻。
     * 仅对已 COMPLETED / SKIPPED 的槽位生效（受影响行数为 0 表示本就无需撤销）。
     */
    @Query(
        """
        UPDATE dose_slots
        SET status = 'PENDING', actual_taken_ts = NULL, snooze_until_ts = NULL, last_snooze_notified_ts = NULL
        WHERE id = :slotId AND status IN ('COMPLETED', 'SKIPPED')
        """
    )
    suspend fun revertToPending(slotId: Long): Int

    /** 记录主提醒已成功弹出，消除后续托盘被划掉后的重复补响 (P1-1) */
    @Query("UPDATE dose_slots SET last_main_notified_ts = :notifiedTs WHERE id = :slotId")
    suspend fun updateLastMainNotifiedTs(slotId: Long, notifiedTs: Long): Int

    /** 记录推迟提醒已成功弹出 (P1-1) */
    @Query("UPDATE dose_slots SET last_snooze_notified_ts = :notifiedTs WHERE id = :slotId")
    suspend fun updateLastSnoozeNotifiedTs(slotId: Long, notifiedTs: Long): Int

    /** 更新提醒次数及最后主提醒时间戳 (用于忽略/划掉后的重复提醒) */
    @Query("UPDATE dose_slots SET reminder_count = :count, last_main_notified_ts = :notifiedTs WHERE id = :slotId")
    suspend fun updateReminderCountAndLastNotified(slotId: Long, count: Int, notifiedTs: Long): Int

    /** 撤销或重新排班时重置提醒计数 */
    @Query("UPDATE dose_slots SET reminder_count = 0 WHERE id = :slotId")
    suspend fun resetReminderCount(slotId: Long): Int

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

    // ⚠️ 已删除（orsbf P3-1，零调用方）：countCompletedSlotsForDate / countTotalSlotsForDate。
    // getAllSlots 保留（备份 / 测试使用）。

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
     * 与结算窗口两个参数），也就多一条会静默失效的依赖链路。
     * 结算线是自然日，15 分钟的对账粒度更无影响。
     *
     * ## cutoff 传什么（PLAN-EXPIRE-WINDOW-20260929）
     *
     * cutoff 传的是**「当地当日 0 点」**：结算窗口是「当日结束」，
     * 严格小于 ⇒ 今天全天豁免 —— 上午没吃的药下午吃完全正常，
     * 不该 11:00 就判漏服。它与补响窗口（`AlarmReconciler.CATCHUP_WINDOW_MS`）
     * 是两条独立的线 —— 曾共用一个 2 小时常量，拆开的原因见该方案 §2。
     * 推迟不再额外加一轮宽限 —— 用户主动给的期限就是期限。
     */
    @Query(
        """
        SELECT * FROM dose_slots
        WHERE status = 'PENDING' AND scheduled_ts < :cutoffTs
        """
    )
    suspend fun getStalePendingSlots(cutoffTs: Long): List<DoseSlotEntity>

    @Query(
        """
        SELECT * FROM dose_slots
        WHERE status = 'SNOOZED' AND snooze_until_ts IS NOT NULL AND snooze_until_ts < :cutoffTs
        """
    )
    suspend fun getStaleSnoozedSlots(cutoffTs: Long): List<DoseSlotEntity>

    /**
     * 结算候选：过期的待服槽位（PENDING 按 `scheduled_ts`）+ 过期的推迟槽位
     * （SNOOZED 按 `snooze_until_ts`）。两分支按 status 天然不相交，直接拼接。
     *
     * ## 为什么是两条单分支查询（ocsbf P1-9 / §一-13）
     *
     * 旧实现一条 `OR` 双分支查询：OR 让 SQLite 无法对任一分支走精确的
     * 索引计划 —— PENDING 分支吃不到 `scheduled_ts` 索引的范围扫描，
     * SNOOZED 分支的 `snooze_until_ts` 本就不在任何索引里。
     * 本查询在对账/闹钟触发的每轮都会跑，表量随使用年限增长（受 14 天窗口约束）。
     * 拆开后 PENDING 分支可用 `scheduled_ts` 索引做范围扫描；
     * SNOOZED 分支如仍需要索引，须升 `AppDatabase.version` + 补 PRAGMA 断言（未做）。
     */
    suspend fun getStaleOpenSlotsBothKinds(cutoffTs: Long): List<DoseSlotEntity> =
        getStalePendingSlots(cutoffTs) + getStaleSnoozedSlots(cutoffTs)

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
