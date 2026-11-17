package com.xxss0903.trainalaram.timer

/** 引擎在一次操作 / 一次 tick 中产生的事件，供界面触发提示与持久化 */
data class EngineEvent(
    val phaseChanged: Boolean = false,
    val exerciseSaved: Boolean = false,
    val sessionCompleted: Boolean = false
) {
    val any: Boolean get() = phaseChanged || exerciseSaved || sessionCompleted

    companion object {
        val NONE = EngineEvent()
    }
}

/** 保存结果：`saved=false` 时 [issues] 描述数据完整性问题，界面据此阻止推进 */
data class SaveReport(val saved: Boolean, val issues: List<String>)

/**
 * 多动作、多组次的训练流程状态机。
 *
 * 设计要点：
 * - **不持有 Android 依赖**，时间完全由注入时钟提供，可在 JVM 上覆盖全流程单测。
 * - 倒计时基于「阶段起始时刻 + 阶段时长」计算，进入后台、进程被杀或设备休眠都不会漂移。
 * - [tick] 支持跨阶段追赶，长时间离开后回到前台会自动补齐 WORK→REST 的真实时长。
 * - 动作按序推进：本动作全部组记录完成后进入 [WorkoutPhase.SETS_DONE]，保存校验通过后
 *   自动把上下文切到下一个动作（保留整份计划与会话状态，不重新初始化）。
 */
class WorkoutSessionEngine(
    private val clock: () -> Long,
    private val wallClock: () -> Long
) {

    private val _plan = mutableListOf<ExerciseRun>()

    /** 训练计划（含每个动作的运行状态与组次记录） */
    val plan: List<ExerciseRun> get() = _plan.toList()

    var currentExerciseId: Long? = null
        private set

    var sessionState: SessionState = SessionState.NOT_STARTED
        private set

    var autoSave: Boolean = false
        private set

    var phase: WorkoutPhase = WorkoutPhase.IDLE
        private set

    private var phaseStartElapsedMs = 0L
    private var phaseDurationMs = 0L
    private var nextId = 1L

    // ------------------------------------------------------------------ 查询

    val currentIndex: Int
        get() {
            val id = currentExerciseId ?: return 0
            val i = _plan.indexOfFirst { it.exercise.id == id }
            return if (i >= 0) i else 0
        }

    val currentRun: ExerciseRun? get() = _plan.getOrNull(currentIndex)

    val currentExercise: Exercise? get() = currentRun?.exercise

    /** 当前动作内已记录的组数 */
    val recordedSets: Int get() = currentRun?.recordedSets ?: 0

    /** 当前动作的目标组数 */
    val targetSets: Int get() = currentExercise?.targetSets ?: 0

    /** 当前（或即将进行）的组号，1 起 */
    val currentSet: Int
        get() {
            val target = targetSets
            if (target <= 0) return 1
            return (recordedSets + 1).coerceAtMost(target)
        }

    val savedExerciseCount: Int get() = _plan.count { it.status == ExerciseStatus.SAVED }

    val totalRecordedSets: Int get() = _plan.sumOf { it.recordedSets }

    val totalTargetSets: Int get() = _plan.sumOf { it.exercise.targetSets }

    val sessionWorkMs: Long get() = _plan.sumOf { it.totalWorkMs }

    val sessionRestMs: Long get() = _plan.sumOf { it.totalRestMs }

    /** 整体训练进度 0f~1f：已记录组数 / 计划总组数 */
    fun overallProgress(): Float {
        val target = totalTargetSets
        if (target <= 0) return 0f
        return (totalRecordedSets.toFloat() / target.toFloat()).coerceIn(0f, 1f)
    }

    /** 当前阶段剩余毫秒；非倒计时阶段恒为 0 */
    fun remainingMs(): Long {
        if (phase != WorkoutPhase.WORK && phase != WorkoutPhase.REST) return 0L
        return (phaseDurationMs - (clock() - phaseStartElapsedMs)).coerceAtLeast(0L)
    }

    /** 当前阶段进度 0f~1f，用于环形进度反馈 */
    fun phaseProgress(): Float {
        if (phase == WorkoutPhase.REST_DONE || phase == WorkoutPhase.SETS_DONE) return 1f
        if (phaseDurationMs <= 0L) return 0f
        val elapsed = (clock() - phaseStartElapsedMs).coerceIn(0L, phaseDurationMs)
        return elapsed.toFloat() / phaseDurationMs.toFloat()
    }

    // -------------------------------------------------------------- 计划管理

    /** 训练进行中禁止增删与排序，避免打乱当前会话上下文 */
    val isPlanStructureEditable: Boolean get() = sessionState != SessionState.IN_PROGRESS

    fun addExercise(
        name: String,
        workSec: Int = WorkoutLimits.DEFAULT_WORK_SEC,
        restSec: Int = WorkoutLimits.DEFAULT_REST_SEC,
        targetSets: Int = WorkoutLimits.DEFAULT_SETS,
        targetReps: Int = WorkoutLimits.DEFAULT_REPS
    ): Exercise {
        val exercise = Exercise.create(nextId++, name, targetSets, workSec, restSec, targetReps)
        _plan += ExerciseRun(exercise)
        when (sessionState) {
            // 已完成后又新增动作：允许继续训练，历史已保存记录原样保留
            SessionState.COMPLETED -> {
                sessionState = SessionState.NOT_STARTED
                currentExerciseId = exercise.id
            }
            // 训练进行中新增只追加到计划末尾，不打断当前动作与会话上下文
            SessionState.IN_PROGRESS -> Unit
            SessionState.NOT_STARTED -> currentExerciseId = _plan.firstOrNull()?.exercise?.id
        }
        return exercise
    }

    /**
     * 修改动作参数。已保存的动作不可再改；进行中的动作不允许把目标组数改到低于已记录组数，
     * 其余参数从下一个阶段开始生效。
     */
    fun updateExercise(exercise: Exercise): Boolean {
        val i = _plan.indexOfFirst { it.exercise.id == exercise.id }
        if (i < 0) return false
        val run = _plan[i]
        if (run.status == ExerciseStatus.SAVED) return false
        if (run.status == ExerciseStatus.IN_PROGRESS && exercise.targetSets < run.recordedSets) return false
        _plan[i] = run.copy(exercise = exercise)
        return true
    }

    fun removeExercise(id: Long): Boolean {
        if (!isPlanStructureEditable) return false
        val i = _plan.indexOfFirst { it.exercise.id == id }
        if (i < 0) return false
        _plan.removeAt(i)
        currentExerciseId = _plan.firstOrNull()?.exercise?.id
        return true
    }

    /** 在计划内上 / 下移动一个动作，[delta] 为 -1 或 1 */
    fun moveExercise(id: Long, delta: Int): Boolean {
        if (!isPlanStructureEditable) return false
        val i = _plan.indexOfFirst { it.exercise.id == id }
        if (i < 0) return false
        val j = i + delta
        if (j < 0 || j >= _plan.size) return false
        val tmp = _plan[i]
        _plan[i] = _plan[j]
        _plan[j] = tmp
        currentExerciseId = _plan.firstOrNull()?.exercise?.id
        return true
    }

    // -------------------------------------------------------------- 流程推进

    /**
     * 主按钮的唯一入口，任何阶段都是一步操作：
     * IDLE→开始本动作第 1 组；WORK→完成本组并计数；REST→跳过休息并开始下一组；
     * REST_DONE→开始下一组；SETS_DONE→保存并进入下一动作。
     */
    fun primaryAction(): EngineEvent = when (phase) {
        WorkoutPhase.IDLE -> startCurrentSet()
        WorkoutPhase.WORK -> completeCurrentSet(clock())
        WorkoutPhase.REST -> {
            finishRest(clock())
            startCurrentSet()
        }
        WorkoutPhase.REST_DONE -> startCurrentSet()
        WorkoutPhase.SETS_DONE -> {
            val report = saveCurrentExercise()
            EngineEvent(
                phaseChanged = report.saved,
                exerciseSaved = report.saved,
                sessionCompleted = report.saved && sessionState == SessionState.COMPLETED
            )
        }
    }

    /**
     * 由界面按固定间隔调用（回到前台时也应调用一次以追赶进度）。
     */
    fun tick(): EngineEvent {
        var phaseChanged = false
        var saved = false
        var completed = false
        var steps = 0
        while (steps++ < MAX_CATCH_UP_STEPS) {
            if (phase != WorkoutPhase.WORK && phase != WorkoutPhase.REST) break
            if (phaseDurationMs <= 0L) break
            val endElapsed = phaseStartElapsedMs + phaseDurationMs
            if (clock() < endElapsed) break
            if (phase == WorkoutPhase.WORK) {
                val event = completeCurrentSet(endElapsed)
                phaseChanged = phaseChanged || event.phaseChanged
                saved = saved || event.exerciseSaved
                completed = completed || event.sessionCompleted
                // 本动作组次已满：等待保存（自动保存模式已在内部完成切换）
                if (phase == WorkoutPhase.SETS_DONE) break
            } else {
                finishRest(endElapsed)
                enterRestDone(endElapsed)
                phaseChanged = true
            }
        }
        return EngineEvent(phaseChanged, saved, completed)
    }

    /** 提前结束当前动作（不足目标组数也可），进入待保存状态 */
    fun finishCurrentExercise(): EngineEvent {
        val run = currentRun ?: return EngineEvent.NONE
        if (run.status == ExerciseStatus.SAVED) return EngineEvent.NONE

        var saved = false
        var completed = false
        when (phase) {
            WorkoutPhase.WORK -> {
                val event = completeCurrentSet(clock())
                saved = event.exerciseSaved
                completed = event.sessionCompleted
            }
            WorkoutPhase.REST -> {
                finishRest(clock())
                enterRestDone(clock())
            }
            else -> Unit
        }
        if (saved) return EngineEvent(phaseChanged = true, exerciseSaved = true, sessionCompleted = completed)

        val latest = currentRun ?: return EngineEvent.NONE
        if (latest.recordedSets == 0) return EngineEvent.NONE
        enterSetsDone(clock())
        return EngineEvent(phaseChanged = true)
    }

    /**
     * 保存当前动作并校验数据完整性。校验不通过时不推进流程，
     * 由界面提示具体问题（避免脏数据被写入或被静默丢弃）。
     */
    fun saveCurrentExercise(): SaveReport {
        val i = currentIndex
        val run = _plan.getOrNull(i) ?: return SaveReport(false, listOf("当前没有可保存的动作"))
        if (run.status == ExerciseStatus.SAVED) return SaveReport(true, emptyList())
        val issues = validateForSave(run)
        if (issues.isNotEmpty()) return SaveReport(false, issues)
        _plan[i] = run.copy(status = ExerciseStatus.SAVED, savedAtMs = wallClock())
        advanceToNextExercise()
        return SaveReport(true, emptyList())
    }

    /** 保存前的数据完整性校验 */
    fun validateForSave(run: ExerciseRun): List<String> {
        val issues = mutableListOf<String>()
        if (run.recordedSets == 0) {
            issues += "「${run.exercise.name}」没有任何组次记录"
            return issues
        }
        var expected = 1
        for (set in run.sets.sortedBy { it.setIndex }) {
            if (set.setIndex != expected) {
                issues += "「${run.exercise.name}」组号不连续，缺少第 $expected 组"
                break
            }
            expected++
        }
        val maxDuration = run.exercise.workSec * 1000L
        for (set in run.sets) {
            if (set.actualDurationMs < 0L) issues += "第 ${set.setIndex} 组锻炼时长为负"
            if (set.actualDurationMs > maxDuration) issues += "第 ${set.setIndex} 组锻炼时长超出配置上限"
            if (set.restAfterMs < 0L) issues += "第 ${set.setIndex} 组休息时长为负"
            if (set.actualReps < 0) issues += "第 ${set.setIndex} 组完成次数为负"
        }
        return issues
    }

    // ---------------------------------------------------------- 组次补录与修改

    /** 补录一组：追加到当前动作末尾 */
    fun appendSetRecord(actualReps: Int, durationMs: Long, completed: Boolean): Boolean {
        val run = currentRun ?: return false
        if (run.status == ExerciseStatus.SAVED) return false
        if (run.recordedSets >= WorkoutLimits.MAX_SETS) return false
        val exercise = run.exercise
        val set = SetExecution(
            setIndex = run.recordedSets + 1,
            actualDurationMs = durationMs.coerceIn(0L, exercise.workSec * 1000L),
            targetReps = exercise.targetReps,
            actualReps = WorkoutLimits.clampReps(actualReps),
            restAfterMs = 0L,
            completed = completed,
            endedAtMs = wallClock()
        )
        replaceCurrentRun(
            run.copy(
                sets = run.sets + set,
                status = if (run.status == ExerciseStatus.NOT_STARTED) ExerciseStatus.IN_PROGRESS else run.status
            )
        )
        refreshPhaseAfterManualEdit()
        return true
    }

    /** 修改已记录的一组（仅未保存的动作可改） */
    fun updateSetRecord(setIndex: Int, actualReps: Int, durationMs: Long, completed: Boolean): Boolean {
        val run = currentRun ?: return false
        if (run.status == ExerciseStatus.SAVED) return false
        val idx = run.sets.indexOfFirst { it.setIndex == setIndex }
        if (idx < 0) return false
        val old = run.sets[idx]
        val updated = run.sets.toMutableList().also {
            it[idx] = old.copy(
                actualReps = WorkoutLimits.clampReps(actualReps),
                actualDurationMs = durationMs.coerceIn(0L, run.exercise.workSec * 1000L),
                completed = completed
            )
        }
        replaceCurrentRun(run.copy(sets = updated))
        return true
    }

    // ---------------------------------------------------------------- 配置与重置

    fun setAutoSave(enabled: Boolean) {
        autoSave = enabled
    }

    /** 重置本次训练：清空所有组次记录与动作状态，保留训练计划本身 */
    fun resetSession() {
        for (i in _plan.indices) {
            _plan[i] = _plan[i].copy(status = ExerciseStatus.NOT_STARTED, sets = emptyList(), savedAtMs = 0L)
        }
        sessionState = SessionState.NOT_STARTED
        phase = WorkoutPhase.IDLE
        phaseDurationMs = 0L
        phaseStartElapsedMs = clock()
        currentExerciseId = _plan.firstOrNull()?.exercise?.id
    }

    // -------------------------------------------------------------- 快照与恢复

    fun snapshot(): SessionSnapshot = SessionSnapshot(
        plan = _plan.toList(),
        currentExerciseId = currentExerciseId,
        sessionState = sessionState,
        autoSave = autoSave,
        phase = phase,
        phaseStartElapsedMs = phaseStartElapsedMs,
        phaseDurationMs = phaseDurationMs
    )

    /** 从快照恢复；数据非法或检测到设备重启时回退到安全状态而不是崩溃 */
    fun restore(snapshot: SessionSnapshot) {
        _plan.clear()
        _plan.addAll(snapshot.plan)
        nextId = (_plan.maxOfOrNull { it.exercise.id } ?: 0L) + 1L
        autoSave = snapshot.autoSave
        sessionState = snapshot.sessionState
        currentExerciseId = snapshot.currentExerciseId ?: _plan.firstOrNull()?.exercise?.id
        phase = snapshot.phase
        phaseStartElapsedMs = snapshot.phaseStartElapsedMs
        phaseDurationMs = snapshot.phaseDurationMs

        if (_plan.isEmpty()) {
            sessionState = SessionState.NOT_STARTED
            phase = WorkoutPhase.IDLE
            currentExerciseId = null
            phaseDurationMs = 0L
            phaseStartElapsedMs = clock()
            return
        }

        // 设备重启后 elapsedRealtime 归零，旧起点会大于当前时刻；此时按已完成的组数回退
        val invalidRunning = (phase == WorkoutPhase.WORK || phase == WorkoutPhase.REST) &&
            (phaseDurationMs <= 0L || phaseStartElapsedMs > clock())
        if (invalidRunning) {
            val run = currentRun
            phase = when {
                run == null -> WorkoutPhase.IDLE
                run.recordedSets >= run.exercise.targetSets -> WorkoutPhase.SETS_DONE
                run.recordedSets > 0 -> WorkoutPhase.REST_DONE
                else -> WorkoutPhase.IDLE
            }
            phaseDurationMs = 0L
            phaseStartElapsedMs = clock()
        }
    }

    // ------------------------------------------------------------------ 内部

    private fun startCurrentSet(startElapsedMs: Long = clock()): EngineEvent {
        val run = currentRun ?: return EngineEvent.NONE
        if (run.status == ExerciseStatus.SAVED) return EngineEvent.NONE
        if (run.recordedSets >= run.exercise.targetSets) return EngineEvent.NONE
        if (sessionState == SessionState.NOT_STARTED) sessionState = SessionState.IN_PROGRESS
        if (sessionState == SessionState.COMPLETED) return EngineEvent.NONE
        replaceCurrentRun(run.copy(status = ExerciseStatus.IN_PROGRESS))
        phase = WorkoutPhase.WORK
        phaseStartElapsedMs = startElapsedMs
        phaseDurationMs = run.exercise.workSec * 1000L
        return EngineEvent(phaseChanged = true)
    }

    private fun completeCurrentSet(endElapsedMs: Long): EngineEvent {
        val run = currentRun ?: return EngineEvent.NONE
        val exercise = run.exercise
        val set = SetExecution(
            setIndex = run.recordedSets + 1,
            actualDurationMs = clampElapsed(endElapsedMs),
            targetReps = exercise.targetReps,
            actualReps = exercise.targetReps,
            restAfterMs = 0L,
            completed = true,
            endedAtMs = wallClock()
        )
        replaceCurrentRun(run.copy(sets = run.sets + set))

        val latest = currentRun ?: return EngineEvent.NONE
        if (latest.recordedSets >= exercise.targetSets) {
            enterSetsDone(endElapsedMs)
            if (autoSave) {
                val report = saveCurrentExercise()
                if (report.saved) {
                    return EngineEvent(
                        phaseChanged = true,
                        exerciseSaved = true,
                        sessionCompleted = sessionState == SessionState.COMPLETED
                    )
                }
            }
            return EngineEvent(phaseChanged = true)
        }
        if (exercise.restSec > 0) {
            phase = WorkoutPhase.REST
            phaseStartElapsedMs = endElapsedMs
            phaseDurationMs = exercise.restSec * 1000L
        } else {
            enterRestDone(endElapsedMs)
        }
        return EngineEvent(phaseChanged = true)
    }

    /** 把本组之后的实际休息时长写回该组记录 */
    private fun finishRest(endElapsedMs: Long) {
        val run = currentRun ?: return
        val last = run.sets.lastOrNull() ?: return
        val actualRest = clampElapsed(endElapsedMs)
        replaceCurrentRun(run.copy(sets = run.sets.dropLast(1) + last.copy(restAfterMs = actualRest)))
    }

    private fun enterRestDone(endElapsedMs: Long) {
        phase = WorkoutPhase.REST_DONE
        phaseStartElapsedMs = endElapsedMs
        phaseDurationMs = 0L
    }

    private fun enterSetsDone(endElapsedMs: Long) {
        phase = WorkoutPhase.SETS_DONE
        phaseStartElapsedMs = endElapsedMs
        phaseDurationMs = 0L
    }

    /** 保存成功后切换上下文到下一个动作；已是最后一个则标记整个会话完成 */
    private fun advanceToNextExercise() {
        val next = currentIndex + 1
        phaseDurationMs = 0L
        phaseStartElapsedMs = clock()
        if (next >= _plan.size) {
            sessionState = SessionState.COMPLETED
            phase = WorkoutPhase.IDLE
        } else {
            currentExerciseId = _plan[next].exercise.id
            phase = WorkoutPhase.IDLE
        }
    }

    /** 补录 / 修改后重新判定阶段，保证不会停留在非法状态 */
    private fun refreshPhaseAfterManualEdit() {
        val run = currentRun ?: return
        if (phase == WorkoutPhase.WORK || phase == WorkoutPhase.REST) return
        if (run.recordedSets >= run.exercise.targetSets) enterSetsDone(clock())
    }

    private fun replaceCurrentRun(run: ExerciseRun) {
        val i = currentIndex
        if (i in _plan.indices) _plan[i] = run
    }

    private fun clampElapsed(endElapsedMs: Long): Long {
        val elapsed = endElapsedMs - phaseStartElapsedMs
        return if (phaseDurationMs > 0L) elapsed.coerceIn(0L, phaseDurationMs) else elapsed.coerceAtLeast(0L)
    }

    private companion object {
        /** 追赶时的最大推进步数，防御脏数据导致的死循环 */
        const val MAX_CATCH_UP_STEPS = 64
    }
}
