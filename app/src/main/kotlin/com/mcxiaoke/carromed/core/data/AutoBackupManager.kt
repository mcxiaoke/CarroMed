package com.mcxiaoke.carromed.core.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.mcxiaoke.carromed.core.data.entity.AppSettingEntity
import com.mcxiaoke.carromed.core.domain.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

/**
 * 自动备份管理器。
 *
 * 核心职责：
 * 1. 每日首次打开 App 时，将全量数据库自动备份至用户指定的 SAF 目录。
 * 2. 严格遵循"未启用或未选目录时不备份"。
 * 3. 滚动保留最新的 10 份自动备份，自动清理旧文件。
 */
object AutoBackupManager {

    private const val TAG = "AutoBackupManager"

    const val KEY_AUTO_BACKUP_ENABLED = "auto_backup_enabled"
    const val KEY_AUTO_BACKUP_TREE_URI = "auto_backup_tree_uri"
    const val KEY_AUTO_BACKUP_DIR_NAME = "auto_backup_dir_name"
    const val KEY_AUTO_BACKUP_LAST_DATE = "auto_backup_last_date"
    const val KEY_AUTO_BACKUP_LAST_TIME = "auto_backup_last_time"

    const val MAX_AUTO_BACKUP_COUNT = 10
    const val AUTO_BACKUP_PREFIX = "CarroMed_AutoBackup_"

    sealed interface AutoBackupResult {
        data object Disabled : AutoBackupResult
        data object NoDirectoryConfigured : AutoBackupResult
        data object PermissionRevoked : AutoBackupResult
        data object AlreadyBackedUpToday : AutoBackupResult
        data class Success(val fileName: String, val purgedCount: Int) : AutoBackupResult
        data class Failure(val error: Throwable) : AutoBackupResult
    }

    /**
     * 检查用户是否给 treeUri 授予了持久化写权限。
     */
    fun isTreeUriPermissionValid(context: Context, treeUri: Uri): Boolean {
        return runCatching {
            context.contentResolver.persistedUriPermissions.any {
                it.uri == treeUri && it.isWritePermission
            }
        }.getOrDefault(false)
    }

    /**
     * 获取用户选择的目录可读显示名称。
     */
    fun getDirectoryDisplayName(context: Context, treeUri: Uri): String {
        return runCatching {
            val docUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            )
            context.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)
                } else null
            }
        }.getOrNull()
            ?: treeUri.lastPathSegment?.substringAfterLast(':')
            ?: treeUri.path
            ?: "已选目录"
    }

    /**
     * 检查并执行每日自动备份。
     *
     * @param todaySupplier 供测试注入自定义日期
     */
    suspend fun performAutoBackupIfNeeded(
        context: Context,
        db: AppDatabase,
        todaySupplier: () -> LocalDate = { LocalDate.now() }
    ): AutoBackupResult = withContext(Dispatchers.IO) {
        val settingDao = db.appSettingDao()
        val enabled = settingDao.getValue(KEY_AUTO_BACKUP_ENABLED)?.toBoolean() ?: false
        if (!enabled) {
            return@withContext AutoBackupResult.Disabled
        }

        val treeUriStr = settingDao.getValue(KEY_AUTO_BACKUP_TREE_URI)
        if (treeUriStr.isNullOrBlank()) {
            AppLog.w(TAG, "auto backup enabled but no directory configured")
            return@withContext AutoBackupResult.NoDirectoryConfigured
        }

        val treeUri = runCatching { Uri.parse(treeUriStr) }.getOrNull()
        if (treeUri == null || !isTreeUriPermissionValid(context, treeUri)) {
            AppLog.w(TAG, "auto backup directory permission missing or revoked: $treeUriStr")
            return@withContext AutoBackupResult.PermissionRevoked
        }

        val today = todaySupplier().toString()
        val lastDate = settingDao.getValue(KEY_AUTO_BACKUP_LAST_DATE)
        if (lastDate == today) {
            return@withContext AutoBackupResult.AlreadyBackedUpToday
        }

        runCatching {
            val backup = DataExporter.buildBackup(db)
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
            val fileName = "$AUTO_BACKUP_PREFIX$timestamp.cbf"

            val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            )
            val fileUri = DocumentsContract.createDocument(
                context.contentResolver,
                parentDocUri,
                "application/octet-stream",
                fileName
            ) ?: throw IOException("Failed to create document: $fileName")

            context.contentResolver.openOutputStream(fileUri)?.use { out ->
                DataExporter.writeBackupToStream(backup, out)
            } ?: throw IOException("Failed to open output stream: $fileUri")

            // 记录备份成功状态
            val nowMs = System.currentTimeMillis()
            settingDao.setSetting(AppSettingEntity(KEY_AUTO_BACKUP_LAST_DATE, today, nowMs))
            settingDao.setSetting(AppSettingEntity(KEY_AUTO_BACKUP_LAST_TIME, nowMs.toString(), nowMs))

            AppLog.i(TAG, "auto backup succeeded: $fileName")

            // 滚动清理超出配额的旧备份
            val purged = purgeOldBackups(context, treeUri, MAX_AUTO_BACKUP_COUNT)
            AutoBackupResult.Success(fileName, purged)
        }.getOrElse { e ->
            AppLog.w(TAG, "auto backup failed", e)
            AutoBackupResult.Failure(e)
        }
    }

    private data class AutoBackupFileRecord(
        val documentId: String,
        val displayName: String,
        val lastModified: Long
    )

    /**
     * 滚动清理旧自动备份，仅保留最新的 [maxCount] 个。
     * 只清理符合 [AUTO_BACKUP_PREFIX] 前缀的文件，绝不误触用户其他文件。
     */
    suspend fun purgeOldBackups(
        context: Context,
        treeUri: Uri,
        maxCount: Int = MAX_AUTO_BACKUP_COUNT
    ): Int = withContext(Dispatchers.IO) {
        runCatching {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            )
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            )
            val backups = mutableListOf<AutoBackupFileRecord>()
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val timeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol) ?: continue
                    if (name.startsWith(AUTO_BACKUP_PREFIX) && (name.endsWith(".cbf") || name.endsWith(".json"))) {
                        val docId = cursor.getString(idCol)
                        val modTime = cursor.getLong(timeCol)
                        backups.add(AutoBackupFileRecord(docId, name, modTime))
                    }
                }
            }

            // 文件名自带 yyyyMMdd_HHmmss，字典序降序即为时间降序
            backups.sortWith(
                compareByDescending<AutoBackupFileRecord> { it.displayName }
                    .thenByDescending { it.lastModified }
            )

            var purgedCount = 0
            if (backups.size > maxCount) {
                val toDelete = backups.drop(maxCount)
                for (record in toDelete) {
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, record.documentId)
                    if (DocumentsContract.deleteDocument(context.contentResolver, docUri)) {
                        purgedCount++
                        AppLog.i(TAG, "purged old auto backup: ${record.displayName}")
                    }
                }
            }
            purgedCount
        }.getOrDefault(0)
    }
}
