package com.elio.recipecraft.ui.cover

import android.os.SystemClock
import java.util.Locale
import javax.inject.Inject

/** Milliseconds on a clock that only moves forward, across sleep too; the Cook timers count on it. */
fun interface ElapsedClock {
    fun millis(): Long
}

/** [SystemClock.elapsedRealtime]: it keeps counting while the device sleeps and a change of wall time leaves it alone. */
class SystemElapsedClock @Inject constructor() : ElapsedClock {
    override fun millis(): Long = SystemClock.elapsedRealtime()
}

enum class CookTimerStatus { IDLE, RUNNING, PAUSED, FINISHED }

/**
 * The countdown of one step, as a value: each change takes the time [now] from an [ElapsedClock]
 * and returns a new timer, so it is the same before and after a configuration change.
 */
data class CookTimer(
    val durationMillis: Long,
    /** Time left when it was last started, paused or reset. */
    val remainingMillis: Long = durationMillis,
    /** When it was last started; null while it is not running. */
    val startedAt: Long? = null,
) {
    val isRunning: Boolean get() = startedAt != null

    fun remaining(now: Long): Long =
        if (startedAt == null) remainingMillis else (remainingMillis - (now - startedAt)).coerceAtLeast(0)

    fun status(now: Long): CookTimerStatus = when {
        remaining(now) == 0L -> CookTimerStatus.FINISHED
        startedAt != null -> CookTimerStatus.RUNNING
        remainingMillis == durationMillis -> CookTimerStatus.IDLE
        else -> CookTimerStatus.PAUSED
    }

    /** Starts or resumes; a finished timer starts over from its full duration. */
    fun start(now: Long): CookTimer = when (status(now)) {
        CookTimerStatus.RUNNING -> this
        CookTimerStatus.FINISHED -> copy(remainingMillis = durationMillis, startedAt = now)
        CookTimerStatus.IDLE, CookTimerStatus.PAUSED -> copy(startedAt = now)
    }

    fun pause(now: Long): CookTimer = if (startedAt == null) this else copy(remainingMillis = remaining(now), startedAt = null)

    fun reset(): CookTimer = CookTimer(durationMillis)

    /** Stops a running timer whose time is up, so it reads as finished from then on. */
    fun tick(now: Long): CookTimer = if (startedAt != null && remaining(now) == 0L) copy(remainingMillis = 0, startedAt = null) else this

    companion object {
        fun ofMinutes(minutes: Int) = CookTimer(minutes * 60_000L)
    }
}

/** Timers keyed by step position, as a flat array a [androidx.lifecycle.SavedStateHandle] can keep. */
internal fun Map<Int, CookTimer>.toLongArray(): LongArray =
    entries.flatMap { (position, timer) ->
        listOf(position.toLong(), timer.durationMillis, timer.remainingMillis, timer.startedAt ?: NOT_RUNNING)
    }.toLongArray()

/** The inverse of [toLongArray]; anything malformed reads as no timers. */
internal fun LongArray.toCookTimers(): Map<Int, CookTimer> {
    if (size % FIELDS != 0) return emptyMap()
    return (indices step FIELDS).associate { i ->
        this[i].toInt() to CookTimer(this[i + 1], this[i + 2], this[i + 3].takeIf { it != NOT_RUNNING })
    }
}

private const val FIELDS = 4
private const val NOT_RUNNING = -1L

/** The time left as `m:ss`, or `h:mm:ss` from an hour up, counting a started second as whole. */
fun formatCountdown(millis: Long): String {
    val seconds = (millis.coerceAtLeast(0) + 999) / 1000
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s) else "%d:%02d".format(Locale.ROOT, m, s)
}
