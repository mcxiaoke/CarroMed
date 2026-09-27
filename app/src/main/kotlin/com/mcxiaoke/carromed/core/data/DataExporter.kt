package com.mcxiaoke.carromed.core.data

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
import com.mcxiaoke.carromed.core.data.entity.SchedulePolicyEntity
import com.mcxiaoke.carromed.core.data.model.RecordStatus
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 数据导出与备份服务
 * 1. CSV 服药明细导出 (UTF-8 带 BOM，Excel/WPS 直接打开无乱码)
 * 2. JSON 全量数据库备份 (org.json 序列化，零额外依赖)
 * 3. 覆盖式恢复导入 (整库快照替换，原样还原自增 ID)
 */
object DataExporter {

    private const val BOM = "\uFEFF"
    private const val BACKUP_APP_TAG = "CarroMed"
    private const val BACKUP_FORMAT_VERSION = 1

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
                RecordStatus.RETROSPECTIVE -> "已服(补录)"
            }
            val typeText = if (r.slotId != null) "计划打卡" else "手动记录"
            sb.append(escapeCsv(fmt.format(Date(r.actualTs)))).append(',')
            sb.append(escapeCsv(med?.name ?: "未知药品")).append(',')
            sb.append(trimFloat(r.doseTaken)).append(',')
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
            }
            sb.append(escapeCsv(fmt.format(Date(t.createdAt)))).append(',')
            sb.append(escapeCsv(med?.name ?: "未知药品")).append(',')
            sb.append(trimFloat(t.changeAmount)).append(',')
            sb.append(escapeCsv(med?.unit ?: "")).append(',')
            sb.append(trimFloat(t.balanceAfter)).append(',')
            sb.append(typeText).append(',')
            sb.append(escapeCsv(t.batchNumber ?: "")).append(',')
            sb.append(escapeCsv(t.expiryDate ?: "")).append(',')
            sb.append(escapeCsv(t.note ?: "")).append('\n')
        }

        val file = File(exportDir(context), "CarroMed_库存流水_${timestamp()}.csv")
        file.writeText(sb.toString(), Charsets.UTF_8)
        return file
    }

    private fun trimFloat(v: Float): String =
        if (v % 1f == 0f) v.toInt().toString() else v.toString()

    private fun escapeCsv(value: String): String =
        if (value.contains(',') || value.contains('"') || value.contains('\n')) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else value

    // ---------------- JSON 全量备份 ----------------

    /**
     * 导出全量数据库为 JSON 备份文件，返回生成的文件
     */
    suspend fun exportFullBackupJson(context: Context, db: AppDatabase): File {
        val root = JSONObject()
        root.put("app", BACKUP_APP_TAG)
        root.put("formatVersion", BACKUP_FORMAT_VERSION)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("exportedAtText", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))

        root.put("medications", JSONArray().apply {
            db.medicationDao().getAllMedications().forEach { m ->
                put(JSONObject()
                    .put("id", m.id).put("name", m.name).put("alias", m.alias ?: JSONObject.NULL)
                    .put("category", m.category).put("form", m.form).put("unit", m.unit)
                    .put("colorHex", m.colorHex).put("iconName", m.iconName)
                    .put("defaultDose", m.defaultDose.toDouble())
                    .put("description", m.description)
                    .put("precautions", JSONArray(m.precautions))
                    .put("noticeShort", m.noticeShort)
                    .put("currentStock", m.currentStock.toDouble())
                    .put("minStockAlert", m.minStockAlert.toDouble())
                    .put("isStockTracked", m.isStockTracked)
                    .put("expiryDate", m.expiryDate)
                    .put("isCriticalReminder", m.isCriticalReminder)
                    .put("snoozeMinutes", m.snoozeMinutes)
                    .put("advanceMinutes", m.advanceMinutes)
                    .put("isPaused", m.isPaused).put("isArchived", m.isArchived)
                    .put("createdAt", m.createdAt).put("updatedAt", m.updatedAt))
            }
        })

        root.put("schedulePolicies", JSONArray().apply {
            db.schedulePolicyDao().getAllPolicies().forEach { p ->
                put(JSONObject()
                    .put("id", p.id).put("medicationId", p.medicationId)
                    .put("policyType", p.policyType.name)
                    .put("intervalDays", p.intervalDays)
                    .put("daysOfWeek", JSONArray(p.daysOfWeek))
                    .put("cycleOnDays", p.cycleOnDays).put("cycleOffDays", p.cycleOffDays)
                    .put("startDate", p.startDate)
                    .put("endDate", p.endDate ?: JSONObject.NULL)
                    .put("isActive", p.isActive).put("version", p.version)
                    .put("createdAt", p.createdAt))
            }
        })

        root.put("policyTimes", JSONArray().apply {
            db.schedulePolicyDao().getAllTimes().forEach { t ->
                put(JSONObject()
                    .put("id", t.id).put("policyId", t.policyId)
                    .put("timeOfDay", t.timeOfDay)
                    .put("doseAmount", t.doseAmount.toDouble())
                    .put("label", t.label).put("sortOrder", t.sortOrder))
            }
        })

        root.put("doseSlots", JSONArray().apply {
            db.doseSlotDao().getAllSlots().forEach { s ->
                put(JSONObject()
                    .put("id", s.id).put("medicationId", s.medicationId).put("policyId", s.policyId)
                    .put("scheduledDate", s.scheduledDate).put("scheduledTime", s.scheduledTime)
                    .put("scheduledTs", s.scheduledTs).put("doseAmount", s.doseAmount.toDouble())
                    .put("status", s.status.name)
                    .put("actualTakenTs", s.actualTakenTs ?: JSONObject.NULL)
                    .put("snoozeUntilTs", s.snoozeUntilTs ?: JSONObject.NULL)
                    .put("createdAt", s.createdAt))
            }
        })

        root.put("doseRecords", JSONArray().apply {
            db.doseRecordDao().getAllRecords().forEach { r ->
                put(JSONObject()
                    .put("id", r.id).put("slotId", r.slotId ?: JSONObject.NULL)
                    .put("medicationId", r.medicationId).put("actualTs", r.actualTs)
                    .put("doseTaken", r.doseTaken.toDouble())
                    .put("status", r.status.name).put("isRetrospective", r.isRetrospective)
                    .put("note", r.note ?: JSONObject.NULL).put("createdAt", r.createdAt))
            }
        })

        root.put("inventoryTransactions", JSONArray().apply {
            db.inventoryTransactionDao().getAllTransactions().forEach { t ->
                put(JSONObject()
                    .put("id", t.id).put("medicationId", t.medicationId)
                    .put("recordId", t.recordId ?: JSONObject.NULL)
                    .put("changeAmount", t.changeAmount.toDouble())
                    .put("balanceAfter", t.balanceAfter.toDouble())
                    .put("txType", t.txType.name)
                    .put("note", t.note ?: JSONObject.NULL)
                    .put("batchNumber", t.batchNumber ?: JSONObject.NULL)
                    .put("expiryDate", t.expiryDate ?: JSONObject.NULL)
                    .put("createdAt", t.createdAt))
            }
        })

        root.put("appSettings", JSONArray().apply {
            db.appSettingDao().getAllSettings().forEach { s ->
                put(JSONObject().put("key", s.key).put("value", s.value))
            }
        })

        val file = File(exportDir(context), "CarroMed_全量备份_${timestamp()}.json")
        file.writeText(root.toString(2), Charsets.UTF_8)
        return file
    }

    // ---------------- 覆盖式恢复导入 ----------------

    sealed class RestoreResult {
        data class Success(val medications: Int, val records: Int) : RestoreResult()
        data class Invalid(val reason: String) : RestoreResult()
        data class Failure(val message: String) : RestoreResult()
    }

    /**
     * 从 JSON 备份 Uri 覆盖式恢复整库 (快照替换，原样还原自增 ID)
     */
    suspend fun importBackup(context: Context, db: AppDatabase, uri: Uri): RestoreResult {
        val text = try {
            context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: return RestoreResult.Invalid("无法读取所选文件")
        } catch (e: Exception) {
            return RestoreResult.Failure("读取文件失败: ${e.message}")
        }

        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            return RestoreResult.Invalid("不是有效的 JSON 备份文件")
        }

        if (root.optString("app") != BACKUP_APP_TAG) {
            return RestoreResult.Invalid("该文件不是 CarroMed 备份文件")
        }
        if (root.optInt("formatVersion", -1) != BACKUP_FORMAT_VERSION) {
            return RestoreResult.Invalid("备份格式版本不兼容，请使用相同版本的应用生成备份")
        }

        return try {
            var medCount = 0
            var recordCount = 0
            db.withTransaction {
                // 清空旧数据 (子表在前，父表在后)
                db.appSettingDao().deleteAllSettings()
                db.inventoryTransactionDao().deleteAllTransactions()
                db.doseRecordDao().deleteAllRecords()
                db.doseSlotDao().deleteAllSlots()
                db.schedulePolicyDao().deleteAllTimes()
                db.schedulePolicyDao().deleteAllPolicies()
                db.medicationDao().deleteAllMedications()

                // 按外键依赖顺序回填 (父表在前，原样保留自增 ID)
                val medications = root.optJSONArray("medications") ?: JSONArray()
                medCount = medications.length()
                db.medicationDao().insertAll((0 until medications.length()).map { i ->
                    val m = medications.getJSONObject(i)
                    MedicationEntity(
                        id = m.getLong("id"),
                        name = m.getString("name"),
                        alias = if (m.isNull("alias")) null else m.getString("alias"),
                        category = m.optString("category", "常备药"),
                        form = m.optString("form", "片剂"),
                        unit = m.optString("unit", "片"),
                        colorHex = m.optString("colorHex", "#2563EB"),
                        iconName = m.optString("iconName", "pill"),
                        defaultDose = m.optDouble("defaultDose", 1.0).toFloat(),
                        description = m.optString("description", ""),
                        precautions = m.optJSONArray("precautions")?.let { arr ->
                            (0 until arr.length()).map { arr.getString(it) }
                        } ?: emptyList(),
                        noticeShort = m.optString("noticeShort", ""),
                        currentStock = m.optDouble("currentStock", 0.0).toFloat(),
                        minStockAlert = m.optDouble("minStockAlert", 0.0).toFloat(),
                        isStockTracked = m.optBoolean("isStockTracked", false),
                        expiryDate = m.optString("expiryDate", ""),
                        isCriticalReminder = m.optBoolean("isCriticalReminder", false),
                        snoozeMinutes = m.optInt("snoozeMinutes", 0),
                        advanceMinutes = m.optInt("advanceMinutes", 0),
                        isPaused = m.optBoolean("isPaused", false),
                        isArchived = m.optBoolean("isArchived", false),
                        createdAt = m.optLong("createdAt", System.currentTimeMillis()),
                        updatedAt = m.optLong("updatedAt", System.currentTimeMillis())
                    )
                })

                val policies = root.optJSONArray("schedulePolicies") ?: JSONArray()
                db.schedulePolicyDao().insertAllPolicies((0 until policies.length()).map { i ->
                    val p = policies.getJSONObject(i)
                    SchedulePolicyEntity(
                        id = p.getLong("id"),
                        medicationId = p.getLong("medicationId"),
                        policyType = com.mcxiaoke.carromed.core.data.model.PolicyType.valueOf(
                            p.optString("policyType", "DAILY")
                        ),
                        intervalDays = p.optInt("intervalDays", 1),
                        daysOfWeek = p.optJSONArray("daysOfWeek")?.let { arr ->
                            (0 until arr.length()).mapNotNull { arr.optInt(it) }
                        } ?: emptyList(),
                        cycleOnDays = p.optInt("cycleOnDays", 0),
                        cycleOffDays = p.optInt("cycleOffDays", 0),
                        startDate = p.getString("startDate"),
                        endDate = if (p.isNull("endDate")) null else p.getString("endDate"),
                        isActive = p.optBoolean("isActive", true),
                        version = p.optInt("version", 1),
                        createdAt = p.optLong("createdAt", System.currentTimeMillis())
                    )
                })

                val times = root.optJSONArray("policyTimes") ?: JSONArray()
                db.schedulePolicyDao().insertTimes((0 until times.length()).map { i ->
                    val t = times.getJSONObject(i)
                    PolicyTimeEntity(
                        id = t.getLong("id"),
                        policyId = t.getLong("policyId"),
                        timeOfDay = t.getString("timeOfDay"),
                        doseAmount = t.optDouble("doseAmount", 1.0).toFloat(),
                        label = t.optString("label", "服药时段"),
                        sortOrder = t.optInt("sortOrder", 0)
                    )
                })

                val slots = root.optJSONArray("doseSlots") ?: JSONArray()
                db.doseSlotDao().insertAll((0 until slots.length()).map { i ->
                    val s = slots.getJSONObject(i)
                    DoseSlotEntity(
                        id = s.getLong("id"),
                        medicationId = s.getLong("medicationId"),
                        policyId = s.getLong("policyId"),
                        scheduledDate = s.getString("scheduledDate"),
                        scheduledTime = s.getString("scheduledTime"),
                        scheduledTs = s.getLong("scheduledTs"),
                        doseAmount = s.optDouble("doseAmount", 1.0).toFloat(),
                        status = com.mcxiaoke.carromed.core.data.model.SlotStatus.valueOf(
                            s.optString("status", "PENDING")
                        ),
                        actualTakenTs = if (s.isNull("actualTakenTs")) null else s.getLong("actualTakenTs"),
                        snoozeUntilTs = if (s.isNull("snoozeUntilTs")) null else s.getLong("snoozeUntilTs"),
                        createdAt = s.optLong("createdAt", System.currentTimeMillis())
                    )
                })

                val records = root.optJSONArray("doseRecords") ?: JSONArray()
                recordCount = records.length()
                db.doseRecordDao().insertAll((0 until records.length()).map { i ->
                    val r = records.getJSONObject(i)
                    DoseRecordEntity(
                        id = r.getLong("id"),
                        slotId = if (r.isNull("slotId")) null else r.getLong("slotId"),
                        medicationId = r.getLong("medicationId"),
                        actualTs = r.getLong("actualTs"),
                        doseTaken = r.optDouble("doseTaken", 1.0).toFloat(),
                        status = RecordStatus.valueOf(r.optString("status", "COMPLETED")),
                        isRetrospective = r.optBoolean("isRetrospective", false),
                        note = if (r.isNull("note")) null else r.getString("note"),
                        createdAt = r.optLong("createdAt", System.currentTimeMillis())
                    )
                })

                val txs = root.optJSONArray("inventoryTransactions") ?: JSONArray()
                db.inventoryTransactionDao().insertAll((0 until txs.length()).map { i ->
                    val t = txs.getJSONObject(i)
                    InventoryTransactionEntity(
                        id = t.getLong("id"),
                        medicationId = t.getLong("medicationId"),
                        recordId = if (t.isNull("recordId")) null else t.getLong("recordId"),
                        changeAmount = t.optDouble("changeAmount", 0.0).toFloat(),
                        balanceAfter = t.optDouble("balanceAfter", 0.0).toFloat(),
                        txType = com.mcxiaoke.carromed.core.data.model.TransactionType.valueOf(
                            t.optString("txType", "CALIBRATION_ADJUST")
                        ),
                        note = if (t.isNull("note")) null else t.getString("note"),
                        batchNumber = if (t.isNull("batchNumber")) null else t.getString("batchNumber"),
                        expiryDate = if (t.isNull("expiryDate")) null else t.getString("expiryDate"),
                        createdAt = t.optLong("createdAt", System.currentTimeMillis())
                    )
                })

                val settings = root.optJSONArray("appSettings") ?: JSONArray()
                db.appSettingDao().insertAll((0 until settings.length()).map { i ->
                    val s = settings.getJSONObject(i)
                    AppSettingEntity(
                        key = s.getString("key"),
                        value = s.getString("value")
                    )
                })
            }
            RestoreResult.Success(medCount, recordCount)
        } catch (e: Exception) {
            RestoreResult.Failure("恢复失败: ${e.message}")
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
