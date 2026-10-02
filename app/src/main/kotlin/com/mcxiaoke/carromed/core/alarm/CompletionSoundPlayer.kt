package com.mcxiaoke.carromed.core.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.mcxiaoke.carromed.R
import com.mcxiaoke.carromed.core.domain.AppLog

/**
 * 服药打卡完成提示音与触感反馈播放器 (参考 TickTick 交互)。
 *
 * 核心规则：
 * 1. 仅在系统**非静音**模式 (RINGER_MODE_NORMAL) 下播放声音提示；静音/振动模式下保持静音。
 * 2. 检查用户设置中的完成提示音开关 (默认 "ding"，可选 "none") 与触感反馈开关 (默认 true)。
 * 3. 采用 SoundPool 低延迟异步播放，故障安全降级，绝不向外抛出异常。
 */
object CompletionSoundPlayer {

    private const val TAG = "CompletionSoundPlayer"

    private var soundPool: SoundPool? = null
    private var dingSoundId: Int = 0
    private var isLoaded: Boolean = false
    private val lock = Any()

    /** 预加载音效资源 (在 Application 初始化或首次使用时调用) */
    fun prepare(context: Context) {
        synchronized(lock) {
            if (soundPool != null) return
            try {
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                val pool = SoundPool.Builder()
                    .setMaxStreams(2)
                    .setAudioAttributes(attributes)
                    .build()
                pool.setOnLoadCompleteListener { _, sampleId, status ->
                    if (status == 0 && sampleId == dingSoundId) {
                        isLoaded = true
                    }
                }
                dingSoundId = pool.load(context.applicationContext, R.raw.sound_ding, 1)
                soundPool = pool
            } catch (t: Throwable) {
                AppLog.w(TAG, "failed to initialize SoundPool", t)
            }
        }
    }

    /**
     * 播放完成提示音并触发触感振动。
     *
     * @param soundEnabled 是否启用了提示音 (对应设置项 completion_sound != "none")
     * @param hapticEnabled 是否启用了轻微振感 (对应设置项 completion_haptic)
     */
    fun play(
        context: Context,
        soundEnabled: Boolean = true,
        hapticEnabled: Boolean = true
    ) {
        val appContext = context.applicationContext

        // 1. 触感振动 (可在非静音及振动模式下触发)
        if (hapticEnabled) {
            triggerHapticFeedback(appContext)
        }

        // 2. 声音提示 (严格检查：未静音 + 设置开启)
        if (!soundEnabled) return

        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
            // 系统处于静音或震动模式，不发声
            return
        }

        synchronized(lock) {
            if (soundPool == null) {
                prepare(appContext)
            }
            val pool = soundPool
            if (pool != null && isLoaded && dingSoundId != 0) {
                val streamId = pool.play(dingSoundId, 1.0f, 1.0f, 1, 0, 1.0f)
                if (streamId != 0) return
            }
        }

        // 兜底：若 SoundPool 尚未加载完成或失败，使用 ToneGenerator 播放一次温和短音
        try {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
                .startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        } catch (t: Throwable) {
            AppLog.w(TAG, "ToneGenerator fallback failed", t)
        }
    }

    private fun triggerHapticFeedback(context: Context) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(30L, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(30L)
                }
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "vibration failed", t)
        }
    }
}
