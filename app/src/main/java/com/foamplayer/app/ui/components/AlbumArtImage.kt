package com.foamplayer.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.decode.DataSource
import com.foamplayer.app.ui.theme.FoamTheme
import kotlinx.coroutines.launch

/** Duration of the fade-in a freshly decoded album art gets over its placeholder. */
private const val AlbumArtFadeInDurationMs = 200

/**
 * Album artwork with a music-note placeholder and a fade-in for real loads.
 *
 * Coil emits [AsyncImagePainter.State.Loading] for EVERY request -- memory-cache
 * hits included -- before delivering the result (RealImageLoader calls the
 * target's onStart unconditionally), so the fade is gated on the RESULT's data
 * source rather than on Loading: a [DataSource.MEMORY_CACHE] result snaps back
 * to full alpha synchronously inside the same composition pass, so shared
 * element flights (row -> album header) and revisited list rows draw the image
 * on their very first frame with no placeholder flash and no fade. Only a
 * genuine load (disk / embedded art decode) shows the note and fades the
 * decoded image in over 200ms when it arrives.
 *
 * The note sits BEHIND the image and is removed on success, so the fade runs
 * cleanly over the artwork surface; on error the note stays visible. Every
 * state is keyed on the model so recycled lazy-list rows reset for their new
 * item instead of inheriting the previous item's fade state.
 */
@Composable
fun AlbumArtImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholderSize: Dp = 20.dp
) {
    val alphaState = remember(model) { mutableFloatStateOf(1f) }
    var loading by remember(model) { mutableStateOf(false) }
    var showPlaceholder by remember(model) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (showPlaceholder) {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = FoamTheme.colors.textSecondary,
                modifier = Modifier.size(placeholderSize)
            )
        }
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = alphaState.floatValue },
            contentScale = contentScale,
            onState = { state ->
                when (state) {
                    is AsyncImagePainter.State.Loading -> {
                        // A request is in flight. Coil ALSO emits this for
                        // memory-cache hits; the Success branch undoes these
                        // changes synchronously (before the first frame) there.
                        loading = true
                        showPlaceholder = true
                        alphaState.floatValue = 0f
                    }
                    is AsyncImagePainter.State.Success -> {
                        showPlaceholder = false
                        if (loading && state.result.dataSource != DataSource.MEMORY_CACHE) {
                            // Freshly decoded: fade in over the artwork surface.
                            scope.launch {
                                val fade = Animatable(initialValue = 0f)
                                fade.animateTo(
                                    targetValue = 1f,
                                    animationSpec = tween(
                                        durationMillis = AlbumArtFadeInDurationMs,
                                        easing = EaseOut
                                    )
                                ) {
                                    alphaState.floatValue = value
                                }
                            }
                        } else {
                            // Memory-cache hit: draw at full alpha immediately,
                            // so the image is on the very first drawn frame.
                            alphaState.floatValue = 1f
                        }
                    }
                    else -> Unit
                }
            }
        )
    }
}
