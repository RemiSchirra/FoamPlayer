package com.foamplayer.app.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.size.Size
import com.foamplayer.app.constants.MediaDensity
import com.foamplayer.app.ui.navigation.SharedElementEasing
import com.foamplayer.app.ui.navigation.SharedElementTransitionDurationMs
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.theme.LocalAutomaticColorEnabled
import com.foamplayer.app.ui.utils.AlbumArtPaletteCache
import kotlin.math.roundToInt

/**
 * Corner radius of an album-art surface of [artSize]: half the artwork's size
 * times the mini-player rounding percentage — the same convention as the mini
 * player and every list row. Use for artwork whose size is known statically;
 * for dynamic sizes (grid cells, animating shared elements) use [AlbumArtShape].
 */
@Composable
fun albumArtCornerRadius(artSize: Dp): Dp = (artSize / 2) * FoamTheme.shapes.miniPlayerRounding

/**
 * Corner shape of every album-art surface, percentage-based: the radius is
 * always [albumArtCornerRadius] of the artwork's OWN size (50% of the side
 * times the rounding percentage), so 48dp list rows, 120dp detail headers and
 * full-width grid cells all round identically relative to their size, and
 * artwork taking part in a shared-element transition scales its corners with
 * the animated bounds instead of jumping between two fixed radii.
 */
@Composable
fun AlbumArtShape(): Shape = RoundedCornerShape(
    percent = (FoamTheme.shapes.miniPlayerRounding * 50).roundToInt().coerceIn(0, 50)
)

/**
 * Fixed decode size (px) for album art shared across the app. Coil's
 * memory-cache key includes the requested size, so using the same value for
 * the small list image and the big header image makes switching between them
 * an instant cache hit instead of a fresh decode (which otherwise shows
 * briefly as a blank/transparent frame mid-transition).
 */
val AlbumArtRequestSize = Size(480, 480)

/**
 * Drives the size/position animation for every shared element in the app's
 * transitions. Uses the SAME duration/easing as the NavHost's screen-level
 * fade (SharedElementTransitionDurationMs/SharedElementEasing) so the
 * background fading in and the artwork moving/resizing finish on the same
 * frame instead of one lagging or overshooting the other, which is what read
 * as a flicker.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
val AlbumArtBoundsTransform = BoundsTransform { _, _ ->
    tween(durationMillis = SharedElementTransitionDurationMs, easing = SharedElementEasing)
}

/** Size of the artwork thumbnail at the leading edge of a list row. */
val ListRowArtSize = 48.dp

/**
 * Animated background of a list row's active highlight (the currently playing
 * track, or the collection the queue was started from), fading in/out over
 * 200ms like every other row state change. Always drawn at 40% alpha.
 *
 * The color comes from the row's OWN image ([imagePath], extracted through
 * [AlbumArtPaletteCache]) so each highlighted row reflects its own artwork;
 * rows without an image — and the brief moment before a color is extracted —
 * fall back to the app accent. With automatic (dynamic accent) color disabled
 * the extracted colors are ignored and the app-defined accent always wins.
 */
@Composable
internal fun activeRowHighlight(
    active: Boolean,
    imagePath: String?,
    isEmbeddedArt: Boolean = true
): Color {
    val automaticColorEnabled = LocalAutomaticColorEnabled.current

    if (active && automaticColorEnabled) {
        val context = LocalContext.current
        LaunchedEffect(imagePath, isEmbeddedArt) {
            AlbumArtPaletteCache.ensure(
                scope = this,
                context = context,
                path = imagePath,
                isEmbeddedArt = isEmbeddedArt
            )
        }
    }

    val extractedColor = if (active && automaticColorEnabled && imagePath != null) {
        AlbumArtPaletteCache.colors[imagePath]
    } else {
        null
    }

    val target = when {
        !active -> Color.Transparent
        !automaticColorEnabled -> FoamTheme.accentColor.copy(alpha = 0.4f)
        else -> (extractedColor ?: FoamTheme.accentColor).copy(alpha = 0.4f)
    }

    return animateColorAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 200, easing = EaseOut),
        label = "activeRowHighlight"
    ).value
}

/** Artwork size of the larger RowBig density. */
val ListRowBigArtSize = 72.dp

/** Artwork size of a row at [density] (RowSmall has no artwork). */
fun rowArtSize(density: MediaDensity): Dp = when (density) {
    MediaDensity.ROW_BIG -> ListRowBigArtSize
    else -> ListRowArtSize
}

/** Padding added around a list row's content, which the press highlight extends beyond. */
val ListRowHighlightPadding = 6.dp

/** Tighter vertical padding of the compact RowSingle-line rows. */
val RowSmallVerticalPadding = 2.dp

/**
 * Vertical padding around a row's content AND the distance its press/active
 * highlight extends beyond the content: the two are the same value so the
 * highlight rectangle always fills the row's bounds exactly. Compact RowSmall
 * rows use tighter padding.
 */
@Composable
internal fun rowVerticalPadding(density: MediaDensity): Dp =
    if (density == MediaDensity.ROW_SMALL) RowSmallVerticalPadding else ListRowHighlightPadding

/** Corner radius of a list row's artwork at rest (rounded like the mini player). */
@Composable
internal fun listRowArtCornerRadius(density: MediaDensity = MediaDensity.ROW_NORMAL): Dp =
    albumArtCornerRadius(rowArtSize(density))

/**
 * Shared row scaffold for every media list row (TrackListItem,
 * CollectionListItem, ...): a full-width row with a rounded press/long-press
 * highlight, a leading artwork box and a title/subtitle column that
 * [trailingContent] can extend (score badge, drag handle, ...).
 *
 * [density] picks the row's layout: RowSmall collapses to a compact
 * single-line title without artwork, RowNormal keeps the classic 48dp art +
 * two-line row, RowBig grows the art to 72dp and shows the optional
 * [tertiaryText] as a third line (tracks pass "duration • format").
 *
 * [artworkModifier] is applied to the artwork box right after its size (e.g.
 * the shared-element modifier, clip and background) and [artworkContent]
 * fills it. [rowModifier] is applied to the row before the highlight clip
 * (e.g. swipe-to-dismiss translation and width measurement).
 * [highlightBackground] is drawn behind the row's content, clipped to the
 * exact same rounded shape as the press ripple (used for the active-track
 * highlight).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MediaListRow(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    artworkModifier: Modifier,
    artworkContent: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    rowModifier: Modifier = Modifier,
    titleModifier: Modifier = Modifier,
    subtitleModifier: Modifier = Modifier,
    density: MediaDensity = MediaDensity.ROW_NORMAL,
    tertiaryText: String? = null,
    highlightBackground: Color = Color.Transparent,
    trailingContent: @Composable RowScope.() -> Unit = {}
) {
    val artSize = rowArtSize(density)
    val artCornerRadius = listRowArtCornerRadius(density)
    val verticalPadding = rowVerticalPadding(density)
    val highlightShape = RoundedCornerShape(artCornerRadius + verticalPadding)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(rowModifier)
            .clip(highlightShape)
            .background(highlightBackground, highlightShape)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = FoamTheme.colors.textPrimary),
                onLongClick = onLongClick,
                onClick = onClick
            )
            .padding(horizontal = ListRowHighlightPadding, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (density != MediaDensity.ROW_SMALL) {
            Box(
                modifier = Modifier
                    .size(artSize)
                    .then(artworkModifier),
                contentAlignment = Alignment.Center
            ) {
                artworkContent()
            }

            Spacer(modifier = Modifier.width(12.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = title,
                style = FoamTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = titleModifier
            )
            if (subtitle != null && density != MediaDensity.ROW_SMALL) {
                BasicText(
                    text = subtitle,
                    style = FoamTheme.typography.body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = subtitleModifier
                )
            }
            if (density == MediaDensity.ROW_BIG && tertiaryText != null) {
                BasicText(
                    text = tertiaryText,
                    style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        trailingContent()
    }
}

/**
 * Marks a list row's artwork as the source of a shared-element transition
 * ([SharedTransitionScope.sharedElement]). No-ops when not [enabled] or when
 * the transition scopes are missing (i.e. the row is not part of a
 * transition). [overlayClip] keeps the given shape applied to every frame of
 * the transition; [boundsTransform] overrides the default spring.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.rowSharedArtwork(
    key: String,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    enabled: Boolean = true,
    overlayClip: Shape? = null,
    boundsTransform: BoundsTransform? = null
): Modifier {
    if (!enabled || sharedTransitionScope == null || animatedVisibilityScope == null) return this
    return with(sharedTransitionScope) {
        val state = rememberSharedContentState(key = key)
        if (overlayClip != null && boundsTransform != null) {
            sharedElement(
                state,
                animatedVisibilityScope = animatedVisibilityScope,
                boundsTransform = boundsTransform,
                clipInOverlayDuringTransition = OverlayClip(overlayClip)
            )
        } else if (overlayClip != null) {
            sharedElement(
                state,
                animatedVisibilityScope = animatedVisibilityScope,
                clipInOverlayDuringTransition = OverlayClip(overlayClip)
            )
        } else if (boundsTransform != null) {
            sharedElement(
                state,
                animatedVisibilityScope = animatedVisibilityScope,
                boundsTransform = boundsTransform
            )
        } else {
            sharedElement(state, animatedVisibilityScope = animatedVisibilityScope)
        }
    }
}

/**
 * Marks a list row's text as the source of a shared-bounds transition
 * ([SharedTransitionScope.sharedBounds]). No-ops when not [enabled] or when
 * the transition scopes are missing.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.rowSharedText(
    key: String,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    enabled: Boolean = true,
    boundsTransform: BoundsTransform? = null
): Modifier {
    if (!enabled || sharedTransitionScope == null || animatedVisibilityScope == null) return this
    return with(sharedTransitionScope) {
        val state = rememberSharedContentState(key = key)
        if (boundsTransform != null) {
            sharedBounds(
                state,
                animatedVisibilityScope = animatedVisibilityScope,
                boundsTransform = boundsTransform
            )
        } else {
            sharedBounds(state, animatedVisibilityScope = animatedVisibilityScope)
        }
    }
}
