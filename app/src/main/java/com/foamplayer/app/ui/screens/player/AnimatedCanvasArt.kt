package com.foamplayer.app.ui.screens.player

import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

private const val CanvasFadeInDurationMs = 450

/**
 * Renders a looping, muted canvas video over the static album art.
 *
 * The static art stays visible underneath until ExoPlayer renders its first
 * frame, then the video fades in on top. Playback pauses (keeping the last
 * painted frame) whenever [playing] goes false, so subview/close transitions
 * fade the video out through the surrounding layers instead of cutting it.
 * The player instance is reused across track changes and released when the
 * composable leaves composition.
 */
@OptIn(UnstableApi::class)
@Composable
fun AnimatedCanvasArt(
    url: String,
    playing: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var hasFirstFrame by remember { mutableStateOf(false) }
    val fadeIn = remember { Animatable(0f) }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    hasFirstFrame = true
                }
            })
        }
    }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    LaunchedEffect(url) {
        hasFirstFrame = false
        fadeIn.snapTo(0f)
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = playing
    }

    LaunchedEffect(playing) {
        player.playWhenReady = playing
    }

    // Only the url path resets the fade to 0: a pause (subview up / player
    // closing) must keep the current alpha so the surrounding layers fade the
    // frozen frame out; resuming just continues toward 1.
    LaunchedEffect(hasFirstFrame, playing) {
        if (hasFirstFrame && playing) {
            fadeIn.animateTo(1f, tween(CanvasFadeInDurationMs))
        }
    }

    Box(modifier = modifier.clipToBounds()) {
        AndroidView(
            factory = { TextureView(it) },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = fadeIn.value
                    // Canvas videos are square; cover-fit them into a w x h
                    // region with ContentScale.Crop parity: stretch the
                    // shorter axis, clip the overflow, pivot centered.
                    val w = size.width
                    val h = size.height
                    if (w > 0f && h > 0f) {
                        if (w > h) scaleY = w / h else scaleX = h / w
                    }
                },
            update = { view -> player.setVideoTextureView(view) }
        )
    }
}
