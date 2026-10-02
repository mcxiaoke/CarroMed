package com.mcxiaoke.carromed.core.domain.service

import androidx.room.withTransaction
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.DoseRecordEntity
import com.mcxiaoke.carromed.core.data.entity.DoseSlotEntity
import com.mcxiaoke.carromed.core.data.entity.InventoryTransactionEntity
import com.mcxiaoke.carromed.core.data.model.RecordStatus
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import com.mcxiaoke.carromed.core.data.model.TransactionType
import com.mcxiaoke.carromed.core.domain.AppLog
import com.mcxiaoke.carromed.core.domain.engine.SlotActionPolicy
import com.mcxiaoke.carromed.core.domain.engine.SlotProjectionEngine
import com.mcxiaoke.carromed.core.domain.model.Dose
import com.mcxiaoke.carromed.core.domain.model.LedgerNoteKey
import com.mcxiaoke.carromed.core.domain.model.RecordNoteKey
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * 手动补录的时间窗：只能补录**最近 7 个自然日**内的服药（含撤销窗口）。
 *
 * 领域层定义（服务层是所有入口的公共下游，UI 的 DatePicker 下界与
 * 记录详情页的撤销窗口都引用它），理由与"未来槽位不可表态"同源：
 * 服药事实虽不可再生，但一条 1970 年的"已服"记录会把依从率与库存台账
 * 永久污染 —— 而那么老的记录早已没有"忘打卡了补一下"的语义。
 * 7 天覆盖"整周外出、回来一次性补"的现实场景，也与管理系统的常规追溯窗一致。
 */
const val MANUAL_DOSE_BACKFILL_DAYS: Long = 7

/**
 * 核心用药追踪与调度核算服务 (DoseTrackingService)
 *
 * ## 库存记账的绝对不变式
 *
 * > `balance(medicationId) := SUM(inventory_transactions.change_amount)`
 *
 * `medications` 表**不再存储 `current_stock`**（见 `MedicationEntity`）。
 * 这意味着本服务里**不再有任何"改账面"的操作** —— 5/6 的库存路径退化为
 * **单条 INSERT**（append-only）：
 *
 * | 操作 | 流水 | 是否需要读余额 |
 * | --- | --- | :---: |
 * | [takeDose] | `TAKEN_DEDUCT` | ❌ |
 * | [undoDose] | `REVERT_ROLLBACK` | ❌ |
 * | [logManualDose] | `TAKEN_DEDUCT` | ❌ |
 * | [refillStock] | `REFILL` | ❌ |
 * | [calibrateStock] | `CALIBRATION_ADJUST` | ✅（本质要求：算差额） |
 * | [setStockTracking] | `CALIBRATION_ADJUST` | ✅（需要判断是否建档） |
 *
 * 附带收益：并发打卡从 read-modify-write 变成 append，**不再可能丢失更新**。
 *
 * ## 负库存是被支持的（D-9）
 *
 * `FINAL-PRODUCT` D-9：「允许扣为负数，绝不阻止打卡；负库存单独视觉化提示盘点。
 * 服药是物理事实，优先于库存记账。」
 * 因此本服务**不再有 `coerceAtLeast(0f)`** —— 那种钳制正是 P0-3 的直接成因
 * （账面钉在 0、流水记全额，守恒被打破）。
 *
 * ## 未来槽位不可表态（本服务是唯一不可省的一层）
 *
 * > 槽位可表态 ⇔ `scheduled_date <= 当前自然日`
 *
 * 服药是**已发生**的事实：一条明天的槽位被点 ✓ 会凭空生成"明天已服"的事实、
 * 扣一次库存，并撤掉明天的闹钟。判据由 [SlotActionPolicy] 一处定义，
 * 经 `dose_slots` 三个写入口的 SQL 守卫执行（[takeDose] / [skipDose] / [snoozeDose]），
 * 服务层与 UI 层都用同一个函数做归因与渲染判断。
 *
 * **撤销与结算刻意不受此限**：撤销是修复坏数据的唯一通道，拦掉会让用户被永久锁死。
 */
class DoseTrackingService(
    private val db: AppDatabase,
    /**
     * "今天"的来源，**可注入**。
     *
     * 为什么不是直接在方法里写 `LocalDate.now()`：未来判据要进 SQL 的 `WHERE`，
     * 一旦读挂钟，**每条测试的成败就随日历漂移** ——
     * fixture 用写死日期的测试会在某几天突然变红、过几天又自己变绿
     * （AGENTS.md §3「测试在下午全绿、早上全红」那一类）。
     * 测试注入固定日期即可完全确定；生产用它拿真实的今天。
     */
    private val todayProvider: () -> LocalDate = { LocalDate.now() }
) {

    /** 当前自然日，规范格式 `yyyy-MM-dd`（与 [SlotProjectionEngine.DATE_FORMATTER] 同源）。 */
    private fun todayStr(): String = todayProvider().format(SlotProjectionEngine.DATE_FORMATTER)

    /**
     * 写入口被拒的**归因**，只用于日志。
     *
     * 刻意不作为写决策：真正拦下这次写入的是 SQL 的 `WHERE`
     * （判据落在数据上，任何调用方都绕不过）。这里只是把"是哪一条拦下的"说清楚 ——
     * 否则 `false` 会把"未来槽位"和"已处理过"混成一句含糊的日志。
     */
    private fun rejectReason(slot: DoseSlotEntity, todayStr: String): String =
        if (!SlotActionPolicy.isActionableOn(slot.scheduledDate, todayStr)) "future-slot" else "not-open"


    private companion object {
        const val TAG = "DoseTrackingService"
    }

    private val medDao = db.medicationDao()
    private val policyDao = db.schedulePolicyDao()
    private val slotDao = db.doseSlotDao()
    private val recordDao = db.doseRecordDao()
    private val inventoryDao = db.inventoryTransactionDao()

    /**
     * 读出该药品当前的账面余额（权威值）。
     *
     * 只有 [calibrateStock] 与 [setStockTracking] 需要它 —— 其余路径直接追加流水即可。
     */
    private suspend fun balanceOf(medicationId: Long): Int =
        inventoryDao.getSumOfChanges(medicationId) ?: 0

    /**
     * 追加一条台账流水。**唯一的余额变更入口**。
     *
     * [balanceAfter] 是展示用快照（`minSdk 26` 的 SQLite 3.18 不支持窗口函数，
     * 无法用 `SUM(...) OVER` 生成累计和）。它不是权威值，权威值恒为 `SUM(change_amount)`；
     * 不变量 I2 会持续校验"最后一条流水的 balanceAfter == SUM(change_amount)"，
     * 一旦分叉立即失败。
     */
    private suspend fun appendLedger(
        medicationId: Long,
        recordId: Long?,
        changeAmount: Dose,
        txType: TransactionType,
        note: String?,
        noteKey: String? = null,
        batchNumber: String? = null,
        expiryDate: String? = null
    ) {
        val balanceAfter = balanceOf(medicationId) + changeAmount.milli
        inventoryDao.insert(
            InventoryTransactionEntity(
                medicationId = medicationId,
                recordId = recordId,
                changeAmount = changeAmount.milli,
                balanceAfter = balanceAfter,
                txType = txType,
                note = note,
                noteKey = noteKey,
                batchNumber = batchNumber,
                expiryDate = expiryDate
            )
        )
        // 台账审计线（PLAN-LOGGING G1）：余额出问题时，这条 INFO 与
        // `inventory_transactions` 表逐行可对——量纲是毫单位，与库同刻度。
        // note 是自由文本（可能含病情描述），按隐私决策（方案 D6）不落日志。
        AppLog.i(
            TAG,
            "ledger med=$medicationId record=$recordId tx=$txType change=${changeAmount.milli} balanceAfter=$balanceAfter"
        )
    }

    // ==================== 1. 确认服药打卡 ====================

    /**
     * 原子事务：更新槽位状态 → 创建服药事实 → （若追踪库存）追加扣减流水。
     *
     * 库存部分**只有一条 INSERT**，不读也不写 `medications`。
     * 幂等锚点：槽位若已被处理（COMPLETED / SKIPPED）则直接返回 false，
     * 连点多次不会重复扣库存（见 `DoseSlotDao.markCompletedIfOpen`）。
     */
    suspend fun takeDose(
        slotId: Long,
        actualTs: Long = System.currentTimeMillis(),
        takenAmount: Float? = null,
        note: String? = null,
        noteKey: String? = null,
        isRetrospective: Boolean = false
    ): Boolean = db.withTransaction {
        // 显式传入的剂量必须在合法量程内（M2-2 / §二-18）。
        // 负剂量打卡 = 扣减变成**加**库存，是这条路径上最恶劣的失败模式；
        // 上界（`Dose.isWithinRange`）挡住量化溢出 —— `Dose.of` 现在会把溢出钳到
        // 上界，只判 `> 0` 会把"极大值"误判成合法。
        // `slot.doseAmount` 那一路不需要校验：它由 `saveReminderPolicy` 的同一条判据
        // 在写入时保证了（那是所有时点剂量的唯一来源）。
        require(takenAmount == null || Dose.isWithinRange(takenAmount)) {
            "服药剂量必须大于 0 且不超过 ${Dose.MAX_MILLI / 1000}，当前 $takenAmount"
        }

        // 时间窗守卫（§二-19）：与 [logManualDose] **同源**，服务层是所有入口的公共下游
        // （UI 打卡 / 通知栏 Action / 未来的 Widget、手表、快捷指令）。
        // 上界：服药是**已发生**的事实，不许记到未来（60 秒容差对齐 UI）。
        // 下界：与补录窗口同源（[MANUAL_DOSE_BACKFILL_DAYS]）—— 否则"先打卡再改时间"
        // 能绕过补录的时间窗（`editDose` 已按同一常量设防，两边必须一致）。
        val nowTs = System.currentTimeMillis()
        require(actualTs <= nowTs + 60_000L) { "服药时间不能晚于当前时刻" }
        require(
            !Instant.ofEpochMilli(actualTs).atZone(ZoneId.systemDefault())
                .toLocalDate().isBefore(todayProvider().minusDays(MANUAL_DOSE_BACKFILL_DAYS))
        ) { "服药时间不能早于 $MANUAL_DOSE_BACKFILL_DAYS 天前" }

        val slot = slotDao.getSlotById(slotId) ?: run {
            AppLog.w(TAG, "takeDose rejected slot=$slotId reason=slot-missing")
            return@withTransaction false
        }
        val medication = medDao.getMedicationById(slot.medicationId) ?: run {
            AppLog.w(TAG, "takeDose rejected slot=$slotId med=${slot.medicationId} reason=med-missing")
            return@withTransaction false
        }

        // 剂量已在入口按 `Dose.isWithinRange` 校验（量化后归零 / 溢出都被挡下），
        // 这里只取值；`slot.doseAmount` 那一路由 `saveReminderPolicy` 的同一条判据
        // 在写入时保证，不重复拒绝 —— 免得把恢复自旧备份的存量数据变成"无法打卡"。
        val finalDose: Dose = takenAmount?.let { Dose.of(it) } ?: Dose(slot.doseAmount)

        // 幂等锚点下沉到 SQL：只有仍在等待、且**不是未来**的槽位才被置为 COMPLETED。
        // 受影响行数为 0 ⇒ 已被处理过或尚未到计划日，直接放弃记账
        // （连点不会重复扣库存；未来槽位不会凭空生成"已服"事实）。
        val today = todayStr()
        if (slotDao.markCompletedIfOpen(slotId, actualTs, today) == 0) {
            AppLog.w(
                TAG,
                "takeDose rejected slot=$slotId reason=${rejectReason(slot, today)} status=${slot.status}"
            )
            return@withTransaction false
        }

        val effectiveRetro = isRetrospective || (slot.scheduledDate < today)
        val record = DoseRecordEntity(
            slotId = slotId,
            medicationId = slot.medicationId,
            actualTs = actualTs,
            doseTaken = finalDose.milli,
            status = RecordStatus.COMPLETED,
            isRetrospective = effectiveRetro,
            note = note,
            noteKey = noteKey
        )
        val recordId = recordDao.insert(record)

        // 库存：单条流水，允许扣成负数（D-9）
        if (medication.isStockTracked) {
            appendLedger(
                medicationId = slot.medicationId,
                recordId = recordId,
                changeAmount = -finalDose,
                txType = TransactionType.TAKEN_DEDUCT,
                note = note,
                noteKey = if (isRetrospective) LedgerNoteKey.RETRO_DEDUCT.name else LedgerNoteKey.TAKE_DEDUCT.name
            )
        }

        AppLog.i(
            TAG,
            "takeDose ok slot=$slotId med=${slot.medicationId} record=$recordId doseMilli=${finalDose.milli}"
        )
        return@withTransaction true
    }

    // ==================== 2. 跳过服药 ====================

    /** 原子事务：更新槽位状态为 SKIPPED → 插入 SKIPPED 事实记录。不影响库存。 */
    suspend fun skipDose(
        slotId: Long,
        reason: String? = null,
        noteKey: String? = null
    ): Boolean = db.withTransaction {
        val slot = slotDao.getSlotById(slotId) ?: run {
            AppLog.w(TAG, "skipDose rejected slot=$slotId reason=slot-missing")
            return@withTransaction false
        }

        val now = System.currentTimeMillis()
        // 幂等锚点下沉到 SQL（允许对已逾期的槽位补记跳过，但拒绝未来槽位）
        val today = todayStr()
        if (slotDao.markSkippedIfOpen(slotId, now, today) == 0) {
            AppLog.w(
                TAG,
                "skipDose rejected slot=$slotId reason=${rejectReason(slot, today)} status=${slot.status}"
            )
            return@withTransaction false
        }

        val record = DoseRecordEntity(
            slotId = slotId,
            medicationId = slot.medicationId,
            actualTs = now,
            doseTaken = 0,
            status = RecordStatus.SKIPPED,
            note = reason,
            // reason 为空时给一个程序化分类，界面据此显示本地化标签（B5）。
            // 用户自己填了原因就不挂 key —— 否则会显示成"主动跳过本次服药（出门在外）"，
            // 把用户的话降格成程序化标签的附注。
            noteKey = noteKey ?: if (reason == null) RecordNoteKey.SKIP.name else null
        )
        val recordId = recordDao.insert(record)
        AppLog.i(TAG, "skipDose ok slot=$slotId med=${slot.medicationId} record=$recordId")
        return@withTransaction true
    }

    // ==================== 3. 推迟提醒 (Snooze) ====================

    suspend fun snoozeDose(slotId: Long, snoozeMinutes: Int): Boolean {
        // 上界钳制：推迟时长同时决定 `snooze_until_ts` 的偏移量，
        // `Int.MAX_VALUE` 分钟会溢出成一个**已经过去**的时间戳 ⇒ 推迟后立刻被判逾期。
        // 下界 1 分钟则会让"立刻重响"成为可能。两端都收到与通知栏按钮一致的取值域。
        val safeMinutes = snoozeMinutes.coerceIn(1, 240)
        val snoozeUntilTs = System.currentTimeMillis() + (safeMinutes * 60 * 1000L)
        val today = todayStr()
        val ok = slotDao.snoozeSlot(slotId, snoozeUntilTs, today) > 0
        if (ok) {
            AppLog.i(TAG, "snoozeDose ok slot=$slotId minutes=$safeMinutes until=$snoozeUntilTs")
        } else {
            // 失败归因要读一次槽位（只在失败路径上，代价可忽略）：
            // "未来的槽位不能推迟"与"槽位已结算/不存在"是两件完全不同的事，
            // 混成一句日志会让排查"为什么点了没反应"时无从下手。
            val slot = slotDao.getSlotById(slotId)
            val reason = when {
                slot == null -> "slot-missing"
                !SlotActionPolicy.isActionableOn(slot.scheduledDate, today) -> "future-slot"
                else -> "settled status=${slot.status}"
            }
            AppLog.w(TAG, "snoozeDose rejected slot=$slotId reason=$reason")
        }
        return ok
    }

    // ==================== 4. 误触/点错撤销 (Undo) ====================

    /**
     * 撤销打卡：槽位回到 PENDING、事实标记为 REVERTED、追加库存冲正流水。
     *
     * ⚠️ **不再物理删除服药事实**（`FINAL-PRODUCT` 场景 2 要求"事实层追加 REVERT 修正…全程留痕"，
     * 产品第二承诺是"吃过的药永不丢失"）。此前的 `DELETE FROM dose_records` 会让
     * 台账里指向该记录的 `record_id` 变成悬空引用。
     */
    suspend fun undoDose(slotId: Long): Boolean = db.withTransaction {
        revertSlotInternal(slotId)
    }

    /**
     * 作废槽位当前的结论：槽位回 `PENDING`、事实标 `REVERTED`、台账按净额逐条冲正。
     *
     * ## 库存层：先算出"这一轮实际还欠多少扣"，再回退槽位
     *
     * ⚠️ 判据是**该事实的台账净额**，不是"读一条代表事实的剂量"，
     * 也不是"药品当前是否追踪库存"。两个旧写法都会错：
     *
     * | 写法 | 错在哪 |
     * | :--- | :--- |
     * | `getRecordBySlotId` 取 `id ASC LIMIT 1` 读它的 `doseTaken` | 第二次撤销拿到的是最早那条（已 `REVERTED`）⇒ 判"没扣过"⇒ **账面凭空少一次扣减** |
     * | `medication.isStockTracked`（当前值） | 打卡后用户改过追踪开关 ⇒ 虚增或永远不回补 |
     *
     * 净额口径同时解决两者，而且天然幂等：冲正后该事实净额变 0，
     * 重复撤销时 `net >= 0`，不会再补第二条。
     *
     * ⚠️ 本方法**不带事务包装**：由调用方开事务（[undoDose] 与 [restateSlot] 各自开），
     * 这样"改判"才能把"作废旧结论 + 施加新结论"合进**同一个**事务里，
     * 不会出现"旧的作废了、新的没写上"的中间态。
     */
    private suspend fun revertSlotInternal(slotId: Long): Boolean {
        val slot = slotDao.getSlotById(slotId) ?: run {
            AppLog.w(TAG, "revert rejected slot=$slotId reason=slot-missing")
            return false
        }
        // 幂等锚点：只有"已产生结论"的槽位才可撤销，与 takeDose/skipDose 的返回语义保持一致
        if (slot.status != SlotStatus.COMPLETED && slot.status != SlotStatus.SKIPPED) {
            AppLog.w(TAG, "revert rejected slot=$slotId reason=no-conclusion status=${slot.status}")
            return false
        }
        // 槽位必须真有事实（跳过也会留一条 SKIPPED 事实）
        if (recordDao.getAllRecordsBySlotId(slotId).isEmpty()) {
            AppLog.w(TAG, "revert rejected slot=$slotId reason=no-records")
            return false
        }

        val rollbacks = recordDao.getCompletedRecordsBySlot(slotId).mapNotNull { rec ->
            val net = inventoryDao.getSumOfChangeByRecordId(rec.id) ?: 0
            if (net < 0) rec.id to Dose(-net) else null
        }

        // 条件回退（原子；受影响行数为 0 表示并发下已被别人撤销）
        if (slotDao.revertToPending(slotId) == 0) {
            AppLog.w(TAG, "revert rejected slot=$slotId reason=lost-race (concurrently reverted)")
            return false
        }

        // 事实层：保留记录，仅改状态（append-only 的补偿，而不是抹除）
        recordDao.markRevertedBySlot(slotId)

        // 台账只增不改：逐条补等额冲正
        rollbacks.forEach { (recordId, amount) ->
            appendLedger(
                medicationId = slot.medicationId,
                recordId = recordId,
                changeAmount = amount,
                txType = TransactionType.REVERT_ROLLBACK,
                note = null,
                noteKey = LedgerNoteKey.UNDO_TAKE_REVERT.name
            )
        }
        AppLog.i(
            TAG,
            "revert ok slot=$slotId med=${slot.medicationId} rollbacks=${rollbacks.size}"
        )
        return true
    }

    // ==================== 4a-2. 改判：已服 ↔ 已跳过 ====================

    /**
     * 把一条**已产生结论**的槽位改判成另一种结论。
     *
     * ## 为什么必须新增这条通路
     *
     * [takeDose] 与 [skipDose] 的幂等锚点都写在 SQL 的 WHERE 里
     * （`DoseSlotDao.markCompletedIfOpen` / `markSkippedIfOpen`，
     * 守卫是 `status IN ('PENDING','SNOOZED','EXPIRED')`）——
     * 这是**故意**的：它们要防止连点重复扣库存。
     *
     * 但也因此，「已服 → 跳过」「已跳过 → 确认」在旧代码里**没有任何路径**，
     * 两个按钮点下去只会返回 false。改判必须走"先作废、再施加"，
     * 且两步在同一个事务里。
     *
     * ## 时间不变，只改结论
     *
     * 新事实沿用槽位原来的 `actual_taken_ts`（而不是 `now`）：改判的语义是
     * "我其实没吃 / 我其实吃了"，不是"我现在服了"。若改用 `now`，
     * 一条昨天漏判的记录改判后会凭空跳到今天，既污染当日统计，
     * 也会让"撤销仅当天"的判据变得诡异。
     *
     * ## 台账守恒
     *
     * 作废走 [revertSlotInternal]（按净额回补），施加走与 takeDose/skipDose
     * **同一套**写库与记账代码，所以 `SUM(change_amount)` 与事实净额始终一致。
     *
     * @return true 表示确实改判了；false 表示槽位不存在、已是目标结论、
     *         或槽位还没有结论（开放槽位请走 [takeDose] / [skipDose]）。
     */
    suspend fun restateSlot(
        slotId: Long,
        target: RecordStatus,
        note: String? = null
    ): Boolean = db.withTransaction {
        val slot = slotDao.getSlotById(slotId) ?: run {
            AppLog.w(TAG, "restate rejected slot=$slotId reason=slot-missing")
            return@withTransaction false
        }
        val medication = medDao.getMedicationById(slot.medicationId) ?: run {
            AppLog.w(TAG, "restate rejected slot=$slotId med=${slot.medicationId} reason=med-missing")
            return@withTransaction false
        }

        // ⚠️ 未来判据必须在 `revertSlotInternal` **之前**：改判是"先作废、再施加"，
        // 而施加用的 `markCompletedIfOpen` / `markSkippedIfOpen` 已带未来守卫。
        // 若把判断留给那一步，一条未来的已产生结论槽位（正是本缺陷留下的坏数据）
        // 会走到"作废已完成、施加被拒"⇒ `return false` **提交**了作废
        // （`withTransaction` 只在抛异常时回滚）⇒ 槽位停在 PENDING、
        // 事实全标 REVERTED、台账已冲正，用户看到半截状态。
        // 修复这种坏数据的正确入口是撤销（[undoDose]），不是改判。
        val today = todayStr()
        if (!SlotActionPolicy.isActionableOn(slot.scheduledDate, today)) {
            AppLog.w(TAG, "restate rejected slot=$slotId reason=future-slot")
            return@withTransaction false
        }

        // 幂等：已经是目标结论 ⇒ 什么都不做
        if (target == RecordStatus.COMPLETED && slot.status == SlotStatus.COMPLETED) {
            AppLog.w(TAG, "restate rejected slot=$slotId reason=already-target target=COMPLETED")
            return@withTransaction false
        }
        if (target == RecordStatus.SKIPPED && slot.status == SlotStatus.SKIPPED) {
            AppLog.w(TAG, "restate rejected slot=$slotId reason=already-target target=SKIPPED")
            return@withTransaction false
        }
        // 只有已结算(EXPIRED)或已产生结论(COMPLETED/SKIPPED)的槽位才谈得上改判/结清
        if (slot.status != SlotStatus.COMPLETED && slot.status != SlotStatus.SKIPPED && slot.status != SlotStatus.EXPIRED) {
            AppLog.w(TAG, "restate rejected slot=$slotId reason=no-conclusion status=${slot.status}")
            return@withTransaction false
        }

        // 结论未定之前先记下来：回退会清空 actual_taken_ts
        val restatedTs = slot.actualTakenTs ?: System.currentTimeMillis()

        // 仅对已产生过结论的槽位执行冲正；EXPIRED 未产生过事实，直接施加新结论
        if (slot.status == SlotStatus.COMPLETED || slot.status == SlotStatus.SKIPPED) {
            if (!revertSlotInternal(slotId)) return@withTransaction false
        }

        when (target) {
            RecordStatus.COMPLETED -> {
                if (slotDao.markCompletedIfOpen(slotId, restatedTs, today) == 0) {
                    AppLog.w(TAG, "restate rejected slot=$slotId reason=not-open-after-revert")
                    return@withTransaction false
                }
                val isRetro = slot.scheduledDate < today
                val record = DoseRecordEntity(
                    slotId = slotId,
                    medicationId = slot.medicationId,
                    actualTs = restatedTs,
                    doseTaken = slot.doseAmount,
                    status = RecordStatus.COMPLETED,
                    isRetrospective = isRetro,
                    note = note,
                    noteKey = if (note == null) RecordNoteKey.REJUDGE_TAKEN.name else null
                )
                val recordId = recordDao.insert(record)

                // 与 takeDose 同一记账口径：单条 TAKEN_DEDUCT，允许扣成负数（D-9）
                if (medication.isStockTracked) {
                    appendLedger(
                        medicationId = slot.medicationId,
                        recordId = recordId,
                        changeAmount = -Dose(slot.doseAmount),
                        txType = TransactionType.TAKEN_DEDUCT,
                        note = note,
                        noteKey = if (isRetro) LedgerNoteKey.RETRO_DEDUCT.name else LedgerNoteKey.REJUDGE_TAKEN.name
                    )
                }
            }

            RecordStatus.SKIPPED -> {
                if (slotDao.markSkippedIfOpen(slotId, restatedTs, today) == 0) {
                    AppLog.w(TAG, "restate rejected slot=$slotId reason=not-open-after-revert")
                    return@withTransaction false
                }
                recordDao.insert(
                    DoseRecordEntity(
                        slotId = slotId,
                        medicationId = slot.medicationId,
                        actualTs = restatedTs,
                        doseTaken = 0,
                        status = RecordStatus.SKIPPED,
                        note = note,
                        noteKey = if (note == null) RecordNoteKey.SKIP.name else null
                    )
                )
            }

            // REVERTED 不是"结论"，改判到它请走 undoDose
            else -> return@withTransaction false
        }
        AppLog.i(TAG, "restate ok slot=$slotId med=${slot.medicationId} target=$target ts=$restatedTs")
        return@withTransaction true
    }

    // ==================== 4b. 无排班记录的撤销 / 修改 ====================

    /**
     * 撤销一条**没有槽位**的服药记录（手动补录 / 按需临时用药，`slot_id == null`）。
     *
     * ## 为什么 [undoDose] 覆盖不到这里
     *
     * [undoDose] 靠 `slotId` 定位槽位（要把槽位退回 PENDING），
     * 而补录的记录**根本没有槽位**——于是这类记录此前**没有任何撤销路径**。
     * 用户补录错了时间就只能留着，或者去数据库里手改。
     *
     * ## 语义与 [undoDose] 一致
     *
     * - 事实**不删除**，只标 `REVERTED`（产品第二承诺 + 台账 `record_id` 不能悬空）
     * - 按该事实的**台账净额**补等额冲正，天然幂等（冲正后净额变 0）
     * - 幂等锚点：只接受 `COMPLETED` / `SKIPPED`，已 `REVERTED` 的返回 false
     */
    suspend fun undoManualDose(recordId: Long): Boolean = db.withTransaction {
        val record = recordDao.getRecordById(recordId) ?: run {
            AppLog.w(TAG, "undoManual rejected record=$recordId reason=record-missing")
            return@withTransaction false
        }
        // 幂等锚点：只有"已产生结论"的事实才可撤销
        if (record.status == RecordStatus.REVERTED) {
            AppLog.w(TAG, "undoManual rejected record=$recordId reason=already-reverted")
            return@withTransaction false
        }
        if (record.slotId != null) {
            // 有槽位的走 undoDose：它还要把槽位退回 PENDING，
            // 否则下一次打卡会因为槽位已是 COMPLETED 而被幂等拦掉。
            return@withTransaction undoDose(record.slotId)
        }

        val net = inventoryDao.getSumOfChangeByRecordId(recordId) ?: 0
        if (recordDao.markReverted(recordId) == 0) {
            AppLog.w(TAG, "undoManual rejected record=$recordId reason=lost-race")
            return@withTransaction false
        }

        if (net < 0) {
            appendLedger(
                medicationId = record.medicationId,
                recordId = recordId,
                changeAmount = Dose(-net),
                txType = TransactionType.REVERT_ROLLBACK,
                note = null,
                noteKey = LedgerNoteKey.UNDO_TEMP_REVERT.name
            )
        }
        AppLog.i(TAG, "undoManual ok record=$recordId med=${record.medicationId} rolledBackMilli=$(-net.coerceAtMost(0))")
        true
    }

    /**
     * 修改一条服药记录的时间 / 剂量 / 备注。
     *
     * @param newDoseAmount null = 不改剂量；否则必须为正（同 M2-2 的符号防御）。
     * @param newActualTs null = 不改时间；否则必须是**已发生**的时刻。
     * @return true 表示确实改动了什么；false 表示参数非法、无此记录、已撤销，
     *         或试图改动槽位来源记录的时间 / 剂量。
     *
     * ## 剂量变更必须动台账
     *
     * 只改 `dose_records.dose_taken` 而不补流水，会让
     * `SUM(change_amount)` 与"记录上写的剂量"**永久分叉** ——
     * 用户看到"这剂吃了 2 片"、账上只扣了 1 片，库存页永远对不上。
     *
     * 做法是**补一条差额流水**而不是 UPDATE 原行（台账只增不改，I1/I2）：
     * 旧剂量 1 片改成 2 片 ⇒ 补 `changeAmount = -1000`；
     * 改小则补正数。冲正依据取 `getSumOfChangeByRecordId` 的**净额**，
     * 与撤销同一口径 —— 该记录若当初"没扣库存"（未开追踪 / 补录时关掉联动），
     * 净额就是 0，此时改剂量**不产生流水**，也就不会凭空造账。
     *
     * ## 槽位来源的记录不许改时间 / 剂量（UX 方案 §3.3）
     *
     * 定时提醒产生的记录，事实时间与槽位的 `scheduled_time`、事实剂量与槽位的
     * `doseAmount` 本来就是配对的。单独改事实会让两处各说一套 —— 与 C-40 同类。
     * 正确做法是撤销后重新打卡。
     */
    suspend fun editDose(
        recordId: Long,
        newDoseAmount: Float? = null,
        newNote: String? = null,
        newActualTs: Long? = null
    ): Boolean = db.withTransaction {
        require(newDoseAmount == null || Dose.isWithinRange(newDoseAmount)) {
            "服药剂量必须大于 0 且不超过 ${Dose.MAX_MILLI / 1000}，当前 $newDoseAmount"
        }
        val record = recordDao.getRecordById(recordId) ?: run {
            AppLog.w(TAG, "editDose rejected record=$recordId reason=record-missing")
            return@withTransaction false
        }
        if (record.status == RecordStatus.REVERTED) {
            AppLog.w(TAG, "editDose rejected record=$recordId reason=already-reverted")
            return@withTransaction false
        }

        // 时间不允许改到未来：服药是**已发生**的事实，
        // 记一条"未来吃过"会让依从率统计凭空多出一次。
        require(newActualTs == null || newActualTs <= System.currentTimeMillis()) {
            "服药时间不能晚于当前时刻"
        }
        // 同理不允许改到补录窗口之外（[MANUAL_DOSE_BACKFILL_DAYS]）——
        // 与 logManualDose 的下界同源，否则"先补录再改时间"能绕过时间窗。
        require(
            newActualTs == null ||
                !Instant.ofEpochMilli(newActualTs).atZone(ZoneId.systemDefault())
                    .toLocalDate().isBefore(todayProvider().minusDays(MANUAL_DOSE_BACKFILL_DAYS))
        ) {
            "服药时间不能早于 $MANUAL_DOSE_BACKFILL_DAYS 天前"
        }

        val fromSlot = record.slotId != null
        // 槽位来源的记录：时间与剂量都拒改（理由见 KDoc）
        if (fromSlot && newActualTs != null && newActualTs != record.actualTs) {
            AppLog.w(TAG, "editDose rejected record=$recordId reason=slot-source-time-immutable")
            return@withTransaction false
        }
        if (fromSlot && newDoseAmount != null && Dose.of(newDoseAmount).milli != record.doseTaken) {
            AppLog.w(TAG, "editDose rejected record=$recordId reason=slot-source-dose-immutable")
            return@withTransaction false
        }

        var changed = false
        var doseChangeDesc = ""
        var timeChanged = false
        var noteChanged = false

        // ---- 时间：纯事实修正，不动台账 ----
        if (newActualTs != null && newActualTs != record.actualTs) {
            if (recordDao.updateActualTs(recordId, newActualTs) == 0) {
                AppLog.w(TAG, "editDose rejected record=$recordId reason=update-ts-miss")
                return@withTransaction false
            }
            changed = true
            timeChanged = true
        }

        // ---- 剂量：先算差额，再补流水 ----
        if (newDoseAmount != null) {
            val newDose = Dose.of(newDoseAmount)
            if (newDose.milli != record.doseTaken) {
                val net = inventoryDao.getSumOfChangeByRecordId(recordId) ?: 0
                if (recordDao.updateDose(recordId, newDose.milli) == 0) {
                    AppLog.w(TAG, "editDose rejected record=$recordId reason=update-dose-miss")
                    return@withTransaction false
                }
                // 目标是把该记录的净额从 `-旧剂量` 挪到 `-新剂量`，
                // 所以补记的差额 = `旧 - 新`：改大 ⇒ 负数（再扣），改小 ⇒ 正数（退回多扣的）。
                //
                // ⚠️ 这里**不能**用 `net` 参与运算：`net` 是负数（`-旧剂量`），
                // 写成 `net - 新剂量` 等于"再扣一遍新剂量" ——
                // 1 片改成 2 片会共扣 3 片，账实从此分叉。
                // 这个错误是写测试时真踩出来的，见 DoseRecordEditTest。
                //
                // 顺带注意 `net != 0` 这个前提：该记录当初若没扣库存
                // （未开追踪 / 补录时关掉了联动），净额为 0，
                // 此时改剂量**不产生流水** —— 不能凭空给账面减药。
                if (net != 0) {
                    val delta = Dose(record.doseTaken - newDose.milli)
                    if (!delta.isZero) {
                        appendLedger(
                            medicationId = record.medicationId,
                            recordId = recordId,
                            changeAmount = delta,
                            txType = TransactionType.DOSE_EDIT_ADJUST,
                            note = "${fmtQty(Dose(record.doseTaken).asFloat)} → ${fmtQty(newDose.asFloat)}",
                            noteKey = LedgerNoteKey.DOSE_EDIT.name
                        )
                    }
                }
                changed = true
                doseChangeDesc = " doseMilli=${record.doseTaken}->${newDose.milli}"
            }
        }

        // ---- 备注：纯文本，不产生流水 ----
        if (newNote != null && newNote != record.note) {
            recordDao.updateNote(recordId, newNote.ifBlank { null })
            changed = true
            noteChanged = true
        }

        if (changed) {
            AppLog.i(
                TAG,
                "editDose ok record=$recordId med=${record.medicationId}" +
                    (if (timeChanged) " time=changed" else "") +
                    doseChangeDesc +
                    (if (noteChanged) " note=changed" else "")
            )
        }
        changed
    }

    // ==================== 5. 补充录入 / 临时按需服药 ====================

    /**
     * @param deductStock 是否联动扣减库存台账。
     *   补录历史服药时用户常需要"只记事实、不动库存"，开关关闭时仅写服药事实。
     *   服药是不可否认的事实，因此 **绝不因为库存不足而阻止记账**。
     */
    suspend fun logManualDose(
        medicationId: Long,
        actualTs: Long,
        doseAmount: Float,
        isRetrospective: Boolean = false,
        note: String? = null,
        deductStock: Boolean = true
    ): Long = db.withTransaction {
        // ⚠️ 符号防御（M2-2）。服务层是**所有**入口的公共下游：
        // 补录页、通知栏 Action、未来的 Widget / 手表 / 快捷指令。
        // 只靠 UI 过滤挡等于"约定只有一种调用方" —— 那一天到来时没人会记得这条约定。
        //
        // 负剂量的后果特别恶劣：`-Dose.of(doseAmount)` 是**加**库存，
        // 于是"补录一次负剂量服药"会凭空给账面加药，且事实记录显示"已服用 -2 片"。
        require(Dose.isWithinRange(doseAmount)) {
            "服药剂量必须大于 0 且不超过 ${Dose.MAX_MILLI / 1000}，当前 $doseAmount"
        }
        // 量化后守正 + 上界（orsbf P0-4 / §二-18）：`Dose.isWithinRange` 已同时
        // 保证"量化后 > 0"与"未触上界（溢出被钳制前就已判假）"，这里只取值。
        val doseMilli = Dose.of(doseAmount).milli
        // 时间窗守卫（[MANUAL_DOSE_BACKFILL_DAYS]）：上界拒绝未来时刻（60 秒容差
        // 对齐 UI 的 `isAfter(now + 1min)`），下界按自然日 —— 与记录详情页的
        // 撤销窗口 `age in 0..N` 同一套日历口径，避免"校验与窗口各说各话"
        // （AGENTS §2 坑 7：校验判据要和兜底判据同源）。
        val now = System.currentTimeMillis()
        require(actualTs <= now + 60_000L) {
            "不能补录未来的服药时间"
        }
        val minDate = todayProvider().minusDays(MANUAL_DOSE_BACKFILL_DAYS)
        val actualDate = Instant.ofEpochMilli(actualTs).atZone(ZoneId.systemDefault()).toLocalDate()
        require(!actualDate.isBefore(minDate)) {
            "只能补录最近 $MANUAL_DOSE_BACKFILL_DAYS 天内的服药（早于 $minDate）"
        }
        val medication = medDao.getMedicationById(medicationId)
            ?: throw IllegalArgumentException("Medication not found: $medicationId")

        val actualDateStr = actualDate.format(SlotProjectionEngine.DATE_FORMATTER)
        val candidateSlots = slotDao.getSlotsForDate(actualDateStr).filter {
            it.medicationId == medicationId &&
                (it.status == SlotStatus.PENDING || it.status == SlotStatus.SNOOZED || it.status == SlotStatus.EXPIRED)
        }
        val matchedSlot = candidateSlots.minByOrNull { Math.abs(it.scheduledTs - actualTs) }
        val targetSlotId = matchedSlot?.id

        if (matchedSlot != null) {
            slotDao.markCompletedIfOpen(matchedSlot.id, actualTs, todayStr())
        }

        val effectiveRetro = isRetrospective || (actualDate < todayProvider())
        val record = DoseRecordEntity(
            slotId = targetSlotId,
            medicationId = medicationId,
            actualTs = actualTs,
            doseTaken = doseMilli,
            status = RecordStatus.COMPLETED,
            isRetrospective = effectiveRetro,
            note = note,
            noteKey = if (note == null) RecordNoteKey.MANUAL_BACKFILL.name else null
        )
        val recordId = recordDao.insert(record)

        if (deductStock && medication.isStockTracked) {
            appendLedger(
                medicationId = medicationId,
                recordId = recordId,
                changeAmount = -Dose(doseMilli),
                txType = TransactionType.TAKEN_DEDUCT,
                note = note,
                noteKey = if (effectiveRetro) {
                    LedgerNoteKey.RETRO_DEDUCT.name
                } else {
                    LedgerNoteKey.PRN_DEDUCT.name
                }
            )
        }

        AppLog.i(
            TAG,
            "logManualDose ok med=$medicationId record=$recordId doseMilli=$doseMilli" +
                " retrospective=$isRetrospective deductStock=${deductStock && medication.isStockTracked}"
        )
        return@withTransaction recordId
    }

    // ==================== 5b. 库存盘点校准 ====================

    /**
     * 用户手中实物与系统账面不符时（换包装 / 之前漏记 / 初次建档修正），
     * 通过写入一条 `CALIBRATION_ADJUST` 流水把账面拉回真实值。
     */
    suspend fun calibrateStock(
        medicationId: Long,
        actualStock: Float,
        note: String? = null
    ): Boolean = db.withTransaction {
        // 实测库存可以是 0（用完了），但**不能是负数**（M2-2）。
        // 负的"实物"在物理上不存在，而它与账面的差额会被写成一条调增流水，
        // 于是凭空给账面加药 —— 与负剂量同一类危害。
        // 注意这里**不**要求 `> 0`：0 是合法的"刚好用完"。
        require(actualStock >= 0f && actualStock.isFinite()) {
            "实测库存不能为负数，当前 $actualStock"
        }
        val medication = medDao.getMedicationById(medicationId) ?: run {
            AppLog.w(TAG, "calibrateStock rejected med=$medicationId reason=med-missing")
            return@withTransaction false
        }
        val currentBalance = balanceOf(medicationId)
        val delta = Dose.of(actualStock) - Dose(currentBalance)
        if (delta.isZero) {
            // 良性 no-op：账面与实物本来就一致。INFO 而非 WARN，
            // 给"用户以为校准了"一个可查的痕迹即可
            AppLog.i(TAG, "calibrateStock no-op med=$medicationId balanceMilli=$currentBalance")
            return@withTransaction false
        }

        appendLedger(
            medicationId = medicationId,
            recordId = null,
            changeAmount = delta,
            txType = TransactionType.CALIBRATION_ADJUST,
            note = listOfNotNull(
                "${fmtQty(Dose(currentBalance).asFloat)} → ${fmtQty(Dose.of(actualStock).asFloat)}",
                note?.takeIf { it.isNotBlank() }
            ).joinToString(" · "),
            noteKey = LedgerNoteKey.CALIBRATE.name
        )
        AppLog.i(
            TAG,
            "calibrateStock ok med=$medicationId fromMilli=$currentBalance toMilli=${Dose.of(actualStock).milli} deltaMilli=${delta.milli}"
        )
        return@withTransaction true
    }

    // ==================== 5c. 开启 / 关闭库存追踪 ====================

    /**
     * 开启追踪时，若账面为 0 而用户给了一个初始值，则追加一条**建档流水**。
     * 关闭追踪不产生任何流水（只是不再自动扣减）。
     */
    suspend fun setStockTracking(
        medicationId: Long,
        enabled: Boolean,
        initialStock: Float? = null
    ): Boolean = db.withTransaction {
        val medication = medDao.getMedicationById(medicationId) ?: run {
            AppLog.w(TAG, "setStockTracking rejected med=$medicationId reason=med-missing")
            return@withTransaction false
        }
        medDao.updateStockTracking(medicationId, enabled)

        if (enabled) {
            val current = balanceOf(medicationId)
            val target = initialStock?.let { Dose.of(it) } ?: Dose(current)
            if (target.milli > 0 && current == 0) {
                // 从零建档：一条流水即可，账面随之成立。
                // ⚠️ 条件必须是 `== 0` 而不是 `<= 0`：账面为负是 D-9 明文允许的状态
                //（补药/盘点不校验追踪开关，追踪关闭期间账面照样可能变负），
                // 此时追加**全额**会得到 `current + target ≠ target`——
                // 用户声明实物 30，账面却是 28。负账必须落到下方"校准差额"分支。
                appendLedger(
                    medicationId = medicationId,
                    recordId = null,
                    changeAmount = target,
                    txType = TransactionType.CALIBRATION_ADJUST,
                    note = null,
                    noteKey = LedgerNoteKey.TRACKING_INIT.name
                )
                AppLog.i(TAG, "setStockTracking ok med=$medicationId enabled=true branch=create-from-zero targetMilli=${target.milli}")
            } else if (target.milli != current) {
                // 用户给了与账面不同的初值 → 走盘点校准，绝不直接改账面
                appendLedger(
                    medicationId = medicationId,
                    recordId = null,
                    changeAmount = Dose(target.milli - current),
                    txType = TransactionType.CALIBRATION_ADJUST,
                    note = null,
                    noteKey = LedgerNoteKey.TRACKING_INIT_CALIBRATE.name
                )
                AppLog.i(TAG, "setStockTracking ok med=$medicationId enabled=true branch=calibrate fromMilli=$current targetMilli=${target.milli}")
            } else {
                AppLog.i(TAG, "setStockTracking ok med=$medicationId enabled=true branch=no-ledger balanceMilli=$current")
            }
        } else {
            AppLog.i(TAG, "setStockTracking ok med=$medicationId enabled=false")
        }
        return@withTransaction true
    }

    // ==================== 6. 药房补货采购入库 ====================

    /** 追加一条 REFILL 流水。不读也不改 `medications`。 */
    suspend fun refillStock(
        medicationId: Long,
        addedAmount: Float,
        note: String? = null,
        batchNumber: String? = null,
        expiryDate: String? = null
    ): Boolean = db.withTransaction {
        // "入库"量必须为正（M2-2）。负数入库 = 记一笔 REFILL 却让账面**减少** ——
        // 台账上写着"采购入库补货"，金额是负的，事后没人能看出发生过什么。
        // 0 同样拒绝：一条零额流水没有任何信息量。
        require(addedAmount > 0f && addedAmount.isFinite()) {
            "入库数量必须大于 0，当前 $addedAmount"
        }
        if (medDao.getMedicationById(medicationId) == null) {
            AppLog.w(TAG, "refillStock rejected med=$medicationId reason=med-missing")
            return@withTransaction false
        }

        // 统一走 appendLedger 唯一入口（L-4）：余额快照与审计日志与打卡/盘点同源，
        // 不在这里自算 balanceAfter。
        appendLedger(
            medicationId = medicationId,
            recordId = null,
            changeAmount = Dose.of(addedAmount),
            txType = TransactionType.REFILL,
            note = note,
            noteKey = LedgerNoteKey.REFILL.name,
            batchNumber = batchNumber,
            expiryDate = expiryDate
        )
        return@withTransaction true
    }

    // ==================== 7. 排班重对齐 ====================

    /**
     * 修改计划后的排班对齐核算。满足「改计划后历史打卡数据不可丢失」。
     *
     * ## 从"全删重建"改为"幂等 diff"（P1-5）
     *
     * 旧实现是 `deleteFuturePendingSlots(...)` + 全量重新投影插入。三个问题：
     *
     * 1. **每次对账都换一批 slot id**。而 `slot.id` 是闹钟的 `requestCode`，
     *    于是旧闹钟变成**孤儿**（新 id 的闹钟注册上去，旧 id 的还在）——
     *    提醒会响两次，且孤儿闹钟永远不会被取消。
     * 2. 反复删插会打断已注册的闹钟，**凭空多一次唤醒**。
     * 3. 删了再插的过程不是原子的（虽然这里包在事务里，但事务外仍有并发对账的可能）。
     *
     * ## 时间轴分三区，各区规则不同
     *
     * ```
     *   < fromDate            [ fromDate .. toDate ]              > toDate
     *   ─────��───────────────┼───────────────────────────────┼──────────────
     *    过去：绝不触碰        权威窗口：与投影逐条 diff           投机区：整段丢弃
     *    （已服/已跳/已逾期     删：不再被命中的 PENDING/SNOOZED    理由见
     *      是既成事实）        留：仍被命中的（**保留原 id**）      deleteSpeculativeFutureSlots
     *                          插：新增的（DB 唯一约束兜底）
     * ```
     *
     * **投机区必须整段丢弃**，这是本轮补上的一个真实泄漏：
     * 只 diff 窗口内的话，窗口外那些"上一次顺手多排的猜测"永远删不掉；
     * 而 `AlarmReconciler` 会给所有开放槽位排闹钟，于是改完计划后
     * 用户会收到一个**已不存在的服药时间**的提醒，且该闹钟再也取消不掉。
     */
    suspend fun reconcileSchedule(
        medicationId: Long,
        fromDate: LocalDate = LocalDate.now(),
        toDate: LocalDate = LocalDate.now().plusDays(14),
        zoneId: ZoneId = ZoneId.systemDefault()
    ) = db.withTransaction {
        // ⚠️ 无有效计划 ≠ 什么都不做。
        //
        // 此前这里直接 `?: return@withTransaction` 提前返回，于是"用户删掉了这个药的
        // 服用计划"这种情况，窗口内已有的 PENDING 槽位**永远删不掉**：
        // 它们不是既成事实（没服药），却仍留在库里，被统计当成"该吃没吃"，
        // 也会出现在今日清单里 —— 而闹钟早已被对账器撤掉，**永远不会响**。
        //
        // 所以无计划时把投影当成"空集"，让下面的删除逻辑照常执行。
        //
        // 归档药品同理（P2#4）：归档 ≠ 无计划，`schedule_policies` 里那条计划仍是
        // active 的，若只按政策投影，归档药的槽位会被继续物化。但"停药"的药不该再欠
        // 任何待办 —— 它的开放槽位不是既成事实，必须按空集投影，由下方删除逻辑清掉。
        val activePolicy = policyDao.getActivePolicyForMedication(medicationId)
        val policy = if (activePolicy != null &&
            medDao.getMedicationById(medicationId)?.isArchived == true
        ) {
            null
        } else {
            activePolicy
        }
        val projectedSlots = if (policy == null) {
            emptyList()
        } else {
            SlotProjectionEngine.projectSlots(
                policy = policy,
                times = policyDao.getTimesForPolicy(policy.id),
                fromDate = fromDate,
                toDate = toDate,
                zoneId = zoneId,
                // 暂停参与投影：暂停期内不产生槽位，于是"没有提醒"就没有"待服项"，
                // 今日清单、统计、闹钟三处自动一致。见 SlotProjectionEngine 的 KDoc。
                pausedUntil = db.reminderSettingsDao()
                    .getByMedicationId(medicationId)?.pausedUntil
            )
        }

        val fromStr = fromDate.format(SlotProjectionEngine.DATE_FORMATTER)
        val toStr = toDate.format(SlotProjectionEngine.DATE_FORMATTER)
        // SQL 内过滤（osbf P3-7）：逐药调用不再把全窗口的行拉回来再丢
        val existing = slotDao.getSlotsInRangeForMedication(
            medicationId = medicationId,
            startDate = fromStr,
            endDate = toStr
        )

        val projectedKeys = projectedSlots.map { slotKey(it.scheduledDate, it.scheduledTime) }.toSet()

        // ---- 删（窗口内）：不再被投影命中的 PENDING / SNOOZED ----
        // 已完成 / 已跳过 / 已逾期是既成事实，任何情况下都不动。
        val obsolete = existing.filter {
            it.status == SlotStatus.PENDING || it.status == SlotStatus.SNOOZED
        }.filter { slotKey(it.scheduledDate, it.scheduledTime) !in projectedKeys }
        if (obsolete.isNotEmpty()) {
            // DELETE ... IN 的变量上限同样是 999（orsbf P0-1）：按 500 一批。
            // 本方法可重入（幂等 diff），分批中断由下一轮对账补齐。
            obsolete.map { it.id }.chunked(500).forEach { slotDao.deleteByIds(it) }
        }

        // ---- 删（投机区）：窗口之外的未来整段丢弃 ----
        val speculativeDeleted = slotDao.deleteSpeculativeFutureSlots(medicationId, toStr)

        // ---- 留（被命中）：保留原 id（闹钟身份因此稳定），但同步可派生的列 ----
        //
        // ⚠️ 只保留不更新是不够的。`dose_amount` 若被冻结在创建时，
        // 用户把剂量从 1 片改成 2 片之后，接下来 14 天每次打卡都按 1 片扣库存 ——
        // 而 `dose_records.dose_taken` 是**不可变事实**（I11 不许事后修正），
        // 于是错误被永久固化。
        //
        // 为什么改「服药时刻」没这个问题：key 变了 ⇒ 旧槽位删、新槽位插 ⇒ 剂量自然新。
        // 漏的恰恰是「时刻不变、剂量变了」这条最高频的路径。
        //
        // 只更新两个**可从投影完全派生**的列，且只针对仍开放的槽位：
        // 已 COMPLETED / SKIPPED / EXPIRED 的是既成事实，其剂量必须与
        // 对应的 `dose_records` 一致，动它就是改历史。
        val existingByKey = existing.associateBy { slotKey(it.scheduledDate, it.scheduledTime) }
        val staleDerived = projectedSlots.mapNotNull { projected ->
            val old = existingByKey[slotKey(projected.scheduledDate, projected.scheduledTime)]
                ?: return@mapNotNull null
            if (old.status != SlotStatus.PENDING && old.status != SlotStatus.SNOOZED) return@mapNotNull null
            if (old.doseAmount == projected.doseAmount &&
                old.policyId == projected.policyId &&
                old.scheduledTs == projected.scheduledTs
            ) {
                return@mapNotNull null
            }
            StaleDerived(old.id, projected.doseAmount, projected.policyId, projected.scheduledTs)
        }
        staleDerived.forEach {
            slotDao.updateDerivedColumns(
                slotId = it.slotId,
                doseMilli = it.doseMilli,
                policyId = it.policyId,
                scheduledTs = it.scheduledTs
            )
        }

        // ---- 插：新增的 ----
        val existingKeys = existing.map { slotKey(it.scheduledDate, it.scheduledTime) }.toSet()
        val toInsert = projectedSlots.filter {
            slotKey(it.scheduledDate, it.scheduledTime) !in existingKeys
        }
        if (toInsert.isNotEmpty()) {
            // IGNORE + DB 唯一约束：即使与并发对账撞了重复键，也只是这一行被忽略，
            // 而不是整批插入失败。
            // 分批（orsbf P0-1）：Room 的 @Insert(List) 是一条多值 INSERT，
            // 超过 SQLite 变量上限 999（dose_slots 11 列 ⇒ ~90 行）会整体失败；
            // 本方法幂等可重入，分批中断由下一轮对账补齐。
            toInsert.chunked(50).forEach { slotDao.insertAll(it) }
        }

        // diff 汇总（G1）：P1-5 幂等 diff 出问题时，这一行是归因起点。
        // 每药每轮一条，量级可控；循环体内的逐槽日志被 D7 纪律禁止。
        AppLog.i(
            TAG,
            "reconcileSchedule med=$medicationId window=$fromStr..$toStr projected=${projectedSlots.size}" +
                " deleted=${obsolete.size} speculativeDeleted=$speculativeDeleted" +
                " derivedUpdated=${staleDerived.size} inserted=${toInsert.size}"
        )
    }

    /** 槽位的业务唯一键，与 `dose_slots` 的 UNIQUE 索引定义保持一致 */
    private fun slotKey(date: String, time: String): String = "$date $time"

    /**
     * 一次 `updateDerivedColumns` 的入参。
     *
     * 提成 data class 而不是继续用 `Triple`：Triple 的三个位置在下一次
     * 加第四列时会静默**接错位**（编译器不会报错，因为类型都是 Long/Int 的组合），
     * 而这四个值每一个接错都意味着"改剂量时把时间戳写成了策略 id"。
     */
    private data class StaleDerived(
        val slotId: Long,
        val doseMilli: Int,
        val policyId: Long,
        val scheduledTs: Long
    )

    // ==================== 工具 ====================

    /**
     * 流水 note 载荷里的数量格式化（B5：DOSE_EDIT / CALIBRATE 的 `a → b` 载荷）。
     *
     * 规则与 `ui.component.Quantity.fmt` **必须逐字一致**（整数去尾零、
     * 非整数 `%.2f`），但它住在 ui 层，`core/domain` 零依赖铁律不允许反向 import，
     * 只能在这里保留同规则实现 —— 由 `QuantityTest` 钉住的那条规则是唯一权威，
     * 改它时这里必须同步。
     */
    private fun fmtQty(value: Float): String =
        if (value == 0f) "0"
        else if (value % 1f == 0f) value.toInt().toString()
        else String.format(Locale.getDefault(), "%.2f", value)

    // ⚠️ M8-1 曾删除过一个同名的 `private fun fmtQty`（零调用方 + 与
    // `com.mcxiaoke.carromed.ui.component.Quantity.fmt` 重复）。B5 把它请了回来，
    // 但理由与 M8-1 不同：**不是**为了展示，而是为了写进 `note` 载荷列
    // （DOSE_EDIT / CALIBRATE 的 `a → b`）。
    //
    // 展示层归 `Quantity`，载荷层归这里 —— `core/domain` 零 `android.*` 依赖铁律
    // 不允许反向 import ui 层，只能保留同规则实现。漂移风险由 `QuantityTest`
    // 钉住的那条规则兜底：**改 `Quantity.fmt` 必须同步改这里**。
}
