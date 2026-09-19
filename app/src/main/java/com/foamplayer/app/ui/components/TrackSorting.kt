package com.foamplayer.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.foamplayer.app.constants.CollectionSortMode
import com.foamplayer.app.data.db.entities.TrackEntity
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
    CollectionSortMode.CUSTOM, CollectionSortMode.RANDOM ->
        // CUSTOM keeps the incoming (playlist) order; RANDOM is handled by
        // the seeded shuffle in [sortedEntriesForDisplay].
        compareBy { 0 }
}

/**
 * Generic counterpart for the list screens (tracks, albums, artists, genres,
 * years, folders, playlists): [comparatorFor] maps each supported mode to that
 * list's ordering; RANDOM is a seeded shuffle, so Room re-emissions (play-count
 * bumps, score edits) don't reshuffle the list under the user — re-seeding is
 * what produces a new shuffle.
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
        CollectionSortMode.RANDOM -> shuffled(Random(randomSeed))
            .let { if (inverted) it.asReversed() else it }
        else -> sortedWith(comparatorFor(mode))
            .let { if (inverted) it.asReversed() else it }
    }
}

/**
 * [rememberSortedList] specialized to track lists: the RANDOM bookkeeping and
 * the track comparators in one call (CUSTOM/RANDOM never reach the comparator).
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
 * [comparatorFor]'s ordering, reversed when [inverted].
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
    return remember(items, mode, inverted, randomSeed) {
        items.sortedEntriesForDisplay(mode, inverted, randomSeed, comparatorFor)
    }
}
