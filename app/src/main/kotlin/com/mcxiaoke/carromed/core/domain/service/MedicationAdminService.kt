package com.mcxiaoke.carromed.core.domain.service

import androidx.room.withTransaction
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.InventoryTransactionEntity
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.entity.PolicyTimeEntity
import com.mcxiaoke.carromed.core.data.entity.SchedulePolicyEntity
import com.mcxiaoke.carromed.core.data.model.PolicyType
import com.mcxiaoke.carromed.core.data.model.TransactionType
import com.mcxiaoke.carromed.core.domain.AppLog
import com.mcxiaoke.carromed.core.domain.model.Dose
import com.mcxiaoke.carromed.core.domain.model.MedicationCategory
import com.mcxiaoke.carromed.core.domain.model.MedicationForm
import com.mcxiaoke.carromed.core.domain.model.SlotLabel
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

    private companion object {
        const val TAG = "MedAdminService"
    }

    private val medDao = db.medicationDao()
    private val policyDao = db.schedulePolicyDao()
    private val inventoryDao = db.inventoryTransactionDao()
    private val reminderSettingsDao = db.reminderSettingsDao()

    /**
     * 药品档案草稿 (对应"药品信息"这一独立维度)。
     *
     * ⚠️ 这里**刻意不含** `isCriticalReminder` / `snoozeMinutes` / `advanceMinutes` ——
     * 它们归"提醒设置"维度，由 [saveReminderBehavior] 独占写入。
     *
     * 草稿里出现不属于本屏幕的字段，正是 P0-5「漏传型」的温床：调用方会把
     * 进页面时的快照值原样传回，用户的真实配置就被覆盖回去。
     * 见 `docs/REMINDER-DOMAIN-REDESIGN.md` §1.3。
     */
    data class ProfileDraft(
        val medId: Long = 0L,
        val name: String,
        val alias: String? = null,
        val category: String = MedicationCategory.COMMON.name,
        val form: String = MedicationForm.TABLET.name,
        val unit: String = "片",
        val colorHex: String = "#2563EB",
        val defaultDose: Float = 1.0f,  // 展示值，落库时转毫单位
        val description: String = "",
        val precautions: List<String> = emptyList(),
        val noticeShort: String = "",
        val expiryDate: String = "",
        val minStockAlert: Float = 0f
    )

    /** 提醒行为草稿 (对应"提醒设置"这一独立维度，由提醒设置页独占) */
    data class ReminderBehaviorDraft(
        val medId: Long,
        val isCriticalReminder: Boolean = false,
        val snoozeMinutes: Int = 0,
        val advanceMinutes: Int = 0
    )

    /** 单个提醒时点草稿 (对应"用药时间表"里的提醒详情) */
    data class TimeDraft(
        val time: String,
        val dose: Float,
        val label: String = SlotLabel.GENERIC.name
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
        val times: List<TimeDraft> = emptyList(),

        /**
         * **清空疗程结束日**的显式意图。
         *
         * ## 为什么 `endDate = null` 不足以表达"清空"
         *
         * `saveReminderPolicy` 一直用 `draft.endDate ?: previous?.endDate` 来实现
         * "用户没改就沿用历史值"。但 `null` 同时表达了两种意图：
         *
         * | 用户操作 | 草稿 | 期望 | `?:` 旧行为 |
         * | :--- | :--- | :--- | :--- |
         * | 没碰结束日 | `endDate = null` | 沿用原值 | ✅ 沿用 |
         * | **关掉开关，要清空** | `endDate = null` | 清空 | ❌ **沿用原值** |
         *
         * 而提醒设置页**确实**提供了清空入口（`Switch` + 日期框的 clear 图标），
         * 关掉 Switch 后 `ReminderSettingsViewModel` 传的就是 `endDate = null`。
         *
         * 后果不只是"开关看着关了但没生效"这么轻：
         * 抗生素设了「疗程至 10-05」，疗程结束后医生说继续吃，用户关掉开关保存 ——
         * `endDate` 仍写回 `2026-10-05`，**10-06 起所有提醒静默消失**，
         * 而用户以为自己还在正常吃药。这正是"提醒不能漏"的反面。
         *
         * 加一个显式标志位，让"清空"成为**必须被表达**的意图而不是巧合。
         */
        val clearEndDate: Boolean = false
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
                minStockAlert = Dose.of(draft.minStockAlert.coerceAtLeast(0f)).milli,
                updatedAt = System.currentTimeMillis()
            )
            // G7：档案编辑是 I9 不变量（不碰 reminder 四列与状态位）的关键路径，
            // 出问题时这条线是第一现场。alias 用 patch 语义，日志记实际生效值的存在性
            AppLog.i(TAG, "saveProfile update med=${draft.medId} name=$name aliasPresent=${resolvedAlias != null}")
            return@withTransaction draft.medId
        }

        // 新增
        val newId = medDao.insert(
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
                minStockAlert = Dose.of(draft.minStockAlert.coerceAtLeast(0f)).milli
            )
        )
        // 必须建默认提醒设置行：否则提醒设置页第一次保存时 UPDATE 命中 0 行，
        // 用户改了设置、点保存、回到详情页发现什么都没变，且**没有任何报错**。
        reminderSettingsDao.ensureDefaults(newId)
        AppLog.i(TAG, "saveProfile insert med=$newId name=$name")
        newId
    }

    /**
     * 保存「提醒行为」维度 (提醒设置页专用)。
     *
     * 与 [saveProfile] 严格分离：这一条**只**写 `reminder_settings` 的三列，
     * 绝不触碰药品档案、库存、暂停状态。P0-5 至此没有第二条写路径。
     */
    suspend fun saveReminderBehavior(draft: ReminderBehaviorDraft) = db.withTransaction {
        reminderSettingsDao.ensureDefaults(draft.medId)
        check(reminderSettingsDao.updateBehavior(
            medicationId = draft.medId,
            isCriticalReminder = draft.isCriticalReminder,
            snoozeMinutes = draft.snoozeMinutes.coerceAtLeast(0),
            advanceMinutes = draft.advanceMinutes.coerceAtLeast(0)
        ) == 1) { "保存提醒行为失败：reminder_settings 无 medId=${draft.medId} 的行" }
        AppLog.i(
            TAG,
            "saveReminderBehavior med=${draft.medId} critical=${draft.isCriticalReminder}" +
                " snooze=${draft.snoozeMinutes} advance=${draft.advanceMinutes}"
        )
    }

    /**
     * 暂停 / 恢复提醒 (详情页的暂停开关)。
     *
     * @param until `null` = 恢复；`""` = 无限期暂停；`"2026-10-15"` = 暂停至该日含。
     *             具体的"截至某天是否算暂停"由 `ReminderSettingsEntity.isPausedOn` 判定，
     *             本方法只负责**存意图**、不替调用方做日期比较 —— 两处实现必然漂移。
     */
    suspend fun setPausedUntil(medId: Long, until: String?) = db.withTransaction {
        reminderSettingsDao.ensureDefaults(medId)
        check(reminderSettingsDao.setPausedUntil(medId, until) == 1) {
            "设置暂停失败：reminder_settings 无 medId=$medId 的行"
        }
        AppLog.i(TAG, "setPausedUntil med=$medId until=${until ?: "<null=resume>"}")
    }

    /** 立即恢复（等价于 [setPausedUntil] 传 null，但语义更清晰，UI 用这个） */
    suspend fun resume(medId: Long) = db.withTransaction {
        reminderSettingsDao.ensureDefaults(medId)
        check(reminderSettingsDao.resume(medId) == 1) { "恢复提醒失败：remId=$medId 无设置行" }
        AppLog.i(TAG, "resume med=$medId")
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
            // P1（zcg 审查）：同一计划内两个相同时点，投影后会产出两条同键槽位，
            // 被 `DoseSlotDao.insertAll(IGNORE)` 撞 UNIQUE 索引后**静默吞掉一条**——
            // 那条时点的剂量从此不存在于任何提醒、打卡与台账，且无任何报错。
            // 在入口拒绝并让事务整体回滚；两个表单 VM 各有前置校验负责给出台词。
            val timeKeys = draft.times.map { it.time.trim() }
            require(timeKeys.size == timeKeys.distinct().size) {
                "同一计划内存在重复的服药时点：${timeKeys.distinct()}"
            }

            // ⭐ 时点必须是合法 `HH:mm`（M7-9，第三层防线，与剂量校验并列）。
            //
            // 坏串不会在这里报错，而是**一路活到投影层**：`SlotProjectionEngine`
            // 对解析失败的时点回退到 08:00。于是备份里一条被截断的
            // `time_of_day`（"08:" / "8点" / 空串）会让这味药每天 08:00 响，
            // 而用户从没设过这个时间，且**全程无任何提示**。
            //
            // 与上面两处同理：前两层（表单校验）只保护本 App 的两个入口，
            // 这一层保护**所有**调用方（备份导入、未来 Widget / 手表 / 快捷指令）。
            // 判据直接用 `SlotProjectionEngine.TIME_FORMATTER`，
            // 保证与投影层的"什么算坏串"永远一致，不留两套定义。
            val badTime = draft.times.firstOrNull {
                runCatching {
                    java.time.LocalTime.parse(
                        it.time.trim(),
                        com.mcxiaoke.carromed.core.domain.engine.SlotProjectionEngine.TIME_FORMATTER
                    )
                }.isFailure
            }
            require(badTime == null) {
                "服药时点「${badTime?.time}」不是有效时间，请按 HH:mm 格式（例如 08:00）填写"
            }

            // ⭐ 剂量必须严格为正（M2-1，第三层防线）。
            //
            // 0 剂量是一条完整的数据损坏路径，且**全程静默**：
            // 闹钟照响、通知照弹、打卡照记一条 COMPLETED 事实，
            // 而 `takeDose` 的扣减量是 `finalDose` = 0 ⇒ **库存永远不扣**。
            // 用户看到的是"每天都在打卡、库存却一直不变"，只能靠人工比对才发现。
            //
            // 前两层（UI 字符过滤 + 提交前解析）都只保护"本 App 的这两个表单"。
            // 这一层保护的是**所有**调用方：备份导入、未来 Widget / 手表 / 快捷指令入口。
            // 负数同样拒绝 —— 负剂量打卡 = 给库存**加**药，比 0 更危险。
            val badDose = draft.times.firstOrNull { it.dose <= 0f }
            require(badDose == null) {
                "服药时点 ${badDose?.time} 的剂量必须大于 0（当前 ${badDose?.dose}）"
            }

            val previous = policyDao.getActivePolicyForMedication(medicationId)

            // 星期几的取值域必须在 1..7（DB C-20）：0 / 8 这类值从 UI 打不进来，
            // 但备份导入、未来的外部入口可以 —— 落库后投影引擎按 `dayOfWeek - 1`
            // 索引星期名，越界值要么静默排错天、要么读出错位的文案。
            // 与时点/剂量的 require 同一性质：服务层是所有入口的公共下游。
            require(draft.daysOfWeek.all { it in 1..7 }) {
                "daysOfWeek 取值必须在 1..7（周一..周日），当前 ${draft.daysOfWeek}"
            }

            val policy = SchedulePolicyEntity(
                medicationId = medicationId,
                policyType = draft.policyType,
                intervalDays = draft.intervalDays.coerceIn(1, 30),
                daysOfWeek = draft.daysOfWeek.distinct().sorted(),
                cycleOnDays = draft.cycleOnDays.coerceAtLeast(1),
                cycleOffDays = draft.cycleOffDays.coerceAtLeast(0),
                startDate = draft.startDate.ifBlank { previous?.startDate ?: LocalDate.now().toString() },
                // 三态：显式清空 > 给了新值 > 没改（沿用历史）。
                // ⚠️ 顺序不能反：先判 `clearEndDate`，否则关掉 Switch 传来的 null
                // 会被当成"没改"而沿用旧值，提醒在原定结束日静默停止。
                endDate = when {
                    draft.clearEndDate -> null
                    draft.endDate.isNullOrBlank() -> previous?.endDate
                    else -> draft.endDate
                },
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
                        label = t.label.ifBlank { SlotLabel.GENERIC.name },
                        sortOrder = index
                    )
                }

            val newPolicyId = policyDao.savePolicyWithTimes(policy, times)
            AppLog.i(
                TAG,
                "saveReminderPolicy med=$medicationId policyId=$newPolicyId version=${policy.version}" +
                    " type=${policy.policyType} times=${times.size} start=${policy.startDate} end=${policy.endDate ?: "<none>"}"
            )
            newPolicyId
        }

    /**
     * 删除 / 清空提醒计划。
     *
     * 停用该药品名下的所有提醒策略（`is_active = 0`）并清空旧时点。
     * 遵循「改计划不冲历史」：已打卡的历史事实 `dose_records` 绝不触碰；
     * 调用的下游通过 `reconcileSchedule` 自动撤销未来尚未执行的待决槽位。
     */
    suspend fun deleteReminderPolicy(medicationId: Long) = db.withTransaction {
        policyDao.deactivatePoliciesForMedication(medicationId)
        policyDao.deleteTimesForMedication(medicationId)
        AppLog.i(TAG, "deleteReminderPolicy med=$medicationId")
    }

    // ⚠️ 已删除两个库存建档辅助（M8-1）：
    //   `ensureInitialStockLedger(medicationId, stock)` 与
    //   `enableStockTrackingIfNeeded(medicationId, stock)`。
    //
    // 零生产调用方，且**调用即破坏不变量 I2**：
    // `ensureInitialStockLedger` 把 `balanceAfter` 硬编码成 `stock`，
    // 而权威值是 `SUM(change_amount)`。只要该药品在调用前已有过任何流水
    // （哪怕是负的），写进去的 `balanceAfter` 就与真实余额分叉 ——
    // 而 I2 的测试会持续校验"最后一条流水的 balanceAfter == SUM(change_amount)"，
    // 于是这个方法**一旦被调用就必然让门禁变红**。
    //
    // 正确路径是 [com.mcxiaoke.carromed.core.domain.service.DoseTrackingService.setStockTracking]：
    // 它区分"账面为 0 的首次建档"与"需要校准差额"两种情况，
    // 并按实际余额算 `balanceAfter`。
    //
    // KDoc 旧版还写着 `== medications.current_stock` —— 那一列**早就被删了**
    // （余额改成台账聚合值），文档比代码活得更久，是它误导了后来的读者。
}
