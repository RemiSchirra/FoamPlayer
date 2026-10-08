package com.foamplayer.app.ui.screens.equalizer

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** dB range shown by the response graphs (grid lines go to ±12). */
private const val GAIN_RANGE_DB = 15f
private const val NODE_HIT_RADIUS_PX = 44f

/** Width of one graphic EQ fader column; the strip content spans the current band count of these. */
internal val BAND_SPACING = 44.dp

/** Max distance from a band's dot that still starts a gain drag (vs sliding the strip). */
private val DOT_GRAB_RADIUS = 32.dp

/** Filters without a meaningful gain (cut filters): their nodes only move in x. */
internal val FilterType.gainAdjustable: Boolean
    get() = this != FilterType.LOW_PASS && this != FilterType.HIGH_PASS

/**
 * The interactive parametric EQ graph (Poweramp-style): draggable colored nodes
 * (x = frequency, y = gain), tap to select. Each node's individual response is
 * drawn faintly in its own color under the combined curve, matching the mini
 * map's per-node curves.
 *
 * Drags run against the [nodes] working list owned by the caller so the curve
 * follows the finger every frame without emitting DSP state; the engine is
 * committed once on release through [onNodeCommit].
 */
@Composable
fun EqualizerGraph(
    state: DspState,
    nodes: List<ParametricNode>,
    enabled: Boolean,
    selectedNodeId: Long?,
    onNodeSelect: (Long?) -> Unit,
    onNodesChange: (List<ParametricNode>) -> Unit,
    onNodeCommit: (ParametricNode) -> Unit,
    modifier: Modifier = Modifier
) {
    var dragNodeId by remember { mutableStateOf<Long?>(null) }

    val curve = remember(state, nodes) {
        DspMath.combinedResponse(
            state.copy(parametricEq = state.parametricEq.copy(nodes = nodes))
        )
    }

    val accent = FoamTheme.accentColor
    val gridColor = FoamTheme.colors.borderOverlay
    val labelColor = FoamTheme.colors.textSecondary
    val surfaceColor = FoamTheme.colors.surface
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 9.sp, color = labelColor)

    // Static grid labels measured once outside the draw block: text measurement is
    // expensive, and the graph redraws every frame of a node drag — re-measuring
    // these in the Canvas each frame multiplied the drag cost for nothing.
    val dbLabelLayouts = remember(labelStyle, textMeasurer) {
        listOf(-12, -6, 0, 6, 12).associateWith { db ->
            textMeasurer.measure(if (db > 0) "+${db}dB" else "${db}dB", labelStyle)
        }
    }
    val freqLabelLayouts = remember(labelStyle, textMeasurer) {
        GRID_FREQUENCIES.associateWith { freq ->
            textMeasurer.measure(frequencyLabel(freq), labelStyle)
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(230.dp)
            .pointerInput(enabled) {
                detectDragGestures(
                    onDragStart = { start ->
                        if (!enabled) return@detectDragGestures
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val hit = nodes
                            .filter { (start - nodePosition(it, w, h)).getDistance() <= NODE_HIT_RADIUS_PX }
                            .minByOrNull { (start - nodePosition(it, w, h)).getDistance() }
                        dragNodeId = hit?.id
                        if (hit != null) onNodeSelect(hit.id)
                    },
                    onDrag = { change, _ ->
                        if (!enabled) return@detectDragGestures
                        change.consume()
                        val id = dragNodeId ?: return@detectDragGestures
                        val node = nodes.firstOrNull { it.id == id } ?: return@detectDragGestures
                        val w = size.width.toFloat()
                        val freq = frequencyAtX(change.position.x.coerceIn(0f, w), w)
                        val gain = gainAtY(
                            change.position.y.coerceIn(0f, size.height.toFloat()),
                            size.height.toFloat()
                        )
                        onNodesChange(
                            nodes.map {
                                if (it.id == id) {
                                    it.copy(
                                        frequencyHz = freq,
                                        gainDb = if (it.type.gainAdjustable) gain else it.gainDb
                                    )
                                } else it
                            }
                        )
                    },
                    onDragEnd = {
                        val id = dragNodeId
                        val node = nodes.firstOrNull { it.id == id }
                        if (id != null && node != null) onNodeCommit(node)
                        dragNodeId = null
                    },
                    onDragCancel = { dragNodeId = null }
                )
            }
            .pointerInput(enabled) {
                detectTapGestures(
                    onTap = { position ->
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val hit = nodes
                            .filter { (position - nodePosition(it, w, h)).getDistance() <= NODE_HIT_RADIUS_PX }
                            .minByOrNull { (position - nodePosition(it, w, h)).getDistance() }
                        onNodeSelect(hit?.id)
                    }
                )
            }
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
                textLayoutResult = dbLabelLayouts.getValue(db),
                topLeft = Offset(4.dp.toPx(), (y - 6.dp.toPx()).coerceAtLeast(0f))
            )
        }
        GRID_FREQUENCIES.forEach { freq ->
            val x = xForFrequency(freq.toFloat(), w)
            val measured = freqLabelLayouts.getValue(freq)
            drawText(
                textLayoutResult = measured,
                topLeft = Offset(
                    (x - 8.dp.toPx()).coerceAtMost(w - measured.size.width.toFloat() - 2.dp.toPx()),
                    h - 12.dp.toPx()
                )
            )
        }

        // Per-node individual responses, in each node's color.
        nodes.forEach { node ->
            drawCurve(
                points = DspMath.nodeResponse(node),
                color = Color(node.colorArgb).copy(alpha = 0.35f),
                strokeWidth = 1.5.dp.toPx()
            )
        }

        // Combined curve + soft fill under it (down to the 0 dB line).
        drawCurve(
            points = curve,
            color = accent,
            strokeWidth = 3.dp.toPx(),
            fill = true
        )

        drawParametricNodes(
            nodes = nodes,
            enabled = enabled,
            selectedId = selectedNodeId,
            dragId = dragNodeId,
            surface = surfaceColor,
            textMeasurer = textMeasurer,
            labelStyle = labelStyle
        )
    }
}

private fun DrawScope.drawParametricNodes(
    nodes: List<ParametricNode>,
    enabled: Boolean,
    selectedId: Long?,
    dragId: Long?,
    surface: Color,
    textMeasurer: TextMeasurer,
    labelStyle: TextStyle
) {
    nodes.forEach { node ->
        val pos = nodePosition(node, size.width, size.height)
        val nodeColor = Color(node.colorArgb)
        val isSelected = node.id == selectedId
        val isDragging = node.id == dragId

        drawCircle(
            color = if (enabled) nodeColor else surface,
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
            style = labelStyle.copy(color = nodeColor)
        )
    }
}

// -------------------------------------------------------------- graphic strip

/**
 * The 21-band graphic EQ as a horizontally scrollable strip — one thin track
 * line per band, an accent line from the 0 dB axis up to each dot, and a
 * draggable dot for the band's gain (no curve, no grid: the mini map carries
 * the response overview). Band centers are spaced evenly on the log-frequency
 * axis (each band sits at index/20 of 20 Hz..20 kHz), the same axis the mini
 * map's viewport overlay uses.
 *
 * One unified gesture handler decides per gesture: a drag starting near a dot
 * changes that band's value; any other drag slides the strip (with a small
 * momentum on release). Gains stay local while a dot is dragged and are
 * committed once on release; [scrollState] is hoisted so the mini map overlay
 * can drive the scroll as well.
 */
@Composable
fun GraphicEqStrip(
    gains: List<Float>,
    enabled: Boolean,
    scrollState: ScrollState,
    onPreview: (index: Int, gainDb: Float) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
    onViewportWidthChanged: (Int) -> Unit = {}
) {
    val accent = FoamTheme.accentColor
    val trackColor = FoamTheme.colors.surfaceVariant
    val labelColor = FoamTheme.colors.textSecondary
    val disabledDotColor = FoamTheme.colors.surface
    val textMeasurer = rememberTextMeasurer()
    val valueStyle = TextStyle(fontSize = 8.sp, color = accent)
    val labelStyle = TextStyle(fontSize = 8.sp, color = labelColor)

    // Live proxies: the gesture detector is created once, so it reads the current
    // values through these instead of stale captures (a dot moved by a previous
    // drag must be grabbable at its new position).
    val latestGains by rememberUpdatedState(gains)
    val latestOnPreview by rememberUpdatedState(onPreview)
    val latestOnCommit by rememberUpdatedState(onCommit)
    val latestScroll by rememberUpdatedState(scrollState)
    val scope = rememberCoroutineScope()

    // Local working state: the dragged band's gain follows the finger here, the
    // screen's committed list only sees the per-frame preview and one commit.
    var dragBandIndex by remember { mutableIntStateOf(-1) }
    var dragGain by remember { mutableFloatStateOf(0f) }
    var scrollDragging by remember { mutableStateOf(false) }
    var velocityScroll by remember { mutableFloatStateOf(0f) }
    var lastDragTimeMs by remember { mutableLongStateOf(0L) }

    val displayGains = if (dragBandIndex >= 0) {
        gains.mapIndexed { index, gain -> if (index == dragBandIndex) dragGain else gain }
    } else {
        gains
    }

    val contentWidth = BAND_SPACING * gains.size.coerceAtLeast(1)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(230.dp)
            .onSizeChanged { onViewportWidthChanged(it.width) }
            // The offset mechanics (and maxValue) come from the scroll modifier;
            // its own gesture handling is disabled because the unified handler
            // below drives the scroll for non-dot drags.
            .horizontalScroll(scrollState, enabled = false)
    ) {
        Column(
            modifier = Modifier
                .width(contentWidth)
                .fillMaxHeight()
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp)
                    .pointerInput(enabled) {
                        detectDragGestures(
                            onDragStart = { start ->
                                velocityScroll = 0f
                                lastDragTimeMs = 0L
                                if (enabled) {
                                    val h = size.height.toFloat()
                                    val spacingPx = BAND_SPACING.toPx()
                                    val grabRadius = DOT_GRAB_RADIUS.toPx()
                                    var best = -1
                                    var bestDist = grabRadius
                                    latestGains.forEachIndexed { index, gain ->
                                        val dx = start.x - spacingPx * (index + 0.5f)
                                        val dy = start.y - yForGain(gain, h)
                                        val d = sqrt(dx * dx + dy * dy)
                                        if (d <= bestDist) {
                                            bestDist = d
                                            best = index
                                        }
                                    }
                                    if (best >= 0) {
                                        dragBandIndex = best
                                        dragGain = latestGains[best]
                                    }
                                }
                                if (dragBandIndex < 0) scrollDragging = true
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val index = dragBandIndex
                                if (index >= 0) {
                                    val dbPerPx = (GAIN_RANGE_DB * 2f) / size.height
                                    dragGain = (dragGain - dragAmount.y * dbPerPx)
                                        .coerceIn(
                                            GraphicEqSettings.MIN_GAIN_DB,
                                            GraphicEqSettings.MAX_GAIN_DB
                                        )
                                    latestOnPreview(index, dragGain)
                                } else if (scrollDragging) {
                                    // Finger left (dx < 0) reveals the right side.
                                    val delta = -dragAmount.x
                                    val nowMs = change.uptimeMillis
                                    if (lastDragTimeMs != 0L) {
                                        val dtMs = (nowMs - lastDragTimeMs).coerceAtLeast(1L)
                                        velocityScroll = velocityScroll * 0.7f +
                                                (delta / dtMs * 1000f) * 0.3f
                                    }
                                    lastDragTimeMs = nowMs
                                    latestScroll?.dispatchRawDelta(delta)
                                }
                            },
                            onDragEnd = {
                                val index = dragBandIndex
                                if (index >= 0) {
                                    latestOnCommit()
                                } else if (scrollDragging && abs(velocityScroll) > 300f) {
                                    val velocity = velocityScroll
                                    scope.launch {
                                        latestScroll?.animateScrollBy(
                                            velocity * 0.3f,
                                            tween(durationMillis = 350, easing = LinearOutSlowInEasing)
                                        )
                                    }
                                }
                                dragBandIndex = -1
                                scrollDragging = false
                                lastDragTimeMs = 0L
                            },
                            onDragCancel = {
                                if (dragBandIndex >= 0) latestOnCommit()
                                dragBandIndex = -1
                                scrollDragging = false
                                lastDragTimeMs = 0L
                            }
                        )
                    }
            ) {
                val h = size.height
                val spacingPx = BAND_SPACING.toPx()
                val centerY = yForGain(0f, h)
                val trackWidth = 2.dp.toPx()
                val fillWidth = 3.5.dp.toPx()

                displayGains.forEachIndexed { index, gain ->
                    val x = spacingPx * (index + 0.5f)

                    // Track: a thin vertical line.
                    drawLine(
                        color = trackColor,
                        start = Offset(x, 0f),
                        end = Offset(x, h),
                        strokeWidth = trackWidth,
                        cap = StrokeCap.Round
                    )

                    // Accent line from the 0 dB axis up to the dot.
                    val dotY = yForGain(gain, h)
                    if (abs(dotY - centerY) > 1f) {
                        drawLine(
                            color = accent.copy(alpha = if (enabled) 0.9f else 0.3f),
                            start = Offset(x, centerY),
                            end = Offset(x, dotY),
                            strokeWidth = fillWidth,
                            cap = StrokeCap.Round
                        )
                    }

                    // Dot thumb.
                    val isDragging = index == dragBandIndex
                    val dotRadius = (if (isDragging) 8.5f else 6.5f).dp.toPx()
                    drawCircle(
                        color = if (enabled) accent else disabledDotColor,
                        radius = dotRadius,
                        center = Offset(x, dotY)
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = if (enabled) 0.85f else 0.3f),
                        radius = dotRadius,
                        center = Offset(x, dotY),
                        style = Stroke(width = 1.5.dp.toPx())
                    )

                    if (isDragging) {
                        drawText(
                            textMeasurer = textMeasurer,
                            text = "%+d".format(gain.roundToInt()),
                            topLeft = Offset(
                                (x - 10.dp.toPx()).coerceIn(0f, (size.width - 16.dp.toPx()).coerceAtLeast(0f)),
                                (dotY - 22.dp.toPx()).coerceAtLeast(0f)
                            ),
                            style = valueStyle
                        )
                    }
                }
            }

            // Frequency labels under the tracks.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp)
            ) {
                List(displayGains.size) { index ->
                    Box(
                        modifier = Modifier
                            .width(BAND_SPACING)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.foundation.text.BasicText(
                            text = frequencyLabel(
                                GraphicEqSettings.bandFrequency(index, displayGains.size).roundToInt()
                            ),
                            style = labelStyle
                        )
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------- mini map

/**
 * Compact frequency-response overview of the active EQ section only — never both
 * at once. In parametric mode it draws the per-node curves in the node's own
 * color plus their combined curve in the accent color; every non-neutral fixed
 * tone control is additionally drawn as its own separated curve in [toneCurves]
 * (bass red, mid amber, treble blue) wherever the active section is drawn, so
 * the tone knobs' contribution stays visible even with both EQ sections off. In
 * graphic mode (with [showViewport]) it draws the graphic section's response and
 * a translucent white overlay marking the section of the 20 Hz..20 kHz axis
 * currently visible in the scrollable fader strip. Dragging the map slides the
 * strip; a tap centers the viewport.
 */
@Composable
fun EqMiniMap(
    combinedCurve: List<Float>,
    nodes: List<ParametricNode>,
    active: Boolean,
    modifier: Modifier = Modifier,
    toneCurves: List<Pair<DspMath.ToneBand, List<Float>>> = emptyList(),
    showViewport: Boolean = false,
    scrollState: ScrollState? = null,
    stripViewportWidthPx: Int = 0,
    combinedCurveBandCount: Int = GraphicEqSettings.DEFAULT_BAND_COUNT
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val latestScroll by rememberUpdatedState(scrollState)
    val latestViewportWidth by rememberUpdatedState(stripViewportWidthPx)
    val latestBandCount by rememberUpdatedState(combinedCurveBandCount)

    val interactive = showViewport && scrollState != null
    val accent = FoamTheme.accentColor
    val dimColor = FoamTheme.colors.textSecondary.copy(alpha = 0.5f)
    val referenceColor = FoamTheme.colors.borderOverlay
    val viewportOverlayColor = if (FoamTheme.isDark) Color.White else Color.Black

    Canvas(
        modifier = modifier
            .then(
                if (interactive) {
                    Modifier
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { position ->
                                    val scroll = latestScroll ?: return@detectTapGestures
                                    val span = viewportSpanPx(density, latestBandCount)
                                    if (span <= 0f) return@detectTapGestures
                                    val spacingPx = with(density) { BAND_SPACING.toPx() }
                                    val target = (position.x / size.width) * span -
                                            latestViewportWidth / 2f - spacingPx / 2f
                                    scope.launch {
                                        scroll.scrollTo(target.roundToInt().coerceIn(0, scroll.maxValue))
                                    }
                                }
                            )
                        }
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onHorizontalDrag = { change, dragAmount ->
                                    change.consume()
                                    val scroll = latestScroll ?: return@detectHorizontalDragGestures
                                    val span = viewportSpanPx(density, latestBandCount)
                                    if (span <= 0f) return@detectHorizontalDragGestures
                                    val delta = dragAmount * span / size.width
                                    scope.launch { scroll.scrollBy(delta) }
                                }
                            )
                        }
                } else {
                    Modifier
                }
            )
    ) {
        val w = size.width
        val h = size.height
        if (combinedCurve.size < 2) return@Canvas

        val lineColor = if (active) accent else dimColor

        // Separated per-tone curves (bass / mid / treble), under everything.
        toneCurves.forEach { (band, curve) ->
            drawCurve(
                points = curve,
                color = toneBandColor(band).copy(alpha = if (active) 0.9f else 0.4f),
                strokeWidth = 1.5.dp.toPx()
            )
        }

        drawCurve(
            points = combinedCurve,
            color = lineColor,
            strokeWidth = 2.dp.toPx(),
            fill = true,
            fillHeight = h / 2f
        )

        // One curve per parametric node, in the node's color.
        nodes.forEach { node ->
            drawCurve(
                points = DspMath.nodeResponse(node),
                color = Color(node.colorArgb).copy(alpha = if (active) 0.95f else 0.4f),
                strokeWidth = 1.5.dp.toPx()
            )
        }

        drawLine(
            color = referenceColor,
            start = Offset(0f, h / 2f),
            end = Offset(w, h / 2f),
            strokeWidth = 1f
        )

        // Translucent viewport overlay over the visible EQ section: white on
        // dark surfaces, black on light ones.
        val viewportWidth = latestViewportWidth
        if (interactive && viewportWidth > 0) {
            val scroll = latestScroll ?: return@Canvas
            val spacingPx = with(density) { BAND_SPACING.toPx() }
            val span = spacingPx * (combinedCurveBandCount - 1)
            if (span <= 0f) return@Canvas

            val rawLeft = (scroll.value + spacingPx / 2f) / span * w
            val rawRight = rawLeft + viewportWidth / span * w
            val left = rawLeft.coerceIn(0f, w)
            val right = rawRight.coerceIn(0f, w)
            if (right - left < 2f) return@Canvas

            drawRoundRect(
                color = viewportOverlayColor.copy(alpha = 0.16f),
                topLeft = Offset(left, 1.dp.toPx()),
                size = Size(right - left, h - 2.dp.toPx()),
                cornerRadius = CornerRadius(3.dp.toPx())
            )
            drawRoundRect(
                color = viewportOverlayColor.copy(alpha = 0.55f),
                topLeft = Offset(left, 1.dp.toPx()),
                size = Size(right - left, h - 2.dp.toPx()),
                cornerRadius = CornerRadius(3.dp.toPx()),
                style = Stroke(width = 1.dp.toPx())
            )
        }
    }
}

/**
 * Color of a tone band's separated mini-map curve: warm red for bass, amber for
 * mid, light blue for treble — the same hues the parametric node palette uses,
 * so all secondary curves read with one visual language.
 */
private fun toneBandColor(band: DspMath.ToneBand): Color = when (band) {
    DspMath.ToneBand.BASS -> Color(0xFFE57373)
    DspMath.ToneBand.MID -> Color(0xFFFFD54F)
    DspMath.ToneBand.TREBLE -> Color(0xFF4FC3F7)
}

/** Visible response span of the strip in px: (bandCount - 1) fader gaps. */
private fun viewportSpanPx(density: androidx.compose.ui.unit.Density, bandCount: Int): Float =
    with(density) { BAND_SPACING.toPx() } * (bandCount - 1).coerceAtLeast(1)

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

private val GRID_FREQUENCIES = listOf(20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000)

internal fun frequencyLabel(hz: Int): String = when {
    hz >= 9995 -> "${(hz / 1000f).roundToInt()}k"
    hz >= 1000 -> {
        val k = (hz / 100f).roundToInt() / 10f
        if (k % 1f == 0f) "${k.roundToInt()}k" else "%.1fk".format(k)
    }
    else -> "$hz"
}

// ---------------------------------------------------------------------- draw

private fun DrawScope.drawCurve(
    points: List<Float>,
    color: Color,
    strokeWidth: Float,
    fill: Boolean = false,
    horizontalPadding: Float = 0f,
    fillHeight: Float = Float.NaN
) {
    if (points.size < 2) return
    val w = size.width
    val h = size.height
    val usable = (w - 2 * horizontalPadding).coerceAtLeast(1f)

    val path = Path()
    points.forEachIndexed { index, db ->
        val x = horizontalPadding + index.toFloat() / (points.size - 1) * usable
        val y = yForGain(db, h)
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }

    if (fill) {
        val baseline = if (fillHeight.isNaN()) yForGain(0f, h) else fillHeight
        val fillPath = Path().apply {
            addPath(path)
            lineTo(horizontalPadding + usable, baseline)
            lineTo(horizontalPadding, baseline)
            close()
        }
        drawPath(
            fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(color.copy(alpha = 0.22f), color.copy(alpha = 0.02f)),
                startY = 0f,
                endY = h
            )
        )
    }
    drawPath(path, color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round))
}
