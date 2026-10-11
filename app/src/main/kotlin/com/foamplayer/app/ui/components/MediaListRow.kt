package com.foamplayer.app.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import coil.size.Size as CoilSize
import com.foamplayer.app.constants.MediaDensity
import com.foamplayer.app.ui.navigation.SharedElementEasing
import com.foamplayer.app.ui.navigation.SharedElementTransitionDurationMs
import com.foamplayer.app.ui.screens.player.immersiveArtFadeSpan
import com.foamplayer.app.ui.screens.player.PlayerDimens
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.theme.LocalAutomaticColorEnabled
import com.foamplayer.app.ui.theme.adaptAccentForTheme
import com.foamplayer.app.ui.utils.AlbumArtPaletteCache
import kotlin.math.roundToInt

/** Corner radius of an album-art surface of [artSize]: half the size times the rounding percentage. */
@Composable
fun albumArtCornerRadius(artSize: Dp): Dp = (artSize / 2) * FoamTheme.shapes.miniPlayerRounding

/**
 * Corner shape of every album-art surface, percentage-based: the radius is
 * always a fraction of the artwork's own size, so 48dp rows, 120dp headers
 * and full-width grid cells round identically, and shared-element artwork
 * scales its corners with the animated bounds.
 */
@Composable
fun AlbumArtShape(): Shape = RoundedCornerShape(
    percent = (FoamTheme.shapes.miniPlayerRounding * 50).roundToInt().coerceIn(0, 50)
)

/**
 * Corner percentage of an artwork's own size that a collection flight morphs
 * from/to for fully round artwork (artists): 50f — a full circle.
 */
internal const val AlbumArtFlightFullyRoundedPercent = 50f

/**
 * Overlay clip of a collection flight whose flying artwork casts its own drop
 * shadow: the animated artwork shape expanded by [marginPx], so the RenderNode
 * shadow — drawn just outside the silhouette — survives the overlay clip. The
 * immersive morph path must keep a clip (without one the drawn side's first
 * overlay frame renders transparent); this variant grows by the margin the
 * shadow needs, nothing more. Returns null when the margin is 0.
 */
private class ExpandedAlbumArtFlightClip(
    private val restPercent: Float,
    private val shapeFraction: () -> Float,
    private val marginPx: () -> Float
) : SharedTransitionScope.OverlayClip {
    private val path = Path()

    override fun getClipPath(
        sharedContentState: SharedTransitionScope.SharedContentState,
        bounds: Rect,
        layoutDirection: LayoutDirection,
        density: Density
    ): Path? {
        val margin = marginPx()
        if (margin <= 0f) return null
        val expanded = bounds.inflate(margin)
        path.reset()
        path.addOutline(
            AlbumArtFlightShape(restPercent) { shapeFraction() }
                .createOutline(expanded.size, layoutDirection, density)
        )
        path.translate(expanded.topLeft)
        return path
    }
}

/**
 * Fixed decode size (px) for album art shared across the app: Coil's memory
 * cache key includes the requested size, so a shared value makes switching
 * between list images and header images an instant cache hit.
 */
val AlbumArtRequestSize = CoilSize(480, 480)

/**
 * Deterministic Coil memory-cache key for the app's shared album art. Setting
 * it explicitly also lets [AlbumArtImage] resolve a cached bitmap
 * synchronously — Coil's AsyncImagePainter delivers Success only
 * asynchronously, and for the drawn side of a shared-element flight those
 * frames are the visible flight.
 */
fun albumArtMemoryCacheKey(path: String): String = "album-art:$path"

/**
 * Bounds transform shared by all artwork shared elements: the same
 * duration/easing as the NavHost's screen fade, so background fades and
 * artwork flights finish on the same frame.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
val AlbumArtBoundsTransform = BoundsTransform { _, _ ->
    tween(durationMillis = SharedElementTransitionDurationMs, easing = SharedElementEasing)
}

/**
 * Animated flight variant of a percentage-based artwork shape: the radius is
 * [restPercent] of the artwork's own size, scaled by [fraction] (1f = the
 * source row's rounded rest look, 0f = the immersive hero's square look).
 * The fraction is read per outline creation, so no recomposition is involved.
 */
internal class AlbumArtFlightShape(
    private val restPercent: Float,
    private val fraction: () -> Float
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val percent = (restPercent * fraction().coerceIn(0f, 1f)).coerceIn(0f, 50f)
        val radius = CornerRadius(size.width * percent / 100f, size.height * percent / 100f)
        return Outline.Rounded(
            RoundRect(
                rect = size.toRect(),
                topLeft = radius,
                topRight = radius,
                bottomRight = radius,
                bottomLeft = radius
            )
        )
    }
}

/**
 * Flight progress of a collection row drawn as the shared side of a flight
 * with an immersive collection hero: 0f while departing (the hero's square,
 * faded look), 1f once landed at the row's rest look. Pinned to 1f when no
 * shared element is in flight, so the row always rests on its static look.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun rememberCollectionFlightProgress(
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope
): State<Float> {
    val flight = animatedVisibilityScope.transition.animateFloat(
        transitionSpec = {
            tween(
                durationMillis = SharedElementTransitionDurationMs,
                easing = SharedElementEasing
            )
        },
        label = "collectionRowFlight"
    ) { state ->
        if (state == EnterExitState.Visible) 1f else 0f
    }
    return remember(sharedTransitionScope, flight) {
        derivedStateOf {
            if (sharedTransitionScope.isTransitionActive) flight.value else 1f
        }
    }
}

/**
 * Whether a collection close flight is currently landing on the given scopes'
 * row side (the artwork shrinking from the hero onto the clicked row). Open
 * flights do not count: there the whole page fades and the row's static shadow
 * hands off through that fade. Rests at false when either scope is missing.
 *
 * Used to step the tagged row's static rest shadow aside for the flight: the
 * overlay renders only the flying artwork (which casts the shadow itself),
 * while the row's own artwork slot is empty in place.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun rememberCollectionCloseFlightActive(
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?
): State<Boolean> {
    if (sharedTransitionScope == null || animatedVisibilityScope == null) {
        val alwaysInactive = remember { mutableStateOf(false) }
        return alwaysInactive
    }
    return remember(animatedVisibilityScope) {
        derivedStateOf {
            animatedVisibilityScope.transition.targetState == EnterExitState.Visible &&
                    animatedVisibilityScope.transition.currentState != EnterExitState.Visible
        }
    }
}

/**
 * Flight-time look of a collection row that is the DRAWN side of a
 * shared-element flight with an immersive collection hero: the corner radius
 * rides the flight from the hero's square look to the row's own radius, and a
 * bottom fade mirrors the hero's, easing out as the art lands.
 *
 * [shadowElevationPx] lets the flying artwork cast the row's drop shadow while
 * the flight plays (the static one is suppressed for the flight); at rest it
 * reads 0. Every frame is draw-only.
 */
@Composable
internal fun Modifier.collectionFlightArtwork(
    progress: State<Float>,
    fullyRounded: Boolean = false,
    shadowElevationPx: () -> Float = { 0f }
): Modifier {
    val restPercent = if (fullyRounded) {
        AlbumArtFlightFullyRoundedPercent
    } else {
        FoamTheme.shapes.miniPlayerRounding * 50f
    }
    return this
        .graphicsLayer {
            val p = progress.value
            shape = AlbumArtFlightShape(restPercent) { p }
            clip = true
            // Isolate the DstIn fade to this layer so it never erases what
            // sits behind the artwork.
            compositingStrategy = if (p < 1f) CompositingStrategy.Offscreen else CompositingStrategy.Auto
            shadowElevation = shadowElevationPx()
        }
        .drawWithContent {
            drawContent()
            val fadeStrength = 1f - progress.value
            if (fadeStrength > 0f) {
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Black,
                        CollectionHeroImmersiveFadeStartFraction to Color.Black,
                        1f to Color.Black.copy(alpha = 1f - fadeStrength)
                    ),
                    blendMode = BlendMode.DstIn
                )
            }
        }
}

/**
 * Shared artwork modifier of a collection row: the static rest path, or — when
 * [immersiveFlightHero] is on — the flight variant whose clip, fade and
 * (optionally) flight shadow ride the transition, so a closing flight departs
 * from the hero's square, faded look and lands on the row's rounded,
 * unfaded one.
 *
 * [fullyRounded] mirrors a circular rest shape (artists) into the flight so it
 * lands on the circle instead of popping to it. [castsFlightShadow] hands the
 * row's static shadow to the flying artwork for the flight's duration (the
 * host suppresses the static one). IMMERSIVE heroes keep the animated overlay
 * clip — without it the drawn side's first overlay frame renders transparent —
 * and use the EXPANDED variant so the shadow survives it.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.collectionRowFlightArtwork(
    key: String,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    enabled: Boolean,
    restShape: Shape,
    immersiveFlightHero: Boolean,
    fullyRounded: Boolean = false,
    castsFlightShadow: Boolean = false
): Modifier {
    if (!enabled || sharedTransitionScope == null || animatedVisibilityScope == null) {
        return this.clip(restShape)
    }
    if (!immersiveFlightHero) {
        if (!castsFlightShadow) {
            return this
                .rowSharedArtwork(
                    key = key,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    enabled = true,
                    overlayClip = restShape,
                    boundsTransform = AlbumArtBoundsTransform
                )
                .clip(restShape)
        }
        val flightProgress = rememberCollectionFlightProgress(
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope
        )
        val closeFlightActive = rememberCollectionCloseFlightActive(
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope
        )
        val restElevationPx = with(LocalDensity.current) { MediaItemArtElevation.toPx() }
        return this
            .rowSharedArtwork(
                key = key,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                enabled = true,
                overlayClip = null,
                boundsTransform = AlbumArtBoundsTransform
            )
            .graphicsLayer {
                shape = restShape
                clip = true
                shadowElevation = if (closeFlightActive.value) {
                    restElevationPx * flightProgress.value
                } else {
                    0f
                }
            }
            .clip(restShape)
    }
    val restPercent = if (fullyRounded) {
        AlbumArtFlightFullyRoundedPercent
    } else {
        FoamTheme.shapes.miniPlayerRounding * 50f
    }
    val flightProgress = rememberCollectionFlightProgress(
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope
    )
    val closeFlightActive = rememberCollectionCloseFlightActive(
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope
    )
    val restElevationPx = with(LocalDensity.current) { MediaItemArtElevation.toPx() }
    val flightShadowElevationPx: () -> Float = {
        if (castsFlightShadow && closeFlightActive.value) {
            restElevationPx * flightProgress.value
        } else {
            0f
        }
    }
    return this
        .rowSharedArtwork(
            key = key,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            enabled = true,
            // Required on this path: without a clip the drawn (entering) side's
            // first overlay frame renders transparent. Expanded so the flying
            // artwork's RenderNode shadow survives the clip.
            overlayClipShape = ExpandedAlbumArtFlightClip(
                restPercent = restPercent,
                shapeFraction = { flightProgress.value },
                marginPx = flightShadowElevationPx
            ),
            boundsTransform = AlbumArtBoundsTransform
        )
        .collectionFlightArtwork(flightProgress, fullyRounded, flightShadowElevationPx)
}

/**
 * Whether a player-dismiss artwork flight is currently landing on this list's
 * rows — the artwork shrinking from the full player's hero onto the playing
 * row. True from the first frame the dismiss gesture takes over until the
 * close flight settles; enum reads cannot overshoot. Used to step the rows'
 * static rest shadows aside for the flight, mirroring
 * [rememberCollectionCloseFlightActive].
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun rememberPlayerDismissFlightActive(
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?
): State<Boolean> {
    if (sharedTransitionScope == null || animatedVisibilityScope == null) {
        val alwaysInactive = remember { mutableStateOf(false) }
        return alwaysInactive
    }
    return remember(animatedVisibilityScope) {
        derivedStateOf {
            animatedVisibilityScope.transition.targetState == EnterExitState.Visible &&
                    animatedVisibilityScope.transition.currentState != EnterExitState.Visible
        }
    }
}

/**
 * Artwork modifier for a list row that is the shared-element ENDPOINT of a
 * player-dismiss flight. During such a flight the overlay renders this row's
 * content at the interpolated bounds, so the row must itself carry the
 * transition look:
 *
 *  - the corner radius lerps from the full player hero's radius down to the
 *    row's own percentage radius,
 *  - the paused-art shrink is mirrored (every endpoint of an album-art pair
 *    must render at the same scale or the art pops at hand-off),
 *  - the artwork casts the hero's drop shadow, easing from the hero's full
 *    elevation down to [restArtElevation] as it lands (the static shadow
 *    suppressed by [rememberPlayerDismissFlightActive] resumes at that value),
 *  - with [immersive] on, the alpha mirrors the hero's immersive fade.
 *
 * Everything is driven by [animatedVisibilityScope]'s enter/exit transition
 * (seeked by the player's SeekableTransitionState), so all values track the
 * finger during the interactive swipe. All reads happen inside the
 * graphicsLayer block: every frame of the flight is draw-only.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.playerFlightArtwork(
    animatedVisibilityScope: AnimatedVisibilityScope,
    fullArtRadius: Dp,
    pausedArtScale: () -> Float,
    immersive: Boolean = false,
    restArtElevation: Dp = MediaItemArtElevation
): Modifier {
    val flightProgress by animatedVisibilityScope.transition.animateFloat(label = "rowArtFlight") { state ->
        if (state == EnterExitState.Visible) 1f else 0f
    }
    val density = LocalDensity.current
    val fullRadiusPx = remember(fullArtRadius, density) { with(density) { fullArtRadius.toPx() } }
    val fullElevationPx =
        remember(density) { with(density) { PlayerDimens.fullPlayerArtElevation.toPx() } }
    val restElevationPx =
        remember(restArtElevation, density) { with(density) { restArtElevation.toPx() } }
    val rounding = FoamTheme.shapes.miniPlayerRounding
    return this.graphicsLayer {
        val progress = flightProgress
        // Rest radius is percentage-based on the animated size, so it scales
        // along with the bounds throughout the flight.
        val restRadiusPx = (size.minDimension / 2f) * rounding
        shape = RoundedCornerShape(with(density) { lerp(fullRadiusPx, restRadiusPx, progress).toDp() })
        clip = true
        val paused = pausedArtScale()
        val scale = paused + (1f - paused) * progress
        scaleX = scale
        scaleY = scale
        shadowElevation = if (!immersive) {
            lerp(fullElevationPx, restElevationPx, progress)
        } else {
            0f
        }
        // progress runs 0 (flight start) -> 1 (landed): replicate the hero's
        // immersive fade so the overlay art is as (in)visible as the hero.
        alpha = if (immersive) {
            1f - EaseOutCubic.transform(((1f - progress) / immersiveArtFadeSpan).coerceIn(0f, 1f))
        } else {
            1f
        }
    }
}

/** Size of the artwork thumbnail at the leading edge of a list row. */
val ListRowArtSize = 48.dp

/**
 * Drop shadow elevation behind every media item's artwork at rest; pass 0.dp
 * for artwork that uses a different shape or has no surface.
 */
val MediaItemArtElevation = 4.dp

/**
 * Animated background of a list row's active highlight (currently playing
 * track, or the collection the queue was started from). The color comes from
 * the row's own image when automatic (dynamic accent) color is enabled, with
 * the app accent as fallback; always drawn at 40% alpha.
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
        else -> (
                extractedColor?.adaptAccentForTheme(FoamTheme.isDark)
                    ?: FoamTheme.accentColor
                ).copy(alpha = 0.4f)
    }

    return animateColorAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 200, easing = EaseOut),
        label = "activeRowHighlight"
    ).value
}

/** Artwork size of the larger RowBig density. */
val ListRowBigArtSize = 72.dp

/** Artwork size of a row at [density] (RowSmall has none). */
fun rowArtSize(density: MediaDensity): Dp = when (density) {
    MediaDensity.ROW_BIG -> ListRowBigArtSize
    else -> ListRowArtSize
}

/** Padding around a list row's content, which the press highlight extends beyond. */
val ListRowHighlightPadding = 6.dp

/** Tighter vertical padding of the compact RowSingle-line rows. */
val RowSmallVerticalPadding = 2.dp

/**
 * Vertical padding around a row's content AND the distance its highlight
 * extends beyond it: the two are the same value so the highlight fills the
 * row's bounds exactly.
 */
@Composable
internal fun rowVerticalPadding(density: MediaDensity): Dp =
    if (density == MediaDensity.ROW_SMALL) RowSmallVerticalPadding else ListRowHighlightPadding

/** Corner radius of a list row's artwork at rest. */
@Composable
internal fun listRowArtCornerRadius(density: MediaDensity = MediaDensity.ROW_NORMAL): Dp =
    albumArtCornerRadius(rowArtSize(density))

/**
 * Shared row scaffold for every media list row (TrackListItem,
 * CollectionListItem, ...): a full-width row with a rounded press/long-press
 * highlight, a leading artwork box and a title/subtitle column that
 * [trailingContent] can extend.
 *
 * [density] picks the layout: RowSmall is a compact single-line title without
 * artwork, RowNormal keeps the classic 48dp art + two-line row, RowBig grows
 * the art to 72dp and shows [tertiaryText] as a third line. [highlightBackground]
 * is drawn behind the content, clipped to the same shape as the press ripple.
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
    artworkElevation: Dp = MediaItemArtElevation,
    artworkShape: Shape = AlbumArtShape(),
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
                    .shadow(elevation = artworkElevation, shape = artworkShape)
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
 * Marks a list row's artwork as the source of a shared-element transition.
 * No-ops when not [enabled] or when the transition scopes are missing.
 * [overlayClipShape] keeps the given OverlayClip applied to every frame;
 * [overlayClip] passes a raw Shape instead; [boundsTransform] overrides the
 * default spring.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.rowSharedArtwork(
    key: String,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    enabled: Boolean = true,
    overlayClip: Shape? = null,
    overlayClipShape: SharedTransitionScope.OverlayClip? = null,
    boundsTransform: BoundsTransform? = null
): Modifier {
    if (!enabled || sharedTransitionScope == null || animatedVisibilityScope == null) return this
    return with(sharedTransitionScope) {
        val state = rememberSharedContentState(key = key)
        val resolvedOverlayClip: SharedTransitionScope.OverlayClip? =
            overlayClipShape ?: overlayClip?.let { OverlayClip(it) }
        when {
            resolvedOverlayClip != null && boundsTransform != null -> sharedElement(
                state,
                animatedVisibilityScope = animatedVisibilityScope,
                boundsTransform = boundsTransform,
                clipInOverlayDuringTransition = resolvedOverlayClip
            )
            resolvedOverlayClip != null -> sharedElement(
                state,
                animatedVisibilityScope = animatedVisibilityScope,
                clipInOverlayDuringTransition = resolvedOverlayClip
            )
            boundsTransform != null -> sharedElement(
                state,
                animatedVisibilityScope = animatedVisibilityScope,
                boundsTransform = boundsTransform
            )
            else -> sharedElement(state, animatedVisibilityScope = animatedVisibilityScope)
        }
    }
}

/**
 * Marks a list row's text as the source of a shared-bounds transition. The
 * enter/exit fades ride the same duration/easing tween as the bounds flight
 * instead of sharedBounds' spring defaults — with the defaults both texts
 * stayed opaque well into the flight, reading as a scale pop plus a dirty
 * crossfade; tweening in lock-step makes the sides swap cleanly at midpoint.
 * zIndexInOverlay 1f keeps the text above the flying artwork (0f).
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
        val fadeSpec = tween<Float>(
            durationMillis = SharedElementTransitionDurationMs,
            easing = SharedElementEasing
        )
        val resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(
            contentScale = ContentScale.FillWidth,
            alignment = Alignment.Center
        )
        if (boundsTransform != null) {
            sharedBounds(
                state,
                animatedVisibilityScope = animatedVisibilityScope,
                enter = fadeIn(fadeSpec),
                exit = fadeOut(fadeSpec),
                resizeMode = resizeMode,
                boundsTransform = boundsTransform,
                zIndexInOverlay = 1f
            )
        } else {
            sharedBounds(
                state,
                animatedVisibilityScope = animatedVisibilityScope,
                enter = fadeIn(fadeSpec),
                exit = fadeOut(fadeSpec),
                resizeMode = resizeMode,
                zIndexInOverlay = 1f
            )
        }
    }
}
