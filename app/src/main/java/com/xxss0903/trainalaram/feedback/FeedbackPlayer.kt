package com.xxss0903.trainalaram.feedback

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * 阶段切换的听觉 + 触觉双重提示。
 * 音调由系统 ToneGenerator 合成，无需额外音频资源。
 */
class FeedbackPlayer(context: Context) {

    private val toneGenerator: ToneGenerator? =
        runCatching { ToneGenerator(AudioManager.STREAM_ALARM, TONE_VOLUME) }.getOrNull()

    private val vibrator: Vibrator? =
        runCatching { context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator }.getOrNull()

    private var released = false

    /** 阶段切换：锻炼开始 / 休息开始 / 休息结束 */
    fun onPhaseSwitch() {
        beep(ToneGenerator.TONE_PROP_BEEP2, 350)
        vibrate(longArrayOf(0L, 140L, 90L, 140L))
    }

    /** 主按钮触发计数 */
    fun onSetCounted() {
        beep(ToneGenerator.TONE_PROP_ACK, 180)
        vibrate(longArrayOf(0L, 60L))
    }

    /** 单个动作数据保存成功 */
    fun onExerciseSaved() {
        beep(ToneGenerator.TONE_PROP_ACK, 260)
        vibrate(longArrayOf(0L, 80L, 70L, 80L))
    }

    /** 整个训练会话完成 */
    fun onSessionCompleted() {
        beep(ToneGenerator.TONE_PROP_BEEP2, 500)
        vibrate(longArrayOf(0L, 200L, 120L, 200L, 120L, 300L))
    }

    /** 保存校验未通过等异常提示 */
    fun onWarning() {
        beep(ToneGenerator.TONE_PROP_NACK, 400)
        vibrate(longArrayOf(0L, 300L))
    }

    /** 阶段结束前 3 秒的提示音 */
    fun onCountdownTick() {
        beep(ToneGenerator.TONE_PROP_BEEP, 90)
    }

    fun release() {
        if (released) return
        released = true
        runCatching { toneGenerator?.release() }
    }

    private fun beep(tone: Int, durationMs: Int) {
        if (released) return
        runCatching { toneGenerator?.startTone(tone, durationMs) }
    }

    @Suppress("DEPRECATION")
    private fun vibrate(pattern: LongArray) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                v.vibrate(pattern, -1)
            }
        }
    }

    private companion object {
        const val TONE_VOLUME = 90
    }
}
