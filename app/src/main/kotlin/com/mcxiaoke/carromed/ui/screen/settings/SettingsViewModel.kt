package com.mcxiaoke.carromed.ui.screen.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.AppSettingEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
    val snoozeMinutes: Int = 30,
    val soundMode: String = "温和药铃 (推荐)",
    val fullScreenAlert: Boolean = true,
    val nightDnd: Boolean = true
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
}
