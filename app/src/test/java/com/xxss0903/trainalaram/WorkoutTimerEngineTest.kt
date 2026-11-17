package com.xxss0903.trainalaram

import com.xxss0903.trainalaram.timer.WorkoutPhase
import com.xxss0903.trainalaram.timer.WorkoutTimerEngine
import com.xxss0903.trainalaram.timer.snapshotFromStorage
import com.xxss0903.trainalaram.timer.toStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖需求中的关键场景：倒计时精度、阶段自动切换、组次计数、后台追赶与异常恢复。
 */
class WorkoutTimerEngineTest {

    private class FakeClock {
        var elapsed = 0L
        var wall = 1_700_000_000_000L

        fun advance(ms: Long) {
            elapsed += ms
            wall += ms
        }
    }

    private fun engineWithDefaults(clock: FakeClock): WorkoutTimerEngine =
        WorkoutTimerEngine(clock = { clock.elapsed }, wallClock = { clock.wall }).apply {
            setDurations(30, 15)
        }

    @Test
    fun initialStateIsIdle() {
        val clock = FakeClock()
        val engine = engineWithDefaults(clock)

        assertEquals(WorkoutPhase.IDLE, engine.phase)
        assertEquals(1, engine.currentSet)
        assertEquals(0, engine.completedSets)
        assertEquals(0L, engine.remainingMs())
        assertTrue(engine.records.isEmpty())
    }

    @Test
    fun primaryActionStartsFirstWorkPhase() {
        val clock = FakeClock()
        val engine = engineWithDefaults(clock)

        engine.primaryAction()

        assertEquals(WorkoutPhase.WORK, engine.phase)
        assertEquals(1, engine.currentSet)
        assertEquals(30_000L, engine.remainingMs())
    }

    @Test
    fun remainingCountsDownAccurately() {
        val clock = FakeClock()
        val engine = engineWithDefaults(clock)
        engine.primaryAction()

        clock.advance(12_345L)

        assertEquals(30_000L - 12_345L, engine.remainingMs())
        assertEquals(12_345f / 30_000f, engine.phaseProgress(), 0.0001f)
    }

    @Test
    fun workAutoSwitchesToRestWhenCountdownEnds() {
        val clock = FakeClock()
        val engine = engineWithDefaults(clock)
        engine.primaryAction()

        clock.advance(30_000L)
        assertTrue(engine.tick())

        assertEquals(WorkoutPhase.REST, engine.phase)
        assertEquals(1, engine.completedSets)
        assertEquals(15_000L, engine.remainingMs())
        assertEquals(1, engine.records.size)
        assertEquals(WorkoutPhase.WORK, engine.records[0].phase)
        assertEquals(30_000L, engine.records[0].durationMs)
    }

    @Test
    fun restAutoSwitchesToRestDoneWhenCountdownEnds() {
        val clock = FakeClock()
        val engine = engineWithDefaults(clock)
        engine.primaryAction()

        clock.advance(45_000L) // 30s 锻炼 + 15s 休息
        assertTrue(engine.tick())

        assertEquals(WorkoutPhase.REST_DONE, engine.phase)
        assertEquals(0L, engine.remainingMs())
        assertEquals(2, engine.records.size)
        assertEquals(WorkoutPhase.REST, engine.records[1].phase)
        assertEquals(15_000L, engine.records[1].durationMs)
        assertEquals(1, engine.records[1].setIndex)
    }

    @Test
    fun tickReturnsFalseWhileStillCounting() {
        val clock = FakeClock()
        val engine = engineWithDefaults(clock)
        engine.primaryAction()

        clock.advance(10_000L)
        assertFalse(engine.tick())
    }

    @Test
    fun longBackgroundAbsenceCatchesUpAcrossPhases() {
        val clock = FakeClock()
        val engine = engineWithDefaults(clock)
        engine.primaryAction()

        // 模拟进程被杀后很久才回到前台：一次 tick 直接追赶到休息结束
        clock.advance(10 * 60 * 1000L)
        assertTrue(engine.tick())

        assertEquals(WorkoutPhase.REST_DONE, engine.phase)
        assertEquals(1, engine.completedSets)
        assertEquals(2, engine.records.size)
        assertEquals(30_000L, engine.records[0].durationMs)
        assertEquals(15_000L, engine.records[1].durationMs)
    }

    @Test
    fun manualFinishRecordsActualDurationAndCountsSet() {
        val clock = FakeClock()
        val engine = engineWithDefaults(clock)
        engine.primaryAction()

        clock.advance(8_400L)
        engine.primaryAction() // 完成本组

        assertEquals(WorkoutPhase.REST, engine.phase)
        assertEquals(1, engine.completedSets)
        assertEquals(8_400L, engine.records[0].durationMs)
    }

    @Test
    fun manualFinishNeverExceedsConfiguredDuration() {
        val clock = FakeClock()
        val engine = engineWithDefaults(clock)
        engine.primaryAction()

        // 界面卡顿导致按钮回调晚于阶段结束
        clock.advance(35_000L)
        engine.primaryAction()

        assertEquals(30_000L, engine.records[0].durationMs)
    }

    @Test
    fun setCountingAdvancesAcrossMultipleSets() {
        val clock = FakeClock()
        val engine = engineWithDefaults(clock)

        // 第 1 组
        engine.primaryAction()
        assertEquals(1, engine.currentSet)
        clock.advance(30_000L)
        engine.tick()

        // 休息结束后开始第 2 组
        clock.advance(15_000L)
        engine.tick()
        engine.primaryAction()
        assertEquals(WorkoutPhase.WORK, engine.phase)
        assertEquals(2, engine.currentSet)
        assertEquals(1, engine.completedSets)

        clock.advance(20_000L)
        engine.primaryAction()
        assertEquals(2, engine.completedSets)
        assertEquals(2, engine.currentSet)

        // 跳过休息，直接进入第 3 组
        engine.primaryAction()
        assertEquals(WorkoutPhase.WORK, engine.phase)
        assertEquals(3, engine.currentSet)
        assertEquals(2, engine.completedSets)

        assertEquals(50_000L, engine.totalWorkMs())
        assertEquals(15_000L, engine.totalRestMs())
    }

    @Test
    fun zeroRestGoesStraightToRestDone() {
        val clock = FakeClock()
        val engine = WorkoutTimerEngine(clock = { clock.elapsed }, wallClock = { clock.wall })
        engine.setDurations(20, 0)
        engine.primaryAction()

        clock.advance(20_000L)
        engine.tick()

        assertEquals(WorkoutPhase.REST_DONE, engine.phase)
        assertEquals(1, engine.completedSets)
        assertEquals(1, engine.records.size)
    }

    @Test
    fun changingDurationsDoesNotAffectRunningPhase() {
        val clock = FakeClock()
        val engine = engineWithDefaults(clock)
        engine.primaryAction()

        clock.advance(5_000L)
        engine.setDurations(60, 30)

        assertEquals(25_000L, engine.remainingMs())

        clock.advance(25_000L)
        engine.tick()
        assertEquals(WorkoutPhase.REST, engine.phase)
        assertEquals(30_000L, engine.remainingMs())
    }

    @Test
    fun resetClearsRecordsAndKeepsDurations() {
        val clock = FakeClock()
        val engine = engineWithDefaults(clock)
        engine.primaryAction()
        clock.advance(30_000L)
        engine.tick()

        engine.reset()

        assertEquals(WorkoutPhase.IDLE, engine.phase)
        assertEquals(0, engine.completedSets)
        assertEquals(1, engine.currentSet)
        assertTrue(engine.records.isEmpty())
        assertEquals(30, engine.workDurationSec)
        assertEquals(15, engine.restDurationSec)
    }

    @Test
    fun snapshotRoundTripPreservesRunningState() {
        val clock = FakeClock()
        val engine = engineWithDefaults(clock)
        engine.primaryAction()
        clock.advance(10_000L)
        engine.primaryAction() // 进入休息
        clock.advance(4_000L)

        val restoredEngine = WorkoutTimerEngine(clock = { clock.elapsed }, wallClock = { clock.wall })
        restoredEngine.restore(snapshotFromStorage(engine.snapshot().toStorage())!!)

        assertEquals(WorkoutPhase.REST, restoredEngine.phase)
        assertEquals(1, restoredEngine.completedSets)
        assertEquals(11_000L, restoredEngine.remainingMs())
        assertEquals(1, restoredEngine.records.size)
        assertEquals(10_000L, restoredEngine.records[0].durationMs)
    }

    @Test
    fun snapshotParsesOnlyWellFormedPayloads() {
        assertNull(snapshotFromStorage(null))
        assertNull(snapshotFromStorage(""))
        assertNull(snapshotFromStorage("garbage"))
        assertNull(snapshotFromStorage("v1\nphase=NOT_A_PHASE\ncurrent_set=1"))
        assertNull(snapshotFromStorage("v2\nphase=IDLE\ncurrent_set=1"))
    }

    @Test
    fun corruptedRecordItemsAreSkippedInsteadOfCrashing() {
        val clock = FakeClock()
        val engine = engineWithDefaults(clock)
        engine.primaryAction()
        clock.advance(30_000L)
        engine.tick()

        val raw = engine.snapshot().toStorage()
        val corrupted = raw.replace("WORK,1,30000,", "WORK,broken,x,")

        val parsed = snapshotFromStorage(corrupted)!!
        assertTrue(parsed.records.isEmpty())
        assertEquals(WorkoutPhase.REST, parsed.phase)
    }

    @Test
    fun restoreFallsBackSafelyAfterDeviceReboot() {
        val clock = FakeClock()
        clock.elapsed = 5_000L

        // phaseStart 远大于当前时刻，说明设备重启后 elapsedRealtime 已归零
        val stale = com.xxss0903.trainalaram.timer.WorkoutSnapshot(
            phase = WorkoutPhase.WORK,
            currentSet = 3,
            completedSets = 2,
            phaseStartElapsedMs = 900_000L,
            phaseDurationMs = 30_000L,
            workDurationSec = 30,
            restDurationSec = 15,
            records = listOf()
        )

        val engine = WorkoutTimerEngine(clock = { clock.elapsed }, wallClock = { clock.wall })
        engine.restore(stale)

        assertEquals(WorkoutPhase.REST_DONE, engine.phase)
        assertEquals(2, engine.completedSets)
        assertEquals(3, engine.currentSet)
        assertEquals(0L, engine.remainingMs())
    }

    @Test
    fun invalidSnapshotDurationsAreClamped() {
        val clock = FakeClock()
        val snapshot = com.xxss0903.trainalaram.timer.WorkoutSnapshot(
            phase = WorkoutPhase.IDLE,
            currentSet = 1,
            completedSets = 0,
            phaseStartElapsedMs = 0L,
            phaseDurationMs = 0L,
            workDurationSec = -5,
            restDurationSec = 99_999,
            records = listOf()
        )

        val engine = WorkoutTimerEngine(clock = { clock.elapsed }, wallClock = { clock.wall })
        engine.restore(snapshot)

        assertEquals(WorkoutTimerEngine.MIN_WORK_SEC, engine.workDurationSec)
        assertEquals(WorkoutTimerEngine.MAX_SEC, engine.restDurationSec)
    }
}
