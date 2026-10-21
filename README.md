<div align="center">

  <img src="assets/foamplayer_icon.png" width="240" height="240" alt="FoamPlayer Icon" style="border-radius: 28px; box-shadow: 0 8px 24px rgba(0,0,0,0.3);" />

  <h1>FoamPlayer</h1>

  <p align="center">
    Your personal music bubble: a player designed for your music.
  </p>

  <p align="center">
      <a href="#-about"><b>About</b></a> •
      <a href="#-key-features"><b>Features</b></a> •
      <a href="#-showcase"><b>Showcase</b></a> •
      <a href="#-architecture"><b>Architecture</b></a> •
      <a href="#-contributing"><b>Contributing</b></a> •
      <a href="#-credits"><b>Credits</b></a> •
      <a href="#-license--legal-mentions"><b>Legal</b></a>
  </p>
</div>

---

## 🌟 About
FoamPlayer is an independent music player for your local library.

Though for audiophiles, FoamPlayer supports Hi-Res bit perfect playback and is highly customizable.

The project is inspired from many other players, like Poweramp and rely on the open source community 
for some features (see more).

---

## ✨ Key Features
### Playback
* Bit-perfect playback tailored for supported devices.
* Independent audio settings for each output device.
* Playback fidelity score with a clear audio pipeline breakdown.
* Audio cross-fading at track ends.
* Stream audio from your Plex or Jellyfin server.

### Lyrics
* Supports word-synced, line-synced, and plain lyrics.
* Loads lyrics embedded in your audio files or from external local files.
* Fetches missing lyrics from online providers.
* Built-in manager to search and save lyrics locally.
* Search for tracks by typing song lyrics.

### Integrations
* Animated video canvas support.
* Artist images fetched from Spotify.
* Discord Rich Presence status.
* Last.fm scrobbling.

### Equalizer
*Currently in development.*

---

## 📸 Showcase
<table>
  <tr>
    <td><img src="assets/screenshots/player.jpg" alt="Main player"></td>
    <td><img src="assets/screenshots/album.jpg" alt="Album view"></td>
    <td><img src="assets/screenshots/lyrics.jpg" alt="Word synced lyrics"></td>
  </tr>
  <tr>
    <td><img src="assets/screenshots/home.jpg" alt="Home feed"></td>
    <td><img src="assets/screenshots/queue.jpg" alt="Queue"></td>
    <td><img src="assets/screenshots/artist.jpg" alt="Artist view"></td>
  </tr>
</table>

---

## 🏛️ Architecture

FoamPlayer is an Android app written in Kotlin, split into a main `:app` module and small library modules that isolate every online integration (lyrics, canvas, Spotify, Discord, Last.fm).

- **UI / Design** — Jetpack Compose.
- **Audio engine** — Media3/ExoPlayer + custom DSP chain, bit-perfect.
- **Data & Storage** — Room + DataStore.
- **Network** — Ktor (lyrics, canvas, Spotify, Discord, Last.fm).

More details in [docs/architecture.md](docs/architecture.md).

---

## 📝 Contributing
Feel free to contribute to the project, either if it's developing new features, fixing bug or making new translations.

---

## 🤝 Credits
FoamPlayer is built thanks to multiple existing open source projects:
* **[ArchiveTune](https://github.com/rukamori/ArchiveTune)** : for the lyrics and canvas providers.
* **[Accompanist lyrics](https://github.com/6xingyv/accompanist-lyrics-core)** : for the lyrics display engine.

---

## ⚖️ License & Legal mentions
FoamPlayer is free software licensed under the **GNU General Public License v3.0 (GPLv3)**. See [LICENSE](LICENSE) for details.