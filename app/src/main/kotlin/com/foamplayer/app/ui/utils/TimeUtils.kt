package com.foamplayer.app.ui.utils

import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Duration and date-time formatting for UI display. */
object TimeUtils {
    /** Spelled-out duration: "1h 02m 03s", "02m 03s" or "03s". */
    fun formatDurationLong(durationMs: Long): String {
        val totalSeconds = durationMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return if (hours > 0) {
            String.format(Locale.getDefault(), "%dh %02dm %02ds", hours, minutes, seconds)
        } else if (minutes > 0) {
            String.format(Locale.getDefault(), "%dm %02ds", minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02ds", seconds)
        }
    }

    /** Compact "m:ss" (or "h:mm:ss") duration. */
    fun formatDurationCompact(durationMs: Long): String {
        val totalSeconds = durationMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
        }
    }

    /** Medium date + short time for a timestamp, with a locale-cached formatter. */
    fun formatDateTime(timestampMs: Long): String {
        val locale = Locale.getDefault()
        val tag = locale.toLanguageTag()
        val formatter = cachedFormatter
            ?.takeIf { it.first == tag }
            ?.second
            ?: DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
                .also { cachedFormatter = tag to it }
        return formatter.format(Date(timestampMs))
    }

    @Volatile
    private var cachedFormatter: Pair<String, DateFormat>? = null
}
