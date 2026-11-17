package com.xxss0903.trainalaram

import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.xxss0903.trainalaram.data.WorkoutRepository
import com.xxss0903.trainalaram.databinding.ActivityMainBinding
import com.xxss0903.trainalaram.databinding.DialogExerciseEditBinding
import com.xxss0903.trainalaram.databinding.DialogPlanBinding
import com.xxss0903.trainalaram.databinding.DialogSetEditBinding
import com.xxss0903.trainalaram.databinding.DialogSetRecordsBinding
import com.xxss0903.trainalaram.databinding.DialogSettingsBinding
import com.xxss0903.trainalaram.databinding.ItemExerciseBinding
import com.xxss0903.trainalaram.databinding.ItemSetRecordBinding
import com.xxss0903.trainalaram.feedback.FeedbackPlayer
import com.xxss0903.trainalaram.timer.EngineEvent
import com.xxss0903.trainalaram.timer.Exercise
import com.xxss0903.trainalaram.timer.ExerciseMode
import com.xxss0903.trainalaram.timer.ExerciseRun
import com.xxss0903.trainalaram.timer.ExerciseStatus
import com.xxss0903.trainalaram.timer.SessionState
import com.xxss0903.trainalaram.timer.SetExecution
import com.xxss0903.trainalaram.timer.WorkoutLimits
import com.xxss0903.trainalaram.timer.WorkoutPhase
import com.xxss0903.trainalaram.timer.WorkoutSessionEngine
import kotlin.math.roundToInt

/**
 * 单屏极简训练界面：当前动作、组次、倒计时与主操作按钮占据视觉核心，
 * 任何阶段的主操作都只需一次点击；计划 / 组次记录 / 设置通过顶部入口与次按钮进入。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: WorkoutRepository
    private lateinit var feedback: FeedbackPlayer
    private lateinit var engine: WorkoutSessionEngine

    private val handler = Handler(Looper.getMainLooper())
    private var ticking = false

    // 渲染缓存：避免每 100ms 重复 setText 触发无谓的重新布局
    private var lastTimeText = ""
    private var lastHeaderText = ""
    private var lastOverallText = ""
    private var lastOverallProgress = -1
    private var lastSetInfoText = ""
    private var lastStatsText = ""
    private var lastRecordText = ""
    private var lastMainText = ""
    private var lastPauseText = ""
    private var lastRenderedPhase: WorkoutPhase? = null
    private var lastRenderedPaused = false
    private var lastBeepSecond = -1L

    /** 界面刷新间隔：100ms，保证进度反馈与操作响应足够跟手 */
    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!ticking) return
            val beforeId = engine.currentExerciseId
            val event = engine.tick()
            handleEvent(event, savedNameOf(beforeId))
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
        engine = WorkoutSessionEngine(
            clock = { SystemClock.elapsedRealtime() },
            wallClock = { System.currentTimeMillis() }
        )

        var restored = false
        repository.loadSession()?.let { snapshot ->
            engine.restore(snapshot)
            restored = snapshot.plan.isNotEmpty() &&
                (snapshot.sessionState != SessionState.NOT_STARTED || snapshot.plan.any { it.recordedSets > 0 })
        }
        // 补齐离开期间已经走完的阶段，避免回到前台后才闪现旧状态
        engine.tick()

        binding.btnMain.setOnClickListener { onPrimaryAction() }
        binding.btnPause.setOnClickListener { onTogglePause() }
        binding.btnPlan.setOnClickListener { showPlanDialog() }
        binding.btnSettings.setOnClickListener { showSettingsDialog() }
        binding.btnReset.setOnClickListener { confirmReset() }
        binding.btnRecords.setOnClickListener { showRecordsDialog() }

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

    // ------------------------------------------------------------------ 主流程

    /**
     * 暂停 / 继续：暂停后锻炼与休息的倒计时、次数型动作的秒表全部冻结，
     * 主按钮被锁定，必须继续后才能推进。
     */
    private fun onTogglePause() {
        if (!engine.isPauseAvailable) return
        if (engine.togglePause()) {
            if (engine.paused) feedback.onPaused() else feedback.onResumed()
            persist()
        }
        render()
    }

    private fun onPrimaryAction() {
        if (engine.plan.isEmpty()) {
            showPlanDialog()
            return
        }
        if (engine.sessionState == SessionState.COMPLETED) {
            Toast.makeText(this, R.string.session_completed_toast, Toast.LENGTH_SHORT).show()
            return
        }

        val savingId = engine.currentExerciseId
        val event: EngineEvent = if (engine.phase == WorkoutPhase.SETS_DONE) {
            // 手动确认保存：校验不通过时不推进流程，并明确告知具体问题
            val report = engine.saveCurrentExercise()
            if (!report.saved) {
                feedback.onWarning()
                Toast.makeText(
                    this,
                    getString(R.string.save_failed_toast, report.issues.joinToString("；")),
                    Toast.LENGTH_LONG
                ).show()
                render()
                return
            }
            EngineEvent(
                phaseChanged = true,
                exerciseSaved = true,
                sessionCompleted = engine.sessionState == SessionState.COMPLETED
            )
        } else {
            engine.primaryAction()
        }

        handleEvent(event, savedNameOf(savingId))
        if (!event.any) feedback.onSetCounted()
        persist()
        render()
    }

    private fun handleEvent(event: EngineEvent, savedName: String?) {
        when {
            event.sessionCompleted -> {
                feedback.onSessionCompleted()
                Toast.makeText(this, R.string.session_completed_toast, Toast.LENGTH_LONG).show()
            }
            event.exerciseSaved -> {
                feedback.onExerciseSaved()
                savedName?.let {
                    Toast.makeText(this, getString(R.string.saved_toast, it), Toast.LENGTH_SHORT).show()
                }
            }
            event.phaseChanged -> feedback.onPhaseSwitch()
        }
        if (event.any) persist()
    }

    private fun savedNameOf(id: Long?): String? =
        engine.plan.firstOrNull { it.exercise.id == id }?.exercise?.name

    // -------------------------------------------------------------------- 渲染

    private fun render() {
        val phase = engine.phase
        val paused = engine.paused
        if (phase != lastRenderedPhase || paused != lastRenderedPaused) {
            lastRenderedPhase = phase
            lastRenderedPaused = paused
            lastBeepSecond = -1L
            lastTimeText = ""
            bindPhase(phase)
        }

        bindHeader()
        bindOverall()
        bindStatistics()
        bindMainButton()
        bindPauseButton()
        binding.btnRecords.visibility = if (engine.currentRun == null) View.INVISIBLE else View.VISIBLE

        val text = timerText()
        if (text != lastTimeText) {
            lastTimeText = text
            binding.tvTime.text = text
            countdownBeep(engine.remainingMs())
            persist()
        }
        binding.ring.setProgress(engine.phaseProgress())
    }

    /**
     * 主区大号计时：次数型动作的锻炼阶段没有倒计时，改为正向秒表；
     * 其余阶段仍为倒计时（暂停时由引擎冻结数值）。
     */
    private fun timerText(): String {
        val repsRunning = engine.phase == WorkoutPhase.WORK &&
            engine.currentExercise?.isRepsMode == true
        return if (repsRunning) formatDuration(engine.workElapsedMs()) else formatCountdown(engine.remainingMs())
    }

    private fun bindPhase(phase: WorkoutPhase) {
        // 暂停态优先展示，避免用户误以为计时仍在走
        if (engine.paused) {
            val accent = ContextCompat.getColor(this, R.color.accent_idle)
            binding.ring.setRingColor(accent)
            binding.tvPhase.setTextColor(accent)
            binding.tvPhase.setText(R.string.phase_paused)
            binding.btnMain.backgroundTintList = ColorStateList.valueOf(accent)
            return
        }
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
                WorkoutPhase.SETS_DONE -> R.string.phase_sets_done
            }
        )
        binding.btnMain.backgroundTintList = ColorStateList.valueOf(accent)
    }

    private fun bindHeader() {
        val run = engine.currentRun
        if (run == null) {
            val text = getString(R.string.plan_empty)
            if (text != lastHeaderText) {
                lastHeaderText = text
                binding.tvExercise.text = text
                binding.tvExerciseStatus.visibility = View.GONE
            }
            return
        }
        val header = getString(
            R.string.exercise_header,
            engine.currentIndex + 1,
            engine.plan.size,
            run.exercise.name
        )
        val status = exerciseStatusText(run.status)
        val combined = "$header\u0000$status"
        if (combined == lastHeaderText) return
        lastHeaderText = combined
        binding.tvExercise.text = header
        binding.tvExerciseStatus.visibility = View.VISIBLE
        binding.tvExerciseStatus.text = status
    }

    private fun bindOverall() {
        val progress = (engine.overallProgress() * 100f).roundToInt()
        if (progress != lastOverallProgress) {
            lastOverallProgress = progress
            binding.pbOverall.progress = progress
        }
        lastOverallText = applyText(
            binding.tvOverall,
            getString(R.string.overall_progress, engine.totalRecordedSets, engine.totalTargetSets),
            lastOverallText
        )
    }

    private fun bindStatistics() {
        val run = engine.currentRun
        val setInfo = when {
            run == null -> getString(R.string.no_record)
            engine.phase == WorkoutPhase.REST_DONE ->
                getString(R.string.set_info_next, engine.currentSet, run.exercise.targetSets, run.exercise.targetReps)
            else ->
                getString(R.string.set_info_current, engine.currentSet, run.exercise.targetSets, run.exercise.targetReps)
        }
        lastSetInfoText = applyText(binding.tvSetInfo, setInfo, lastSetInfoText)

        val stats = when {
            run == null -> getString(R.string.no_record)
            else -> getString(
                R.string.stats_current,
                run.completedSets,
                formatDuration(run.totalWorkMs),
                formatDuration(run.totalRestMs)
            )
        }
        lastStatsText = applyText(binding.tvStats, stats, lastStatsText)

        val last = run?.sets?.lastOrNull()
        val lastRecord = if (last == null) {
            getString(R.string.no_record)
        } else {
            getString(
                R.string.last_record,
                formatDuration(last.actualDurationMs),
                formatDuration(last.restAfterMs)
            )
        }
        lastRecordText = applyText(binding.tvLastRecord, lastRecord, lastRecordText)
    }

    private fun bindMainButton() {
        val run = engine.currentRun
        val text = when {
            engine.paused -> getString(R.string.action_paused_hint)
            run == null -> getString(R.string.action_add_exercise)
            engine.sessionState == SessionState.COMPLETED -> getString(R.string.action_all_done)
            else -> when (engine.phase) {
                WorkoutPhase.IDLE -> getString(R.string.action_start_set, engine.currentSet)
                WorkoutPhase.WORK -> getString(R.string.action_finish_set)
                WorkoutPhase.REST -> getString(R.string.action_skip_rest)
                WorkoutPhase.REST_DONE -> getString(R.string.action_start_set, engine.currentSet)
                WorkoutPhase.SETS_DONE ->
                    if (engine.currentIndex >= engine.plan.size - 1) getString(R.string.action_save_finish)
                    else getString(R.string.action_save_next)
            }
        }
        lastMainText = applyText(binding.btnMain, text, lastMainText)

        // 暂停期间锁定主流程：按钮不可点击，必须继续后才能推进
        val enabled = !engine.paused
        binding.btnMain.isEnabled = enabled
        binding.btnMain.alpha = if (enabled) 1f else DISABLED_ALPHA
    }

    private fun bindPauseButton() {
        val available = engine.isPauseAvailable
        binding.btnPause.isEnabled = available
        binding.btnPause.alpha = if (available) 1f else DISABLED_ALPHA

        val text = getString(if (engine.paused) R.string.action_resume else R.string.action_pause)
        if (text != lastPauseText) {
            lastPauseText = text
            binding.btnPause.text = text
            binding.btnPause.setTextColor(
                ContextCompat.getColor(this, if (engine.paused) R.color.accent_work else R.color.accent_idle)
            )
        }
    }

    private fun exerciseStatusText(status: ExerciseStatus): String = getString(
        when (status) {
            ExerciseStatus.NOT_STARTED -> R.string.exercise_status_not_started
            ExerciseStatus.IN_PROGRESS -> R.string.exercise_status_in_progress
            ExerciseStatus.SAVED -> R.string.exercise_status_saved
        }
    )

    /** 阶段结束前 3 秒给出提示音，便于低头看表前的预判 */
    private fun countdownBeep(remainingMs: Long) {
        if (engine.paused) return
        val phase = engine.phase
        if (phase != WorkoutPhase.WORK && phase != WorkoutPhase.REST) return
        val second = (remainingMs + 999L) / 1000L
        if (second in 1..3 && second != lastBeepSecond) {
            lastBeepSecond = second
            feedback.onCountdownTick()
        }
    }

    // ------------------------------------------------------------------ 计划管理

    private fun showPlanDialog() {
        val dialogBinding = DialogPlanBinding.inflate(layoutInflater)

        // applyChange 与 refreshPlanItems 需要互相调用，用可赋值的 lambda 打破声明顺序限制
        var refreshPlanItems: () -> Unit = {}

        val applyChange: () -> Unit = {
            persist()
            render()
            refreshPlanItems()
        }

        refreshPlanItems = {
            val editable = engine.isPlanStructureEditable
            val plan = engine.plan
            dialogBinding.tvPlanHint.visibility = if (editable) View.GONE else View.VISIBLE
            dialogBinding.tvPlanHint.setText(
                if (editable) R.string.plan_locked else R.string.plan_locked_action
            )
            dialogBinding.btnAddExercise.isEnabled = editable
            dialogBinding.btnAddExercise.alpha = if (editable) 1f else DISABLED_ALPHA
            dialogBinding.tvPlanEmpty.visibility = if (plan.isEmpty()) View.VISIBLE else View.GONE
            dialogBinding.svPlan.visibility = if (plan.isEmpty()) View.GONE else View.VISIBLE
            dialogBinding.llPlan.removeAllViews()

            plan.forEachIndexed { index, run ->
                val item = ItemExerciseBinding.inflate(layoutInflater, dialogBinding.llPlan, false)
                item.tvIndex.text = (index + 1).toString()
                item.tvName.text = run.exercise.name
                item.tvStatus.setText(
                    when (run.status) {
                        ExerciseStatus.NOT_STARTED -> R.string.exercise_status_not_started
                        ExerciseStatus.IN_PROGRESS -> R.string.exercise_status_in_progress
                        ExerciseStatus.SAVED -> R.string.exercise_status_saved
                    }
                )
                item.tvStatus.setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        when (run.status) {
                            ExerciseStatus.NOT_STARTED -> R.color.text_secondary
                            ExerciseStatus.IN_PROGRESS -> R.color.accent_work
                            ExerciseStatus.SAVED -> R.color.accent_rest
                        }
                    )
                )
                item.tvDetail.text = if (run.exercise.isRepsMode) {
                    getString(
                        R.string.plan_item_detail_reps,
                        run.exercise.targetSets,
                        run.exercise.targetReps,
                        run.exercise.restSec
                    )
                } else {
                    getString(
                        R.string.plan_item_detail,
                        run.exercise.targetSets,
                        run.exercise.targetReps,
                        run.exercise.workSec,
                        run.exercise.restSec
                    )
                }

                val canUp = editable && index > 0
                val canDown = editable && index < plan.size - 1
                val canEdit = run.status != ExerciseStatus.SAVED
                val canDelete = editable

                item.btnUp.alpha = if (canUp) 1f else DISABLED_ALPHA
                item.btnDown.alpha = if (canDown) 1f else DISABLED_ALPHA
                item.btnEdit.alpha = if (canEdit) 1f else DISABLED_ALPHA
                item.btnDelete.alpha = if (canDelete) 1f else DISABLED_ALPHA

                item.btnUp.setOnClickListener { if (engine.moveExercise(run.exercise.id, -1)) applyChange() }
                item.btnDown.setOnClickListener { if (engine.moveExercise(run.exercise.id, 1)) applyChange() }
                item.btnEdit.setOnClickListener {
                    if (!canEdit) return@setOnClickListener
                    showExerciseEditor(run.exercise) { applyChange() }
                }
                item.btnDelete.setOnClickListener {
                    if (!canDelete) return@setOnClickListener
                    confirmDeleteExercise(run) { if (engine.removeExercise(run.exercise.id)) applyChange() }
                }
                dialogBinding.llPlan.addView(item.root)
            }
            Unit
        }

        dialogBinding.btnAddExercise.setOnClickListener {
            if (!engine.isPlanStructureEditable) return@setOnClickListener
            showExerciseEditor(null) { applyChange() }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.plan_title)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.close, null)
            .create()
        refreshPlanItems()
        dialog.show()
    }

    private fun confirmDeleteExercise(run: ExerciseRun, onConfirm: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(R.string.plan_delete_title)
            .setMessage(getString(R.string.plan_delete_message, run.exercise.name))
            .setPositiveButton(R.string.plan_delete) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ 动作编辑

    private fun showExerciseEditor(existing: Exercise?, onSaved: () -> Unit) {
        val dialogBinding = DialogExerciseEditBinding.inflate(layoutInflater)
        val initialMode = if (existing?.isRepsMode == true) ExerciseMode.REPS else ExerciseMode.TIME
        dialogBinding.chipModeTime.isChecked = initialMode == ExerciseMode.TIME
        dialogBinding.chipModeReps.isChecked = initialMode == ExerciseMode.REPS

        if (existing == null) {
            dialogBinding.inputName.setText(
                getString(R.string.default_exercise_name, engine.plan.size + 1)
            )
            dialogBinding.inputSets.setText(WorkoutLimits.DEFAULT_SETS.toString())
            dialogBinding.inputReps.setText(WorkoutLimits.DEFAULT_REPS.toString())
            dialogBinding.inputWork.setText(WorkoutLimits.DEFAULT_WORK_SEC.toString())
            dialogBinding.inputRest.setText(WorkoutLimits.DEFAULT_REST_SEC.toString())
        } else {
            dialogBinding.inputName.setText(existing.name)
            dialogBinding.inputSets.setText(existing.targetSets.toString())
            dialogBinding.inputReps.setText(existing.targetReps.toString())
            dialogBinding.inputWork.setText(existing.workSec.toString())
            dialogBinding.inputRest.setText(existing.restSec.toString())
        }

        // 次数型动作没有倒计时，隐藏锻炼时长与时长预设
        val applyMode: (ExerciseMode) -> Unit = { mode ->
            val repsMode = mode == ExerciseMode.REPS
            val visibility = if (repsMode) View.GONE else View.VISIBLE
            dialogBinding.inputWork.visibility = visibility
            dialogBinding.tvPresetsLabel.visibility = visibility
            dialogBinding.chipPresets.visibility = visibility
            dialogBinding.tvModeHint.visibility = if (repsMode) View.VISIBLE else View.GONE
        }
        applyMode(initialMode)

        dialogBinding.chipMode.setOnCheckedChangeListener { _, checkedId ->
            applyMode(if (checkedId == R.id.chip_mode_reps) ExerciseMode.REPS else ExerciseMode.TIME)
        }

        dialogBinding.chipPresets.setOnCheckedChangeListener { group, checkedId ->
            val tag = group.findViewById<View>(checkedId)?.tag as? String
                ?: return@setOnCheckedChangeListener
            val preset = tag.split('/')
            if (preset.size == 2) {
                dialogBinding.inputWork.setText(preset[0])
                dialogBinding.inputRest.setText(preset[1])
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.exercise_add_title else R.string.exercise_edit_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = dialogBinding.inputName.text.toString()
                val sets = dialogBinding.inputSets.text.toString().toIntOrNull()
                val reps = dialogBinding.inputReps.text.toString().toIntOrNull()
                val rest = dialogBinding.inputRest.text.toString().toIntOrNull()
                val work = dialogBinding.inputWork.text.toString().toIntOrNull()
                val repsMode = dialogBinding.chipModeReps.isChecked
                val mode = if (repsMode) ExerciseMode.REPS else ExerciseMode.TIME
                // 次数型动作的锻炼时长不参与配置，沿用输入框中的存量值
                val effectiveWork = work ?: WorkoutLimits.DEFAULT_WORK_SEC
                val valid = sets != null && reps != null && rest != null &&
                    sets in WorkoutLimits.MIN_SETS..WorkoutLimits.MAX_SETS &&
                    reps in WorkoutLimits.MIN_REPS..WorkoutLimits.MAX_REPS &&
                    rest in WorkoutLimits.MIN_REST_SEC..WorkoutLimits.MAX_SEC &&
                    (repsMode || (work != null && work in WorkoutLimits.MIN_WORK_SEC..WorkoutLimits.MAX_SEC))
                if (!valid) {
                    Toast.makeText(this, R.string.invalid_input, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val ok = if (existing == null) {
                    engine.addExercise(name, effectiveWork, rest!!, sets!!, reps!!, mode)
                    true
                } else {
                    engine.updateExercise(
                        Exercise.create(existing.id, name, sets!!, effectiveWork, rest!!, reps!!, mode)
                    )
                }
                if (!ok) {
                    Toast.makeText(this, R.string.plan_locked_action, Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    return@setOnClickListener
                }
                persist()
                render()
                onSaved()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    // ------------------------------------------------------------------ 组次记录

    private fun showRecordsDialog() {
        val startRun = engine.currentRun
        if (startRun == null) {
            Toast.makeText(this, R.string.no_exercise_hint, Toast.LENGTH_SHORT).show()
            return
        }

        val dialogBinding = DialogSetRecordsBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.records_title, startRun.exercise.name))
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.close, null)
            .create()

        fun refresh() {
            val run = engine.currentRun ?: return
            val readonly = run.status == ExerciseStatus.SAVED
            dialogBinding.tvRecordsHint.visibility = if (readonly) View.VISIBLE else View.GONE
            dialogBinding.recordsActions.visibility = if (readonly) View.GONE else View.VISIBLE
            dialogBinding.tvRecordsEmpty.visibility = if (run.sets.isEmpty()) View.VISIBLE else View.GONE
            dialogBinding.svRecords.visibility = if (run.sets.isEmpty()) View.GONE else View.VISIBLE
            dialogBinding.llSets.removeAllViews()

            run.sets.sortedBy { it.setIndex }.forEach { set ->
                val item = ItemSetRecordBinding.inflate(layoutInflater, dialogBinding.llSets, false)
                item.tvSetTitle.text = getString(R.string.records_item_title, set.setIndex)
                item.tvSetDetail.text = getString(
                    R.string.records_item_detail,
                    formatDuration(set.actualDurationMs),
                    set.actualReps,
                    set.targetReps,
                    formatDuration(set.restAfterMs)
                )
                item.tvSetStatus.setText(
                    if (set.completed) R.string.exercise_status_completed else R.string.records_item_pending
                )
                item.tvSetStatus.setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (set.completed) R.color.accent_rest else R.color.accent_work
                    )
                )
                item.btnEditSet.visibility = if (readonly) View.GONE else View.VISIBLE
                item.btnEditSet.setOnClickListener {
                    showSetEditor(set) { refresh() }
                }
                dialogBinding.llSets.addView(item.root)
            }

            dialogBinding.btnAddSet.isEnabled = run.recordedSets < WorkoutLimits.MAX_SETS
            dialogBinding.btnAddSet.alpha = if (dialogBinding.btnAddSet.isEnabled) 1f else DISABLED_ALPHA
            dialogBinding.btnAddSet.setOnClickListener {
                showSetEditor(null) { refresh() }
            }
            dialogBinding.btnFinishExercise.isEnabled = run.recordedSets > 0
            dialogBinding.btnFinishExercise.alpha =
                if (dialogBinding.btnFinishExercise.isEnabled) 1f else DISABLED_ALPHA
            dialogBinding.btnFinishExercise.setOnClickListener {
                handleEvent(engine.finishCurrentExercise(), null)
                persist()
                render()
                dialog.dismiss()
            }
        }

        refresh()
        dialog.show()
    }

    /** [existing] 为 null 时表示补录新的一组 */
    private fun showSetEditor(existing: SetExecution?, onSaved: () -> Unit) {
        val run = engine.currentRun ?: return
        if (run.status == ExerciseStatus.SAVED) {
            Toast.makeText(this, R.string.records_readonly, Toast.LENGTH_SHORT).show()
            return
        }

        val dialogBinding = DialogSetEditBinding.inflate(layoutInflater)
        if (existing == null) {
            dialogBinding.inputReps.setText(run.exercise.targetReps.toString())
            dialogBinding.inputDuration.setText(run.exercise.workSec.toString())
            dialogBinding.cbCompleted.isChecked = true
        } else {
            dialogBinding.inputReps.setText(existing.actualReps.toString())
            dialogBinding.inputDuration.setText((existing.actualDurationMs / 1000L).toString())
            dialogBinding.cbCompleted.isChecked = existing.completed
        }

        val title = if (existing == null) {
            getString(R.string.set_edit_add_title, run.recordedSets + 1)
        } else {
            getString(R.string.set_edit_title, existing.setIndex)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val reps = dialogBinding.inputReps.text.toString().toIntOrNull()
                val durationSec = dialogBinding.inputDuration.text.toString().toIntOrNull()
                val completed = dialogBinding.cbCompleted.isChecked
                val valid = reps != null && durationSec != null &&
                    reps in WorkoutLimits.MIN_REPS..WorkoutLimits.MAX_REPS &&
                    durationSec in 0..WorkoutLimits.MAX_SEC
                if (!valid) {
                    Toast.makeText(this, R.string.invalid_input, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val ok = if (existing == null) {
                    engine.appendSetRecord(reps!!, durationSec!! * 1000L, completed)
                } else {
                    engine.updateSetRecord(existing.setIndex, reps!!, durationSec!! * 1000L, completed)
                }
                if (!ok) {
                    Toast.makeText(this, R.string.records_readonly, Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    return@setOnClickListener
                }
                persist()
                render()
                onSaved()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    // -------------------------------------------------------------------- 设置

    private fun showSettingsDialog() {
        val dialogBinding = DialogSettingsBinding.inflate(layoutInflater)
        dialogBinding.switchAutosave.isChecked = engine.autoSave
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save) { _, _ ->
                engine.setAutoSave(dialogBinding.switchAutosave.isChecked)
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
                engine.resetSession()
                persist()
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun persist() {
        repository.saveSession(engine.snapshot())
    }

    /** 仅在内容变化时写入文本，避免无谓的重新布局 */
    private fun applyText(view: TextView, text: String, last: String): String {
        if (text == last) return last
        view.text = text
        return text
    }

    private companion object {
        const val TICK_INTERVAL_MS = 100L
        const val DISABLED_ALPHA = 0.35f
    }
}
