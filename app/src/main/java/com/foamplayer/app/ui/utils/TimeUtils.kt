package com.foamplayer.app.ui.utils

import java.text.DateFormat
import java.util.Date
import java.util.Locale

object TimeUtils {
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

    /** Compact "m:ss" (or "h:mm:ss") duration, used by the RowBig density's third line. */
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

    fun formatDateTime(timestampMs: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
            .format(Date(timestampMs))
}
