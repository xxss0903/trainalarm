package com.xxss0903.trainalaram

import com.xxss0903.trainalaram.timer.Exercise
import com.xxss0903.trainalaram.timer.ExerciseMode
import com.xxss0903.trainalaram.timer.ExerciseRun
import com.xxss0903.trainalaram.timer.ExerciseStatus
import com.xxss0903.trainalaram.timer.SessionSnapshot
import com.xxss0903.trainalaram.timer.SessionState
import com.xxss0903.trainalaram.timer.SetExecution
import com.xxss0903.trainalaram.timer.WorkoutLimits
import com.xxss0903.trainalaram.timer.WorkoutPhase
import com.xxss0903.trainalaram.timer.WorkoutSessionEngine
import com.xxss0903.trainalaram.timer.sessionSnapshotFromStorage
import com.xxss0903.trainalaram.timer.toStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖多动作、多组次训练流程的关键场景：
 * 单动作多组训练、多动作顺序切换、手动 / 自动保存与数据完整性校验、
 * 组次补录与修改、异常中断恢复、快照往返与非法数据容错。
 */
class WorkoutSessionEngineTest {

    private class FakeClock {
        var elapsed = 0L
        var wall = 1_700_000_000_000L

        fun advance(ms: Long) {
            elapsed += ms
            wall += ms
        }
    }

    private fun newEngine(clock: FakeClock): WorkoutSessionEngine =
        WorkoutSessionEngine(clock = { clock.elapsed }, wallClock = { clock.wall })

    /** 默认动作参数：3 组 × 12 次 · 锻炼 30 秒 / 休息 15 秒 */
    private fun engineWith(clock: FakeClock, vararg names: String): WorkoutSessionEngine =
        newEngine(clock).apply { names.forEach { addExercise(it) } }

    /** 走完一组的完整流程：开始本组 → 计时结束 → 自动结算 → 走完组间休息 */
    private fun completeSet(engine: WorkoutSessionEngine, clock: FakeClock) {
        val exercise = engine.currentExercise!!
        engine.primaryAction()
        clock.advance(exercise.workSec * 1000L)
        engine.tick()
        if (engine.phase == WorkoutPhase.REST) {
            clock.advance(exercise.restSec * 1000L)
            engine.tick()
        }
    }

    // ------------------------------------------------------------ 初始状态

    @Test
    fun newPlanStartsIdleAtFirstExercise() {
        val clock = FakeClock()
        val engine = engineWith(clock, "深蹲", "卧推")

        assertEquals(2, engine.plan.size)
        assertEquals(0, engine.currentIndex)
        assertEquals("深蹲", engine.currentExercise?.name)
        assertEquals(WorkoutPhase.IDLE, engine.phase)
        assertEquals(SessionState.NOT_STARTED, engine.sessionState)
        assertEquals(1, engine.currentSet)
        assertEquals(0, engine.recordedSets)
        assertEquals(0L, engine.remainingMs())
        assertTrue(engine.isPlanStructureEditable)
    }

    // ------------------------------------------------------- 单动作多组训练

    @Test
    fun startingSetCountsDownAccurately() {
        val clock = FakeClock()
        val engine = engineWith(clock, "深蹲")

        engine.primaryAction()

        assertEquals(WorkoutPhase.WORK, engine.phase)
        assertEquals(SessionState.IN_PROGRESS, engine.sessionState)
        assertEquals(30_000L, engine.remainingMs())

        clock.advance(12_345L)

        assertEquals(30_000L - 12_345L, engine.remainingMs())
        assertEquals(12_345f / 30_000f, engine.phaseProgress(), 0.0001f)
        assertFalse(engine.tick().any)
    }

    @Test
    fun completingSetRecordsDataAndEntersRest() {
        val clock = FakeClock()
        val engine = engineWith(clock, "深蹲")
        engine.primaryAction()

        clock.advance(30_000L)
        val event = engine.tick()

        assertTrue(event.phaseChanged)
        assertEquals(WorkoutPhase.REST, engine.phase)
        assertEquals(1, engine.recordedSets)
        assertEquals(15_000L, engine.remainingMs())

        val set = engine.currentRun!!.sets.single()
        assertEquals(1, set.setIndex)
        assertEquals(30_000L, set.actualDurationMs)
        assertEquals(12, set.targetReps)
        assertEquals(12, set.actualReps)
        assertTrue(set.completed)
    }

    @Test
    fun restFinishWritesBackActualRestDuration() {
        val clock = FakeClock()
        val engine = engineWith(clock, "深蹲")
        engine.primaryAction()

        clock.advance(30_000L)
        engine.tick()
        assertEquals(WorkoutPhase.REST, engine.phase)

        clock.advance(6_000L)
        engine.tick()
        assertEquals(WorkoutPhase.REST, engine.phase)

        clock.advance(9_000L)
        assertTrue(engine.tick().phaseChanged)

        assertEquals(WorkoutPhase.REST_DONE, engine.phase)
        assertEquals(15_000L, engine.currentRun!!.sets.single().restAfterMs)
    }

    @Test
    fun singleExerciseAllSetsThenManualSaveCompletesSession() {
        val clock = FakeClock()
        val engine = engineWith(clock, "深蹲")

        completeSet(engine, clock)
        completeSet(engine, clock)
        completeSet(engine, clock)

        assertEquals(WorkoutPhase.SETS_DONE, engine.phase)
        assertEquals(3, engine.recordedSets)
        assertEquals(SessionState.IN_PROGRESS, engine.sessionState)

        val report = engine.saveCurrentExercise()

        assertTrue(report.saved)
        assertTrue(report.issues.isEmpty())
        assertEquals(ExerciseStatus.SAVED, engine.plan[0].status)
        assertTrue(engine.plan[0].savedAtMs > 0L)
        assertEquals(1, engine.savedExerciseCount)
        assertEquals(SessionState.COMPLETED, engine.sessionState)
    }

    @Test
    fun restDoneStartsNextSetWithOneAction() {
        val clock = FakeClock()
        val engine = engineWith(clock, "深蹲")
        engine.primaryAction()

        clock.advance(45_000L)
        engine.tick()
        assertEquals(WorkoutPhase.REST_DONE, engine.phase)

        val event = engine.primaryAction()

        assertTrue(event.phaseChanged)
        assertEquals(WorkoutPhase.WORK, engine.phase)
        assertEquals(2, engine.currentSet)
        assertEquals(1, engine.recordedSets)
    }

    @Test
    fun skipRestStartsNextSetImmediately() {
        val clock = FakeClock()
        val engine = engineWith(clock, "深蹲")
        engine.primaryAction()

        clock.advance(30_000L)
        engine.tick()
        clock.advance(3_000L)

        engine.primaryAction()

        assertEquals(WorkoutPhase.WORK, engine.phase)
        assertEquals(3_000L, engine.currentRun!!.sets[0].restAfterMs)
        assertEquals(2, engine.currentSet)
    }

    @Test
    fun zeroRestMovesStraightToRestDone() {
        val clock = FakeClock()
        val engine = newEngine(clock).apply { addExercise("深蹲", 20, 0, 3, 12) }

        engine.primaryAction()
        clock.advance(20_000L)
        engine.tick()

        assertEquals(WorkoutPhase.REST_DONE, engine.phase)
        assertEquals(1, engine.recordedSets)
        assertEquals(0L, engine.currentRun!!.sets[0].restAfterMs)
    }

    // ------------------------------------------------------- 多动作顺序切换

    @Test
    fun autoSaveAdvancesToNextExerciseKeepingSessionContext() {
        val clock = FakeClock()
        val engine = engineWith(clock, "深蹲", "卧推")
        engine.setAutoSave(true)

        completeSet(engine, clock)
        completeSet(engine, clock)
        completeSet(engine, clock)

        // 深蹲三组完成，自动保存并切换上下文到卧推，计划本身未被重建
        assertEquals(ExerciseStatus.SAVED, engine.plan[0].status)
        assertEquals("卧推", engine.currentExercise?.name)
        assertEquals(1, engine.currentIndex)
        assertEquals(2, engine.plan.size)
        assertEquals(WorkoutPhase.IDLE, engine.phase)
        assertEquals(SessionState.IN_PROGRESS, engine.sessionState)
        assertEquals(1, engine.savedExerciseCount)
        assertEquals(3, engine.totalRecordedSets)

        completeSet(engine, clock)
        completeSet(engine, clock)
        completeSet(engine, clock)

        assertEquals(SessionState.COMPLETED, engine.sessionState)
        assertEquals(WorkoutPhase.IDLE, engine.phase)
        assertEquals(2, engine.savedExerciseCount)
        assertEquals(6, engine.totalRecordedSets)
        assertEquals(6, engine.totalTargetSets)
        assertEquals(1f, engine.overallProgress(), 0.0001f)
    }

    @Test
    fun overallProgressTracksRecordedOverPlannedSets() {
        val clock = FakeClock()
        val engine = engineWith(clock, "深蹲", "卧推")

        assertEquals(6, engine.totalTargetSets)
        assertEquals(0f, engine.overallProgress(), 0.0001f)

        engine.appendSetRecord(12, 30_000L, true)

        assertEquals(1, engine.totalRecordedSets)
        assertEquals(1f / 6f, engine.overallProgress(), 0.0001f)
    }

    // -------------------------------------------------------- 保存与完整性校验

    @Test
    fun saveWithoutAnySetIsRejected() {
        val clock = FakeClock()
        val engine = engineWith(clock, "深蹲")

        val report = engine.saveCurrentExercise()

        assertFalse(report.saved)
        assertTrue(report.issues.any { it.contains("没有任何组次记录") })
        assertEquals(ExerciseStatus.NOT_STARTED, engine.plan[0].status)
        assertEquals(SessionState.NOT_STARTED, engine.sessionState)
    }

    @Test
    fun validationDetectsGapInSetIndexes() {
        val clock = FakeClock()
        val engine = engineWith(clock, "深蹲")
        val exercise = engine.plan[0].exercise

        val runWithGap = ExerciseRun(
            exercise = exercise,
            sets = listOf(
                SetExecution(1, 10_000L, 12, 12, 5_000L, true, 0L),
                SetExecution(3, 10_000L, 12, 12, 0L, true, 0L)
            )
        )

        val issues = engine.validateForSave(runWithGap)

        assertTrue(issues.any { it.contains("组号不连续") })
    }

    @Test
    fun validationDetectsDurationBeyondConfiguration() {
        val clock = FakeClock()
        val engine = engineWith(clock, "深蹲")
        val exercise = engine.plan[0].exercise

        val run = ExerciseRun(
            exercise = exercise,
            sets = listOf(SetExecution(1, 45_000L, 12, 12, 0L, true, 0L))
        )

        val issues = engine.validateForSave(run)

        assertTrue(issues.any { it.contains("超出配置上限") })
    }

    @Test
    fun savedExerciseRejectsFurtherEdits() {
        val clock = FakeClock()
        val engine = engineWith(clock, "深蹲", "卧推")
        engine.appendSetRecord(12, 30_000L, true)

        assertTrue(engine.saveCurrentExercise().saved)
        val savedId = engine.plan[0].exercise.id

        assertFalse(engine.updateExercise(Exercise.create(savedId, "深蹲改", 3, 30, 15, 12)))
        assertEquals("卧推", engine.currentExercise?.name)
        assertTrue(engine.appendSetRecord(12, 30_000L, true))
    }

    @Test
    fun updateExerciseRejectsTargetSetsBelowRecorded() {
        val clock = FakeClock()
        val engine = engineWith(clock, "深蹲")
        engine.appendSetRecord(12, 30_000L, true)
        engine.appendSetRecord(12, 30_000L, true)

        val exercise = engine.plan[0].exercise

        assertFalse(engine.updateExercise(exercise.copy(targetSets = 1)))
        assertTrue(engine.updateExercise(exercise.copy(targetSets = 5)))
        assertEquals(5, engine.currentRun!!.exercise.targetSets)
    }

    // ------------------------------------------------------- 组次补录与修改

    @Test
    fun manualAppendAndUpdateSetRecords() {
        val clock = FakeClock()
        val engine = engineWith(clock, "深蹲")

        assertTrue(engine.appendSetRecord(10, 20_000L, completed = true))
        assertEquals(1, engine.recordedSets)
        assertEquals(ExerciseStatus.IN_PROGRESS, engine.plan[0].status)
        assertEquals(10, engine.currentRun!!.sets[0].actualReps)

        assertTrue(engine.updateSetRecord(1, 8, 15_000L, completed = false))
        val updated = engine.currentRun!!.sets[0]
        assertEquals(8, updated.actualReps)
        assertEquals(15_000L, updated.actualDurationMs)
        assertFalse(updated.completed)

        // 非法次数被收敛到合法区间
        assertTrue(engine.updateSetRecord(1, 99_999, 15_000L, completed = true))
        assertEquals(WorkoutLimits.MAX_REPS, engine.currentRun!!.sets[0].actualReps)
    }

    @Test
    fun appendingUpToTargetSetsMovesToSetsDone() {
        val clock = FakeClock()
        val engine = engineWith(clock, "深蹲")

        repeat(3) { engine.appendSetRecord(12, 30_000L, true) }

        assertEquals(3, engine.recordedSets)
        assertEquals(WorkoutPhase.SETS_DONE, engine.phase)
    }

    @Test
    fun finishingExerciseEarlyKeepsRecordedSets() {
        val clock = FakeClock()
        val engine = engineWith(clock, "深蹲")
        engine.primaryAction()
        clock.advance(10_000L)
        engine.tick()

        assertTrue(engine.finishCurrentExercise().any)
        assertEquals(WorkoutPhase.SETS_DONE, engine.phase)
        assertEquals(1, engine.recordedSets)

        assertTrue(engine.saveCurrentExercise().saved)
        assertEquals(1, engine.plan[0].sets.size)
        assertEquals(SessionState.COMPLETED, engine.sessionState)
    }

    // ------------------------------------------------------- 计划增删与排序

    @Test
    fun planStructureLockedWhileTraining() {
        val clock = FakeClock()
        val engine = engineWith(clock, "A", "B", "C")
        val firstId = engine.plan[0].exercise.id

        assertTrue(engine.moveExercise(firstId, 1))
        assertEquals("B", engine.plan[0].exercise.name)
        assertEquals("A", engine.plan[1].exercise.name)

        engine.primaryAction()
        assertEquals(SessionState.IN_PROGRESS, engine.sessionState)

        assertFalse(engine.moveExercise(firstId, 1))
        assertFalse(engine.removeExercise(firstId))
        assertEquals(3, engine.plan.size)
    }

    @Test
    fun removingCurrentExerciseFallsBackToFirst() {
        val clock = FakeClock()
        val engine = engineWith(clock, "A", "B")

        assertTrue(engine.removeExercise(engine.plan[0].exercise.id))

        assertEquals(1, engine.plan.size)
        assertEquals("B", engine.currentExercise?.name)
    }

    @Test
    fun addingExerciseAfterCompletionResumesTraining() {
        val clock = FakeClock()
        val engine = engineWith(clock, "深蹲")
        repeat(3) { engine.appendSetRecord(12, 30_000L, true) }
        assertTrue(engine.saveCurrentExercise().saved)
        assertEquals(SessionState.COMPLETED, engine.sessionState)

        engine.addExercise("卧推")

        assertEquals(2, engine.plan.size)
        assertEquals(SessionState.NOT_STARTED, engine.sessionState)
        assertEquals("卧推", engine.currentExercise?.name)
        assertEquals(1, engine.savedExerciseCount)
    }

    // -------------------------------------------------- 异常中断与恢复

    @Test
    fun tickCatchesUpAcrossPhasesAfterLongAbsence() {
        val clock = FakeClock()
        val engine = engineWith(clock, "深蹲")
        engine.primaryAction()

        // 模拟进程被杀后很久才回到前台：一次 tick 直接追赶到休息结束
        clock.advance(10 * 60 * 1000L)
        val event = engine.tick()

        assertTrue(event.phaseChanged)
        assertEquals(WorkoutPhase.REST_DONE, engine.phase)
        assertEquals(1, engine.recordedSets)
        assertEquals(30_000L, engine.currentRun!!.sets[0].actualDurationMs)
        assertEquals(15_000L, engine.currentRun!!.sets[0].restAfterMs)
    }

    @Test
    fun snapshotRoundTripRestoresFullSession() {
        val clock = FakeClock()
        val engine = engineWith(clock, "深蹲", "卧推")
        engine.setAutoSave(true)
        engine.primaryAction()
        clock.advance(10_000L)
        engine.primaryAction()
        clock.advance(4_000L)

        val raw = engine.snapshot().toStorage()
        val parsed = sessionSnapshotFromStorage(raw)!!
        val restored = newEngine(clock)
        restored.restore(parsed)

        assertEquals(2, restored.plan.size)
        assertEquals("深蹲", restored.currentExercise?.name)
        assertEquals(WorkoutPhase.REST, restored.phase)
        assertEquals(1, restored.recordedSets)
        assertEquals(10_000L, restored.currentRun!!.sets[0].actualDurationMs)
        assertEquals(15_000L - 4_000L, restored.remainingMs())
        assertTrue(restored.autoSave)
    }

    @Test
    fun snapshotParsingRejectsMalformedPayloads() {
        assertNull(sessionSnapshotFromStorage(null))
        assertNull(sessionSnapshotFromStorage(""))
        assertNull(sessionSnapshotFromStorage("garbage"))
        assertNull(
            sessionSnapshotFromStorage(
                "v1\nauto_save=0\nsession_state=NOT_STARTED\ncurrent_exercise=-1\n" +
                    "phase=IDLE\nphase_start=0\nphase_duration=0"
            )
        )
        assertNull(
            sessionSnapshotFromStorage(
                "v2\nauto_save=0\nsession_state=NOT_STARTED\ncurrent_exercise=-1\n" +
                    "phase=NOT_A_PHASE\nphase_start=0\nphase_duration=0"
            )
        )
    }

    @Test
    fun corruptedSetLineIsSkippedWithoutLosingOthers() {
        val clock = FakeClock()
        val engine = engineWith(clock, "深蹲")
        engine.appendSetRecord(12, 30_000L, true)
        engine.appendSetRecord(10, 20_000L, true)

        val corrupted = engine.snapshot().toStorage().replace("exset=2|", "exset=broken|")
        val parsed = sessionSnapshotFromStorage(corrupted)!!

        assertEquals(1, parsed.plan.size)
        assertEquals(1, parsed.plan[0].recordedSets)
        assertEquals(1, parsed.plan[0].sets[0].setIndex)
    }

    @Test
    fun restoreFallsBackSafelyAfterDeviceReboot() {
        val clock = FakeClock()
        clock.elapsed = 5_000L

        // phaseStart 远大于当前时刻，说明设备重启后 elapsedRealtime 已归零
        val stale = SessionSnapshot(
            plan = listOf(
                ExerciseRun(
                    exercise = Exercise.create(1, "深蹲"),
                    status = ExerciseStatus.IN_PROGRESS,
                    sets = listOf(SetExecution(1, 30_000L, 12, 12, 15_000L, true, 0L))
                )
            ),
            currentExerciseId = 1L,
            sessionState = SessionState.IN_PROGRESS,
            autoSave = false,
            phase = WorkoutPhase.WORK,
            phaseStartElapsedMs = 900_000L,
            phaseDurationMs = 30_000L
        )

        val engine = newEngine(clock)
        engine.restore(stale)

        assertEquals(WorkoutPhase.REST_DONE, engine.phase)
        assertEquals(0L, engine.remainingMs())
        assertEquals(1, engine.recordedSets)
    }

    @Test
    fun restoreWithEmptyPlanResetsToInitialState() {
        val clock = FakeClock()
        val engine = engineWith(clock, "深蹲")
        engine.primaryAction()

        engine.restore(
            SessionSnapshot(
                plan = emptyList(),
                currentExerciseId = null,
                sessionState = SessionState.IN_PROGRESS,
                autoSave = true,
                phase = WorkoutPhase.WORK,
                phaseStartElapsedMs = 0L,
                phaseDurationMs = 30_000L
            )
        )

        assertTrue(engine.plan.isEmpty())
        assertEquals(SessionState.NOT_STARTED, engine.sessionState)
        assertEquals(WorkoutPhase.IDLE, engine.phase)
        assertNull(engine.currentExerciseId)
    }

    @Test
    fun resetSessionClearsRecordsAndKeepsPlan() {
        val clock = FakeClock()
        val engine = engineWith(clock, "深蹲", "卧推")
        engine.primaryAction()
        clock.advance(30_000L)
        engine.tick()
        assertEquals(1, engine.totalRecordedSets)

        engine.resetSession()

        assertEquals(2, engine.plan.size)
        assertEquals(0, engine.totalRecordedSets)
        assertEquals(SessionState.NOT_STARTED, engine.sessionState)
        assertEquals(WorkoutPhase.IDLE, engine.phase)
        assertEquals("深蹲", engine.currentExercise?.name)
        assertTrue(engine.plan.all { it.status == ExerciseStatus.NOT_STARTED })
    }

    // ---------------------------------------------------------------- 暂停功能

    @Test
    fun pauseOnlyAvailableWhileTiming() {
        val clock = FakeClock()
        val engine = engineWith(clock, "深蹲")

        assertEquals(WorkoutPhase.IDLE, engine.phase)
        assertFalse(engine.setPaused(true))
        assertFalse(engine.paused)

        engine.primaryAction()
        assertTrue(engine.isPauseAvailable)
        assertTrue(engine.setPaused(true))
        assertTrue(engine.paused)
        // 已暂停时始终可以继续，即使阶段判定不满足
        assertTrue(engine.setPaused(false))
        assertFalse(engine.paused)
    }

    @Test
    fun pauseFreezesWorkCountdownAndLocksMainFlow() {
        val clock = FakeClock()
        val engine = engineWith(clock, "深蹲")
        engine.primaryAction()

        clock.advance(10_000L)
        assertEquals(20_000L, engine.remainingMs())

        assertTrue(engine.setPaused(true))

        // 暂停期间真实时间继续流逝，但剩余时间与进度被冻结
        clock.advance(60_000L)
        assertEquals(20_000L, engine.remainingMs())
        assertEquals(10_000f / 30_000f, engine.phaseProgress(), 0.0001f)

        // 主流程被锁定：tick 与主按钮都不产生任何推进
        assertFalse(engine.tick().any)
        assertFalse(engine.primaryAction().any)
        assertEquals(WorkoutPhase.WORK, engine.phase)
        assertEquals(0, engine.recordedSets)

        // 继续后从冻结处接着走，暂停时长不计入锻炼时长
        assertTrue(engine.setPaused(false))
        clock.advance(5_000L)
        assertEquals(15_000L, engine.remainingMs())

        clock.advance(15_000L)
        assertTrue(engine.tick().phaseChanged)
        assertEquals(30_000L, engine.currentRun!!.sets.single().actualDurationMs)
    }

    @Test
    fun pauseFreezesRestCountdownAndIsClearedForNextPhase() {
        val clock = FakeClock()
        val engine = engineWith(clock, "深蹲")
        engine.primaryAction()
        clock.advance(30_000L)
        engine.tick()
        assertEquals(WorkoutPhase.REST, engine.phase)

        clock.advance(5_000L)
        assertTrue(engine.setPaused(true))
        clock.advance(30_000L)
        assertEquals(10_000L, engine.remainingMs())

        assertTrue(engine.setPaused(false))
        // 跳过休息：实际休息时长只计暂停之外的 5 秒
        engine.primaryAction()
        assertEquals(WorkoutPhase.WORK, engine.phase)
        assertEquals(5_000L, engine.currentRun!!.sets[0].restAfterMs)
        // 暂停累计不跨阶段：下一组仍是完整的锻炼时长
        assertEquals(30_000L, engine.remainingMs())
    }

    @Test
    fun pauseBlocksEarlyFinishAndSurvivesSnapshotRoundTrip() {
        val clock = FakeClock()
        val engine = engineWith(clock, "深蹲")
        engine.primaryAction()
        clock.advance(8_000L)
        engine.setPaused(true)

        // 暂停期间「结束本动作」同样被锁定
        assertFalse(engine.finishCurrentExercise().any)

        val parsed = sessionSnapshotFromStorage(engine.snapshot().toStorage())!!
        val restored = newEngine(clock)
        restored.restore(parsed)

        assertTrue(restored.paused)
        assertEquals(WorkoutPhase.WORK, restored.phase)
        assertEquals(22_000L, restored.remainingMs())

        // 进程被杀后重新恢复：仍保持暂停，计时不因离开而流失
        clock.advance(120_000L)
        assertEquals(22_000L, restored.remainingMs())
        assertFalse(restored.tick().any)

        assertTrue(restored.setPaused(false))
        clock.advance(2_000L)
        assertEquals(20_000L, restored.remainingMs())
    }

    // ------------------------------------------------------------ 次数型动作

    @Test
    fun repsModeHasNoCountdownAndUsesManualCompletion() {
        val clock = FakeClock()
        val engine = newEngine(clock).apply { addExercise("俯卧撑", 30, 15, 3, 20, ExerciseMode.REPS) }

        engine.primaryAction()
        assertEquals(WorkoutPhase.WORK, engine.phase)
        assertTrue(engine.currentExercise!!.isRepsMode)
        // 无倒计时：剩余时间恒为 0，秒表从 0 开始正向累计
        assertEquals(0L, engine.remainingMs())
        assertEquals(0L, engine.workElapsedMs())

        clock.advance(75_000L)
        // 时间再长也不会自动结算
        assertFalse(engine.tick().any)
        assertEquals(WorkoutPhase.WORK, engine.phase)
        assertEquals(75_000L, engine.workElapsedMs())
        assertEquals(0, engine.recordedSets)

        // 手动点击「完成本组」结算，时长可超过配置的锻炼时长而不被判为异常
        assertTrue(engine.primaryAction().phaseChanged)
        val set = engine.currentRun!!.sets.single()
        assertEquals(20, set.actualReps)
        assertEquals(75_000L, set.actualDurationMs)
        assertEquals(WorkoutPhase.REST, engine.phase)
        assertTrue(engine.validateForSave(engine.currentRun!!).isEmpty())
    }

    @Test
    fun repsModePauseFreezesStopwatch() {
        val clock = FakeClock()
        val engine = newEngine(clock).apply { addExercise("深蹲", 30, 15, 3, 20, ExerciseMode.REPS) }
        engine.primaryAction()
        clock.advance(12_000L)
        assertEquals(12_000L, engine.workElapsedMs())

        engine.setPaused(true)
        clock.advance(45_000L)
        assertEquals(12_000L, engine.workElapsedMs())

        engine.setPaused(false)
        clock.advance(3_000L)
        assertEquals(15_000L, engine.workElapsedMs())

        engine.primaryAction()
        assertEquals(15_000L, engine.currentRun!!.sets.single().actualDurationMs)
    }

    @Test
    fun repsModeAndPauseSurviveSnapshotRoundTrip() {
        val clock = FakeClock()
        val engine = newEngine(clock).apply { addExercise("深蹲", 30, 15, 3, 20, ExerciseMode.REPS) }
        engine.primaryAction()
        clock.advance(20_000L)

        val parsed = sessionSnapshotFromStorage(engine.snapshot().toStorage())!!
        assertEquals(ExerciseMode.REPS, parsed.plan[0].exercise.mode)

        // 次数型动作在锻炼阶段本就无倒计时，恢复时不应被当作非法状态回退
        val restored = newEngine(clock)
        restored.restore(parsed)

        assertTrue(restored.currentExercise!!.isRepsMode)
        assertEquals(WorkoutPhase.WORK, restored.phase)
        assertEquals(20_000L, restored.workElapsedMs())
    }

    @Test
    fun legacyExerciseLineWithoutModeDefaultsToTime() {
        val raw = "v2\nauto_save=0\nsession_state=IN_PROGRESS\ncurrent_exercise=1\n" +
            "phase=IDLE\nphase_start=0\nphase_duration=0\n" +
            "ex=1|深蹲|3|30|15|12|IN_PROGRESS|0"

        val parsed = sessionSnapshotFromStorage(raw)!!

        assertEquals(ExerciseMode.TIME, parsed.plan[0].exercise.mode)
        assertFalse(parsed.pause.paused)
        assertEquals(0L, parsed.pause.pauseOffsetMs)
    }
}
