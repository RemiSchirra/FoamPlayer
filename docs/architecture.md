# FoamPlayer Architecture

How FoamPlayer is built: modules, audio stack, and data flow.

---

## Tech Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose, Navigation Compose
- **Playback:** Media3 / ExoPlayer
- **DI:** Hilt
- **Storage:** Room (library, queue, history, lyrics cache), DataStore (settings)
- **Networking:** Ktor Client
- **Async:** Coroutines and Flow
- **Images:** Coil, Palette
- **Lyrics UI:** Accompanist lyrics (word and line sync)
- **Japanese search:** Kuromoji
- **Tests:** JUnit 4, Mockito, coroutines-test

---

## Modules

`:app` holds the whole Android application. Every other module is a plain Kotlin library that wraps one external service.

```
FoamPlayer/
├── app/                # Android app: UI, audio, data, DI
├── canvas/
│   ├── core/           # CanvasArtwork model
│   ├── apple/
│   ├── tidal/
│   ├── vivimusic/
│   └── echomusic/
├── lyrics/
│   ├── betterlyrics/
│   ├── lrclib/
│   ├── kugou/
│   ├── simpmusic/
│   ├── unison/
│   ├── youlyplus/
│   └── paxsenix/       # Aggregator (Apple Music, Musixmatch, Netease, Spotify, YouTube)
├── spotify/            # Anonymous auth, album and artist images
├── distant/
│   ├── core/           # Provider interface, models, URI scheme, HTTP
│   ├── plex/           # Plex media server
│   └── jellyfin/       # Jellyfin media server
└── discord/            # Rich Presence
```

---

## Big Picture

```
 System / BT / car ◄── MediaSession ──► PlaybackService
                                              │
 UI (Compose + ViewModels) ◄─ StateFlow ─► AudioEngine ──► ExoPlayer
        │                                     │                │
        │                                     │          DspAudioProcessor
        ▼                                     ▼                │
   Data layer                          DiscordPresence     AudioSink
 (Room, DataStore,                      Controller             │
  repositories, scanner)                      │          Output device
        │                                     ▼
        ▼                                :discord
 :spotify  :lyrics:*  :canvas:*
```

The UI never talks to ExoPlayer. It talks to `AudioEngine`, and `AudioEngine` is the single source of truth for playback.

---

## The `:app` Module

```
com.foamplayer.app/
├── audio/
│   ├── bitperfect/     # BitPerfectDetector
│   ├── device/         # OutputDeviceManager
│   ├── dsp/            # DspEngine, DspAudioProcessor, EQ math
│   ├── engine/         # AudioEngine, FadeEngine, QueuePersistence
│   ├── fidelity/       # FidelityScore
│   ├── pipeline/       # AudioPipeline snapshot (for the UI)
│   └── service/        # PlaybackService, notification
├── data/
│   ├── backup/         # JSON backup and restore
│   ├── canvas/         # CanvasProvider
│   ├── db/             # Room entities and DAOs
│   ├── distant/        # DistantRepository, OAuth coordinator, URI streaming
│   ├── lyrics/         # LRC parsing, lyrics file and tag writers
│   ├── media/          # Embedded album art
│   ├── repository/     # Track, Playlist, Lyrics, Settings, ArtistImage
│   ├── scanner/        # LocalAudioScanner (SAF), RemoteLibraryScanner, M3U, covers
│   └── settings/       # AppSettings
├── di/                 # Hilt modules
├── discord/            # DiscordPresenceController
├── lyrics/             # LyricsProvider, registry, adapters
├── notification/       # Media notification, lock-screen launcher
└── ui/                 # components, modals, theme, navigation, screens
```

### Data flow

1. **Scan.** `LocalAudioScanner` walks the SAF folders, reads metadata in parallel, and upserts tracks into Room. `RemoteLibraryScanner` does the same for distant providers, in parallel with the local scan.
2. **Browse.** ViewModels collect Room flows from the repositories and feed the Compose screens.
3. **Play.** The UI calls `AudioEngine`. The engine owns the queue, loads only a few items around the current track, and saves the queue through `QueuePersistence`.
4. **Enrich.** While a track plays, three things happen:
    - `LyricsRepository` finds lyrics.
    - `CanvasProvider` fetches the animated canvas.
    - `DiscordPresenceController` updates Rich Presence.

---

## Audio Stack

The goal is bit-perfect playback.

- **`AudioEngine`** is an app-scoped singleton. It owns the `ExoPlayer`, the queue, and the playback state. A crossfade creates a second player and promotes it at the swap (`FadeEngine`). The engine's `player` always points to the audible stream.
- **`DspEngine` / `DspAudioProcessor`** is our custom DSP chain in ExoPlayer's `AudioSink`. It replaces the platform `Equalizer`. When the DSP is neutral, it passes bytes through untouched. That is what keeps the path bit-perfect.
- **`OutputDeviceManager`** tracks audio devices and keeps separate settings for each one.
- **`BitPerfectDetector`** is a pure function of its inputs: track format, device, device config, DSP state. It returns the bit-perfect verdict, the 0–100 `FidelityScore`, and the `AudioPipeline` shown in the pipeline modal.
- **`PlaybackService`** is a `MediaSessionService` bound to the engine's player. It does not own the player, because the system can stop an idle service while the process is alive. It handles media-button resumption, the notification, and the lock-screen overlay.

```
File ─► ExoPlayer ─► DspAudioProcessor ─► AudioSink ─► Output device
                           ▲
                     DspEngine state ◄─ EQ / settings UI
```

EQ changes update the UI state right away, go to the processor, and are saved to DataStore with a debounce.

---

## Providers (lyrics and canvas)

Online sources sit behind a small interface and a registry. This makes them pluggable and user-configurable.

**Lyrics**
- `LyricsProvider` is the interface (`isEnabled`, `getLyrics`, `getAllLyrics`).
- `LyricsProviderRegistry` maps the user's source order to adapters.
- Adapters cover embedded tags, local `.lrc` / `.ttml` files, and the online APIs.
- `LyricsRepository` picks the resolution order, manages the Room cache, and handles search by lyrics (Kuromoji for Japanese).

**Canvas**
- Each `canvas/*` module exposes `getBySongArtist(...)`.
- `CanvasProvider` tries Apple Music, Tidal, ViviMusic, then EchoMusic.
- It also enforces the Wi-Fi only setting.

The `:lyrics:*` and `:canvas:*` modules use plain Kotlin and Ktor only.

### Distant providers (media servers)

The `:distant:*` modules extend the same registry idea to remote libraries. A distant source is scanned into Room like a local one, then streamed on demand.

- `DistantProvider` is the interface (login, token refresh, folder/track listing, stream URL, artwork).
- `DistantProviderRegistry` maps provider types to implementations.
- `DistantUris` gives every remote track a synthetic `foam-distant://` URI; the real URL is resolved at play time.
- `DistantRepository` coordinates connection (Plex signs in through its browser PIN flow; Jellyfin uses credentials), scanning, and streaming. Tokens and server config are stored in DataStore (`AppSettings.distantServers`).
- `DistantDataSourceFactory` is a Media3 `ResolvingDataSource` injected into the player. It turns a `foam-distant://` URI into signed URL plus headers, so ExoPlayer streams remote audio without the engine knowing it is remote.

Providers that stream over the network are flagged as streaming in `BitPerfectDetector`, which caps the fidelity score at a `WARN` transport stage.

---

## Integrations

- **`:spotify`** gets anonymous web-player tokens and resolves album art and artist images.
- **`:discord`** is a custom client: OAuth login, a gateway client over WebSockets, and a presence client. Art comes from `:spotify`, with a YouTube thumbnail as fallback.
- **`:distant:*`** list and stream remote libraries: Plex (browser PIN sign-in) and Jellyfin (credentials).
- Thin controllers in `:app` (`DiscordPresenceController`, `ArtistImageStore`) connect both modules to `AudioEngine`'s playback state.

---

## Playback Resumption

`QueuePersistence` saves the queue, position, and item metadata to disk.

Media3 stops a paused foreground service after about 10 minutes, and the system can kill the process. When a media button recreates the service, `onPlaybackResumption` restores the queue where it was.