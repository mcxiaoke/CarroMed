package com.mcxiaoke.carromed.core.domain.service

import androidx.room.withTransaction
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.DoseRecordEntity
import com.mcxiaoke.carromed.core.data.entity.DoseSlotEntity
import com.mcxiaoke.carromed.core.data.entity.InventoryTransactionEntity
import com.mcxiaoke.carromed.core.data.model.RecordStatus
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import com.mcxiaoke.carromed.core.data.model.TransactionType
import com.mcxiaoke.carromed.core.domain.engine.SlotProjectionEngine
import com.mcxiaoke.carromed.core.domain.model.Dose
import java.time.LocalDate
import java.time.ZoneId

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
 */
class DoseTrackingService(private val db: AppDatabase) {

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
        note: String?
    ) {
        val balanceAfter = balanceOf(medicationId) + changeAmount.milli
        inventoryDao.insert(
            InventoryTransactionEntity(
                medicationId = medicationId,
                recordId = recordId,
                changeAmount = changeAmount.milli,
                balanceAfter = balanceAfter,
                txType = txType,
                note = note
            )
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
        note: String? = null
    ): Boolean = db.withTransaction {
        // 显式传入的剂量同样必须为正（M2-2）。
        // 负剂量打卡 = 扣减变成**加**库存，是这条路径上最恶劣的失败模式。
        // `slot.doseAmount` 那一路不需要校验：它由 `saveReminderPolicy` 的
        // `require(dose > 0)` 在写入时保证了（那是所有时点剂量的唯一来源）。
        require(takenAmount == null || (takenAmount > 0f && takenAmount.isFinite())) {
            "服药剂量必须大于 0，当前 $takenAmount"
        }
        val slot = slotDao.getSlotById(slotId) ?: return@withTransaction false
        val medication = medDao.getMedicationById(slot.medicationId) ?: return@withTransaction false

        val finalDose: Dose = takenAmount?.let { Dose.of(it) } ?: Dose(slot.doseAmount)

        // 幂等锚点下沉到 SQL：只有仍在等待的槽位才被置为 COMPLETED。
        // 受影响行数为 0 ⇒ 已被处理过，直接放弃记账（连点不会重复扣库存）。
        if (slotDao.markCompletedIfOpen(slotId, actualTs) == 0) {
            return@withTransaction false
        }

        val record = DoseRecordEntity(
            slotId = slotId,
            medicationId = slot.medicationId,
            actualTs = actualTs,
            doseTaken = finalDose.milli,
            status = RecordStatus.COMPLETED,
            isRetrospective = false,
            note = note
        )
        val recordId = recordDao.insert(record)

        // 库存：单条流水，允许扣成负数（D-9）
        if (medication.isStockTracked) {
            appendLedger(
                medicationId = slot.medicationId,
                recordId = recordId,
                changeAmount = -finalDose,
                txType = TransactionType.TAKEN_DEDUCT,
                note = note ?: "按时服药打卡扣减"
            )
        }

        return@withTransaction true
    }

    // ==================== 2. 跳过服药 ====================

    /** 原子事务：更新槽位状态为 SKIPPED → 插入 SKIPPED 事实记录。不影响库存。 */
    suspend fun skipDose(
        slotId: Long,
        reason: String? = null
    ): Boolean = db.withTransaction {
        val slot = slotDao.getSlotById(slotId) ?: return@withTransaction false

        val now = System.currentTimeMillis()
        // 幂等锚点下沉到 SQL（允许对已逾期的槽位补记跳过）
        if (slotDao.markSkippedIfOpen(slotId, now) == 0) {
            return@withTransaction false
        }

        val record = DoseRecordEntity(
            slotId = slotId,
            medicationId = slot.medicationId,
            actualTs = now,
            doseTaken = 0,
            status = RecordStatus.SKIPPED,
            note = reason ?: "主动跳过本次服药"
        )
        recordDao.insert(record)
        return@withTransaction true
    }

    // ==================== 3. 推迟提醒 (Snooze) ====================

    suspend fun snoozeDose(slotId: Long, snoozeMinutes: Int): Boolean {
        // 上界钳制：推迟时长同时决定 `snooze_until_ts` 的偏移量，
        // `Int.MAX_VALUE` 分钟会溢出成一个**已经过去**的时间戳 ⇒ 推迟后立刻被判逾期。
        // 下界 1 分钟则会让"立刻重响"成为可能。两端都收到与通知栏按钮一致的取值域。
        val safeMinutes = snoozeMinutes.coerceIn(1, 240)
        val snoozeUntilTs = System.currentTimeMillis() + (safeMinutes * 60 * 1000L)
        return slotDao.snoozeSlot(slotId, snoozeUntilTs) > 0
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
        val slot = slotDao.getSlotById(slotId) ?: return@withTransaction false
        // 幂等锚点：只有"已产生结论"的槽位才可撤销，与 takeDose/skipDose 的返回语义保持一致
        if (slot.status != SlotStatus.COMPLETED && slot.status != SlotStatus.SKIPPED) {
            return@withTransaction false
        }
        // 槽位必须真有事实（跳过也会留一条 SKIPPED 事实）
        if (recordDao.getAllRecordsBySlotId(slotId).isEmpty()) return@withTransaction false

        // ---- 库存层：先算出"这一轮实际还欠多少扣"，再回退槽位 ----
        //
        // ⚠️ 判据是**该事实的台账净额**，不是"读一条代表事实的剂量"，
        // 也不是"药品当前是否追踪库存"。两个旧写法都会错：
        //
        // | 写法 | 错在哪 |
        // | :--- | :--- |
        // | `getRecordBySlotId` 取 `id ASC LIMIT 1` 读它的 `doseTaken` | 第二次撤销拿到的是最早那条（已 `REVERTED`）⇒ 判"没扣过"⇒ **账面凭空少一次扣减** |
        // | `medication.isStockTracked`（当前值） | 打卡后用户改过追踪开关 ⇒ 虚增或永远不回补 |
        //
        // 净额口径同时解决两者，而且天然幂等：冲正后该事实净额变 0，
        // 重复撤销时 `net >= 0`，不会再补第二条。
        val rollbacks = recordDao.getCompletedRecordsBySlot(slotId).mapNotNull { rec ->
            val net = inventoryDao.getSumOfChangeByRecordId(rec.id) ?: 0
            if (net < 0) rec.id to Dose(-net) else null
        }

        // 条件回退（原子；受影响行数为 0 表示并发下已被别人撤销）
        if (slotDao.revertToPending(slotId) == 0) return@withTransaction false

        // 事实层：保留记录，仅改状态（append-only 的补偿，而不是抹除）
        recordDao.markRevertedBySlot(slotId)

        // 台账只增不改：逐条补等额冲正
        rollbacks.forEach { (recordId, amount) ->
            appendLedger(
                medicationId = slot.medicationId,
                recordId = recordId,
                changeAmount = amount,
                txType = TransactionType.REVERT_ROLLBACK,
                note = "用户误触打卡撤销冲正"
            )
        }
        return@withTransaction true
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
        require(doseAmount > 0f && doseAmount.isFinite()) {
            "服药剂量必须大于 0，当前 $doseAmount"
        }
        val medication = medDao.getMedicationById(medicationId)
            ?: throw IllegalArgumentException("Medication not found: $medicationId")

        val record = DoseRecordEntity(
            slotId = null,
            medicationId = medicationId,
            actualTs = actualTs,
            doseTaken = Dose.of(doseAmount).milli,
            status = RecordStatus.COMPLETED,
            isRetrospective = isRetrospective,
            note = note
        )
        val recordId = recordDao.insert(record)

        if (deductStock && medication.isStockTracked) {
            appendLedger(
                medicationId = medicationId,
                recordId = recordId,
                changeAmount = -Dose.of(doseAmount),
                txType = TransactionType.TAKEN_DEDUCT,
                note = note ?: if (isRetrospective) "事后补录服药扣减" else "按需/临时服药扣减"
            )
        }

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
        val medication = medDao.getMedicationById(medicationId) ?: return@withTransaction false
        val currentBalance = balanceOf(medicationId)
        val delta = Dose.of(actualStock) - Dose(currentBalance)
        if (delta.isZero) return@withTransaction false

        appendLedger(
            medicationId = medicationId,
            recordId = null,
            changeAmount = delta,
            txType = TransactionType.CALIBRATION_ADJUST,
            note = note ?: "库存盘点校准 (账面 ${Dose(currentBalance).asFloat} → 实物 ${Dose.of(actualStock).asFloat})"
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
        val medication = medDao.getMedicationById(medicationId) ?: return@withTransaction false
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
                    note = "开启库存追踪建档"
                )
            } else if (target.milli != current) {
                // 用户给了与账面不同的初值 → 走盘点校准，绝不直接改账面
                appendLedger(
                    medicationId = medicationId,
                    recordId = null,
                    changeAmount = Dose(target.milli - current),
                    txType = TransactionType.CALIBRATION_ADJUST,
                    note = "开启库存追踪建档校准"
                )
            }
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
        val medication = medDao.getMedicationById(medicationId) ?: return@withTransaction false

        val balanceAfter = balanceOf(medicationId) + Dose.of(addedAmount).milli
        inventoryDao.insert(
            InventoryTransactionEntity(
                medicationId = medicationId,
                recordId = null,
                changeAmount = Dose.of(addedAmount).milli,
                balanceAfter = balanceAfter,
                txType = TransactionType.REFILL,
                note = note ?: "采购入库补货",
                batchNumber = batchNumber,
                expiryDate = expiryDate
            )
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
        val existing = slotDao
            .getSlotsInRange(startDate = fromStr, endDate = toStr)
            .filter { it.medicationId == medicationId }

        val projectedKeys = projectedSlots.map { slotKey(it.scheduledDate, it.scheduledTime) }.toSet()

        // ---- 删（窗口内）：不再被投影命中的 PENDING / SNOOZED ----
        // 已完成 / 已跳过 / 已逾期是既成事实，任何情况下都不动。
        val obsolete = existing.filter {
            it.status == SlotStatus.PENDING || it.status == SlotStatus.SNOOZED
        }.filter { slotKey(it.scheduledDate, it.scheduledTime) !in projectedKeys }
        if (obsolete.isNotEmpty()) {
            slotDao.deleteByIds(obsolete.map { it.id })
        }

        // ---- 删（投机区）：窗口之外的未来整段丢弃 ----
        slotDao.deleteSpeculativeFutureSlots(medicationId, toStr)

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
            slotDao.insertAll(toInsert)
        }
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

    private fun fmtQty(v: Float): String = if (v % 1f == 0f) v.toInt().toString() else v.toString()
}
