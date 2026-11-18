# IPTV

A fast, native IPTV player for **Android TV** and **Android phones**, in a single ~125 KB APK.

Sign in with an Xtream Codes subscription and browse live channels, movies and series with a remote
or by touch. No ads, no accounts, no tracking, no bundled content.

![Live TV with the programme guide](docs/screenshots/live-tv.png)

## Features

- **Live TV** — categories, channel list with now/next programme guide, favourites, recently watched.
- **Movies and series** — poster grids, detail pages, seasons and episodes, resume where you left off.
- **Home** — live category shortcuts, Continue Watching, latest and recommended titles.
- **Search** across channels, movies and series, with results as you type.
- **Built for a remote** — every action reachable with arrows, OK and Back; hold OK for options.
- **Touch** — tap, drag and long-press on phones and tablets (landscape).
- **Language and region filters** — show only what you watch; hide or pin individual categories.
- **Player** — channel zapping, last-channel recall, audio and subtitle tracks, picture modes,
  accelerated seeking, automatic next episode.
- **Phone pairing** — scan a QR code on the TV to type your sign-in on your phone.
- **Large libraries** — the catalogue is stored in SQLite; 150,000+ titles stay smooth on a 2 GB box.

<p>
  <img src="docs/screenshots/sign-in.png" alt="Sign-in screen with QR pairing" width="49%">
  <img src="docs/screenshots/live-tv.png" alt="Live TV" width="49%">
</p>

## Requirements

- Android 8.0 (API 26) or newer, phone or Android TV / Google TV.
- An Xtream Codes compatible subscription: server address, username and password.

This app is a player only. It ships with no channels, playlists or streams; you are responsible for
the sources you use it with.

## Build

The project deliberately has no Gradle and no third-party dependencies. It compiles with the JDK and
the Android SDK command-line tools.

Prerequisites: JDK 17, Android SDK with `build-tools` and a `platforms/android-*` package.
Adjust the paths at the top of `build-apk.ps1` if yours differ.

```powershell
powershell -ExecutionPolicy Bypass -File build-apk.ps1
```

The signed APK is written to `dist\iptv.apk`. The first build creates a local signing key in
`build\iptv-release.jks`; keep that file if you want later builds to install as updates.

## Install

```bash
adb connect <device-ip>:5555
adb install -r dist/iptv.apk
```

or run `install-to-tv.bat` on Windows for a guided wireless install.

## Controls

| Where | Remote | Touch | Action |
| --- | --- | --- | --- |
| Lists | OK | Tap | Open or play |
| Lists | Hold OK | Long-press | Favourite, My List, hide or pin a category |
| Anywhere | Back | Back | Up one level; twice on Home to exit |
| Live playback | Up / Down | — | Previous / next channel |
| Live playback | OK | Tap | Channel list |
| Live playback | Left / Right | — | Last channel / options |
| Movie playback | Left / Right | — | Seek (hold to accelerate) |
| Movie playback | OK | Tap | Pause, show controls |

## Project layout

```
android/
  AndroidManifest.xml
  res/                      launcher icon, TV banner, strings
  src/tv/iptv/player/
    MainActivity.java       navigation, Back handling, sync
    RList.java              recycling list/grid that owns focus and eases scroll per frame
    *Screen.java            Home, Live, Browse, Detail, Search, Settings
    PlayerController.java   playback on the platform MediaPlayer, off the UI thread
    Db.java                 SQLite catalogue, favourites, progress
    Xtream.java             Xtream Codes client and library sync
    Img.java, Epg.java      image cache and programme guide
    Qr.java                 QR encoder for phone pairing
build-apk.ps1               aapt2 -> javac -> d8 -> zipalign -> apksigner
```

## Status

Developed and tested on Android TV (Google TV streamer, Android 14). Touch support is new and has
had less testing than remote control.

## License

[MIT](LICENSE)
