package com.mcxiaoke.carromed.core.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
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

    /** ToneGenerator 兜底短音的长度（毫秒）。 */
    private const val FALLBACK_TONE_MS = 100

    enum class SoundItem(
        val key: String,
        @androidx.annotation.RawRes val resId: Int,
        val volumeScale: Float
    ) {
        DING("ding", R.raw.sound_ding, 0.50f),
        DROP("drop", R.raw.sound_drop, 0.75f),
        CLICK("click", R.raw.sound_click, 0.65f),
        CHIME("chime", R.raw.sound_chime, 0.45f);

        companion object {
            fun fromKey(key: String): SoundItem =
                entries.firstOrNull { it.key == key } ?: CHIME
        }
    }

    private var soundPool: SoundPool? = null
    private val soundIdMap = mutableMapOf<String, Int>()
    private val loadedSoundIds = mutableSetOf<Int>()
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
                    .setMaxStreams(4)
                    .setAudioAttributes(attributes)
                    .build()
                pool.setOnLoadCompleteListener { _, sampleId, status ->
                    if (status == 0) {
                        synchronized(lock) {
                            loadedSoundIds.add(sampleId)
                        }
                    }
                }
                val appContext = context.applicationContext
                for (item in SoundItem.entries) {
                    val sId = pool.load(appContext, item.resId, 1)
                    soundIdMap[item.key] = sId
                }
                soundPool = pool
            } catch (t: Throwable) {
                AppLog.w(TAG, "failed to initialize SoundPool", t)
            }
        }
    }

    /**
     * 播放完成提示音并触发触感振动。
     *
     * @param soundKey 提示音类型 ("ding", "drop", "click", "chime", "none")
     * @param soundEnabled 是否启用了提示音 (对应设置项 completion_sound != "none")
     * @param hapticEnabled 是否启用了轻微振感 (对应设置项 completion_haptic)
     */
    fun play(
        context: Context,
        soundKey: String = "chime",
        soundEnabled: Boolean = true,
        hapticEnabled: Boolean = true
    ) {
        val appContext = context.applicationContext

        // 1. 触感振动 (可在非静音及振动模式下触发)
        if (hapticEnabled) {
            triggerHapticFeedback(appContext)
        }

        // 2. 声音提示 (严格检查：未静音 + 设置开启且不是 "none")
        if (!soundEnabled || soundKey == "none") return

        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
            // 系统处于静音或震动模式，不发声
            return
        }

        val soundItem = SoundItem.fromKey(soundKey)
        val vol = soundItem.volumeScale

        synchronized(lock) {
            if (soundPool == null) {
                prepare(appContext)
            }
            val pool = soundPool
            val sId = soundIdMap[soundItem.key] ?: 0
            if (pool != null && sId != 0 && loadedSoundIds.contains(sId)) {
                val streamId = pool.play(sId, vol, vol, 1, 0, 1.0f)
                if (streamId != 0) return
            }
        }

        // 兜底：若 SoundPool 尚未加载完成或失败，使用 ToneGenerator 播放一次温和短音。
        //
        // ⚠️ 必须 release：ToneGenerator 内部持有 AudioTrack，只 startTone 不管它
        // 就是每次走这条兜底都泄漏一个（用户刚打开 App 立即打卡时 SoundPool 必然还没
        // load 完，所以这条兜底并不罕见）。
        // 用 `setOnToneStoppedListener` 在音结束的回调里释放，而不是 startTone 之后
        // 立刻 release —— 那会把音当场掐掉；起音失败则走 catch 手动释放。
        // 释放时机不能"startTone 之后立刻 release"——那会把音当场掐掉。
        // 也不用 `setOnToneStoppedListener`：本机 android-35 的 android.jar 里
        // **没有** 这个方法（`javap android.media.ToneGenerator` 只有 startTone / release）。
        // 折中：按音长 + 余量投递一次延迟释放。
        var tone: ToneGenerator? = null
        try {
            val t = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60)
            tone = t
            t.startTone(ToneGenerator.TONE_PROP_BEEP, FALLBACK_TONE_MS)
            Handler(Looper.getMainLooper()).postDelayed(
                { runCatching { t.release() } },
                FALLBACK_TONE_MS + 200L
            )
        } catch (t: Throwable) {
            AppLog.w(TAG, "ToneGenerator fallback failed", t)
            // 起音失败也必须释放，否则这次尝试的 AudioTrack 就留在那里了
            runCatching { tone?.release() }
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
