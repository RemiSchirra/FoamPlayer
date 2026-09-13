package com.foamplayer.app.ui.screens.playlists

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.foamplayer.app.R
import com.foamplayer.app.ui.components.FoamBottomModal
import com.foamplayer.app.ui.components.ModalSectionDivider
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme

private val M3uMimeTypes = arrayOf(
    "audio/x-mpegurl",
    "audio/mpegurl",
    "application/x-mpegurl",
    "application/vnd.apple.mpegurl",
    "text/plain"
)

// Immutable by construction; the List field makes it unstable to the compiler's
// inference, which forced the modal to recompose on every unrelated parent pass.
@Immutable
data class MissingSongsUiState(
    val playlistName: String,
    val missing: List<String>,
    val folderUri: String?,
    val isScanning: Boolean,
    val scanProgress: Float
)

/**
 * "Create Playlist" sheet: a name field, an optional cover image, two mutually
 * exclusive sources (.m3u file or device folder, each resettable) and a Create
 * button.
 *
 * [onCreate] receives the raw name (empty falls back to "New playlist" in the
 * ViewModel), the cover image URI if one was picked, plus whichever source URI
 * is set, or null when none is selected.
 */
@Composable
fun CreatePlaylistModal(
    visible: Boolean,
    onDismiss: () -> Unit,
    onCreate: (name: String, m3uUri: String?, folderUri: String?, coverUri: String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var name by remember { mutableStateOf("") }
    var coverUri by remember { mutableStateOf<String?>(null) }
    var coverName by remember { mutableStateOf<String?>(null) }
    var m3uUri by remember { mutableStateOf<String?>(null) }
    var m3uName by remember { mutableStateOf<String?>(null) }
    var folderUri by remember { mutableStateOf<String?>(null) }
    var folderName by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(visible) {
        if (visible) {
            name = ""
            coverUri = null
            coverName = null
            m3uUri = null
            m3uName = null
            folderUri = null
            folderName = null
        }
    }

    val context = LocalContext.current
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            coverUri = uri.toString()
            coverName = queryDisplayName(context, uri) ?: uri.lastPathSegment
        }
    }
    val m3uPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            m3uUri = uri.toString()
            m3uName = queryDisplayName(context, uri) ?: uri.lastPathSegment
        }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            folderUri = uri.toString()
            folderName = folderDisplayName(uri)
        }
    }

    FoamBottomModal(
        visible = visible,
        onDismissRequest = onDismiss,
        modifier = modifier
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)) {
            BasicText(
                text = stringResource(R.string.create_playlist_title),
                style = FoamTheme.typography.titleMedium,
                modifier = Modifier.padding(
                    horizontal = FoamDimens.paddingSmall,
                    vertical = FoamDimens.spacingTiny
                )
            )

            PlaylistNameField(
                value = name,
                onValueChange = { name = it }
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(FoamDimens.itemRounding))
                    .background(FoamTheme.colors.surfaceVariant)
            ) {
                CoverImageRow(
                    imageUri = coverUri,
                    title = stringResource(R.string.playlist_cover_image),
                    subtitle = coverName ?: stringResource(R.string.playlist_cover_hint),
                    onClick = { coverPicker.launch(ImageMimeTypes) },
                    onReset = {
                        coverUri = null
                        coverName = null
                    }
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(FoamDimens.itemRounding))
                    .background(FoamTheme.colors.surfaceVariant)
            ) {
                SourceOptionRow(
                    title = stringResource(R.string.from_m3u_playlist),
                    subtitle = m3uName ?: stringResource(R.string.from_m3u_subtitle),
                    selected = m3uUri != null,
                    enabled = m3uUri != null || folderUri == null,
                    onClick = { m3uPicker.launch(M3uMimeTypes) },
                    onReset = {
                        m3uUri = null
                        m3uName = null
                    }
                )
                ModalSectionDivider()
                SourceOptionRow(
                    title = stringResource(R.string.from_folder),
                    subtitle = folderName ?: stringResource(R.string.from_folder_subtitle),
                    selected = folderUri != null,
                    enabled = folderUri != null || m3uUri == null,
                    onClick = { folderPicker.launch(null) },
                    onReset = {
                        folderUri = null
                        folderName = null
                    }
                )
            }

            ModalButton(
                label = stringResource(R.string.create),
                filled = true,
                onClick = { onCreate(name, m3uUri, folderUri, coverUri) }
            )
        }
    }
}

private val ImageMimeTypes = arrayOf("image/*")

/**
 * The pill-shaped playlist name input shared by the create and rename sheets.
 * The FoamBottomModal hosting it already lifts the sheet above the soft
 * keyboard, so the field stays visible while typing.
 */
@Composable
internal fun PlaylistNameField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = stringResource(R.string.playlist_name_hint),
    focusRequester: FocusRequester? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(FoamTheme.colors.surfaceVariant)
            .padding(horizontal = FoamDimens.paddingLarge, vertical = FoamDimens.spacingStandard),
        contentAlignment = Alignment.CenterStart
    ) {
        if (value.isEmpty()) {
            BasicText(
                text = hint,
                style = FoamTheme.typography.body.copy(color = FoamTheme.colors.textSecondary)
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = FoamTheme.typography.body.copy(color = FoamTheme.colors.textPrimary),
            cursorBrush = SolidColor(FoamTheme.accentColor),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            modifier = Modifier
                .fillMaxWidth()
                .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
            singleLine = true
        )
    }
}

/**
 * Cover image chooser row: a small preview (the picked image, or an image icon
 * placeholder), a title + filename subtitle, and a clear button once selected.
 */
@Composable
private fun CoverImageRow(
    imageUri: String?,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    onReset: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = FoamTheme.colors.textPrimary),
                onClick = onClick
            )
            .padding(horizontal = FoamDimens.paddingMedium, vertical = FoamDimens.paddingMedium),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(FoamDimens.buttonRounding))
                .background(FoamTheme.colors.background),
            contentAlignment = Alignment.Center
        ) {
            if (imageUri != null) {
                AsyncImage(
                    model = imageUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Image,
                    contentDescription = null,
                    tint = FoamTheme.colors.textSecondary,
                    modifier = Modifier.size(FoamDimens.iconSmall)
                )
            }
        }

        Spacer(modifier = Modifier.width(FoamDimens.paddingMedium))

        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = title,
                style = FoamTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            BasicText(
                text = subtitle,
                style = FoamTheme.typography.body.copy(
                    color = if (imageUri != null) FoamTheme.accentColor else FoamTheme.colors.textSecondary
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (imageUri != null) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(color = FoamTheme.colors.textPrimary),
                        onClick = onReset
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.clear_selection),
                    tint = FoamTheme.colors.textSecondary,
                    modifier = Modifier.size(FoamDimens.iconSmall)
                )
            }
        }
    }
}

@Composable
private fun SourceOptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onReset: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = FoamTheme.colors.textPrimary),
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = FoamDimens.paddingMedium, vertical = FoamDimens.paddingMedium),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .border(
                    width = 2.dp,
                    color = if (selected) FoamTheme.accentColor else FoamTheme.colors.textSecondary,
                    shape = CircleShape
                )
                .padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(FoamTheme.accentColor)
                )
            }
        }

        Spacer(modifier = Modifier.width(FoamDimens.paddingMedium))

        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = title,
                style = FoamTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            BasicText(
                text = subtitle,
                style = FoamTheme.typography.body.copy(
                    color = if (selected) FoamTheme.accentColor else FoamTheme.colors.textSecondary
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (selected) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(color = FoamTheme.colors.textPrimary),
                        onClick = onReset
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.clear_selection),
                    tint = FoamTheme.colors.textSecondary,
                    modifier = Modifier.size(FoamDimens.iconSmall)
                )
            }
        }
    }
}

/**
 * Report of songs that could not be matched in the library. For a folder
 * source ([MissingSongsUiState.folderUri] != null) it also offers to add the
 * folder to the library and scan it, then create the playlist with its songs.
 * [onDismiss] (scrim / drag-down) and [onCancel] both abort the creation;
 * [onKeep] closes the report keeping what was created so far.
 */
@Composable
fun MissingSongsModal(
    state: MissingSongsUiState?,
    onDismiss: () -> Unit,
    onKeep: () -> Unit,
    onCancel: () -> Unit,
    onImportFolder: () -> Unit,
    modifier: Modifier = Modifier
) {
    var lastState by remember { mutableStateOf<MissingSongsUiState?>(null) }
    SideEffect {
        if (state != null) lastState = state
    }
    val shown = state ?: lastState

    FoamBottomModal(
        visible = state != null,
        onDismissRequest = onDismiss,
        modifier = modifier
    ) {
        if (shown != null) {
            Column(verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)) {
                BasicText(
                    text = stringResource(R.string.songs_not_found_title),
                    style = FoamTheme.typography.titleMedium,
                    modifier = Modifier.padding(
                        horizontal = FoamDimens.paddingSmall,
                        vertical = FoamDimens.spacingTiny
                    )
                )

                if (shown.playlistName.isNotBlank()) {
                    BasicText(
                        text = stringResource(R.string.playlist_name_value, shown.playlistName),
                        style = FoamTheme.typography.body,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = FoamDimens.paddingSmall)
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .clip(RoundedCornerShape(FoamDimens.itemRounding))
                        .background(FoamTheme.colors.surfaceVariant)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = FoamDimens.spacingSmall),
                    verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingTiny)
                ) {
                    shown.missing.forEach { song ->
                        BasicText(
                            text = song,
                            style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textPrimary),
                            modifier = Modifier.padding(horizontal = FoamDimens.paddingMedium)
                        )
                    }
                }

                if (shown.isScanning) {
                    BasicText(
                        text = stringResource(R.string.scanning_folder),
                        style = FoamTheme.typography.body,
                        modifier = Modifier.padding(horizontal = FoamDimens.paddingSmall)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(FoamDimens.spacingTiny)
                            .clip(RoundedCornerShape(FoamDimens.spacingTiny / 2))
                            .background(FoamTheme.colors.surfaceVariant)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(shown.scanProgress.coerceIn(0f, 1f))
                                .height(FoamDimens.spacingTiny)
                                .background(FoamTheme.accentColor)
                        )
                    }
                } else if (shown.folderUri != null) {
                    ModalButton(
                        label = stringResource(R.string.add_folder_and_scan),
                        filled = true,
                        onClick = onImportFolder
                    )
                    ModalButton(
                        label = stringResource(R.string.create_with_found_songs),
                        filled = false,
                        onClick = onKeep
                    )
                    ModalButton(
                        label = stringResource(R.string.cancel),
                        filled = false,
                        onClick = onCancel
                    )
                } else {
                    ModalButton(
                        label = stringResource(R.string.ok),
                        filled = true,
                        onClick = onKeep
                    )
                    ModalButton(
                        label = stringResource(R.string.cancel),
                        filled = false,
                        onClick = onCancel
                    )
                }
            }
        }
    }
}

/** Filled (accent) or flat full-width button, shared by the playlist sheets. */
@Composable
internal fun ModalButton(
    label: String,
    filled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FoamDimens.buttonRounding))
            .background(if (filled) FoamTheme.accentColor else FoamTheme.colors.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(vertical = FoamDimens.spacingStandard),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = label,
            style = FoamTheme.typography.body.copy(
                color = if (filled) FoamTheme.colors.background else FoamTheme.colors.textPrimary,
                fontWeight = FontWeight.Bold
            )
        )
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

private fun folderDisplayName(uri: Uri): String {
    if (uri.scheme != "content") return uri.lastPathSegment ?: uri.toString()
    return runCatching {
        val treeId = DocumentsContract.getTreeDocumentId(uri)
        Uri.decode(treeId.substringAfter(':')).ifBlank { uri.toString() }
    }.getOrDefault(uri.toString())
}
