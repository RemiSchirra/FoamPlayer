package com.foamplayer.app.lyrics

import com.foamplayer.app.constants.PreferredLyricsProvider
import com.foamplayer.app.lyrics.providers.BetterLyricsProvider
import com.foamplayer.app.lyrics.providers.KuGouLyricsProvider
import com.foamplayer.app.lyrics.providers.LrcLibLyricsProvider
import com.foamplayer.app.lyrics.providers.PaxsenixAppleMusicLyricsProvider
import com.foamplayer.app.lyrics.providers.PaxsenixMusixmatchLyricsProvider
import com.foamplayer.app.lyrics.providers.PaxsenixNeteaseLyricsProvider
import com.foamplayer.app.lyrics.providers.PaxsenixSpotifyLyricsProvider
import com.foamplayer.app.lyrics.providers.PaxsenixYouTubeLyricsProvider
import com.foamplayer.app.lyrics.providers.SimpMusicLyricsProvider
import com.foamplayer.app.lyrics.providers.UnisonLyricsProvider
import com.foamplayer.app.lyrics.providers.YouLyPlusLyricsProvider

/** Maps each settings enum value to its provider implementation. */
val PreferredLyricsProvider.lyricsProvider: LyricsProvider?
    get() = when (this) {
        PreferredLyricsProvider.BETTER_LYRICS -> BetterLyricsProvider
        PreferredLyricsProvider.YOULY_PLUS -> YouLyPlusLyricsProvider
        PreferredLyricsProvider.LRCLIB -> LrcLibLyricsProvider
        PreferredLyricsProvider.KUGOU -> KuGouLyricsProvider
        PreferredLyricsProvider.SIMPMUSIC -> SimpMusicLyricsProvider
        PreferredLyricsProvider.UNISON -> UnisonLyricsProvider
        PreferredLyricsProvider.PAXSENIX_APPLE_MUSIC -> PaxsenixAppleMusicLyricsProvider
        PreferredLyricsProvider.PAXSENIX_NETEASE -> PaxsenixNeteaseLyricsProvider
        PreferredLyricsProvider.PAXSENIX_SPOTIFY -> PaxsenixSpotifyLyricsProvider
        PreferredLyricsProvider.PAXSENIX_MUSIXMATCH -> PaxsenixMusixmatchLyricsProvider
        PreferredLyricsProvider.PAXSENIX_YOUTUBE -> PaxsenixYouTubeLyricsProvider
    }
