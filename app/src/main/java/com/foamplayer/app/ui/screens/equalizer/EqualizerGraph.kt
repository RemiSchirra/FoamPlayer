package com.foamplayer.app.ui.screens.equalizer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foamplayer.app.audio.dsp.DspMath
import com.foamplayer.app.audio.dsp.DspState
import com.foamplayer.app.audio.dsp.FilterType
import com.foamplayer.app.audio.dsp.GraphicEqSettings
import com.foamplayer.app.audio.dsp.ParametricNode
import com.foamplayer.app.ui.theme.FoamTheme
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** dB range shown by the response graphs (grid lines go to ±12). */
private const val GAIN_RANGE_DB = 15f
private const val NODE_HIT_RADIUS_PX = 44f

/** Filters without a meaningful gain (cut filters): their nodes only move in x. */
internal val FilterType.gainAdjustable: Boolean
    get() = this != FilterType.LOW_PASS && this != FilterType.HIGH_PASS

/**
 * The main interactive EQ graph (Poweramp-style):
 *  - Graphic mode: one draggable handle per band, vertical drag sets its gain.
 *  - Parametric mode: draggable nodes (x = frequency, y = gain), tap to select.
 *
 * Drags run against local working copies so the curve follows the finger every
 * frame without emitting DSP state; the engine is committed once on release. The
 * working copies are single remembered cells synced from the engine via effects —
 * recreating them (remember(key)) would restart the pointerInput mid-gesture.
 */
@Composable
fun EqualizerGraph(
    state: DspState,
    section: DspMath.Section,
    selectedNodeId: Long?,
    onNodeSelect: (Long?) -> Unit,
    onGraphicGainsCommit: (List<Float>) -> Unit,
    onNodeCommit: (ParametricNode) -> Unit,
    modifier: Modifier = Modifier
) {
    var workingGains by remember { mutableStateOf(state.graphicEq.gainsDb) }
    var workingNodes by remember { mutableStateOf(state.parametricEq.nodes) }

    // Re-sync when the engine state changes externally (preset applied, node
    // added/removed, section toggled). Rare, so a mid-drag override is acceptable.
    LaunchedEffect(state.graphicEq.gainsDb) { workingGains = state.graphicEq.gainsDb }
    LaunchedEffect(state.parametricEq.nodes) { workingNodes = state.parametricEq.nodes }

    var dragBandIndex by remember { mutableStateOf(-1) }
    var dragNodeId by remember { mutableStateOf<Long?>(null) }

    val curve = remember(section, state, workingGains, workingNodes) {
        DspMath.combinedResponse(
            state.copy(
                graphicEq = state.graphicEq.copy(gainsDb = workingGains),
                parametricEq = state.parametricEq.copy(nodes = workingNodes)
            )
        )
    }

    val accent = FoamTheme.accentColor
    val gridColor = FoamTheme.colors.borderOverlay
    val labelColor = FoamTheme.colors.textSecondary
    val surfaceColor = FoamTheme.colors.surface
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 9.sp, color = labelColor)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(230.dp)
            .then(
                when (section) {
                    DspMath.Section.GRAPHIC -> Modifier
                        .pointerInput(state.graphicEq.enabled) {
                            detectDragGestures(
                                onDragStart = { start ->
                                    if (state.graphicEq.enabled) {
                                        val bandWidth = size.width.toFloat() / GraphicEqSettings.BAND_COUNT
                                        dragBandIndex = (start.x / bandWidth).toInt()
                                            .coerceIn(0, GraphicEqSettings.BAND_COUNT - 1)
                                    }
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val index = dragBandIndex
                                    if (index >= 0) {
                                        val dbPerPixel = (GAIN_RANGE_DB * 2f) / size.height
                                        val current = workingGains.getOrElse(index) { 0f }
                                        val updated = (current - dragAmount.y * dbPerPixel)
                                            .coerceIn(GraphicEqSettings.MIN_GAIN_DB, GraphicEqSettings.MAX_GAIN_DB)
                                        workingGains = workingGains
                                            .toMutableList()
                                            .also { it[index] = updated }
                                    }
                                },
                                onDragEnd = {
                                    val index = dragBandIndex
                                    if (index >= 0) onGraphicGainsCommit(workingGains)
                                    dragBandIndex = -1
                                },
                                onDragCancel = { dragBandIndex = -1 }
                            )
                        }
                    DspMath.Section.PARAMETRIC -> Modifier
                        .pointerInput(state.parametricEq.enabled) {
                            detectDragGestures(
                                onDragStart = { start ->
                                    if (!state.parametricEq.enabled) return@detectDragGestures
                                    val w = size.width.toFloat()
                                    val h = size.height.toFloat()
                                    val hit = workingNodes
                                        .filter { (start - nodePosition(it, w, h)).getDistance() <= NODE_HIT_RADIUS_PX }
                                        .minByOrNull { (start - nodePosition(it, w, h)).getDistance() }
                                    dragNodeId = hit?.id
                                    if (hit != null) onNodeSelect(hit.id)
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val id = dragNodeId ?: return@detectDragGestures
                                    val node = workingNodes.firstOrNull { it.id == id }
                                        ?: return@detectDragGestures
                                    val w = size.width.toFloat()
                                    val freq = frequencyAtX(
                                        change.position.x.coerceIn(0f, w), w
                                    )
                                    val gain = gainAtY(
                                        change.position.y.coerceIn(0f, size.height.toFloat()),
                                        size.height.toFloat()
                                    )
                                    workingNodes = workingNodes.map {
                                        if (it.id == id) {
                                            it.copy(
                                                frequencyHz = freq,
                                                gainDb = if (it.type.gainAdjustable) gain else it.gainDb
                                            )
                                        } else it
                                    }
                                },
                                onDragEnd = {
                                    val id = dragNodeId
                                    val node = workingNodes.firstOrNull { it.id == id }
                                    if (id != null && node != null) onNodeCommit(node)
                                    dragNodeId = null
                                },
                                onDragCancel = { dragNodeId = null }
                            )
                        }
                        .pointerInput(state.parametricEq.enabled) {
                            detectTapGestures(
                                onTap = { position ->
                                    val w = size.width.toFloat()
                                    val h = size.height.toFloat()
                                    val hit = workingNodes
                                        .filter { (position - nodePosition(it, w, h)).getDistance() <= NODE_HIT_RADIUS_PX }
                                        .minByOrNull { (position - nodePosition(it, w, h)).getDistance() }
                                    onNodeSelect(hit?.id)
                                }
                            )
                        }
                }
            )
    ) {
        val w = size.width
        val h = size.height

        // Grid: frequency decades + dB lines with labels.
        GRID_FREQUENCIES.forEach { freq ->
            val x = xForFrequency(freq.toFloat(), w)
            drawLine(gridColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
        }
        listOf(-12, -6, 0, 6, 12).forEach { db ->
            val y = yForGain(db.toFloat(), h)
            drawLine(
                gridColor,
                Offset(0f, y),
                Offset(w, y),
                strokeWidth = if (db == 0) 1.5f else 1f
            )
            drawText(
                textMeasurer = textMeasurer,
                text = if (db > 0) "+${db}dB" else "${db}dB",
                topLeft = Offset(4.dp.toPx(), (y - 6.dp.toPx()).coerceAtLeast(0f)),
                style = labelStyle
            )
        }
        GRID_FREQUENCIES.forEach { freq ->
            val x = xForFrequency(freq.toFloat(), w)
            drawText(
                textMeasurer = textMeasurer,
                text = frequencyLabel(freq),
                topLeft = Offset((x - 8.dp.toPx()).coerceAtMost(w - 26.dp.toPx()), h - 12.dp.toPx()),
                style = labelStyle
            )
        }

        // Curve + soft fill under it (down to the 0 dB line).
        if (curve.size >= 2) {
            val curvePath = Path()
            curve.forEachIndexed { index, db ->
                val x = index.toFloat() / (curve.size - 1) * w
                val y = yForGain(db, h)
                if (index == 0) curvePath.moveTo(x, y) else curvePath.lineTo(x, y)
            }
            val fillPath = Path().apply {
                addPath(curvePath)
                lineTo(w, yForGain(0f, h))
                lineTo(0f, yForGain(0f, h))
                close()
            }
            drawPath(
                fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(accent.copy(alpha = 0.28f), accent.copy(alpha = 0.02f)),
                    startY = 0f,
                    endY = h
                )
            )
            drawPath(curvePath, accent, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
        }

        when (section) {
            DspMath.Section.GRAPHIC -> drawGraphicHandles(
                gains = workingGains,
                enabled = state.graphicEq.enabled,
                dragIndex = dragBandIndex,
                accent = accent,
                surface = surfaceColor,
                textMeasurer = textMeasurer,
                labelStyle = labelStyle
            )
            DspMath.Section.PARAMETRIC -> drawParametricNodes(
                nodes = workingNodes,
                enabled = state.parametricEq.enabled,
                selectedId = selectedNodeId,
                dragId = dragNodeId,
                accent = accent,
                surface = surfaceColor,
                textMeasurer = textMeasurer,
                labelStyle = labelStyle
            )
        }
    }
}

// ------------------------------------------------------------------- handles

private fun DrawScope.drawGraphicHandles(
    gains: List<Float>,
    enabled: Boolean,
    dragIndex: Int,
    accent: Color,
    surface: Color,
    textMeasurer: TextMeasurer,
    labelStyle: TextStyle
) {
    val bandWidth = size.width / GraphicEqSettings.BAND_COUNT
    gains.forEachIndexed { index, gain ->
        val x = bandWidth * (index + 0.5f)
        val y = yForGain(gain, size.height)
        val centerY = yForGain(0f, size.height)

        drawLine(
            color = if (index == dragIndex) accent else accent.copy(alpha = if (enabled) 0.35f else 0.15f),
            start = Offset(x, centerY),
            end = Offset(x, y),
            strokeWidth = if (index == dragIndex) 3f else 2f
        )

        drawCircle(
            color = if (enabled) accent else surface,
            radius = if (index == dragIndex) 11f else 9f,
            center = Offset(x, y)
        )
        drawCircle(
            color = Color.White.copy(alpha = if (enabled) 0.85f else 0.3f),
            radius = if (index == dragIndex) 11f else 9f,
            center = Offset(x, y),
            style = Stroke(width = 2f)
        )

        drawText(
            textMeasurer = textMeasurer,
            text = "${if (gain >= 0f) "+" else ""}${gain.roundToInt()}",
            topLeft = Offset(
                (x - 8.dp.toPx()).coerceAtLeast(0f),
                (y - 18.dp.toPx()).coerceAtLeast(0f)
            ),
            style = labelStyle
        )
    }
}

private fun DrawScope.drawParametricNodes(
    nodes: List<ParametricNode>,
    enabled: Boolean,
    selectedId: Long?,
    dragId: Long?,
    accent: Color,
    surface: Color,
    textMeasurer: TextMeasurer,
    labelStyle: TextStyle
) {
    nodes.forEach { node ->
        val pos = nodePosition(node, size.width, size.height)
        val isSelected = node.id == selectedId
        val isDragging = node.id == dragId

        drawCircle(
            color = if (enabled) accent else surface,
            radius = when {
                isDragging -> 13f
                isSelected -> 11f
                else -> 9f
            },
            center = pos
        )
        if (isSelected || isDragging) {
            drawCircle(
                color = Color.White.copy(alpha = 0.9f),
                radius = 14f,
                center = pos,
                style = Stroke(width = 2f)
            )
        } else {
            drawCircle(
                color = Color.White.copy(alpha = if (enabled) 0.8f else 0.3f),
                radius = 9f,
                center = pos,
                style = Stroke(width = 2f)
            )
        }

        val label = "${node.type.label.take(2).uppercase()} ${frequencyLabel(node.frequencyHz.roundToInt())}"
        drawText(
            textMeasurer = textMeasurer,
            text = label,
            topLeft = Offset(
                (pos.x - 10.dp.toPx()).coerceIn(0f, (size.width - 30.dp.toPx()).coerceAtLeast(0f)),
                (pos.y - 22.dp.toPx()).coerceAtLeast(0f)
            ),
            style = labelStyle
        )
    }
}

// ------------------------------------------------------------------- mapping

private fun nodePosition(node: ParametricNode, width: Float, height: Float): Offset =
    Offset(
        xForFrequency(node.frequencyHz, width),
        yForGain(node.gainDb, height)
    )

private fun xForFrequency(freq: Float, width: Float): Float {
    val logMin = ln(DspMath.RESPONSE_MIN_HZ)
    val logMax = ln(DspMath.RESPONSE_MAX_HZ)
    val t = (ln(freq.coerceIn(DspMath.RESPONSE_MIN_HZ, DspMath.RESPONSE_MAX_HZ)) - logMin) / (logMax - logMin)
    return t * width
}

private fun frequencyAtX(x: Float, width: Float): Float {
    val logMin = ln(DspMath.RESPONSE_MIN_HZ)
    val logMax = ln(DspMath.RESPONSE_MAX_HZ)
    val t = (x / width.coerceAtLeast(1f)).coerceIn(0f, 1f)
    return 10f.pow(logMin + (logMax - logMin) * t)
}

private fun yForGain(gainDb: Float, height: Float): Float =
    height * (1f - (gainDb.coerceIn(-GAIN_RANGE_DB, GAIN_RANGE_DB) + GAIN_RANGE_DB) / (GAIN_RANGE_DB * 2f))

private fun gainAtY(y: Float, height: Float): Float {
    val t = (y / height.coerceAtLeast(1f)).coerceIn(0f, 1f)
    return (1f - t) * GAIN_RANGE_DB * 2f - GAIN_RANGE_DB
}

private val GRID_FREQUENCIES = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

private fun frequencyLabel(hz: Int): String = if (hz >= 1000) "${hz / 1000}k" else "$hz"

/**
 * Compact non-interactive frequency-response curve, used as the EQ screen's mini
 * map (and reusable wherever a live preview of the whole chain is needed).
 */
@Composable
fun ResponseMiniMap(
    curve: List<Float>,
    modifier: Modifier = Modifier,
    active: Boolean = true
) {
    val accent = FoamTheme.accentColor
    val lineColor = if (active) accent else FoamTheme.colors.textSecondary.copy(alpha = 0.5f)
    val referenceColor = FoamTheme.colors.borderOverlay

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (curve.size < 2) return@Canvas

        val path = Path()
        curve.forEachIndexed { index, db ->
            val x = index.toFloat() / (curve.size - 1) * w
            val y = h * (1f - (db.coerceIn(-GAIN_RANGE_DB, GAIN_RANGE_DB) + GAIN_RANGE_DB) / (GAIN_RANGE_DB * 2f))
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        val fillPath = Path().apply {
            addPath(path)
            lineTo(w, h / 2f)
            lineTo(0f, h / 2f)
            close()
        }
        drawPath(
            fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(lineColor.copy(alpha = 0.22f), lineColor.copy(alpha = 0.0f)),
                startY = 0f,
                endY = h
            )
        )
        drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))

        drawLine(
            color = referenceColor,
            start = Offset(0f, h / 2f),
            end = Offset(w, h / 2f),
            strokeWidth = 1f
        )
    }
}
