<p align="center">
  <img src="icon.png" width="132" alt="Resound" />
</p>

<h1 align="center">Resound</h1>

<p align="center">
  <strong>A premium, local-first audio editor and multitrack mixer for Android.</strong><br />
  Edit, record, arrange, mix, and export — entirely on-device.
</p>

<p align="center">
  <img alt="Android 8+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white" />
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?logo=kotlin&logoColor=white" />
  <img alt="Jetpack Compose" src="https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4" />
  <img alt="License" src="https://img.shields.io/badge/license-Apache--2.0-blue" />
</p>

<p align="center">
  <a href="https://github.com/MikereDD/Resound/releases">Releases</a> ·
  <a href="CHANGELOG.md">Changelog</a> ·
  <a href="docs/RELEASING.md">Release process</a> ·
  <a href="SECURITY.md">Security</a>
</p>

---

## What is Resound?

Resound is an Android audio workspace built for the jobs that should not require
an account, an upload, or a desktop DAW. Open local audio, record something new,
make precise edits against a decoded waveform, arrange clips on a multitrack
timeline, and export the result directly to `Music/Resound`.

The app is intentionally **local-first**:

- no account
- no advertising
- no cloud requirement
- audio processing stays on the device
- releases are distributed as signed APKs through GitHub Releases

## Workstation interface

Since 0.8.0-dev.13, Resound uses an OLED-black workstation interface: true-black
backgrounds, near-black panels, Steel Blue interactive controls and a cyan
waveform. Editor, Multitrack, Library and Settings share one bottom navigation,
the Editor is waveform-first and Multitrack is timeline-first.

<p align="center">
  <img src="docs/design/Resound-premium-mockup.png" width="920" alt="Earlier Resound 0.8 design concept (not a screenshot of the current interface)" />
</p>

<p align="center"><sub>Earlier 0.8 design concept; the current interface differs.</sub></p>

## Features

### Waveform Editor

- real `MediaCodec`-decoded waveform
- draggable selection with detailed timecode
- live playhead and selection playback
- open audio or supported video through Android's system picker
- reopen exported audio directly from the Library
- Close audio returns to a compact start screen focused on Record and Open

### Audio tools

Resound currently provides eleven FFmpeg-powered operations:

`Trim` · `Mix` · `Concat` · `Fade` · `Volume` · `Speed` · `Pitch` · `EQ` · `Vocal Remove` · `Convert` · `Compress`

Processing progress is shown in-app and completed files are published to the
shared `Music/Resound` collection.

### Recording

- one-tap voice recording with AAC/M4A output
- recordings publish to the Resound output library when you stop
- optional filename with Date, Time and Duration options and a live preview
  (Android 10+; empty means automatic naming)
- Recent Recording shortcut to reopen the last recording
- unfinished recordings are kept as recovery drafts that can be opened,
  saved or discarded

### Multitrack

- multiple independent tracks
- colored clip lanes
- drag clips along the timeline
- visible clip-edge trim handles
- start / duration / end readout for selected clips
- synchronized horizontal scroll and zoom (6–240 px/s), including arrangements
  many hours long
- per-track mute controls
- FFmpeg mixdown export

Not yet available in Multitrack: playback and a moving playhead, Solo, Split,
Duplicate, Delete, undo / redo and track deletion. The interface does not show
controls for them; listen to an arrangement by exporting the mix.

### Library

- browse exports from `Music/Resound`
- view format, duration, size, and modified date
- refresh the collection
- share exported audio
- reopen a file directly in the Editor

### Ringtone support

Loaded audio can be published and set as the Android system ringtone.

### Secure updater

Resound includes a user-initiated sideload updater that follows the
[Typezer∅ Release Standards](https://github.com/MikereDD/Typezero-Release-Standards)
(release manifest schema revision 2). Before Android is allowed to install an
update, Resound verifies:

- HTTPS-only URLs on trusted GitHub hosts, including every redirect
- the release manifest: app, platform, architecture, channel, updater protocol
  and a version that is newer under Typezer∅ numeric version ordering
- exact APK and signature file names, sizes and SHA-256 checksums
- a detached release signature made with the pinned Resound release key
- the Android package name, the announced version, a newer version code and the
  pinned Resound APK signing certificate, matching the installed build

There are no silent updates and no automatic downgrades.

## Project status

**Current development source:** `0.8.0-dev.13` (`versionCode 26`)

The 0.8 line is in development. Development builds are published as GitHub
**pre-releases**; they are tested on a real device, but not every workflow is
validated on every build. See [`CHANGELOG.md`](CHANGELOG.md) for details.

## Releases

Signed APKs are published **only through GitHub Releases**:

**https://github.com/MikereDD/Resound/releases**

APK binaries are intentionally not stored in the Git repository. Each release
APK is attached to the matching GitHub Release together with its SHA-256
checksum, detached release signature, release manifest, release notes and
validation record.

Resound currently targets **arm64-v8a** devices and is intended for sideloading:
download the APK from the release page and allow your browser or file manager
to install unknown apps when Android asks.

### Production and DEV TEST builds

| | Production | DEV TEST |
|---|---|---|
| App name | Resound | Resound DEV TEST |
| Package | `com.typezero.resound` | `com.typezero.resound.devtest` |
| Output folder | `Music/Resound` | `Music/Resound DEV TEST` |
| Signing | release key | Android debug key |
| In-app updater, ringtone | enabled | disabled |
| Published on GitHub Releases | yes | no — local testing only |

The two packages install side by side and never share data. A DEV TEST APK
cannot update or replace a production installation.

## Build from source

### Requirements

- JDK 17
- Android SDK 35
- Gradle 8.9
- AGP 8.7.2
- Kotlin 2.0.21

Build a debug APK with a locally installed Gradle:

```bash
gradle :app:assembleDebug
```

Build the isolated DEV TEST variant instead:

```bash
gradle :app:assembleDevtest
```

Signing configuration for public release APKs is not stored in the repository.

Or generate a local wrapper if preferred:

```bash
gradle wrapper --gradle-version 8.9
./gradlew :app:assembleDebug
```

The Gradle wrapper is intentionally not committed. GitHub Actions installs
Gradle 8.9 explicitly for CI builds.

## Audio engine

Resound talks to FFmpeg through `FFmpegRunner`, implemented by
`FFmpegKitRunner`, using the 16 KB page-size compatible community package:

```kotlin
implementation("com.moizhassan.ffmpeg:ffmpeg-kit-16kb:6.1.1")
```

FFmpeg and the wrapper remain under their own applicable licenses. See
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) before redistributing builds.

## Release and update model

The in-app updater has two channels, both read from the `main` branch:

- **Development** (`releases/development/release-manifest.json`):
  release-signed development builds (`-dev` versions), starting with
  0.8.0-dev.11.
- **Stable** (`releases/update.json`): stable releases. This file is added with
  the first stable release.

DEV TEST builds never check for updates.

Release APKs do **not** live in the repository. A release manifest points to the
signed APK attached to the matching GitHub Release, for example:

```text
https://github.com/MikereDD/Resound/releases/download/v0.8.0/Resound-v0.8.0.apk
```

See [`releases/release-manifest.example.json`](releases/release-manifest.example.json)
for the manifest structure and [`docs/RELEASING.md`](docs/RELEASING.md) for the complete
release workflow.

## Repository history

Resound began inside the `It-Works-On-My-Machine` monorepository. Its
application-specific Git history was extracted into this standalone repository
with `git filter-repo` rather than restarting the project with a synthetic
initial commit.

See [`docs/MONOREPO-MIGRATION.md`](docs/MONOREPO-MIGRATION.md) for details.

## Contributing

Resound is primarily a personal project, but focused bug reports and sensible
pull requests are welcome. See [`CONTRIBUTING.md`](CONTRIBUTING.md).

For vulnerabilities or security-sensitive reports, follow [`SECURITY.md`](SECURITY.md).

## License

Resound's original source is licensed under the **Apache License 2.0**.
Third-party components retain their own licenses and notices.

See [`LICENSE`](LICENSE) and [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

---

<p align="center">
  <strong>Resound</strong><br />
  Built by Typezer∅
</p>
