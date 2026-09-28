package com.mcxiaoke.carromed.core.domain.service

import androidx.room.withTransaction
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.InventoryTransactionEntity
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.entity.PolicyTimeEntity
import com.mcxiaoke.carromed.core.data.entity.SchedulePolicyEntity
import com.mcxiaoke.carromed.core.data.model.PolicyType
import com.mcxiaoke.carromed.core.data.model.TransactionType
import com.mcxiaoke.carromed.core.domain.model.Dose
import java.time.LocalDate

/**
 * 药品档案与提醒计划的写入服务 (MedicationAdminService)
 *
 * 存在的原因：此前新增/编辑全部塞在 `AddEditMedicationViewModel.save()` 里，
 * 用 `insert(REPLACE)` 整行覆盖药品、用全新 `SchedulePolicyEntity` 覆盖策略，
 * 导致：
 * 1. 编辑一次药品就清空 alias / precautions / noticeShort / isPaused / isArchived / createdAt；
 * 2. 编辑一次计划就把 `startDate` 重置为今天、`endDate` 疗程丢失、`version` 归 1。
 *
 * 本服务把"新增"与"编辑"两条路径收敛为**两个语义清晰的写入口**：
 * - [saveProfile]      —— 只改药品档案，绝不碰状态位与库存账面
 * - [saveReminderPolicy] —— 换版策略时保留疗程边界与起始日
 */
class MedicationAdminService(private val db: AppDatabase) {

    private val medDao = db.medicationDao()
    private val policyDao = db.schedulePolicyDao()
    private val inventoryDao = db.inventoryTransactionDao()

    /** 药品档案草稿 (对应"药品信息"这一独立维度) */
    data class ProfileDraft(
        val medId: Long = 0L,
        val name: String,
        val alias: String? = null,
        val category: String = "常备药",
        val form: String = "片剂",
        val unit: String = "片",
        val colorHex: String = "#2563EB",
        val defaultDose: Float = 1.0f,  // 展示值，落库时转毫单位
        val description: String = "",
        val precautions: List<String> = emptyList(),
        val noticeShort: String = "",
        val expiryDate: String = "",
        val isCriticalReminder: Boolean = false,
        val snoozeMinutes: Int = 0,
        val advanceMinutes: Int = 0,
        val minStockAlert: Float = 10f
    )

    /** 单个提醒时点草稿 (对应"用药时间表"里的提醒详情) */
    data class TimeDraft(
        val time: String,
        val dose: Float,
        val label: String = "服药时段"
    )

    /** 提醒计划草稿 (对应"提醒设置"这一独立维度) */
    data class PolicyDraft(
        val policyType: PolicyType = PolicyType.DAILY,
        val intervalDays: Int = 2,
        val daysOfWeek: List<Int> = emptyList(),
        val cycleOnDays: Int = 21,
        val cycleOffDays: Int = 7,
        val startDate: String = LocalDate.now().toString(),
        val endDate: String? = null,
        val times: List<TimeDraft> = emptyList()
    )

    /**
     * 保存药品档案。
     *
     * @return 药品 ID (新增时为新生成的自增 ID)
     */
    suspend fun saveProfile(draft: ProfileDraft): Long = db.withTransaction {
        val name = draft.name.trim()
        require(name.isNotEmpty()) { "药品名称不能为空" }

        if (draft.medId > 0L) {
            // 编辑：只写档案字段。isPaused / isArchived / current_stock / is_stock_tracked
            // 与 created_at 一律保持原值，避免"改个名字把状态和库存改了"。
            //
            // alias 采用 patch 语义：**null = 本次不修改，沿用原值**（表单未加载 / 部分提交），
            // 传空串或纯空白 = 主动清空。否则一次部分提交就会把别名静默抹掉。
            val existingAlias = medDao.getMedicationById(draft.medId)?.alias
            val resolvedAlias = draft.alias?.trim()?.ifBlank { "" } ?: existingAlias

            medDao.updateProfile(
                id = draft.medId,
                name = name,
                alias = resolvedAlias,
                category = draft.category,
                form = draft.form,
                unit = draft.unit,
                colorHex = draft.colorHex,
                defaultDose = Dose.of(draft.defaultDose).milli,
                description = draft.description.trim(),
                precautions = draft.precautions.map { it.trim() }.filter { it.isNotEmpty() },
                noticeShort = draft.noticeShort.trim(),
                expiryDate = draft.expiryDate.trim(),
                isCriticalReminder = draft.isCriticalReminder,
                snoozeMinutes = draft.snoozeMinutes,
                advanceMinutes = draft.advanceMinutes,
                minStockAlert = Dose.of(draft.minStockAlert.coerceAtLeast(0f)).milli,
                updatedAt = System.currentTimeMillis()
            )
            return@withTransaction draft.medId
        }

        // 新增
        medDao.insert(
            MedicationEntity(
                name = name,
                alias = draft.alias?.trim()?.ifBlank { null },
                category = draft.category,
                form = draft.form,
                unit = draft.unit,
                colorHex = draft.colorHex,
                defaultDose = Dose.of(draft.defaultDose).milli,
                description = draft.description.trim(),
                precautions = draft.precautions.map { it.trim() }.filter { it.isNotEmpty() },
                noticeShort = draft.noticeShort.trim(),
                expiryDate = draft.expiryDate.trim(),
                isCriticalReminder = draft.isCriticalReminder,
                snoozeMinutes = draft.snoozeMinutes,
                advanceMinutes = draft.advanceMinutes,
                minStockAlert = Dose.of(draft.minStockAlert.coerceAtLeast(0f)).milli
            )
        )
    }

    /**
     * 保存 / 换版提醒计划。
     *
     * 与旧实现的差异：
     * - `startDate` 缺省时**沿用历史策略的起始日**（不再被重置为今天），
     *   否则 INTERVAL(隔日) 的相位会随每次编辑漂移；
     * - `endDate` 缺省时沿用历史疗程边界（用户没主动改疗程就不该丢）；
     * - `version` 递增，供审计与"改计划"历史提示使用。
     */
    suspend fun saveReminderPolicy(medicationId: Long, draft: PolicyDraft): Long =
        db.withTransaction {
            val previous = policyDao.getActivePolicyForMedication(medicationId)

            val policy = SchedulePolicyEntity(
                medicationId = medicationId,
                policyType = draft.policyType,
                intervalDays = draft.intervalDays.coerceIn(1, 30),
                daysOfWeek = draft.daysOfWeek.distinct().sorted(),
                cycleOnDays = draft.cycleOnDays.coerceAtLeast(1),
                cycleOffDays = draft.cycleOffDays.coerceAtLeast(0),
                startDate = draft.startDate.ifBlank { previous?.startDate ?: LocalDate.now().toString() },
                endDate = draft.endDate?.ifBlank { null } ?: previous?.endDate,
                isActive = true,
                version = (previous?.version ?: 0) + 1
            )

            val times = draft.times
                .sortedBy { it.time }
                .mapIndexed { index, t ->
                    PolicyTimeEntity(
                        policyId = 0,
                        timeOfDay = t.time,
                        doseAmount = Dose.of(t.dose).milli,
                        label = t.label.ifBlank { "服药时段" },
                        sortOrder = index
                    )
                }

            policyDao.savePolicyWithTimes(policy, times)
        }

    /**
     * 首次录入库存时写入一条建档流水，保证
     * `SUM(inventory_transactions.change_amount) == medications.current_stock` 守恒。
     * 已有流水的药品不会被重复记账。
     */
    suspend fun ensureInitialStockLedger(medicationId: Long, stock: Float) = db.withTransaction {
        if (stock <= 0f) return@withTransaction
        if (inventoryDao.getSumOfChanges(medicationId) != null) return@withTransaction
        inventoryDao.insert(
            InventoryTransactionEntity(
                medicationId = medicationId,
                changeAmount = Dose.of(stock).milli,
                balanceAfter = Dose.of(stock).milli,
                txType = TransactionType.CALIBRATION_ADJUST,
                note = "初始录入建档"
            )
        )
    }

    /**
     * 首次录入库存时同步打开库存追踪开关。
     * 走 `updateStockTracking` 而不是整行覆盖，保证其他字段安全。
     */
    suspend fun enableStockTrackingIfNeeded(medicationId: Long, stock: Float) = db.withTransaction {
        val med = medDao.getMedicationById(medicationId) ?: return@withTransaction
        if (!med.isStockTracked && stock > 0f) {
            medDao.updateStockTracking(medicationId, true)
        }
    }
}
