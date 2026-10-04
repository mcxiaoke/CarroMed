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
import com.mcxiaoke.carromed.core.domain.model.MedicationCategory
import com.mcxiaoke.carromed.core.domain.model.MedicationForm
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 演示数据播种器 —— **仅存在于 debug 源集，release 包内完全没有这段代码**
 *
 * 冷启动路径 (`TodayViewModel.init`) **刻意不调用**它。
 * 首次启动必须是干净空库：否则用户会看到凭空出现的"环孢素 / 羟氯喹"等
 * 不属于自己的服药记录，直接污染依从率、库存与统计报表。
 *
 * 用途：开发与演示阶段需要一批真实排班数据时，在调试器里手动调用
 * ```kotlin
 * DevSampleDataSeeder.seedIfNeeded(AppDatabase.getInstance(context))
 * ```
 * 或从 adb 触发调试广播，避免手工录入 4 个药品 + 历史打卡。
 *
 * 药品名：统一使用规范通用名「**环孢素**」(Cyclosporine)。
 * 医疗 App 出现通用名错字会直接损伤专业可信度。
 */
object DevSampleDataSeeder {

    /** 空库时灌入与原型一致的示例数据；库非空则不动用户数据 */
    suspend fun seedIfNeeded(db: AppDatabase, today: LocalDate = LocalDate.now()) {
        seed(db, today)
    }

    private suspend fun seed(db: AppDatabase, today: LocalDate) {
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

        // 1. 环孢素 (处方药 · 免疫, 低库存告警)
        val med1Id = medDao.insert(
            MedicationEntity(
                name = "环孢素",
                alias = "新赛斯平",
                category = MedicationCategory.RX_IMMUNE.name,
                form = MedicationForm.SOFTGEL.name,
                unit = "片",
                colorHex = "#8B5CF6",
                defaultDose = 1000,
                description = "预防和治疗同种异体器官移植后的排斥反应。建议每天固定时间整粒温水吞服，切勿与葡萄柚同服。",
                precautions = listOf("整粒吞服禁嚼碎", "严禁与葡萄柚同食", "固定早晚时点", "定期复查血药浓度"),
                noticeShort = "温水吞服 · 禁食葡萄柚",
                minStockAlert = 10000,
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
                PolicyTimeEntity(policyId = 0, timeOfDay = "10:30", doseAmount = 1000, sortOrder = 0),
                PolicyTimeEntity(policyId = 0, timeOfDay = "22:00", doseAmount = 1000, sortOrder = 1)
            )
        )
        inventoryDao.insert(
            InventoryTransactionEntity(
                medicationId = med1Id,
                changeAmount = 30000,
                balanceAfter = 30000,
                txType = TransactionType.REFILL,
                note = "初始药房采购入库"
            )
        )
        inventoryDao.insert(
            InventoryTransactionEntity(
                medicationId = med1Id,
                changeAmount = -24000,
                balanceAfter = 6000,
                txType = TransactionType.TAKEN_DEDUCT,
                note = "历史服药累计扣减"
            )
        )

        // 2. 羟氯喹 (慢病处方)
        val med2Id = medDao.insert(
            MedicationEntity(
                name = "羟氯喹",
                alias = "赛妥",
                category = MedicationCategory.CHRONIC.name,
                form = MedicationForm.TABLET.name,
                unit = "片",
                colorHex = "#3B82F6",
                defaultDose = 1000,
                description = "免疫调节药物，随餐或温牛奶送服，定期检查眼底。",
                precautions = listOf("随餐温水送服", "定期检查眼底"),
                noticeShort = "随餐温水送服",
                minStockAlert = 7000,
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
                PolicyTimeEntity(policyId = 0, timeOfDay = "09:00", doseAmount = 1000, sortOrder = 0)
            )
        )
        inventoryDao.insert(
            InventoryTransactionEntity(
                medicationId = med2Id,
                changeAmount = 36000,
                balanceAfter = 36000,
                txType = TransactionType.CALIBRATION_ADJUST,
                note = "初始录入"
            )
        )

        // 3. 醋酸泼尼松 (激素类 - 隔日服用)
        val med3Id = medDao.insert(
            MedicationEntity(
                name = "醋酸泼尼松",
                alias = "强的松",
                category = MedicationCategory.HORMONE.name,
                form = MedicationForm.TABLET.name,
                unit = "片",
                colorHex = "#10B981",
                defaultDose = 1000,
                description = "糖皮质激素，建议早晨早餐后一次性服用，不可自行突然停药。",
                precautions = listOf("早晨早餐后服用", "切勿突然擅自停药"),
                noticeShort = "早餐后一次性服用",
                minStockAlert = 10000,
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
                PolicyTimeEntity(policyId = 0, timeOfDay = "08:00", doseAmount = 1000, sortOrder = 0)
            )
        )
        inventoryDao.insert(
            InventoryTransactionEntity(
                medicationId = med3Id,
                changeAmount = 121500,
                balanceAfter = 121500,
                txType = TransactionType.CALIBRATION_ADJUST,
                note = "初始录入"
            )
        )

        // 4. 钙和维生素D (营养保健)
        val med4Id = medDao.insert(
            MedicationEntity(
                name = "钙和维生素D",
                alias = "钙尔奇",
                category = MedicationCategory.SUPPLEMENT.name,
                form = MedicationForm.TABLET.name,
                unit = "片",
                colorHex = "#F59E0B",
                defaultDose = 1000,
                description = "促进骨骼钙质吸收，午餐后温水吞服。",
                precautions = listOf("午餐后温水送服"),
                noticeShort = "午餐后温水送服",
                minStockAlert = 15000,
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
                PolicyTimeEntity(policyId = 0, timeOfDay = "13:30", doseAmount = 1000, sortOrder = 0)
            )
        )
        inventoryDao.insert(
            InventoryTransactionEntity(
                medicationId = med4Id,
                changeAmount = 50000,
                balanceAfter = 50000,
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
                doseAmount = 1000,
                status = SlotStatus.COMPLETED,
                actualTakenTs = slot3Ts + 300000L
            )
        )
        recordDao.insert(
            DoseRecordEntity(
                slotId = slot3Id,
                medicationId = med3Id,
                actualTs = slot3Ts + 300000L,
                doseTaken = 1000,
                status = RecordStatus.COMPLETED,
                note = "早饭后准时服用"
            )
        )
        // ⚠️ 打卡事实必须配一条台账流水（M8-6）。
        // 少了它，演示数据就制造了一个**假的矛盾**：今日页说"已服 1 片"，
        // 而库存页的余额仍是建档时的 121.5 片 —— 演示时看起来像"打完卡库存不减"，
        // 让人以为扣减功能坏了。真实路径 `DoseTrackingService.takeDose` 是
        // 一次事务里同时写事实行与流水的，演示数据也该守同一条不变量 I2。
        // 121500（建档） - 1000（本次） = 120500
        inventoryDao.insert(
            InventoryTransactionEntity(
                medicationId = med3Id,
                changeAmount = -1000,
                balanceAfter = 120500,
                txType = TransactionType.TAKEN_DEDUCT,
                note = "今日 08:00 打卡扣减"
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
                doseAmount = 1000,
                status = SlotStatus.COMPLETED,
                actualTakenTs = slot2Ts + 60000L
            )
        )
        recordDao.insert(
            DoseRecordEntity(
                slotId = slot2Id,
                medicationId = med2Id,
                actualTs = slot2Ts + 60000L,
                doseTaken = 1000,
                status = RecordStatus.COMPLETED,
                note = "随餐温水送服"
            )
        )
        // ⚠️ 同上：打卡事实必须配台账流水（M8-6）。36000（建档） - 1000（本次） = 35000
        inventoryDao.insert(
            InventoryTransactionEntity(
                medicationId = med2Id,
                changeAmount = -1000,
                balanceAfter = 35000,
                txType = TransactionType.TAKEN_DEDUCT,
                note = "今日 09:00 打卡扣减"
            )
        )

        // c. 环孢素 10:30 -> 待服药 (PENDING)
        val slot1aTs = today.atTime(LocalTime.of(10, 30)).atZone(zoneId).toInstant().toEpochMilli()
        slotDao.insert(
            DoseSlotEntity(
                medicationId = med1Id,
                policyId = policy1Id,
                scheduledDate = todayStr,
                scheduledTime = "10:30",
                scheduledTs = slot1aTs,
                doseAmount = 1000,
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
                doseAmount = 1000,
                status = SlotStatus.PENDING
            )
        )

        // e. 环孢素 22:00 -> 待服药 (PENDING)
        val slot1bTs = today.atTime(LocalTime.of(22, 0)).atZone(zoneId).toInstant().toEpochMilli()
        slotDao.insert(
            DoseSlotEntity(
                medicationId = med1Id,
                policyId = policy1Id,
                scheduledDate = todayStr,
                scheduledTime = "22:00",
                scheduledTs = slot1bTs,
                doseAmount = 1000,
                status = SlotStatus.PENDING
            )
        )

        // 提醒运行态默认行。
        //
        // 正常建药路径由 `MedicationAdminService.saveProfile` 调 `ensureDefaults` 建行；
        // 本播种器是**直插实体**、绕过那条路径的。若不同步补上，演示数据里的药
        // 会缺 `reminder_settings` 行 —— 读路径靠 LEFT JOIN + 默认值仍能正常工作，
        // 但"新建药品必有提醒设置行"这条不变量在演示数据上就不成立了，
        // 而演示数据正是走查与人工验收的依据。
        listOf(med1Id, med2Id, med3Id, med4Id).forEach { id ->
            db.reminderSettingsDao().ensureDefaults(id)
        }
    }
}
