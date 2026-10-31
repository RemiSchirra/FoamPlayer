package com.foamplayer.app.ui.modals

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foamplayer.app.R
import com.foamplayer.app.constants.MediaDensity
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.ui.components.TrackListItem
import com.foamplayer.app.ui.components.albumArtCornerRadius
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.theme.adaptAccentForTheme
import com.foamplayer.app.ui.theme.foamBackground
import com.foamplayer.app.ui.utils.AlbumArtPaletteCache

/** Vertical gap between the radio sheet's blocks, matching TrackFoamModal. */
private val FoamTrackRadioSpacing = FoamDimens.spacingSmall

/** Art size of the header's radio chip, matching the row scaffold's artwork. */
private val TrackRadioChipSize = 44.dp

/** Size of the round play button in the radio header. */
private val TrackRadioPlaySize = 40.dp

/** Composed height of one ROW_NORMAL [TrackListItem] row, for list sizing. */
private val TrackRadioRowHeight = 60.dp

/** Screen height reserved for the header and sheet chrome when sizing the list. */
private val TrackRadioHeaderReserve = 220.dp

/**
 * Song-radio sheet for one seed track. Layout, top to bottom:
 * - A header row on a darker container: the radio glyph in an accent chip
 *   (accent = the seed track's own album-art color when the automatic-color
 *   setting is on, else the app accent), the "Radio" title over the seed
 *   track's name, and a round play button that starts the radio from the seed
 *   track.
 * - The radio list rendered as standard [TrackListItem] rows — the seed track
 *   first, then similar tracks in random order, plain rows without any card
 *   wrapper. Tapping a row starts the radio from it, handing the list to the
 *   player, which replaces the upcoming queue with it.
 *
 * The list itself is provided by [TrackRadioViewModel]; this composable is pure
 * rendering. [visible] follows the ViewModel's open state, while the internally
 * kept last-shown track keeps the header rendering while the exit animation
 * plays (FoamBottomModal stays mounted through it, with the state already
 * cleared).
 */
@Composable
fun TrackRadioModal(
    visible: Boolean,
    sourceTrack: TrackEntity?,
    tracks: List<TrackEntity>,
    isLoading: Boolean,
    useAlbumArtAccent: Boolean,
    onPlay: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var lastShownTrack by remember { mutableStateOf<TrackEntity?>(null) }
    SideEffect {
        if (sourceTrack != null) lastShownTrack = sourceTrack
    }
    val displayTrack = sourceTrack ?: lastShownTrack

    val context = LocalContext.current
    LaunchedEffect(useAlbumArtAccent, displayTrack?.albumArtPath) {
        if (useAlbumArtAccent) {
            AlbumArtPaletteCache.ensure(this, context, displayTrack?.albumArtPath)
        }
    }
    val artAccent = if (useAlbumArtAccent) {
        displayTrack?.albumArtPath?.let { AlbumArtPaletteCache.colors[it] }
    } else {
        null
    }
    val accentColor = artAccent?.adaptAccentForTheme(FoamTheme.isDark) ?: FoamTheme.accentColor

    FoamBottomModal(
        visible = visible,
        onDismissRequest = onDismiss,
        modifier = modifier
    ) {
        if (displayTrack != null) {
            Column(
                modifier = Modifier.padding(bottom = FoamDimens.spacingSmall),
                verticalArrangement = Arrangement.spacedBy(FoamTrackRadioSpacing)
            ) {
                TrackRadioHeader(
                    track = displayTrack,
                    accentColor = accentColor,
                    isPlayEnabled = !isLoading,
                    onPlay = { onPlay(0) }
                )

                when {
                    isLoading && tracks.isEmpty() -> TrackRadioLoading()
                    !isLoading && tracks.isEmpty() -> TrackRadioEmpty()
                    else -> TrackRadioList(tracks = tracks, onPlayFrom = onPlay)
                }
            }
        }
    }
}

/** Header row: radio chip, "Radio" title over the seed track's name, play button. */
@Composable
private fun TrackRadioHeader(
    track: TrackEntity,
    accentColor: Color,
    isPlayEnabled: Boolean,
    onPlay: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FoamDimens.itemRounding))
            .foamBackground()
            .padding(FoamDimens.spacingMedium),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(TrackRadioChipSize)
                .clip(RoundedCornerShape(albumArtCornerRadius(TrackRadioChipSize)))
                .background(accentColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = FoamTheme.icons.current.radio,
                contentDescription = stringResource(R.string.action_radio),
                tint = FoamTheme.colors.background,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(FoamDimens.spacingMedium))

        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = stringResource(R.string.action_radio),
                style = FoamTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            BasicText(
                text = track.title,
                style = FoamTheme.typography.body.copy(color = FoamTheme.colors.textSecondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(FoamDimens.spacingSmall))

        TrackRadioPlayButton(
            accentColor = accentColor,
            enabled = isPlayEnabled,
            onClick = onPlay
        )
    }
}

/** Play button that starts the radio from its seed track. */
@Composable
private fun TrackRadioPlayButton(
    accentColor: Color,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(TrackRadioPlaySize)
            .clip(CircleShape)
            .background(if (enabled) accentColor else FoamTheme.colors.borderOverlay)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = FoamTheme.colors.textPrimary),
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = FoamTheme.icons.current.play,
            contentDescription = stringResource(R.string.play),
            tint = FoamTheme.colors.background,
            modifier = Modifier.size(22.dp)
        )
    }
}

/** "No similar tracks" note, shown when nothing shares the seed track's genres. */
@Composable
private fun TrackRadioEmpty() {
    BasicText(
        text = stringResource(R.string.radio_no_similar_tracks),
        style = FoamTheme.typography.body.copy(color = FoamTheme.colors.textSecondary),
        modifier = Modifier.padding(horizontal = FoamDimens.paddingSmall)
    )
}

/** Brief spinner shown while the radio list is being built. */
@Composable
private fun TrackRadioLoading() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = FoamDimens.paddingLarge),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            color = FoamTheme.accentColor,
            modifier = Modifier.size(FoamDimens.iconLarge)
        )
    }
}

/**
 * The radio list: seed track first, then the similar tracks, as plain standard
 * [TrackListItem] rows without a wrapping card. A bounded [LazyColumn] keeps
 * composition to the visible rows only, so opening the sheet never composes
 * the whole list inside its entrance animation (the sheet's own verticalScroll
 * hands unbounded constraints, which a lazy list needs a definite height
 * against — the estimated content height caps at the free screen space, like
 * LyricsProvidersModal).
 */
@Composable
private fun TrackRadioList(
    tracks: List<TrackEntity>,
    onPlayFrom: (Int) -> Unit
) {
    val listHeight = minOf(
        TrackRadioRowHeight * tracks.size,
        (LocalConfiguration.current.screenHeightDp.dp - TrackRadioHeaderReserve)
            .coerceAtLeast(160.dp)
    )

    LazyColumn(
        state = rememberLazyListState(),
        modifier = Modifier
            .fillMaxWidth()
            .height(listHeight)
    ) {
        itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
            TrackListItem(
                track = track,
                onClick = { onPlayFrom(index) },
                density = MediaDensity.ROW_NORMAL
            )
        }
    }
}
