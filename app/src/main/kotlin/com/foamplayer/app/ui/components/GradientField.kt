package com.foamplayer.app.ui.components

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.foamplayer.app.data.settings.AppSettings
import com.foamplayer.app.data.settings.GradientStop
import kotlin.math.roundToInt

/**
 * True 2D color field for the gradient background: every point is a
 * distance-weighted blend (Shepard interpolation) of all stops' colors, so
 * each anchor paints its own neighborhood instead of slicing the surface into
 * vertical bands.
 *
 * [x] and [y] are normalized 0..1 surface coordinates ([GradientStop.offset]
 * drives Y, [GradientStop.x] the free horizontal placement).
 */
fun gradientColorAt(stops: List<GradientStop>, x: Float, y: Float): Int {
    if (stops.isEmpty()) return AppSettings.DefaultGradientStops.first().argb
    var r = 0f
    var g = 0f
    var b = 0f
    var weightSum = 0f
    for (stop in stops) {
        val dx = x - stop.x
        val dy = y - stop.offset
        // Inverse-square falloff; the epsilon keeps the anchor's own color
        // dominant right at the dot and the math finite.
        val weight = 1f / (dx * dx + dy * dy + 0.0008f)
        val argb = stop.argb
        r += ((argb shr 16) and 0xFF) * weight
        g += ((argb shr 8) and 0xFF) * weight
        b += (argb and 0xFF) * weight
        weightSum += weight
    }
    val ir = (r / weightSum).roundToInt().coerceIn(0, 255)
    val ig = (g / weightSum).roundToInt().coerceIn(0, 255)
    val ib = (b / weightSum).roundToInt().coerceIn(0, 255)
    return 0xFF000000.toInt() or (ir shl 16) or (ig shl 8) or ib
}

/**
 * Rasterizes the 2D field at a low resolution; callers scale the result up
 * with bilinear filtering, smooth at any size and cheap to rebuild while
 * dragging.
 */
fun buildGradientImage(stops: List<GradientStop>, width: Int, height: Int): ImageBitmap {
    val w = width.coerceAtLeast(1)
    val h = height.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(w * h)
    var index = 0
    for (row in 0 until h) {
        val y = (row + 0.5f) / h
        for (col in 0 until w) {
            pixels[index++] = gradientColorAt(stops, (col + 0.5f) / w, y)
        }
    }
    bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    return bitmap.asImageBitmap()
}
