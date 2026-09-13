package com.foamplayer.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.core.net.toUri
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.utils.AlbumArtPaletteCache
import com.foamplayer.app.ui.utils.TimeUtils

/** What kind of browsable collection a [CollectionInfo] describes. */
enum class CollectionKind(val displayName: String) {
    Album("Album"),
    Artist("Artist"),
    Genre("Genre"),
    Year("Year"),
    Folder("Folder")
}

/**
 * The collection shown in [CollectionFoamModal] / [CollectionDetailsModal].
 * Unlike tracks, collections are a UI-level abstraction: [title] plus optional
 * display fields, and — when the source screen can resolve them — the
 * collection's [tracks], which power the Next/Queue actions and the stats
 * shown on the details page. Without [tracks] the queue actions simply no-op.
 *
 * [artist] doubles as the header's second line and the target of "See Artist"
 * (the row is hidden when null); [subtitle] is the fallback second line for
 * collections without an artist (e.g. "12 tracks" for folders). [location] is
 * the storage path shown on the details page when relevant (folders).
 */
data class CollectionInfo(
    val title: String,
    val artist: String? = null,
    val subtitle: String? = null,
    val imageUrl: String? = null,
    val icon: ImageVector? = null,
    val kind: CollectionKind? = null,
    val tracks: List<TrackEntity> = emptyList(),
    val location: String? = null
)

/**
 * Holds the collection currently shown in a [CollectionFoamModal]. A screen
 * owns one of these, calls [show] from a long-press handler, and renders a
 * single [CollectionFoamModal] bound to it.
 */
class CollectionFoamModalState {
    var collection: CollectionInfo? by mutableStateOf(null)
        private set

    val isVisible: Boolean
        get() = collection != null

    fun show(collection: CollectionInfo) {
        this.collection = collection
    }

    fun dismiss() {
        collection = null
    }
}

@Composable
fun rememberCollectionFoamModalState(): CollectionFoamModalState = remember { CollectionFoamModalState() }

/**
 * Bottom sheet shown when the user long-presses a collection (album, artist,
 * genre, year, folder). The track modal's counterpart, minus what collections
 * have no use for: no score heart in the header, no Playlist button. Layout,
 * top to bottom:
 * - A header on a darker container: artwork (or icon/letter fallback) with the
 *   collection title and its artist underneath. It stays mounted across page
 *   switches.
 * - The actions page: two side-by-side buttons ("Next" = queue the collection
 *   right after the current track, "Queue" = append it to the end of the main
 *   queue), then navigation rows — "See Artist" for albums, "See Albums" for
 *   artists, and both "See Artist" + "See Album" for folders/genres/years
 *   (when such a collection spans several artists/albums, the row opens a
 *   list screen of them via [onSeeArtistList] / [onSeeAlbumList]) — and a
 *   final "Details" button in its own section.
 * - The details page: the one-line count/duration summary plus the collection
 *   metadata, reached from the Details button with a push-style slide — the
 *   header never leaves the composition; a Back button returns to the actions.
 *
 * Every action dismisses the sheet before invoking its callback.
 */
@Composable
fun CollectionFoamModal(
    state: CollectionFoamModalState,
    onPlayNext: (CollectionInfo) -> Unit = {},
    onAddToQueue: (CollectionInfo) -> Unit = {},
    onSeeArtist: (String) -> Unit = {},
    onSeeAlbum: (album: String, albumArtPath: String?, artist: String?) -> Unit = { _, _, _ -> },
    onSeeArtistList: (sourceKind: CollectionKind, sourceKey: String) -> Unit = { _, _ -> },
    onSeeAlbumList: (sourceKind: CollectionKind, sourceKey: String) -> Unit = { _, _ -> },
    useAlbumArtAccent: Boolean = false,
    onDismiss: () -> Unit = { state.dismiss() }
) {
    // FoamBottomModal stays mounted while its exit animation plays, but
    // state.collection is already null by then. Keep the last shown collection
    // around so the sheet keeps rendering its content while it slides away.
    var lastShownCollection by remember { mutableStateOf<CollectionInfo?>(null) }
    SideEffect {
        if (state.collection != null) lastShownCollection = state.collection
    }
    val displayCollection = state.collection ?: lastShownCollection

    // Accent for the shared header + details page: the collection's own album
    // art color when the automatic-color setting is on, else the app accent.
    val context = LocalContext.current
    LaunchedEffect(useAlbumArtAccent, displayCollection?.imageUrl) {
        if (useAlbumArtAccent) {
            AlbumArtPaletteCache.ensure(this, context, displayCollection?.imageUrl)
        }
    }
    val artAccent = if (useAlbumArtAccent) {
        displayCollection?.imageUrl?.let { AlbumArtPaletteCache.colors[it] }
    } else {
        null
    }
    val accentColor = artAccent ?: FoamTheme.accentColor

    // Details is a second page of the SAME sheet (one Dialog window), so the
    // header can genuinely persist across the switch. Reset when the sheet
    // closes or changes collection.
    var showDetails by remember { mutableStateOf(false) }
    LaunchedEffect(state.collection) {
        showDetails = false
    }

    // Horizontal gap kept between the outgoing and incoming pages while they
    // slide (both travel one width + gap in lockstep), so their contents never
    // touch mid-transition; at rest neither offset applies.
    val pageGapPx = with(LocalDensity.current) { FoamDimens.spacingSmall.roundToPx() }

    fun perform(action: (CollectionInfo) -> Unit) {
        val current = state.collection ?: return
        state.dismiss()
        action(current)
    }

    FoamBottomModal(
        visible = state.isVisible,
        onDismissRequest = onDismiss
    ) {
        if (displayCollection != null) {
            Column(
                modifier = Modifier.padding(bottom = FoamDimens.spacingSmall),
                verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)
            ) {
                CollectionModalHeader(
                    collection = displayCollection,
                    accentColor = accentColor
                )

                AnimatedContent(
                    targetState = showDetails,
                    transitionSpec = {
                        if (targetState) {
                            // Push: details slides in from the right...
                            slideInHorizontally { it + pageGapPx } togetherWith
                                    slideOutHorizontally { -it - pageGapPx }
                        } else {
                            // ...pop: back to the actions.
                            slideInHorizontally { -it - pageGapPx } togetherWith
                                    slideOutHorizontally { it + pageGapPx }
                        }
                    },
                    label = "CollectionModalPage"
                ) { isDetails ->
                    Column(
                        verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)
                    ) {
                        if (isDetails) {
                            CollectionDetailsContent(
                                collection = displayCollection,
                                accentColor = accentColor
                            )

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(FoamDimens.itemRounding))
                                    .background(FoamTheme.colors.surfaceVariant)
                            ) {
                                ModalBackButton(onBack = { showDetails = false })
                            }
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(FoamDimens.itemRounding))
                                    .background(FoamTheme.colors.surfaceVariant)
                                    .padding(
                                        horizontal = FoamDimens.paddingSmall,
                                        vertical = FoamDimens.spacingTiny
                                    )
                            ) {
                                ModalActionButton(
                                    icon = FoamTheme.icons.current.next,
                                    label = "Next",
                                    onClick = { perform(onPlayNext) },
                                    modifier = Modifier.weight(1f)
                                )
                                ModalActionButton(
                                    icon = FoamTheme.icons.current.queue,
                                    label = "Queue",
                                    onClick = { perform(onAddToQueue) },
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            val navigationRows = collectionNavigationRows(
                                collection = displayCollection,
                                perform = { action ->
                                    state.dismiss()
                                    action()
                                },
                                onSeeArtist = onSeeArtist,
                                onSeeAlbum = onSeeAlbum,
                                onSeeArtistList = onSeeArtistList,
                                onSeeAlbumList = onSeeAlbumList
                            )

                            if (navigationRows.isNotEmpty()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(FoamDimens.itemRounding))
                                        .background(FoamTheme.colors.surfaceVariant)
                                ) {
                                    navigationRows.forEachIndexed { index, row ->
                                        if (index > 0) ModalSectionDivider()
                                        ModalRowButton(
                                            label = row.label,
                                            onClick = row.onClick
                                        )
                                    }
                                }
                            }

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(FoamDimens.itemRounding))
                                    .background(FoamTheme.colors.surfaceVariant)
                            ) {
                                ModalRowButton(
                                    label = "Details",
                                    onClick = { showDetails = true }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Provided by [com.foamplayer.app.ui.navigation.FoamNavHost] at the app root so
 * every [CollectionListItem] can open the collection modal on long-press
 * without each screen threading it through. Screens that pass an explicit
 * onLongClick to the item always take precedence.
 */
val LocalCollectionFoamModalState = staticCompositionLocalOf<CollectionFoamModalState?> { null }

/**
 * Full metadata sheet for a collection. Also embedded as the second page of
 * [CollectionFoamModal] (see its details button), where the header persists
 * across the switch; this standalone hosting is kept for direct use.
 * Same visual language as [TrackDetailsModal]: the collection header on a
 * darker container, then grouped label/value sections on lighter containers.
 */
@Composable
fun CollectionDetailsModal(
    collection: CollectionInfo?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    useAlbumArtAccent: Boolean = false
) {
    // Same exit-animation preservation as the other modals in this package.
    var lastShownCollection by remember { mutableStateOf<CollectionInfo?>(null) }
    SideEffect {
        if (collection != null) lastShownCollection = collection
    }
    val displayCollection = collection ?: lastShownCollection

    val context = LocalContext.current
    LaunchedEffect(useAlbumArtAccent, displayCollection?.imageUrl) {
        if (useAlbumArtAccent) {
            AlbumArtPaletteCache.ensure(this, context, displayCollection?.imageUrl)
        }
    }
    val artAccent = if (useAlbumArtAccent) {
        displayCollection?.imageUrl?.let { AlbumArtPaletteCache.colors[it] }
    } else {
        null
    }
    val accentColor = artAccent ?: FoamTheme.accentColor

    FoamBottomModal(
        visible = collection != null,
        onDismissRequest = onDismiss,
        modifier = modifier
    ) {
        if (displayCollection != null) {
            Column(
                modifier = Modifier.padding(bottom = FoamDimens.spacingSmall),
                verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)
            ) {
                CollectionModalHeader(
                    collection = displayCollection,
                    accentColor = accentColor
                )

                CollectionDetailsContent(
                    collection = displayCollection,
                    accentColor = accentColor
                )
            }
        }
    }
}

/** Header block: artwork (or icon/letter fallback) on the left, title with the artist underneath, on a darker container. */
@Composable
private fun CollectionModalHeader(
    collection: CollectionInfo,
    modifier: Modifier = Modifier,
    accentColor: Color = FoamTheme.accentColor
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FoamDimens.itemRounding))
            .background(FoamTheme.colors.background)
            .padding(FoamDimens.spacingMedium),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(albumArtCornerRadius(64.dp)))
                .background(FoamTheme.colors.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (collection.imageUrl != null) {
                val context = LocalContext.current
                val artRequest = remember(collection.imageUrl) {
                    ImageRequest.Builder(context)
                        .data(EmbeddedAlbumArt(collection.imageUrl.toUri()))
                        .size(AlbumArtRequestSize)
                        .build()
                }
                AsyncImage(
                    model = artRequest,
                    contentDescription = "Album Art",
                    modifier = Modifier.size(64.dp),
                    contentScale = ContentScale.Crop
                )
            } else if (collection.icon != null) {
                Icon(
                    imageVector = collection.icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(32.dp)
                )
            } else {
                BasicText(
                    text = collection.title.take(1).uppercase(),
                    style = FoamTheme.typography.titleLarge.copy(color = accentColor)
                )
            }
        }

        Spacer(modifier = Modifier.width(FoamDimens.spacingMedium))

        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = collection.title,
                style = FoamTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val secondLine = collection.artist ?: collection.subtitle
            if (secondLine != null) {
                BasicText(
                    text = secondLine,
                    style = FoamTheme.typography.body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** The Collection (+ optional Location) sections shared by the standalone details modal and the details page of [CollectionFoamModal]. */
@Composable
private fun CollectionDetailsContent(
    collection: CollectionInfo,
    accentColor: Color
) {
    val totalDurationMs = collection.tracks.sumOf { it.duration }

    DetailsSection(title = "Collection", accentColor = accentColor) {
        DetailRow("Type", collection.kind?.displayName)
        DetailRow("Artist", collection.artist)
        DetailRow("Tracks", collection.tracks.size.takeIf { it > 0 }?.toString())
        DetailRow(
            "Duration",
            totalDurationMs.takeIf { it > 0 }?.let { TimeUtils.formatDurationLong(it) }
        )
        if (collection.kind == CollectionKind.Album) {
            DetailRow(
                "Album art",
                if (collection.imageUrl != null) "Embedded in files" else null
            )
        }
    }

    if (collection.location != null) {
        DetailsSection(title = "Location", accentColor = accentColor) {
            DetailStackedValue(
                label = "Path",
                value = simplifiedDisplayPath(collection.location),
                accentColor = accentColor
            )
        }
    }
}

/** One navigation row on the actions page: label plus the action to run on tap. */
private data class CollectionModalNavRow(
    val label: String,
    val onClick: () -> Unit
)

/** A distinct album among a collection's tracks, everything the album detail route needs. */
data class CollectionAlbumEntry(
    val album: String,
    val albumArtPath: String?,
    val artist: String?
)

/**
 * The "See Artist" / "See Albums" / "See Album" rows for a collection, per
 * kind: albums navigate to their (first) artist, artists to their album list,
 * and folders/genres/years offer both rows — navigating directly when the
 * collection holds exactly one artist/album, opening a list screen of them
 * otherwise. Every row runs through [perform], so the sheet dismisses first.
 */
private fun collectionNavigationRows(
    collection: CollectionInfo,
    perform: (() -> Unit) -> Unit,
    onSeeArtist: (String) -> Unit,
    onSeeAlbum: (String, String?, String?) -> Unit,
    onSeeArtistList: (CollectionKind, String) -> Unit,
    onSeeAlbumList: (CollectionKind, String) -> Unit
): List<CollectionModalNavRow> = when (collection.kind) {
    // The artist detail screen IS the artist's album list.
    CollectionKind.Artist -> listOf(
        CollectionModalNavRow("See Albums") { perform { onSeeArtist(collection.title) } }
    )
    else -> buildList {
        when (collection.kind) {
            // Album rows carry a possibly "; "-joined album-artist string;
            // navigating to the first name keeps the existing behavior.
            CollectionKind.Album -> {
                collection.artist?.split("; ")
                    ?.firstOrNull { it.isNotBlank() }
                    ?.let { name ->
                        add(CollectionModalNavRow("See Artist") { perform { onSeeArtist(name) } })
                    }
            }
            else -> {
                val sourceKind = collection.kind
                val sourceKey = collection.listSourceKey()

                val artistNames = collection.distinctArtistNames()
                when {
                    artistNames.size == 1 -> add(
                        CollectionModalNavRow("See Artist") { perform { onSeeArtist(artistNames.first()) } }
                    )
                    artistNames.size > 1 && sourceKind != null -> add(
                        CollectionModalNavRow("See Artist") { perform { onSeeArtistList(sourceKind, sourceKey) } }
                    )
                }

                val albums = collection.tracks.distinctCollectionAlbums()
                when {
                    albums.size == 1 -> {
                        val entry = albums.first()
                        add(
                            CollectionModalNavRow("See Album") {
                                perform { onSeeAlbum(entry.album, entry.albumArtPath, entry.artist) }
                            }
                        )
                    }
                    albums.size > 1 && sourceKind != null -> add(
                        CollectionModalNavRow("See Album") { perform { onSeeAlbumList(sourceKind, sourceKey) } }
                    )
                }
            }
        }
    }
}

/** Where the "artists-in / albums-in" list screens re-resolve this collection's tracks from. */
private fun CollectionInfo.listSourceKey(): String = when (kind) {
    CollectionKind.Folder -> location ?: title
    else -> title
}

/** Individual artist names across [tracks] ("; "-joined multi-artist fields are split). */
internal fun List<TrackEntity>.distinctCollectionArtistNames(): List<String> =
    flatMap { listOfNotNull(it.artist, it.albumArtist) }
        .flatMap { it.split("; ") }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

internal fun CollectionInfo.distinctArtistNames(): List<String> = tracks.distinctCollectionArtistNames()

/** One entry per distinct album among [tracks], in first-seen order. */
internal fun List<TrackEntity>.distinctCollectionAlbums(): List<CollectionAlbumEntry> =
    groupBy { it.album }.map { (album, albumTracks) ->
        CollectionAlbumEntry(
            album = album,
            albumArtPath = albumTracks.firstNotNullOfOrNull { it.albumArtPath },
            artist = albumTracks.first().albumArtist ?: albumTracks.first().artist
        )
    }
