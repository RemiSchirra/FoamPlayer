# FoamPlayer Architecture

This document describes how FoamPlayer is structured, from the Gradle module layout down to the playback and data flows.

---

## Tech Stack

| Concern | Technology |
|---|---|
| Language | Kotlin 2.4 (JVM 17 for the app, JVM 21 for library modules) |
| UI | Jetpack Compose (BOM 2026.06.00), Material, Navigation Compose |
| Playback | Media3 / ExoPlayer 1.9 (`exoplayer`, `session`, `extractor`) |
| Dependency injection | Hilt 2.59 (KSP) |
| Persistence | Room 2.8 (tracks, playlists, queue, listen history, cached lyrics), DataStore Preferences (settings) |
| Networking | Ktor Client 3.5 (OkHttp/CIO engines, content negotiation, WebSockets for Discord) |
| Async | Kotlin Coroutines & Flows (`StateFlow` / `SharedFlow` everywhere) |
| Imaging | Coil (album art), AndroidX Palette (color extraction) |
| Lyrics rendering | `com.mocharealm.accompanist:lyrics-core` / `lyrics-ui` (karaoke, word/line-synced) |
| Japanese tokenization | Kuromoji IPADIC (search lyrics by typing Japanese text) |
| Testing | JUnit 4, Mockito / mockito-kotlin, kotlinx-coroutines-test |

---

## Module Overview

FoamPlayer is a multi-module Gradle project. The `:app` module contains the entire Android application; every other module is a **plain Kotlin (JVM) library** focused on one external service. This keeps third-party API clients small, testable, and reusable outside Android.

```
FoamPlayer/
├── app/                     # Android application (Compose UI, audio, data, DI)
├── canvas/
│   ├── core/                # CanvasArtwork model shared by all canvas providers
│   ├── apple/               # Apple Music canvas resolver
│   ├── tidal/               # Tidal canvas resolver
│   ├── vivimusic/           # ViviMusic canvas resolver
│   └── echomusic/           # EchoMusic canvas resolver
├── lyrics/
│   ├── betterlyrics/        # BetterLyrics provider
│   ├── lrclib/              # LrcLib provider
│   ├── kugou/               # KuGou provider
│   ├── simpmusic/           # SimpMusic provider
│   ├── unison/              # Unison provider
│   ├── youlyplus/           # YouLy+ provider
│   └── paxsenix/            # Paxsenix aggregator (Apple Music, Musixmatch,
│                            #   Netease, Spotify, YouTube backends)
├── spotify/                 # Anonymous Spotify auth + album and artist image resolvers
└── discord/                 # Discord Rich Presence
```

---

## High-Level Diagram

```
                          ┌───────────────────────────────────────────────┐
                          │                    :app                       │
                          │                                               │
  ┌──────────┐   Media    │  ┌───────────────┐      ┌──────────────────┐  │
  │ System / │  session   │  │   UI (Compose)│      │  DI (Hilt)       │  │
  │ BT / car │◄───────────┼──┤  screens,     │◄────►│  AudioModule,    │  │
  │ controls │  Player    │  │  ViewModels   │State │  DatabaseModule, │  │
  └──────────┘  Service   │  │  navigation   │Flows │  Repository...   │  │
       ▲                  │  └───────┬───────┘      └──────────────────┘  │
       │                  │          │ observe/act                        │
       │                  │          ▼                                    │
       │                  │  ┌──────────────────┐                         │
       │                  │  │    AudioEngine   │ (singleton)             │
       │                  │  │  queue, state,   │                         │
       │                  │  │  crossfade swap  │                         │
       │                  │  └───────┬──────────┘                         │
       │                  │          │ ExoPlayer + AudioSink              │
       │                  │          ▼                                    │
       │                  │  ┌──────────────────┐   ┌─────────────────┐   │
       │                  │  │ DspEngine /      │   │ BitPerfect      │   │
       │                  │  │ DspAudioProcessor│   │ Detector +      │   │
       │                  │  │ (passthrough =   │   │ FidelityScore + │   │
       │                  │  │ bit-perfect)     │   │ AudioPipeline   │   │
       │                  │  └───────┬──────────┘   └─────────────────┘   │
       │                  │          │ audio out                          │
       │                  │          ▼                                    │
       │                  │  ┌──────────────────┐                         │
       │                  │  │ OutputDevice /   │                         │
       │                  │  │ per-device config│                         │
       │                  │  └──────────────────┘                         │
       │                  └───────────┬───────────────────────────────────┘
       │                              │
       │        ┌─────────────────────┼──────────────────────────────┐
       │        ▼                     ▼                              ▼
       │  ┌───────────┐        ┌──────────────┐            ┌──────────────────┐
       │  │ Discord   │◄───────│  Discord/    │            │  Data layer      │
       │  │ Gateway   │        │  Presence    │            │  Room DB,        │
       │  │ (WSS)     │        │  Controller  │            │  DataStore,      │
       │  └───────────┘        └──────────────┘            │  repositories,   │
       │                                                   │  SAF scanner     │
       │                                                   └────────┬─────────┘
       │                                                            │
       │                              ┌─────────────────────────────┼──────────┐
       │                              ▼                             ▼          ▼
       │                      ┌──────────────┐   ┌──────────────┐  ┌───────────┐
       └──────────────────────┤ Spotify      │   │ Lyrics       │  │ Canvas    │
        (art reused for       │ resolvers    │   │ providers    │  │ providers │
         presence/assets)     │ (:spotify)   │   │ (:lyrics:*)  │  │(:canvas:*)│
                              └──────┬───────┘   └──────┬───────┘  └─────┬─────┘
                                     │                  │                │
                                     ▼                  ▼                ▼
                              (open.spotify.com)  (11 lyric APIs)  (4 canvas APIs)
```

---

## The `:app` Module

Package layout under `com.foamplayer.app` (~200 Kotlin files):

```
com.foamplayer.app/
├── FoamApplication.kt / MainActivity.kt
├── audio/                  # The audio stack (see dedicated section)
│   ├── bitperfect/         #   BitPerfectDetector: evaluates the signal path
│   ├── device/             #   OutputDeviceManager: per-device audio config
│   ├── dsp/                #   DspEngine, DspAudioProcessor, EQ filters/math
│   ├── engine/             #   AudioEngine, FadeEngine, PlaybackState, QueuePersistence
│   ├── fidelity/           #   FidelityScore model
│   ├── pipeline/           #   AudioPipeline snapshot model (UI visualization)
│   └── service/            #   PlaybackService (MediaSessionService), notification
├── data/
│   ├── backup/             # Settings/library backup & restore (JSON)
│   ├── canvas/             # CanvasProvider: chain of canvas sources
│   ├── db/                 # Room database: entities + DAOs
│   ├── lyrics/             # LrcParser, lyrics file/tag writers, status scanner
│   ├── media/              # EmbeddedAlbumArtFetcher
│   ├── repository/         # Track/Playlist/Lyrics/Settings/ArtistImage repositories
│   ├── scanner/            # LocalAudioScanner (SAF), M3U parsing, cover providers
│   └── settings/           # AppSettings model
├── di/                     # Hilt modules (Audio, Database, Discord, Repository, Spotify)
├── discord/                # DiscordPresenceController (bridges AudioEngine -> :discord)
├── lyrics/                 # LyricsProvider interface, registry, 14 provider adapters
├── notification/           # Media notification provider, lock-screen launcher
└── ui/
    ├── components/ modals/ theme/ utils/
    ├── navigation/         # FoamNavigation, Screen definitions
    └── screens/            # home, player, lyrics, library, browse, playlists,
                            # queue, search, stats, settings (+ lyrics manager),
                            # equalizer (WIP), onboarding
```

### Data flow

1. **Scan**: `LocalAudioScanner` enumerates audio files via SAF tree URIs, extracts metadata with `MediaMetadataRetriever` on a bounded parallel dispatcher (with FLAC/MP4 fallback parsers), and upserts `TrackEntity` rows into Room.
2. **Browse**: ViewModels collect Room `Flow`s exposed by `TrackRepository` / `PlaylistRepository` and render Compose screens.
3. **Play**: UI calls `AudioEngine` (never ExoPlayer directly). The engine manages the queue, windows a small number of items around the current track, and persists the queue via `QueuePersistence`.
4. **Enrich**: While playing, `LyricsRepository` resolves lyrics (online cache → sidecar file → embedded tag, in the user's configured source order), `CanvasProvider` fetches an animated canvas, and `DiscordPresenceController` pushes Rich Presence. `SpotifyArtistImageResolver` fills artist images.

---

## Audio Stack

The audio path is designed around **bit-perfect playback**:

- **`AudioEngine`** — app-scoped singleton owning the `ExoPlayer` instance, the queue and playback state. Crossfades build a *secondary* player that is promoted at the swap (via `FadeEngine`), so the engine's `player` reference always points at the audible stream.
- **`DspEngine` / `DspAudioProcessor`** — a custom DSP chain inserted into ExoPlayer's `AudioSink` (the app's replacement for the platform `Equalizer` effect). When the DSP state is neutral, the processor is a **byte-for-byte passthrough**, which is what keeps the path bit-perfect. EQ state changes are published instantly to `StateFlow` (live UI), pushed to the processor, and persisted to DataStore (debounced).
- **`OutputDeviceManager`** — tracks audio devices and keeps **independent audio settings per output device**.
- **`BitPerfectDetector`** — a *pure evaluator*: given the track format, the active device, per-device config and DSP state, it derives the bit-perfect verdict (Poweramp-style "direct volume control" semantics), the 0–100 `FidelityScore`, and the `AudioPipeline` snapshot rendered by the pipeline modal.

```
File ──► ExoPlayer ──► DspAudioProcessor ──► AudioSink ──► Output device
                          │  (passthrough when neutral = bit-perfect)
                          ▲
                  DspEngine state ◄── EQ / settings UI (StateFlow)
```

- **`PlaybackService`** — a `MediaSessionService` bound to the engine's player. It does *not* own the player: the system can stop an idle service while the process lives, so the engine outlives the service. It handles media-button resumption (rebuilding the persisted queue), the media notification, and the optional lock-screen player overlay.

---

## Provider Pattern (lyrics & canvas)

All online sources implement one tiny interface and are resolved through registries, making sources pluggable and user-configurable:

- **Lyrics**: `LyricsProvider` (name, `isEnabled`, `getLyrics`, `getAllLyrics` + streaming callback). `LyricsProviderRegistry` maps the user's preferred-provider setting to the adapter. Fourteen adapters cover embedded tags, local `.lrc`/`.txt` sidecar files, and 11+ online APIs (LrcLib, KuGou, BetterLyrics/TTML, SimpMusic, Unison, YouLy+, and five Paxsenix backends). `LyricsRepository` orchestrates resolution order, the Room-backed online cache, and search-by-lyrics (with Kuromoji tokenization for Japanese text).
- **Canvas**: `canvas/core` defines the `CanvasArtwork` model; each `canvas/*` module exposes a `getBySongArtist(...)` resolver. `CanvasProvider` tries Apple Music → Tidal → ViviMusic → EchoMusic in preference order and enforces the "Wi-Fi only" restriction.

The `:lyrics/*` and `:canvas/*` modules stay Android-free: plain Kotlin + Ktor, so they compile fast, unit-test cleanly, and could be published standalone.

---

## Integrations

- **`spotify`** (`:spotify`): mints anonymous web-player access tokens using a community-maintained TOTP secret, then powers `SpotifyAlbumArtResolver` / `SpotifyArtistImageResolver` / `SpotifySearchQuery`.
- **`discord`** (`:discord`): a hand-rolled Discord client — OAuth login (`DiscordOAuth` + callback activity), `DiscordGatewayClient` over Ktor WebSockets, and `DiscordPresenceClient` publishing track assets (art reused from the Spotify module, YouTube thumbnail fallback).
- Both are bridged into the app by thin controllers in `:app` (`DiscordPresenceController`, `ArtistImageStore`) that observe `AudioEngine`'s playback state.

---

## Playback Resumption

`QueuePersistence` snapshots the playing session (queue, position, per-item metadata) to disk. Media3 retires a paused foreground service after ~10 minutes and the system may kill the process; when the service is recreated to serve a media-button press (Bluetooth, car, headset), `onPlaybackResumption` restores the queue exactly where it was so notification controls keep working.
