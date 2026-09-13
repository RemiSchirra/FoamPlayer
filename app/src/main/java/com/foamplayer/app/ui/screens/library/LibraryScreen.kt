package com.foamplayer.app.ui.screens.library

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.foamplayer.app.R
import com.foamplayer.app.constants.MediaDensity
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.ui.components.DensityOrganisationModal
import com.foamplayer.app.ui.components.OrganisationMenuButton
import com.foamplayer.app.ui.components.TrackListItem
import com.foamplayer.app.ui.theme.FoamTheme

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onTrackClick: (List<TrackEntity>, Int) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    sharedTrackId: Long?,
    activeTrackId: Long?,
    density: MediaDensity = MediaDensity.ROW_NORMAL,
    onDensityChange: ((MediaDensity) -> Unit)? = null
) {
    val tracks by viewModel.tracks.collectAsState()
    var showOrganisationModal by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText(
                text = stringResource(R.string.audio_library),
                style = FoamTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            if (onDensityChange != null) {
                OrganisationMenuButton(
                    contentDescription = stringResource(R.string.more_options),
                    onClick = { showOrganisationModal = true }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (tracks.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                BasicText(
                    text = stringResource(R.string.no_audio_detected),
                    style = FoamTheme.typography.body,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else if (density.isGrid) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(density.gridColumns),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                gridItemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                    TrackListItem(
                        track = track,
                        onClick = { onTrackClick(tracks, index) },
                        active = track.id == activeTrackId,
                        density = density,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        isSharedTransitionSource = track.id == sharedTrackId
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                    TrackListItem(
                        track = track,
                        onClick = { onTrackClick(tracks, index) },
                        active = track.id == activeTrackId,
                        density = density,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        isSharedTransitionSource = track.id == sharedTrackId
                    )
                }
            }
        }
    }

    if (onDensityChange != null) {
        DensityOrganisationModal(
            visible = showOrganisationModal,
            currentDensity = density,
            onSelectDensity = onDensityChange,
            onDismiss = { showOrganisationModal = false }
        )
    }
}
