package com.mcxiaoke.carromed.ui.screen.settings

import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcxiaoke.carromed.core.alarm.AlarmReconciler
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.DataExporter
import com.mcxiaoke.carromed.R
import com.mcxiaoke.carromed.core.data.entity.AppSettingEntity
import com.mcxiaoke.carromed.core.domain.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val snoozeMinutes: Int = 30,
    val nightDnd: Boolean = true,
    val nightDndStart: String = "23:00",
    val nightDndEnd: String = "07:00",
    val completionSound: String = "ding",
    val completionHaptic: Boolean = true,
    val repeatReminderEnabled: Boolean = true,
    val repeatIntervalMinutes: Int = 30,
    val repeatMaxCount: Int = 3,
    val isExporting: Boolean = false,
    /** 正在解析所选备份文件（此时尚未碰数据库） */
    val isInspectingBackup: Boolean = false,
    /** 正在执行覆盖式恢复 */
    val isRestoring: Boolean = false,
    /** 本机导出目录里的备份（用于「从本机备份恢复」，绕开 SAF 的目录限制） */
    val localBackups: List<DataExporter.LocalBackupInfo> = emptyList(),
    /** 是否正在显示本机备份列表对话框 */
    val showLocalBackupPicker: Boolean = false,
    /**
     * 已解析、等待用户确认的备份。
     *
     * 非 null 即表示确认对话框该弹出来了 —— 用"待确认的备份"本身作为 UI 状态，
     * 就不必再单独维护一个 `showDialog: Boolean`（那种写法迟早两边不同步）。
     *
     * [RestoreSource] 是 sealed：Uri 来自系统文件选择器，File 来自本机导出目录。
     */
    val pendingRestore: Pair<DataExporter.BackupPreview, RestoreSource>? = null
)

/** 恢复来源。两个变体最终都汇入 `DataExporter` 的同一条恢复路径，只有"怎么读到文本"不同。 */
sealed interface RestoreSource {
    data class FromUri(val uri: Uri) : RestoreSource
    data class FromFile(val file: java.io.File) : RestoreSource
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        const val TAG = "SettingsViewModel"
    }

    private val db = AppDatabase.getInstance(application)
    private val settingDao = db.appSettingDao()

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val snooze = settingDao.getValue(com.mcxiaoke.carromed.core.alarm.ReminderSettings.KEY_SNOOZE_MINUTES)?.toIntOrNull() ?: 30
            val dnd = settingDao.getValue(com.mcxiaoke.carromed.core.alarm.ReminderSettings.KEY_NIGHT_DND)?.toBoolean() ?: true
            val dndStart = settingDao.getValue(com.mcxiaoke.carromed.core.alarm.ReminderSettings.KEY_NIGHT_DND_START) ?: "23:00"
            val dndEnd = settingDao.getValue(com.mcxiaoke.carromed.core.alarm.ReminderSettings.KEY_NIGHT_DND_END) ?: "07:00"
            val sound = settingDao.getValue(com.mcxiaoke.carromed.core.alarm.ReminderSettings.KEY_COMPLETION_SOUND) ?: "ding"
            val haptic = settingDao.getValue(com.mcxiaoke.carromed.core.alarm.ReminderSettings.KEY_COMPLETION_HAPTIC)?.toBoolean() ?: true
            val repeatEnabled = settingDao.getValue(com.mcxiaoke.carromed.core.alarm.ReminderSettings.KEY_REPEAT_REMINDER_ENABLED)?.toBoolean() ?: true
            val repeatInterval = settingDao.getValue(com.mcxiaoke.carromed.core.alarm.ReminderSettings.KEY_REPEAT_REMINDER_INTERVAL)?.toIntOrNull() ?: 30
            val repeatMax = settingDao.getValue(com.mcxiaoke.carromed.core.alarm.ReminderSettings.KEY_REPEAT_REMINDER_MAX_COUNT)?.toIntOrNull() ?: 3

            _uiState.value = SettingsUiState(
                snoozeMinutes = snooze,
                nightDnd = dnd,
                nightDndStart = dndStart,
                nightDndEnd = dndEnd,
                completionSound = sound,
                completionHaptic = haptic,
                repeatReminderEnabled = repeatEnabled,
                repeatIntervalMinutes = repeatInterval,
                repeatMaxCount = repeatMax
            )
        }
    }

    fun onSnoozeMinutesChange(minutes: Int) {
        AppLog.i(TAG, "snoozeMinutes changed: $minutes")
        _uiState.value = _uiState.value.copy(snoozeMinutes = minutes)
        viewModelScope.launch {
            settingDao.setSetting(AppSettingEntity(com.mcxiaoke.carromed.core.alarm.ReminderSettings.KEY_SNOOZE_MINUTES, minutes.toString()))
        }
    }

    fun onNightDndChange(enabled: Boolean) {
        AppLog.i(TAG, "nightDnd changed: $enabled")
        _uiState.value = _uiState.value.copy(nightDnd = enabled)
        viewModelScope.launch {
            settingDao.setSetting(AppSettingEntity(com.mcxiaoke.carromed.core.alarm.ReminderSettings.KEY_NIGHT_DND, enabled.toString()))
        }
    }

    fun onNightDndTimeChange(start: String, end: String) {
        AppLog.i(TAG, "nightDndTime changed: $start ~ $end")
        _uiState.value = _uiState.value.copy(nightDndStart = start, nightDndEnd = end)
        viewModelScope.launch {
            settingDao.setSetting(AppSettingEntity(com.mcxiaoke.carromed.core.alarm.ReminderSettings.KEY_NIGHT_DND_START, start))
            settingDao.setSetting(AppSettingEntity(com.mcxiaoke.carromed.core.alarm.ReminderSettings.KEY_NIGHT_DND_END, end))
        }
    }

    fun onCompletionSoundChange(sound: String) {
        AppLog.i(TAG, "completionSound changed: $sound")
        _uiState.value = _uiState.value.copy(completionSound = sound)
        viewModelScope.launch {
            settingDao.setSetting(AppSettingEntity(com.mcxiaoke.carromed.core.alarm.ReminderSettings.KEY_COMPLETION_SOUND, sound))
        }
    }

    fun onCompletionHapticChange(enabled: Boolean) {
        AppLog.i(TAG, "completionHaptic changed: $enabled")
        _uiState.value = _uiState.value.copy(completionHaptic = enabled)
        viewModelScope.launch {
            settingDao.setSetting(AppSettingEntity(com.mcxiaoke.carromed.core.alarm.ReminderSettings.KEY_COMPLETION_HAPTIC, enabled.toString()))
        }
    }

    fun onRepeatReminderEnabledChange(enabled: Boolean) {
        AppLog.i(TAG, "repeatReminderEnabled changed: $enabled")
        _uiState.value = _uiState.value.copy(repeatReminderEnabled = enabled)
        viewModelScope.launch {
            settingDao.setSetting(AppSettingEntity(com.mcxiaoke.carromed.core.alarm.ReminderSettings.KEY_REPEAT_REMINDER_ENABLED, enabled.toString()))
        }
    }

    fun onRepeatIntervalMinutesChange(minutes: Int) {
        AppLog.i(TAG, "repeatIntervalMinutes changed: $minutes")
        _uiState.value = _uiState.value.copy(repeatIntervalMinutes = minutes)
        viewModelScope.launch {
            settingDao.setSetting(AppSettingEntity(com.mcxiaoke.carromed.core.alarm.ReminderSettings.KEY_REPEAT_REMINDER_INTERVAL, minutes.toString()))
        }
    }

    fun onRepeatMaxCountChange(count: Int) {
        AppLog.i(TAG, "repeatMaxCount changed: $count")
        _uiState.value = _uiState.value.copy(repeatMaxCount = count)
        viewModelScope.launch {
            settingDao.setSetting(AppSettingEntity(com.mcxiaoke.carromed.core.alarm.ReminderSettings.KEY_REPEAT_REMINDER_MAX_COUNT, count.toString()))
        }
    }

    /** 导出服药明细 CSV：生成文件后弹出系统分享面板 */
    fun exportCsv() {
        if (_uiState.value.isExporting) return
        _uiState.value = _uiState.value.copy(isExporting = true)
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                val file = DataExporter.exportDoseRecordsCsv(app, db)
                withContext(Dispatchers.Main) {
                    Toast.makeText(app, app.getString(R.string.set_export_csv_done, file.name), Toast.LENGTH_LONG).show()
                }
                if (!DataExporter.shareFile(app, file, "text/csv")) {
                    // 分享面板没能启动：不能让"已导出"提示变成空头支票（L11）
                    withContext(Dispatchers.Main) {
                        Toast.makeText(app, R.string.set_share_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                // 吞异常降级成 Toast 的地方必须留痕（PLAN-LOGGING G4）
                AppLog.w(TAG, "exportCsv failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(app, app.getString(R.string.set_export_failed, e.message), Toast.LENGTH_SHORT).show()
                }
            } finally {
                _uiState.value = _uiState.value.copy(isExporting = false)
            }
        }
    }

    /** 生成全量数据库 JSON 备份并分享 */
    fun exportBackup() {
        if (_uiState.value.isExporting) return
        _uiState.value = _uiState.value.copy(isExporting = true)
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                val file = DataExporter.exportFullBackupJson(app, db)
                withContext(Dispatchers.Main) {
                    Toast.makeText(app, app.getString(R.string.set_backup_done, file.name), Toast.LENGTH_LONG).show()
                }
                if (!DataExporter.shareFile(app, file, "application/json")) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(app, R.string.set_share_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                AppLog.w(TAG, "exportBackup failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(app, app.getString(R.string.set_backup_failed, e.message), Toast.LENGTH_SHORT).show()
                }
            } finally {
                _uiState.value = _uiState.value.copy(isExporting = false)
            }
        }
    }


    /**
     * 覆盖式恢复的两步走（P1-14 / `FINAL-PRODUCT:158`）。
     *
     * 旧实现是"选完文件即刻整库清空"，而用户选错文件是必然会发生的事。
     * 现在拆成：
     * 1. [inspectBackup] / [inspectLocalBackup] —— 只读解析 + 校验，
     *    **数据库分毫未动**，产出带真实数字的预览
     * 2. [confirmRestore] —— 用户看过预览、点了确认，才真正清库
     *
     * 二次确认必须带真实信息才有用：「确定要覆盖吗？」用户只能盲点确定；
     * 「该备份含 4 种药品、2 条服药记录，当前数据将被完全替换」才是决策依据。
     */
    fun inspectBackup(uri: Uri) {
        val app = getApplication<Application>()
        _uiState.value = _uiState.value.copy(isInspectingBackup = true, pendingRestore = null)
        // ⚠️ 切到 IO（M5-4）。这条路径做**全量 JSON 解析**，主线程上会让 UI 冻结
        // 几百毫秒到数秒（备份越大越久），而用户什么都看不到 —— 因为
        // `isInspectingBackup` 那个转圈此前根本没有任何 UI 消费它。
        viewModelScope.launch(Dispatchers.IO) {
            val result = DataExporter.inspectBackup(app, uri)
            _uiState.value = _uiState.value.copy(
                isInspectingBackup = false,
                pendingRestore = result.fold(
                    onSuccess = { it to RestoreSource.FromUri(uri) },
                    onFailure = { null }
                )
            )
            result.exceptionOrNull()?.let { showRestoreError(it) }
        }
    }

    /**
     * 打开「本机导出记录」列表。
     *
     * 为什么需要它：备份写在 `getExternalFilesDir(DOCUMENTS)/exports`，即
     * `/sdcard/Android/data/<pkg>/...`，**SAF 明确不允许浏览这个目录**。
     * 于是"导出了却选不回来"，除非用户先把文件分享出去。
     * 对一个物理断网的 App，"必须先分享出去才能导回来"不可接受。
     */
    fun openLocalBackupPicker() {
        val app = getApplication<Application>()
        _uiState.value = _uiState.value.copy(
            localBackups = DataExporter.listLocalBackups(app),
            showLocalBackupPicker = true
        )
    }

    fun closeLocalBackupPicker() {
        _uiState.value = _uiState.value.copy(showLocalBackupPicker = false)
    }

    /**
     * 解析本机备份文件。
     *
     * ⚠️ 切到 IO（M5-4）。旧实现**连 `suspend` 都不是**——直接在主线程读文件 + 解析 JSON。
     * Uri 路径至少还有 `viewModelScope`（默认 Main），本机路径连那层都没有。
     * 两条路径现在口径一致。
     */
    fun inspectLocalBackup(file: java.io.File) {
        _uiState.value = _uiState.value.copy(
            isInspectingBackup = true,
            pendingRestore = null,
            showLocalBackupPicker = false
        )
        viewModelScope.launch(Dispatchers.IO) {
            val result = DataExporter.inspectLocalBackup(getApplication(), file)
            _uiState.value = _uiState.value.copy(
                isInspectingBackup = false,
                pendingRestore = result.fold(
                    onSuccess = { it to RestoreSource.FromFile(file) },
                    onFailure = { null }
                )
            )
            result.exceptionOrNull()?.let { showRestoreError(it) }
        }
    }

    private fun showRestoreError(t: Throwable) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            withContext(Dispatchers.Main) {
                Toast.makeText(app, app.getString(R.string.set_restore_error, t.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 取消二次确认，什么都不做 */
    fun cancelRestore() {
        _uiState.value = _uiState.value.copy(pendingRestore = null)
    }

    /** 用户已确认，执行真正的覆盖式恢复 */
    fun confirmRestore() {
        val pending = _uiState.value.pendingRestore ?: return
        val source = pending.second
        val app = getApplication<Application>()
        _uiState.value = _uiState.value.copy(pendingRestore = null, isRestoring = true)
        // 全程 IO（M5）：importBackup 内部是大 JSON 解析 + 快照文件写，
        // rescheduleAll 也自切 IO；旧实现在 Main 上裸跑会冻结界面。
        viewModelScope.launch(Dispatchers.IO) {
            val result = when (source) {
                is RestoreSource.FromUri -> DataExporter.importBackup(app, db, source.uri)
                is RestoreSource.FromFile -> DataExporter.importLocalBackup(app, db, source.file)
            }
            val message = when (result) {
                is DataExporter.RestoreResult.Success -> {
                    // 恢复后立刻按新数据重排全部闹钟。
                    // 重排失败不推翻恢复本身（数据已还原），但必须让用户知道
                    // 提醒可能不响（H3）——旧实现把失败咽成一句日志然后照报"恢复成功"。
                    val rescheduled = runCatching { AlarmReconciler.rescheduleAll(app, db) }
                        .onFailure { AppLog.e(TAG, "rescheduleAll after restore failed", it) }
                        .isSuccess
                    val snap = result.snapshotFile
                        ?.let { app.getString(R.string.set_restore_snapshot_fmt, it.substringAfterLast('/')) }
                        ?: app.getString(R.string.set_restore_no_snapshot)
                    val base = app.getString(R.string.set_restore_success_fmt, result.medications, result.records) + snap
                    if (rescheduled) base
                    else base + "\n" + app.getString(R.string.set_restore_schedule_failed)
                }
                is DataExporter.RestoreResult.Invalid -> app.getString(R.string.set_restore_error, result.reason)
                is DataExporter.RestoreResult.Failure -> app.getString(R.string.set_restore_failed, result.message)
            }
            _uiState.value = _uiState.value.copy(isRestoring = false)
            withContext(Dispatchers.Main) {
                Toast.makeText(app, message, Toast.LENGTH_LONG).show()
            }
        }
    }
}
