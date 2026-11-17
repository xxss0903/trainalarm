package com.xxss0903.trainalaram.timer

/** 训练阶段 */
enum class WorkoutPhase {
    /** 未开始 */
    IDLE,

    /** 锻炼倒计时中 */
    WORK,

    /** 组间休息倒计时中 */
    REST,

    /** 休息已结束，等待用户开始下一组 */
    REST_DONE
}

/**
 * 一条计时记录：一组锻炼或一段休息的**实际**时长。
 *
 * @param phase     记录类型（WORK / REST）
 * @param setIndex  所属组号，从 1 开始
 * @param durationMs 实际持续时长（毫秒）
 * @param endedAtMs 结束时刻的墙上时间（毫秒），仅用于展示与追溯
 */
data class SetRecord(
    val phase: WorkoutPhase,
    val setIndex: Int,
    val durationMs: Long,
    val endedAtMs: Long
)

/** 可持久化的训练状态快照 */
data class WorkoutSnapshot(
    val phase: WorkoutPhase,
    val currentSet: Int,
    val completedSets: Int,
    val phaseStartElapsedMs: Long,
    val phaseDurationMs: Long,
    val workDurationSec: Int,
    val restDurationSec: Int,
    val records: List<SetRecord>
)

private const val STORAGE_VERSION = "v1"
private const val RECORD_SEPARATOR = ';'
private const val FIELD_SEPARATOR = ','

/** 序列化为单行文本，便于写入 SharedPreferences；格式纯 Kotlin 实现，可在 JVM 单测中校验 */
fun WorkoutSnapshot.toStorage(): String {
    val sb = StringBuilder(256)
    sb.append(STORAGE_VERSION).append('\n')
    sb.append("phase=").append(phase.name).append('\n')
    sb.append("current_set=").append(currentSet).append('\n')
    sb.append("completed_sets=").append(completedSets).append('\n')
    sb.append("phase_start=").append(phaseStartElapsedMs).append('\n')
    sb.append("phase_duration=").append(phaseDurationMs).append('\n')
    sb.append("work_sec=").append(workDurationSec).append('\n')
    sb.append("rest_sec=").append(restDurationSec).append('\n')
    sb.append("records=")
    sb.append(
        records.joinToString(RECORD_SEPARATOR.toString()) {
            "${it.phase.name}$FIELD_SEPARATOR${it.setIndex}$FIELD_SEPARATOR${it.durationMs}$FIELD_SEPARATOR${it.endedAtMs}"
        }
    )
    return sb.toString()
}

/**
 * 解析持久化文本。任何字段缺失或格式非法都返回 null，
 * 由调用方回退到全新状态，避免脏数据导致崩溃（异常状态恢复）。
 */
fun snapshotFromStorage(raw: String?): WorkoutSnapshot? {
    if (raw.isNullOrBlank()) return null
    val lines = raw.split('\n').filter { it.isNotBlank() }
    if (lines.size < 2 || lines[0].trim() != STORAGE_VERSION) return null

    val fields = HashMap<String, String>()
    for (i in 1 until lines.size) {
        val idx = lines[i].indexOf('=')
        if (idx <= 0) continue
        fields[lines[i].substring(0, idx).trim()] = lines[i].substring(idx + 1).trim()
    }

    val phase = runCatching { WorkoutPhase.valueOf(fields["phase"].orEmpty()) }.getOrNull() ?: return null
    val currentSet = fields["current_set"]?.toIntOrNull() ?: return null
    val completedSets = fields["completed_sets"]?.toIntOrNull() ?: return null
    val phaseStart = fields["phase_start"]?.toLongOrNull() ?: return null
    val phaseDuration = fields["phase_duration"]?.toLongOrNull() ?: return null
    val workSec = fields["work_sec"]?.toIntOrNull() ?: return null
    val restSec = fields["rest_sec"]?.toIntOrNull() ?: return null

    val records = ArrayList<SetRecord>()
    val rawRecords = fields["records"].orEmpty()
    if (rawRecords.isNotBlank()) {
        for (item in rawRecords.split(RECORD_SEPARATOR)) {
            val parts = item.split(FIELD_SEPARATOR)
            if (parts.size != 4) continue
            val recordPhase = runCatching { WorkoutPhase.valueOf(parts[0]) }.getOrNull() ?: continue
            val setIndex = parts[1].toIntOrNull() ?: continue
            val duration = parts[2].toLongOrNull() ?: continue
            val endedAt = parts[3].toLongOrNull() ?: continue
            records += SetRecord(recordPhase, setIndex, duration, endedAt)
        }
    }

    return WorkoutSnapshot(
        phase = phase,
        currentSet = currentSet,
        completedSets = completedSets,
        phaseStartElapsedMs = phaseStart,
        phaseDurationMs = phaseDuration,
        workDurationSec = workSec,
        restDurationSec = restSec,
        records = records
    )
}
