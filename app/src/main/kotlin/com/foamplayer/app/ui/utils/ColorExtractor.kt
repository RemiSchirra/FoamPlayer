package com.foamplayer.app.ui.utils

import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.core.net.toUri

/**
 * Extracts the accent color for [path]. Embedded audio art is read out of the
 * file itself; set [isEmbedded] to false for plain image URIs (playlist
 * covers), which Coil loads directly.
 */
suspend fun extractPaletteColor(context: android.content.Context, path: String, isEmbedded: Boolean = true): Color? {
    return withContext(Dispatchers.IO) {
        val data = if (isEmbedded) EmbeddedAlbumArt(path.toUri()) else path
        val request = ImageRequest.Builder(context)
            .data(data)
            .allowHardware(false)
            // Palette downsamples internally anyway; a small decode avoids a huge
            // duplicate entry in Coil's memory cache.
            .size(PALETTE_DECODE_SIZE)
            // Coil's memory-cache key ignores the decode size, so this small
            // decode must not occupy the slot the UI's 480px art requests use:
            // a cached 96px bitmap would fail the UI request's size check and
            // force a re-decode (a one-frame blank, visible mid shared-element
            // transition).
            .memoryCacheKey("palette:$path")
            .build()

        val result = context.imageLoader.execute(request)
        if (result is SuccessResult) {
            val bitmap = (result.drawable as BitmapDrawable).bitmap
            val palette = Palette.from(bitmap).generate()

            val swatch = palette.vibrantSwatch
                ?: palette.lightVibrantSwatch
                ?: palette.mutedSwatch
                ?: palette.dominantSwatch

            swatch?.let { s ->
                val hsl = FloatArray(3)
                ColorUtils.colorToHSL(s.rgb, hsl)

                if (hsl[1] < 0.1f) {
                    // Grayscale: push toward a bright neutral.
                    hsl[1] = 0f
                    hsl[2] = 0.9f
                } else {
                    // Ensure the color pops enough to act as an accent.
                    if (hsl[2] < 0.4f) hsl[2] = 0.5f
                    if (hsl[1] < 0.2f) hsl[1] = 0.4f
                }

                Color(ColorUtils.HSLToColor(hsl))
            }
        } else null
    }
}

private const val PALETTE_DECODE_SIZE = 96
