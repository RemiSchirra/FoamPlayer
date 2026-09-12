package com.foamplayer.app.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.size.Size
import com.foamplayer.app.ui.navigation.SharedElementEasing
import com.foamplayer.app.ui.navigation.SharedElementTransitionDurationMs
import com.foamplayer.app.ui.theme.FoamTheme

/**
 * Corner radius of every artwork taking part in the album shared-element
 * transition (list rows here, detail headers in TrackListScreen /
 * CollectionListScreen, search result rows in SearchScreen). Using ONE shape
 * on both ends of the transition means the corners never have to jump between
 * two radii mid-animation.
 */
val AlbumArtShape = RoundedCornerShape(12.dp)

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

/** Padding added around a list row's content, which the press highlight extends beyond. */
val ListRowHighlightPadding = 6.dp

/** Corner radius of a list row's artwork at rest (rounded like the mini player). */
@Composable
internal fun listRowArtCornerRadius(): Dp = (ListRowArtSize / 2) * FoamTheme.shapes.miniPlayerRounding

/**
 * Shared row scaffold for every media list row (TrackListItem,
 * CollectionListItem, ...): a full-width row with a rounded press/long-press
 * highlight, a [ListRowArtSize] artwork box on the leading edge, and a
 * title/subtitle column that [trailingContent] can extend (score badge, drag
 * handle, ...).
 *
 * [artworkModifier] is applied to the artwork box right after its size (e.g.
 * the shared-element modifier, clip and background) and [artworkContent]
 * fills it. [rowModifier] is applied to the row before the highlight clip
 * (e.g. swipe-to-dismiss translation and width measurement).
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
    trailingContent: @Composable RowScope.() -> Unit = {}
) {
    val highlightShape = RoundedCornerShape(listRowArtCornerRadius() + ListRowHighlightPadding)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(rowModifier)
            .clip(highlightShape)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = FoamTheme.colors.textPrimary),
                onLongClick = onLongClick,
                onClick = onClick
            )
            .padding(horizontal = ListRowHighlightPadding, vertical = ListRowHighlightPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(ListRowArtSize)
                .then(artworkModifier),
            contentAlignment = Alignment.Center
        ) {
            artworkContent()
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = title,
                style = FoamTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = titleModifier
            )
            if (subtitle != null) {
                BasicText(
                    text = subtitle,
                    style = FoamTheme.typography.body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = subtitleModifier
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
