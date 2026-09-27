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
import java.time.LocalDate
import java.time.ZoneId

/**
 * 核心用药追踪与调度核算服务 (DoseTrackingService)
 * 封装核心业务用例，保证：
 * 1. 打卡与库存扣减的原子性事务
 * 2. 误触撤销的冲正与台账守恒
 * 3. 计划修改时的排班重对齐 (历史事实不可变，未来排班平滑重投影)
 */
class DoseTrackingService(private val db: AppDatabase) {

    private val medDao = db.medicationDao()
    private val policyDao = db.schedulePolicyDao()
    private val slotDao = db.doseSlotDao()
    private val recordDao = db.doseRecordDao()
    private val inventoryDao = db.inventoryTransactionDao()

    /**
     * 1. 确认服药打卡
     * 原子事务：更新槽位状态 -> 创建服药事实记录 -> (若追踪库存) 写入不可变台账扣减流水并更新药品库存
     */
    suspend fun takeDose(
        slotId: Long,
        actualTs: Long = System.currentTimeMillis(),
        takenAmount: Float? = null,
        note: String? = null
    ): Boolean = db.withTransaction {
        val slot = slotDao.getSlotById(slotId) ?: return@withTransaction false
        val medication = medDao.getMedicationById(slot.medicationId) ?: return@withTransaction false

        val finalDose = takenAmount ?: slot.doseAmount

        // 1. 更新槽位状态为 COMPLETED
        slotDao.updateStatus(slotId = slotId, status = SlotStatus.COMPLETED, actualTs = actualTs)

        // 2. 插入服药历史事实记录
        val record = DoseRecordEntity(
            slotId = slotId,
            medicationId = slot.medicationId,
            actualTs = actualTs,
            doseTaken = finalDose,
            status = RecordStatus.COMPLETED,
            isRetrospective = false,
            note = note
        )
        val recordId = recordDao.insert(record)

        // 3. 库存联动处理 (若开启库存追踪)
        if (medication.isStockTracked) {
            val newStock = (medication.currentStock - finalDose).coerceAtLeast(0f)
            val tx = InventoryTransactionEntity(
                medicationId = slot.medicationId,
                recordId = recordId,
                changeAmount = -finalDose,
                balanceAfter = newStock,
                txType = TransactionType.TAKEN_DEDUCT,
                note = note ?: "按时服药打卡扣减"
            )
            inventoryDao.insert(tx)
            medDao.updateStock(slot.medicationId, newStock)
        }

        return@withTransaction true
    }

    /**
     * 2. 跳过服药
     * 原子事务：更新槽位状态为 SKIPPED -> 插入 SKIPPED 事实记录 -> 不影响库存
     */
    suspend fun skipDose(
        slotId: Long,
        reason: String? = null
    ): Boolean = db.withTransaction {
        val slot = slotDao.getSlotById(slotId) ?: return@withTransaction false

        slotDao.updateStatus(slotId = slotId, status = SlotStatus.SKIPPED, actualTs = System.currentTimeMillis())

        val record = DoseRecordEntity(
            slotId = slotId,
            medicationId = slot.medicationId,
            actualTs = System.currentTimeMillis(),
            doseTaken = 0f,
            status = RecordStatus.SKIPPED,
            note = reason ?: "主动跳过本次服药"
        )
        recordDao.insert(record)
        return@withTransaction true
    }

    /**
     * 3. 推迟提醒 (Snooze)
     */
    suspend fun snoozeDose(
        slotId: Long,
        snoozeMinutes: Int
    ): Boolean {
        val snoozeUntilTs = System.currentTimeMillis() + (snoozeMinutes * 60 * 1000L)
        slotDao.snoozeSlot(slotId, snoozeUntilTs)
        return true
    }

    /**
     * 4. 误触/点错撤销 (Undo / Revert)
     * 满足用户明确要求：点错了可以撤销，历史数据不丢，库存精确冲正
     */
    suspend fun undoDose(slotId: Long): Boolean = db.withTransaction {
        val slot = slotDao.getSlotById(slotId) ?: return@withTransaction false
        val previousRecord = recordDao.getRecordBySlotId(slotId) ?: return@withTransaction false
        val medication = medDao.getMedicationById(slot.medicationId) ?: return@withTransaction false

        // 若之前是 COMPLETED 并且扣减了库存，必须做台账冲正
        if (previousRecord.status == RecordStatus.COMPLETED && medication.isStockTracked && previousRecord.doseTaken > 0f) {
            val restoredStock = medication.currentStock + previousRecord.doseTaken
            val rollbackTx = InventoryTransactionEntity(
                medicationId = slot.medicationId,
                recordId = previousRecord.id,
                changeAmount = previousRecord.doseTaken,
                balanceAfter = restoredStock,
                txType = TransactionType.REVERT_ROLLBACK,
                note = "用户误触打卡撤销冲正"
            )
            inventoryDao.insert(rollbackTx)
            medDao.updateStock(slot.medicationId, restoredStock)
        }

        // 删除该 slot 对应的打卡记录
        recordDao.deleteBySlotId(slotId)

        // 将槽位重新恢复为待服药状态
        slotDao.updateStatus(slotId = slotId, status = SlotStatus.PENDING, actualTs = null)

        return@withTransaction true
    }

    /**
     * 5. 补充录入/临时按需服药 (PRN 或 事后补录)
     *
     * @param deductStock 是否联动扣减库存台账。
     *   补录历史服药时用户常需要"只记事实、不动库存"(例如从别处已经吃过的那一片)，
     *   开关关闭时仅写服药事实，不产生任何库存流水，台账守恒不受影响。
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
            doseTaken = doseAmount,
            status = if (isRetrospective) RecordStatus.RETROSPECTIVE else RecordStatus.COMPLETED,
            isRetrospective = isRetrospective,
            note = note
        )
        val recordId = recordDao.insert(record)

        if (deductStock && medication.isStockTracked) {
            val newStock = (medication.currentStock - doseAmount).coerceAtLeast(0f)
            val tx = InventoryTransactionEntity(
                medicationId = medicationId,
                recordId = recordId,
                changeAmount = -doseAmount,
                balanceAfter = newStock,
                txType = TransactionType.TAKEN_DEDUCT,
                note = note ?: if (isRetrospective) "事后补录服药扣减" else "按需/临时服药扣减"
            )
            inventoryDao.insert(tx)
            medDao.updateStock(medicationId, newStock)
        }

        return@withTransaction recordId
    }

    /**
     * 5b. 库存盘点校准
     *
     * 用户手中实物与系统账面不符时 (换了包装 / 之前漏记 / 初次建档修正)，
     * 通过写入一条 CALIBRATION_ADJUST 流水把账面拉回真实值，
     * 绝不直接 UPDATE current_stock，保证 `SUM(change_amount) == current_stock` 守恒。
     */
    suspend fun calibrateStock(
        medicationId: Long,
        actualStock: Float,
        note: String? = null
    ): Boolean = db.withTransaction {
        val medication = medDao.getMedicationById(medicationId) ?: return@withTransaction false
        val delta = actualStock - medication.currentStock
        if (kotlin.math.abs(delta) < 0.0001f) return@withTransaction false

        val tx = InventoryTransactionEntity(
            medicationId = medicationId,
            changeAmount = delta,
            balanceAfter = actualStock,
            txType = TransactionType.CALIBRATION_ADJUST,
            note = note ?: "库存盘点校准 (账面 ${trimFloat(medication.currentStock)} → 实物 ${trimFloat(actualStock)})"
        )
        inventoryDao.insert(tx)
        medDao.updateStock(medicationId, actualStock)
        return@withTransaction true
    }

    /**
     * 5c. 开启 / 关闭库存追踪
     * 关闭时不产生任何流水，仅切换开关位；重新开启时以当前账面作为基准写入一条建档流水。
     */
    suspend fun setStockTracking(
        medicationId: Long,
        enabled: Boolean,
        currentStock: Float? = null
    ): Boolean = db.withTransaction {
        val medication = medDao.getMedicationById(medicationId) ?: return@withTransaction false
        medDao.updateStockTracking(medicationId, enabled)

        if (enabled) {
            val target = currentStock ?: medication.currentStock
            if (target > 0f && medication.currentStock <= 0f) {
                // 从零建档：写一条建档流水并同步账面，守恒不变量成立
                inventoryDao.insert(
                    InventoryTransactionEntity(
                        medicationId = medicationId,
                        changeAmount = target,
                        balanceAfter = target,
                        txType = TransactionType.CALIBRATION_ADJUST,
                        note = "开启库存追踪建档"
                    )
                )
                medDao.updateStock(medicationId, target)
            } else if (target != medication.currentStock) {
                medDao.updateStock(medicationId, target)
            }
        }
        return@withTransaction true
    }

    private fun trimFloat(v: Float): String =
        if (v % 1f == 0f) v.toInt().toString() else v.toString()

    /**
     * 6. 药房补货采购入库
     */
    suspend fun refillStock(
        medicationId: Long,
        addedAmount: Float,
        note: String? = null
    ): Boolean = db.withTransaction {
        val medication = medDao.getMedicationById(medicationId) ?: return@withTransaction false
        val newStock = medication.currentStock + addedAmount

        val tx = InventoryTransactionEntity(
            medicationId = medicationId,
            changeAmount = addedAmount,
            balanceAfter = newStock,
            txType = TransactionType.REFILL,
            note = note ?: "采购入库补货"
        )
        inventoryDao.insert(tx)
        medDao.updateStock(medicationId, newStock)
        return@withTransaction true
    }

    /**
     * 7. 提醒计划变更时的排班对齐核算 (Reconcile Schedule)
     * 满足用户明确要求：修改计划后，历史打卡数据不可丢失，只重投影未来未执行的 PENDING 槽位
     *
     * @param medicationId 药品 ID
     * @param fromDate 重排起始日期 (通常为当天)
     * @param toDate 投影截止日期 (通常为未来 14 天)
     * @param zoneId 时区
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

        // 核心保护：只删除未来或当天仍处于 PENDING 状态的旧槽位！已完成(COMPLETED)或跳过(SKIPPED)的槽位绝对保留！
        slotDao.deleteFuturePendingSlots(medicationId, fromEpochMilli)

        // 根据最新策略投影计算新槽位
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
}
