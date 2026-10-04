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
 * Case-insensitive [Comparator] over a string [key] (the app's standard
 * ordering for names, albums and artists).
 *
 * Sorting is driven by the per-view [CollectionSortMode] picked in the
 * organisation modal. Every mode has one canonical order; the modal's
 * "Inverse" toggle reverses whatever the mode produced (a random shuffle
 * included), so no mode needs its own reversed variant.
 *
 * NULL disc/track/year/listen stamps share Kotlin's default null-first ordering
 * (mirroring the DAOs' SQLite ASC columns); score/duration/listen modes lead
 * with their "most" value (best/longest/newest first), with never-played tracks
 * kept at the very end.
 */
fun <T> caseInsensitiveComparator(key: (T) -> String): Comparator<T> =
    Comparator { a, b -> key(a).compareTo(key(b), ignoreCase = true) }

// Case-insensitive name ordering everywhere; each mode ends with enough
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
        // CUSTOM keeps the incoming (playlist) order; RECOMMENDED keeps the
        // DAO's ranking (both are order-preserving and short-circuit in
        // [sortedEntriesForDisplay], so this comparator never actually runs);
        // RANDOM is handled by the seeded shuffle in [sortedEntriesForDisplay].
        compareBy { 0 }
}

/**
 * Generic counterpart for the list screens (tracks, albums, artists, genres,
 * years, folders, playlists): [comparatorFor] maps each supported mode to that
 * list's ordering; RANDOM is a seeded shuffle, so Room re-emissions (play-count
 * bumps, score edits) don't reshuffle the list under the user — re-seeding is
 * what produces a new shuffle. RECOMMENDED is order-preserving too: it keeps
 * the incoming (DAO-ranked) order, so a ranked "See all" page shows exactly
 * what its Home row shows.
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

/**
 * [rememberSortedList] specialized to track lists: the RANDOM bookkeeping and
 * the track comparators in one call (CUSTOM/RECOMMENDED/RANDOM never reach the
 * comparator).
 */
@Composable
fun rememberSortedTracks(
    tracks: List<TrackEntity>,
    mode: CollectionSortMode,
    inverted: Boolean
): List<TrackEntity> = rememberSortedList(tracks, mode, inverted, ::trackComparator)

/**
 * Composable sort for any list: draws a fresh RANDOM seed whenever [mode]
 * becomes random (selecting the mode re-shuffles exactly once; list emissions,
 * inverse toggles and recompositions keep the same shuffle) and applies
 * [comparatorFor]'s ordering, reversed when [inverted] (the order-preserving
 * CUSTOM/RECOMMENDED modes bypass the comparator entirely).
 *
 * Returns instantly with the previous (or incoming) list and computes the
 * ordering on [Dispatchers.Default], so opening a screen never stalls the
 * first frame on a full-library sort; the sorted result swaps in a frame or
 * two later. Lists below [AsyncSortThreshold] sort inline — the dispatcher
 * hop would cost more than the sort itself.
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

    // Big lists sort on [Dispatchers.Default]: the first composition returns
    // the incoming (DAO-ordered) list immediately so the screen paints
    // instantly, and the ordering replaces it from the sort coroutine a frame
    // or two later. The state is NOT re-seeded when [items] changes, so Room
    // re-emissions keep showing the last sorted list instead of flashing back
    // to the raw order while the re-sort runs. Small lists (and CUSTOM, which
    // is order-preserving anyway) sort inline exactly as before — a dispatcher
    // hop would cost more than the sort itself.
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
 * The list's displayed rows under a collection hero's live item search (see
 * [CollectionSearchState]): the incoming list unchanged while the bar is
 * closed or the query is blank (stable identity — play/pause flips, drag
 * swaps and active-track changes never re-run the O(n) filter), the
 * query-matched subset while the search is actually narrowing the list.
 *
 * Big lists filter on [Dispatchers.Default], mirroring [rememberSortedList]:
 * the first composition after an open/keystroke returns the previous result
 * immediately — the frame that opens the screen or accepts a keystroke never
 * waits on an O(n) pass — and the matched subset replaces it a frame or two
 * later. Small lists filter inline inside remember, keeping a row tap's
 * captured queue exactly equal to the rows the user sees.
 */
@Composable
fun <T> rememberFilteredList(
    items: List<T>,
    searchState: CollectionSearchState,
    matches: (T) -> Boolean
): List<T> {
    // Tracked snapshot reads: recompose when the bar opens/closes or the
    // query text changes; the item pass itself runs inside remember/effect.
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

/** Sorts up to this many elements directly inside the sort effect (see [rememberSortedList]). */
private const val AsyncSortThreshold = 200

/** Filters up to this many elements inline inside remember (see [rememberFilteredList]). */
private const val AsyncFilterThreshold = 200
