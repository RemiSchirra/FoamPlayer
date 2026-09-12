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

                // If the image is grayscale
                if (hsl[1] < 0.1f) {
                    hsl[1] = 0f    // Remove color
                    hsl[2] = 0.9f  // Push the lightness up toward white
                } else {
                    // For actual colors, ensure they pop enough to act as an accent
                    if (hsl[2] < 0.4f) {
                        hsl[2] = 0.5f
                    }
                    if (hsl[1] < 0.2f) {
                        hsl[1] = 0.4f
                    }
                }

                Color(ColorUtils.HSLToColor(hsl))
            }
        } else null
    }
}