package com.foamplayer.app.ui.screens.equalizer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foamplayer.app.R
import com.foamplayer.app.audio.dsp.AssignedDeviceKind
import com.foamplayer.app.audio.dsp.PresetAssignments
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.ui.modals.FoamBottomModal
import com.foamplayer.app.ui.theme.FoamTheme

/**
 * The assign sheet of the presets browser: an assign checklist for the
 * currently applied preset (user presets and built-ins alike). Four targets:
 * the track / album / playlist being played and the four output-device kinds.
 * Toggling a row's checkbox applies the change at once — there is no confirm
 * step. Track assignation is always prioritary over output-device
 * assignation; the playlist row only appears while playing from a playlist,
 * and the track/album/playlist rows only appear while something is playing.
 */
@Composable
internal fun AssignPresetModal(
    visible: Boolean,
    presetName: String?,
    assignments: PresetAssignments,
    currentTrack: TrackEntity?,
    queueSourceId: String?,
    currentPlaylistName: String?,
    onDismiss: () -> Unit,
    onSetTrack: (Long, String?) -> Unit,
    onSetAlbum: (String, String?) -> Unit,
    onSetPlaylist: (Long, String?) -> Unit,
    onSetDevice: (AssignedDeviceKind, String?) -> Unit
) {
    FoamBottomModal(
        visible = visible,
        onDismissRequest = onDismiss
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            BasicText(
                text = stringResource(R.string.eq_assign_preset_title, presetName.orEmpty()),
                style = FoamTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(12.dp))

            val track = currentTrack
            if (track != null) {
                AssignRow(
                    title = stringResource(R.string.eq_assign_track, track.title),
                    checked = assignments.trackPresets[track.id] == presetName,
                    onToggle = { checked -> onSetTrack(track.id, if (checked) presetName else null) }
                )
                AssignRow(
                    title = stringResource(R.string.eq_assign_album, track.album),
                    checked = assignments.albumPresets[track.album] == presetName,
                    onToggle = { checked -> onSetAlbum(track.album, if (checked) presetName else null) }
                )
                // Playlist target: only while playing from a playlist queue.
                if (queueSourceId != null && queueSourceId.startsWith("playlist:")) {
                    val playlistId = queueSourceId.removePrefix("playlist:").toLongOrNull()
                    if (playlistId != null) {
                        AssignRow(
                            title = stringResource(
                                R.string.eq_assign_playlist,
                                currentPlaylistName ?: playlistId.toString()
                            ),
                            checked = assignments.playlistPresets[playlistId] == presetName,
                            onToggle = { checked ->
                                onSetPlaylist(playlistId, if (checked) presetName else null)
                            }
                        )
                    }
                }
            }

            // Device kinds: the four coarse kinds, not per-device ids.
            AssignRow(
                title = stringResource(R.string.eq_assign_device_speaker),
                checked = assignments.devicePresets[AssignedDeviceKind.SPEAKER] == presetName,
                onToggle = { checked ->
                    onSetDevice(AssignedDeviceKind.SPEAKER, if (checked) presetName else null)
                }
            )
            AssignRow(
                title = stringResource(R.string.eq_assign_device_bluetooth),
                checked = assignments.devicePresets[AssignedDeviceKind.BLUETOOTH] == presetName,
                onToggle = { checked ->
                    onSetDevice(AssignedDeviceKind.BLUETOOTH, if (checked) presetName else null)
                }
            )
            AssignRow(
                title = stringResource(R.string.eq_assign_device_usb),
                checked = assignments.devicePresets[AssignedDeviceKind.USB_DAC] == presetName,
                onToggle = { checked ->
                    onSetDevice(AssignedDeviceKind.USB_DAC, if (checked) presetName else null)
                }
            )
            AssignRow(
                title = stringResource(R.string.eq_assign_device_chromecast),
                checked = assignments.devicePresets[AssignedDeviceKind.CHROMECAST] == presetName,
                onToggle = { checked ->
                    onSetDevice(AssignedDeviceKind.CHROMECAST, if (checked) presetName else null)
                }
            )

            Spacer(modifier = Modifier.height(8.dp))
            BasicText(
                text = stringResource(R.string.eq_assign_note),
                style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textSecondary)
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/** One row of the assign checklist: a checkbox toggling the assignment at once. */
@Composable
private fun AssignRow(
    title: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onToggle(!checked) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onToggle,
            // 20dp = the checkbox's own drawn size; drops its 48dp built-in touch
            // padding so the row height stays compact.
            modifier = Modifier.size(20.dp),
            colors = CheckboxDefaults.colors(
                checkedColor = FoamTheme.accentColor,
                uncheckedColor = FoamTheme.colors.textSecondary
            )
        )
        Spacer(modifier = Modifier.width(12.dp))
        BasicText(
            text = title,
            style = FoamTheme.typography.body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
