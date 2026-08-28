package com.foamplayer.app.ui.screens.player

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.foamplayer.app.ui.theme.FoamTheme

/** Background layer for the classic blurred treatment and the immersive art hero. */
@androidx.compose.runtime.Composable
internal fun AlbumArtBackdrop(
    frontArtPath: String?,
    backArtPath: String?,
    crossfadeFraction: Float,
    immersive: Boolean,
    subViewProgress: Float,
    modifier: Modifier = Modifier
) {
    if (immersive) {
        ImmersiveAlbumArtBackdrop(
            frontArtPath = frontArtPath,
            backArtPath = backArtPath,
            crossfadeFraction = crossfadeFraction,
            subViewProgress = subViewProgress,
            modifier = modifier
        )
        return
    }

    Box(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().background(FoamTheme.colors.background))
        Box(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 1f }) {
            if (frontArtPath != null) {
                AsyncImage(
                    model = frontArtPath,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().blur(20.dp),
                    contentScale = ContentScale.Crop
                )
            }
            if (backArtPath != null && backArtPath != frontArtPath) {
                AsyncImage(
                    model = backArtPath,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().blur(20.dp).graphicsLayer { alpha = crossfadeFraction },
                    contentScale = ContentScale.Crop
                )
            }
        }
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.2f)))
        BackdropGradient()
    }
}

/** Edge-to-edge art treatment that dissolves into the blurred background. */
@SuppressLint("UnusedBoxWithConstraintsScope")
@androidx.compose.runtime.Composable
private fun ImmersiveAlbumArtBackdrop(
    frontArtPath: String?,
    backArtPath: String?,
    crossfadeFraction: Float,
    subViewProgress: Float,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val sharpArtHeight = maxHeight * 0.65f

        Box(modifier = Modifier.fillMaxSize().background(FoamTheme.colors.background))
        AlbumArtImagePair(
            frontArtPath = frontArtPath,
            backArtPath = backArtPath,
            crossfadeFraction = crossfadeFraction,
            modifier = Modifier.fillMaxSize().blur(35.dp)
        )
        Box(modifier = Modifier.fillMaxWidth().height(sharpArtHeight)) {
            AlbumArtImagePair(
                frontArtPath = frontArtPath,
                backArtPath = backArtPath,
                crossfadeFraction = crossfadeFraction,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 1f - subViewProgress }
                    .fadeIntoBlur()
            )
        }
        BackdropGradient()
    }
}

@androidx.compose.runtime.Composable
private fun BackdropGradient() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.5f to Color.Transparent,
                    0.75f to Color.Black.copy(alpha = 0.5f),
                    1f to Color.Black.copy(alpha = 1f)
                )
            )
    )
}

/** Fades the sharp top art into the blur below it. */
private fun Modifier.fadeIntoBlur(): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithCache {
            val alphaMask = Brush.verticalGradient(
                0f to Color.White,
                0.7f to Color.White,
                1f to Color.Transparent
            )
            onDrawWithContent {
                drawContent()
                drawRect(alphaMask, blendMode = BlendMode.DstIn)
            }
        }

@androidx.compose.runtime.Composable
private fun AlbumArtImagePair(
    frontArtPath: String?,
    backArtPath: String?,
    crossfadeFraction: Float,
    modifier: Modifier
) {
    Box(modifier = modifier) {
        if (frontArtPath != null) {
            AsyncImage(
                model = frontArtPath,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        if (backArtPath != null && backArtPath != frontArtPath) {
            AsyncImage(
                model = backArtPath,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = crossfadeFraction },
                contentScale = ContentScale.Crop
            )
        }
    }
}
