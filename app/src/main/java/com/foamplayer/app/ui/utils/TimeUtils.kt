package com.foamplayer.app.ui.utils

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
}
