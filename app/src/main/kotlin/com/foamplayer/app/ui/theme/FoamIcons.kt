package com.foamplayer.app.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector
import com.foamplayer.app.data.settings.FoamIconStyle

/** One app icon, per semantic role. */
@Immutable
data class FoamIconSet(
    val play: ImageVector,
    val pause: ImageVector,
    val next: ImageVector,
    val previous: ImageVector,
    val lyrics: ImageVector,
    val queue: ImageVector,
    val cast: ImageVector,
    val radio: ImageVector,
    val repeat: ImageVector,
    val repeatOne: ImageVector,
    val home: ImageVector,
    val library: ImageVector,
    val playlists: ImageVector,
    val search: ImageVector,
    val equalizer: ImageVector,
    val stats: ImageVector,
    val settings: ImageVector,
    val dragHandle: ImageVector,
    val more: ImageVector,
    val close: ImageVector,
    val delete: ImageVector,
    val arrowForward: ImageVector,
    val shuffle: ImageVector,
    val misc: ImageVector,
    val ui: ImageVector,
    val info: ImageVector,
)

/** The three icon styles and which one is active. */
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
        next = Icons.Filled.FastForward,
        previous = Icons.Filled.FastRewind,
        lyrics = Icons.Filled.Lyrics,
        queue = Icons.AutoMirrored.Filled.PlaylistPlay,
        cast = Icons.Filled.Cast,
        radio = Icons.Filled.Radio,
        repeat = Icons.Filled.Repeat,
        repeatOne = Icons.Filled.RepeatOne,
        home = Icons.Filled.Home,
        library = Icons.Filled.LibraryMusic,
        playlists = Icons.AutoMirrored.Filled.QueueMusic,
        search = Icons.Filled.Search,
        equalizer = Icons.Filled.GraphicEq,
        stats = Icons.Filled.BarChart,
        settings = Icons.Filled.Settings,
        dragHandle = Icons.Filled.DragHandle,
        more = Icons.Filled.Menu,
        close = Icons.Filled.Close,
        delete = Icons.Filled.Delete,
        arrowForward = Icons.AutoMirrored.Filled.ArrowForward,
        shuffle = Icons.Filled.Shuffle,
        misc = Icons.Filled.Extension,
        ui = Icons.Filled.Palette,
        info = Icons.Filled.Info
    ),
    outline = FoamIconSet(
        play = Icons.Outlined.PlayArrow,
        pause = Icons.Outlined.Pause,
        next = Icons.Outlined.FastForward,
        previous = Icons.Outlined.FastRewind,
        lyrics = Icons.Outlined.Lyrics,
        queue = Icons.AutoMirrored.Outlined.PlaylistPlay,
        cast = Icons.Outlined.Cast,
        radio = Icons.Outlined.Radio,
        repeat = Icons.Outlined.Repeat,
        repeatOne = Icons.Outlined.RepeatOne,
        home = Icons.Outlined.Home,
        library = Icons.Outlined.LibraryMusic,
        playlists = Icons.AutoMirrored.Outlined.QueueMusic,
        search = Icons.Outlined.Search,
        equalizer = Icons.Outlined.GraphicEq,
        stats = Icons.Outlined.BarChart,
        settings = Icons.Outlined.Settings,
        dragHandle = Icons.Outlined.DragHandle,
        more = Icons.Outlined.Menu,
        close = Icons.Outlined.Close,
        delete = Icons.Outlined.Delete,
        arrowForward = Icons.AutoMirrored.Outlined.ArrowForward,
        shuffle = Icons.Outlined.Shuffle,
        misc = Icons.Outlined.Extension,
        ui = Icons.Outlined.Palette,
        info = Icons.Outlined.Info
    ),
    flat = FoamIconSet(
        play = Icons.Rounded.PlayArrow,
        pause = Icons.Rounded.Pause,
        next = Icons.Rounded.FastForward,
        previous = Icons.Rounded.FastRewind,
        lyrics = Icons.Rounded.Lyrics,
        queue = Icons.AutoMirrored.Rounded.PlaylistPlay,
        cast = Icons.Rounded.Cast,
        radio = Icons.Rounded.Radio,
        repeat = Icons.Rounded.Repeat,
        repeatOne = Icons.Rounded.RepeatOne,
        home = Icons.Rounded.Home,
        library = Icons.Rounded.LibraryMusic,
        playlists = Icons.AutoMirrored.Rounded.QueueMusic,
        search = Icons.Rounded.Search,
        equalizer = Icons.Rounded.GraphicEq,
        stats = Icons.Rounded.BarChart,
        settings = Icons.Rounded.Settings,
        dragHandle = Icons.Rounded.DragHandle,
        more = Icons.Rounded.Menu,
        close = Icons.Rounded.Close,
        delete = Icons.Rounded.Delete,
        arrowForward = Icons.AutoMirrored.Rounded.ArrowForward,
        shuffle = Icons.Rounded.Shuffle,
        misc = Icons.Rounded.Extension,
        ui = Icons.Rounded.Palette,
        info = Icons.Rounded.Info
    ),
    currentStyle = FoamIconStyle.FLAT
)

val LocalFoamIcons = staticCompositionLocalOf { DefaultFoamIcons }
