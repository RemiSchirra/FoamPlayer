package com.foamplayer.app.ui.components

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamMotion
import com.foamplayer.app.ui.theme.FoamTheme
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/**
 * Single-line horizontal volume bar bound to the music stream volume: a
 * volume icon on the left (muted glyph at 0), a full-width [FoamSlider] and
 * an optional live percentage on the right ([showPercent]). Kept in sync with
 * hardware volume keys through the volume-changed broadcast, with a polling
 * fallback for devices that don't send it. Half-step key presses are inferred
 * from the broadcast sequence. Shared by the equalizer screen and the
 * "Play on" sheet.
 */
@Composable
fun SystemVolumeControl(showPercent: Boolean = true) {
    val context = LocalContext.current
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val maxIndex = remember(audioManager) {
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }
    var index by remember {
        mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }
    var halfStep by remember { mutableStateOf(false) }
    var lastKnownIndex by remember { mutableIntStateOf(index) }
    var lastLocalChangeMs by remember { mutableLongStateOf(0L) }

    fun fractionOf(wholeStep: Int, half: Boolean): Float =
        ((wholeStep - if (half) 0.5f else 0f).coerceAtLeast(0f) / maxIndex).coerceIn(0f, 1f)

    val scope = rememberCoroutineScope()
    val shownFraction = remember { Animatable(fractionOf(index, false)) }
    fun showFromSystem(newFraction: Float) {
        scope.launch {
            shownFraction.animateTo(
                newFraction,
                tween(durationMillis = FoamMotion.Duration.STANDARD, easing = FoamMotion.Easings.STANDARD)
            )
        }
    }

    fun onMusicVolumeBroadcast() {
        if (SystemClock.elapsedRealtime() - lastLocalChangeMs <= LOCAL_CHANGE_ECHO_MS) return
        val actual = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, maxIndex)
        halfStep = when (actual - lastKnownIndex) {
            0 -> !halfStep
            1 -> true
            else -> false
        }
        Log.d(SYSTEM_VOLUME_TAG, "broadcast: $lastKnownIndex -> $actual, half=$halfStep")
        lastKnownIndex = actual
        index = actual
        showFromSystem(fractionOf(actual, halfStep))
    }

    DisposableEffect(audioManager, context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1) != AudioManager.STREAM_MUSIC) return
                onMusicVolumeBroadcast()
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(VOLUME_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    // Fallback for devices that don't send the volume broadcast.
    LaunchedEffect(audioManager) {
        var mismatches = 0
        while (true) {
            delay(SYSTEM_VOLUME_POLL_MS.milliseconds)
            val actual = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, maxIndex)
            val settled = SystemClock.elapsedRealtime() - lastLocalChangeMs > LOCAL_CHANGE_ECHO_MS
            if (actual != index && settled) {
                if (++mismatches >= 2) {
                    index = actual
                    lastKnownIndex = actual
                    halfStep = false
                    showFromSystem(fractionOf(actual, false))
                    mismatches = 0
                }
            } else {
                mismatches = 0
            }
        }
    }

    // Zero (and only zero: a half-step press keeps the bar above 0) is mute.
    val muted = shownFraction.value <= 0f

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
            contentDescription = null,
            tint = FoamTheme.colors.textSecondary,
            modifier = Modifier.size(FoamDimens.iconMedium)
        )
        Spacer(modifier = Modifier.width(FoamDimens.spacingSmall))
        FoamSlider(
            modifier = Modifier.weight(1f),
            value = { shownFraction.value },
            onValueChange = { raw ->
                val fraction = raw.coerceIn(0f, 1f)
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    shownFraction.snapTo(fraction)
                }
                val newIndex = (fraction * maxIndex).roundToInt().coerceIn(0, maxIndex)
                if (newIndex != index || halfStep) {
                    index = newIndex
                    halfStep = false
                    lastKnownIndex = newIndex
                    lastLocalChangeMs = SystemClock.elapsedRealtime()
                    try {
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newIndex, 0)
                    } catch (_: SecurityException) {
                        // Refused by volume policy; the poll restores the real value.
                    }
                }
            }
        )
        if (showPercent) {
            Spacer(modifier = Modifier.width(FoamDimens.spacingSmall))
            BasicText(
                text = formatPercent((shownFraction.value * 100f).roundToInt().toFloat()),
                style = FoamTheme.typography.monospace
            )
        }
    }
}

private fun formatPercent(percent: Float): String = "%.0f%%".format(percent)

private const val SYSTEM_VOLUME_POLL_MS = 250L
private const val SYSTEM_VOLUME_TAG = "SystemVolume"
private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"

private const val LOCAL_CHANGE_ECHO_MS = 300L
