package com.foamplayer.app.data.settings

/** The complete, validated set of user-configurable application preferences. */
enum class FoamIconStyle(val label: String) {
    SOLID("Solid"),
    OUTLINE("Outline"),
    FLAT("Flat")
}

data class AppSettings(
    val bitPerfectEnabled: Boolean = true,
    val libraryFolders: Set<String> = emptySet(),
    val iconStyle: FoamIconStyle = FoamIconStyle.FLAT,
    val miniPlayerRounding: Int = 8,
    val fullPlayerRounding: Int = 24,
    val artistSeparators: Set<String> = DEFAULT_SEPARATORS,
    val genreSeparators: Set<String> = DEFAULT_SEPARATORS,
    val automaticColorEnabled: Boolean = true,
    val accentColor: Int = DEFAULT_ACCENT_COLOR,
    val immersiveModeEnabled: Boolean = true,
    val animateSeekBarEnabled: Boolean = false,
    val autoOpenPlayerEnabled: Boolean = false,
    val dynamicBackgroundEnabled: Boolean = false,
    val backgroundBlur: Int = DEFAULT_BACKGROUND_BLUR
) {
    fun sanitized(): AppSettings = copy(
        libraryFolders = libraryFolders.map(String::trim).filter(String::isNotEmpty).toSet(),
        miniPlayerRounding = miniPlayerRounding.coerceIn(MIN_MINI_PLAYER_ROUNDING, MAX_MINI_PLAYER_ROUNDING),
        fullPlayerRounding = fullPlayerRounding.coerceIn(MIN_FULL_PLAYER_ROUNDING, MAX_FULL_PLAYER_ROUNDING),
        backgroundBlur = backgroundBlur.coerceIn(MIN_BACKGROUND_BLUR, MAX_BACKGROUND_BLUR),
        artistSeparators = artistSeparators.normalizedSeparators(),
        genreSeparators = genreSeparators.normalizedSeparators()
    )

    companion object {
        // 0xFFFF5252 (the app's existing default red) as a signed ARGB Int.
        const val DEFAULT_ACCENT_COLOR = -44_462
        const val MIN_MINI_PLAYER_ROUNDING = 0
        const val MAX_MINI_PLAYER_ROUNDING = 32
        const val MIN_FULL_PLAYER_ROUNDING = 0
        const val MAX_FULL_PLAYER_ROUNDING = 64
        const val MIN_BACKGROUND_BLUR = 0
        const val MAX_BACKGROUND_BLUR = 100
        const val DEFAULT_BACKGROUND_BLUR = 20
        val DEFAULT_SEPARATORS = setOf(";", ",")
    }
}

private fun Set<String>.normalizedSeparators(): Set<String> =
    map(String::trim).filter(String::isNotEmpty).toSet().ifEmpty { AppSettings.DEFAULT_SEPARATORS }
