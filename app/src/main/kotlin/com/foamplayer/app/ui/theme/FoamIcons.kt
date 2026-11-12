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

/**
 * One semantic role of the app's icons (play, search, ...). [FoamIconSet]'s
 * named accessors and the icon settings screen's preview grid both read this
 * enum, so a new role is one member + one accessor + one default assignment.
 */
enum class FoamIconRole(val label: String) {
    PLAY("Play"),
    PAUSE("Pause"),
    NEXT("Next"),
    PREVIOUS("Previous"),
    LYRICS("Lyrics"),
    QUEUE("Queue"),
    CAST("Cast"),
    RADIO("Radio"),
    REPEAT("Repeat"),
    REPEAT_ONE("Repeat one"),
    HOME("Home"),
    LIBRARY("Library"),
    PLAYLISTS("Playlists"),
    SEARCH("Search"),
    EQUALIZER("Equalizer"),
    STATS("Stats"),
    SETTINGS("Settings"),
    DRAG_HANDLE("Drag handle"),
    MORE("More"),
    CLOSE("Close"),
    DELETE("Delete"),
    ARROW_FORWARD("Forward"),
    CHEVRON_RIGHT("Chevron right"),
    SHUFFLE("Shuffle"),
    MISC("Misc"),
    UI("UI"),
    INFO("Info"),
    REPAIR("Repair")
}

/**
 * One app icon, per semantic role. Backed by a role-to-vector map so bundled
 * icon packs can override individual roles while every role keeps resolving.
 * The named accessors below are the call-site compatible surface (the same
 * names the flat properties used before), [get] reads a role dynamically and
 * [with] overlays one role for the icon packs.
 */
@Immutable
data class FoamIconSet(private val icons: Map<FoamIconRole, ImageVector>) {
    val play: ImageVector get() = icons.getValue(FoamIconRole.PLAY)
    val pause: ImageVector get() = icons.getValue(FoamIconRole.PAUSE)
    val next: ImageVector get() = icons.getValue(FoamIconRole.NEXT)
    val previous: ImageVector get() = icons.getValue(FoamIconRole.PREVIOUS)
    val lyrics: ImageVector get() = icons.getValue(FoamIconRole.LYRICS)
    val queue: ImageVector get() = icons.getValue(FoamIconRole.QUEUE)
    val cast: ImageVector get() = icons.getValue(FoamIconRole.CAST)
    val radio: ImageVector get() = icons.getValue(FoamIconRole.RADIO)
    val repeat: ImageVector get() = icons.getValue(FoamIconRole.REPEAT)
    val repeatOne: ImageVector get() = icons.getValue(FoamIconRole.REPEAT_ONE)
    val home: ImageVector get() = icons.getValue(FoamIconRole.HOME)
    val library: ImageVector get() = icons.getValue(FoamIconRole.LIBRARY)
    val playlists: ImageVector get() = icons.getValue(FoamIconRole.PLAYLISTS)
    val search: ImageVector get() = icons.getValue(FoamIconRole.SEARCH)
    val equalizer: ImageVector get() = icons.getValue(FoamIconRole.EQUALIZER)
    val stats: ImageVector get() = icons.getValue(FoamIconRole.STATS)
    val settings: ImageVector get() = icons.getValue(FoamIconRole.SETTINGS)
    val dragHandle: ImageVector get() = icons.getValue(FoamIconRole.DRAG_HANDLE)
    val more: ImageVector get() = icons.getValue(FoamIconRole.MORE)
    val close: ImageVector get() = icons.getValue(FoamIconRole.CLOSE)
    val delete: ImageVector get() = icons.getValue(FoamIconRole.DELETE)
    val arrowForward: ImageVector get() = icons.getValue(FoamIconRole.ARROW_FORWARD)
    val chevronRight: ImageVector get() = icons.getValue(FoamIconRole.CHEVRON_RIGHT)
    val shuffle: ImageVector get() = icons.getValue(FoamIconRole.SHUFFLE)
    val misc: ImageVector get() = icons.getValue(FoamIconRole.MISC)
    val ui: ImageVector get() = icons.getValue(FoamIconRole.UI)
    val info: ImageVector get() = icons.getValue(FoamIconRole.INFO)
    val repair: ImageVector get() = icons.getValue(FoamIconRole.REPAIR)

    /** The icon of [role]; the dynamic twin of the matching named accessor. */
    operator fun get(role: FoamIconRole): ImageVector = icons.getValue(role)

    /** A copy of this set with [role] overlaid by [vector] (icon packs). */
    fun with(role: FoamIconRole, vector: ImageVector): FoamIconSet =
        FoamIconSet(icons + (role to vector))

    companion object {
        /** Builds a set from role-to-vector pairs; missing roles fail on access. */
        fun of(vararg pairs: Pair<FoamIconRole, ImageVector>): FoamIconSet =
            FoamIconSet(pairs.toMap())
    }
}

/** The three icon styles and which one is active. */
@Immutable
data class FoamIcons(
    val solid: FoamIconSet,
    val outline: FoamIconSet,
    val flat: FoamIconSet,
    val currentStyle: FoamIconStyle,
) {
    val current: FoamIconSet
        get() = setFor(currentStyle)

    /** The set of [style] (the style preview chips read the other two too). */
    fun setFor(style: FoamIconStyle): FoamIconSet = when (style) {
        FoamIconStyle.SOLID -> solid
        FoamIconStyle.OUTLINE -> outline
        FoamIconStyle.FLAT -> flat
    }
}

val DefaultFoamIcons = FoamIcons(
    solid = FoamIconSet.of(
        FoamIconRole.PLAY to Icons.Filled.PlayArrow,
        FoamIconRole.PAUSE to Icons.Filled.Pause,
        FoamIconRole.NEXT to Icons.Filled.FastForward,
        FoamIconRole.PREVIOUS to Icons.Filled.FastRewind,
        FoamIconRole.LYRICS to Icons.Filled.Lyrics,
        FoamIconRole.QUEUE to Icons.AutoMirrored.Filled.PlaylistPlay,
        FoamIconRole.CAST to Icons.Filled.Cast,
        FoamIconRole.RADIO to Icons.Filled.Radio,
        FoamIconRole.REPEAT to Icons.Filled.Repeat,
        FoamIconRole.REPEAT_ONE to Icons.Filled.RepeatOne,
        FoamIconRole.HOME to Icons.Filled.Home,
        FoamIconRole.LIBRARY to Icons.Filled.LibraryMusic,
        FoamIconRole.PLAYLISTS to Icons.AutoMirrored.Filled.QueueMusic,
        FoamIconRole.SEARCH to Icons.Filled.Search,
        FoamIconRole.EQUALIZER to Icons.Filled.GraphicEq,
        FoamIconRole.STATS to Icons.Filled.BarChart,
        FoamIconRole.SETTINGS to Icons.Filled.Settings,
        FoamIconRole.DRAG_HANDLE to Icons.Filled.DragHandle,
        FoamIconRole.MORE to Icons.Filled.Menu,
        FoamIconRole.CLOSE to Icons.Filled.Close,
        FoamIconRole.DELETE to Icons.Filled.Delete,
        FoamIconRole.ARROW_FORWARD to Icons.AutoMirrored.Filled.ArrowForward,
        FoamIconRole.CHEVRON_RIGHT to Icons.Filled.ChevronRight,
        FoamIconRole.SHUFFLE to Icons.Filled.Shuffle,
        FoamIconRole.MISC to Icons.Filled.Extension,
        FoamIconRole.UI to Icons.Filled.Palette,
        FoamIconRole.INFO to Icons.Filled.Info,
        FoamIconRole.REPAIR to Icons.Filled.Build
    ),
    outline = FoamIconSet.of(
        FoamIconRole.PLAY to Icons.Outlined.PlayArrow,
        FoamIconRole.PAUSE to Icons.Outlined.Pause,
        FoamIconRole.NEXT to Icons.Outlined.FastForward,
        FoamIconRole.PREVIOUS to Icons.Outlined.FastRewind,
        FoamIconRole.LYRICS to Icons.Outlined.Lyrics,
        FoamIconRole.QUEUE to Icons.AutoMirrored.Outlined.PlaylistPlay,
        FoamIconRole.CAST to Icons.Outlined.Cast,
        FoamIconRole.RADIO to Icons.Outlined.Radio,
        FoamIconRole.REPEAT to Icons.Outlined.Repeat,
        FoamIconRole.REPEAT_ONE to Icons.Outlined.RepeatOne,
        FoamIconRole.HOME to Icons.Outlined.Home,
        FoamIconRole.LIBRARY to Icons.Outlined.LibraryMusic,
        FoamIconRole.PLAYLISTS to Icons.AutoMirrored.Outlined.QueueMusic,
        FoamIconRole.SEARCH to Icons.Outlined.Search,
        FoamIconRole.EQUALIZER to Icons.Outlined.GraphicEq,
        FoamIconRole.STATS to Icons.Outlined.BarChart,
        FoamIconRole.SETTINGS to Icons.Outlined.Settings,
        FoamIconRole.DRAG_HANDLE to Icons.Outlined.DragHandle,
        FoamIconRole.MORE to Icons.Outlined.Menu,
        FoamIconRole.CLOSE to Icons.Outlined.Close,
        FoamIconRole.DELETE to Icons.Outlined.Delete,
        FoamIconRole.ARROW_FORWARD to Icons.AutoMirrored.Outlined.ArrowForward,
        FoamIconRole.CHEVRON_RIGHT to Icons.Outlined.ChevronRight,
        FoamIconRole.SHUFFLE to Icons.Outlined.Shuffle,
        FoamIconRole.MISC to Icons.Outlined.Extension,
        FoamIconRole.UI to Icons.Outlined.Palette,
        FoamIconRole.INFO to Icons.Outlined.Info,
        FoamIconRole.REPAIR to Icons.Outlined.Build
    ),
    flat = FoamIconSet.of(
        FoamIconRole.PLAY to Icons.Rounded.PlayArrow,
        FoamIconRole.PAUSE to Icons.Rounded.Pause,
        FoamIconRole.NEXT to Icons.Rounded.FastForward,
        FoamIconRole.PREVIOUS to Icons.Rounded.FastRewind,
        FoamIconRole.LYRICS to Icons.Rounded.Lyrics,
        FoamIconRole.QUEUE to Icons.AutoMirrored.Rounded.PlaylistPlay,
        FoamIconRole.CAST to Icons.Rounded.Cast,
        FoamIconRole.RADIO to Icons.Rounded.Radio,
        FoamIconRole.REPEAT to Icons.Rounded.Repeat,
        FoamIconRole.REPEAT_ONE to Icons.Rounded.RepeatOne,
        FoamIconRole.HOME to Icons.Rounded.Home,
        FoamIconRole.LIBRARY to Icons.Rounded.LibraryMusic,
        FoamIconRole.PLAYLISTS to Icons.AutoMirrored.Rounded.QueueMusic,
        FoamIconRole.SEARCH to Icons.Rounded.Search,
        FoamIconRole.EQUALIZER to Icons.Rounded.GraphicEq,
        FoamIconRole.STATS to Icons.Rounded.BarChart,
        FoamIconRole.SETTINGS to Icons.Rounded.Settings,
        FoamIconRole.DRAG_HANDLE to Icons.Rounded.DragHandle,
        FoamIconRole.MORE to Icons.Rounded.Menu,
        FoamIconRole.CLOSE to Icons.Rounded.Close,
        FoamIconRole.DELETE to Icons.Rounded.Delete,
        FoamIconRole.ARROW_FORWARD to Icons.AutoMirrored.Rounded.ArrowForward,
        FoamIconRole.CHEVRON_RIGHT to Icons.Rounded.ChevronRight,
        FoamIconRole.SHUFFLE to Icons.Rounded.Shuffle,
        FoamIconRole.MISC to Icons.Rounded.Extension,
        FoamIconRole.UI to Icons.Rounded.Palette,
        FoamIconRole.INFO to Icons.Rounded.Info,
        FoamIconRole.REPAIR to Icons.Rounded.Build
    ),
    currentStyle = FoamIconStyle.FLAT
)

val LocalFoamIcons = staticCompositionLocalOf { DefaultFoamIcons }
