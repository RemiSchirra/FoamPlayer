package com.foamplayer.app.ui.modals

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BluetoothAudio
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.foamplayer.app.R
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.utils.pressShrink

/**
 * Bottom sheet behind the player's "Play on" cast button: a header (cast icon +
 * title) over the list of selectable playback targets. "This device" is always
 * part of the list and selected by default; the repository-supplied targets
 * (Bluetooth outputs, media-server remote players, discovered cast/UPnP devices)
 * join it with their own icon, title and play-type description.
 */
@Composable
fun PlayOnModal(
    visible: Boolean,
    onDismiss: () -> Unit,
    devices: List<PlayOnDevice>,
    selectedDeviceId: String?,
    scanning: Boolean = false,
    onDeviceSelected: (PlayOnDevice) -> Unit
) {
    FoamBottomModal(
        visible = visible,
        onDismissRequest = onDismiss
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = FoamTheme.icons.current.cast,
                    contentDescription = null,
                    tint = FoamTheme.accentColor,
                    modifier = Modifier.size(FoamDimens.iconMedium)
                )
                Spacer(modifier = Modifier.width(FoamDimens.spacingSmall))
                BasicText(
                    text = stringResource(R.string.play_on),
                    style = FoamTheme.typography.titleMedium
                )
            }
            Spacer(modifier = Modifier.height(FoamDimens.spacingStandard))

            devices.forEach { device ->
                PlayOnDeviceItem(
                    device = device,
                    isSelected = device.id == selectedDeviceId,
                    onClick = { onDeviceSelected(device) }
                )
            }

            // The Cast SDK needs a few seconds of multicast before the first
            // device shows up: say so instead of leaving the list looking empty.
            if (scanning && devices.none { it.type == PlayOnDeviceType.CHROMECAST }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = FoamDimens.paddingSmall,
                            vertical = FoamDimens.spacingSmall
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = FoamTheme.accentColor,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(FoamDimens.spacingMedium))
                    BasicText(
                        text = stringResource(R.string.play_on_scanning),
                        style = FoamTheme.typography.caption.copy(
                            color = FoamTheme.colors.textSecondary
                        )
                    )
                }
            }
        }
    }
}

/** One selectable row: type icon in a tinted circle, title + play-type description, check mark. */
@Composable
private fun PlayOnDeviceItem(
    device: PlayOnDevice,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val icon: ImageVector = when (device.type) {
        PlayOnDeviceType.THIS_DEVICE -> Icons.Rounded.Smartphone
        PlayOnDeviceType.BLUETOOTH -> Icons.Rounded.BluetoothAudio
        PlayOnDeviceType.CHROMECAST -> Icons.Rounded.Cast
        PlayOnDeviceType.REMOTE_PLAYER -> Icons.Rounded.Dns
        PlayOnDeviceType.UPNP -> Icons.Rounded.Speaker
    }
    val title = when (device.type) {
        PlayOnDeviceType.THIS_DEVICE -> stringResource(R.string.play_on_this_device)
        else -> device.title ?: device.serverName
            ?: device.type.name.lowercase().replaceFirstChar { it.uppercase() }
    }
    val description = when (device.type) {
        PlayOnDeviceType.THIS_DEVICE -> stringResource(R.string.play_on_this_device_desc)
        PlayOnDeviceType.BLUETOOTH -> stringResource(R.string.play_on_bluetooth_desc)
        PlayOnDeviceType.CHROMECAST -> stringResource(R.string.play_on_chromecast_desc)
        PlayOnDeviceType.REMOTE_PLAYER ->
            stringResource(R.string.play_on_remote_player_desc, device.serverName ?: device.title ?: "")
        PlayOnDeviceType.UPNP -> stringResource(R.string.play_on_upnp_desc)
    }

    val interactions = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FoamDimens.itemRounding))
            .background(if (isSelected) FoamTheme.colors.surfaceVariant else Color.Transparent)
            .clickable(
                interactionSource = interactions,
                indication = ripple(color = FoamTheme.colors.textPrimary),
                onClick = onClick
            )
            .padding(horizontal = FoamDimens.paddingSmall, vertical = FoamDimens.spacingSmall),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(
                    if (isSelected) FoamTheme.accentColor.copy(alpha = 0.15f)
                    else FoamTheme.colors.surfaceVariant
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) FoamTheme.accentColor else FoamTheme.colors.textSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(FoamDimens.spacingMedium))
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = title,
                style = FoamTheme.typography.body.copy(
                    fontWeight = FontWeight.Medium,
                    color = FoamTheme.colors.textPrimary
                )
            )
            BasicText(
                text = description,
                style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textSecondary)
            )
        }
        if (isSelected) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = FoamTheme.accentColor,
                modifier = Modifier.size(FoamDimens.iconSmall)
            )
        }
    }
}
