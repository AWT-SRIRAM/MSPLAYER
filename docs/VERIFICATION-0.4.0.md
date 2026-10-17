# MS PLAYER 0.4.0 verification report

Date: 2026-10-17 (Asia/Calcutta)
Package: app.msplayer.offline; versionCode 5; min API 26, target API 36.
Engine: LibVLC Android 3.7.7. Phone APK includes ARM64 only.

## Delivered files

- MS-PLAYER-0.4.0-arm64.apk (67,418,100 bytes).
- MS-PLAYER-0.4.0-source.zip (112,126,691 bytes; 67 entries).
- SHA256SUMS-0.4.0.txt.
- Application source: repository root.
- Actual captures and comparisons: docs/images/.
- Machine-readable test/build/signing evidence: local verification bundle (not published).

Source archive CRC and every archived file were checked against the saved source. It excludes build caches, local.properties, APKs, and signing keys.

## Implemented behavior

- Hold unobstructed playing video for 400 ms, then drag left/right to temporarily vary speed from 0.25x to 4x. Releasing restores the exact previous rate; a top-centred translucent scale follows the reference. Ordinary swipes retain seek/level behavior. Pause, interruption, rotation, a second pointer, leaving playback, and PiP cancel temporary speed.
- Right-side vertical swipe controls volume with feedback on the left; left-side swipe controls window brightness with feedback on the right. Slim progress bars show icon and percentage, animate their levels, and fade after release.
- Volume supports 0-200% through Android media level plus LibVLC gain. First upward gesture stops at 100%, second gesture enables boost. Player hardware keys reduce boost first. Focus ducking preserves the chosen gain. Route changes and new playback sessions reset boost. 200% is an engine gain value, not a promise of twice the loudness or unclipped output.
- Audio/subtitle panels use translucent charcoal with a lighter outside scrim. Native video remains visible behind them.
- Subtitle fonts resolve actual Android family mappings. Default/Sans Serif are available; Roboto, Droid Sans, Arial, and Arial Bold depend on installed families. No Arial font is bundled and unavailable faces are disabled. Font changes preserve media, rate, selected tracks, and delays across renderer recreation; ASS/PGS retain authored behavior.
- Fixed pause/reopen races: pause intent is captured before native acknowledgement; late buffering cannot leave paused playback looking busy; Play/Pause issued during renderer recreation updates the pending restore intent.
- Music now has a record/artwork screen, metadata, transport, queue selection, EQ/effects, speed, A-B controls, shuffle/repeat, favourites, sharing, offline lyrics, and library mini-player. Animation stops while paused/backgrounded or system animations are disabled.
- ID3v2.3/v2.4 USLT lyric text and UTF-8 local LRC/text imports are supported. Imported document grants are remembered. Lyrics from other embedded containers are not parsed; they can be supplied through Open local lyrics.
- Saved playlists resolve audio types when reopened. History uses library types or filename inference to retain music playback presentation.
- Video rows use 96x54 dp thumbnails, crop only at display, and retain duration badges; audio rows use compact square placeholders. Extraction tries representative timestamps before provider/native fallback. Successful images have a bounded 48 MiB disk cache keyed by file identity/modification and cache generation. Decoder work stays serialized and suspended during video playback.

## Automated checks

- Debug unit tests: 16 passed; release unit tests: the same 16 passed.
- Complete Android 16 emulator regression run: OK (14 tests): 13 active passed and the explicit two-install upgrade probe was skipped by assumption. See test-040-delivery.log.
- Final targeted gesture/music test: passed, including native forward/slow speed changes, release to previous 0.8x, level gestures, actual native 200 gain, hardware volume decrement, font reopen, panels, real MP3 metadata/USLT lyrics, favourite add/remove, A-B set/reset, shuffle/repeat, queue playback, and mini-player. See test-040-final-controls.log.
- The complete run preceded the final small saved-music classification and thumbnail row-size refinements; the final targeted run and both builds cover the final source. All playback-service/gesture changes were present in the successful full run.
- Existing regression coverage passed AC3/EAC3 audio decoding/output counters, SRT/ASS/PGS tracks, English/Tamil subtitle pixel visibility/placement, delays, audio focus/media session, seek/resume, portrait/rotation metadata/manual override, PiP, native thumbnail fallback, EQ persistence, and idempotent task-removal cleanup.
- Debug and release lint: 0 errors, 40 warnings each. Warnings are retained in the XML reports; this is not a warning-free result.
- Upgrade: archived 0.3.1 emulator APK -> 0.4.0 via install -r passed history, playlists, decoder/resume preferences, and subtitle migration checks. Physical ARM64 upgrade remains untested.
- Final shutdown inspection shows no active app playback service: closed-services.txt contains '(nothing)'.

Earlier verification found renderer pause/restore races and a malformed speed glyph. These were corrected before the passing runs. Initial music-fixture lyric padding was corrected to a valid ID3v2.3 UTF-16 USLT fixture; the application parser then passed with real MP3 playback.

## Signing, alignment, and permissions

- APK v2 signature verifies with the existing certificate.
- Certificate SHA-256: 7ca1657ff7e0ef419150adf179083556f2b68c05441e1bb3b9d84f17716d1faf.
- zipalign -c -P 16 passed.
- All four native libraries have PT_LOAD alignment of at least 16,384 bytes.
- Manifest has no INTERNET permission and release has no debuggable flag. No ads or network service was added.

## Performance and remaining device checks

The generated stress fixture is 3840x2160 at 60 fps, HEVC Main10 plus EAC3, approximately 50.8 Mbps, six seconds, SDR. Four-second sequential headless-emulator samples from the successful regression run:

| Effect | Decoder preference | Decoded video | Displayed | Lost | Decoded audio | Played audio buffers |
|---|---|---:|---:|---:|---:|---:|
| Original | Auto | 258 | 23 | 38 | 329 | 164 |
| Clarity | Auto | 337 | 31 | 101 | 333 | 166 |
| Movie | Auto | 330 | 26 | 100 | 331 | 165 |

The emulator still drops frames. These short sequential samples, with a software-rendered emulator GPU and some concurrent host build activity, are diagnostic output rather than a controlled EQ comparison or proof of hardware decoding. No claim is made that high-bitrate stutter is solved on the phone. No downscaling or colour filter was added.

Pending physical iQOO checks: affected original high-bitrate file, sustained playback and thermal behavior, 4x playback under load, speaker/headphone/Bluetooth gain and clipping, route changes, HDR/Dolby Vision colour, exact font availability, and ARM64 installation.

Captured music/speed comparisons show reference layout against actual application output. Speed/video comparisons use different clips; they do not prove colour parity, identical subtitle cues, or pixel equivalence. SRT/ASS/PGS regression fixtures passed, but final font comfort and identical real-film cue comparisons remain for the phone.

Additional manual checks remain for the Android document-picker import flow, sharing through another application, and reduced-motion behavior on the physical device. A-B marker set/reset is automated; precise repeated audio loop timing is not quantitatively measured.

## Research and provenance

References were inspected from review/review 3.1 and review/review 3.1/new. Community research was limited to one Compose performance article with no comments; it is guidance rather than an MS PLAYER benchmark:
http[local path]

Primary implementation references: Android Compose phases, VideoLAN Android audio boost documentation, bundled LibVLC Java API, and VideoLAN FreeType configuration. No new playback library was adopted.
