package com.foamplayer.app.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Icon
import androidx.compose.material.ripple
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.R
import com.foamplayer.app.ui.components.FoamSliderStateItem
import com.foamplayer.app.ui.components.FoamSwitchItem
import com.foamplayer.app.ui.components.LibraryFoldersCard
import com.foamplayer.app.ui.components.LibraryScanCard
import com.foamplayer.app.ui.modals.AddProviderModal
import com.foamplayer.app.ui.modals.ModalButton
import com.foamplayer.app.ui.modals.PickerHeaderButton
import com.foamplayer.app.ui.modals.PlaylistNameField
import com.foamplayer.app.ui.navigation.LocalBottomBarClearance
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme
import kotlin.math.roundToInt

/** Which separator list the add-separator dialog is open for; null = closed. */
private enum class SeparatorTarget { ARTIST, GENRE }

@Composable
fun LibrarySettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val selectedFolders = settings.libraryFolders
    val scanStatus by viewModel.scanStatus.collectAsState()
    val artistImageScan by viewModel.artistImageScan.collectAsState()
    val artistSeparators = settings.artistSeparators
    val genreSeparators = settings.genreSeparators
    val context = LocalContext.current

    var separatorDialogTarget by remember { mutableStateOf<SeparatorTarget?>(null) }
    var showAddProvider by remember { mutableStateOf(false) }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            viewModel.addFolder(it.toString())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FoamTheme.colors.background)
    ) {
        SettingsHeader(
            title = stringResource(R.string.library),
            onBack = onBack,
            modifier = Modifier.padding(horizontal = FoamDimens.paddingMedium)
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = FoamDimens.paddingMedium),
            contentPadding = PaddingValues(bottom = LocalBottomBarClearance.current)
        ) {
            // --- Library Folders ---
            item {
                LibraryFoldersCard(
                    folders = selectedFolders,
                    servers = settings.distantServers,
                    onAddProvider = { showAddProvider = true },
                    onRemoveFolder = viewModel::removeFolder,
                    onRemoveServer = viewModel::removeServer
                )
            }

            item { Spacer(modifier = Modifier.height(FoamDimens.spacingLarge)) }

            // --- Library Scan (directly under the folder list: scanning is
            // tied to the folders above) ---
            item {
                LibraryScanCard(
                    scanStatus = scanStatus,
                    onStartScan = viewModel::startScan
                )
                Spacer(modifier = Modifier.height(FoamDimens.spacingExtraLarge))
            }

            // --- Metadata Separators ---
            item {
                BasicText(text = stringResource(R.string.metadata_separators), style = FoamTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))

                SeparatorListSection(
                    title = stringResource(R.string.artist_separators),
                    separators = artistSeparators,
                    onAdd = { separatorDialogTarget = SeparatorTarget.ARTIST },
                    onRemove = { viewModel.setArtistSeparators(artistSeparators - it) }
                )

                Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))

                SeparatorListSection(
                    title = stringResource(R.string.genre_separators),
                    separators = genreSeparators,
                    onAdd = { separatorDialogTarget = SeparatorTarget.GENRE },
                    onRemove = { viewModel.setGenreSeparators(genreSeparators - it) }
                )

                Spacer(modifier = Modifier.height(FoamDimens.spacingExtraLarge))
            }

            // --- Statistics ---
            item {
                BasicText(text = stringResource(R.string.statistics), style = FoamTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                        .background(FoamTheme.colors.surface)
                ) {
                    FoamSliderStateItem(
                        title = stringResource(R.string.listened_threshold),
                        description = stringResource(R.string.listened_threshold_description),
                        initialValue = settings.listenedThresholdPercent.toFloat(),
                        onValueChangeFinished = {
                            viewModel.setListenedThresholdPercent(it.roundToInt())
                        },
                        steps = 9,
                        valueRange = 0f..100f,
                        formatValue = { "${it.roundToInt()}%" },
                        minMaxDisplay = "0%" to "100%"
                    )
                }
                Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))
            }

            // --- Artist Images ---
            item {
                BasicText(text = stringResource(R.string.artist_images), style = FoamTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                // The switch's ripple is drawn at card level: it expands from
                // the press point across the whole card, reaching the top AND
                // bottom borders (clipped by the rounded corners).
                val switchInteraction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                        .background(FoamTheme.colors.surface)
                        .indication(
                            interactionSource = switchInteraction,
                            indication = ripple(bounded = true, color = FoamTheme.colors.textPrimary)
                        )
                ) {
                    // No vertical padding: the switch row spans the full card
                    // height when collapsed, so its ripple reaches the card's
                    // borders (same as the audio settings cards).
                    Column(Modifier.fillMaxWidth()) {
                        FoamSwitchItem(
                            title = stringResource(R.string.artist_images_resolve),
                            description = stringResource(R.string.artist_images_resolve_desc),
                            checked = settings.artistImageResolveEnabled,
                            onCheckedChange = { viewModel.setArtistImageResolveEnabled(it) },
                            interactionSource = switchInteraction
                        )

                        // Expandable body (same pattern as the audio devices
                        // cards): the switch reveals the scan controls with an
                        // animated expand/collapse.
                        AnimatedVisibility(
                            visible = settings.artistImageResolveEnabled,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = FoamDimens.paddingMedium)
                                    .padding(bottom = FoamDimens.spacingMedium)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    BasicText(
                                        text = if (artistImageScan.running) {
                                            stringResource(
                                                R.string.artist_images_scanning,
                                                artistImageScan.found,
                                                artistImageScan.total
                                            )
                                        } else {
                                            stringResource(
                                                R.string.artist_images_found,
                                                artistImageScan.found,
                                                artistImageScan.total
                                            )
                                        },
                                        style = FoamTheme.typography.caption,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(FoamDimens.spacingSmall))
                                    // START SCAN <-> STOP: the pill animates its
                                    // background, size and label between the two
                                    // states; the ripple stays bounded inside it.
                                    val scanRunning = artistImageScan.running
                                    val scanButtonBackground by animateColorAsState(
                                        targetValue = if (scanRunning) FoamTheme.colors.surfaceVariant
                                        else FoamTheme.accentColor,
                                        animationSpec = tween(durationMillis = 200),
                                        label = "artistScanButtonBackground"
                                    )
                                    Box(
                                        modifier = Modifier
                                            .animateContentSize(animationSpec = tween(durationMillis = 200))
                                            .clip(RoundedCornerShape(FoamDimens.buttonRounding))
                                            .background(scanButtonBackground)
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = ripple(bounded = true, color = FoamTheme.colors.textPrimary),
                                                onClick = {
                                                    if (scanRunning) viewModel.stopArtistImageScan()
                                                    else viewModel.startArtistImageScan()
                                                }
                                            )
                                            .padding(
                                                horizontal = FoamDimens.spacingStandard,
                                                vertical = FoamDimens.spacingSmall
                                            )
                                    ) {
                                        AnimatedContent(
                                            targetState = scanRunning,
                                            transitionSpec = {
                                                fadeIn(animationSpec = tween(durationMillis = 120)) togetherWith
                                                        fadeOut(animationSpec = tween(durationMillis = 120))
                                            },
                                            label = "artistScanButtonLabel"
                                        ) { running ->
                                            BasicText(
                                                text = if (running) stringResource(R.string.artist_images_stop)
                                                else stringResource(R.string.start_scan),
                                                style = FoamTheme.typography.caption.copy(
                                                    color = if (running) FoamTheme.colors.textPrimary
                                                    else FoamTheme.colors.background,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            )
                                        }
                                    }
                                }

                                if (artistImageScan.running && artistImageScan.total > 0) {
                                    Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(FoamDimens.spacingTiny)
                                            .clip(RoundedCornerShape(FoamDimens.spacingTiny / 2))
                                            .background(FoamTheme.colors.surfaceVariant)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth(
                                                    artistImageScan.found.toFloat() / artistImageScan.total
                                                )
                                                .height(FoamDimens.spacingTiny)
                                                .background(FoamTheme.accentColor)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    BasicText(
                                        text = stringResource(
                                            R.string.artist_images_cache_count,
                                            artistImageScan.cachedCount
                                        ),
                                        style = FoamTheme.typography.caption,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(FoamDimens.spacingSmall))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(FoamDimens.buttonRounding))
                                            .background(FoamTheme.colors.surfaceVariant)
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = ripple(bounded = true, color = FoamTheme.colors.textPrimary),
                                                onClick = { viewModel.clearArtistImageCache() }
                                            )
                                            .padding(
                                                horizontal = FoamDimens.spacingStandard,
                                                vertical = FoamDimens.spacingSmall
                                            )
                                    ) {
                                        BasicText(
                                            text = stringResource(R.string.artist_images_clear_cache),
                                            style = FoamTheme.typography.caption.copy(
                                                color = FoamTheme.colors.textPrimary,
                                                fontWeight = FontWeight.Bold
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(FoamDimens.spacingExtraLarge))
            }
        }
    }

    separatorDialogTarget?.let { target ->
        AddSeparatorDialog(
            title = stringResource(
                when (target) {
                    SeparatorTarget.ARTIST -> R.string.artist_separators
                    SeparatorTarget.GENRE -> R.string.genre_separators
                }
            ),
            onConfirm = { value ->
                when (target) {
                    SeparatorTarget.ARTIST -> viewModel.setArtistSeparators(artistSeparators + value)
                    SeparatorTarget.GENRE -> viewModel.setGenreSeparators(genreSeparators + value)
                }
                separatorDialogTarget = null
            },
            onDismiss = { separatorDialogTarget = null }
        )
    }

    AddProviderModal(
        viewModel = viewModel,
        visible = showAddProvider,
        onDismiss = { showAddProvider = false },
        onPickLocalFolder = { folderPickerLauncher.launch(null) },
        onServerScanned = viewModel::scanServer
    )
}

/**
 * One metadata-separator card: title with a circular add button trailing on
 * the same row (same as the playlist picker's header +), then the current
 * separators as horizontal chips (wrapped by [FlowRow] when they run out of
 * width), all on a rounded surface card. The add button opens a modal instead
 * of the previous inline text field.
 *
 * The card's top padding is tighter than its bottom: the 40dp add button makes
 * the title row taller than the title text, which already leaves ~10dp of
 * visual air above the title -- a full paddingMedium on top read as ~27dp.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeparatorListSection(
    title: String,
    separators: Set<String>,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
            .background(FoamTheme.colors.surface)
            .padding(horizontal = FoamDimens.paddingMedium)
            .padding(top = FoamDimens.spacingSmall, bottom = FoamDimens.paddingMedium)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicText(
                    text = title,
                    style = FoamTheme.typography.body.copy(fontWeight = FontWeight.Bold, color = FoamTheme.colors.textPrimary),
                    modifier = Modifier.weight(1f)
                )
                PickerHeaderButton(
                    icon = Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.add),
                    onClick = onAdd
                )
            }
            Spacer(modifier = Modifier.height(FoamDimens.spacingTiny))

            if (separators.isEmpty()) {
                BasicText(
                    text = stringResource(R.string.none),
                    style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textSecondary),
                    modifier = Modifier.padding(vertical = FoamDimens.spacingTiny)
                )
            } else {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall),
                    verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)
                ) {
                    separators.sorted().forEach { separator ->
                        SeparatorChip(
                            separator = separator,
                            onRemove = { onRemove(separator) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * One separator chip: the character(s) on a rounded surfaceVariant pill with a
 * tiny accent cross on its right; the cross carries the remove ripple. Tight
 * vertical padding keeps the pill flat; the cross's touch target is what sets
 * the chip's height.
 */
@Composable
private fun SeparatorChip(
    separator: String,
    onRemove: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(FoamDimens.buttonRounding))
            .background(FoamTheme.colors.surfaceVariant)
            .padding(horizontal = FoamDimens.spacingMedium, vertical = FoamDimens.spacingTiny)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(text = separator, style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textPrimary))
            Spacer(modifier = Modifier.width(FoamDimens.spacingTiny))
            // Circular clip bounds the ripple around the tiny cross.
            Box(
                modifier = Modifier
                    .size(FoamDimens.iconMedium)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(color = FoamTheme.colors.textPrimary),
                        onClick = onRemove
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = FoamTheme.icons.current.close,
                    contentDescription = stringResource(R.string.remove),
                    tint = FoamTheme.accentColor,
                    modifier = Modifier.size(FoamDimens.iconTiny)
                )
            }
        }
    }
}

/**
 * Add-separator dialog: a small plain [Dialog] (not a FoamModal sheet) with a
 * single-line input and Cancel / Add buttons, mirroring the playlist-creation
 * dialog opened from the add-to-playlist picker's header. Reuses the shared
 * modal building blocks so the two look identical.
 */
@Composable
private fun AddSeparatorDialog(
    title: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(FoamDimens.itemRounding))
                .background(FoamTheme.colors.surface)
                .padding(FoamDimens.spacingMedium),
            verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)
        ) {
            BasicText(
                text = title,
                style = FoamTheme.typography.titleMedium,
                modifier = Modifier.padding(
                    horizontal = FoamDimens.paddingSmall,
                    vertical = FoamDimens.spacingTiny
                )
            )

            PlaylistNameField(
                value = value,
                onValueChange = { value = it },
                hint = stringResource(R.string.add_separator),
                focusRequester = focusRequester,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { if (value.isNotBlank()) onConfirm(value.trim()) }
                )
            )

            Row(horizontalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)) {
                ModalButton(
                    label = stringResource(R.string.cancel),
                    filled = false,
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                )
                ModalButton(
                    label = stringResource(R.string.add),
                    filled = true,
                    enabled = value.isNotBlank(),
                    onClick = { if (value.isNotBlank()) onConfirm(value.trim()) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
