package com.foamplayer.app.ui.modals

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.foamplayer.app.R
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme
import kotlinx.coroutines.flow.first

/** Per-row delay of the staggered fade-in for the rows the sheet opened with. */
private const val MountStaggerStepMs = 40
/** Cap on the stagger delay so a long list never feels like it is waiting. */
private const val MaxMountStaggerMs = 240
/** How far a mount row drifts up while fading in. */
private val MountDriftDp = 12.dp

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
    onDeviceSelected: (PlayOnDevice) -> Unit,
) {
    FoamBottomModal(
        visible = visible,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = FoamTheme.icons.current.cast,
                    contentDescription = null,
                    tint = FoamTheme.accentColor,
                    modifier = Modifier.size(FoamDimens.iconMedium)
                )
                Spacer(modifier = Modifier.width(FoamDimens.spacingSmall))
                BasicText(
                    text = stringResource(R.string.play_on),
                    style = FoamTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                val showScanningSpinner =
                    scanning && devices.none { it.type == PlayOnDeviceType.CHROMECAST }
                AnimatedVisibility(
                    visible = showScanningSpinner,
                    enter = fadeIn(tween(220)) + expandHorizontally(tween(280)),
                    exit = fadeOut(tween(200)) + shrinkHorizontally(tween(220))
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = FoamTheme.accentColor,
                        strokeWidth = 2.dp
                    )
                }
            }
            Spacer(modifier = Modifier.height(FoamDimens.spacingStandard))

            PlayOnDeviceList(
                devices = devices,
                selectedDeviceId = selectedDeviceId,
                onDeviceSelected = onDeviceSelected
            )
        }
    }
}

/**
 * One row the device list currently renders. The [PlayOnDevice] payload is
 * refreshed on every reconcile; the animation bookkeeping ([leaving],
 * [atMount], [enterDelayMs]) survives reorders so a row never restarts an
 * animation that is already in flight.
 */
private data class DeviceRowState(
    val device: PlayOnDevice,
    /** Removed from the live list; still composing its collapse animation. */
    val leaving: Boolean,
    /** Part of the list the sheet opened with: staggered fade-in, no height animation. */
    val atMount: Boolean,
    /** Stagger delay of the mount fade-in. */
    val enterDelayMs: Int
)

/**
 * Reconciles the rows currently rendered against the live [devices] list:
 * - at mount, every device becomes a row carrying its stagger delay;
 * - new arrivals are spliced in at their live-list position (their rows then
 *   expand in, pushing the rows below them down);
 * - vanished devices become leaving rows, grouped under the live row that
 *   followed them in the old order (or the end), so they collapse in place
 *   while the rows below slide up;
 * - surviving rows keep their animation state.
 */
private fun reconcileRows(
    previous: List<DeviceRowState>,
    devices: List<PlayOnDevice>,
    atMount: Boolean
): List<DeviceRowState> {
    if (atMount) {
        return devices.mapIndexed { index, device ->
            DeviceRowState(
                device = device,
                leaving = false,
                atMount = true,
                enterDelayMs = (index * MountStaggerStepMs).coerceAtMost(MaxMountStaggerMs)
            )
        }
    }

    val liveIds = devices.mapTo(HashSet()) { it.id }
    val previousById = previous.associateBy { it.device.id }
    val leavingBeforeById = HashMap<String, MutableList<DeviceRowState>>()
    val trailingLeaving = mutableListOf<DeviceRowState>()
    var heldLeaving = mutableListOf<DeviceRowState>()
    for (old in previous) {
        if (old.device.id in liveIds) {
            leavingBeforeById.getOrPut(old.device.id) { mutableListOf() }.addAll(heldLeaving)
            heldLeaving = mutableListOf()
        } else {
            heldLeaving.add(old.copy(leaving = true))
        }
    }
    trailingLeaving.addAll(heldLeaving)

    val rows = mutableListOf<DeviceRowState>()
    for (device in devices) {
        leavingBeforeById.remove(device.id)?.let(rows::addAll)
        val old = previousById[device.id]
        rows.add(
            DeviceRowState(
                device = device,
                leaving = false,
                atMount = old?.atMount ?: false,
                enterDelayMs = old?.enterDelayMs ?: 0
            )
        )
    }
    rows.addAll(trailingLeaving)
    return rows
}

/**
 * The selectable target rows. Rows the sheet opened with fade in with a
 * stagger; devices discovered while the sheet is open expand in (pushing the
 * rows below them down, and the sheet itself grows with them); devices that
 * disappear collapse in place while the rows below slide up.
 */
@Composable
private fun PlayOnDeviceList(
    devices: List<PlayOnDevice>,
    selectedDeviceId: String?,
    onDeviceSelected: (PlayOnDevice) -> Unit
) {
    // Rows currently rendered: the live devices plus any "leaving" rows that
    // are still playing their collapse animation.
    var rows by remember { mutableStateOf(emptyList<DeviceRowState>()) }
    // False only during the sheet's very first composition pass, when every
    // incoming device is a mount row; afterwards new arrivals are dynamic and
    // expand in.
    var mounted by remember { mutableStateOf(false) }
    rows = reconcileRows(rows, devices, atMount = !mounted)
    SideEffect { mounted = true }

    Column(modifier = Modifier.fillMaxWidth()) {
        rows.forEach { row ->
            // key() keeps a row's remembered animation state across reorders,
            // so a row never restarts its entrance mid-flight.
            key(row.device.id) {
                PlayOnDeviceRow(
                    row = row,
                    isSelected = row.device.id == selectedDeviceId,
                    onClick = { onDeviceSelected(row.device) }
                ) {
                    rows = rows.filterNot { it.device.id == row.device.id }
                }
            }
        }
    }
}

/** One selectable row: type icon in a tinted circle, title + play-type description, check mark. */
@Composable
private fun PlayOnDeviceRow(
    row: DeviceRowState,
    isSelected: Boolean,
    onClick: () -> Unit,
    onExitFinished: () -> Unit
) {
    val device = row.device
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

    val heightState = remember { MutableTransitionState(row.atMount) }
    heightState.targetState = !row.leaving

    val mountProgress = remember { Animatable(if (row.atMount) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (row.atMount) {
            mountProgress.animateTo(1f, tween(durationMillis = 220, delayMillis = row.enterDelayMs))
        }
    }

    LaunchedEffect(row.leaving) {
        if (!row.leaving) return@LaunchedEffect
        snapshotFlow { heightState.currentState }.first { !it }
        onExitFinished()
    }

    val interactions = remember { MutableInteractionSource() }
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) FoamTheme.colors.surfaceVariant else Color.Transparent,
        animationSpec = tween(220)
    )
    val iconCircleColor by animateColorAsState(
        targetValue = if (isSelected) FoamTheme.accentColor.copy(alpha = 0.15f)
        else FoamTheme.colors.surfaceVariant,
        animationSpec = tween(220)
    )
    val iconTint by animateColorAsState(
        targetValue = if (isSelected) FoamTheme.accentColor else FoamTheme.colors.textSecondary,
        animationSpec = tween(220)
    )

    AnimatedVisibility(
        visibleState = heightState,
        enter = if (row.atMount) EnterTransition.None
        else fadeIn(tween(220)) + expandVertically(tween(280)),
        exit = fadeOut(tween(200)) + shrinkVertically(tween(220))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    alpha = mountProgress.value
                    translationY = (1f - mountProgress.value) * MountDriftDp.toPx()
                }
                .clip(RoundedCornerShape(FoamDimens.itemRounding))
                .background(backgroundColor)
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
                    .background(iconCircleColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
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
            AnimatedVisibility(
                visible = isSelected,
                enter = fadeIn(tween(220)) + expandHorizontally(tween(280)),
                exit = fadeOut(tween(200)) + shrinkHorizontally(tween(220))
            ) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = FoamTheme.accentColor,
                    modifier = Modifier.size(FoamDimens.iconSmall)
                )
            }
        }
    }
}
