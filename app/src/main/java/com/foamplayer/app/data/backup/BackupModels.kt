package com.foamplayer.app.data.backup

sealed interface BackupOutcome {
    data object Success : BackupOutcome
    data class Error(val message: String) : BackupOutcome
}

enum class BackupFeature(val key: String) {
    APP_SETTINGS("app_settings"),
    MUSIC_FOLDERS("music_folders"),
    EQUALIZER_PRESETS("equalizer_presets"),
    PLAYLISTS("playlists"),
    SONG_STATISTICS("song_stats"),
    SEARCH_HISTORY("search_history");

    companion object {
        fun fromKey(key: String): BackupFeature? = entries.firstOrNull { it.key == key }
    }
}
