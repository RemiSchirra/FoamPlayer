<div align="center">

  <img src="assets/logo.png" width="240" height="240" alt="YumaPlayer Icon" style="border-radius: 28px; box-shadow: 0 8px 24px rgba(0,0,0,0.3);" />

  <h1>FoamPlayer</h1>

  <p align="center">
    Description
  </p>

  <p align="center">
  <a href="#-key-features"><b>Features</b></a> •
  <a href="#-showcase"><b>Showcase</b></a> •
  <a href="#️-architecture--documentation"><b>Architecture</b></a> •
  <a href="#-download--installation"><b>Download</b></a> •
  <a href="#-contributing"><b>Contributing</b></a> •
  <a href="#-support-the-project"><b>Support</b></a>
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

### Equalizer
*Currently in development.*

---

## Showcase
<div align="center">
  <table>
    <tr>
      <td width="33%" align="center">
        <img src="assets/screenshots/" alt="" width="100%"/><br />
        <sub><b>Title</b></sub>
      </td>
      <td width="33%" align="center">
        <img src="assets/screenshots/" alt="" width="100%"/><br />
        <sub><b></b></sub>
      </td>
      <td width="33%" align="center">
        <img src="assets/screenshots/" alt="" width="100%"/><br />
        <sub><b></b></sub>
      </td>
    </tr>
    <tr>
      <td width="33%" align="center">
        <img src="assets/screenshots/" alt="" width="100%"/><br />
        <sub><b></b></sub>
      </td>
      <td width="33%" align="center">
        <img src="assets/screenshots/" alt="" width="100%"/><br />
        <sub><b></b></sub>
      </td>
      <td width="33%" align="center">
        <img src="assets/screenshots/" alt="" width="100%"/><br />
        <sub><b></b></sub>
      </td>
    </tr>
    <tr>
      <td width="33%" align="center">
        <img src="assets/screenshots/" alt="" width="100%"/><br />
        <sub><b></b></sub>
      </td>
      <td width="33%" align="center">
        <img src="assets/screenshots/" alt="" width="100%"/><br />
        <sub><b></b></sub>
      </td>
      <td width="33%" align="center">
        <img src="assets/screenshots/" alt="" width="100%"/><br />
        <sub><b></b></sub>
      </td>
    </tr>
  </table>
</div>


## Architecture

FoamPlayer is an Android app written in Kotlin, split into a main `:app` module and small library modules that isolate every online integration (lyrics, canvas, Spotify, Discord).

- **UI / Design** — Jetpack Compose.
- **Audio engine** — Media3/ExoPlayer + custom DSP chain, bit-perfect.
- **Data & Storage** — Room + DataStore.
- **Network** — Ktor (lyrics, canvas, Spotify, Discord).

More details in [docs/architecture.md](docs/architecture.md).

## Contribution

## Credits

## Legal mentions