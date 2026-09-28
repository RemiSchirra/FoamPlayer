package com.foamplayer.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.R
import com.foamplayer.app.data.settings.ThemeMode
import com.foamplayer.app.ui.components.FoamBottomModal
import com.foamplayer.app.ui.components.FoamSwitchItem
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.navigation.LocalBottomBarClearance
import com.foamplayer.app.ui.theme.FoamTheme

/**
 * One-tap accent shortcuts under the picker: 12 vibrant, evenly spread hues
 * laid out 6 per row (see [DefaultStopPalette], shared with the gradient
 * tool's picker).
 */
private val AccentShortcutRows: List<List<Int>> = DefaultStopPalette.chunked(6)

/** Side of one palette swatch in the accent card. */
private val PaletteSwatchSize = 36.dp

/**
 * Minimum interval between mid-drag accent persists. Persisting on every
 * pointer frame would flood DataStore with writes and recompose every
 * accent reader at gesture frequency; ~8 Hz reads as a continuous recolor
 * while keeping the disk quiet. The gesture end always persists once more so
 * the final, exact color is never lost to the throttle window.
 */
private const val AccentPersistIntervalMs = 120L

@Composable
fun ColorsSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    // Whole-settings projection: AppSettings is @Immutable, so recomposition only
    // happens when a value this screen actually renders changes. (A previous
    // narrow-projection attempt read the StateFlow's .value inside derivedStateOf,
    // which never invalidates -- StateFlow is not snapshot state -- freezing the
    // screen: the switch stopped reacting and the dropdown label never updated.)
    val settings by viewModel.settings.collectAsState()
    val isAutomaticColorEnabled = settings.automaticColorEnabled
    val accentColorInt = settings.accentColor
    val themeMode = settings.themeMode

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FoamTheme.colors.background)
    ) {
        SettingsHeader(
            title = stringResource(R.string.colors),
            onBack = onBack,
            modifier = Modifier.padding(horizontal = FoamDimens.paddingMedium)
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = FoamDimens.paddingMedium),
            contentPadding = PaddingValues(bottom = LocalBottomBarClearance.current)
        ) {
            item {
                BasicText(text = stringResource(R.string.theme), style = FoamTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                ThemeSelector(
                    currentTheme = themeMode,
                    onThemeSelected = { viewModel.setThemeMode(it) }
                )
                Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))
            }

            item {
                BasicText(text = stringResource(R.string.system_colors), style = FoamTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                        .background(FoamTheme.colors.surface)
                ) {
                    FoamSwitchItem(
                        title = stringResource(R.string.automatic_color),
                        description = "Extract accent color from album art",
                        checked = isAutomaticColorEnabled,
                        onCheckedChange = viewModel::setAutomaticColorEnabled
                    )
                }
                Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))
            }

            if (!isAutomaticColorEnabled) {
                item {
                    BasicText(text = stringResource(R.string.accent_palette), style = FoamTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                            .background(FoamTheme.colors.surface)
                            .padding(FoamDimens.paddingMedium)
                    ) {
                        AccentPickerSection(
                            accentColorInt = accentColorInt,
                            onAccentColorSelected = viewModel::setAccentColor
                        )
                    }
                    Spacer(modifier = Modifier.height(FoamDimens.spacingExtraLarge))
                }
            }
        }
    }
}

/**
 * The accent card: the same HSV picker as the gradient tool's modal
 * ([SaturationValueField] + [HueBar], with the shortcut palette under it),
 * inlined into the card instead of hosted in a sheet.
 *
 * The picker edits local HSV state and the draft recolors the rings AND the
 * live app accent: the draft is persisted at [AccentPersistIntervalMs] during
 * a drag and once more on release, so dragging the square or the hue bar
 * visibly recolors the app while the disk write rate stays bounded. A palette
 * tap seeds the HSV state (the rings jump along) and persists immediately.
 *
 * The HSV state is deliberately NOT keyed on [accentColorInt]: mid-drag
 * persists echo back through the settings flow as a new committed value, and
 * re-seeding on every echo would reset the rings under a moving finger. The
 * seed happens once when the section enters composition (leaving via the
 * Automatic Color toggle or scrolling this LazyColumn item off-screen
 * disposes it, so the next entry re-seeds from the committed accent).
 */
@Composable
private fun AccentPickerSection(
    accentColorInt: Int,
    onAccentColorSelected: (Int) -> Unit
) {
    val seed = remember {
        FloatArray(3).also { android.graphics.Color.colorToHSV(accentColorInt, it) }
    }
    var hue by remember { mutableFloatStateOf(seed[0]) }
    var saturation by remember { mutableFloatStateOf(seed[1]) }
    var value by remember { mutableFloatStateOf(seed[2]) }

    // The live draft, for the palette selection highlight.
    val draftArgb = android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value))

    var lastPersistAtMs by remember { mutableLongStateOf(0L) }
    fun persistDraft(force: Boolean) {
        val now = System.currentTimeMillis()
        if (force || now - lastPersistAtMs >= AccentPersistIntervalMs) {
            lastPersistAtMs = now
            onAccentColorSelected(
                android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value))
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingStandard)) {
        // Drags update local HSV per frame (rings + live accent recolor) and
        // persist the draft on the throttle; release persists the final value.
        SaturationValueField(
            hue = hue,
            saturation = saturation,
            value = value,
            onSaturationValue = { s, v ->
                saturation = s
                value = v
                persistDraft(force = false)
            },
            onEditFinished = { persistDraft(force = true) }
        )
        HueBar(
            hue = hue,
            onHueChange = { hue = it; persistDraft(force = false) },
            onEditFinished = { persistDraft(force = true) }
        )

        // The shortcut palette: one tap commits (and steers the picker rings).
        AccentShortcutRows.forEach { rowColors ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                rowColors.forEach { argb ->
                    Swatch(
                        color = Color(argb),
                        isSelected = argb == draftArgb,
                        onSelect = {
                            val hsv = FloatArray(3)
                            android.graphics.Color.colorToHSV(argb, hsv)
                            hue = hsv[0]
                            saturation = hsv[1]
                            value = hsv[2]
                            onAccentColorSelected(argb)
                        }
                    )
                }
            }
        }
    }
}

/**
 * Dropdown selector for the app's theme mode (dark / light / follow system).
 */
@Composable
private fun ThemeSelector(
    currentTheme: ThemeMode,
    onThemeSelected: (ThemeMode) -> Unit
) {
    var showModal by remember { mutableStateOf(false) }
    val currentLabel = stringResource(
        when (currentTheme) {
            ThemeMode.FOLLOW_SYSTEM -> R.string.theme_follow_system
            ThemeMode.DARK -> R.string.theme_dark
            ThemeMode.LIGHT -> R.string.theme_light
        }
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
            .background(FoamTheme.colors.surface)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = FoamTheme.colors.textPrimary),
                onClick = { showModal = true }
            )
            .padding(FoamDimens.paddingMedium)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText(text = currentLabel, style = FoamTheme.typography.body)
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = FoamTheme.colors.textSecondary
            )
        }
    }

    FoamBottomModal(
        visible = showModal,
        onDismissRequest = { showModal = false }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            BasicText(
                text = stringResource(R.string.theme),
                style = FoamTheme.typography.titleMedium.copy(textAlign = TextAlign.Center),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(FoamDimens.spacingSmall))
            ThemeMode.entries.forEach { mode ->
                val isSelected = mode == currentTheme
                val label = stringResource(
                    when (mode) {
                        ThemeMode.FOLLOW_SYSTEM -> R.string.theme_follow_system
                        ThemeMode.DARK -> R.string.theme_dark
                        ThemeMode.LIGHT -> R.string.theme_light
                    }
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                        // Highlight for the current selection, drawn under the ripple.
                        .background(
                            if (isSelected) FoamTheme.colors.textPrimary.copy(alpha = 0.12f)
                            else Color.Transparent
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(color = FoamTheme.colors.textPrimary),
                            onClick = {
                                onThemeSelected(mode)
                                showModal = false
                            }
                        )
                        .padding(vertical = FoamDimens.paddingMedium),
                    contentAlignment = Alignment.Center
                ) {
                    BasicText(
                        text = label,
                        style = FoamTheme.typography.body.copy(
                            color = FoamTheme.colors.textPrimary,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            textAlign = TextAlign.Center
                        )
                    )
                }
            }
        }
    }
}

/**
 * One selectable palette swatch, its own composable scope: a tap only
 * recomposes the swatches whose selection state actually flips, not the
 * surrounding card / LazyColumn item or the other swatches.
 */
@Composable
private fun Swatch(
    color: Color,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(PaletteSwatchSize)
            .clip(RoundedCornerShape(FoamDimens.buttonRounding))
            .background(color)
            .border(
                width = if ( isSelected) 2.dp else 0.dp,
                color = if (isSelected) FoamTheme.colors.textPrimary else Color.Transparent,
                shape = RoundedCornerShape(FoamDimens.buttonRounding)
            )
            .clickable(onClick = onSelect),
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(FoamTheme.colors.textPrimary)
            )
        }
    }
}
