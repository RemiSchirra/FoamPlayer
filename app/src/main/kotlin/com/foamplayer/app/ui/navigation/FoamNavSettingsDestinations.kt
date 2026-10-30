package com.foamplayer.app.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.foamplayer.app.BuildConfig
import com.foamplayer.app.ui.screens.settings.AboutSettingsScreen
import com.foamplayer.app.ui.screens.settings.AudioBehaviorSettingsScreen
import com.foamplayer.app.ui.screens.settings.AudioDevicesSettingsScreen
import com.foamplayer.app.ui.screens.settings.AudioSettingsScreen
import com.foamplayer.app.ui.screens.settings.BackgroundSettingsScreen
import com.foamplayer.app.ui.screens.settings.CollectionsSettingsScreen
import com.foamplayer.app.ui.screens.settings.ColorsSettingsScreen
import com.foamplayer.app.ui.screens.settings.DiscordSettingsScreen
import com.foamplayer.app.ui.screens.settings.EqualizerSettingsScreen
import com.foamplayer.app.ui.screens.settings.HomeSettingsScreen
import com.foamplayer.app.ui.screens.settings.LastFmSettingsScreen
import com.foamplayer.app.ui.screens.settings.LibrarySettingsScreen
import com.foamplayer.app.ui.screens.settings.LyricsSettingsScreen
import com.foamplayer.app.ui.screens.settings.MiscSettingsScreen
import com.foamplayer.app.ui.screens.settings.NavBarSettingsScreen
import com.foamplayer.app.ui.screens.settings.NotificationSettingsScreen
import com.foamplayer.app.ui.screens.settings.PlaybackSettingsScreen
import com.foamplayer.app.ui.screens.settings.PlayerSettingsScreen
import com.foamplayer.app.ui.screens.settings.SettingsScreen
import com.foamplayer.app.ui.screens.settings.TestSettingsScreen
import com.foamplayer.app.ui.screens.settings.UISettingsScreen
import com.foamplayer.app.ui.screens.settings.lyricsmanager.LyricsManagerScreen
import com.foamplayer.app.ui.screens.settings.lyricsmanager.LyricsSearchResultsScreen

/** The settings stack and its leaf pages. */
internal fun NavGraphBuilder.settingsDestinations(context: FoamNavGraphContext) {
    composable(Screen.Settings.route) {
        SettingsScreen(
            onNavigateToAudio = { context.navController.navigate(Screen.SettingsAudio.route) },
            onNavigateToUI = { context.navController.navigate(Screen.SettingsUI.route) },
            onNavigateToLibrary = { context.navController.navigate(Screen.SettingsLibrary.route) },
            onNavigateToLyrics = { context.navController.navigate(Screen.SettingsLyrics.route) },
            onNavigateToMisc = { context.navController.navigate(Screen.SettingsMisc.route) },
            onNavigateToAbout = { context.navController.navigate(Screen.SettingsAbout.route) },
            // Compiled in only with devMode=true; the entry disappears from
            // Settings when the flag is off.
            onNavigateToTest = if (BuildConfig.DEV_MODE) {
                { context.navController.navigate(Screen.SettingsTest.route) }
            } else {
                null
            }
        )
    }
    composable(Screen.SettingsAudio.route) {
        AudioSettingsScreen(
            onBack = { context.back() },
            onNavigateToPlayback = { context.navController.navigate(Screen.SettingsAudioPlayback.route) },
            onNavigateToBehavior = { context.navController.navigate(Screen.SettingsAudioBehavior.route) },
            onNavigateToDevices = { context.navController.navigate(Screen.SettingsAudioDevices.route) },
            onNavigateToEqualizer = { context.navController.navigate(Screen.SettingsAudioEqualizer.route) }
        )
    }
    composable(Screen.SettingsAudioPlayback.route) {
        PlaybackSettingsScreen(onBack = { context.back() })
    }
    composable(Screen.SettingsAudioBehavior.route) {
        AudioBehaviorSettingsScreen(onBack = { context.back() })
    }
    composable(Screen.SettingsAudioDevices.route) {
        AudioDevicesSettingsScreen(onBack = { context.back() })
    }
    composable(Screen.SettingsAudioEqualizer.route) {
        EqualizerSettingsScreen(onBack = { context.back() })
    }
    composable(Screen.SettingsUI.route) {
        UISettingsScreen(
            onBack = { context.back() },
            onNavigateToColors = { context.navController.navigate(Screen.SettingsColors.route) },
            onNavigateToPlayer = { context.navController.navigate(Screen.SettingsPlayer.route) },
            onNavigateToNavBar = { context.navController.navigate(Screen.SettingsNavBar.route) },
            onNavigateToBackground = { context.navController.navigate(Screen.SettingsBackground.route) },
            onNavigateToCollections = { context.navController.navigate(Screen.SettingsUICollections.route) },
            onNavigateToHome = { context.navController.navigate(Screen.SettingsUIHome.route) }
        )
    }
    composable(Screen.SettingsColors.route) {
        ColorsSettingsScreen(onBack = { context.back() })
    }
    composable(Screen.SettingsPlayer.route) {
        PlayerSettingsScreen(onBack = { context.back() })
    }
    composable(Screen.SettingsNavBar.route) {
        NavBarSettingsScreen(onBack = { context.back() })
    }
    composable(Screen.SettingsBackground.route) {
        BackgroundSettingsScreen(onBack = { context.back() })
    }
    composable(Screen.SettingsUICollections.route) {
        CollectionsSettingsScreen(onBack = { context.back() })
    }
    composable(Screen.SettingsUIHome.route) {
        HomeSettingsScreen(onBack = { context.back() })
    }
    composable(Screen.SettingsLyricsManager.route) {
        LyricsManagerScreen(
            onBack = { context.back() },
            onOpenResults = { trackId ->
                context.navController.navigate(Screen.SettingsLyricsManagerResults.createRoute(trackId))
            }
        )
    }
    composable(
        Screen.SettingsLyricsManagerResults.route,
        arguments = listOf(navArgument("trackId") { type = NavType.LongType })
    ) { backStackEntry ->
        val trackId = backStackEntry.arguments?.getLong("trackId") ?: -1L
        LyricsSearchResultsScreen(
            trackId = trackId,
            onBack = { context.back() }
        )
    }
    composable(Screen.SettingsLibrary.route) {
        LibrarySettingsScreen(onBack = { context.back() })
    }
    composable(Screen.SettingsLyrics.route) {
        LyricsSettingsScreen(
            onBack = { context.back() },
            onNavigateToLyricsManager = {
                context.navController.navigate(Screen.SettingsLyricsManager.route)
            }
        )
    }
    composable(Screen.SettingsMisc.route) {
        MiscSettingsScreen(
            onBack = { context.back() },
            onNavigateToNotification = {
                context.navController.navigate(Screen.SettingsNotification.route)
            },
            onNavigateToLastFm = {
                context.navController.navigate(Screen.SettingsLastFm.route)
            },
            onNavigateToDiscord = {
                context.navController.navigate(Screen.SettingsDiscord.route)
            }
        )
    }
    composable(Screen.SettingsNotification.route) {
        NotificationSettingsScreen(onBack = { context.back() })
    }
    composable(Screen.SettingsLastFm.route) {
        LastFmSettingsScreen(onBack = { context.back() })
    }
    composable(Screen.SettingsDiscord.route) {
        DiscordSettingsScreen(onBack = { context.back() })
    }
    composable(Screen.SettingsAbout.route) {
        AboutSettingsScreen(onBack = { context.back() })
    }
    // Developer test screen: registered only with devMode=true.
    if (BuildConfig.DEV_MODE) {
        composable(Screen.SettingsTest.route) {
            TestSettingsScreen(
                onBack = { context.back() },
                onOnboardingReset = {
                    // Re-run the first-run flow from a clean stack.
                    context.navController.navigate(Screen.Onboarding.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
    }
}
