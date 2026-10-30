package com.foamplayer.app.ui.modals

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.foamplayer.app.R
import com.foamplayer.app.lastfm.LastFmImportProgress
import com.foamplayer.app.lastfm.LastFmUnmatchedTrack
import com.foamplayer.app.ui.screens.settings.LastFmImportUiState
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.theme.foamSurfaceVariant

/**
 * First sheet of the import flow: choose whether the current listening history
 * is kept or cleared before Last.fm's scrobbles are applied.
 */
@Composable
fun LastFmImportChoiceModal(
    visible: Boolean,
    onDismiss: () -> Unit,
    onEraseAndImport: () -> Unit,
    onImportWithoutErasing: () -> Unit,
    modifier: Modifier = Modifier
) {
    FoamBottomModal(
        visible = visible,
        onDismissRequest = onDismiss,
        modifier = modifier
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)) {
            BasicText(
                text = stringResource(R.string.lastfm_import_choice_title),
                style = FoamTheme.typography.titleMedium,
                modifier = Modifier.padding(
                    horizontal = FoamDimens.paddingSmall,
                    vertical = FoamDimens.spacingTiny
                )
            )
            BasicText(
                text = stringResource(R.string.lastfm_import_choice_desc),
                style = FoamTheme.typography.body,
                modifier = Modifier.padding(horizontal = FoamDimens.paddingSmall)
            )
            BasicText(
                text = stringResource(R.string.lastfm_import_erase_warning),
                style = FoamTheme.typography.caption.copy(
                    color = FoamTheme.colors.textSecondary
                ),
                modifier = Modifier.padding(horizontal = FoamDimens.paddingSmall)
            )

            ModalButton(
                label = stringResource(R.string.lastfm_import_erase_and_import),
                filled = false,
                onClick = onEraseAndImport
            )
            ModalButton(
                label = stringResource(R.string.lastfm_import_without_erasing),
                filled = true,
                onClick = onImportWithoutErasing
            )
        }
    }
}

/** Progress sheet shown while the history pages are downloaded and applied. */
@Composable
fun LastFmImportProgressModal(
    state: LastFmImportUiState,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val running = state as? LastFmImportUiState.Running
    // Keep the last progress visible while the sheet slides away after finishing.
    var lastProgress by remember { mutableStateOf<LastFmImportProgress?>(null) }
    SideEffect {
        if (running != null) lastProgress = running.progress
    }
    val progress = running?.progress ?: lastProgress

    FoamBottomModal(
        visible = running != null,
        onDismissRequest = {
            // The final database write is quick; don't let a stray tap abort it.
            if (progress?.saving != true) onCancel()
        },
        modifier = modifier
    ) {
        if (progress != null) {
            ImportProgressContent(progress = progress, onCancel = onCancel)
        }
    }
}

@Composable
private fun ImportProgressContent(
    progress: LastFmImportProgress,
    onCancel: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)) {
        BasicText(
            text = stringResource(R.string.lastfm_import_progress_title),
            style = FoamTheme.typography.titleMedium,
            modifier = Modifier.padding(
                horizontal = FoamDimens.paddingSmall,
                vertical = FoamDimens.spacingTiny
            )
        )
        BasicText(
            text = if (progress.saving) {
                stringResource(R.string.lastfm_import_saving)
            } else {
                stringResource(
                    R.string.lastfm_import_progress,
                    progress.scrobbles,
                    progress.page,
                    progress.totalPages.coerceAtLeast(progress.page)
                )
            },
            style = FoamTheme.typography.body,
            modifier = Modifier.padding(horizontal = FoamDimens.paddingSmall)
        )

        ImportProgressBar(progress.fraction)

        Spacer(modifier = Modifier.height(FoamDimens.spacingTiny))
        ModalButton(
            label = stringResource(R.string.cancel),
            filled = false,
            enabled = !progress.saving,
            onClick = onCancel
        )
    }
}

/** Success/failure sheet: a summary, then every unmatched Last.fm track. */
@Composable
fun LastFmImportResultModal(
    state: LastFmImportUiState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val resultState = state.takeIf {
        it is LastFmImportUiState.Finished || it is LastFmImportUiState.Failed
    }
    // Keep the result visible while the sheet slides away after dismissal.
    var lastResultState by remember { mutableStateOf<LastFmImportUiState?>(null) }
    SideEffect {
        if (resultState != null) lastResultState = resultState
    }
    val displayState = resultState ?: lastResultState
    val finished = displayState as? LastFmImportUiState.Finished
    val failed = displayState as? LastFmImportUiState.Failed

    FoamBottomModal(
        visible = resultState != null,
        onDismissRequest = onDismiss,
        modifier = modifier,
        footer = {
            Box(
                modifier = Modifier.padding(
                    horizontal = FoamDimens.paddingMedium,
                    vertical = FoamDimens.paddingSmall
                )
            ) {
                ModalButton(
                    label = stringResource(R.string.ok),
                    filled = true,
                    onClick = onDismiss
                )
            }
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)) {
            BasicText(
                text = stringResource(
                    if (finished != null) {
                        R.string.lastfm_import_complete_title
                    } else {
                        R.string.lastfm_import_failed_title
                    }
                ),
                style = FoamTheme.typography.titleMedium,
                modifier = Modifier.padding(
                    horizontal = FoamDimens.paddingSmall,
                    vertical = FoamDimens.spacingTiny
                )
            )

            if (finished != null) {
                ImportSuccessContent(finished)
            } else if (failed != null) {
                BasicText(
                    text = failed.message
                        ?: stringResource(R.string.lastfm_import_failed_generic),
                    style = FoamTheme.typography.body,
                    modifier = Modifier.padding(horizontal = FoamDimens.paddingSmall)
                )
            }
        }
    }
}

@Composable
private fun ImportSuccessContent(finished: LastFmImportUiState.Finished) {
    val result = finished.result
    BasicText(
        text = stringResource(
            R.string.lastfm_import_result_summary,
            result.matchedListens,
            result.scrobbles,
            result.matchedTracks
        ),
        style = FoamTheme.typography.body,
        modifier = Modifier.padding(horizontal = FoamDimens.paddingSmall)
    )

    val unmatched = result.unmatchedTracks
    if (unmatched.isEmpty()) return

    BasicText(
        text = stringResource(R.string.lastfm_import_unmatched_title, unmatched.size),
        style = FoamTheme.typography.titleMedium,
        modifier = Modifier.padding(
            horizontal = FoamDimens.paddingSmall,
            vertical = FoamDimens.spacingTiny
        )
    )

    unmatched.take(MAX_UNMATCHED_ROWS).forEach { track ->
        UnmatchedTrackRow(track)
    }
    if (unmatched.size > MAX_UNMATCHED_ROWS) {
        BasicText(
            text = stringResource(
                R.string.lastfm_import_unmatched_more,
                unmatched.size - MAX_UNMATCHED_ROWS
            ),
            style = FoamTheme.typography.caption.copy(
                color = FoamTheme.colors.textSecondary
            ),
            modifier = Modifier.padding(horizontal = FoamDimens.paddingSmall)
        )
    }
}

@Composable
private fun UnmatchedTrackRow(track: LastFmUnmatchedTrack) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = FoamDimens.paddingSmall,
                vertical = FoamDimens.spacingTiny
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = track.title,
                style = FoamTheme.typography.body.copy(
                    color = FoamTheme.colors.textPrimary
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            BasicText(
                text = track.artist,
                style = FoamTheme.typography.caption.copy(
                    color = FoamTheme.colors.textSecondary
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(FoamDimens.spacingSmall))
        BasicText(
            text = stringResource(R.string.lastfm_import_plays, track.playCount),
            style = FoamTheme.typography.caption.copy(
                color = FoamTheme.colors.textSecondary
            )
        )
    }
}

@Composable
private fun ImportProgressBar(progress: Float) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 200),
        label = "lastFmImportProgress"
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FoamDimens.paddingSmall)
            .height(FoamDimens.spacingTiny)
            .clip(RoundedCornerShape(FoamDimens.spacingTiny / 2))
            .foamSurfaceVariant()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animatedProgress)
                .height(FoamDimens.spacingTiny)
                .background(FoamTheme.accentColor)
        )
    }
}

/** Rendering every unmatched title could mean thousands of rows in one sheet. */
private const val MAX_UNMATCHED_ROWS = 200
