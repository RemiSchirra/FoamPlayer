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
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
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

/**
 * Corner radius of an album-art surface of [artSize]: half the artwork's size
 * times the mini-player rounding percentage -- the same convention as the mini
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
 * Corner percentage (0f..50f) of an artwork's own size that a collection
 * flight morphs from/to: the theme's album rounding, or 50f for artwork
 * rendered fully round (artists), so the flight lands exactly on the row's
 * own rest look.
 */
internal const val AlbumArtFlightFullyRoundedPercent = 50f

/**
 * Fixed decode size (px) for album art shared across the app. Coil's
 * memory-cache key includes the requested size, so using the same value for
 * the small list image and the big header image makes switching between them
 * an instant cache hit instead of a fresh decode (which otherwise shows
 * briefly as a blank/transparent frame mid-transition).
 */
val AlbumArtRequestSize = CoilSize(480, 480)

/**
 * Deterministic Coil memory-cache key for the app's shared album art. Every
 * art surface decodes at [AlbumArtRequestSize], so one key serves them all.
 * Setting it explicitly on an art request also lets [AlbumArtImage] resolve
 * the cached bitmap synchronously: Coil's AsyncImagePainter delivers its
 * Success state only asynchronously -- even on a memory-cache hit (its KDoc
 * says so explicitly) -- so a freshly composed AsyncImage draws its loading
 * look for a frame or two before the cached bitmap arrives. For the drawn
 * side of a shared-element flight (a collection card landing a closing hero)
 * those frames are the visible flight: the flying cover popped away
 * mid-animation.
 */
fun albumArtMemoryCacheKey(path: String): String = "album-art:$path"

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

/**
 * Animated flight variant of a percentage-based artwork shape: the corner
 * radius is [restPercent] of the artwork's own size (the theme's album
 * rounding, or 50f -- a full circle -- for fully round artwork like artist
 * images), scaled by [fraction]: 1f = the source row's rounded rest look,
 * 0f = the immersive hero's square look. Used both as a collection flight's
 * [androidx.compose.animation.SharedTransitionScope.OverlayClip] and as the
 * in-place graphicsLayer shape, so the drawn side of a collection
 * shared-element flight keeps its corners riding the transition instead of
 * snapping between the two rest looks. The fraction is read per outline
 * creation (the overlay redraws every flight frame), so no recomposition is
 * involved.
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
 * Flight progress of a collection row that is the drawn side of a shared
 * artwork flight with an immersive collection hero: 0f while the flight
 * departs (the hero's square, faded look), 1f once landed at the row's rest
 * look (Visible). Rides [animatedVisibilityScope]'s own enter/exit transition
 * with the same duration/easing as [AlbumArtBoundsTransform], so the morph
 * lands with the flying bounds; every read is draw-phase only.
 *
 * Gated on the shared transition actually being active: without a matching
 * shared element in flight (plain screen fades, rest) the progress pins to 1f
 * so the row always rests on its exact static look.
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
 * Whether a collection shared-element flight (a nav transition between a
 * collection list and a detail screen's hero) is currently landing on the
 * given scopes' row side -- the artwork shrinking from the hero onto the
 * clicked row.
 *
 * Close flights only (the row side ENTERS while the detail screen closes, its
 * transition targeting [EnterExitState.Visible] without having settled there
 * yet -- the same enum reads [rememberPlayerDismissFlightActive] uses for the
 * player's own close flight). Open flights (the row side exiting toward the
 * hero) deliberately do NOT count: there the whole page fades, which is the
 * established open-flight look, and the row's static shadow hands off
 * through that fade. When either scope is missing the state rests at false,
 * so nothing suppresses anything.
 *
 * Used to step the tagged row's static rest shadow aside for the flight's
 * duration: the shared-element overlay renders ONLY the becoming-visible
 * side's content (the flying artwork, which casts the shadow itself -- see
 * [collectionRowFlightArtwork]'s flight shadow), while the row's own artwork
 * slot is empty in place. The static shadow is drawn by the row scaffold
 * OUTSIDE the sharedElement modifier, so it keeps rendering in place through
 * the whole flight and would otherwise sit under that empty slot from the
 * first flight frame -- the "static shadow at the target position".
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
 * The flight-time look of a collection row that is the DRAWN side of a
 * shared-element flight with an immersive collection hero (i.e. the row the
 * hero morphs into when the detail screen closes). During the transition the
 * shared-element overlay renders ONLY this row's own content, which must then
 * carry the whole morph itself, exactly like [playerFlightArtwork] does for
 * the player's dismiss flight:
 *
 * - the corner radius rides the flight from the hero's square rest look to
 *   the row's own percentage radius ([fullyRounded] rows -- artists -- rest on
 *   a full circle, the same convention as [AlbumArtFlightShape]'s
 *   [AlbumArtFlightFullyRoundedPercent], so it lands exactly on the row's
 *   rest look),
 * - a bottom fade mirrors the hero's (see CollectionHero's immersive artwork)
 *   at full strength at flight start and eases out as the art lands.
 *
 * [shadowElevationPx] lets the flying artwork CAST the row's static drop
 * shadow itself while the flight plays (opted in by
 * [collectionRowFlightArtwork]'s [collectionRowFlightArtwork.castsFlightShadow]):
 * the static shadow -- drawn by the row scaffold OUTSIDE the sharedElement
 * modifier -- is suppressed for the flight (see
 * [rememberCollectionCloseFlightActive]), so without this the landing art
 * would be shadowless until the flight settles. The elevation is provided by
 * the caller so it can gate on the close flight being active and ease with
 * the flight progress; at rest it should read 0 so the static shadow alone
 * casts.
 *
 * At rest the progress reads 1f: the shape is the row's own static rounding
 * and the fade strength is zero -- the rest look is byte-for-byte the
 * un-flight one, which is why the static [clip] is dropped on this path.
 *
 * Every frame is draw-only (graphicsLayer + drawWithContent reads).
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
            // The bottom fade blends with DstIn; isolate it to this layer so
            // it never erases what sits behind the artwork.
            compositingStrategy = if (p < 1f) CompositingStrategy.Offscreen else CompositingStrategy.Auto
            // The flying artwork casts the row's shadow for the whole flight,
            // so the shadow follows the shrinking image instead of sitting at
            // the target position. Reads 0 at rest: the row's own static
            // shadow (suppressed for the flight) casts there.
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
 * Shared artwork modifier of a collection row: the static rest path
 * ([rowSharedArtwork] + [restShape] clip) or, when [immersiveFlightHero] is
 * on (the destination detail screen uses an immersive hero), the flight
 * variant whose clip and fade ride the transition (see
 * [collectionFlightArtwork]) -- so a closing flight departs from the hero's
 * square, faded look and lands on the row's rounded, unfaded one instead of
 * popping straight to the row look on the first frame.
 *
 * [fullyRounded] mirrors the row's fully round (circular) rest shape
 * (artists) into the flight itself: without it the morph would land on the
 * album's percentage rounding and pop to the circle at flight end.
 *
 * [castsFlightShadow] opts the row into the flight's shadow hand-off: while
 * a close flight is landing on this tagged row, the flying artwork casts the
 * row's static drop shadow itself, elevation easing up from the hero's
 * shadowless look to [MediaItemArtElevation] as the art lands -- so the
 * shadow follows the shrinking image instead of sitting at the target
 * position from the first flight frame. The HOST suppresses the scaffold's
 * static shadow for the flight's duration (its own close-flight read, see
 * [rememberCollectionCloseFlightActive]) and resumes it on the settle frame,
 * where the flight layer's elevation lands on exactly [MediaItemArtElevation] --
 * an identical-strength hand-off. Home/search cards that cast no static
 * shadow keep the default (off) and their established shadowless flights.
 *
 * IMMERSIVE heroes are the exception: their flight must keep the animated
 * overlay clip (the flight path below always carries it) because without it
 * the drawn (entering) side's first overlay frame renders transparent -- the
 * flying cover flickers away for a frame at the start of the close flight.
 * That clip also clips the flying artwork's RenderNode shadow, so the
 * immersive path drops the flight shadow and hosts instead ease their static
 * shadow up with the flight progress (no shadow at the hero's shadowless
 * departure, exactly rest strength on the settle frame -- no pop either way).
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
    return this
        .rowSharedArtwork(
            key = key,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            enabled = true,
            // The overlay clip is REQUIRED on this path even though the morph
            // layer below clips to the same animated shape: without it the
            // drawn (entering) side's first overlay frame renders TRANSPARENT
            // -- the flying cover flickers away for a frame at the start of
            // the close flight. (The compact path above gets away with no
            // clip because it carries no morph layer.) The cost: the flying
            // artwork's own RenderNode shadow is clipped away with the
            // content, so hosts carrying a static shadow ease that one up
            // with the flight progress instead of suppressing it for the
            // flight (see PlaylistsScreen's staticArtElevation and
            // CollectionListItem's artworkElevation).
            overlayClip = AlbumArtFlightShape(restPercent) { flightProgress.value },
            boundsTransform = AlbumArtBoundsTransform
        )
        .collectionFlightArtwork(flightProgress, fullyRounded)
}

/**
 * Whether a player-dismiss artwork flight is currently landing on this list's
 * rows -- the artwork shrinking from the full player's hero onto the playing
 * row, driven by the given player-container [animatedVisibilityScope].
 *
 * True from the first frame the dismiss gesture takes over (the press preload
 * has already flipped the flight's target to the row) until the close flight
 * settles at [EnterExitState.Visible]. Both endpoints are snapshot-read enum
 * states, so the value flips exactly twice per flight with no spring overshoot
 * to guard against, and no per-frame composition reads.
 *
 * Used to step the rows' static rest shadows aside for the flight's duration:
 * during the flight the shared-element overlay renders ONLY the becoming
 * visible side's content (the flying artwork, which casts the shadow itself --
 * see [playerFlightArtwork]), while the row's own artwork slot is empty in
 * place. The static shadow is drawn by MediaListRow/MediaGridCell OUTSIDE the
 * sharedElement modifier, so it keeps rendering in place through the whole
 * flight and would otherwise sit under that empty slot from the first gesture
 * frame. The flight artwork lands at exactly the row's rest elevation, so when
 * the flight settles the static shadow resumes on the same frame at an
 * identical strength -- a seamless hand-off.
 *
 * Close flights only (the row side ENTERS while the player closes). Open
 * flights (the player expanding over this page) deliberately do NOT count:
 * there the hero's own elevation ramps up from zero over the departing row
 * and the row's static shadow hands off through the page fade, which is the
 * established open-flight look. Both reads are on the row-side scope's own
 * EnterExitState transition, so they are true only for a close flight whose
 * landing target is this list.
 *
 * When either scope is missing (hosts outside the shared-transition/player
 * plumbing) the state rests at false, so the static shadows render unchanged.
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
            // Close-flight only: the row side ENTERS (targets Visible) while
            // the player closes, and is still in flight until the row-side
            // transition's current state reaches Visible (the transition
            // settles). currentState != Visible is exactly the in-flight
            // window; enum reads cannot overshoot the way a float would.
            animatedVisibilityScope.transition.targetState == EnterExitState.Visible &&
                    animatedVisibilityScope.transition.currentState != EnterExitState.Visible
        }
    }
}

/**
 * Artwork surface for a list row that is the shared-element ENDPOINT of a player
 * transition (i.e. the row the full player's artwork morphs into when the player is
 * dismissed onto its origin collection). During such a flight the shared-element
 * overlay renders the row's own content at the interpolated bounds -- only the
 * becoming-visible side of a [SharedTransitionScope.sharedElement] pair is drawn --
 * so the row's clipping must follow the flight instead of staying at its rest radius,
 * or the art would show near-square corners while the bounds are still hero-sized.
 *
 * The corner radius is lerped from the full player hero's radius down to the row's own
 * percentage radius (half the element's size times the mini-player rounding -- the
 * same convention as [AlbumArtShape], so it lands exactly on the row's rest look for
 * 48dp rows, 72dp rows and grid cells alike). The paused-art shrink is likewise
 * mirrored for the first frames of the flight: the hero it departs from renders
 * scaled while playback is paused (see rememberPausedArtScale -- every endpoint of an
 * album-art shared-element pair must render at the same scale or the art pops the
 * instant the gesture starts), easing back to the row's unscaled rest as it lands.
 *
 * The artwork also carries the full player's drop shadow ITSELF, riding the flight:
 * the hero side of the pair is the becoming-invisible side and is not rendered
 * mid-flight at all (only the becoming-visible side draws), so without this the
 * resting hero's shadow vanishes the instant the gesture takes over. The elevation
 * starts at the hero's full [PlayerDimens.fullPlayerArtElevation] while the
 * interpolated bounds are still hero-sized and eases down to [restArtElevation] --
 * the row's own rest shadow value -- as the art lands, so the static shadow
 * suppressed for the flight's duration (see [rememberPlayerDismissFlightActive])
 * resumes on the same frame at an identical strength.
 *
 * Immersive keeps 0f throughout: the immersive hero casts no shadow (see
 * TrackHeroPager's hero shadowElevation gate) and the flight's artwork alpha fades
 * mid-flight there -- a layer's own alpha cannot fade its RenderNode shadow (see
 * MiniPlayer's flight-elevation note), so a partial-strength shadow is not
 * expressible on this path.
 *
 * With [immersive] on, the overlay art also mirrors the hero's immersive fade
 * (FullPlayerMainContent): the hero artwork is invisible at rest-open -- it lives in
 * the backdrop -- and only fades in over the closing flight's last stretch, so the
 * overlay must start fully transparent and follow the exact same curve instead of
 * popping in at full alpha.
 *
 * Everything is driven by [animatedVisibilityScope]'s own enter/exit transition --
 * which is seeked by the player's [androidx.compose.animation.core.SeekableTransitionState]
 * -- so the radius, scale, elevation and fade track the finger during the interactive
 * swipe and hand off continuously to the settle animation on release. All reads happen
 * inside the graphicsLayer block, keeping every frame of the flight draw-only.
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
        // Rest radius is percentage-based on the element's own (animated) size, so it
        // scales along with the bounds throughout the flight.
        val restRadiusPx = (size.minDimension / 2f) * rounding
        shape = RoundedCornerShape(with(density) { lerp(fullRadiusPx, restRadiusPx, progress).toDp() })
        clip = true
        val paused = pausedArtScale()
        val scale = paused + (1f - paused) * progress
        scaleX = scale
        scaleY = scale
        // The flying artwork casts the hero's shadow itself for the whole flight:
        // full elevation while the bounds are still hero-sized, easing down to the
        // row's own rest elevation as it lands (the static shadow it hands off to --
        // see rememberPlayerDismissFlightActive -- is exactly that value).
        shadowElevation = if (!immersive) {
            lerp(fullElevationPx, restElevationPx, progress)
        } else {
            0f
        }
        // flightProgress runs 0 (flight start, rest-open) -> 1 (landed, rest-closed),
        // i.e. the inverse of the hero's open progress: replicate its immersive fade
        // exactly so the overlay art is as (in)visible as the hero it replaces.
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
 * Drop shadow elevation behind every media item's artwork thumbnail at rest,
 * matching the player art's shadow style. Applied by the shared scaffolds
 * ([MediaListRow], [MediaGridCell]) with the percentage-based [AlbumArtShape];
 * pass 0.dp for artwork that uses a different shape or has no surface.
 */
val MediaItemArtElevation = 4.dp

/**
 * Animated background of a list row's active highlight (the currently playing
 * track, or the collection the queue was started from), fading in/out over
 * 200ms like every other row state change. Always drawn at 40% alpha.
 *
 * The color comes from the row's OWN image ([imagePath], extracted through
 * [AlbumArtPaletteCache]) so each highlighted row reflects its own artwork;
 * rows without an image -- and the brief moment before a color is extracted --
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

/**
 * Corner radius of a list row's artwork at rest (rounded like the mini player).
 */
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
 * fills it. The artwork casts a rest drop shadow at [artworkElevation] with
 * [artworkShape] (circular for fully round artwork, so the shadow follows the
 * rest look instead of peeking out behind its corners); 0.dp
 * [artworkElevation] opts out of the shadow entirely. [rowModifier] is
 * applied to the row before the highlight clip
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
 *
 * The enter/exit fades ride the SAME duration/easing tween as the bounds flight
 * ([AlbumArtBoundsTransform]) instead of sharedBounds' spring-based
 * defaults: with the defaults, both texts stayed at full alpha well into the
 * flight and the departing one lingered past the landing -- reading as an
 * instant scale pop plus a dirty overlapping crossfade. Tweening the fades in
 * lock-step with the bounds makes the two sides swap cleanly at the flight's
 * midpoint.
 *
 * zIndexInOverlay 1f keeps the text above the flying artwork (0f) for both
 * flight directions -- on close flights this side is the becoming-visible one,
 * so its config drives the overlay entry.
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