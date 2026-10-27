package com.elio.recipecraft.ui.cover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CookTimerTest {

    private val twoMinutes = CookTimer.ofMinutes(2)

    @Test
    fun aNewTimerIsIdleAtItsFullDuration() {
        assertEquals(120_000, twoMinutes.durationMillis)
        assertEquals(CookTimerStatus.IDLE, twoMinutes.status(now = 5_000))
        assertEquals(120_000, twoMinutes.remaining(now = 5_000))
        assertFalse(twoMinutes.isRunning)
    }

    @Test
    fun startCountsDownOnTheClock() {
        val running = twoMinutes.start(now = 1_000)
        assertEquals(CookTimerStatus.RUNNING, running.status(now = 1_000))
        assertEquals(120_000, running.remaining(now = 1_000))
        assertEquals(90_000, running.remaining(now = 31_000))
        // Starting again while it runs changes nothing.
        assertSame(running, running.start(now = 31_000))
    }

    @Test
    fun pauseKeepsTheTimeLeftAndResumeGoesOnFromIt() {
        val paused = twoMinutes.start(now = 0).pause(now = 30_000)
        assertEquals(CookTimerStatus.PAUSED, paused.status(now = 100_000))
        assertEquals(90_000, paused.remaining(now = 100_000))
        assertSame(paused, paused.pause(now = 200_000))

        val resumed = paused.start(now = 100_000)
        assertEquals(CookTimerStatus.RUNNING, resumed.status(now = 100_000))
        assertEquals(80_000, resumed.remaining(now = 110_000))
    }

    @Test
    fun tickFinishesATimerWhoseTimeIsUp() {
        val running = twoMinutes.start(now = 0)
        assertSame(running, running.tick(now = 119_999))
        assertEquals(1, running.tick(now = 119_999).remaining(now = 119_999))

        val finished = running.tick(now = 125_000)
        assertEquals(CookTimerStatus.FINISHED, finished.status(now = 125_000))
        assertEquals(0, finished.remaining(now = 500_000))
        assertFalse(finished.isRunning)
        assertNull(finished.startedAt)
        // Even before the tick, the time left never goes below zero.
        assertEquals(CookTimerStatus.FINISHED, running.status(now = 130_000))
        assertEquals(0, running.remaining(now = 130_000))
    }

    @Test
    fun startingAFinishedTimerStartsItOver() {
        val finished = twoMinutes.start(now = 0).tick(now = 120_000)
        val again = finished.start(now = 200_000)
        assertEquals(CookTimerStatus.RUNNING, again.status(now = 200_000))
        assertEquals(120_000, again.remaining(now = 200_000))
    }

    @Test
    fun resetGoesBackToIdle() {
        assertEquals(twoMinutes, twoMinutes.start(now = 0).reset())
        assertEquals(twoMinutes, twoMinutes.start(now = 0).pause(now = 10_000).reset())
        assertEquals(twoMinutes, twoMinutes.start(now = 0).tick(now = 200_000).reset())
    }

    @Test
    fun timersSurviveTheTripThroughALongArray() {
        val timers = mapOf(
            0 to twoMinutes,
            2 to twoMinutes.start(now = 7_000),
            5 to CookTimer.ofMinutes(10).start(now = 0).pause(now = 60_000),
        )
        assertEquals(timers, timers.toLongArray().toCookTimers())
        assertEquals(emptyMap<Int, CookTimer>(), longArrayOf(1, 2, 3).toCookTimers())
        assertTrue(emptyMap<Int, CookTimer>().toLongArray().isEmpty())
    }

    @Test
    fun theCountdownRoundsAStartedSecondUp() {
        assertEquals("2:00", formatCountdown(120_000))
        assertEquals("1:30", formatCountdown(89_001))
        assertEquals("0:01", formatCountdown(1))
        assertEquals("0:00", formatCountdown(0))
        assertEquals("0:00", formatCountdown(-5))
        assertEquals("1:05:00", formatCountdown(3_900_000))
    }
}
