package com.foamplayer.app.ui.screens.equalizer

import com.foamplayer.squig.AutoEq
import com.foamplayer.squig.SquigDatabase
import com.foamplayer.squig.SquigDb
import com.foamplayer.squig.SquigDevice
import com.foamplayer.squig.SquigSite

/** Which EQ section a generated Auto EQ drives. */
enum class AutoEqOutputMode { PARAMETRIC, GRAPHIC }

/** One successful generation: the fitting plus the names it was computed from. */
data class AutoEqGeneration(
    val fromName: String,
    val targetName: String,
    val result: AutoEq.Result,
)

/** UI state of the Auto EQ flow inside the preset selection modal. */
data class AutoEqUiState(
    val sites: List<SquigSite> = emptyList(),
    val sitesLoading: Boolean = false,
    val sitesError: String? = null,
    val selectedSite: SquigSite? = null,
    val selectedDb: SquigDb? = null,
    val database: SquigDatabase? = null,
    val databaseLoading: Boolean = false,
    val databaseError: String? = null,
    val fromDevice: SquigDevice? = null,
    val targetDevice: SquigDevice? = null,
    val outputMode: AutoEqOutputMode = AutoEqOutputMode.PARAMETRIC,
    val generating: Boolean = false,
    val generateError: String? = null,
    val generation: AutoEqGeneration? = null,
) {
    /** Ready to generate: a loaded database and both devices picked. */
    val canGenerate: Boolean
        get() = database != null && fromDevice != null && targetDevice != null && !generating
}

/** Handed-down actions of the Auto EQ flow; the screen wires them to the ViewModel. */
class AutoEqActions(
    val loadSites: () -> Unit,
    val selectDatabase: (SquigSite, SquigDb) -> Unit,
    val selectFrom: (SquigDevice) -> Unit,
    val selectTarget: (SquigDevice) -> Unit,
    val setOutputMode: (AutoEqOutputMode) -> Unit,
    val generate: () -> Unit,
    val apply: () -> Unit,
)
