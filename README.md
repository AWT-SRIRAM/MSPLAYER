# MS PLAYER

Offline-first, ad-free Android multimedia player built with Kotlin, Jetpack Compose, AndroidLiquidGlass, and LibVLC Android 3.7.7. Current version: **0.6.2**, versionCode **9**, package `app.msplayer.offline`.

By **SRIRAM S & ANTIGRAVITY**.

Primary device target: iQOO Neo10R running Android 16. Minimum Android API 26; compile/target API 36.

## Download APK

- **Latest Signed ARM64 APK (`v0.6.2`, 64.9 MB)**: [**Download `MS-PLAYER-0.6.2-arm64.apk`**](https://github.com/AWT-SRIRAM/MSPLAYER/raw/main/release/MS-PLAYER-0.6.2-arm64.apk) ([view in `release/`](release/MS-PLAYER-0.6.2-arm64.apk))
  - SHA-256: `5fa98e1c9145378761d6af218e430808d2b7ef3f73d729f0af9be72ada615ffb`
- Requires Android 8.0+ (API 26) on ARM64 (`arm64-v8a`).
- The public APK bundles **no preconfigured API keys** — local playback, multi-subtitle tracks, `.srt`/`.zip` subtitles, vertical equalizer & DSP effects, music player, Crystal Liquid Glass UI, gestures, and network streaming work out of the box. For optional Cloud AI Subtitles, Subtitle Translation, and TMDB/Multi-Provider Online Subtitles, add your own free API keys inside `Settings -> API Keys` on the device.

## Features

- Compact media library dashboard with quick-action bar (`Music`, `Playlists`, `History`, `Stream`, `Open`), dismissible `Recently Added` row, folder badges, playlists, resume playback, thumbnails, and seek preview thumbnails.
- Apple iOS-inspired Crystal Liquid Glass theme (`io.github.kyant0:backdrop:1.0.0`) with live optical refraction, frosted popup menus, floating dock pills, `iosLiquidPress` spring feedback, `SharedTransitionLayout` audio mini-player morph, adaptive album-art glow, and API 31+ window blur-behind player sheets & dialogs (toggleable in Settings).
- Online Subtitles engine: automatic Filename/Year/`SxxExx` parsing, **TMDB** movie & series normalization, and concurrent multi-provider search & archive extraction across **OpenSubtitles** (with 64-bit moviehash matching), **SubDL**, **SubSource**, and **Wyzie Subs**.
- Cloud AI Subtitles & Translation: speech-to-text pipeline (`Original speech` and `Speech -> English`) with English track prioritization, lightweight 16 kHz / 18 kbps Mono AAC chunking, live streaming Part-1 preview, ID-locked batch cloud translation (`English` & `Tamil`), and 1-tap `Share .srt` export.
- Multi-subtitle playback (unlimited simultaneous tracks), `.zip`/`.srt` auto-discovery & import, instant post-seek cue sync, `StrokeView` outline/fill rendering, and optional top docking for secondary tracks.
- Temporary 0.25x–4.0x speed capsule (8 stops: `0.25x`, `0.5x`, `1.0x`, `1.5x`, `2.0x`, `2.5x`, `3.0x`, `4.0x`) on 400 ms long-press drag, plus direct speed picker, swipe seeking, pinch zoom, and opposite-side brightness/volume HUDs.
- Studio audio panel with 5-band & 10-band Vertical Equalizer faders, quick preset strip, `Reverb` presets, rotary `Bass Boost` & `Virtualizer` knobs, and 200% volume gain.
- Network URL streaming (`HTTP`, `HTTPS`, `HLS .m3u8`, `DASH .mpd`, `RTSP`, `RTMP`) with clipboard detection, `ACTION_SEND` intake, hardware-first (`Auto` / `HW` / `HW+` / `SW`) decoders, Picture-in-Picture, and background audio.

No tracking or advertising services are included (`android.permission.INTERNET` is used only when triggering Online Subtitles, AI Subtitles / Translation, or streaming a network URL). This is an independent multimedia player implementation and is not affiliated with MX Player or VLC.

## Build

Install JDK 17 and Android SDK API 36 with Build Tools 36.0.0. Configure `JAVA_HOME` and `ANDROID_HOME`, or provide an untracked `local.properties` with `sdk.dir=<SDK path>`. Gradle requires Internet access on the build computer to resolve dependencies.

Optional API keys can be entered inside `Settings -> API Keys` on the device, or supplied for private builds in your untracked `local.properties` (never committed to git):

```properties
sdk.dir=C:\\path\\to\\Android\\Sdk
MSPLAYER_GROQ_KEY=
MSPLAYER_DEEPGRAM_KEY=
MSPLAYER_OPENSUBTITLES_KEY=
MSPLAYER_SUBDL_KEY=
MSPLAYER_SUBSOURCE_KEY=
MSPLAYER_WYZIE_KEY=
MSPLAYER_TMDB_KEY=
```

Pass `-PpublicRelease=true` to strip all `local.properties` API keys from `BuildConfig` when building public APKs.

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

On macOS/Linux, use `./gradlew`. Debug installs as `app.msplayer.offline.debug` (MS PLAYER DEBUG), separately from the everyday app. The test runner refuses the release package. Default native ABI is ARM64; debug APK output is `app/build/outputs/apk/debug/app-debug.apk`.

Pinned tooling: Gradle 8.11.1, AGP 8.10.1, Kotlin/Compose compiler 2.2.21, `io.github.kyant0:backdrop:1.0.0`. Gradle wrapper and distribution checksum are included.

### Android tests

Use an Android device or emulator. For an x86_64 emulator:

```powershell
.\gradlew.bat connectedDebugAndroidTest -PtestAbi=x86_64
```

Omit `-PtestAbi` for an ARM64 device. Instrumentation fixtures live in `app/src/androidTest/assets`; they are not packaged in the ordinary player APK. The explicit two-install upgrade test skips unless its phase argument is supplied; see [release history](docs/RELEASE-HISTORY.md) and [verification report](docs/VERIFICATION-0.4.0.md).

### Signed release

Supply your own signing key (alias `msplayer`) through `MSPLAYER_KEYSTORE` and `MSPLAYER_STORE_PASSWORD`, then run:

```powershell
.\gradlew.bat assembleRelease testReleaseUnitTest lintRelease -PpublicRelease=true
```

The original signing key is excluded. An independently signed APK cannot update an installation signed with another certificate.

## Verification and limits

Version 0.5.0 adds continuous subtitle size/scale, exact migration of old defaults, a transactional Text dialog, encoding selection, native-inset compensation, and placement against the measured timeline boundary. ASS/PGS stay native. See [0.5.0 verification](docs/VERIFICATION-0.5.0.md) for phone/emulator results and measured residuals.

The controlled iQOO comparison meets the approximate cap-height and bottom-clearance targets; native bold weight and multiline pitch still differ from MX. This is a review build, without a claim of pixel parity. Subtitle gestures/folder selection remain scheduled for 0.5.1; cue scroll and fade are deferred. The native surface alpha probe had no effect.

The [0.4.0 verification](docs/VERIFICATION-0.4.0.md) remains historical evidence. Sustained high-bitrate performance, HDR/Dolby Vision colour and audio-route comfort are not established by this subtitle update. AC3/EAC3 decoding does not imply Dolby Atmos certification or surround passthrough.

Arial/Droid font choices require actual device-installed families. Another app's private Android `IS_PENDING` downloads remain inaccessible; incomplete files may not play or seek. Exact MX thumbnail frames and pixel parity are not established.

## Reference comparisons

These are layout comparisons using user-supplied reference captures. The speed comparison uses different clips and does not establish colour/subtitle parity.

![Music interface comparison](docs/images/comparison-music.png)

![Temporary speed comparison](docs/images/comparison-temporary-speed.png)

## Source and licensing

Application code is provided under [MIT](LICENSE), subject to the exclusions and dependency terms in [third-party notices](THIRD-PARTY-NOTICES.md). Engine source snapshots and licence texts are retained in `third_party` and `app/src/main/assets/licenses`.

The existing launcher artwork, reference screenshots, and upstream test media retain their respective third-party rights and are not covered by the application's MIT grant. See the notices and [release history](docs/RELEASE-HISTORY.md) for provenance and LibVLC replacement/relinking instructions.

[0.5.0 source provenance](docs/SOURCE-PROVENANCE-0.5.0.json) · [0.4.0 provenance](docs/SOURCE-PROVENANCE.json)
