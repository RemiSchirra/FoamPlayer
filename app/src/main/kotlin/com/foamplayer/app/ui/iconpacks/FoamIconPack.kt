package com.foamplayer.app.ui.iconpacks

import com.foamplayer.app.R
import com.foamplayer.app.data.settings.DEFAULT_ICON_PACK_ID
import com.foamplayer.app.data.settings.FoamIconStyle
import com.foamplayer.app.ui.theme.FoamIconRole

/**
 * A bundled icon pack: a set of drawable resources overlaying individual
 * [FoamIconRole]s on top of the default icon set. Packs are partial by
 * design — [resFor] returning null keeps the default icon for that role,
 * so every role always resolves.
 */
interface FoamIconPack {
    /** Stable id persisted in the settings ([DEFAULT_ICON_PACK_ID] is the built-in pack). */
    val id: String

    /** Human-readable name shown in the icon settings picker. */
    val label: String

    /** Drawable res shown in the settings picker, or null to use the default set's play icon. */
    val previewRes: Int?

    /** Drawable res for a role, or null to fall back to the default icon for that role. */
    fun resFor(style: FoamIconStyle, role: FoamIconRole): Int?

    /** The icon styles (variants) this pack draws; shown as pills in the picker. */
    val variants: List<FoamIconStyle>

    /**
     * The style actually rendered for [preferred]: the preference when this
     * pack draws it, else the pack's first variant.
     */
    fun effectiveVariant(preferred: FoamIconStyle): FoamIconStyle =
        if (preferred in variants) preferred else variants.firstOrNull() ?: FoamIconStyle.FLAT
}

/** The built-in set: no overrides, the app's default icons render everywhere. */
object DefaultIconPack : FoamIconPack {
    override val id: String = DEFAULT_ICON_PACK_ID
    override val label: String = "Default"
    override val previewRes: Int? = null
    override val variants: List<FoamIconStyle> = FoamIconStyle.entries
    override fun resFor(style: FoamIconStyle, role: FoamIconRole): Int? = null
}

object CooliconsIconPack : FoamIconPack {
    override val id: String = "coolicons"
    override val label: String = "Coolicons"
    override val previewRes: Int = R.drawable.coolicons_play
    override val variants: List<FoamIconStyle> = listOf(FoamIconStyle.FLAT, FoamIconStyle.OUTLINE)

    override fun resFor(style: FoamIconStyle, role: FoamIconRole): Int? =
        (if (style == FoamIconStyle.OUTLINE) resOutline else res)[role]

    private val res = mapOf(
        FoamIconRole.PLAY to R.drawable.coolicons_play,
        FoamIconRole.PAUSE to R.drawable.coolicons_pause,
        FoamIconRole.NEXT to R.drawable.coolicons_next,
        FoamIconRole.PREVIOUS to R.drawable.coolicons_previous,
        FoamIconRole.SEARCH to R.drawable.coolicons_search,
        FoamIconRole.EQUALIZER to R.drawable.coolicons_equalizer,
        FoamIconRole.HOME to R.drawable.coolicons_home,
        FoamIconRole.SETTINGS to R.drawable.coolicons_settings,
        FoamIconRole.STATS to R.drawable.coolicons_stats,
        FoamIconRole.LIBRARY to R.drawable.coolicons_library,
        FoamIconRole.PLAYLISTS to R.drawable.coolicons_playlists,
        FoamIconRole.QUEUE to R.drawable.coolicons_queue,
        FoamIconRole.LYRICS to R.drawable.coolicons_lyrics,
        FoamIconRole.CAST to R.drawable.coolicons_cast,
        FoamIconRole.RADIO to R.drawable.coolicons_radio,
        FoamIconRole.REPEAT to R.drawable.coolicons_repeat,
        FoamIconRole.REPEAT_ONE to R.drawable.coolicons_repeat_one,
        FoamIconRole.SHUFFLE to R.drawable.coolicons_shuffle,
        FoamIconRole.CLOSE to R.drawable.coolicons_close,
        FoamIconRole.DELETE to R.drawable.coolicons_trash,
        FoamIconRole.DRAG_HANDLE to R.drawable.coolicons_drag_handle,
        FoamIconRole.MORE to R.drawable.coolicons_more,
    )

    private val resOutline = mapOf(
        FoamIconRole.PLAY to R.drawable.coolicons_outline_play,
        FoamIconRole.PAUSE to R.drawable.coolicons_outline_pause,
        FoamIconRole.NEXT to R.drawable.coolicons_outline_next,
        FoamIconRole.PREVIOUS to R.drawable.coolicons_outline_previous,
        FoamIconRole.SEARCH to R.drawable.coolicons_outline_search,
        FoamIconRole.EQUALIZER to res[FoamIconRole.EQUALIZER],
        FoamIconRole.HOME to R.drawable.coolicons_outline_home,
        FoamIconRole.SETTINGS to R.drawable.coolicons_outline_settings,
        FoamIconRole.STATS to R.drawable.coolicons_outline_stats,
        FoamIconRole.LIBRARY to R.drawable.coolicons_outline_library,
        FoamIconRole.PLAYLISTS to R.drawable.coolicons_outline_playlists,
        FoamIconRole.QUEUE to R.drawable.coolicons_outline_queue,
        FoamIconRole.LYRICS to R.drawable.coolicons_outline_lyrics,
        FoamIconRole.CAST to res[FoamIconRole.CAST],
        FoamIconRole.RADIO to R.drawable.coolicons_outline_radio,
        FoamIconRole.REPEAT to res[FoamIconRole.REPEAT],
        FoamIconRole.REPEAT_ONE to res[FoamIconRole.REPEAT_ONE],
        FoamIconRole.SHUFFLE to res[FoamIconRole.SHUFFLE],
        FoamIconRole.CLOSE to res[FoamIconRole.CLOSE],
        FoamIconRole.DELETE to R.drawable.coolicons_outline_trash,
        FoamIconRole.DRAG_HANDLE to res[FoamIconRole.DRAG_HANDLE],
        FoamIconRole.MORE to res[FoamIconRole.MORE],
    )
}

/** The bundled packs, in picker order; [byId] falls back to the default pack. */
object FoamIconPackRegistry {
    /** The default pack's id, shared with the settings layer. */
    const val DEFAULT_ID = DEFAULT_ICON_PACK_ID

    /** Every pack bundled with the APK, in picker order. */
    val all: List<FoamIconPack> = listOf(DefaultIconPack, CooliconsIconPack)

    /** The pack of [id]; unknown ids (e.g. from older backups) resolve to the default pack. */
    fun byId(id: String): FoamIconPack = all.firstOrNull { it.id == id } ?: DefaultIconPack
}
