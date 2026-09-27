package com.mcxiaoke.carromed.core.data

import com.mcxiaoke.carromed.core.data.entity.DoseRecordEntity
import com.mcxiaoke.carromed.core.data.entity.DoseSlotEntity
import com.mcxiaoke.carromed.core.data.entity.InventoryTransactionEntity
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.entity.PolicyTimeEntity
import com.mcxiaoke.carromed.core.data.entity.SchedulePolicyEntity
import com.mcxiaoke.carromed.core.data.model.PolicyType
import com.mcxiaoke.carromed.core.data.model.RecordStatus
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import com.mcxiaoke.carromed.core.data.model.TransactionType
import com.mcxiaoke.carromed.core.domain.engine.SlotProjectionEngine
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 演示数据初始化器 (冷启动检测并装载)
 * 1:1 对齐原型截图中的四种常用药与当日打卡事实
 */
object SampleDataSeeder {

    suspend fun seedIfNeeded(db: AppDatabase, today: LocalDate = LocalDate.now()) {
        val medDao = db.medicationDao()
        if (medDao.getAllMedications().isNotEmpty()) {
            return
        }

        val policyDao = db.schedulePolicyDao()
        val slotDao = db.doseSlotDao()
        val recordDao = db.doseRecordDao()
        val inventoryDao = db.inventoryTransactionDao()

        val zoneId = ZoneId.systemDefault()
        val todayStr = today.format(SlotProjectionEngine.DATE_FORMATTER)

        // 1. 环抱素 (处方药 · 免疫, 低库存告警)
        val med1Id = medDao.insert(
            MedicationEntity(
                name = "环抱素",
                alias = "新赛斯平",
                category = "处方药 · 免疫",
                form = "软胶囊",
                unit = "片",
                colorHex = "#8B5CF6",
                iconName = "pill",
                defaultDose = 1.0f,
                description = "预防和治疗同种异体器官移植后的排斥反应。建议每天固定时间整粒温水吞服，切勿与葡萄柚同服。",
                precautions = listOf("整粒吞服禁嚼碎", "严禁与葡萄柚同食", "固定早晚时点", "定期复查血药浓度"),
                noticeShort = "温水吞服 · 禁食葡萄柚",
                currentStock = 6.0f,
                minStockAlert = 10.0f,
                isStockTracked = true
            )
        )
        val policy1Id = policyDao.savePolicyWithTimes(
            SchedulePolicyEntity(
                medicationId = med1Id,
                policyType = PolicyType.DAILY,
                startDate = today.minusDays(10).format(SlotProjectionEngine.DATE_FORMATTER)
            ),
            listOf(
                PolicyTimeEntity(policyId = 0, timeOfDay = "10:30", doseAmount = 1.0f, sortOrder = 0),
                PolicyTimeEntity(policyId = 0, timeOfDay = "22:00", doseAmount = 1.0f, sortOrder = 1)
            )
        )
        inventoryDao.insert(
            InventoryTransactionEntity(
                medicationId = med1Id,
                changeAmount = 30.0f,
                balanceAfter = 30.0f,
                txType = TransactionType.REFILL,
                note = "初始药房采购入库"
            )
        )
        inventoryDao.insert(
            InventoryTransactionEntity(
                medicationId = med1Id,
                changeAmount = -24.0f,
                balanceAfter = 6.0f,
                txType = TransactionType.TAKEN_DEDUCT,
                note = "历史服药累计扣减"
            )
        )

        // 2. 羟氯喹 (慢病处方)
        val med2Id = medDao.insert(
            MedicationEntity(
                name = "羟氯喹",
                alias = "赛妥",
                category = "慢病处方",
                form = "片剂",
                unit = "片",
                colorHex = "#3B82F6",
                iconName = "pill",
                defaultDose = 1.0f,
                description = "免疫调节药物，随餐或温牛奶送服，定期检查眼底。",
                precautions = listOf("随餐温水送服", "定期检查眼底"),
                noticeShort = "随餐温水送服",
                currentStock = 36.0f,
                minStockAlert = 7.0f,
                isStockTracked = true
            )
        )
        val policy2Id = policyDao.savePolicyWithTimes(
            SchedulePolicyEntity(
                medicationId = med2Id,
                policyType = PolicyType.DAILY,
                startDate = today.minusDays(10).format(SlotProjectionEngine.DATE_FORMATTER)
            ),
            listOf(
                PolicyTimeEntity(policyId = 0, timeOfDay = "09:00", doseAmount = 1.0f, sortOrder = 0)
            )
        )
        inventoryDao.insert(
            InventoryTransactionEntity(
                medicationId = med2Id,
                changeAmount = 36.0f,
                balanceAfter = 36.0f,
                txType = TransactionType.CALIBRATION_ADJUST,
                note = "初始录入"
            )
        )

        // 3. 醋酸泼尼松 (激素类 - 隔日服用)
        val med3Id = medDao.insert(
            MedicationEntity(
                name = "醋酸泼尼松",
                alias = "强的松",
                category = "激素类",
                form = "片剂",
                unit = "片",
                colorHex = "#10B981",
                iconName = "pill",
                defaultDose = 1.0f,
                description = "糖皮质激素，建议早晨早餐后一次性服用，不可自行突然停药。",
                precautions = listOf("早晨早餐后服用", "切勿突然擅自停药"),
                noticeShort = "早餐后一次性服用",
                currentStock = 121.5f,
                minStockAlert = 10.0f,
                isStockTracked = true
            )
        )
        val policy3Id = policyDao.savePolicyWithTimes(
            SchedulePolicyEntity(
                medicationId = med3Id,
                policyType = PolicyType.INTERVAL,
                intervalDays = 2,
                startDate = today.minusDays(10).format(SlotProjectionEngine.DATE_FORMATTER)
            ),
            listOf(
                PolicyTimeEntity(policyId = 0, timeOfDay = "08:00", doseAmount = 1.0f, sortOrder = 0)
            )
        )
        inventoryDao.insert(
            InventoryTransactionEntity(
                medicationId = med3Id,
                changeAmount = 121.5f,
                balanceAfter = 121.5f,
                txType = TransactionType.CALIBRATION_ADJUST,
                note = "初始录入"
            )
        )

        // 4. 钙和维生素D (营养保健)
        val med4Id = medDao.insert(
            MedicationEntity(
                name = "钙和维生素D",
                alias = "钙尔奇",
                category = "营养保健",
                form = "片剂",
                unit = "片",
                colorHex = "#F59E0B",
                iconName = "pill",
                defaultDose = 1.0f,
                description = "促进骨骼钙质吸收，午餐后温水吞服。",
                precautions = listOf("午餐后温水送服"),
                noticeShort = "午餐后温水送服",
                currentStock = 50.0f,
                minStockAlert = 15.0f,
                isStockTracked = true
            )
        )
        val policy4Id = policyDao.savePolicyWithTimes(
            SchedulePolicyEntity(
                medicationId = med4Id,
                policyType = PolicyType.DAILY,
                startDate = today.minusDays(10).format(SlotProjectionEngine.DATE_FORMATTER)
            ),
            listOf(
                PolicyTimeEntity(policyId = 0, timeOfDay = "13:30", doseAmount = 1.0f, sortOrder = 0)
            )
        )
        inventoryDao.insert(
            InventoryTransactionEntity(
                medicationId = med4Id,
                changeAmount = 50.0f,
                balanceAfter = 50.0f,
                txType = TransactionType.CALIBRATION_ADJUST,
                note = "初始录入"
            )
        )

        // 5. 播种今日槽位与打卡事实 (完全对照截图 1)
        // a. 醋酸泼尼松 08:00 -> 已打卡完成
        val slot3Ts = today.atTime(LocalTime.of(8, 0)).atZone(zoneId).toInstant().toEpochMilli()
        val slot3Id = slotDao.insert(
            DoseSlotEntity(
                medicationId = med3Id,
                policyId = policy3Id,
                scheduledDate = todayStr,
                scheduledTime = "08:00",
                scheduledTs = slot3Ts,
                doseAmount = 1.0f,
                status = SlotStatus.COMPLETED,
                actualTakenTs = slot3Ts + 300000L
            )
        )
        recordDao.insert(
            DoseRecordEntity(
                slotId = slot3Id,
                medicationId = med3Id,
                actualTs = slot3Ts + 300000L,
                doseTaken = 1.0f,
                status = RecordStatus.COMPLETED,
                note = "早饭后准时服用"
            )
        )

        // b. 羟氯喹 09:00 -> 已打卡完成
        val slot2Ts = today.atTime(LocalTime.of(9, 0)).atZone(zoneId).toInstant().toEpochMilli()
        val slot2Id = slotDao.insert(
            DoseSlotEntity(
                medicationId = med2Id,
                policyId = policy2Id,
                scheduledDate = todayStr,
                scheduledTime = "09:00",
                scheduledTs = slot2Ts,
                doseAmount = 1.0f,
                status = SlotStatus.COMPLETED,
                actualTakenTs = slot2Ts + 60000L
            )
        )
        recordDao.insert(
            DoseRecordEntity(
                slotId = slot2Id,
                medicationId = med2Id,
                actualTs = slot2Ts + 60000L,
                doseTaken = 1.0f,
                status = RecordStatus.COMPLETED,
                note = "随餐温水送服"
            )
        )

        // c. 环抱素 10:30 -> 待服药 (PENDING)
        val slot1aTs = today.atTime(LocalTime.of(10, 30)).atZone(zoneId).toInstant().toEpochMilli()
        slotDao.insert(
            DoseSlotEntity(
                medicationId = med1Id,
                policyId = policy1Id,
                scheduledDate = todayStr,
                scheduledTime = "10:30",
                scheduledTs = slot1aTs,
                doseAmount = 1.0f,
                status = SlotStatus.PENDING
            )
        )

        // d. 钙和维生素D 13:30 -> 待服药 (PENDING)
        val slot4Ts = today.atTime(LocalTime.of(13, 30)).atZone(zoneId).toInstant().toEpochMilli()
        slotDao.insert(
            DoseSlotEntity(
                medicationId = med4Id,
                policyId = policy4Id,
                scheduledDate = todayStr,
                scheduledTime = "13:30",
                scheduledTs = slot4Ts,
                doseAmount = 1.0f,
                status = SlotStatus.PENDING
            )
        )

        // e. 环抱素 22:00 -> 待服药 (PENDING)
        val slot1bTs = today.atTime(LocalTime.of(22, 0)).atZone(zoneId).toInstant().toEpochMilli()
        slotDao.insert(
            DoseSlotEntity(
                medicationId = med1Id,
                policyId = policy1Id,
                scheduledDate = todayStr,
                scheduledTime = "22:00",
                scheduledTs = slot1bTs,
                doseAmount = 1.0f,
                status = SlotStatus.PENDING
            )
        )
    }
}
