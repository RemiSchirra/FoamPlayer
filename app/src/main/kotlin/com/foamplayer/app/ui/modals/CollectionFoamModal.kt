package com.foamplayer.app.ui.modals

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.core.net.toUri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Person
import com.foamplayer.app.R
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import com.foamplayer.app.ui.components.AlbumArtRequestSize
import com.foamplayer.app.ui.components.albumArtCornerRadius
import com.foamplayer.app.ui.components.albumArtMemoryCacheKey
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.theme.adaptAccentForTheme
import com.foamplayer.app.ui.utils.AlbumArtPaletteCache
import com.foamplayer.app.ui.utils.TimeUtils

/** What kind of browsable collection a [CollectionInfo] describes. */
enum class CollectionKind(val displayName: String) {
    Album("Album"),
    Artist("Artist"),
    Genre("Genre"),
    Year("Year"),
    Folder("Folder"),
    Composer("Composer"),
    Playlist("Playlist")
}

/** Localized label of a [CollectionKind] ("Album", "Artist", ...). */
@Composable
fun CollectionKind.label(): String = stringResource(
    when (this) {
        CollectionKind.Album -> R.string.kind_album
        CollectionKind.Artist -> R.string.kind_artist
        CollectionKind.Genre -> R.string.kind_genre
        CollectionKind.Year -> R.string.kind_year
        CollectionKind.Folder -> R.string.kind_folder
        CollectionKind.Composer -> R.string.kind_composer
        CollectionKind.Playlist -> R.string.kind_playlist
    }
)

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
 *
 * [imageUrl] is a plain image URI for playlist covers and Spotify artist
 * pictures ([imageIsEmbeddedArt] == false) and an audio file path whose
 * embedded picture must be extracted for albums/tracks (true, the default).
 */
data class CollectionInfo(
    val title: String,
    val artist: String? = null,
    val subtitle: String? = null,
    val imageUrl: String? = null,
    val imageIsEmbeddedArt: Boolean = true,
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
 * genre, year, folder, composer). The track modal's counterpart, minus what
 * collections have no use for: no score heart in the header. Layout, top to
 * bottom:
 * - A header on a darker container: artwork (or icon/letter fallback) with the
 *   collection title and its artist underneath. It stays mounted across page
 *   switches.
 * - The actions page: three side-by-side buttons ("Next" = queue the
 *   collection right after the current track, "Queue" = append it to the end
 *   of the main queue, "Playlist" = add every track of the collection to a
 *   playlist via the playlist picker), then navigation rows — "See Artist" for albums, "See Albums" for
 *   artists, and both "See Artist" + "See Album" for folders/genres/years/
 *   composers (when such a collection spans several artists/albums, the row
 *   opens a list screen of them via [onSeeArtistList] / [onSeeAlbumList]) —
 *   and a final "Details" button in its own section.
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
    onAddToPlaylist: (CollectionInfo) -> Unit = {},
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
    LaunchedEffect(
        useAlbumArtAccent,
        displayCollection?.imageUrl,
        displayCollection?.imageIsEmbeddedArt
    ) {
        if (useAlbumArtAccent) {
            AlbumArtPaletteCache.ensure(
                scope = this,
                context = context,
                path = displayCollection?.imageUrl,
                isEmbeddedArt = displayCollection?.imageIsEmbeddedArt ?: true
            )
        }
    }
    val artAccent = if (useAlbumArtAccent) {
        displayCollection?.imageUrl?.let { AlbumArtPaletteCache.colors[it] }
    } else {
        null
    }
    val accentColor = artAccent?.adaptAccentForTheme(FoamTheme.isDark) ?: FoamTheme.accentColor

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
        // Match the track modal: system back while on the details page returns
        // to the actions page instead of dismissing the whole sheet.
        BackHandler(enabled = state.isVisible && showDetails) {
            showDetails = false
        }

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
                                    label = stringResource(R.string.next),
                                    onClick = { perform(onPlayNext) },
                                    modifier = Modifier.weight(1f)
                                )
                                ModalActionButton(
                                    icon = FoamTheme.icons.current.queue,
                                    label = stringResource(R.string.queue),
                                    onClick = { perform(onAddToQueue) },
                                    modifier = Modifier.weight(1f)
                                )
                                ModalActionButton(
                                    icon = FoamTheme.icons.current.playlists,
                                    label = stringResource(R.string.playlist),
                                    onClick = { perform(onAddToPlaylist) },
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            // distinctArtistNames()/distinctCollectionAlbums() scan the
                            // whole track list (flatMap/split/distinct + groupBy) and
                            // this modal recomposes per animation frame while the
                            // dynamic accent color animates -- compute them once per
                            // collection instead of once per recomposition.
                            val navArtistNames = remember(displayCollection) {
                                displayCollection.distinctArtistNames()
                            }
                            val navAlbums = remember(displayCollection) {
                                displayCollection.tracks.distinctCollectionAlbums()
                            }
                            val navLabels = rememberNavRowLabels()
                            val navigationRows = collectionNavigationRows(
                                collection = displayCollection,
                                artistNames = navArtistNames,
                                albums = navAlbums,
                                labels = navLabels,
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
                                            icon = row.icon,
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
                                    label = stringResource(R.string.details),
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
 * every [com.foamplayer.app.ui.components.CollectionListItem] can open the collection modal on long-press
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
    LaunchedEffect(
        useAlbumArtAccent,
        displayCollection?.imageUrl,
        displayCollection?.imageIsEmbeddedArt
    ) {
        if (useAlbumArtAccent) {
            AlbumArtPaletteCache.ensure(
                scope = this,
                context = context,
                path = displayCollection?.imageUrl,
                isEmbeddedArt = displayCollection?.imageIsEmbeddedArt ?: true
            )
        }
    }
    val artAccent = if (useAlbumArtAccent) {
        displayCollection?.imageUrl?.let { AlbumArtPaletteCache.colors[it] }
    } else {
        null
    }
    val accentColor = artAccent?.adaptAccentForTheme(FoamTheme.isDark) ?: FoamTheme.accentColor

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
        // Artist images render fully round (matching every other artist
        // surface); other collections keep the percentage-based album shape.
        val headerArtShape = if (collection.kind == CollectionKind.Artist) {
            CircleShape
        } else {
            RoundedCornerShape(albumArtCornerRadius(64.dp))
        }
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(headerArtShape)
                .background(FoamTheme.colors.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (collection.imageUrl != null) {
                val context = LocalContext.current
                val artRequest = remember(collection.imageUrl, collection.imageIsEmbeddedArt) {
                    ImageRequest.Builder(context)
                        .data(
                            // Embedded art: the model is an audio file whose picture
                            // is extracted. Otherwise the model is a plain image
                            // (playlist cover / Spotify artist cache file).
                            if (collection.imageIsEmbeddedArt) {
                                EmbeddedAlbumArt(collection.imageUrl.toUri())
                            } else {
                                collection.imageUrl
                            }
                        )
                        .size(AlbumArtRequestSize)
                        .memoryCacheKey(albumArtMemoryCacheKey(collection.imageUrl))
                        .build()
                }
                AsyncImage(
                    model = artRequest,
                    contentDescription = stringResource(R.string.album_art),
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
            // The EQ preset assigned to this album, if any (album-kind only: the
            // assignment is keyed by album name).
            AssignedPresetLine(
                presetName = if (collection.kind == CollectionKind.Album) {
                    LocalDspAssignments.current.albumPresets[collection.title]
                } else {
                    null
                },
                modifier = Modifier.padding(top = FoamDimens.spacingTiny)
            )
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
    // The album's credited composers across its tracks ("; " multi-value
    // convention split apart), in first-seen order; empty when none is tagged.
    val composers = if (collection.kind == CollectionKind.Album) {
        remember(collection) {
            collection.tracks.asSequence()
                .mapNotNull { it.composer }
                .flatMap { it.split("; ") }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .toList()
        }
    } else {
        emptyList()
    }

    DetailsSection(title = stringResource(R.string.details_collection), accentColor = accentColor) {
        DetailRow(stringResource(R.string.details_type), collection.kind?.let { with(it) { label() } })
        DetailRow(stringResource(R.string.details_artist), collection.artist)
        DetailRow(stringResource(R.string.details_tracks), collection.tracks.size.takeIf { it > 0 }?.toString())
        DetailRow(
            stringResource(R.string.details_duration),
            totalDurationMs.takeIf { it > 0 }?.let { TimeUtils.formatDurationLong(it) }
        )
        if (composers.isNotEmpty()) {
            DetailRow(stringResource(R.string.details_composer), composers.joinToString("; "))
        }
        if (collection.kind == CollectionKind.Album) {
            DetailRow(
                stringResource(R.string.album_art),
                if (collection.imageUrl != null) stringResource(R.string.embedded_in_files) else null
            )
        }
    }

    if (collection.location != null) {
        DetailsSection(title = stringResource(R.string.details_location), accentColor = accentColor) {
            DetailStackedValue(
                label = stringResource(R.string.details_path),
                value = simplifiedDisplayPath(collection.location),
                accentColor = accentColor
            )
        }
    }
}

/** One navigation row on the actions page: label plus the action to run on tap. */
private data class CollectionModalNavRow(
    val label: String,
    val icon: ImageVector? = null,
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
 * and folders/genres/years/composers offer both rows — navigating directly
 * when the collection holds exactly one artist/album, opening a list screen
 * of them otherwise. Every row runs through [perform], so the sheet
 * dismisses first.
 */
private fun collectionNavigationRows(
    collection: CollectionInfo,
    artistNames: List<String>,
    albums: List<CollectionAlbumEntry>,
    labels: NavRowLabels,
    perform: (() -> Unit) -> Unit,
    onSeeArtist: (String) -> Unit,
    onSeeAlbum: (String, String?, String?) -> Unit,
    onSeeArtistList: (CollectionKind, String) -> Unit,
    onSeeAlbumList: (CollectionKind, String) -> Unit
): List<CollectionModalNavRow> = when (collection.kind) {
    // The artist detail screen IS the artist's album list.
    CollectionKind.Artist -> listOf(
        CollectionModalNavRow(labels.seeAlbums, icon = Icons.Rounded.Album) {
            perform { onSeeArtist(collection.title) }
        }
    )
    else -> buildList {
        when (collection.kind) {
            // Album rows carry a possibly "; "-joined album-artist string;
            // navigating to the first name keeps the existing behavior.
            CollectionKind.Album -> {
                collection.artist?.split("; ")
                    ?.firstOrNull { it.isNotBlank() }
                    ?.let { name ->
                        add(
                            CollectionModalNavRow(labels.seeArtist, icon = Icons.Rounded.Person) {
                                perform { onSeeArtist(name) }
                            }
                        )
                    }
            }
            else -> {
                val sourceKind = collection.kind
                val sourceKey = collection.listSourceKey()

                when {
                    artistNames.size == 1 -> add(
                        CollectionModalNavRow(labels.seeArtist, icon = Icons.Rounded.Person) {
                            perform { onSeeArtist(artistNames.first()) }
                        }
                    )
                    artistNames.size > 1 && sourceKind != null -> add(
                        CollectionModalNavRow(labels.seeArtist, icon = Icons.Rounded.Person) {
                            perform { onSeeArtistList(sourceKind, sourceKey) }
                        }
                    )
                }

                when {
                    albums.size == 1 -> {
                        val entry = albums.first()
                        add(
                            CollectionModalNavRow(labels.seeAlbum, icon = Icons.Rounded.Album) {
                                perform { onSeeAlbum(entry.album, entry.albumArtPath, entry.artist) }
                            }
                        )
                    }
                    albums.size > 1 && sourceKind != null -> add(
                        CollectionModalNavRow(labels.seeAlbum, icon = Icons.Rounded.Album) {
                            perform { onSeeAlbumList(sourceKind, sourceKey) }
                        }
                    )
                }
            }
        }
    }
}

/** Pre-resolved labels of the collection modal's navigation rows. */
private data class NavRowLabels(
    val seeArtist: String,
    val seeAlbum: String,
    val seeAlbums: String
)

@Composable
private fun rememberNavRowLabels(): NavRowLabels = NavRowLabels(
    seeArtist = stringResource(R.string.see_artist),
    seeAlbum = stringResource(R.string.see_album),
    seeAlbums = stringResource(R.string.see_albums)
)

/** Where the "artists-in / albums-in" list screens re-resolve this collection's tracks from. */
private fun CollectionInfo.listSourceKey(): String = when (kind) {
    CollectionKind.Folder -> location ?: title
    else -> title
}

/** Individual artist names across [tracks] ("; "-joined multi-artist fields are split). */
internal fun List<TrackEntity>.distinctCollectionArtistNames(): List<String> =
    asSequence().flatMap { listOfNotNull(it.artist, it.albumArtist) }
        .flatMap { it.split("; ") }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct().toList()

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
