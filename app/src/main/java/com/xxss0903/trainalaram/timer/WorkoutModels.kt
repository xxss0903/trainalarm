package com.xxss0903.trainalaram.timer

/** 当前动作内的即时阶段 */
enum class WorkoutPhase {
    /** 未开始（等待开始本动作的第 1 组） */
    IDLE,

    /** 锻炼倒计时中 */
    WORK,

    /** 组间休息倒计时中 */
    REST,

    /** 休息已结束，等待用户开始下一组 */
    REST_DONE,

    /** 本动作全部组已记录完成，等待保存 */
    SETS_DONE
}

/** 单个动作在会话中的状态：未开始 / 进行中 / 已保存 */
enum class ExerciseStatus { NOT_STARTED, IN_PROGRESS, SAVED }

/** 整个训练会话的状态：未开始 / 进行中 / 已完成 */
enum class SessionState { NOT_STARTED, IN_PROGRESS, COMPLETED }

/** 数值边界，统一在此约束，避免各处散落魔数 */
object WorkoutLimits {
    const val MIN_WORK_SEC = 1
    const val MIN_REST_SEC = 0
    const val MAX_SEC = 3600
    const val MIN_SETS = 1
    const val MAX_SETS = 99
    const val MIN_REPS = 0
    const val MAX_REPS = 999
    const val MAX_NAME_LEN = 24

    const val DEFAULT_WORK_SEC = 30
    const val DEFAULT_REST_SEC = 15
    const val DEFAULT_SETS = 3
    const val DEFAULT_REPS = 12

    fun clampWork(sec: Int): Int = sec.coerceIn(MIN_WORK_SEC, MAX_SEC)
    fun clampRest(sec: Int): Int = sec.coerceIn(MIN_REST_SEC, MAX_SEC)
    fun clampSets(count: Int): Int = count.coerceIn(MIN_SETS, MAX_SETS)
    fun clampReps(count: Int): Int = count.coerceIn(MIN_REPS, MAX_REPS)

    /** 名称清洗：去掉会破坏持久化格式的字符，并限制长度 */
    fun clampName(name: String): String =
        name.trim().replace('|', '/').replace('\n', ' ').replace('\r', ' ').take(MAX_NAME_LEN).ifBlank { "未命名动作" }
}

/** 动作定义（可编辑的训练计划项） */
data class Exercise(
    val id: Long,
    val name: String,
    val targetSets: Int,
    val workSec: Int,
    val restSec: Int,
    val targetReps: Int
) {
    companion object {
        /** 构造一个各字段均已收敛到合法区间的动作 */
        fun create(
            id: Long,
            name: String,
            targetSets: Int = WorkoutLimits.DEFAULT_SETS,
            workSec: Int = WorkoutLimits.DEFAULT_WORK_SEC,
            restSec: Int = WorkoutLimits.DEFAULT_REST_SEC,
            targetReps: Int = WorkoutLimits.DEFAULT_REPS
        ): Exercise = Exercise(
            id = id,
            name = WorkoutLimits.clampName(name),
            targetSets = WorkoutLimits.clampSets(targetSets),
            workSec = WorkoutLimits.clampWork(workSec),
            restSec = WorkoutLimits.clampRest(restSec),
            targetReps = WorkoutLimits.clampReps(targetReps)
        )
    }
}

/** 一组已记录的执行数据 */
data class SetExecution(
    /** 组号，从 1 开始 */
    val setIndex: Int,
    /** 本组实际锻炼时长（毫秒） */
    val actualDurationMs: Long,
    /** 本组目标次数 */
    val targetReps: Int,
    /** 本组实际完成次数（可补录 / 修改） */
    val actualReps: Int,
    /** 本组之后的组间休息时长（毫秒） */
    val restAfterMs: Long,
    /** 是否按计划完整完成（false 表示提前结束或补录时标记为未完成） */
    val completed: Boolean,
    /** 结束时刻的墙上时间（毫秒），用于展示与回溯 */
    val endedAtMs: Long
)

/** 动作在会话中的运行实例 */
data class ExerciseRun(
    val exercise: Exercise,
    val status: ExerciseStatus = ExerciseStatus.NOT_STARTED,
    val sets: List<SetExecution> = emptyList(),
    /** 保存时刻的墙上时间，未保存为 0 */
    val savedAtMs: Long = 0L
) {
    /** 已记录组数 */
    val recordedSets: Int get() = sets.size

    /** 已按计划完整完成的组数 */
    val completedSets: Int get() = sets.count { it.completed }

    val totalWorkMs: Long get() = sets.sumOf { it.actualDurationMs }
    val totalRestMs: Long get() = sets.sumOf { it.restAfterMs }
}

/** 可持久化的整会话快照 */
data class SessionSnapshot(
    val plan: List<ExerciseRun>,
    val currentExerciseId: Long?,
    val sessionState: SessionState,
    val autoSave: Boolean,
    val phase: WorkoutPhase,
    val phaseStartElapsedMs: Long,
    val phaseDurationMs: Long
)

private const val STORAGE_VERSION = "v2"
private const val LIST_SEPARATOR = '|'
private const val TAG_EXERCISE = "ex="
private const val TAG_EXERCISE_SET = "exset="

/**
 * 序列化为逐行文本，便于写入 SharedPreferences。
 * 纯 Kotlin 实现（不依赖 Android），可在 JVM 单测中直接校验往返一致性。
 */
fun SessionSnapshot.toStorage(): String {
    val sb = StringBuilder(512)
    sb.append(STORAGE_VERSION).append('\n')
    sb.append("auto_save=").append(if (autoSave) 1 else 0).append('\n')
    sb.append("session_state=").append(sessionState.name).append('\n')
    sb.append("current_exercise=").append(currentExerciseId ?: -1L).append('\n')
    sb.append("phase=").append(phase.name).append('\n')
    sb.append("phase_start=").append(phaseStartElapsedMs).append('\n')
    sb.append("phase_duration=").append(phaseDurationMs).append('\n')
    for (run in plan) {
        val e = run.exercise
        sb.append(TAG_EXERCISE)
            .append(e.id).append(LIST_SEPARATOR)
            .append(WorkoutLimits.clampName(e.name)).append(LIST_SEPARATOR)
            .append(e.targetSets).append(LIST_SEPARATOR)
            .append(e.workSec).append(LIST_SEPARATOR)
            .append(e.restSec).append(LIST_SEPARATOR)
            .append(e.targetReps).append(LIST_SEPARATOR)
            .append(run.status.name).append(LIST_SEPARATOR)
            .append(run.savedAtMs)
            .append('\n')
        for (s in run.sets) {
            sb.append(TAG_EXERCISE_SET)
                .append(s.setIndex).append(LIST_SEPARATOR)
                .append(s.actualDurationMs).append(LIST_SEPARATOR)
                .append(s.targetReps).append(LIST_SEPARATOR)
                .append(s.actualReps).append(LIST_SEPARATOR)
                .append(s.restAfterMs).append(LIST_SEPARATOR)
                .append(if (s.completed) 1 else 0).append(LIST_SEPARATOR)
                .append(s.endedAtMs)
                .append('\n')
        }
    }
    return sb.toString()
}

/**
 * 解析持久化文本。
 * 顶层字段缺失 / 版本不符 → 返回 null，由调用方回退到全新状态；
 * 单条动作或组次记录格式非法 → 跳过该条目而不是整体失败（异常恢复）。
 */
fun sessionSnapshotFromStorage(raw: String?): SessionSnapshot? {
    if (raw.isNullOrBlank()) return null
    val lines = raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.isEmpty() || lines[0] != STORAGE_VERSION) return null

    val fields = HashMap<String, String>()
    val plan = ArrayList<ExerciseRun>()
    for (line in lines.drop(1)) {
        if (line.startsWith(TAG_EXERCISE)) {
            parseExerciseLine(line.removePrefix(TAG_EXERCISE))?.let { plan += it }
        } else if (line.startsWith(TAG_EXERCISE_SET)) {
            val run = plan.lastOrNull() ?: continue
            parseSetLine(line.removePrefix(TAG_EXERCISE_SET))?.let { set ->
                plan[plan.lastIndex] = run.copy(sets = run.sets + set)
            }
        } else {
            val idx = line.indexOf('=')
            if (idx > 0) fields[line.substring(0, idx)] = line.substring(idx + 1)
        }
    }

    val sessionState = enumOrNull<SessionState>(fields["session_state"]) ?: return null
    val phase = enumOrNull<WorkoutPhase>(fields["phase"]) ?: return null
    val phaseStart = fields["phase_start"]?.toLongOrNull() ?: return null
    val phaseDuration = fields["phase_duration"]?.toLongOrNull() ?: return null
    val autoSave = when (fields["auto_save"]) {
        "1" -> true
        "0" -> false
        else -> return null
    }
    val rawCurrentId = fields["current_exercise"]?.toLongOrNull() ?: return null
    val currentExerciseId = if (rawCurrentId < 0L) null else rawCurrentId

    return SessionSnapshot(
        plan = plan,
        currentExerciseId = currentExerciseId,
        sessionState = sessionState,
        autoSave = autoSave,
        phase = phase,
        phaseStartElapsedMs = phaseStart,
        phaseDurationMs = phaseDuration
    )
}

private inline fun <reified T : Enum<T>> enumOrNull(value: String?): T? =
    if (value.isNullOrBlank()) null else runCatching { enumValueOf<T>(value) }.getOrNull()

private fun parseExerciseLine(payload: String): ExerciseRun? {
    val p = payload.split(LIST_SEPARATOR)
    if (p.size != 8) return null
    val id = p[0].toLongOrNull() ?: return null
    val targetSets = p[2].toIntOrNull() ?: return null
    val workSec = p[3].toIntOrNull() ?: return null
    val restSec = p[4].toIntOrNull() ?: return null
    val targetReps = p[5].toIntOrNull() ?: return null
    val status = enumOrNull<ExerciseStatus>(p[6]) ?: return null
    val savedAt = p[7].toLongOrNull() ?: return null
    return ExerciseRun(
        exercise = Exercise.create(id, p[1], targetSets, workSec, restSec, targetReps),
        status = status,
        savedAtMs = savedAt
    )
}

private fun parseSetLine(payload: String): SetExecution? {
    val p = payload.split(LIST_SEPARATOR)
    if (p.size != 7) return null
    val setIndex = p[0].toIntOrNull() ?: return null
    val duration = p[1].toLongOrNull() ?: return null
    val targetReps = p[2].toIntOrNull() ?: return null
    val actualReps = p[3].toIntOrNull() ?: return null
    val restAfter = p[4].toLongOrNull() ?: return null
    val completed = when (p[5]) {
        "1" -> true
        "0" -> false
        else -> return null
    }
    val endedAt = p[6].toLongOrNull() ?: return null
    return SetExecution(setIndex, duration, targetReps, actualReps, restAfter, completed, endedAt)
}
