package com.mcxiaoke.carromed.ui.screen.settings

import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcxiaoke.carromed.core.alarm.AlarmReconciler
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.DataExporter
import com.mcxiaoke.carromed.core.data.entity.AppSettingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val snoozeMinutes: Int = 30,
    val soundMode: String = "温和药铃 (推荐)",
    val fullScreenAlert: Boolean = true,
    val nightDnd: Boolean = true,
    val isExporting: Boolean = false
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val settingDao = db.appSettingDao()

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val snooze = settingDao.getValue("snooze_minutes")?.toIntOrNull() ?: 30
            val sound = settingDao.getValue("sound_mode") ?: "温和药铃 (推荐)"
            val fullScreen = settingDao.getValue("full_screen_alert")?.toBoolean() ?: true
            val dnd = settingDao.getValue("night_dnd")?.toBoolean() ?: true

            _uiState.value = SettingsUiState(
                snoozeMinutes = snooze,
                soundMode = sound,
                fullScreenAlert = fullScreen,
                nightDnd = dnd
            )
        }
    }

    fun onSnoozeMinutesChange(minutes: Int) {
        _uiState.value = _uiState.value.copy(snoozeMinutes = minutes)
        viewModelScope.launch {
            settingDao.setSetting(AppSettingEntity("snooze_minutes", minutes.toString()))
        }
    }

    fun onSoundModeChange(sound: String) {
        _uiState.value = _uiState.value.copy(soundMode = sound)
        viewModelScope.launch {
            settingDao.setSetting(AppSettingEntity("sound_mode", sound))
        }
    }

    fun onFullScreenAlertChange(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(fullScreenAlert = enabled)
        viewModelScope.launch {
            settingDao.setSetting(AppSettingEntity("full_screen_alert", enabled.toString()))
        }
    }

    fun onNightDndChange(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(nightDnd = enabled)
        viewModelScope.launch {
            settingDao.setSetting(AppSettingEntity("night_dnd", enabled.toString()))
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
                    Toast.makeText(app, "已导出 ${file.name}，请选择保存或分享方式", Toast.LENGTH_LONG).show()
                }
                DataExporter.shareFile(app, file, "text/csv")
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(app, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(app, "备份已生成 ${file.name}", Toast.LENGTH_LONG).show()
                }
                DataExporter.shareFile(app, file, "application/json")
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(app, "备份失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                _uiState.value = _uiState.value.copy(isExporting = false)
            }
        }
    }

    /** 从 JSON 备份覆盖式恢复整库 */
    fun importBackup(uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val result = DataExporter.importBackup(app, db, uri)
            val message = when (result) {
                is DataExporter.RestoreResult.Success -> {
                    // 恢复后立刻按新数据重排全部闹钟
                    runCatching {
                        AlarmReconciler.rescheduleAll(app, db)
                    }
                    "恢复成功：${result.medications} 种药品、${result.records} 条服药记录已还原"
                }
                is DataExporter.RestoreResult.Invalid -> "无法恢复：${result.reason}"
                is DataExporter.RestoreResult.Failure -> "恢复失败：${result.message}"
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(app, message, Toast.LENGTH_LONG).show()
            }
        }
    }
}
