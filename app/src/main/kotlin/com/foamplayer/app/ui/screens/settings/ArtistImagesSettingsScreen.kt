package com.foamplayer.app.ui.screens.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.R
import com.foamplayer.app.data.scanner.ArtistImageScanState
import com.foamplayer.app.ui.components.FoamCard
import com.foamplayer.app.ui.components.FoamSliderStateItem
import com.foamplayer.app.ui.components.FoamSwitchItem
import com.foamplayer.app.ui.navigation.LocalBottomBarClearance
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamMotion
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.theme.foamSurfaceVariant
import kotlin.math.roundToInt

/**
 * Artist images settings, one concern per card: the resolution master switch,
 * the "artists to cache" LRU bound, the "resolve all artists" bulk scan (its
 * controls gated by the switch) and the cache management row.
 */
@Composable
fun ArtistImagesSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val artistImageScan by viewModel.artistImageScan.collectAsState()

    SettingsPage(
        title = stringResource(R.string.artist_images),
        onBack = onBack
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = FoamDimens.paddingMedium),
            contentPadding = PaddingValues(
                top = SettingsHeaderZoneHeight,
                bottom = LocalBottomBarClearance.current
            )
        ) {
            // Resolution master switch: its own card.
            item {
                FoamCard(modifier = Modifier.fillMaxWidth()) {
                    FoamSwitchItem(
                        title = stringResource(R.string.artist_images_resolve),
                        description = stringResource(R.string.artist_images_resolve_desc),
                        checked = settings.artistImageResolveEnabled,
                        onCheckedChange = { viewModel.setArtistImageResolveEnabled(it) }
                    )
                }
                Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))
            }

            // Everything below only configures an enabled feature.
            item {
                AnimatedVisibility(
                    visible = settings.artistImageResolveEnabled,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column {
                        // On-demand cache bound: its own card.
                        FoamCard(modifier = Modifier.fillMaxWidth()) {
                            FoamSliderStateItem(
                                title = stringResource(R.string.artist_images_cache_limit),
                                description = stringResource(R.string.artist_images_cache_limit_desc),
                                initialValue = settings.artistImageCacheLimit.toFloat(),
                                onValueChangeFinished = {
                                    viewModel.setArtistImageCacheLimit(it.roundToInt())
                                },
                                valueRange = 0f..50f,
                                minMaxDisplay = "0" to "50"
                            )
                        }

                        Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))

                        // Bulk resolve switch: its own card. Turning it off
                        // mid-scan stops the scan.
                        FoamCard(modifier = Modifier.fillMaxWidth()) {
                            FoamSwitchItem(
                                title = stringResource(R.string.artist_images_resolve_all),
                                description = stringResource(R.string.artist_images_resolve_all_desc),
                                checked = settings.artistImageResolveAllEnabled,
                                onCheckedChange = { enabled ->
                                    viewModel.setArtistImageResolveAllEnabled(enabled)
                                    if (!enabled && artistImageScan.running) {
                                        viewModel.stopArtistImageScan()
                                    }
                                }
                            )
                        }

                        // Scan controls, gated by the bulk switch.
                        AnimatedVisibility(
                            visible = settings.artistImageResolveAllEnabled,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Column {
                                Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))
                                FoamCard(modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.fillMaxWidth()) {
                                        ScanControlsRow(
                                            scan = artistImageScan,
                                            onStart = viewModel::startArtistImageScan,
                                            onStop = viewModel::stopArtistImageScan
                                        )
                                        if (artistImageScan.running && artistImageScan.total > 0) {
                                            Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                                            ScanProgressBar(
                                                found = artistImageScan.found,
                                                total = artistImageScan.total
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))

                        // Cache management: its own card.
                        FoamCard(modifier = Modifier.fillMaxWidth()) {
                            CacheManagementRow(
                                cachedCount = artistImageScan.cachedCount,
                                onClear = viewModel::clearArtistImageCache
                            )
                        }
                    }
                }
            }
        }
    }
}

/** found/total progress text with the animated START SCAN <-> STOP pill. */
@Composable
private fun ScanControlsRow(
    scan: ArtistImageScanState,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(FoamDimens.spacingStandard),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicText(
            text = if (scan.running) {
                stringResource(R.string.artist_images_scanning, scan.found, scan.total)
            } else {
                stringResource(R.string.artist_images_found, scan.found, scan.total)
            },
            style = FoamTheme.typography.caption,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(FoamDimens.spacingSmall))
        // START SCAN <-> STOP: the pill animates its background, size and
        // label between the two states; the ripple stays bounded inside it.
        val scanRunning = scan.running
        val scanButtonBackground by animateColorAsState(
            targetValue = if (scanRunning) FoamTheme.colors.surfaceVariant
            else FoamTheme.accentColor,
            animationSpec = tween(durationMillis = FoamMotion.Duration.SWIFT),
            label = "artistScanButtonBackground"
        )
        Box(
            modifier = Modifier
                .animateContentSize(animationSpec = tween(durationMillis = FoamMotion.Duration.SWIFT))
                .clip(RoundedCornerShape(FoamDimens.buttonRounding))
                .background(scanButtonBackground)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = true, color = FoamTheme.colors.textPrimary),
                    onClick = { if (scanRunning) onStop() else onStart() }
                )
                .padding(
                    horizontal = FoamDimens.spacingStandard,
                    vertical = FoamDimens.spacingSmall
                )
        ) {
            AnimatedContent(
                targetState = scanRunning,
                transitionSpec = {
                    fadeIn(animationSpec = tween(durationMillis = FoamMotion.Duration.SNAP)) togetherWith
                            fadeOut(animationSpec = tween(durationMillis = FoamMotion.Duration.SNAP))
                },
                label = "artistScanButtonLabel"
            ) { running ->
                BasicText(
                    text = if (running) stringResource(R.string.artist_images_stop)
                    else stringResource(R.string.start_scan),
                    style = FoamTheme.typography.caption.copy(
                        color = if (running) FoamTheme.colors.textPrimary
                        else FoamTheme.colors.background,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        }
    }
}

/** Thin accent progress bar: found / total artists. */
@Composable
private fun ScanProgressBar(found: Int, total: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FoamDimens.spacingStandard)
            .height(FoamDimens.spacingTiny)
            .clip(RoundedCornerShape(FoamDimens.spacingTiny / 2))
            .foamSurfaceVariant()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(found.toFloat() / total)
                .height(FoamDimens.spacingTiny)
                .background(FoamTheme.accentColor)
        )
    }
}

/** Cached image count with the CLEAR CACHE pill. */
@Composable
private fun CacheManagementRow(
    cachedCount: Int,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(FoamDimens.spacingStandard),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicText(
            text = stringResource(R.string.artist_images_cache_count, cachedCount),
            style = FoamTheme.typography.caption,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(FoamDimens.spacingSmall))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(FoamDimens.buttonRounding))
                .foamSurfaceVariant()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = true, color = FoamTheme.colors.textPrimary),
                    onClick = onClear
                )
                .padding(
                    horizontal = FoamDimens.spacingStandard,
                    vertical = FoamDimens.spacingSmall
                )
        ) {
            BasicText(
                text = stringResource(R.string.artist_images_clear_cache),
                style = FoamTheme.typography.caption.copy(
                    color = FoamTheme.colors.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }
}
