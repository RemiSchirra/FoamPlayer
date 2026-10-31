package com.foamplayer.app.ui.screens.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.foamplayer.app.R
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import com.foamplayer.app.ui.components.AlbumArtRequestSize
import com.foamplayer.app.ui.components.NoAlbumArtPlaceholder
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Rotation speed: 33⅓ RPM — one revolution every 1.8 s. */
private const val VinylRevolutionsPerMinute = 33.333f
private const val VinylDegreesPerNanosecond =
    (360f * VinylRevolutionsPerMinute / 60f / 1_000_000_000f)

/**
 * Single-emission target change at or below this is continuous movement (timer ticks,
 * the animated track-change glide); anything larger is a jump (track flip, non-animated
 * seek) and swings the arm along a tween instead of teleporting.
 */
private const val TonearmJumpThreshold = 0.05f

/** Tween duration for a full 0f..1f jump, in ms; scaled to the jump size. */
private const val TonearmSwingMsPerUnit = 250f

/** The circular art label, as a fraction of the disc diameter. */
private const val VinylLabelFraction = 0.4f

/** Opacity of the blurred album-art tint spread across the record surface. */
private const val DiscArtAlpha = 0.2f

/** Blur radius of the album-art tint on the record surface. */
private val DiscArtBlurRadius = 24.dp

/*
 * Tonearm geometry, in fractions of the (square) art box: the rotation pivot is the
 * counterweight disc, fixed in the top-right corner wedge clearly OFF the disc
 * (the disc fills the whole box, so only the corners are outside it). The arm is a
 * fixed-length rod rotating around that pivot; [ArmOuterAngle] lands the stylus on
 * the outer groove at the lower-right of the disc (progress 0), [ArmInnerAngle]
 * brings it to the label edge (progress 1). Angles are measured in DEGREES from
 * straight down, negative = leaning left as it descends, and converted to radians
 * before sin/cos. A short rear stub extends behind the pivot, opposite the stylus.
 */
private const val TonearmPivotXFraction = 0.95f
private const val TonearmPivotYFraction = 0.12f
private const val TonearmArmLengthFraction = 0.56f
private const val TonearmTailFraction = 0.06f
private const val ArmOuterAngle = -1.5
private const val ArmInnerAngle = -26.3

private val DiscColorCenter = Color(0xFF242424)
private val DiscColorEdge = Color(0xFF0C0C0C)
private val GrooveColor = Color(0x14FFFFFF)
private val RimColor = Color(0x2EFFFFFF)
private val SpindleColor = Color(0xCC000000)
private val TonearmColor = Color(0xFFB7BCC5)
private val TonearmEdgeColor = Color(0xFFE8ECF2)
private val TonearmDarkColor = Color(0xFF2E3138)
private val TonearmShaftWidth = 4.dp
private val TonearmHeadshellWidth = 10.dp
private val TonearmHeadshellLength = 14.dp
private val TonearmPivotDiscRadius = 8.dp
private val TonearmCantileverWidth = 1.5.dp

/**
 * Vinyl-mode rendering of one track: a spinning record whose label is the track art
 * cropped to a circle.
 *
 * The record rotates only while playback is active; the angle freezes (does not
 * reset) while paused and resumes from where it stopped. The player-level tonearm
 * is drawn separately by [TonearmOverlay] so pages can slide beneath it.
 */
@Composable
internal fun VinylDisc(
    albumArtPath: String?,
    isPlaying: () -> Boolean,
    modifier: Modifier = Modifier
) {
    val playing = isPlaying()
    val context = LocalContext.current
    val artRequest = remember(albumArtPath) {
        albumArtPath?.let { path ->
            ImageRequest.Builder(context)
                .data(EmbeddedAlbumArt(path.toUri()))
                .size(AlbumArtRequestSize)
                .crossfade(false)
                .build()
        }
    }

    // Frame-accumulated rotation: advances with real frame deltas while playing and
    // simply stops advancing when paused.
    var rotation by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        var previousFrame = 0L
        while (true) {
            withFrameNanos { frameTime ->
                if (previousFrame != 0L) {
                    rotation =
                        (rotation + (frameTime - previousFrame) * VinylDegreesPerNanosecond) % 360f
                }
                previousFrame = frameTime
            }
        }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        // Rotating layer: the disc surface and the art label spin together (concentric
        // grooves don't visibly move, but the label and the specular sheen do).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = rotation },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawDiscSurface()
            }
            // A blurred, translucent copy of the art tints the whole record. It is
            // decorative, so it stays out of the accessibility tree.
            if (artRequest != null) {
                AsyncImage(
                    model = artRequest,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .blur(
                            radius = DiscArtBlurRadius,
                            edgeTreatment = BlurredEdgeTreatment.Unbounded
                        )
                        .graphicsLayer { alpha = DiscArtAlpha },
                    contentScale = ContentScale.Crop
                )
            }
            // Grooves and rim sit above the tint so the vinyl texture stays crisp.
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawDiscGrooves()
            }
            // The label: the track art cropped to a circle, centered on the record.
            Box(
                modifier = Modifier
                    .fillMaxSize(VinylLabelFraction)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (artRequest != null) {
                    AsyncImage(
                        model = artRequest,
                        contentDescription = stringResource(R.string.album_art),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    // Same music-note placeholder as every other art surface.
                    NoAlbumArtPlaceholder()
                }
                // The spindle hole sits on top of the label, at the record's center.
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawSpindle()
                }
            }
        }
    }
}

/**
 * The player-level tonearm overlay: a single arm shared by every vinyl page, drawn
 * on top of the pager so records slide beneath it during swipes. Its angle tracks
 * the seek bar's visual progress through the same tween policy as the seek bar
 * itself: while the finger is down on the seek bar ([isDragging]), while the track
 * pager is mid-swipe ([isSwiping]) and for continuous movement (timer ticks, the
 * animated track-change tween) the arm follows 1:1 with zero lag, while a jump
 * (track flip, non-animated seek) swings the arm along a short tween instead of
 * teleporting it there.
 */
@Composable
internal fun TonearmOverlay(
    progress: () -> Float,
    isDragging: () -> Boolean = { false },
    isSwiping: () -> Boolean = { false },
    modifier: Modifier = Modifier
) {
    val armProgress = remember { Animatable(0f) }
    val currentTarget by rememberUpdatedState(progress)
    val currentIsDragging by rememberUpdatedState(isDragging)
    val currentIsSwiping by rememberUpdatedState(isSwiping)

    LaunchedEffect(Unit) {
        var lastSeenTarget = armProgress.value
        snapshotFlow { Triple(currentIsDragging(), currentIsSwiping(), currentTarget()) }
            .collectLatest { (dragging, swiping, target) ->
                val jump = abs(target - lastSeenTarget)
                lastSeenTarget = target
                if (dragging || swiping) {
                    armProgress.snapTo(target)
                } else if (jump > TonearmJumpThreshold) {
                    armProgress.animateTo(
                        target,
                        animationSpec = tween(
                            durationMillis =
                                (abs(target - armProgress.value) * TonearmSwingMsPerUnit).toInt(),
                            easing = FastOutSlowInEasing
                        )
                    )
                } else if (!armProgress.isRunning) {
                    armProgress.snapTo(target)
                }
            }
    }

    Canvas(modifier = modifier) {
        drawTonearm(armProgress.value)
    }
}

/**
 * The record surface: a near-black disc with a subtle radial sheen. The blurred art
 * tint is drawn over this base, then [drawDiscGrooves] adds the texture on top.
 */
private fun DrawScope.drawDiscSurface() {
    val center = Offset(size.width / 2f, size.height / 2f)
    val discRadius = size.minDimension / 2f

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(DiscColorCenter, DiscColorEdge),
            center = center,
            radius = discRadius
        ),
        radius = discRadius,
        center = center
    )
}

/**
 * Faint concentric grooves between the label edge and the rim, plus a thin rim
 * highlight.
 */
private fun DrawScope.drawDiscGrooves() {
    val center = Offset(size.width / 2f, size.height / 2f)
    val discRadius = size.minDimension / 2f
    val labelRadius = discRadius * VinylLabelFraction

    // Grooves: a handful of faint concentric rings selling the vinyl material.
    val grooveCount = 8
    for (i in 1..grooveCount) {
        val fraction = i.toFloat() / (grooveCount + 1)
        drawCircle(
            color = GrooveColor,
            radius = labelRadius + (discRadius - labelRadius) * fraction,
            center = center,
            style = Stroke(width = 1.dp.toPx())
        )
    }

    // Rim highlight.
    drawCircle(
        color = RimColor,
        radius = discRadius - 0.5.dp.toPx(),
        center = center,
        style = Stroke(width = 1.dp.toPx())
    )
}

/** The spindle hole at the record's center, drawn over the label art. */
private fun DrawScope.drawSpindle() {
    val center = Offset(size.width / 2f, size.height / 2f)
    drawCircle(color = SpindleColor, radius = 3.dp.toPx(), center = center)
    drawCircle(
        color = RimColor,
        radius = 4.5.dp.toPx(),
        center = center,
        style = Stroke(width = 1.dp.toPx())
    )
}

/**
 * The tonearm: a fixed-length rod rotating around a fixed counterweight-disc pivot in
 * the top-right corner wedge (off the disc), with a compact dark cartridge at the
 * stylus end. The angle sweeps from the outer groove at the lower-right of the disc
 * (progress 0) to the label edge (progress 1).
 */
private fun DrawScope.drawTonearm(progress: Float) {
    // The rotation pivot: the counterweight disc, fixed in the top-right corner wedge.
    val pivot = Offset(
        size.width * TonearmPivotXFraction,
        size.height * TonearmPivotYFraction
    )
    val angleDeg =
        ArmOuterAngle + (ArmInnerAngle - ArmOuterAngle) * progress.coerceIn(0f, 1f).toDouble()
    val angle = Math.toRadians(angleDeg)
    // Arm direction: 0° points straight down, negative angles lean the rod left as
    // it descends so the stylus lands on the lower-right of the record.
    val direction = Offset(sin(angle).toFloat(), cos(angle).toFloat())
    val armLength = size.minDimension * TonearmArmLengthFraction
    val stylus = pivot + direction * armLength

    // Rear stub behind the pivot, opposite the stylus end.
    val tail = pivot - direction * (size.minDimension * TonearmTailFraction)

    // Rod from the tail stub, through the pivot disc, down to the cartridge.
    drawLine(
        color = TonearmColor,
        start = tail,
        end = stylus,
        strokeWidth = TonearmShaftWidth.toPx(),
        cap = StrokeCap.Butt
    )

    // Counterweight/pivot disc at the rotation pivot, drawn over the rod.
    drawCircle(
        color = TonearmColor,
        radius = TonearmPivotDiscRadius.toPx(),
        center = pivot
    )
    drawCircle(
        color = TonearmDarkColor,
        radius = TonearmPivotDiscRadius.toPx() * 0.35f,
        center = pivot
    )

    // Compact dark cartridge at the stylus end.
    val headshellAngle = Math.toRadians(18.0)
    val headDirection = Offset(
        (direction.x * cos(headshellAngle) - direction.y * sin(headshellAngle)).toFloat(),
        (direction.x * sin(headshellAngle) + direction.y * cos(headshellAngle)).toFloat()
    )
    val headLength = TonearmHeadshellLength.toPx()
    val headStart = stylus - headDirection * (headLength * 0.5f)
    val headEnd = stylus + headDirection * (headLength * 0.5f)
    drawLine(
        color = TonearmDarkColor,
        start = headStart,
        end = headEnd,
        strokeWidth = TonearmHeadshellWidth.toPx(),
        cap = StrokeCap.Round
    )

    // Thin bright cantilever from the cartridge nose.
    val cantileverEnd = headEnd + headDirection * (4.dp.toPx())
    drawLine(
        color = TonearmEdgeColor,
        start = headEnd,
        end = cantileverEnd,
        strokeWidth = TonearmCantileverWidth.toPx(),
        cap = StrokeCap.Round
    )
}
