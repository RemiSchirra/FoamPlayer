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
}

/** The built-in set: no overrides, the app's default icons render everywhere. */
object DefaultIconPack : FoamIconPack {
    override val id: String = DEFAULT_ICON_PACK_ID
    override val label: String = "Default"
    override val previewRes: Int? = null
    override fun resFor(style: FoamIconStyle, role: FoamIconRole): Int? = null
}

object CooliconsIconPack : FoamIconPack {
    override val id: String = "coolicons"
    override val label: String = "Coolicons"
    override val previewRes: Int = R.drawable.coolicons_play

    override fun resFor(style: FoamIconStyle, role: FoamIconRole): Int? = res[role]

    private val res = mapOf(
        FoamIconRole.PLAY to R.drawable.coolicons_play,
        FoamIconRole.PAUSE to R.drawable.coolicons_pause,
        FoamIconRole.NEXT to R.drawable.coolicons_next,
        FoamIconRole.PREVIOUS to R.drawable.coolicons_previous,
        FoamIconRole.SEARCH to R.drawable.coolicons_search,
        FoamIconRole.HOME to R.drawable.coolicons_home,
        FoamIconRole.SETTINGS to R.drawable.coolicons_settings,
        FoamIconRole.STATS to R.drawable.coolicons_stats,
        FoamIconRole.LIBRARY to R.drawable.coolicons_library,
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
