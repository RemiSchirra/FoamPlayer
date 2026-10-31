package com.foamplayer.app.ui.screens.settings.lyricsmanager

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.foamplayer.app.R
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.lyrics.LyricsResultType
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import com.foamplayer.app.lyrics.LyricsUtils
import com.foamplayer.app.ui.modals.FoamBottomModal
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.theme.foamSurfaceVariant
import com.foamplayer.app.ui.utils.TimeUtils

/**
 * Long-press modal for a song in the lyrics manager: lyrics type (word-synced /
 * synced / plain), provider, per-source availability (file / embedded / cached)
 * and the full lyrics text in a scrollable container, with a delete-lyrics
 * action pinned at the bottom. Lyrics are read from local sources only (cache,
 * sidecar file, embedded tag) — when none has any, a prompt offers to run the
 * online lyrics search instead.
 */
@Composable
fun LyricsTrackDetailsModal(
    visible: Boolean,
    track: TrackEntity?,
    details: LyricsTrackDetails?,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onSearch: () -> Unit
) {
    FoamBottomModal(
        visible = visible && track != null,
        onDismissRequest = onDismiss
    ) {
        if (track == null) return@FoamBottomModal

        Column(modifier = Modifier.fillMaxWidth()) {
            ModalHeader(track = track)

            Spacer(modifier = Modifier.height(FoamDimens.spacingSmall))

            when {
                isLoading && details == null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = FoamDimens.paddingLarge),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = FoamTheme.accentColor)
                    }
                }
                else -> {
                    details?.let { loaded ->
                        if (loaded.track.id != track.id) return@let

                        if (loaded.content == null) {
                            // Nothing in the local sources: offer the online search.
                            NoLyricsPrompt(onSearch = onSearch)
                        } else {
                            LyricsMetaChips(details = loaded)
                            Spacer(modifier = Modifier.height(FoamDimens.spacingSmall))

                            LyricsScrollContainer(content = loaded.content)

                            Spacer(modifier = Modifier.height(FoamDimens.paddingMedium))

                            DeleteLyricsButton(
                                enabled = !isLoading,
                                onClick = onDelete
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModalHeader(track: TrackEntity) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(FoamDimens.buttonRounding))
                .foamSurfaceVariant(),
            contentAlignment = Alignment.Center
        ) {
            if (track.albumArtPath != null) {
                val context = LocalContext.current
                val albumArtRequest = remember(track.albumArtPath) {
                    ImageRequest.Builder(context)
                        .data(EmbeddedAlbumArt(track.albumArtPath.toUri()))
                        .size(128)
                        .build()
                }
                AsyncImage(
                    model = albumArtRequest,
                    contentDescription = stringResource(R.string.album_art),
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Crop
                )
            } else {
                BasicText(
                    text = track.format,
                    style = FoamTheme.typography.caption.copy(color = FoamTheme.accentColor)
                )
            }
        }

        Spacer(modifier = Modifier.width(FoamDimens.paddingSmall))

        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = track.title,
                style = FoamTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            BasicText(
                text = "${track.artist} • ${track.album} • ${TimeUtils.formatDurationCompact(track.duration)}",
                style = FoamTheme.typography.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Empty state: nothing in any local source — jump to the online lyrics search. */
@Composable
private fun NoLyricsPrompt(onSearch: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FoamDimens.buttonRounding))
            .foamSurfaceVariant()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = FoamTheme.colors.textPrimary)
            ) { onSearch() }
            .padding(FoamDimens.paddingMedium),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = FoamTheme.icons.current.search,
            contentDescription = null,
            tint = FoamTheme.accentColor,
            modifier = Modifier.size(FoamDimens.iconSmall)
        )
        Spacer(modifier = Modifier.width(FoamDimens.spacingSmall))
        BasicText(
            text = stringResource(R.string.lyrics_modal_search_prompt),
            style = FoamTheme.typography.body.copy(color = FoamTheme.colors.textPrimary)
        )
    }
}

/** Type chip + provider caption + per-source availability badges. */
@Composable
private fun LyricsMetaChips(details: LyricsTrackDetails) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)
    ) {
        val typeLabel = details.type?.let { type ->
            stringResource(
                when (type) {
                    LyricsResultType.PLAIN -> R.string.lyrics_results_plain
                    LyricsResultType.SYNCED -> R.string.lyrics_results_synced
                    LyricsResultType.WORD_SYNCED -> R.string.lyrics_results_word_synced
                }
            )
        }
        if (typeLabel != null) {
            MetaChip(label = typeLabel)
        }

        if (details.status.hasFile) {
            MetaChip(label = stringResource(R.string.lyrics_manager_file))
        }
        if (details.status.hasEmbedded) {
            MetaChip(label = stringResource(R.string.lyrics_manager_embedded))
        }
        if (details.status.hasCached) {
            MetaChip(label = stringResource(R.string.lyrics_manager_cached))
        }
    }

    details.providerName?.let { provider ->
        Spacer(modifier = Modifier.height(FoamDimens.spacingTiny))
        BasicText(
            text = "${stringResource(R.string.lyrics_results_provider)}: $provider",
            style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textSecondary)
        )
    }
}
@Composable
private fun MetaChip(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(FoamDimens.buttonRounding))
            .foamSurfaceVariant()
            .padding(horizontal = FoamDimens.paddingSmall, vertical = FoamDimens.spacingTiny)
    ) {
        BasicText(
            text = label,
            style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textPrimary)
        )
    }
}

/** Full lyrics text, readable (timestamps/XML stripped) or raw as stored, scrollable. */
@Composable
private fun LyricsScrollContainer(content: String?) {
    var showRaw by remember(content) { mutableStateOf(false) }
    val displayText = remember(content, showRaw) {
        when {
            content == null -> ""
            showRaw -> content
            else -> LyricsUtils.displayLyricsText(content)
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        LyricsFormatToggle(
            showRaw = showRaw,
            onToggle = { showRaw = it }
        )

        Spacer(modifier = Modifier.height(FoamDimens.spacingTiny))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .clip(RoundedCornerShape(FoamDimens.buttonRounding))
                .foamSurfaceVariant()
                .verticalScroll(rememberScrollState())
                .padding(FoamDimens.paddingSmall)
        ) {
            BasicText(
                text = displayText.ifBlank { stringResource(R.string.lyrics_not_found) },
                style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textPrimary),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun DeleteLyricsButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FoamDimens.buttonRounding))
            .background(
                if (enabled) FoamTheme.accentColor.copy(alpha = 0.15f) else FoamTheme.colors.surfaceVariant
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = FoamTheme.colors.textPrimary),
                enabled = enabled
            ) { onClick() }
            .padding(vertical = FoamDimens.paddingSmall),
        contentAlignment = Alignment.Center
    ) {
            BasicText(
                text = stringResource(R.string.lyrics_manager_delete),
                style = FoamTheme.typography.body.copy(
                    color = if (enabled) FoamTheme.accentColor else FoamTheme.colors.textSecondary
                )
            )
    }
}
