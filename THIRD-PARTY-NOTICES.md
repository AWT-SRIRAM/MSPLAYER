# Third-party software and reference assets

## LibVLC Android 3.7.7 — VideoLAN and contributors

Artifact: `org.videolan.android:libvlc-all:3.7.7` from Maven Central.
Published license: GNU Lesser General Public License 2.1 (the source headers allow 2.1 or later where specified).
License text: `app/src/main/assets/licenses/LGPL-2.1.txt`.

Upstream: https://code.videolan.org/videolan/libvlcjni
Published binding sources: https://repo.maven.apache.org/maven2/org/videolan/android/libvlc-all/3.7.7/libvlc-all-3.7.7-sources.jar
Published metadata: https://repo.maven.apache.org/maven2/org/videolan/android/libvlc-all/3.7.7/libvlc-all-3.7.7.pom

The published sources and POM are included in `third_party`. The stable JNI source snapshot identifies version 3.7.7, commit `0b8dc65efb203c86a0476bc337ddd99ecf1c0ef6`; its `buildsystem/get-vlc.sh` pins VLC source `84177b2273abc5c4300234e6e18b55a4d2dd5a03`. Source archives are supplied for that JNI snapshot and pinned VLC base. Build scripts/patches and the VLC contrib directory contain the source recipes for the engine's bundled dependencies. Their individual licenses and copyright notices remain in those sources.

The native binary embeds VLC version 3.0.25 and build changeset `3.0.24-35-gc7e5d68ee5`; the patched native tree may therefore have a different generated commit identity from its unpatched source base. Native rebuilding has not been performed. The binary has not been modified; only the ARM64 ABI is included in the phone APK.

The full application source and Gradle wrapper allow relinking/rebuilding with a replacement compatible LibVLC AAR using `-PlocalVlcAar=...`. The corresponding published binaries remain downloadable from Maven Central; source/build information is included here. Source retrieval and replacement instructions are supplied together with the executable, rather than treating a short license notice as the entire distribution arrangement.

## AndroidX, Compose, Room — Android Open Source Project contributors

Apache License 2.0. Full text: `app/src/main/assets/licenses/Apache-2.0.txt`.
Source: https://android.googlesource.com/platform/frameworks/support/

AndroidX includes its graphics-path native library; this is independently checked in the packaged APK's alignment report.

## Kotlin and kotlinx.coroutines — JetBrains and contributors

Apache License 2.0.
Sources: https://github.com/JetBrains/kotlin and https://github.com/Kotlin/kotlinx.coroutines .

## AndroidLiquidGlass (Backdrop) — Kyant0 and contributors

Artifact: `io.github.kyant0:backdrop:1.0.0` from Maven Central.
License: Apache License 2.0. Full text: `app/src/main/assets/licenses/Apache-2.0.txt`.
Source: https://github.com/Kyant0/AndroidLiquidGlass .

## Reference launcher icon

The user explicitly requested the blue play-button logo from their supplied MX Player reference. The launcher PNG was taken from that supplied APKM's `icon.png`. It is a third-party reference asset, not MS PLAYER's original artwork, and is excluded from the application's own source license grant. The application name, package and signing identity are MS PLAYER's separate identity. Other interface icons are Material icons supplied through AndroidX.

## Test-only fixtures

Public EAC3 and PGS samples came from the FFmpeg sample archive, with provenance recorded in README and the test report. They retain their upstream rights. Locally generated test video/audio and authored SRT/ASS content are test fixtures. None of the test media or FFmpeg command-line binaries is included in the player APK.

## LLVM libc++ runtime

The packaged `libc++_shared.so` is the LLVM C++ runtime supplied with the upstream LibVLC AAR. LLVM Project contributors; Apache License 2.0 with LLVM Exceptions. Full license is included in `third_party/LLVM-libcxx-LICENSE.txt` and the APK assets. Source: https://github.com/llvm/llvm-project/tree/main/libcxx .
