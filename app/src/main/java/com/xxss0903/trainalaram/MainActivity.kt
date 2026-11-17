package com.xxss0903.trainalaram

import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.xxss0903.trainalaram.data.WorkoutRepository
import com.xxss0903.trainalaram.databinding.ActivityMainBinding
import com.xxss0903.trainalaram.databinding.DialogDurationSettingsBinding
import com.xxss0903.trainalaram.feedback.FeedbackPlayer
import com.xxss0903.trainalaram.timer.WorkoutPhase
import com.xxss0903.trainalaram.timer.WorkoutTimerEngine

/**
 * 单屏极简训练计时界面：倒计时、组次、主操作按钮占据视觉核心，
 * 任何阶段的主操作都只需一次点击。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: WorkoutRepository
    private lateinit var feedback: FeedbackPlayer
    private lateinit var engine: WorkoutTimerEngine

    private val handler = Handler(Looper.getMainLooper())
    private var ticking = false

    private var lastTimeText = ""
    private var lastRenderedPhase: WorkoutPhase? = null
    private var lastBeepSecond = -1L

    /** 界面刷新间隔：100ms，保证进度反馈与操作响应足够跟手 */
    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!ticking) return
            if (engine.tick()) {
                feedback.onPhaseSwitch()
                persist()
            }
            render()
            handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 运动场景下保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = WorkoutRepository(this)
        feedback = FeedbackPlayer(this)
        engine = WorkoutTimerEngine(
            clock = { SystemClock.elapsedRealtime() },
            wallClock = { System.currentTimeMillis() }
        )

        var restored = false
        repository.loadSnapshot()?.let { snapshot ->
            engine.restore(snapshot)
            restored = engine.phase != WorkoutPhase.IDLE || engine.completedSets > 0
        }
        // 补齐离开期间已经走完的阶段，避免回到前台后才闪现旧状态
        engine.tick()

        binding.btnMain.setOnClickListener { onPrimaryAction() }
        binding.btnSettings.setOnClickListener { showDurationSettings() }
        binding.btnReset.setOnClickListener { confirmReset() }

        render()
        persist()
        if (restored) {
            Toast.makeText(this, R.string.restored_hint, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStart() {
        super.onStart()
        if (!ticking) {
            ticking = true
            handler.post(tickRunnable)
        }
    }

    override fun onPause() {
        super.onPause()
        persist()
    }

    override fun onStop() {
        super.onStop()
        // 停止界面刷新，计时依靠时间戳在回到前台时自动追赶，不产生后台耗电
        ticking = false
        handler.removeCallbacks(tickRunnable)
        persist()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(tickRunnable)
        feedback.release()
    }

    /** 主操作：IDLE 开始 / WORK 完成本组并计数 / REST 跳过休息 / REST_DONE 开始下一组 */
    private fun onPrimaryAction() {
        engine.primaryAction()
        feedback.onSetCounted()
        persist()
        render()
    }

    private fun render() {
        val phase = engine.phase
        if (phase != lastRenderedPhase) {
            lastRenderedPhase = phase
            lastBeepSecond = -1L
            lastTimeText = ""
            bindPhase(phase)
        }

        val remaining = engine.remainingMs()
        val text = formatCountdown(remaining)
        if (text != lastTimeText) {
            lastTimeText = text
            binding.tvTime.text = text
            bindStatistics()
            persist()
            countdownBeep(remaining)
        }
        binding.ring.setProgress(engine.phaseProgress())
    }

    private fun bindPhase(phase: WorkoutPhase) {
        val accentRes = when (phase) {
            WorkoutPhase.WORK -> R.color.accent_work
            WorkoutPhase.REST -> R.color.accent_rest
            else -> R.color.accent_idle
        }
        val accent = ContextCompat.getColor(this, accentRes)

        binding.ring.setRingColor(accent)
        binding.tvPhase.setTextColor(accent)
        binding.tvPhase.setText(
            when (phase) {
                WorkoutPhase.IDLE -> R.string.phase_idle
                WorkoutPhase.WORK -> R.string.phase_work
                WorkoutPhase.REST -> R.string.phase_rest
                WorkoutPhase.REST_DONE -> R.string.phase_rest_done
            }
        )
        binding.btnMain.backgroundTintList = ColorStateList.valueOf(accent)
        binding.btnMain.text = when (phase) {
            WorkoutPhase.IDLE -> getString(R.string.action_start)
            WorkoutPhase.WORK -> getString(R.string.action_finish_set)
            WorkoutPhase.REST -> getString(R.string.action_skip_rest)
            WorkoutPhase.REST_DONE -> getString(R.string.action_start_next, engine.currentSet + 1)
        }
    }

    private fun bindStatistics() {
        binding.tvSetInfo.text = if (engine.phase == WorkoutPhase.REST_DONE) {
            getString(R.string.set_info_next, engine.currentSet + 1)
        } else {
            getString(R.string.set_info_current, engine.currentSet)
        }

        binding.tvStats.text = getString(
            R.string.stats_summary,
            engine.completedSets,
            formatDuration(engine.totalWorkMs()),
            formatDuration(engine.totalRestMs())
        )

        val lastWork = engine.lastWorkRecord()
        binding.tvLastRecord.text = if (lastWork == null) {
            getString(R.string.no_record)
        } else {
            val rest = engine.restRecordOf(lastWork.setIndex)
            getString(
                R.string.last_record,
                formatDuration(lastWork.durationMs),
                if (rest == null) "--:--" else formatDuration(rest.durationMs)
            )
        }
    }

    /** 阶段结束前 3 秒给出提示音，便于低头看表前的预判 */
    private fun countdownBeep(remainingMs: Long) {
        val phase = engine.phase
        if (phase != WorkoutPhase.WORK && phase != WorkoutPhase.REST) return
        val second = (remainingMs + 999L) / 1000L
        if (second in 1..3 && second != lastBeepSecond) {
            lastBeepSecond = second
            feedback.onCountdownTick()
        }
    }

    private fun showDurationSettings() {
        val dialogBinding = DialogDurationSettingsBinding.inflate(layoutInflater)
        dialogBinding.inputWork.setText(engine.workDurationSec.toString())
        dialogBinding.inputRest.setText(engine.restDurationSec.toString())

        dialogBinding.chipPresets.setOnCheckedChangeListener { group, checkedId ->
            val tag = group.findViewById<View>(checkedId)?.tag as? String
                ?: return@setOnCheckedChangeListener
            val preset = tag.split('/')
            if (preset.size == 2) {
                dialogBinding.inputWork.setText(preset[0])
                dialogBinding.inputRest.setText(preset[1])
            }
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val work = dialogBinding.inputWork.text.toString().toIntOrNull()
                val rest = dialogBinding.inputRest.text.toString().toIntOrNull()
                if (work == null || rest == null ||
                    work < WorkoutTimerEngine.MIN_WORK_SEC || work > WorkoutTimerEngine.MAX_SEC ||
                    rest < WorkoutTimerEngine.MIN_REST_SEC || rest > WorkoutTimerEngine.MAX_SEC
                ) {
                    Toast.makeText(this, R.string.settings_invalid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                engine.setDurations(work, rest)
                persist()
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle(R.string.reset_title)
            .setMessage(R.string.reset_message)
            .setPositiveButton(R.string.reset_confirm) { _, _ ->
                engine.reset()
                persist()
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun persist() {
        repository.saveSnapshot(engine.snapshot())
    }

    private companion object {
        const val TICK_INTERVAL_MS = 100L
    }
}
