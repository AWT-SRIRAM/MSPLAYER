# MS PLAYER

Offline-first, ad-free Android multimedia player built with Kotlin, Jetpack Compose, AndroidLiquidGlass, and LibVLC Android 3.7.7. Current version: **0.6.1**, versionCode **8**, package `app.msplayer.offline`.

Primary device target: iQOO Neo10R running Android 16. Minimum Android API 26; compile/target API 36.

## Features

- Local video/audio library, folders, dismissible Recently Added row (per-item remove, clear, and Settings restore), playlists, resume playback, video thumbnails, and seek preview thumbnails.
- Liquid Glass theme (`io.github.kyant0:backdrop:1.0.0`) with live optical refraction, frosted dock pills, Apple iOS-style interactive springs (`iosLiquidPress`), `SharedTransitionLayout` audio mini-player morph, adaptive album-art mood glow, and Android 12+ (`API 31+`) window blur-behind player dialogs (toggleable in Settings).
- Direct one-tap Aspect Ratio cycling (`Best fit` -> `Crop` -> `Stretch` -> `16:9` -> `4:3` -> `Original`) with frosted HUD indicator.
- Network URL streaming (`HTTP`, `HTTPS`, `HLS .m3u8`, `DASH .mpd`, `RTSP`, `RTMP`) with clipboard auto-detection, share-sheet `ACTION_SEND` intake, and persistent Recent Streams history.
- Hardware-first playback with Auto, Hardware, HW+, and Software decoder modes.
- Simultaneous multi-subtitle selection (unlimited 2, 3, 4, 5+ tracks at once), automatic primary subtitle selection on video open, buffered MKV track demuxing (`file://` and `content://`), `.zip`/`.srt` subtitle import, folder sidecar auto-discovery, pixel-locked single-layout Canvas outline/fill rendering (`StrokeView`), and optional top docking for secondary tracks (OpenSubtitles.org cloud search currently paused via feature flag).
- Optional AI Subtitles (Speech-to-Text): automatically prioritizes English audio tracks on dual-audio containers, fast-splits compact AAC/Opus audio without re-encoding or compresses multi-channel/heavy codecs to lightweight 16 kHz / 18 kbps Mono AAC chunks (`<= 10 MB`), streams live progressive subtitle cues from Part 1 while remaining parts run in parallel across comma-separated API keys with backup failover, and saves persistent `.srt` subtitles next to your media.
- Swipe seeking, pinch zoom, left-side brightness and right-side volume gestures, with opposite-side feedback indicators.
- Hold for 400 ms and drag horizontally for temporary 0.25x–4.0x speed using a compact aligned frosted ruler that smoothly morphs into a compact speed status capsule.
- Volume gain up to 200%, audio effects, and manual equalizer.
- Record-style music screen with blurred album-art backdrop, queue, shuffle/repeat, favourites, A-B controls, offline USLT/LRC/text lyrics, and mini-player.
- Picture-in-picture, optional background audio, and explicit resource cleanup on Exit/task removal.

No tracking or advertising services are included (`android.permission.INTERNET` is used only if you trigger AI Subtitles or open a network stream URL). The interface was developed using user-supplied MX Player references; this is an independent implementation and is not affiliated with MX Player.

## Build

Install JDK 17 and Android SDK API 36 with Build Tools 36.0.0. Configure `JAVA_HOME` and `ANDROID_HOME`, or provide an untracked `local.properties` with `sdk.dir=<SDK path>`. Gradle requires Internet access on the build computer to resolve dependencies.

Optional API keys can be entered inside `Settings -> API Keys` on the device, or supplied at build time in your untracked `local.properties` (never committed to git):

```properties
sdk.dir=C:\\path\\to\\Android\\Sdk
MSPLAYER_GROQ_KEY=
MSPLAYER_DEEPGRAM_KEY=
```

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
.\gradlew.bat assembleRelease testReleaseUnitTest lintRelease
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
