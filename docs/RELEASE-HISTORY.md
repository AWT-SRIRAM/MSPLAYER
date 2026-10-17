# MS PLAYER 0.4.0

Offline, ad-free Android media player for iQOO Neo10R / Android 16. Package app.msplayer.offline; versionCode 5. LibVLC Android 3.7.7 is retained.

## 0.4.0 controls and appearance

- Hold the video for 400 ms, then drag left/right to temporarily slow/speed playback (0.25x-4x). Release restores the previous speed. Ordinary swipes still seek or adjust levels; interruption, leaving playback, a second finger, and rotation restore temporary speed.
- Right-side vertical swipe controls volume with a left indicator; left-side swipe controls window brightness with a right indicator. Up increases, down decreases.
- Volume supports 0-200% through system media volume plus LibVLC gain. The first upward swipe stops at 100%; a second swipe enters boost. Hardware volume-down reduces boost first. New sessions and output-route changes reset boost. Amplification may clip and does not promise twice the perceived loudness.
- Audio/subtitle panels use translucent charcoal backgrounds. Subtitle appearance supports actual Android font families; Arial and Droid choices are unavailable when the device does not provide them. Sans Serif/Default remain usable with native glyph fallback; ASS/PGS retain authored rendering.
- Music has a record/artwork screen, title/artist, transport, queue, repeat/shuffle, favourites (Favorites playlist), A-B repeat, speed, effects/EQ, local sharing, and a library mini-player. Animation stops when paused/backgrounded or system animations are disabled.
- Offline lyrics support bounded ID3v2.3/v2.4 USLT text extraction and user-imported UTF-8 LRC/text. Local file grants are remembered. Embedded lyrics in other containers are not currently parsed; use Open local lyrics. No lyrics network service is used.
- Video thumbnails use representative timestamps before provider/native fallbacks, preserve extraction proportions, and cache successes in a bounded 48 MiB disk cache. Refresh changes cache generation. Exact MX frame selection is not established.
- Pausing now updates the requested playback state before subtitle renderer recreation and ignores late buffering events while paused.

## Verification

See ../TEST-RESULTS-0.4.0.md for actual test outcomes, comparison captures, hardware limitations, and pending device checks. Build/signing commands and prior release history follow below.

## 0.3.1 playback repair update

- Timeline taps and drags commit the current touch value and retain the requested position while the native engine seeks. The timeline is cleared on media changes. Real touch tests verify native forward/backward movement rather than only checking the displayed timestamp.
- Fast seeking is enabled by default for keyframe-friendly HEVC navigation. Player Options can disable it for precise seeking. Keyframe spacing determines the actual landing timestamp; a fast seek is not frame-accurate.
- Clarity now uses a subtractive curve with 0 dB preamp and unity gain in the speech/presence bands, avoiding the prior broad 3 dB reduction. Original still bypasses EQ; custom bands are retained.
- Playback starts without an extra metadata demux. Thumbnail/metadata/library background work remains suspended while an attached video is paused as well as playing. Rotation normally uses native track metadata, with a delayed metadata fallback only for unavailable dimensions.
- Temporary names (.pending prefix or .pending/.part/.crdownload suffix) retain recognizable media extensions. SAF folder scans can inspect bounded media signatures for extensionless temporary files; Open a file accepts these too. Unknown binary content can be opened explicitly from another app.
- Android IS_PENDING is a distinct ownership/access restriction. This update cannot reveal another app's private in-progress MediaStore entries. Use a granted file/folder or wait for the downloader to publish the file. Incomplete/non-seekable containers may not play or seek; errors are bounded and the app does not rename, finalize, or modify downloads.
- Package and signing identity are unchanged; versionCode is 4. Physical-device high-bitrate smoothness and Clarity listening checks are pending until tested on the iQOO with an affected file.

# MS PLAYER 0.3.1

Independent, offline Android multimedia player. Primary target: iQOO Neo 10R running Android 16. Package: `app.msplayer.offline`. Updates retain the existing package and signing identity.

## Build

Install JDK 17 and the Android SDK (API 36, Build Tools 35.0.0 or newer). Set `JAVA_HOME` and `ANDROID_HOME` or create an untracked `local.properties` containing `sdk.dir=<SDK path>`.

From this directory:

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

The default build includes ARM64 native libraries only. The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`. Internet access is required by Gradle on the build computer to resolve dependencies; the Android application itself has no internet permission.

Pinned build dependencies: AGP 8.10.1, Gradle 8.11.1 with the official distribution SHA256, Kotlin/Compose compiler 2.1.20, Compose BOM 2025.05.01, LibVLC Android 3.7.7, Room 2.7.1. JDK 17; compile/target API 36; minimum API 26. These versions are pinned as a resolved build combination rather than following every newly published release.

For a signed release, create your own keystore using alias `msplayer`, then set:

```powershell
$env:MSPLAYER_KEYSTORE = 'C:\path\to\your-keystore.jks'
$env:MSPLAYER_STORE_PASSWORD = '<your password>'
.\gradlew.bat assembleRelease
```

Without these variables, `assembleRelease` produces an unsigned release APK. The supplied signed APK uses a local development certificate and has debugging disabled. Its signing key is kept outside the source archive. A newly signed build with a different certificate requires uninstalling the existing package, which clears app history/settings.

## Install

Transfer `MS-PLAYER-0.3.0-arm64.apk` to your phone and open it in your file manager. Allow installation from that file manager if Android requests it. MS PLAYER installs as a separate app alongside MX Player.

Alternatively:

```powershell
adb install -r MS-PLAYER-0.3.0-arm64.apk
```

On first launch, choose **Allow media access**, **Open a file**, or **Choose a folder**. Android may grant full video access, selected-video access, or no library access. File selection remains available. Music access is requested separately by Android within the combined permission request.

## 0.3.0 update

- Video-metadata orientation, including rotation and sample aspect ratio; manual rotation lasts for the current item.
- Smaller dashboard folders and typography; right-side audio/subtitle panels in landscape.
- Lighter subtitle styling, normalized bottom clearance, and independent subtitle-surface movement above visible controls. Native Android output is preferred with a GLES fallback; selecting a subtitle track refreshes the current cue. ASS/PGS keep authored positioning. Unchanged 0.2.0 defaults migrate; custom native margins are preserved.
- Six audio presets and custom ten-band EQ with conservative preamp headroom; Original bypasses EQ. Connected outputs are listed, without claiming that they prove the active route.
- Serialized thumbnail extraction with provider, representative-frame, and offscreen LibVLC fallbacks. Cache identity includes modification time; Rescan retries failures.
- Timeline subscriptions isolated from dashboard metadata. Nonessential probing waits while video plays.
- Exit and task removal stop the native player, remove its notification, release audio focus/resources, and preserve position. Home retains PiP/background behaviour.
- Options → Record playback diagnostics collects a bounded local sample buffer; Save local diagnostic report exports it through Android's file picker. Sampling is off by default. Frame statistics diagnose stutter; they do not prove a hardware codec is active.

## Features

- Black/cyan local folder and video screens, thumbnails, duration, size/resolution and parsed subtitle badges.
- Search, sorting, list/grid switching, long-press selection, queues, playlists and recent history.
- Long-press a folder to exclude it; restore excluded folders in Settings.
- LibVLC playback, hardware preference/software option and a bounded software recovery attempt.
- Multiple audio/subtitle tracks, external subtitles, audio/subtitle delay and playback speed.
- Swipe seeking, left brightness/right volume gestures, double-tap seeking, pinch zoom, aspect modes, screen lock and rotation.
- Graphical edge indicators for brightness and volume, target-time feedback for seeking, and compact zoom feedback.
- Subtitle appearance controls for plain text: size, bold, outline, shadow, and bottom spacing. Changes reopen the current item at its saved playback position.
- Android adaptive launcher icon with a blue background and white play symbol.
- Resume positions and remembered audio/subtitle selections. Settings use private SharedPreferences; history/playlists use Room.
- Background audio, media-session/notification controls, audio focus and picture-in-picture.
- Android MediaStore/SAF access and external `content://` opening.

All library and player processing is on the device. There are no ads, accounts or required servers, and no internet, camera, microphone, location or all-files-access permissions. The app writes its private history, settings, caches and selected-subtitle copies; it has no media deletion action.

## Tests

The integration suite includes a pixel-level caption visibility/placement check and a generated 3840×2160/60 HEVC Main10 + EAC3 clip (~50.8 Mbps). Emulator frame counters are diagnostic evidence, not an iQOO performance guarantee.

`UpgradeVerificationTest` is skipped in a normal run. To reproduce the upgrade check, install 0.3.0 and run its method using the 0.3.1 test runner with `-e upgradePhase seed`; install 0.3.1 with `adb install -r`, launch it once, and rerun with `-e upgradePhase verify`.

Run host checks:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug
```

For an Android 16 x86_64 emulator:

```powershell
.\gradlew.bat connectedDebugAndroidTest -PtestAbi=x86_64
```

That property creates a separate emulator ABI build. Never distribute its x86_64 APK as the phone's ARM64 APK. Rebuild without `testAbi` for ARM64.

Instrumentation tests exercise native audio decoding/output counters, seeking, speed, delay, resume, media-session controls, audio focus, multiple tracks, Room playlists, content URIs, PGS metadata, rotation and PiP. Check `../TEST-RESULTS.md` for results actually completed; implementation alone is not evidence of successful playback.

The EAC3 fixture comes from https://samples.ffmpeg.org/A-codecs/AC3/eac3/sample-eac3.mkv . The original excerpt has stale container duration/seek metadata. It was remuxed and repeated with stream copying (no audio re-encoding) to create a valid seek/resume fixture. PGS sample: https://samples.ffmpeg.org/sub/PGS/supsample.mkv . The multitrack color-bar/tone fixture was generated locally with FFmpeg and contains H.264, AC3, EAC3, SRT and ASS streams. Test media is in the instrumentation APK only, not the installable player.

## Known scope limits

This is an initial implementation, not a claim of complete observable parity with MX Player. Menus, icon spacing and some interaction details still differ. Folder badges count files absent from playback history; their exact MX Player rule was not established. Streaming, games, cleaner, status saving, private vault and file transfer are outside this version.

ASS/SSA authored styling is rendered by LibVLC. Custom text-font/color controls and MX-specific subtitle gestures are not implemented. PGS remains bitmap content. Decoder labels show the chosen preference, not a verified native hardware decoder name. Non-seekable document providers may not permit seeking. The selected-folder scan is bounded to 20,000 media entries/10,000 visited directories; a full 10,000-file performance benchmark and files over 4 GB remain pending unless recorded in the test report.

The physical iQOO has not been tested here. Dolby Atmos rendering, Dolby Vision/HDR output, surround passthrough, battery/thermal behavior and Bluetooth routes require separate device/output verification. AC3/EAC3 audio decoding does not establish those capabilities.

## Source and library replacement

See `THIRD-PARTY-NOTICES.md`, the license texts bundled under `app/src/main/assets/licenses`, and the upstream sources in `third_party/`.

To replace LibVLC with a compatible independently built AAR:

```powershell
.\gradlew.bat assembleDebug -PlocalVlcAar=C:\path\to\libvlc-all.aar
```

The project does not restrict library modification/relinking. The upstream JNI source includes native build scripts, patches and pinned VLC/contrib source recipes; native rebuilding normally requires a suitable Linux environment. No native source-build reproducibility claim is made until that build has been run.

Reference research: MX Player's official description of HW/HW+ is https://support.mxplayer.in/support/solutions/articles/43000608821-what-are-the-differences-between-hw-and-hw- . MS PLAYER uses LibVLC and Android hardware-decoding support; the MX proprietary framework is not part of this application.
