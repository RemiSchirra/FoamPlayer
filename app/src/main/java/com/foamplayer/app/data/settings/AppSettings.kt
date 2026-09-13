package com.foamplayer.app.data.settings

import com.foamplayer.app.constants.DefaultLyricsProviderOrder
import com.foamplayer.app.constants.DefaultNavBarPrimaryOrder
import com.foamplayer.app.constants.DefaultNavBarScreenItems
import com.foamplayer.app.constants.MediaDensity
import com.foamplayer.app.constants.MediaListView
import com.foamplayer.app.constants.NavBarItem
import com.foamplayer.app.constants.NavigationBarMode
import com.foamplayer.app.constants.PreferredLyricsProvider

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
    val miniPlayerRounding: Int = 40,
    val fullPlayerRounding: Int = 40,
    val artistSeparators: Set<String> = DEFAULT_SEPARATORS,
    val genreSeparators: Set<String> = DEFAULT_SEPARATORS,
    val automaticColorEnabled: Boolean = true,
    val accentColor: Int = DEFAULT_ACCENT_COLOR,
    val immersiveModeEnabled: Boolean = true,
    val animateSeekBarEnabled: Boolean = false,
    val autoOpenPlayerEnabled: Boolean = false,
    val dynamicBackgroundEnabled: Boolean = false,
    val backgroundBlur: Int = DEFAULT_BACKGROUND_BLUR,
    val dynamicBackgroundIntensity: Int = DEFAULT_DYNAMIC_BACKGROUND_INTENSITY,
    val lyricsProviderOrder: List<PreferredLyricsProvider> = DefaultLyricsProviderOrder,
    val enabledLyricsProviders: Set<PreferredLyricsProvider> = PreferredLyricsProvider.entries.toSet(),
    val searchHistory: List<String> = emptyList(),
    val language: String = "auto",
    val navigationBarMode: NavigationBarMode = NavigationBarMode.ATTACHED,
    val navBarTransparentWhenPlayerOpen: Boolean = false,
    val navBarPrimaryOrder: List<NavBarItem> = DefaultNavBarPrimaryOrder,
    val navBarPrimaryEnabled: Set<NavBarItem> = DefaultNavBarScreenItems.toSet(),
    val navBarSecondaryOrder: List<NavBarItem> = DefaultNavBarScreenItems,
    val navBarSecondaryEnabled: Set<NavBarItem> = emptySet(),
    /** Per-view media list density; views missing from the map fall back to [MediaDensity.ROW_NORMAL]. */
    val mediaDensities: Map<MediaListView, MediaDensity> = emptyMap()
) {
    /**
     * Items of the main bar, in the user's configured order. Includes the [NavBarItem.MORE]
     * positional marker (the toggle itself is only rendered while the second bar is non-empty).
     */
    val primaryNavBarItems: List<NavBarItem>
        get() = navBarPrimaryOrder.filter { it == NavBarItem.MORE || it in navBarPrimaryEnabled }

    /** Enabled items of the second bar (the row the "more" button reveals). */
    val secondaryNavBarItems: List<NavBarItem> get() = navBarSecondaryOrder.filter { it in navBarSecondaryEnabled }

    /** The [MediaDensity] remembered by [view]; every view starts as the classic row. */
    fun mediaDensity(view: MediaListView): MediaDensity = mediaDensities[view] ?: MediaDensity.ROW_NORMAL

    fun sanitized(): AppSettings {
        // The "more" toggle is never a switchable item; its visibility is derived
        // from the second bar's content.
        val primaryEnabled = navBarPrimaryEnabled.filter { it in DefaultNavBarScreenItems }.toSet()
        val secondaryEnabled = navBarSecondaryEnabled.filter { it in DefaultNavBarScreenItems }.toSet()

        // The settings entry must exist in at least one of the two bars.
        val finalPrimaryEnabled =
            if (NavBarItem.SETTINGS in primaryEnabled || NavBarItem.SETTINGS in secondaryEnabled) {
                primaryEnabled
            } else {
                primaryEnabled + NavBarItem.SETTINGS
            }

        return copy(
            libraryFolders = libraryFolders.map(String::trim).filter(String::isNotEmpty).toSet(),
            miniPlayerRounding = miniPlayerRounding.coerceIn(MIN_ROUNDING, MAX_ROUNDING),
            fullPlayerRounding = fullPlayerRounding.coerceIn(MIN_ROUNDING, MAX_ROUNDING),
            backgroundBlur = backgroundBlur.coerceIn(MIN_BACKGROUND_BLUR, MAX_BACKGROUND_BLUR),
            dynamicBackgroundIntensity = dynamicBackgroundIntensity.coerceIn(
                MIN_DYNAMIC_BACKGROUND_INTENSITY,
                MAX_DYNAMIC_BACKGROUND_INTENSITY
            ),
            artistSeparators = artistSeparators.normalizedSeparators(),
            genreSeparators = genreSeparators.normalizedSeparators(),
            navBarPrimaryOrder = normalizedNavBarOrder(navBarPrimaryOrder, DefaultNavBarPrimaryOrder),
            navBarPrimaryEnabled = finalPrimaryEnabled,
            navBarSecondaryOrder = normalizedNavBarOrder(navBarSecondaryOrder, DefaultNavBarScreenItems),
            navBarSecondaryEnabled = secondaryEnabled
        )
    }

    companion object {
        // 0xFFFF5252 (the app's existing default red) as a signed ARGB Int.
        const val DEFAULT_ACCENT_COLOR = -44_462
        const val MIN_ROUNDING = 0
        const val MAX_ROUNDING = 100
        const val MIN_BACKGROUND_BLUR = 0
        const val MAX_BACKGROUND_BLUR = 100
        const val DEFAULT_BACKGROUND_BLUR = 20
        const val MIN_DYNAMIC_BACKGROUND_INTENSITY = 0
        const val MAX_DYNAMIC_BACKGROUND_INTENSITY = 100
        const val DEFAULT_DYNAMIC_BACKGROUND_INTENSITY = 50
        val DEFAULT_SEPARATORS = setOf(";", ",")
    }
}

private fun Set<String>.normalizedSeparators(): Set<String> =
    map(String::trim).filter(String::isNotEmpty).toSet().ifEmpty { AppSettings.DEFAULT_SEPARATORS }

/** Completes a stored nav-bar order so it contains every [allowed] item exactly once. */
private fun normalizedNavBarOrder(order: List<NavBarItem>, allowed: List<NavBarItem>): List<NavBarItem> {
    val parsed = order.filter { it in allowed }.distinct()
    return parsed + allowed.filterNot { it in parsed }
}
