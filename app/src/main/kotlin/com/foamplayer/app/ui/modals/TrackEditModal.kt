package com.foamplayer.app.ui.modals

import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt
import com.foamplayer.app.R
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.repository.MULTI_VALUE_SEPARATOR
import com.foamplayer.app.data.repository.TrackMetadataEdit
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme

/**
 * Local draft of the edit page, seeded from the track once when the page opens
 * (keyed on the id so stat updates don't wipe the form). Numeric fields stay
 * text until Apply parses them; blank optional fields become null.
 */
private data class TrackEditDraft(
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val genres: List<String>,
    val composer: String,
    val year: String,
    val trackNumber: String,
    val discNumber: String
) {
    companion object {
        fun from(track: TrackEntity): TrackEditDraft = TrackEditDraft(
            title = track.title,
            artist = track.artist,
            album = track.album,
            albumArtist = track.albumArtist.orEmpty(),
            genres = track.genre?.split(MULTI_VALUE_SEPARATOR)
                ?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
            composer = track.composer.orEmpty(),
            year = track.year?.toString().orEmpty(),
            trackNumber = track.trackNumber?.toString().orEmpty(),
            discNumber = track.discNumber?.toString().orEmpty()
        )
    }
}

/** Parses the draft into the persisted edit: trims, blanks → null, numeric text → Int. */
private fun TrackEditDraft.toEdit(): TrackMetadataEdit = TrackMetadataEdit(
    title = title.trim(),
    artist = artist.trim(),
    album = album.trim(),
    albumArtist = albumArtist.trim().ifEmpty { null },
    genres = genres.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
    composer = composer.trim().ifEmpty { null },
    year = year.trim().toIntOrNull(),
    trackNumber = trackNumber.trim().toIntOrNull(),
    discNumber = discNumber.trim().toIntOrNull()
)

/**
 * The track modal's metadata edit page: pill fields for the text metadata, the
 * genre list as removable chips with an add button (the library settings'
 * separator-list pattern), and an Apply/Cancel row — both return to the
 * details page, Apply persists through [TrackMetadataEdit].
 */
@Composable
internal fun TrackEditContent(
    track: TrackEntity,
    accentColor: Color,
    existingGenres: List<String>,
    onApply: (TrackMetadataEdit) -> Unit,
    onCancel: () -> Unit
) {
    var draft by remember(track) { mutableStateOf(TrackEditDraft.from(track)) }
    var showAddGenre by remember { mutableStateOf(false) }

    val focusManager = LocalFocusManager.current
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(imeVisible) {
        if (!imeVisible) focusManager.clearFocus()
    }

    Column(verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)) {
        EditField(
            title = stringResource(R.string.details_title),
            accentColor = accentColor,
            value = draft.title,
            onValueChange = { draft = draft.copy(title = it) }
        )
        EditField(
            title = stringResource(R.string.details_artist),
            accentColor = accentColor,
            value = draft.artist,
            onValueChange = { draft = draft.copy(artist = it) }
        )
        EditField(
            title = stringResource(R.string.details_album),
            accentColor = accentColor,
            value = draft.album,
            onValueChange = { draft = draft.copy(album = it) }
        )
        EditField(
            title = stringResource(R.string.details_album_artist),
            accentColor = accentColor,
            value = draft.albumArtist,
            onValueChange = { draft = draft.copy(albumArtist = it) }
        )
        GenreEditSection(
            genres = draft.genres,
            accentColor = accentColor,
            onAdd = { showAddGenre = true },
            onRemove = { genre -> draft = draft.copy(genres = draft.genres - genre) }
        )
        EditField(
            title = stringResource(R.string.details_composer),
            accentColor = accentColor,
            value = draft.composer,
            onValueChange = { draft = draft.copy(composer = it) }
        )
        EditField(
            title = stringResource(R.string.details_year),
            accentColor = accentColor,
            value = draft.year,
            onValueChange = { draft = draft.copy(year = it) },
            numeric = true
        )
        Row(horizontalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)) {
            EditField(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.details_track_number),
                accentColor = accentColor,
                value = draft.trackNumber,
                onValueChange = { draft = draft.copy(trackNumber = it) },
                numeric = true
            )
            EditField(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.details_disc_number),
                accentColor = accentColor,
                value = draft.discNumber,
                onValueChange = { draft = draft.copy(discNumber = it) },
                numeric = true
            )
        }

        Row(
            modifier = Modifier.padding(top = FoamDimens.spacingMedium),
            horizontalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)
        ) {
            ModalButton(
                label = stringResource(R.string.cancel),
                filled = false,
                modifier = Modifier.weight(1f),
                onClick = onCancel
            )
            ModalButton(
                label = stringResource(R.string.apply),
                filled = true,
                modifier = Modifier.weight(1f),
                enabled = draft.title.isNotBlank() && draft.artist.isNotBlank() && draft.album.isNotBlank(),
                onClick = { onApply(draft.toEdit()) }
            )
        }
    }

    if (showAddGenre) {
        AddGenreDialog(
            existingGenres = existingGenres,
            onConfirm = { genre ->
                draft = draft.copy(genres = (draft.genres + genre).distinct())
                showAddGenre = false
            },
            onDismiss = { showAddGenre = false }
        )
    }
}

/** One edit-page field: a track-colored title above the thin pill text field ([MetadataField]), which doubles as the placeholder. */
@Composable
private fun EditField(
    modifier: Modifier = Modifier,
    title: String,
    accentColor: Color,
    value: String,
    onValueChange: (String) -> Unit,
    numeric: Boolean = false
) {
    val focusManager = LocalFocusManager.current
    Column(modifier = modifier) {
        BasicText(
            text = title,
            style = FoamTheme.typography.caption.copy(color = accentColor),
            modifier = Modifier.padding(horizontal = FoamDimens.paddingSmall, vertical = FoamDimens.spacingTiny)
        )
        MetadataField(
            value = value,
            onValueChange = onValueChange,
            hint = title,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
        )
    }
}

/**
 * The metadata page's own thin pill text field — slimmer than the playlist
 * name pill it used to reuse, since this page stacks nine of them.
 * Same caret handling: BasicTextField's String overload drops the caret at
 * index 0 when focus is requested programmatically (the add-genre dialog),
 * so the selection is tracked explicitly and restored to the end whenever
 * the text is set from outside (prefill / re-open).
 * [trailing] renders an extra slot inside the pill's trailing edge (the
 * add-genre dropdown chevron); the text keeps clear of it. [shape] lets the
 * add-genre field square its bottom corners while its dropdown is attached.
 */
@Composable
private fun MetadataField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "",
    focusRequester: FocusRequester? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    shape: Shape = CircleShape,
    trailing: (@Composable () -> Unit)? = null
) {
    var textFieldValue by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    if (textFieldValue.text != value) {
        textFieldValue = TextFieldValue(value, TextRange(value.length))
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(FoamTheme.colors.surfaceVariant)
            .padding(horizontal = FoamDimens.paddingMedium, vertical = FoamDimens.spacingSmall),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    BasicText(
                        text = hint,
                        style = FoamTheme.typography.body.copy(color = FoamTheme.colors.textSecondary)
                    )
                }
                BasicTextField(
                    value = textFieldValue,
                    onValueChange = {
                        val textChanged = it.text != textFieldValue.text
                        textFieldValue = it
                        if (textChanged) onValueChange(it.text)
                    },
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
            if (trailing != null) {
                trailing()
            }
        }
    }
}

/** The edit page's genre section: a track-colored title above a rounded card holding the chips and the "+" on the same row (library settings' separator list). */
@Composable
private fun GenreEditSection(
    genres: List<String>,
    accentColor: Color,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit
) {
    Column {
        BasicText(
            text = stringResource(R.string.details_genre),
            style = FoamTheme.typography.caption.copy(color = accentColor),
            modifier = Modifier.padding(horizontal = FoamDimens.paddingSmall, vertical = FoamDimens.spacingTiny)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(FoamDimens.itemRounding))
                .background(FoamTheme.colors.surfaceVariant)
                .padding(
                    horizontal = FoamDimens.paddingMedium,
                    vertical = FoamDimens.spacingSmall
                )
        ) {
            if (genres.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText(
                        text = stringResource(R.string.none),
                        style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textSecondary),
                        modifier = Modifier.weight(1f)
                    )
                    AddGenreButton(onAdd)
                }
            } else {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall),
                    verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall),
                    itemVerticalAlignment = Alignment.CenterVertically
                ) {
                    genres.forEach { genre ->
                        GenreChip(genre = genre, onRemove = { onRemove(genre) })
                    }
                    AddGenreButton(onAdd)
                }
            }
        }
    }
}

/** The "+" that opens the add-genre dialog, sitting inline with the chips it extends. */
@Composable
private fun AddGenreButton(onAdd: () -> Unit) {
    PickerHeaderButton(
        icon = Icons.Rounded.Add,
        contentDescription = stringResource(R.string.add),
        onClick = onAdd
    )
}

/** One genre chip: the name on a pill with a tiny accent cross that removes it. */
@Composable
private fun GenreChip(genre: String, onRemove: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(FoamDimens.buttonRounding))
            .background(FoamTheme.colors.surface)
            .padding(
                start = FoamDimens.spacingMedium,
                top = FoamDimens.spacingTiny,
                end = FoamDimens.spacingTiny,
                bottom = FoamDimens.spacingTiny
            )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                text = genre,
                style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textPrimary)
            )
            Spacer(modifier = Modifier.width(FoamDimens.spacingTiny))
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
 * Add-genre dialog: a name field with an autocomplete dropdown of the
 * library's existing genres.
 *
 * Dropdown behaviour:
 * - It hangs directly under the field and reads as one surface with it (same
 *   color, no gap, the field's bottom corners square off while it is open).
 * - It is filtered live by the text (case-insensitive "contains", prefix
 *   matches first), and opens as soon as the user types.
 * - The chevron in the field shows/hides it at any time.
 * - Picking a row fills the field and closes the list; it only comes back
 *   when the user types again or taps the chevron.
 *
 * Implementation notes (these are the fixes over the previous version):
 * - No separate Popup window any more. The popup's outside-tap handling
 *   closed the list on a chevron tap and the chevron's own click then
 *   re-opened it (the "closes then reopens" bug), and its height was capped
 *   by the height of a wrap-content dialog window (the "only 2 rows" bug).
 * - The dialog window is full-screen (transparent card centred in it), so
 *   the list can overlay the buttons and extend past the card without being
 *   clipped, and is sized from the real visible height (window resizes for
 *   the soft keyboard) so it never runs behind the IME.
 * - [MetadataField] only reports real text changes, not cursor/selection
 *   moves, so merely tapping into the field can't re-open the list.
 * - The typed text is no longer trimmed on every keystroke (that made it
 *   impossible to type multi-word genres such as "Hip Hop"); it is trimmed
 *   when confirming.
 */
@Composable
private fun AddGenreDialog(
    existingGenres: List<String>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var fieldBounds by remember { mutableStateOf(Rect.Zero) }
    var rootHeightPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val allGenres = remember(existingGenres) {
        existingGenres.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }
    val query = value.trim()
    // Filter the dropdown
    val suggestions = remember(allGenres, query) {
        allGenres
            .filter { it.contains(query, ignoreCase = true) }
            .sortedByDescending { it.startsWith(query, ignoreCase = true) }
    }
    val listVisible = expanded && suggestions.isNotEmpty()

    // Square the field's bottom corners while the list is attached to it.
    val fieldBottomCornerPercent by animateIntAsState(
        targetValue = if (listVisible) 0 else 50,
        label = "GenreFieldBottomCorners"
    )
    val chevronRotation by animateFloatAsState(
        targetValue = if (listVisible) 180f else 0f,
        label = "GenreChevronRotation"
    )

    Dialog(
        onDismissRequest = { if (listVisible) expanded = false else onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val dialogView = LocalView.current
        DisposableEffect(dialogView) {
            (dialogView.parent as? DialogWindowProvider)?.window
                ?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            onDispose { }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { rootHeightPx = it.height }
                .pointerInput(listVisible) {
                    detectTapGestures { if (listVisible) expanded = false else onDismiss() }
                },
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(FoamDimens.itemRounding))
                    .background(FoamTheme.colors.surface)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(FoamDimens.spacingMedium),
                verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)
            ) {
                BasicText(
                    text = stringResource(R.string.add_genre),
                    style = FoamTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = FoamDimens.paddingSmall, vertical = FoamDimens.spacingTiny)
                )
                MetadataField(
                    modifier = Modifier.onGloballyPositioned { fieldBounds = it.boundsInRoot() },
                    shape = RoundedCornerShape(
                        topStartPercent = 50,
                        topEndPercent = 50,
                        bottomEndPercent = fieldBottomCornerPercent,
                        bottomStartPercent = fieldBottomCornerPercent
                    ),
                    value = value,
                    onValueChange = {
                        value = it
                        expanded = true
                    },
                    hint = stringResource(R.string.add_genre),
                    focusRequester = focusRequester,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (value.isNotBlank()) onConfirm(value.trim()) }),
                    trailing = if (allGenres.isNotEmpty()) {
                        {
                            Box(
                                modifier = Modifier
                                    .offset(x = 6.dp)
                                    .size(28.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        role = Role.Button,
                                        onClick = { expanded = !listVisible }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ExpandMore,
                                    contentDescription = stringResource(R.string.show_existing_genres),
                                    tint = FoamTheme.colors.textSecondary,
                                    modifier = Modifier.graphicsLayer { rotationZ = chevronRotation }
                                )
                            }
                        }
                    } else {
                        null
                    }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)) {
                    ModalButton(
                        label = stringResource(R.string.cancel),
                        filled = false,
                        modifier = Modifier.weight(1f),
                        onClick = onDismiss
                    )
                    ModalButton(
                        label = stringResource(R.string.add),
                        filled = true,
                        modifier = Modifier.weight(1f),
                        enabled = value.isNotBlank(),
                        onClick = { if (value.isNotBlank()) onConfirm(value.trim()) }
                    )
                }
            }

            if (fieldBounds.width > 0f) {
                val maxListHeight = with(density) {
                    (rootHeightPx - fieldBounds.bottom - 12.dp.toPx()).toDp()
                        .coerceIn(GenreDropdownMinHeight, GenreDropdownMaxHeight)
                }
                GenreDropdown(
                    visible = listVisible,
                    genres = suggestions,
                    width = with(density) { fieldBounds.width.toDp() },
                    maxHeight = maxListHeight,
                    onSelect = { genre ->
                        value = genre
                        expanded = false
                    },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset { IntOffset(fieldBounds.left.roundToInt(), fieldBounds.bottom.roundToInt()) }
                )
            }
        }
    }
}

private val GenreDropdownMaxHeight = 280.dp
private val GenreDropdownMinHeight = 96.dp

/**
 * The add-genre suggestion list: the continuation of the pill field above it
 * (same surface, square top edge, rounded bottom), positioned by the caller
 * flush under the field. Height-capped by [maxHeight] and scrollable; tapping
 * a row calls [onSelect]. Expands/collapses from the field's bottom edge.
 */
@Composable
private fun GenreDropdown(
    visible: Boolean,
    genres: List<String>,
    width: Dp,
    maxHeight: Dp,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var displayed by remember { mutableStateOf(genres) }
    if (genres.isNotEmpty() && genres != displayed) displayed = genres

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
    ) {
        LazyColumn(
            modifier = Modifier
                .width(width)
                .heightIn(max = maxHeight)
                .clip(
                    RoundedCornerShape(
                        bottomStart = FoamDimens.itemRounding,
                        bottomEnd = FoamDimens.itemRounding
                    )
                )
                .background(FoamTheme.colors.surfaceVariant)
        ) {
            items(displayed, key = { it }) { genre ->
                BasicText(
                    text = genre,
                    style = FoamTheme.typography.body.copy(color = FoamTheme.colors.textPrimary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(color = FoamTheme.colors.textPrimary),
                            onClick = { onSelect(genre) }
                        )
                        .padding(horizontal = FoamDimens.paddingMedium, vertical = FoamDimens.spacingSmall)
                )
            }
        }
    }
}
