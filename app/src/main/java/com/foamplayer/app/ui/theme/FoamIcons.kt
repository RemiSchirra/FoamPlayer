package com.foamplayer.app.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector

enum class FoamIconStyle(val label: String) {
    SOLID("Solid"),
    OUTLINE("Outline"),
    FLAT("Flat")
}

@Immutable
data class FoamIconSet(
    val play: ImageVector,
    val pause: ImageVector,
    val next: ImageVector,
    val previous: ImageVector,
    val lyrics: ImageVector,
    val queue: ImageVector,
    val library: ImageVector,
    val playlists: ImageVector,
    val search: ImageVector,
    val equalizer: ImageVector,
    val settings: ImageVector
)

@Immutable
data class FoamIcons(
    val solid: FoamIconSet,
    val outline: FoamIconSet,
    val flat: FoamIconSet,
    val currentStyle: FoamIconStyle
) {
    val current: FoamIconSet
        get() = when (currentStyle) {
            FoamIconStyle.SOLID -> solid
            FoamIconStyle.OUTLINE -> outline
            FoamIconStyle.FLAT -> flat
        }
}

val DefaultFoamIcons = FoamIcons(
    solid = FoamIconSet(
        play = Icons.Filled.PlayArrow,
        pause = Icons.Filled.Pause,
        next = Icons.Filled.SkipNext,
        previous = Icons.Filled.SkipPrevious,
        lyrics = Icons.Filled.Lyrics,
        queue = Icons.AutoMirrored.Filled.PlaylistPlay,
        library = Icons.Filled.LibraryMusic,
        playlists = Icons.AutoMirrored.Filled.QueueMusic,
        search = Icons.Filled.Search,
        equalizer = Icons.Filled.GraphicEq,
        settings = Icons.Filled.Settings
    ),
    outline = FoamIconSet(
        play = Icons.Outlined.PlayArrow,
        pause = Icons.Outlined.Pause,
        next = Icons.Outlined.SkipNext,
        previous = Icons.Outlined.SkipPrevious,
        lyrics = Icons.Outlined.Lyrics,
        queue = Icons.AutoMirrored.Outlined.PlaylistPlay,
        library = Icons.Outlined.LibraryMusic,
        playlists = Icons.AutoMirrored.Outlined.QueueMusic,
        search = Icons.Outlined.Search,
        equalizer = Icons.Outlined.GraphicEq,
        settings = Icons.Outlined.Settings
    ),
    flat = FoamIconSet(
        play = Icons.Rounded.PlayArrow,
        pause = Icons.Rounded.Pause,
        next = Icons.Rounded.SkipNext,
        previous = Icons.Rounded.SkipPrevious,
        lyrics = Icons.Rounded.Lyrics,
        queue = Icons.AutoMirrored.Rounded.PlaylistPlay,
        library = Icons.Rounded.LibraryMusic,
        playlists = Icons.AutoMirrored.Rounded.QueueMusic,
        search = Icons.Rounded.Search,
        equalizer = Icons.Rounded.GraphicEq,
        settings = Icons.Rounded.Settings
    ),
    currentStyle = FoamIconStyle.FLAT
)

val LocalFoamIcons = staticCompositionLocalOf { DefaultFoamIcons }
