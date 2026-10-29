package com.gazali.livepace

import android.location.Location

/**
 * Turns GPS fixes into a steady "current pace".
 *
 * Why not just use the latest reading? Phone GPS positions wobble by 3-5 m,
 * and you only cover about 3 m per second, so second-by-second pace jumps
 * around wildly. Instead we use the GPS chip's own speed reading (measured
 * from the Doppler shift of the satellite signal, much steadier than
 * position-to-position distance) and average it over the last WINDOW_MS.
 */
class PaceCalculator {

    companion object {
        const val WINDOW_MS = 10_000L        // rolling window: last 10 seconds
        const val MAX_ACCURACY_M = 25f       // ignore fixes worse than this
        const val MIN_SPEED_MPS = 1.2f       // slower than ~13:53/km counts as stopped
        const val MIN_SAMPLES = 3
    }

    private data class Sample(val timeMs: Long, val speedMps: Float)

    private val samples = ArrayDeque<Sample>()
    private var previous: Location? = null

    var lastFixTimeMs: Long = 0L
        private set

    fun reset() {
        samples.clear()
        previous = null
        lastFixTimeMs = 0L
    }

    /** nowMs should be SystemClock.elapsedRealtime(). */
    fun addFix(location: Location, nowMs: Long) {
        if (location.hasAccuracy() && location.accuracy > MAX_ACCURACY_M) return

        val speed: Float? = when {
            location.hasSpeed() -> location.speed
            previous != null -> {
                val prev = previous!!
                val dtSec = (location.time - prev.time) / 1000f
                if (dtSec > 0f) prev.distanceTo(location) / dtSec else null
            }
            else -> null
        }
        previous = location
        lastFixTimeMs = nowMs
        if (speed == null || speed.isNaN() || speed < 0f || speed > 12f) return

        samples.addLast(Sample(nowMs, speed))
        trim(nowMs)
    }

    private fun trim(nowMs: Long) {
        while (samples.isNotEmpty() && nowMs - samples.first().timeMs > WINDOW_MS) {
            samples.removeFirst()
        }
    }

    /**
     * Current pace in seconds per km, or null when there isn't enough data
     * or you're stopped/walking.
     */
    fun currentPaceSecPerKm(nowMs: Long): Int? {
        trim(nowMs)
        if (samples.size < MIN_SAMPLES) return null
        val avgSpeed = samples.sumOf { it.speedMps.toDouble() } / samples.size
        if (avgSpeed < MIN_SPEED_MPS) return null
        return (1000.0 / avgSpeed).toInt()
    }
}
