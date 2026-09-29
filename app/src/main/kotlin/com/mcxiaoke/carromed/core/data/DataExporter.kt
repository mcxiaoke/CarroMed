package com.mcxiaoke.carromed.core.data
import com.mcxiaoke.carromed.core.data.model.PolicyType
import com.mcxiaoke.carromed.core.alarm.AlarmScheduler

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.room.withTransaction
import com.mcxiaoke.carromed.core.data.entity.AppSettingEntity
import com.mcxiaoke.carromed.core.data.entity.DoseRecordEntity
import com.mcxiaoke.carromed.core.data.entity.DoseSlotEntity
import com.mcxiaoke.carromed.core.data.entity.InventoryTransactionEntity
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.data.entity.PolicyTimeEntity
import com.mcxiaoke.carromed.core.data.entity.ReminderSettingsEntity
import com.mcxiaoke.carromed.core.data.entity.SchedulePolicyEntity
import com.mcxiaoke.carromed.core.domain.model.Dose
import com.mcxiaoke.carromed.core.data.model.RecordStatus
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份校验发现的问题的**类别**。
 *
 * ## 为什么要有这个枚举
 *
 * 原实现里"这条问题要不要拦住恢复"是这么判的：
 *
 * ```kotlin
 * val fatal = problems.filter { it.contains("不受支持") || it.contains("不存在的") }
 * ```
 *
 * **用中文字符串关键词做控制流**。这不是风格问题，是三处真漏洞的共同根因：
 *
 * | 缺陷 | 旧判定的结果 | 后果 |
 * | :--- | :--- | :--- |
 * | 药品 id 重复 | 文案不含那两个词 ⇒ **放行** | `MedicationDao.insertAll` 是 `REPLACE`，静默覆盖并**外键级联删掉**该药的设置/计划/槽位/服药记录 |
 * | 槽位唯一键重复 | 文案不含那两个词 ⇒ **放行** | `DoseSlotDao.insertAll` 是 `IGNORE`，静默丢行 —— 而它自己的 KDoc 写着"必须**报错**而不是静默跳过" |
 * | 药品缺提醒设置 | `inspectText` 当 warning 放行，`importBackup` 却因 `isNotEmpty()` 整库拒绝 | 预览说"只是提示"，点确认后被拒，**用户卡死且无法绕过** |
 *
 * 第三条尤其荒唐：那条提示的文案自己都写着「恢复时会补默认值」，
 * `restoreBackup` 里也确实写了 `settingsByMed` 兜底 —— 校验层和恢复层对同一件事的判断相反。
 *
 * ## 判据应该是什么
 *
 * 判据只能是**后果**，不是措辞：
 *
 * - 会让 Room 在事务中途抛异常 ⇒ 拦住（`DANGLING_FK`）
 * - 会被 `REPLACE` / `IGNORE` **静默吞掉** ⇒ 拦住（两种重复）
 * - 恢复逻辑有兜底 ⇒ 放行（`MISSING_REMINDER_SETTINGS`）
 *
 * 加一条新问题时，它属于哪一类是**语义决定**，必须显式写进枚举，
 * 而不是靠后人记得在中文里加一个特定词。
 */
enum class BackupProblemKind(val blocksRestore: Boolean) {
    /** 备份格式版本超出本 App 支持范围，其语义无法解析 */
    UNSUPPORTED_VERSION(true),

    /** 外键指向不存在的行：回填到一半会被 Room 的外键约束抛异常 */
    DANGLING_FK(true),

    /**
     * 药品主键重复。
     *
     * ⚠️ 比外键悬空更危险：外键悬空会**响**（事务崩、用户看到错误），
     * 而这一类是**静默的** —— `REPLACE` 覆盖掉前一行，且 SQLite 的
     * `INSERT OR REPLACE` 会按外键定义级联删除子行。
     * 表现是"恢复成功"，但那个药品的提醒设置、服用计划、槽位、服药记录**全没了**。
     */
    DUPLICATE_MEDICATION_ID(true),

    /**
     * 槽位业务唯一键 `(medication_id, scheduled_date, scheduled_time)` 重复。
     *
     * A3 给 `dose_slots` 加了该 UNIQUE 约束，而 `insertAll` 是 `IGNORE` ——
     * 重复键会被静默丢弃，"恢复成功"但少了若干槽位，用户毫无察觉。
     */
    DUPLICATE_SLOT_KEY(true),

    /**
     * `dose_records.slot_id` **没有真实外键**（实体只声明了 `medication_id`），
     * 而 `reconcileSchedule` 会物理删除 `PENDING`/`SNOOZED` 槽位，
     * `undoDose` 留下的 `REVERTED` 事实仍带着那个 `slot_id`。
     *
     * ⇒ 悬空 `slot_id` **不会**让恢复崩溃（Room 无从校验一个不存在的约束），
     * 只会留下一条指向空处的历史引用。
     *
     * ⚠️ 原先它与「药品/计划外键悬空」归为同一类且 `blocksRestore = true`，
     * 于是**自己导出的备份可能被自己的校验判为不可恢复**，而用户没有绕过开关：
     * 打卡 → 撤销（事实 `REVERTED`，仍带 `slot_id`）→ 把服药时间 08:00 改成 09:00
     * → 旧槽位被删 → 备份里那条事实就悬空了。
     */
    DANGLING_SLOT_REF(false),

    /**
     * `dose_records` / `inventory_transactions` / `schedule_policies` / `policy_times`
     * 的**主键**重复。
     *
     * 这四张表的回填都是 `OnConflictStrategy.REPLACE` ⇒ 重复主键会**静默覆盖丢行**，
     * 与 [DUPLICATE_MEDICATION_ID] 同级，只是没有级联删除子表的连带损伤。
     * 校验层原先只覆盖了 `medications` 与 `dose_slots` 两处，漏了它们四张。
     */
    DUPLICATE_RECORD_ID(true),
    DUPLICATE_LEDGER_ID(true),
    DUPLICATE_POLICY_ID(true),
    DUPLICATE_POLICY_TIME_ID(true),

    /**
     * 同一药品的 `reminder_settings` 出现**多行**（M5-3）。
     *
     * A2 建立的 1:1 不变量。它是回填时的唯一约束，而恢复用的
     * `insertIfAbsent`（`IGNORE`）会让**第二行被静默丢弃** ——
     * 用户的"重要提醒 / 推迟时长 / 提前提醒"配置少了一行却显示恢复成功。
     */
    DUPLICATE_REMINDER_SETTINGS(true),

    /**
     * 同一 `app_settings.key` 出现**多行**（M5-3）。
     *
     * 后果同上：后一行静默顶掉前一行，而用户看到的只是"已恢复"。
     * 推迟时长、夜间免打扰这类设置丢起来不会有任何征兆。
     */
    DUPLICATE_APP_SETTING_KEY(true),

    /**
     * 同一药品有**多条** `is_active` 的服用计划（M5-2）。
     *
     * "同一药品同时只有一条 active 计划"从来只是一条**调用顺序维持的约定**：
     * 没有 DB 约束，`validateBackup` 也不查。
     *
     * 现在 `getActivePolicyForMedication` 加了 `ORDER BY`，所以"取哪条"是**确定的** ——
     * 但确定地取错一条仍然是数据损坏（用户的服药计划静默变成另一份）。
     * 所以必须在导入前拦住，而不是靠 ORDER BY 掩盖。
     */
    MULTIPLE_ACTIVE_POLICIES(true),

    /**
     * 某个药品缺 `reminder_settings` 行（A2 建立的不变量）。
     *
     * 放行：[restoreBackup] 会为它补一行默认值。
     * 拦住反而会让来自 A2 之前版本的备份**永远恢复不了**。
     */
    MISSING_REMINDER_SETTINGS(false),
}

/**
 * 备份里"缺失即异常"的字段的回退值。
 *
 * 提成对象而不是散落的字面量：这些数字同时出现在
 * [SchedulePolicyBackup] 的序列化默认值与 [DataExporter.restoreBackup] 的回退里，
 * 两处必须一致 —— 改一处忘一处，症状会表现为"某些旧备份恢复后疗程变了"，
 * 极难定位。
 */
object BackupDefaults {
    /** 「吃 21 停 7」是临床上最常见的疗程参数，用作回退 */
    const val CYCLE_ON_DAYS = 21
    const val CYCLE_OFF_DAYS = 7
}

/** 一条校验问题：[kind] 决定它是否拦住恢复，[message] 只用于展示。 */
data class BackupProblem(val kind: BackupProblemKind, val message: String) {
    val blocksRestore: Boolean get() = kind.blocksRestore
}

/**
 * 数据导出与备份服务
 *
 * 1. CSV 服药明细导出 (UTF-8 带 BOM，Excel/WPS 直接打开无乱码)
 * 2. JSON 全量数据库备份（`kotlinx.serialization`，格式见 [BackupFile]）
 * 3. 覆盖式恢复导入（先校验 → 先留快照 → 再整库替换）
 *
 * ## 本类的分层：纯函数在内，Android 在外
 *
 * | 层 | 函数 | 依赖 | 谁能测 |
 * | --- | --- | --- | :---: |
 * | 纯 | [buildBackup] / [restoreBackup] / [validateBackup] | 只有 [AppDatabase] | ✅ 直接单测 |
 * | IO | [exportFullBackupJson] / [inspectBackup] / [importBackup] | `Context` / `Uri` | 需 Robolectric |
 *
 * A4 之前整份编解码都埋在 `importBackup` 里，想测一次往返必须先造一个
 * `Uri` —— 于是**往返链路从来没有被测过**，字段漏写也从来没人发现。
 */
object DataExporter {

    private const val BOM = "﻿"
    private const val BACKUP_APP_TAG = "CarroMed"

    /**
     * 备份编解码配置。
     *
     * | 选项 | 为什么 |
     * | :--- | :--- |
     * | `ignoreUnknownKeys = true` | 向前兼容。V1 备份比 [BackupFile] 少一个 `schemaVersion` 字段；多出来的键必须被忽略而不是让整份文件读不进来 |
     * | `encodeDefaults = true` | 等于默认值的字段也写出来，让文件**自描述**。关掉它的话，一个只改了一个字段的备份会退化成"其余全默认"，人工核对时看不出差别 |
     * | `explicitNulls = true` | `pausedUntil = null`（未暂停）与 `""`（无限期）语义完全相反，必须都显式出现 |
     * | `coerceInputValues = true` | 读到未知枚举值时降级为默认值，而不是让整份文件失败。**宁可少一个字段，也不能恢复不成功** —— 用户已经准备清库了 |
     */
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
        coerceInputValues = true
    }

    private fun exportDir(context: Context): File {
        val base = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS)
            ?: context.filesDir
        return File(base, "exports").apply { mkdirs() }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

    // ---------------- CSV 服药明细导出 ----------------

    /**
     * 导出全量服药明细记录为 CSV 文件 (计划打卡 + 补录 + 跳过)，返回生成的文件
     */
    suspend fun exportDoseRecordsCsv(context: Context, db: AppDatabase): File {
        val medMap = db.medicationDao().getAllMedications().associateBy { it.id }
        val records = db.doseRecordDao().getAllRecords() // actual_ts 升序

        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val sb = StringBuilder()
        sb.append(BOM)
        sb.append("服药时间,药品名称,剂量,单位,记录状态,记录类型,备注\n")
        for (r in records) {
            val med = medMap[r.medicationId]
            val statusText = when (r.status) {
                RecordStatus.COMPLETED -> if (r.isRetrospective) "已服(补录)" else "已服"
                RecordStatus.SKIPPED -> "跳过"
                RecordStatus.REVERTED -> "已撤销"
            }
            val typeText = if (r.slotId != null) "计划打卡" else "手动记录"
            sb.append(escapeCsv(fmt.format(Date(r.actualTs)))).append(',')
            sb.append(escapeCsv(med?.name ?: "未知药品")).append(',')
            sb.append(Dose(r.doseTaken).asFloat).append(',')
            sb.append(escapeCsv(med?.unit ?: "")).append(',')
            sb.append(statusText).append(',')
            sb.append(typeText).append(',')
            sb.append(escapeCsv(r.note ?: "")).append('\n')
        }

        val file = File(exportDir(context), "CarroMed_服药明细_${timestamp()}.csv")
        file.writeText(sb.toString(), Charsets.UTF_8)
        return file
    }

    /**
     * 导出库存出入库流水为 CSV。
     *
     * 与服药明细 CSV 互补：服药明细回答"哪天吃了什么"，
     * 本表回答"账面怎么变成现在这样的"，可与药盒实物逐条核对。
     */
    suspend fun exportInventoryLedgerCsv(context: Context, db: AppDatabase): File {
        val medMap = db.medicationDao().getAllMedications().associateBy { it.id }
        val txs = db.inventoryTransactionDao().getAllTransactions()

        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val sb = StringBuilder()
        sb.append(BOM)
        sb.append("时间,药品名称,变动数量,单位,结余,类型,批号,有效期,备注\n")
        for (t in txs) {
            val med = medMap[t.medicationId]
            val typeText = when (t.txType) {
                com.mcxiaoke.carromed.core.data.model.TransactionType.TAKEN_DEDUCT -> "服药扣减"
                com.mcxiaoke.carromed.core.data.model.TransactionType.REFILL -> "购药入库"
                com.mcxiaoke.carromed.core.data.model.TransactionType.REVERT_ROLLBACK -> "撤销冲正"
                com.mcxiaoke.carromed.core.data.model.TransactionType.CALIBRATION_ADJUST -> "盘点调整"
                com.mcxiaoke.carromed.core.data.model.TransactionType.DOSE_EDIT_ADJUST -> "改剂量调整"
            }
            sb.append(escapeCsv(fmt.format(Date(t.createdAt)))).append(',')
            sb.append(escapeCsv(med?.name ?: "未知药品")).append(',')
            sb.append(Dose(t.changeAmount).asFloat).append(',')
            sb.append(escapeCsv(med?.unit ?: "")).append(',')
            sb.append(Dose(t.balanceAfter).asFloat).append(',')
            sb.append(typeText).append(',')
            sb.append(escapeCsv(t.batchNumber ?: "")).append(',')
            sb.append(escapeCsv(t.expiryDate ?: "")).append(',')
            sb.append(escapeCsv(t.note ?: "")).append('\n')
        }

        val file = File(exportDir(context), "CarroMed_库存流水_${timestamp()}.csv")
        file.writeText(sb.toString(), Charsets.UTF_8)
        return file
    }

    /**
     * CSV 字段转义 + **公式注入**防护。
     *
     * ## 为什么需要防注入
     *
     * 备注、药品名、注意事项都是**用户自由文本**，而 CSV 会被 Excel / WPS 直接打开。
     * 以 `=` `+` `-` `@` 开头的单元格会被这些工具**当公式执行**：
     *
     * | 用户输入 | 不转义时 Excel 打开 |
     * | :--- | :--- |
     * | `=1+1` | 显示 2，**原值 `=1+1` 消失** |
     * | `=HYPERLINK("http://evil","点我")` | 渲染成一个**可点击的钓鱼链接** |
     * | `=cmd|'/C calc'!A0`（老版本） | 直接执行命令 |
     *
     * 前两种在本项目里是完全可达的：给药品写一句带 `=` 的备注再导出即可。
     * 数值列（剂量、天数）不走这里，所以不会误伤。
     *
     * ## 为什么要包在引号里**再加**单引号
     *
     * 只加单引号在某些解析器下会被当字面量显示出来（`'=1+1`）；
     * 只加引号不能阻止公式求值。标准做法是**两者都做**：
     * 字段用双引号包住（让逗号/换行/引号安全），值本身前置一个单引号
     * （让 Excel 把它当纯文本）。
     *
     * ## `\r` 也要处理
     *
     * 旧实现只查 `\n`。而 `\r` 单独出现时（老 Mac 风格，或某些输入法/脚本写入的文本）
     * 多数 CSV 解析器**不**把它当行分隔，于是字段值里会带着一个裸回车：
     * 在 Excel 里显示成方块、在部分工具里造成列错位。
     */
    private fun escapeCsv(value: String): String {
        val needsQuoting = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        // 公式注入防护：仅对**首个非空白字符**判定。
        // 前面有空格时 Excel 仍可能求值（`' =1+1` 会被 trim 后当公式），
        // 所以这里用 trimStart 判，并**去掉前导空白**再前置单引号 ——
        // 保留缩进没有价值，而留着它等于防护失效。
        val trimmed = value.trimStart(' ', '\t')
        val needsFormulaGuard = trimmed.isNotEmpty() &&
            trimmed.first() in FORMULA_PREFIXES
        val safe = if (needsFormulaGuard) "'$trimmed" else value
        return if (needsQuoting || needsFormulaGuard) {
            "\"" + safe.replace("\"", "\"\"") + "\""
        } else safe
    }

    /**
     * 会被电子表格软件当公式开头的字符。
     *
     * `-` 在最前也会触发（`-1+1` 是公式），而"负数剂量"这种文本
     * 在本项目里本来就不该出现（领域层已 `require(dose > 0)`），
     * 所以为了安全一律加引号。
     */
    private val FORMULA_PREFIXES = charArrayOf('=', '+', '-', '@', '\t', '\r')

    // ---------------- JSON 全量备份：纯函数层 ----------------

    /**
     * 把整库读成 [BackupFile]。**纯函数**：不碰 `Context`，因此可以直接单测。
     */
    suspend fun buildBackup(db: AppDatabase, now: Long = System.currentTimeMillis()): BackupFile {
        // 库存余额不再是 medications 的一列；此处附上台账聚合值**仅供人工核对**，
        // 恢复流水即恢复余额，不从它写回任何列。
        val balanceByMed = db.inventoryTransactionDao().getAllBalances()
            .associate { it.medicationId to it.balance }

        return BackupFile(
            app = BACKUP_APP_TAG,
            formatVersion = BackupFormatVersion.CURRENT.code,
            exportedAt = now,
            exportedAtText = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(Date(now)),
            medications = db.medicationDao().getAllMedications().map { m ->
                MedicationBackup(
                    id = m.id,
                    name = m.name,
                    alias = m.alias,
                    category = m.category,
                    form = m.form,
                    unit = m.unit,
                    colorHex = m.colorHex,
                    defaultDose = m.defaultDose,
                    description = m.description,
                    precautions = m.precautions,
                    noticeShort = m.noticeShort,
                    stockMilli = balanceByMed[m.id] ?: 0,
                    minStockAlert = m.minStockAlert,
                    isStockTracked = m.isStockTracked,
                    expiryDate = m.expiryDate,
                    isArchived = m.isArchived,
                    createdAt = m.createdAt,
                    updatedAt = m.updatedAt
                )
            },
            reminderSettings = db.reminderSettingsDao().getAll().map { s ->
                ReminderSettingsBackup(
                    medicationId = s.medicationId,
                    isCriticalReminder = s.isCriticalReminder,
                    snoozeMinutes = s.snoozeMinutes,
                    advanceMinutes = s.advanceMinutes,
                    pausedUntil = s.pausedUntil
                )
            },
            schedulePolicies = db.schedulePolicyDao().getAllPolicies().map { p ->
                SchedulePolicyBackup(
                    id = p.id,
                    medicationId = p.medicationId,
                    policyType = p.policyType,
                    intervalDays = p.intervalDays,
                    daysOfWeek = p.daysOfWeek,
                    cycleOnDays = p.cycleOnDays,
                    cycleOffDays = p.cycleOffDays,
                    startDate = p.startDate,
                    endDate = p.endDate,
                    isActive = p.isActive,
                    version = p.version,
                    createdAt = p.createdAt
                )
            },
            policyTimes = db.schedulePolicyDao().getAllTimes().map { t ->
                PolicyTimeBackup(
                    id = t.id,
                    policyId = t.policyId,
                    timeOfDay = t.timeOfDay,
                    doseAmount = t.doseAmount,
                    label = t.label,
                    sortOrder = t.sortOrder
                )
            },
            doseSlots = db.doseSlotDao().getAllSlots().map { s ->
                DoseSlotBackup(
                    id = s.id,
                    medicationId = s.medicationId,
                    policyId = s.policyId,
                    scheduledDate = s.scheduledDate,
                    scheduledTime = s.scheduledTime,
                    scheduledTs = s.scheduledTs,
                    doseAmount = s.doseAmount,
                    status = s.status,
                    actualTakenTs = s.actualTakenTs,
                    snoozeUntilTs = s.snoozeUntilTs,
                    createdAt = s.createdAt
                )
            },
            doseRecords = db.doseRecordDao().getAllRecords().map { r ->
                DoseRecordBackup(
                    id = r.id,
                    slotId = r.slotId,
                    medicationId = r.medicationId,
                    actualTs = r.actualTs,
                    doseTaken = r.doseTaken,
                    status = r.status,
                    isRetrospective = r.isRetrospective,
                    note = r.note,
                    createdAt = r.createdAt
                )
            },
            inventoryTransactions = db.inventoryTransactionDao().getAllTransactions().map { t ->
                InventoryTransactionBackup(
                    id = t.id,
                    medicationId = t.medicationId,
                    recordId = t.recordId,
                    changeAmount = t.changeAmount,
                    balanceAfter = t.balanceAfter,
                    txType = t.txType,
                    note = t.note,
                    batchNumber = t.batchNumber,
                    expiryDate = t.expiryDate,
                    createdAt = t.createdAt
                )
            },
            appSettings = db.appSettingDao().getAllSettings().map { s ->
                AppSettingBackup(key = s.key, value = s.value, updatedAt = s.updatedAt)
            }
        )
    }

    fun encodeBackup(backup: BackupFile): String = json.encodeToString(BackupFile.serializer(), backup)

    fun decodeBackup(text: String): BackupFile = json.decodeFromString(BackupFile.serializer(), text)

    /**
     * 恢复前的完整性校验。
     *
     * ## 为什么要"先校验、后清库"
     *
     * 旧实现是 `deleteAll*()` 之后才开始逐段解析。一旦解析到一半失败，
     * 整库已经被清空、备份又读不进去 —— **用户的数据两头都没了**。
     * 校验必须在任何写操作之前完成。
     *
     * 检查项都是"外键指向不存在的行"这类结构性错误。它们不会让解析失败，
     * 但会让 Room 的外键约束在事务中途抛异常，结果同样是"清空了但没恢复成"。
     */
    /**
     * 校验一份备份能否安全恢复。
     *
     * 返回**分类后**的问题列表；是否拦住恢复只看 [BackupProblem.blocksRestore]，
     * 绝不看 [BackupProblem.message] 的措辞。理由见 [BackupProblemKind] 的 KDoc。
     *
     * 检查项都是"回填时会被静默吞掉或让事务中途崩"这类**结构性**错误，
     * 它们不会让 JSON 解析失败，只会让恢复"看起来成功但数据不对"。
     */
    fun validateBackup(backup: BackupFile): List<BackupProblem> {
        val problems = mutableListOf<BackupProblem>()
        fun report(kind: BackupProblemKind, message: String) {
            problems += BackupProblem(kind, message)
        }

        val version = BackupFormatVersion.from(backup.formatVersion)
        if (version == null) {
            report(
                BackupProblemKind.UNSUPPORTED_VERSION,
                "备份格式版本 ${backup.formatVersion} 不受支持（当前支持 1~${BackupFormatVersion.CURRENT.code}）"
            )
        }

        val medIds = backup.medications.map { it.id }.toSet()
        val policyIds = backup.schedulePolicies.map { it.id }.toSet()
        val slotIds = backup.doseSlots.map { it.id }.toSet()

        backup.reminderSettings.forEach {
            if (it.medicationId !in medIds) {
                report(BackupProblemKind.DANGLING_FK, "提醒设置引用了不存在的药品 #${it.medicationId}")
            }
        }
        backup.schedulePolicies.forEach {
            if (it.medicationId !in medIds) {
                report(BackupProblemKind.DANGLING_FK, "服用计划 #${it.id} 引用了不存在的药品 #${it.medicationId}")
            }
        }
        backup.policyTimes.forEach {
            if (it.policyId !in policyIds) {
                report(BackupProblemKind.DANGLING_FK, "服药时点 #${it.id} 引用了不存在的计划 #${it.policyId}")
            }
        }
        backup.doseSlots.forEach {
            if (it.medicationId !in medIds) {
                report(BackupProblemKind.DANGLING_FK, "槽位 #${it.id} 引用了不存在的药品 #${it.medicationId}")
            }
        }
        backup.doseRecords.forEach {
            if (it.medicationId !in medIds) {
                report(BackupProblemKind.DANGLING_FK, "服药记录 #${it.id} 引用了不存在的药品 #${it.medicationId}")
            }
            // slotId 可空（手动补录），非空时必须指向存在的槽位。
            // ⚠️ 但这**不是**致命项：没有真实外键约束，恢复不会崩。见 [BackupProblemKind.DANGLING_SLOT_REF]。
            if (it.slotId != null && it.slotId !in slotIds) {
                report(BackupProblemKind.DANGLING_SLOT_REF, "服药记录 #${it.id} 引用了已不存在的槽位 #${it.slotId}")
            }
        }
        backup.inventoryTransactions.forEach {
            if (it.medicationId !in medIds) {
                report(BackupProblemKind.DANGLING_FK, "库存流水 #${it.id} 引用了不存在的药品 #${it.medicationId}")
            }
        }

        // 药品 id 重复 ⇒ `MedicationDao.insertAll` 的 REPLACE 会覆盖前一行，
        // 并按外键定义级联删掉它的设置/计划/槽位/记录 —— 静默丢数据。
        backup.medications.groupBy { it.id }
            .filterValues { it.size > 1 }
            .forEach { (id, dup) ->
                report(BackupProblemKind.DUPLICATE_MEDICATION_ID, "药品 #$id 在备份中出现了 ${dup.size} 次")
            }

        // 槽位唯一键重复 ⇒ `DoseSlotDao.insertAll` 的 IGNORE 会静默丢行。
        backup.doseSlots
            .groupBy { Triple(it.medicationId, it.scheduledDate, it.scheduledTime) }
            .filterValues { it.size > 1 }
            .forEach { (key, dup) ->
                report(
                    BackupProblemKind.DUPLICATE_SLOT_KEY,
                    "药品 #${key.first} 在 ${key.second} ${key.third} 有 ${dup.size} 条重复槽位"
                )
            }

        // ⚠️ 四张表的回填都是 `OnConflictStrategy.REPLACE`，
        // 重复主键会**静默覆盖丢行**。校验层原先只覆盖 `medications` 与 `dose_slots`，
        // 漏了这四张 —— 而它们恰恰是最可能被手工编辑过的表。
        fun <T> checkDuplicates(
            rows: List<T>,
            idOf: (T) -> Long,
            kind: BackupProblemKind,
            label: String
        ) {
            rows.groupBy { idOf(it) }
                .filterValues { it.size > 1 }
                .forEach { (rid, dup) -> report(kind, "$label #$rid 在备份中出现了 ${dup.size} 次") }
        }
        checkDuplicates(backup.doseRecords, { it.id }, BackupProblemKind.DUPLICATE_RECORD_ID, "服药记录")
        checkDuplicates(backup.inventoryTransactions, { it.id }, BackupProblemKind.DUPLICATE_LEDGER_ID, "库存流水")
        checkDuplicates(backup.schedulePolicies, { it.id }, BackupProblemKind.DUPLICATE_POLICY_ID, "服用计划")
        checkDuplicates(backup.policyTimes, { it.id }, BackupProblemKind.DUPLICATE_POLICY_TIME_ID, "服药时点")

        // ⚠️ 下面三类的**主键不是自增 id**，上面那个通用检查覆盖不到它们（M5-3）。
        //
        // | 表 | 键 | 重复后果 |
        // | :--- | :--- | :--- |
        // | `dose_slots` | `id`（自增，但备份可被手工编辑） | REPLACE 覆盖前一行，**丢槽位** |
        // | `reminder_settings` | `medication_id` | A2 建立的 1:1 不变量被破坏，两行互相覆盖 |
        // | `app_settings` | `key` | 后一行静默顶掉前一行，**设置丢失且无任何提示** |
        //
        // 三者都是 `OnConflictStrategy.REPLACE` 或等价的 upsert，
        // 所以重复 = 静默丢数据，而恢复流程**照常显示成功**。
        backup.doseSlots.groupBy { it.id }
            .filterValues { it.size > 1 }
            .forEach { (rid, dup) ->
                report(
                    BackupProblemKind.DUPLICATE_SLOT_KEY,
                    "槽位 #$rid 在备份中出现了 ${dup.size} 次（主键重复会静默覆盖）"
                )
            }
        backup.reminderSettings.groupBy { it.medicationId }
            .filterValues { it.size > 1 }
            .forEach { (medId, dup) ->
                report(
                    BackupProblemKind.DUPLICATE_REMINDER_SETTINGS,
                    "药品 #$medId 的提醒设置在备份中有 ${dup.size} 行（每药应恰好一行）"
                )
            }
        backup.appSettings.groupBy { it.key }
            .filterValues { it.size > 1 }
            .forEach { (key, dup) ->
                report(
                    BackupProblemKind.DUPLICATE_APP_SETTING_KEY,
                    "设置项「$key」在备份中出现了 ${dup.size} 次"
                )
            }

        // 同一药品多条**活跃**计划 ⇒ "取哪条"是不确定的（M5-2）。
        //
        // `SchedulePolicyDao.getActivePolicyForMedication` 现在有 `ORDER BY`，
        // 所以结果**确定**了；但"确定地取错一条"仍然是数据损坏 ——
        // 用户的服药计划会静默变成另一份。所以必须在**导入前**拦住。
        backup.schedulePolicies.filter { it.isActive }
            .groupBy { it.medicationId }
            .filterValues { it.size > 1 }
            .forEach { (medId, dup) ->
                report(
                    BackupProblemKind.MULTIPLE_ACTIVE_POLICIES,
                    "药品 #$medId 有 ${dup.size} 条同时生效的计划（#${dup.joinToString { p -> p.id.toString() }}）"
                )
            }

        // 每个药品都应有一行提醒运行态（A2 建立的不变量）。缺失的会在恢复时补默认值，
        // 所以这是**提示**而不是错误 —— 拦住会让 A2 之前版本的备份永远恢复不了。
        val medsWithSettings = backup.reminderSettings.map { it.medicationId }.toSet()
        medIds.subtract(medsWithSettings).forEach {
            report(
                BackupProblemKind.MISSING_REMINDER_SETTINGS,
                "药品 #$it 缺少提醒设置（恢复时会补默认值，建议重新导出备份）"
            )
        }

        return problems
    }

    /**
     * 用备份覆盖整库。**必须在事务内**，且调用前必须已通过 [validateBackup]。
     *
     * @return 恢复的药品数与服药记录数
     */
    suspend fun restoreBackup(db: AppDatabase, backup: BackupFile): Pair<Int, Int> =
        db.withTransaction {
            // 清空旧数据 (子表在前，父表在后)
            db.appSettingDao().deleteAllSettings()
            db.inventoryTransactionDao().deleteAllTransactions()
            db.doseRecordDao().deleteAllRecords()
            db.doseSlotDao().deleteAllSlots()
            db.reminderSettingsDao().deleteAll()
            db.schedulePolicyDao().deleteAllTimes()
            db.schedulePolicyDao().deleteAllPolicies()
            db.medicationDao().deleteAllMedications()

            // 按外键依赖顺序回填 (父表在前，原样保留自增 ID)
            db.medicationDao().insertAll(backup.medications.map { m ->
                MedicationEntity(
                    id = m.id,
                    name = m.name,
                    alias = m.alias,
                    category = m.category,
                    form = m.form,
                    unit = m.unit,
                    colorHex = m.colorHex,
                    defaultDose = m.defaultDose,
                    description = m.description,
                    precautions = m.precautions,
                    noticeShort = m.noticeShort,
                    minStockAlert = m.minStockAlert,
                    isStockTracked = m.isStockTracked,
                    expiryDate = m.expiryDate,
                    isArchived = m.isArchived,
                    createdAt = m.createdAt,
                    updatedAt = m.updatedAt
                )
            })

            // 提醒运行态必须**晚于**药品恢复（外键约束）。
            // 备份来自缺少该数组的旧版本时，为每个药品补一行默认值，
            // 否则"恢复后提醒设置页一保存就静默失败"。
            val settingsByMed = backup.reminderSettings.associateBy { it.medicationId }
            backup.medications.forEach { m ->
                val s = settingsByMed[m.id]
                db.reminderSettingsDao().insert(
                    ReminderSettingsEntity(
                        medicationId = m.id,
                        isCriticalReminder = s?.isCriticalReminder ?: false,
                        snoozeMinutes = s?.snoozeMinutes ?: 0,
                        advanceMinutes = s?.advanceMinutes ?: 0,
                        pausedUntil = s?.pausedUntil
                    )
                )
            }

            db.schedulePolicyDao().insertAllPolicies(backup.schedulePolicies.map { p ->
                SchedulePolicyEntity(
                    id = p.id,
                    medicationId = p.medicationId,
                    policyType = p.policyType,
                    intervalDays = p.intervalDays,
                    daysOfWeek = p.daysOfWeek,
                    // ⚠️ CYCLE 的 0 值回退默认 21/7（M5-8）。
                    //
                    // 旧版本备份里 `cycleOnDays` 的序列化默认值是 **0**，
                    // 而投影层把它 `coerceAtLeast(1)` 夹成 1：
                    // `totalCycle = 1 + off`，`cycleDay % total < 1` 恒真 ⇒
                    // **「吃 21 停 7」静默变成「每天都吃」**。
                    //
                    // 判据只对 CYCLE 生效：DAILY / INTERVAL / DAYS_OF_WEEK 的
                    // 这两列本来就不参与计算，改它们没有意义。
                    // 只在"备份里是 0"时回退，正常备份的值原样保留。
                    cycleOnDays = if (p.policyType == PolicyType.CYCLE && p.cycleOnDays <= 0) {
                        BackupDefaults.CYCLE_ON_DAYS
                    } else {
                        p.cycleOnDays
                    },
                    cycleOffDays = if (p.policyType == PolicyType.CYCLE && p.cycleOnDays <= 0) {
                        BackupDefaults.CYCLE_OFF_DAYS
                    } else {
                        p.cycleOffDays
                    },
                    startDate = p.startDate,
                    endDate = p.endDate,
                    isActive = p.isActive,
                    version = p.version,
                    createdAt = p.createdAt
                )
            })

            db.schedulePolicyDao().insertTimes(backup.policyTimes.map { t ->
                PolicyTimeEntity(
                    id = t.id,
                    policyId = t.policyId,
                    timeOfDay = t.timeOfDay,
                    doseAmount = t.doseAmount,
                    label = t.label,
                    sortOrder = t.sortOrder
                )
            })

            // ⚠️ `insertAll` 是 `OnConflictStrategy.IGNORE`（A3 为保住 slot.id 而改的），
            // 所以**唯一键重复会被静默丢弃**。这正是 [validateBackup] 要提前拦下的原因。
            db.doseSlotDao().insertAll(backup.doseSlots.map { s ->
                DoseSlotEntity(
                    id = s.id,
                    medicationId = s.medicationId,
                    policyId = s.policyId,
                    scheduledDate = s.scheduledDate,
                    scheduledTime = s.scheduledTime,
                    scheduledTs = s.scheduledTs,
                    doseAmount = s.doseAmount,
                    status = s.status,
                    actualTakenTs = s.actualTakenTs,
                    snoozeUntilTs = s.snoozeUntilTs,
                    createdAt = s.createdAt
                )
            })

            db.doseRecordDao().insertAll(backup.doseRecords.map { r ->
                DoseRecordEntity(
                    id = r.id,
                    slotId = r.slotId,
                    medicationId = r.medicationId,
                    actualTs = r.actualTs,
                    doseTaken = r.doseTaken,
                    status = r.status,
                    isRetrospective = r.isRetrospective,
                    note = r.note,
                    createdAt = r.createdAt
                )
            })

            db.inventoryTransactionDao().insertAll(backup.inventoryTransactions.map { t ->
                InventoryTransactionEntity(
                    id = t.id,
                    medicationId = t.medicationId,
                    recordId = t.recordId,
                    changeAmount = t.changeAmount,
                    balanceAfter = t.balanceAfter,
                    txType = t.txType,
                    note = t.note,
                    batchNumber = t.batchNumber,
                    expiryDate = t.expiryDate,
                    createdAt = t.createdAt
                )
            })

            db.appSettingDao().insertAll(backup.appSettings.map { s ->
                AppSettingEntity(key = s.key, value = s.value, updatedAt = s.updatedAt)
            })

            backup.medications.size to backup.doseRecords.size
        }

    // ---------------- JSON 全量备份：IO 层 ----------------

    /** 导出全量数据库为 JSON 备份文件，返回生成的文件 */
    suspend fun exportFullBackupJson(context: Context, db: AppDatabase): File {
        val file = File(exportDir(context), "CarroMed_全量备份_${timestamp()}.json")
        file.writeText(encodeBackup(buildBackup(db)), Charsets.UTF_8)
        return file
    }

    /**
     * 恢复前把**当前数据**另存一份。
     *
     * 覆盖式恢复是不可逆的，而"选错文件"是必然会发生的用户错误。
     * 这份快照让误操作可逆，成本是一次文件写入。
     * 写失败**不阻断恢复** —— 快照是保险，不是前置条件。
     */
    private suspend fun writeSafetySnapshot(context: Context, db: AppDatabase): File? = try {
        val file = File(exportDir(context), "CarroMed_恢复前快照_${timestamp()}.json")
        file.writeText(encodeBackup(buildBackup(db)), Charsets.UTF_8)
        file
    } catch (e: Exception) {
        android.util.Log.w("DataExporter", "safety snapshot failed", e)
        null
    }

    sealed class RestoreResult {
        /** @param snapshotFile 恢复前自动留的快照文件路径；写失败时为 null */
        data class Success(
            val medications: Int,
            val records: Int,
            val snapshotFile: String?
        ) : RestoreResult()

        /** 备份文件本身不合法 —— **数据库未被触碰** */
        data class Invalid(val reason: String) : RestoreResult()

        /** 恢复过程中出错。数据库处于事务中，会整体回滚 */
        data class Failure(val message: String) : RestoreResult()
    }

    /**
     * 备份文件预览，供二次确认对话框使用（P1-14 / `FINAL-PRODUCT:158`）。
     *
     * 二次确认必须**带真实信息**才有用：
     * "确定要覆盖吗？"用户只能盲点确定；"该备份含 4 种药品、2 条服药记录、
     * 生成于 2026-09-28 13:45，当前数据将被完全替换"才是决策依据。
     */
    data class BackupPreview(
        val fileName: String,
        val version: BackupFormatVersion,
        val exportedAtText: String,
        val medicationCount: Int,
        val recordCount: Int,
        val slotCount: Int,
        val ledgerCount: Int,

        /**
         * **不拦住恢复**的问题（[BackupProblemKind.blocksRestore] 为 false 的那些）。
         *
         * 调用方展示 [BackupProblem.message] 即可 —— 不要在这里再判一次
         * "这条要不要给用户看"，那是 [BackupProblemKind] 已经决定过的事。
         */
        val warnings: List<BackupProblem>
    )

    private fun readText(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        }.getOrNull()

    private fun readText(file: File): String? =
        runCatching { file.readText(Charsets.UTF_8) }.getOrNull()

    /**
     * App 自己导出的备份文件列表（`exportDir` 下）。
     *
     * ## 为什么需要这个入口（这是实测发现的真实限制）
     *
     * 备份写在 `context.getExternalFilesDir(DOCUMENTS)/exports`，也就是
     * `/sdcard/Android/data/<pkg>/files/...`。**SAF 明确不允许浏览这个目录** ——
     * 这是 Android 的设计，不是 bug。于是：
     *
     * > 用户点了「生成备份」，关掉分享面板，那份文件**再也选不回来**。
     * > 只有先分享到「文件」/云盘，才可能再导入。
     *
     * 对一个**物理断网**（`AndroidManifest` 里严禁 `INTERNET` 权限）的 App，
     * "必须先分享出去才能导回来" 是不可接受的。所以除了系统文件选择器，
     * 再给一个直接列本机导出记录的入口。
     */
    fun listLocalBackups(context: Context): List<LocalBackupInfo> {
        val dir = exportDir(context)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { LocalBackupInfo(file = it, displayName = it.name, sizeKb = it.length() / 1024) }
            ?: emptyList()
    }

    data class LocalBackupInfo(
        val file: File,
        val displayName: String,
        val sizeKb: Long
    )

    /**
     * 只读解析与校验，**不碰数据库**。
     * UI 用它来显示二次确认信息；解析失败时用户的数据分毫未动。
     */
    suspend fun inspectBackup(context: Context, uri: Uri): Result<BackupPreview> {
        val text = readText(context, uri)
            ?: return Result.failure(IllegalArgumentException("无法读取所选文件"))
        return inspectText(text, displayName(context, uri))
    }

    /** 同上，但读本机导出目录里的文件（绕开 SAF 的目录限制） */
    fun inspectLocalBackup(file: File): Result<BackupPreview> {
        val text = readText(file)
            ?: return Result.failure(IllegalArgumentException("无法读取 ${file.name}"))
        return inspectText(text, file.name)
    }

    private fun inspectText(text: String, display: String): Result<BackupPreview> {
        val backup = runCatching { decodeBackup(text) }.getOrElse {
            return Result.failure(IllegalArgumentException("不是有效的 CarroMed 备份文件"))
        }
        if (backup.app != BACKUP_APP_TAG) {
            return Result.failure(IllegalArgumentException("该文件不是 CarroMed 备份文件"))
        }
        val problems = validateBackup(backup)
        val fatal = problems.filter { it.blocksRestore }
        if (fatal.isNotEmpty()) {
            return Result.failure(IllegalArgumentException(fatal.joinToString("；") { it.message }))
        }
        val version = BackupFormatVersion.from(backup.formatVersion) ?: BackupFormatVersion.V1
        return Result.success(
            BackupPreview(
                fileName = display,
                version = version,
                exportedAtText = backup.exportedAtText,
                medicationCount = backup.medications.size,
                recordCount = backup.doseRecords.size,
                slotCount = backup.doseSlots.size,
                ledgerCount = backup.inventoryTransactions.size,
                warnings = problems
            )
        )
    }

    private fun displayName(context: Context, uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "所选文件"

    /**
     * 从 JSON 备份覆盖式恢复整库。
     *
     * 执行顺序是本函数最重要的部分：
     * **读 → 校验 → 留快照 → 才开事务清库**。任何一步失败都不会丢数据。
     */
    suspend fun importBackup(context: Context, db: AppDatabase, uri: Uri): RestoreResult {
        val text = readText(context, uri) ?: return RestoreResult.Invalid("无法读取所选文件")
        return restoreFromText(context, db, text)
    }

    /** 同上，但源是本机导出目录里的文件 */
    suspend fun importLocalBackup(context: Context, db: AppDatabase, file: File): RestoreResult {
        val text = readText(file) ?: return RestoreResult.Invalid("无法读取 ${file.name}")
        return restoreFromText(context, db, text)
    }

    private suspend fun restoreFromText(
        context: Context,
        db: AppDatabase,
        text: String
    ): RestoreResult {
        val backup = try {
            decodeBackup(text)
        } catch (e: Exception) {
            return RestoreResult.Invalid("不是有效的 CarroMed 备份文件")
        }
        if (backup.app != BACKUP_APP_TAG) {
            return RestoreResult.Invalid("该文件不是 CarroMed 备份文件")
        }

        val problems = validateBackup(backup)
        // ⚠️ 判据必须与 [inspectText] **逐字一致**。
        //
        // 旧实现这里是 `if (problems.isNotEmpty())` —— 任何问题都整库拒绝，
        // 而预览那边只把"不受支持 / 不存在的"当致命。两边判据不同源的直接后果是：
        // 预览页显示"可以恢复，只是有 N 条提示"，用户点确认后被告知恢复失败，
        // **而且没有任何办法绕过**。其中最常见的一条恰恰是
        // 「药品缺少提醒设置（恢复时会补默认值）」—— 文案自己都说能补默认值。
        val fatal = problems.filter { it.blocksRestore }
        if (fatal.isNotEmpty()) {
            // ⚠️ 在此返回，数据库**尚未被触碰**
            return RestoreResult.Invalid(fatal.joinToString("；") { it.message })
        }

        val snapshot = writeSafetySnapshot(context, db)

        // ⚠️ 必须在**清库之前**撤掉旧库的**全部**闹钟（M5-1）。
        //
        // `AlarmReconciler` 的孤儿清理靠的是"重排**之前**拍一份开放槽位快照"，
        // 而恢复路径把库整个换掉了 —— 恢复**之后**再去对账，快照里已经
        // 是新数据，旧库的闹钟身份**根本不在其中**。
        //
        // 后果：恢复一份**更早**的备份后，旧库里那些"已排但永远不会响"的闹钟
        // 仍留在系统里。它们不是无害的：`dose_slots.id` 在恢复后是备份里的值，
        // 而备份可能来自更早的库 —— **id 空间会交叠**。
        // 于是"旧备份的槽位 id = 3"与"新库的槽位 id = 3"指向不同的药，
        // 而 `AlarmReceiver` 当时正是按 extras 里的 slotId 反查的
        // ⇒ **可能给错药发提醒**。
        //
        // 撤在清库之前，才能拿到完整的旧身份集合。
        cancelAllAlarmsBeforeRestore(context, db)

        return try {
            val (medCount, recordCount) = restoreBackup(db, backup)
            RestoreResult.Success(medCount, recordCount, snapshot?.absolutePath)
        } catch (e: Exception) {
            // 事务整体回滚，数据库回到恢复前的样子
            RestoreResult.Failure("恢复失败，已自动回滚: ${e.message}")
        }
    }

    /**
     * 撤掉当前库里**所有**开放槽位的全部种类闹钟。
     *
     * 只在恢复路径调用。失败**不阻断**恢复 —— 撤不掉的闹钟最坏结果是
     * 空唤醒（`AlarmReceiver` 的 `getSlotById == null` 守卫会静默丢弃），
     * 而阻断恢复会让用户卡在"明明有备份却导不进来"。
     */
    private suspend fun cancelAllAlarmsBeforeRestore(context: Context, db: AppDatabase) {
        val app = context.applicationContext
        val open = runCatching { db.doseSlotDao().getOpenSlots() }.getOrElse {
            android.util.Log.w("DataExporter", "cannot read open slots before restore", it)
            return
        }
        open.forEach { slot ->
            runCatching {
                AlarmScheduler.cancelAll(
                    app, slot.medicationId, slot.scheduledDate, slot.scheduledTime, slot.id
                )
            }
        }
    }

    // ---------------- 分享 ----------------

    /**
     * 通过系统分享面板分享导出文件 (FileProvider 授权)
     */
    fun shareFile(context: Context, file: File, mime: String, title: String = "分享导出文件") {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(chooser) }
    }
}
