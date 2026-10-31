package com.foamplayer.app.ui.modals

import android.content.res.Resources
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.foamplayer.app.R
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.theme.adaptAccentForTheme
import com.foamplayer.app.ui.theme.foamSurfaceVariant
import com.foamplayer.app.ui.utils.AlbumArtPaletteCache
import com.foamplayer.app.ui.utils.TimeUtils
import androidx.core.net.toUri
import java.util.Locale

/**
 * Pre-resolved localized channel labels, built once per composition. The
 * mono/stereo words resolve eagerly; multi-channel counts resolve per call
 * (plural selection needs the actual channel count, so it is read at render
 * time from the hosting composable's context).
 */
internal class ChannelLabels(
    val mono: String,
    val stereo: String,
    private val resources: Resources
) {
    fun count(n: Int): String = resources.getQuantityString(R.plurals.channels_count, n, n)
}

@Composable
internal fun rememberChannelLabels(): ChannelLabels {
    val resources = LocalResources.current
    return remember(resources) {
        ChannelLabels(
            mono = resources.getString(R.string.channels_mono),
            stereo = resources.getString(R.string.channels_stereo),
            resources = resources
        )
    }
}

/** Localized channel layout label ("Mono" / "Stereo" / "N channels"). */
private fun formatChannels(channels: Int, labels: ChannelLabels): String = when (channels) {
    1 -> labels.mono
    2 -> labels.stereo
    else -> labels.count(channels)
}

/**
 * Full metadata sheet for a track. Also embedded as the second page of
 * [TrackFoamModal] (see its details button), where the header persists across
 * the switch; this standalone hosting is kept for direct use.
 * Same visual language: the track header on a darker container, then grouped
 * label/value sections on lighter containers.
 */
@Composable
fun TrackDetailsModal(
    track: TrackEntity?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onResetPlayStats: (TrackEntity) -> Unit = {},
    onSetInstrumental: (TrackEntity, Boolean) -> Unit = { _, _ -> },
    scoreOverrides: Map<Long, Int> = emptyMap(),
    onSetScore: (Long, Int) -> Unit = { _, _ -> },
    useAlbumArtAccent: Boolean = false
) {
    // Same exit-animation preservation as the other modals in this package.
    var lastShownTrack by remember { mutableStateOf<TrackEntity?>(null) }
    SideEffect {
        if (track != null) lastShownTrack = track
    }
    val displayTrack = track ?: lastShownTrack

    // Accent texts take the color of the VIEWED track's own album art (when the
    // automatic-color setting is on), not the playing track's dynamic accent.
    // Extraction runs through the shared palette cache; until it lands -- or when
    // the setting is off -- the app accent is used instead.
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
        visible = track != null,
        onDismissRequest = onDismiss,
        modifier = modifier
    ) {
        if (displayTrack != null) {
            Column(
                modifier = Modifier.padding(bottom = FoamDimens.spacingSmall),
                verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)
            ) {
                TrackModalHeader(
                    track = displayTrack,
                    accentColor = accentColor,
                    score = scoreOverrides[displayTrack.id] ?: displayTrack.score,
                    onSetScore = { onSetScore(displayTrack.id, it) }
                )

                val channelLabels = rememberChannelLabels()
                BasicText(
                    text = remember(displayTrack, channelLabels) { audioSummaryText(displayTrack, channelLabels) },
                    style = FoamTheme.typography.body.copy(color = FoamTheme.colors.textSecondary),
                    modifier = Modifier.padding(horizontal = FoamDimens.paddingSmall)
                )

                TrackDetailsContent(
                    track = displayTrack,
                    accentColor = accentColor,
                    onResetPlayStats = { onResetPlayStats(displayTrack) },
                    onSetInstrumental = { onSetInstrumental(displayTrack, it) }
                )
            }
        }
    }
}

/** The Tags + File sections shared by the standalone details modal and the details page of [TrackFoamModal]. */
@Composable
internal fun TrackDetailsContent(
    track: TrackEntity,
    accentColor: Color,
    onResetPlayStats: () -> Unit,
    onSetInstrumental: (Boolean) -> Unit
) {
    val hasStats = track.playCount > 0 || track.lastPlayedAt != null

    DetailsSection(
        title = stringResource(R.string.details_stats),
        accentColor = accentColor,
        trailing = {
            if (hasStats) {
                TrackStatsResetButton(onClick = onResetPlayStats)
            }
        }
    ) {
        DetailRow(stringResource(R.string.details_listens), track.playCount.takeIf { it > 0 }?.toString())
        DetailRow(
            stringResource(R.string.details_last_played),
            track.lastPlayedAt?.let { TimeUtils.formatDateTime(it) }
        )
    }

    DetailsSection(title = stringResource(R.string.details_tags), accentColor = accentColor) {
        DetailRow(stringResource(R.string.details_track_number), track.trackNumber?.toString())
        DetailRow(stringResource(R.string.details_disc_number), track.discNumber?.toString())
        DetailRow(stringResource(R.string.details_year), track.year?.toString())
        DetailRow(stringResource(R.string.details_genre), track.genre)
        DetailRow(stringResource(R.string.details_artist), track.artist)
        DetailRow(stringResource(R.string.details_album_artist), track.albumArtist)
        DetailRow(stringResource(R.string.details_album), track.album)
        DetailRow(stringResource(R.string.details_composer), track.composer)
        DetailCheckboxRow(
            label = stringResource(R.string.instrumental),
            checked = track.instrumental,
            onCheckedChange = onSetInstrumental
        )
    }

    DetailsSection(title = stringResource(R.string.details_file), accentColor = accentColor) {
        DetailStackedValue(stringResource(R.string.details_file_path), simplifiedDisplayPath(track.path), accentColor)
        DetailRow(stringResource(R.string.details_format), track.format)
        DetailRow(
            stringResource(R.string.album_art),
            if (track.albumArtPath != null) stringResource(R.string.embedded_in_file) else null
        )
    }
}

/** One-line technical summary of a track; [labels] carries the localized channel words. */
internal fun audioSummaryText(track: TrackEntity, labels: ChannelLabels): String = listOfNotNull(
    TimeUtils.formatDurationLong(track.duration),
    formatSampleRate(track.sampleRate),
    "${track.bitDepth} bit",
    formatChannels(track.channels, labels),
    track.bitrate.takeIf { it > 0 }?.let { "$it kbps" },
    formatFileSize(track.size).takeIf { track.size > 0 }
).joinToString("  ·  ")

@Composable
internal fun DetailsSection(
    title: String,
    accentColor: Color,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FoamDimens.itemRounding))
            .foamSurfaceVariant()
            .padding(vertical = FoamDimens.spacingTiny)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = FoamDimens.paddingMedium,
                    vertical = FoamDimens.spacingTiny
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText(
                text = title,
                style = FoamTheme.typography.caption.copy(color = accentColor),
                modifier = Modifier.weight(1f)
            )
            trailing?.invoke()
        }
        content()
    }
}

/** Small cross button that resets a track's listen counter and last-listen date. */
@Composable
private fun TrackStatsResetButton(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .size(FoamDimens.iconMedium)
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = FoamTheme.colors.textPrimary),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = stringResource(R.string.reset_listen_stats),
            tint = FoamTheme.colors.textSecondary,
            modifier = Modifier.size(FoamDimens.iconSmall)
        )
    }
}

/** Detail row whose value is a checkbox (user-set booleans like "Instrumental"). */
@Composable
private fun DetailCheckboxRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                interactionSource = interactionSource,
                indication = ripple(color = FoamTheme.colors.textPrimary),
                role = Role.Checkbox,
                onValueChange = onCheckedChange
            )
            .padding(horizontal = FoamDimens.paddingMedium, vertical = FoamDimens.spacingTiny),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicText(
            text = label,
            style = FoamTheme.typography.body.copy(color = FoamTheme.colors.textSecondary),
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(FoamDimens.paddingMedium))
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            // 20dp = the checkbox's own drawn size; drops its 48dp built-in
            // touch padding so the row height matches the other detail rows.
            modifier = Modifier.size(20.dp),
            colors = CheckboxDefaults.colors(
                checkedColor = FoamTheme.accentColor,
                uncheckedColor = FoamTheme.colors.textSecondary
            )
        )
    }
}

@Composable
internal fun DetailRow(label: String, value: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FoamDimens.paddingMedium, vertical = FoamDimens.spacingTiny),
        verticalAlignment = Alignment.Top
    ) {
        BasicText(
            text = label,
            style = FoamTheme.typography.body.copy(color = FoamTheme.colors.textSecondary),
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(FoamDimens.paddingMedium))
        BasicText(
            text = value.takeUnless { it.isNullOrBlank() } ?: "—",
            style = FoamTheme.typography.body.copy(
                color = FoamTheme.colors.textPrimary,
                textAlign = TextAlign.End
            ),
            modifier = Modifier.weight(1f)
        )
    }
}

/** Label above the value, for long content (file paths) that should wrap freely. */
@Composable
internal fun DetailStackedValue(
    label: String,
    value: String,
    accentColor: Color = FoamTheme.accentColor
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FoamDimens.paddingMedium, vertical = FoamDimens.spacingTiny)
    ) {
        BasicText(
            text = label,
            style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textSecondary)
        )
        Spacer(modifier = Modifier.height(FoamDimens.spacingTiny))
        BasicText(
            text = value,
            style = FoamTheme.typography.monospace.copy(color = accentColor)
        )
    }
}

private fun formatSampleRate(hz: Int): String {
    val khz = hz / 1000.0
    val value = if (khz % 1.0 == 0.0) {
        String.format(Locale.getDefault(), "%.0f", khz)
    } else {
        String.format(Locale.getDefault(), "%.1f", khz)
    }
    return "$value kHz"
}

// Hoisted: this was compiled inside simplifiedDisplayPath on every composition pass.
private val StoragePrefixRegex = Regex("^/storage/emulated/\\d+/")

/**
 * Turns the stored playback URI into a short, readable path that starts at the
 * scanned folder, e.g. "library/subfolder/name of the song.mp3".
 *
 * SAF content URIs carry the scanned tree root and the document id as separate
 * segments (".../tree/primary%3AMusic/document/primary%3AMusic%2FSub%2Fsong.mp3");
 * pulling both through DocumentsContract decodes the %2F/%3A/%20 escapes, and the
 * document path is then made relative to the tree and prefixed with the scanned
 * folder's own name. Legacy file:// paths are decoded and trimmed of
 * device-specific storage prefixes. Falls back to the raw path on any surprise.
 */
internal fun simplifiedDisplayPath(path: String): String {
    return runCatching {
        val uri = path.toUri()
        when (uri.scheme) {
            "content" -> {
                val docPath = DocumentsContract.getDocumentId(uri)
                    .substringAfter(':')
                    .trimStart('/')
                val treePath = runCatching { DocumentsContract.getTreeDocumentId(uri) }
                    .getOrNull()
                    ?.substringAfter(':')
                    ?.trimStart('/')
                val treeName = treePath?.substringAfterLast('/')

                val relative = when {
                    treePath.isNullOrEmpty() -> docPath
                    docPath.startsWith("$treePath/") -> docPath.removePrefix("$treePath/")
                    else -> docPath.substringAfterLast('/')
                }

                listOfNotNull(
                    treeName?.takeIf { it.isNotBlank() },
                    relative.takeIf { it.isNotBlank() }
                ).joinToString("/").ifEmpty { path }
            }
            "file" -> {
                val decoded = Uri.decode(uri.path ?: return@runCatching path)
                decoded
                    .replace(StoragePrefixRegex, "")
                    .removePrefix("/sdcard/")
                    .trimStart('/')
                    .ifEmpty { path }
            }
            else -> Uri.decode(path)
        }
    }.getOrDefault(path)
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "—"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1.0) {
        String.format(Locale.getDefault(), "%.1f MB", mb)
    } else {
        String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
    }
}
