package com.foamplayer.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.foamplayer.app.constants.CollectionSortMode
import com.foamplayer.app.data.db.entities.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Case-insensitive [Comparator] over a string [key] — the app's standard
 * ordering for names, albums and artists. NULL stamps share Kotlin's
 * null-first ordering; score/duration/listen modes lead with their "most"
 * value, keeping never-played tracks at the very end.
 */
fun <T> caseInsensitiveComparator(key: (T) -> String): Comparator<T> =
    Comparator { a, b -> key(a).compareTo(key(b), ignoreCase = true) }

// Case-insensitive ordering everywhere; each mode ends with enough
// tiebreakers to keep equal keys in a stable, sensible order.
private val trackByTitle = caseInsensitiveComparator(TrackEntity::title)
private val trackByAlbum = caseInsensitiveComparator(TrackEntity::album)
private val trackByDiscTrack =
    compareBy<TrackEntity> { it.discNumber }.thenBy { it.trackNumber }

private fun trackComparator(mode: CollectionSortMode): Comparator<TrackEntity> = when (mode) {
    CollectionSortMode.NAME ->
        trackByTitle.then(trackByAlbum).then(trackByDiscTrack)
    CollectionSortMode.TRACK_NUMBER ->
        trackByDiscTrack.then(trackByAlbum).then(trackByTitle)
    CollectionSortMode.ARTIST ->
        caseInsensitiveComparator<TrackEntity> { it.albumArtist ?: it.artist }
            .then(trackByAlbum)
            .then(trackByDiscTrack)
            .then(trackByTitle)
    CollectionSortMode.ALBUM ->
        trackByAlbum.then(trackByDiscTrack).then(trackByTitle)
    CollectionSortMode.YEAR ->
        compareBy<TrackEntity> { it.year ?: Int.MAX_VALUE } // untagged years last
            .then(trackByAlbum)
            .then(trackByDiscTrack)
            .then(trackByTitle)
    CollectionSortMode.SCORE ->
        compareByDescending(TrackEntity::score).then(trackByTitle)
    CollectionSortMode.DURATION ->
        compareByDescending(TrackEntity::duration).then(trackByTitle)
    CollectionSortMode.LISTEN_DATE ->
        compareByDescending<TrackEntity> { it.lastPlayedAt ?: Long.MIN_VALUE } // never-played last
            .then(trackByTitle)
    CollectionSortMode.LISTEN_COUNT ->
        compareByDescending(TrackEntity::playCount).then(trackByTitle)
    CollectionSortMode.CUSTOM, CollectionSortMode.RECOMMENDED, CollectionSortMode.RANDOM ->
        // CUSTOM/RECOMMENDED are order-preserving and short-circuit in
        // [sortedEntriesForDisplay]; RANDOM is a seeded shuffle there.
        compareBy { 0 }
}

/**
 * Generic sorting for the list screens: RANDOM is a seeded shuffle, so Room
 * re-emissions (play-count bumps, score edits) don't reshuffle the list under
 * the user — re-seeding produces a new shuffle. RECOMMENDED is
 * order-preserving too: it keeps the incoming (DAO-ranked) order.
 */
fun <T> List<T>.sortedEntriesForDisplay(
    mode: CollectionSortMode,
    inverted: Boolean,
    randomSeed: Long,
    comparatorFor: (CollectionSortMode) -> Comparator<T>
): List<T> {
    if (isEmpty()) return this
    return when (mode) {
        CollectionSortMode.CUSTOM -> this
        CollectionSortMode.RECOMMENDED -> this
        CollectionSortMode.RANDOM -> shuffled(Random(randomSeed))
            .let { if (inverted) it.asReversed() else it }
        else -> sortedWith(comparatorFor(mode))
            .let { if (inverted) it.asReversed() else it }
    }
}

/** [rememberSortedList] specialized to track lists. */
@Composable
fun rememberSortedTracks(
    tracks: List<TrackEntity>,
    mode: CollectionSortMode,
    inverted: Boolean
): List<TrackEntity> = rememberSortedList(tracks, mode, inverted, ::trackComparator)

/**
 * Composable sort for any list: draws a fresh RANDOM seed whenever [mode]
 * becomes random (list emissions and inverse toggles keep the same shuffle)
 * and applies [comparatorFor]'s ordering, reversed when [inverted].
 *
 * Big lists sort on [Dispatchers.Default]: the first composition returns the
 * incoming list immediately so the screen paints instantly, and the ordering
 * replaces it a frame or two later. Lists below [AsyncSortThreshold] sort
 * inline — the dispatcher hop would cost more than the sort itself.
 */
@Composable
fun <T> rememberSortedList(
    items: List<T>,
    mode: CollectionSortMode,
    inverted: Boolean,
    comparatorFor: (CollectionSortMode) -> Comparator<T>
): List<T> {
    var randomSeed by remember { mutableLongStateOf(Random.nextLong()) }
    LaunchedEffect(mode) {
        if (mode == CollectionSortMode.RANDOM) randomSeed = Random.nextLong()
    }

    val sortsInline = mode == CollectionSortMode.CUSTOM ||
        items.size < AsyncSortThreshold ||
        items.isEmpty()

    // The state is NOT re-seeded when [items] changes, so Room re-emissions
    // keep showing the last sorted list instead of flashing back to raw order.
    if (sortsInline) {
        return remember(items, mode, inverted, randomSeed) {
            items.sortedEntriesForDisplay(mode, inverted, randomSeed, comparatorFor)
        }
    }

    var sortedList by remember { mutableStateOf(items) }
    LaunchedEffect(items, mode, inverted, randomSeed, comparatorFor) {
        sortedList = withContext(Dispatchers.Default) {
            items.sortedEntriesForDisplay(mode, inverted, randomSeed, comparatorFor)
        }
    }
    return sortedList
}

/**
 * The list's displayed rows under a collection hero's live item search
 * ([CollectionSearchState]): the incoming list unchanged while the bar is
 * closed or the query is blank (stable identity — play/pause flips and drag
 * swaps never re-run the O(n) filter), the matched subset otherwise. Big
 * lists filter on [Dispatchers.Default], mirroring [rememberSortedList].
 */
@Composable
fun <T> rememberFilteredList(
    items: List<T>,
    searchState: CollectionSearchState,
    matches: (T) -> Boolean
): List<T> {
    val active = searchState.open && searchState.query.isNotBlank()
    if (!active) return items

    val query = searchState.query
    val latestMatches by rememberUpdatedState(matches)
    if (items.size < AsyncFilterThreshold) {
        return remember(items, query) { items.filter(latestMatches) }
    }

    var filteredList by remember { mutableStateOf(items) }
    LaunchedEffect(items, query) {
        filteredList = withContext(Dispatchers.Default) {
            items.filter(latestMatches)
        }
    }
    return filteredList
}

/** Sorts up to this many elements directly inside the sort effect. */
private const val AsyncSortThreshold = 200

/** Filters up to this many elements inline inside remember. */
private const val AsyncFilterThreshold = 200
