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

    suspend fun snoozeDose(
        slotId: Long,
        snoozeMinutes: Int
    ): Boolean {
        val snoozeUntilTs = System.currentTimeMillis() + (snoozeMinutes * 60 * 1000L)
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
        val record = recordDao.getRecordBySlotId(slotId) ?: return@withTransaction false
        val medication = medDao.getMedicationById(slot.medicationId) ?: return@withTransaction false

        // 条件回退（原子；受影响行数为 0 表示并发下已被别人撤销）
        if (slotDao.revertToPending(slotId) == 0) return@withTransaction false

        // 事实层：保留记录，仅改状态（append-only 的补偿，而不是抹除）
        recordDao.markRevertedBySlot(slotId)

        // 库存层：追加一条冲正流水（台账只增不改）
        if (record.status == RecordStatus.COMPLETED && medication.isStockTracked && record.doseTaken > 0) {
            appendLedger(
                medicationId = slot.medicationId,
                recordId = record.id,
                changeAmount = Dose(record.doseTaken),
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
            if (target.milli > 0 && current <= 0) {
                // 从零建档：一条流水即可，账面随之成立
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
     * 修改计划后的排班对齐核算。
     * 满足"修改计划后历史打卡数据不可丢失，只重投影未来未执行的 PENDING 槽位"。
     */
    suspend fun reconcileSchedule(
        medicationId: Long,
        fromDate: LocalDate = LocalDate.now(),
        toDate: LocalDate = LocalDate.now().plusDays(14),
        zoneId: ZoneId = ZoneId.systemDefault()
    ) = db.withTransaction {
        val policy = policyDao.getActivePolicyForMedication(medicationId) ?: return@withTransaction
        val times = policyDao.getTimesForPolicy(policy.id)

        val fromEpochMilli = fromDate.atStartOfDay(zoneId).toInstant().toEpochMilli()

        // 核心保护：只删除未来或当天仍处于 PENDING 状态的旧槽位！
        // 已完成(COMPLETED)或跳过(SKIPPED)的槽位绝对保留！
        slotDao.deleteFuturePendingSlots(medicationId, fromEpochMilli)

        val projectedSlots = SlotProjectionEngine.projectSlots(
            policy = policy,
            times = times,
            fromDate = fromDate,
            toDate = toDate,
            zoneId = zoneId
        )

        // 过滤去重：如果当天已有同时间点且已非待处理的槽位（如已服药或已跳过），避免重复生成
        val existingSlots = slotDao.getSlotsInRange(
            startDate = fromDate.format(SlotProjectionEngine.DATE_FORMATTER),
            endDate = toDate.format(SlotProjectionEngine.DATE_FORMATTER)
        ).filter { it.medicationId == medicationId }
        val existingSlotKeys = existingSlots.map { "${it.scheduledDate}_${it.scheduledTime}" }.toSet()

        val slotsToInsert = projectedSlots.filter {
            "${it.scheduledDate}_${it.scheduledTime}" !in existingSlotKeys
        }

        if (slotsToInsert.isNotEmpty()) {
            slotDao.insertAll(slotsToInsert)
        }
    }

    // ==================== 工具 ====================

    private fun fmtQty(v: Float): String = if (v % 1f == 0f) v.toInt().toString() else v.toString()
}
