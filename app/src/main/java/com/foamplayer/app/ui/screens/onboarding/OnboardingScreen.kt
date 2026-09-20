package com.foamplayer.app.ui.screens.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp as lerpDp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.R
import com.foamplayer.app.data.scanner.ScanStatus
import com.foamplayer.app.ui.screens.settings.SettingsViewModel
import com.foamplayer.app.ui.screens.settings.folderDisplayName
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val OnboardingPageCount = 3

@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    val scanStatus by viewModel.scanStatus.collectAsState()

    var audioGranted by remember { mutableStateOf(context.hasAudioPermission()) }
    var notificationsGranted by remember { mutableStateOf(context.hasNotificationPermission()) }

    val refreshPermissions = {
        audioGranted = context.hasAudioPermission()
        notificationsGranted = context.hasNotificationPermission()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshPermissions() }

    // The system dialog's result also arrives here, but re-checking on every resume
    // additionally covers grants made from the system settings page.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            viewModel.addFolder(it.toString())
        }
    }

    val pagerState = rememberPagerState(pageCount = { OnboardingPageCount })
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FoamTheme.colors.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) { page ->
            when (page) {
                0 -> WelcomePage()
                1 -> PermissionsPage(
                    audioGranted = audioGranted,
                    notificationsGranted = notificationsGranted,
                    onRequestAudio = { permissionLauncher.launch(arrayOf(audioPermission())) },
                    onRequestNotifications = {
                        permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                    }
                )
                else -> LibraryPage(
                    folders = settings.libraryFolders,
                    scanStatus = scanStatus,
                    onPickFolder = { folderPickerLauncher.launch(null) },
                    onRemoveFolder = viewModel::removeFolder,
                    onStartScan = viewModel::startScan
                )
            }
        }

        // Continuous pager position in page units (e.g. 1.5 = halfway between pages
        // 1 and 2): the bottom bar reads it so its elements track the finger during
        // manual swipes instead of snapping when the target page changes.
        val pagerScrollPosition = (pagerState.currentPage + pagerState.currentPageOffsetFraction)
            .coerceIn(0f, (OnboardingPageCount - 1).toFloat())

        OnboardingBottomBar(
            scrollPosition = pagerScrollPosition,
            pageCount = OnboardingPageCount,
            onPrevious = {
                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
            },
            onNext = {
                if (pagerState.currentPage < OnboardingPageCount - 1) {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                } else {
                    // Flipping the flag also lets MainActivity's auto-request pick up any
                    // permission skipped here (and start the playback service when set).
                    viewModel.setOnboardingCompleted(true)
                    onFinish()
                }
            }
        )
    }
}

// ---- Page 1: Welcome ----------------------------------------------------------

@Composable
private fun WelcomePage() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(FoamDimens.paddingMedium),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(FoamTheme.accentColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = FoamTheme.colors.background,
                    modifier = Modifier.size(52.dp)
                )
            }
            Spacer(modifier = Modifier.height(FoamDimens.paddingLarge))
            BasicText(
                text = stringResource(R.string.onboarding_welcome_title),
                style = FoamTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = FoamDimens.paddingMedium)
            )
            Spacer(modifier = Modifier.height(FoamDimens.spacingSmall))
            BasicText(
                text = stringResource(R.string.onboarding_welcome_subtitle),
                style = FoamTheme.typography.body.copy(textAlign = TextAlign.Center),
                modifier = Modifier.padding(horizontal = FoamDimens.paddingLarge)
            )
        }
    }
}

// ---- Page 2: Permissions ------------------------------------------------------

@Composable
private fun PermissionsPage(
    audioGranted: Boolean,
    notificationsGranted: Boolean,
    onRequestAudio: () -> Unit,
    onRequestNotifications: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = FoamDimens.paddingMedium),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            BasicText(
                text = stringResource(R.string.onboarding_permissions_title),
                style = FoamTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(FoamDimens.spacingSmall))
            BasicText(
                text = stringResource(R.string.onboarding_permissions_subtitle),
                style = FoamTheme.typography.body
            )
            Spacer(modifier = Modifier.height(FoamDimens.paddingLarge))

            PermissionCard(
                icon = Icons.Rounded.MusicNote,
                title = stringResource(R.string.onboarding_permission_audio_title),
                description = stringResource(R.string.onboarding_permission_audio_desc),
                granted = audioGranted,
                onGrant = onRequestAudio
            )
            Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
            PermissionCard(
                icon = Icons.Rounded.Notifications,
                title = stringResource(R.string.onboarding_permission_notifications_title),
                description = stringResource(R.string.onboarding_permission_notifications_desc),
                granted = notificationsGranted,
                onGrant = onRequestNotifications
            )
        }
    }
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    description: String,
    granted: Boolean,
    onGrant: () -> Unit
) {
    val statusColor by animateColorAsState(
        targetValue = if (granted) FoamTheme.colors.bitPerfectActive else FoamTheme.accentColor,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "permissionStatusColor"
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FoamDimens.cornerRadiusMedium))
            .background(FoamTheme.colors.surface)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = FoamTheme.colors.textPrimary),
                enabled = !granted
            ) { onGrant() }
            .padding(FoamDimens.paddingMedium)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(FoamDimens.headerIconSize)
                    .clip(RoundedCornerShape(FoamDimens.headerIconRounding))
                    .background(statusColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(FoamDimens.iconMedium)
                )
            }
            Spacer(modifier = Modifier.width(FoamDimens.spacingStandard))
            Column(modifier = Modifier.weight(1f)) {
                BasicText(text = title, style = FoamTheme.typography.titleMedium)
                BasicText(text = description, style = FoamTheme.typography.body)
            }
            Spacer(modifier = Modifier.width(FoamDimens.spacingSmall))
            Crossfade(
                targetState = granted,
                animationSpec = tween(durationMillis = 300),
                label = "permissionTrailingIcon"
            ) { isGranted ->
                Icon(
                    imageVector = if (isGranted) Icons.Rounded.Check else Icons.Rounded.Add,
                    contentDescription = null,
                    tint = if (isGranted) FoamTheme.colors.bitPerfectActive else FoamTheme.accentColor,
                    modifier = Modifier.size(FoamDimens.iconMedium)
                )
            }
        }
    }
}

// ---- Page 3: Library ----------------------------------------------------------

@Composable
private fun LibraryPage(
    folders: Set<String>,
    scanStatus: ScanStatus,
    onPickFolder: () -> Unit,
    onRemoveFolder: (String) -> Unit,
    onStartScan: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = FoamDimens.paddingMedium),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            BasicText(
                text = stringResource(R.string.onboarding_library_title),
                style = FoamTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(FoamDimens.spacingSmall))
            BasicText(
                text = stringResource(R.string.onboarding_library_subtitle),
                style = FoamTheme.typography.body
            )
            Spacer(modifier = Modifier.height(FoamDimens.paddingLarge))

            // --- Folders ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(FoamDimens.cornerRadiusMedium))
                    .background(FoamTheme.colors.surface)
                    .padding(FoamDimens.paddingMedium)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicText(
                            text = stringResource(R.string.library_folders),
                            style = FoamTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(FoamDimens.buttonRounding))
                                .background(FoamTheme.accentColor)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(color = FoamTheme.colors.background)
                                ) { onPickFolder() }
                                .padding(horizontal = FoamDimens.spacingMedium, vertical = FoamDimens.spacingSmall)
                        ) {
                            BasicText(
                                text = stringResource(R.string.add_folder),
                                style = FoamTheme.typography.caption.copy(
                                    color = FoamTheme.colors.background,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                    if (folders.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                                .background(FoamTheme.colors.surfaceVariant)
                                .padding(vertical = FoamDimens.paddingLarge),
                            contentAlignment = Alignment.Center
                        ) {
                            BasicText(
                                text = stringResource(R.string.no_folders_selected),
                                style = FoamTheme.typography.body
                            )
                        }
                    } else {
                        val sortedFolders = remember(folders) { folders.sortedBy { folderDisplayName(it) } }
                        sortedFolders.forEach { folder ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = FoamDimens.spacingTiny)
                                    .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                                    .background(FoamTheme.colors.surfaceVariant)
                            ) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(FoamDimens.spacingMedium),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    BasicText(
                                        text = folderDisplayName(folder),
                                        style = FoamTheme.typography.caption,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1
                                    )
                                    Spacer(modifier = Modifier.width(FoamDimens.spacingSmall))
                                    Icon(
                                        imageVector = FoamTheme.icons.current.close,
                                        contentDescription = stringResource(R.string.remove),
                                        tint = FoamTheme.accentColor,
                                        modifier = Modifier
                                            .size(FoamDimens.iconSmall)
                                            .clickable { onRemoveFolder(folder) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))

            // --- First scan ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(FoamDimens.cornerRadiusMedium))
                    .background(FoamTheme.colors.surface)
                    .padding(FoamDimens.paddingMedium)
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            BasicText(text = stringResource(R.string.scan_media), style = FoamTheme.typography.titleMedium)
                            when (val status = scanStatus) {
                                is ScanStatus.Scanning -> {
                                    BasicText(
                                        text = if (status.progress >= 1f) stringResource(R.string.scan_finishing)
                                        else stringResource(R.string.scanning_status, status.currentFile, (status.progress * 100).toInt()),
                                        style = FoamTheme.typography.caption
                                    )
                                }
                                is ScanStatus.Finished -> {
                                    BasicText(
                                        text = stringResource(R.string.scan_finished, status.count),
                                        style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.bitPerfectActive)
                                    )
                                }
                                else -> {
                                    BasicText(text = stringResource(R.string.sync_library), style = FoamTheme.typography.body)
                                }
                            }
                        }

                        // Clickable text action: accent-colored, bounded ripple.
                        val isScanning = scanStatus is ScanStatus.Scanning
                        BasicText(
                            text = if (isScanning) stringResource(R.string.scanning_caps)
                            else stringResource(R.string.start_scan),
                            style = FoamTheme.typography.body.copy(
                                color = if (isScanning) FoamTheme.colors.textSecondary else FoamTheme.accentColor,
                                fontWeight = FontWeight.Bold
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(FoamDimens.buttonRounding))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(color = FoamTheme.accentColor),
                                    enabled = !isScanning
                                ) { onStartScan() }
                                .padding(horizontal = FoamDimens.spacingMedium, vertical = FoamDimens.spacingSmall)
                        )
                    }

                    if (scanStatus is ScanStatus.Scanning) {
                        Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                        val animatedProgress by animateFloatAsState(
                            targetValue = (scanStatus as ScanStatus.Scanning).progress,
                            animationSpec = tween(durationMillis = 200),
                            label = "onboardingScanProgress"
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(FoamDimens.spacingTiny)
                                .clip(RoundedCornerShape(FoamDimens.spacingTiny / 2))
                                .background(FoamTheme.colors.surfaceVariant)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(animatedProgress.coerceIn(0f, 1f))
                                    .height(FoamDimens.spacingTiny)
                                    .background(FoamTheme.accentColor)
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(FoamDimens.paddingLarge))
            BasicText(
                text = stringResource(R.string.onboarding_skip_hint),
                style = FoamTheme.typography.caption
            )
        }
    }
}

// ---- Bottom bar: previous / dots / next ---------------------------------------

/**
 * Every element is driven by [scrollPosition] — the pager's continuous position in
 * page units — so the bar animates progressively under the finger during manual
 * swipes, and follows the tweened pager animation on button presses alike.
 */
@Composable
private fun OnboardingBottomBar(
    scrollPosition: Float,
    pageCount: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    // "Previous" fades/slides in across the first page-width of scrolling.
    val backProgress = scrollPosition.coerceIn(0f, 1f)
    // The forward text crossfades into "Get Started" while the last page approaches.
    val forwardProgress = (scrollPosition - (pageCount - 2)).coerceIn(0f, 1f)
    val slidePx = with(LocalDensity.current) { 16.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FoamDimens.paddingMedium, vertical = FoamDimens.paddingMedium)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                BasicText(
                    text = stringResource(R.string.previous),
                    style = FoamTheme.typography.body.copy(
                        color = FoamTheme.colors.textSecondary,
                        fontWeight = FontWeight.SemiBold
                    ),
                    modifier = Modifier
                        .graphicsLayer {
                            alpha = backProgress
                            translationX = (backProgress - 1f) * slidePx
                        }
                        .clip(RoundedCornerShape(FoamDimens.buttonRounding))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(color = FoamTheme.colors.textSecondary),
                            enabled = backProgress > 0.5f
                        ) { onPrevious() }
                        .padding(horizontal = FoamDimens.spacingMedium, vertical = FoamDimens.spacingSmall)
                )
            }
            OnboardingDots(scrollPosition = scrollPosition, pageCount = pageCount)
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(FoamDimens.buttonRounding))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(color = FoamTheme.colors.textPrimary)
                        ) { onNext() },
                    contentAlignment = Alignment.CenterEnd
                ) {
                    ForwardText(text = stringResource(R.string.next), alpha = 1f - forwardProgress)
                    ForwardText(text = stringResource(R.string.onboarding_get_started), alpha = forwardProgress)
                }
            }
        }
    }
}

@Composable
private fun ForwardText(text: String, alpha: Float) {
    BasicText(
        text = text,
        style = FoamTheme.typography.body.copy(
            color = FoamTheme.accentColor,
            fontWeight = FontWeight.SemiBold
        ),
        modifier = Modifier
            .graphicsLayer { this.alpha = alpha }
            .padding(horizontal = FoamDimens.spacingMedium, vertical = FoamDimens.spacingSmall)
    )
}

@Composable
private fun OnboardingDots(scrollPosition: Float, pageCount: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)) {
        repeat(pageCount) { index ->
            // 0 when the scroll position sits on this dot, 1 when fully passed:
            // width and color morph continuously while the finger drags.
            val distance = abs(scrollPosition - index).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .height(8.dp)
                    .width(lerpDp(24.dp, 8.dp, distance))
                    .clip(RoundedCornerShape(4.dp))
                    .background(lerpColor(FoamTheme.accentColor, FoamTheme.colors.surfaceVariant, distance))
            )
        }
    }
}

// ---- Permission helpers -------------------------------------------------------

private fun audioPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

private fun Context.hasAudioPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, audioPermission()) == PackageManager.PERMISSION_GRANTED

private fun Context.hasNotificationPermission(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
