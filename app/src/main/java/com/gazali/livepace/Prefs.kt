package com.gazali.livepace

import android.content.Context
import android.content.SharedPreferences

/** Saved settings plus helpers for turning "5:30" into seconds and back. */
object Prefs {
    const val NAME = "live_pace"
    const val TARGET_FAST = "target_fast"   // seconds per km, -1 = none
    const val TARGET_SLOW = "target_slow"   // seconds per km, -1 = none
    const val VIBRATE = "vibrate"
    const val VOICE_INTERVAL = "voice_interval" // seconds, 0 = off
    const val BUBBLE_X = "bubble_x"
    const val BUBBLE_Y = "bubble_y"

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    private val PACE_REGEX = Regex("""^(\d{1,2})[:.](\d{2})$""")

    /** "5:30" or "5.30" -> 330. Returns null if it can't be read. */
    fun parsePace(text: String): Int? {
        val match = PACE_REGEX.matchEntire(text.trim()) ?: return null
        val minutes = match.groupValues[1].toInt()
        val seconds = match.groupValues[2].toInt()
        if (seconds >= 60 || minutes < 2) return null
        return minutes * 60 + seconds
    }

    /** 330 -> "5:30" */
    fun formatPace(secondsPerKm: Int): String =
        String.format(java.util.Locale.US, "%d:%02d", secondsPerKm / 60, secondsPerKm % 60)
}
