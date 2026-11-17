package com.xxss0903.trainalaram.timer

/**
 * 训练计时状态机。
 *
 * 设计要点：
 * - **不持有 Android 依赖**，时间完全由外部注入的时钟提供，方便在 JVM 上做精度与流程单测。
 * - 倒计时基于「阶段起始时刻 + 阶段时长」计算，而不是靠累加 tick，
 *   因此应用进入后台、进程被杀、甚至设备休眠都不会造成计时漂移。
 * - [tick] 支持跨阶段追赶：长时间离开后再回到前台，会自动补齐 WORK→REST→REST_DONE
 *   的推进，并写入这段时间内的真实时长记录。
 */
class WorkoutTimerEngine(
    private val clock: () -> Long,
    private val wallClock: () -> Long
) {

    companion object {
        const val MIN_WORK_SEC = 1
        const val MIN_REST_SEC = 0
        const val MAX_SEC = 3600
        const val DEFAULT_WORK_SEC = 30
        const val DEFAULT_REST_SEC = 15

        /** 追赶时的最大推进步数，防御脏数据导致的死循环 */
        private const val MAX_CATCH_UP_STEPS = 64
    }

    var workDurationSec: Int = DEFAULT_WORK_SEC
        private set

    var restDurationSec: Int = DEFAULT_REST_SEC
        private set

    var phase: WorkoutPhase = WorkoutPhase.IDLE
        private set

    /** 当前进行中的组号；未开始训练时为即将开始的组号 */
    var currentSet: Int = 1
        private set

    /** 已完成（已计数）的总组数 */
    var completedSets: Int = 0
        private set

    private val _records = mutableListOf<SetRecord>()

    /** 已记录的时间明细，按发生顺序排列 */
    val records: List<SetRecord> get() = _records.toList()

    private var phaseStartElapsedMs = 0L
    private var phaseDurationMs = 0L

    /** 当前阶段剩余毫秒；非倒计时阶段恒为 0 */
    fun remainingMs(): Long {
        if (phase != WorkoutPhase.WORK && phase != WorkoutPhase.REST) return 0L
        return (phaseDurationMs - (clock() - phaseStartElapsedMs)).coerceAtLeast(0L)
    }

    /** 当前阶段进度 0f~1f，用于环形进度反馈 */
    fun phaseProgress(): Float {
        if (phase == WorkoutPhase.REST_DONE) return 1f
        if (phaseDurationMs <= 0L) return 0f
        val elapsed = (clock() - phaseStartElapsedMs).coerceIn(0L, phaseDurationMs)
        return elapsed.toFloat() / phaseDurationMs.toFloat()
    }

    /** 修改默认时长；对正在进行的阶段不生效，从下一个阶段开始采用新值 */
    fun setDurations(workSec: Int, restSec: Int) {
        workDurationSec = workSec.coerceIn(MIN_WORK_SEC, MAX_SEC)
        restDurationSec = restSec.coerceIn(MIN_REST_SEC, MAX_SEC)
    }

    /**
     * 主按钮的唯一入口，任何阶段下都是一步操作：
     * IDLE→开始第 1 组；WORK→完成本组并计数；REST→跳过休息直接进下一组；REST_DONE→开始下一组。
     */
    fun primaryAction() {
        when (phase) {
            WorkoutPhase.IDLE -> {
                currentSet = 1
                startWorkPhase()
            }

            WorkoutPhase.WORK -> completeWork(clock())

            WorkoutPhase.REST -> {
                completeRest(clock())
                currentSet += 1
                startWorkPhase()
            }

            WorkoutPhase.REST_DONE -> {
                currentSet += 1
                startWorkPhase()
            }
        }
    }

    /**
     * 由界面按固定间隔调用（也应在回到前台时调用一次以追赶进度）。
     * @return 本次调用是否发生了阶段切换
     */
    fun tick(): Boolean {
        var changed = false
        var steps = 0
        while (steps++ < MAX_CATCH_UP_STEPS) {
            if (phase != WorkoutPhase.WORK && phase != WorkoutPhase.REST) break
            if (phaseDurationMs <= 0L) break
            val endElapsed = phaseStartElapsedMs + phaseDurationMs
            if (clock() < endElapsed) break
            if (phase == WorkoutPhase.WORK) completeWork(endElapsed) else completeRest(endElapsed)
            changed = true
        }
        return changed
    }

    /** 清空本次训练的全部记录，保留已配置的时长 */
    fun reset() {
        phase = WorkoutPhase.IDLE
        currentSet = 1
        completedSets = 0
        _records.clear()
        phaseStartElapsedMs = 0L
        phaseDurationMs = 0L
    }

    fun totalWorkMs(): Long = _records.sumOf { if (it.phase == WorkoutPhase.WORK) it.durationMs else 0L }

    fun totalRestMs(): Long = _records.sumOf { if (it.phase == WorkoutPhase.REST) it.durationMs else 0L }

    fun lastWorkRecord(): SetRecord? = _records.lastOrNull { it.phase == WorkoutPhase.WORK }

    /** 取指定组号的休息记录，用于成对展示「本组时长 + 组间休息」 */
    fun restRecordOf(setIndex: Int): SetRecord? =
        _records.lastOrNull { it.phase == WorkoutPhase.REST && it.setIndex == setIndex }

    fun snapshot(): WorkoutSnapshot = WorkoutSnapshot(
        phase = phase,
        currentSet = currentSet,
        completedSets = completedSets,
        phaseStartElapsedMs = phaseStartElapsedMs,
        phaseDurationMs = phaseDurationMs,
        workDurationSec = workDurationSec,
        restDurationSec = restDurationSec,
        records = _records.toList()
    )

    /** 从快照恢复。数据非法或检测到设备重启时，回退到安全状态而不是崩溃 */
    fun restore(snapshot: WorkoutSnapshot) {
        workDurationSec = snapshot.workDurationSec.coerceIn(MIN_WORK_SEC, MAX_SEC)
        restDurationSec = snapshot.restDurationSec.coerceIn(MIN_REST_SEC, MAX_SEC)
        currentSet = snapshot.currentSet.coerceAtLeast(1)
        completedSets = snapshot.completedSets.coerceAtLeast(0)
        _records.clear()
        _records.addAll(snapshot.records)
        phase = snapshot.phase
        phaseStartElapsedMs = snapshot.phaseStartElapsedMs
        phaseDurationMs = snapshot.phaseDurationMs

        val invalidRunningPhase = (phase == WorkoutPhase.WORK || phase == WorkoutPhase.REST) &&
            (phaseDurationMs <= 0L || phaseStartElapsedMs > clock())
        if (invalidRunningPhase) {
            // 设备重启后 elapsedRealtime 归零，旧起点会大于当前时间，此时按未开始处理
            phase = if (completedSets > 0) WorkoutPhase.REST_DONE else WorkoutPhase.IDLE
            phaseDurationMs = 0L
            phaseStartElapsedMs = clock()
            currentSet = completedSets + 1
        }
    }

    private fun startWorkPhase() {
        phase = WorkoutPhase.WORK
        phaseStartElapsedMs = clock()
        phaseDurationMs = workDurationSec * 1000L
    }

    private fun completeWork(endElapsedMs: Long) {
        val actual = actualDuration(endElapsedMs)
        _records += SetRecord(WorkoutPhase.WORK, currentSet, actual, wallClock())
        completedSets += 1
        phaseStartElapsedMs = endElapsedMs
        if (restDurationSec > 0) {
            phase = WorkoutPhase.REST
            phaseDurationMs = restDurationSec * 1000L
        } else {
            phase = WorkoutPhase.REST_DONE
            phaseDurationMs = 0L
        }
    }

    private fun completeRest(endElapsedMs: Long) {
        val actual = actualDuration(endElapsedMs)
        _records += SetRecord(WorkoutPhase.REST, currentSet, actual, wallClock())
        phase = WorkoutPhase.REST_DONE
        phaseStartElapsedMs = endElapsedMs
        phaseDurationMs = 0L
    }

    private fun actualDuration(endElapsedMs: Long): Long {
        val elapsed = endElapsedMs - phaseStartElapsedMs
        return if (phaseDurationMs > 0L) elapsed.coerceIn(0L, phaseDurationMs) else elapsed.coerceAtLeast(0L)
    }
}
