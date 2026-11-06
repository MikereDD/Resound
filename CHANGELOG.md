# Changelog

## 0.8.0-dev.11 — Development update channel

- Advance the development build to versionCode 24.
- Add a development update channel: release-signed `-dev` builds check `releases/update-dev.json`; stable builds keep `releases/update.json`. DEV TEST builds never check for updates.
- Fix update downloads from GitHub Releases: follow up to five redirects, each only to an explicitly trusted HTTPS host (adds `release-assets.githubusercontent.com`).
- Require the downloaded APK to be `com.typezero.resound`, to carry the manifest's version code and be newer than the installed build, and to be signed only with the Resound release certificate.
- Add focused host tests for channels, trusted redirects and update identity.

## 0.8.0-dev.10 — Close audio, compact Editor and filename options

- Advance the development build to versionCode 23.

### Filename options

- Simplify the optional naming panel: a filename field (empty = automatic), a collapsible Filename options section with Date, Time and Duration checkboxes (plus Trim range on Editor exports), a live preview with labelled example values, and Use automatic naming. Checkboxes add or remove the existing tokens without duplicating them or discarding typed text, and follow manually typed tokens.
- Remove the Format option. A trailing `$FORMAT` in saved patterns is no longer repeated before the real file extension (`take_$FORMAT` saves as `take.m4a`); saved patterns are kept unchanged.

### Compact empty Editor (P3)

- With no audio loaded, hide the waveform, transport, tools and export panels and show full-width Record and Open audio actions, keeping status, recording naming, Recent Recording and recovery cards. Loading audio restores the full workspace; Close audio returns to the compact screen.

### Initial publication naming (P2)

- Add optional collapsed recording-name and export-new-copy panels on API 29+. Empty patterns keep the existing automatic timestamp/random naming; recovery Save and API 26–28 retain automatic naming and the existing legacy publication path.
- Share literal/token validation and preview for `$DATE`, `$TIME`, `$DURATION`, `$FORMAT` and real Trim-only `$TRIM` ranges. Freeze recording choices/local time before capture and export choices/time before processing. Resolve duration from completed output metadata; unavailable/invalid values fall back nonfatally to automatic naming. Convert previews follow the selected output extension.
- Persist recording/export preferences with platform SharedPreferences on serialized application-owned IO; preference loading never blocks Record or Stop. No rename, relocation, draft/receipt schema, capture-format or navigation changes.
- Serialize participating Resound API 29+ publications from insert through write/finalization/readback or failure cleanup. This is a Resound-controlled concurrency contract, not exclusion against other applications/providers. Read the provider-assigned filename/path after finalization; metadata-read failure keeps published audio, reports an unknown filename and cannot substitute a requested name into Recent. Original-byte receipt verification remains independent.
- Add focused host naming, preference ordering/restart and bounded production-gate regressions. Android/Compose/provider behavior remains unverified without device testing; no version bump, APK or device work.

### Close audio (P1)

- Add a labeled Close audio action for a loaded, idle Editor source. Reserve the Editor against recording/publication/other busy work, revoke preview readiness, and await the existing owned playback retirement before clearing source, waveform, selection and playhead. Successful close returns to the top with Open, Record and any Recent shortcut available.
- Preserve published audio, recovery drafts, receipts and Recent references. Cancellation, retirement timeout or native cleanup failure must not clear the loaded source; failures keep readable inline feedback. No native ownership, recovery, capture or navigation architecture changes.
- Consume Library open requests once at load admission using request identities. Accepted requests cannot replay after Close or tab navigation; stale acknowledgements cannot consume a newer same-file selection. Admission is not a claim of decode/playback success.
- Add focused host regressions for busy guards, retirement ordering/failure/cancellation, existing owned native release, reopening after retirement and one-shot/stale Library requests. Compose layout, provider behavior and interactive audio/device validation remain unverified; no version bump or APK/device work.

### Unavailable Recent Recording feedback

- Keep failed Recent Reopen feedback on the card as a readable “File unavailable” state with moved/deleted/access guidance, Retry and Clear Recent instead of leaving an unchanged Reopen action. The notice remains until explicit success, clearing, or a new successful recording during the current app process; no background scan or media-health claim is added.
- Clear Recent removes only the app-owned recent reference on IO, retaining all audio and recovery drafts. Clearing persists across restart; stale callbacks and older in-flight metadata cannot restore the cleared shortcut. Failed removal keeps the card with readable retry feedback.
- Add focused host regressions for missing/denied references, successful retry, reference-only clearing/restart, stale work, cancellation and clear failure. No recording/publication/recovery format changes; device validation remains pending.


## 0.8.0-dev.9 — Recent Recording

- Advance the development build to versionCode 22 for controlled validation using the existing isolated DEV TEST package.

- Remember the published location of the last successfully stopped and verified recording in a small app-private atomic reference. Failed/cancelled or untracked publication keeps the previous reference; a shortcut-write failure is reported separately from successful audio publication.
- Show a Recent Recording card when the Editor is idle with no source loaded, with completed date/time, name and known duration/format/channel details. Reopen uses the existing metadata, waveform and preview pipeline; unavailable media keeps its reference for retry and never earns a false success from path existence alone.
- Keep Recent Recording separate from Unsaved Recordings recovery. No audio duplicates, format changes, deletion, migration, continuous scan or automatic overwrite. Omit illustrative waveforms and defer History because the current Library lists all exports without a recording-history filter.
- Derive Editor, Settings, Library and About destination labels from the existing variant output folder without changing actual publication paths.
- Add focused host regressions for persistence/restart, publication outcomes, cancellation/ordering, reference bounds, Reopen readiness and production/devtest destination labels. Interactive recording, provider/metadata and Reopen workflows require separate manual device validation.

## 0.8.0-dev.8 — recording-recovery health

- Advance the development build to versionCode 21 for controlled validation using the existing isolated DEV TEST package.

- Label recovery drafts Unverified until checked. Explicit Open/Save recheck an admitted, read-only local file on IO for an audio track, positive duration and a bounded first encoded sample; metadata/sample inspection alone never claims playback or full-file integrity.
- Keep apparently damaged/incomplete drafts and the current Editor source when Open finds no usable media. Only a ready preview with a real, nonempty decoded waveform earns “Playable preview — last check”; readiness/preparation failure or later player failure revokes that label.
- Keep original-byte Save available for nonempty damaged/unverified drafts, with a playback warning and the existing verified-publication duplicate guard. Uncertain read cleanup protects the recovery lease before cancellation/dispatcher return and skips preview/publication work.
- Add focused host JUnit policy/store/admission regressions. Android extractor/codec behavior and device playback/lifecycle validation remain unverified; no repair, format switch, background scan or draft-schema change.

## Unreleased — player / waveform readiness (Phase 1)

- Bind Editor Play and Seek to the prepared source, invalidating old playback only after valid metadata is accepted. Preparation failure keeps the new metadata/real waveform and reports Playback unavailable.
- Retire native preview players on an owned IO worker outside the readiness monitor, releasing directly without an invalid pause of prepared or failed players. Source adoption waits cooperatively up to 5 seconds for retirement; cancellation, timeout or cleanup failure keeps the prior identity unavailable instead of displaying a new source over old playback. Disposal revokes immediately while late preparation/cleanup remains owned.
- Keep waveform availability independent of playback; decode failures no longer manufacture synthetic amplitudes. Synthetic development previews carry provenance.
- Register codec ownership before configure/start, clean resources independently, propagate cancellation, and bound cooperative decode waiting (5 seconds without progress / 120 seconds overall). Native vendor calls cannot be hard-preempted.
- Add focused host regressions for source switching, readiness, native transport errors, cancellation, ownership, PCM folding and bounded waits. Device playback/lifecycle validation is not claimed.

## Unreleased — recording-draft safety

- Retain original recording drafts during Editor startup, disposal and source switching; receipts no longer authorize automatic deletion.
- Verify finalized, readable, byte-identical publication copies before tracking or hiding a duplicate. Malformed, missing, denied or unverifiable destinations keep drafts recoverable.
- Add host regressions for cold-owner recovery, verification failures, receipt bounds and retained publication originals. Android provider/device verification remains pending review and explicit authorization.

## 0.8.0-dev.6

- Prepared the standalone Resound repository for its GitHub mirror.
- Replaced active monorepo links with the standalone `MikereDD/Resound` repository.
- Added repository license, security/contribution docs, release documentation, issue templates, and Android CI.
- Added production/example updater manifests and pointed future update checks at the standalone repository.

- Rebuilt Settings as a complete premium application-information workspace.
- Consolidated updater, About, changelog, export destination, processing, and privacy details.
- Added a visible development-channel/version badge and consistent action-row treatment.
- Removed the unfinished workspace placeholder and aligned public attribution with Typezer∅.
- Advanced the development build to versionCode 19.

## 0.8.0-dev.5

- Replaced the Library placeholder with a live Music/Resound export browser.
- Added export metadata, refresh, sharing, and polished empty/loading/permission states.
- Added one-tap Library-to-Editor handoff for reopening exported audio.
- Advanced the development build to versionCode 18.

## 0.8.0-dev.4

- Added visible trim handles and stronger selected-clip feedback in Multitrack.
- Added live start, duration, and end readouts for the selected clip.
- Enlarged primary Multitrack controls to prevent text clipping.
- Made Settings scroll safely and removed the redundant clipped updater button.

## 0.8.0-dev.3

- Rebuilt Multitrack as a premium studio workspace.
- Added a shared zoomable time ruler and synchronized track lanes.
- Added compact track controls, colored track identity, richer clip cards, and a stronger mixdown action.
- Added polished empty, status, and export states while preserving clip move and trim behavior.

## 0.8.0-dev.2

- Rebuild the Editor as a premium studio workspace based on the approved mockup.
- Add file-source card with metadata, open, and recording controls.
- Add dedicated waveform stage, selection summary, detailed timecode, and transport controls.
- Replace the legacy text button wall with consistent icon-based tool tiles.
- Add refined status and Music/Resound output cards.
- Remove redundant Editor About and Multitrack controls now handled by bottom navigation.
- Fix duplicated top system-inset spacing in the Editor.


## 0.8.0-dev.1 — Premium app shell

- Added a persistent premium bottom navigation shell for Editor, Multitrack, Library, and Settings.
- Added reusable bordered `ResoundCard` surfaces and expanded the darkroom-for-sound design tokens.
- Added a Library foundation screen for future recent exports and project history.
- Added a dedicated Settings screen with updater, About, changelog, and future workspace controls.
- Preserved the existing Editor and Multitrack workflows while moving navigation responsibility to the app shell.
- Added the approved premium UI mockup under `docs/design/` as the implementation reference.

## [0.7.0] - 2026-07-27

### Added
- Secure, user-initiated in-app updater accessible from About.
- HTTPS-only update manifest, SHA-256 verification, package-name validation,
  and signing-certificate matching against the installed app before install.
- Download progress, Android unknown-sources permission flow, FileProvider-based
  APK handoff, and release manifest examples under `releases/`.

### Changed
- Version advanced to 0.7.0 (`versionCode` 13).
- Added the network and package-install permissions required by the updater.

### Audit notes
- See `AUDIT.md` for fixed findings and deferred architectural work.

All notable changes to Resound are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.6.2] - 2026-06-21

### Added
- **About dialog**: an "About" button in the editor header opens app version
  (read from PackageInfo), a short description, links to the repo and changelog,
  and FFmpegKit (LGPL) attribution.

[0.6.2]: https://github.com/MikereDD/It-Works-On-My-Machine/tree/main/Android/Resound

## [0.6.0] - 2026-06-21

### Added
- **Visual identity**: a "darkroom for sound" theme — deep ink background, a
  single signal-teal accent pulled from the waveform, amber reserved for the live
  record state, monospace timecodes. Replaces the default Material palette.
- **Filled waveform**: the editor waveform now renders as a filled, mirrored
  shape with a vertical teal gradient, plus grip handles and a brighter playhead.
- **Launcher icon**: adaptive + legacy PNG icons (teal waveform mark on ink)
  across all densities; dark window background removes the white launch flash.
- **Per-clip trim** in the timeline: drag a clip's left/right edge to set its
  source in/out (mix uses atrim); drag the body to move it.
- **Zoom & horizontal scroll** in the timeline: −/+ zoom changes the time scale;
  lanes share one synchronized horizontal scroll.

### Changed
- Editor restyled: scrollable layout, transport row (Open / Record / Play) with
  even widths, waveform in a panel, tonal op grid, secondary actions (Ringtone /
  Multitrack) grouped. Timeline lanes are now cards.
- `Clip` gains `sourceInMs`/`sourceOutMs`; `Effects.mixTimeline` trims each clip
  before delaying and mixing.

[0.6.0]: https://github.com/MikereDD/It-Works-On-My-Machine/tree/main/Android/Resound

## [0.5.0] - 2026-06-21

### Added
- **Multitrack timeline** (`feature/timeline`): a second screen reachable via the
  "Multitrack ▸" button. Add tracks, add clips from files (each decoded to its
  own waveform), drag clips horizontally to position them on a shared time axis,
  and mute tracks.
- **Mixdown export**: `Effects.mixTimeline` delays each clip to its start
  (adelay), applies track volume, and amix'es them (normalize=0) into a single
  file published to Music/Resound.
- `Timeline`/`Track`/`Clip` model; `TimelineScreen` UI; screen navigation in
  `MainActivity` between the editor and the timeline.

### Notes
- Clips span their full source; per-clip trimming within the timeline, plus
  horizontal zoom/scroll, are later refinements. The time axis currently fits
  the project (min 60s) to screen width.

[0.5.0]: https://github.com/MikereDD/It-Works-On-My-Machine/tree/main/Android/Resound

## [0.4.1] - 2026-06-21

### Added
- In-app playback: a Play/Pause button previews the loaded file. Playback runs
  from the current selection's start and stops at its end, and the waveform
  playhead tracks the position live. `AudioPlayer` wraps MediaPlayer; the player
  is prepared on open/record and released with the screen.

[0.4.1]: https://github.com/MikereDD/It-Works-On-My-Machine/tree/main/Android/Resound

## [0.4.0] - 2026-06-21

### Added
- **Voice recorder**: a Record/Stop button (with elapsed timer) captures from
  the mic via MediaRecorder (AAC/m4a). On stop the recording loads straight into
  the editor and a copy is saved to Music/Resound. Requests RECORD_AUDIO at
  first use.
- **Set as Ringtone**: sets the loaded file as the default ringtone. Inserts a
  copy into the MediaStore Ringtones collection and points the system default at
  it. Routes the user to the WRITE_SETTINGS grant screen when needed.
- `AndroidRecorder` (MediaRecorder) and `Ringtones` helper.

### Changed
- `Outputs.publishToMusic` returns `Published(uri, displayPath)` so callers can
  reuse the saved URI (recorder, ringtone).

[0.4.0]: https://github.com/MikereDD/It-Works-On-My-Machine/tree/main/Android/Resound

## [0.3.2] - 2026-06-21

### Changed
- Outputs now save to the shared **Music/Resound** library instead of the
  app-private folder — browsable in any file manager, survives uninstall.
  FFmpeg writes to a cache temp file, then it's published via MediaStore
  (`RELATIVE_PATH = Music/Resound`) on API 29+, or written to the public Music
  dir on API 26-28.
- Status line now reports the save location ("Saved to Music/Resound/…").

### Added
- `WRITE_EXTERNAL_STORAGE` (maxSdk 28) + a one-time runtime request for the
  legacy publish path; 29+ needs no permission.

[0.3.2]: https://github.com/MikereDD/It-Works-On-My-Machine/tree/main/Android/Resound

## [0.3.1] - 2026-06-21

### Changed
- FFmpeg now resolves from Maven Central instead of a vendored AAR:
  `com.moizhassan.ffmpeg:ffmpeg-kit-16kb:6.1.1` — a community ffmpeg-kit
  republish rebuilt for the 16KB page size Android 15 / API 35 requires (the old
  4KB-aligned 6.0 binaries SIGBUS on modern devices). Keeps the
  `com.arthenica.ffmpegkit` API; pulls smart-exception transitively.
- Removed the vendoring machinery: `flatDir` repo, `app/libs/`, the
  `BuildConfig.HAS_FFMPEG` opt-in flag, the reflective engine load, and the
  opt-in `src/ffmpeg/kotlin` source set. `FFmpegKitRunner` is now always
  compiled and selected directly.

[0.3.1]: https://github.com/MikereDD/It-Works-On-My-Machine/tree/main/Android/Resound

## [0.3.0] - 2026-06-21

### Added
- All remaining actions wired through a shared run path (resolve -> Effects ->
  FFmpegRunner -> save + media scan):
  - Immediate: **Vocal Remove** (center-channel).
  - Parameter dialogs (`OpControls`): **Volume**, **Speed**, **Pitch**,
    **Fade**, **EQ** (single band), **Convert** (format picker),
    **Compress** (bitrate picker).
  - Second-file pickers: **Mix**, **Concat**.
- `EditOp` enum + `OpParams` + `OpDialog` (sliders / choice chips) for parameter
  collection.

### Changed
- Editor action buttons are all enabled once a file is loaded; `runSingle` /
  `runDual` replace the Trim-only handler.

### Notes
- Effects apply to the whole file except Trim (which uses the selection);
  per-selection application of other effects is a later refinement.
- Real FFmpeg AAR still required for any of these to actually execute; without
  it the stub reports the engine is missing.

[0.3.0]: https://github.com/MikereDD/It-Works-On-My-Machine/tree/main/Android/Resound

## [0.2.1] - 2026-06-21

### Added
- `MediaCodecWaveformExtractor` — real PCM decode (MediaExtractor + MediaCodec)
  folded into min/max peak buckets as it streams, so the waveform reflects the
  actual audio. Handles 16-bit and float PCM; collapses channels by per-frame
  peak. Falls back to the synthesised shape if decoding fails.
- "Decoding waveform…" status while extraction runs.

### Changed
- DI now provides `MediaCodecWaveformExtractor` (needs app `Context`, passed
  from `ResoundApp`) instead of the placeholder extractor.

### Notes
- Full-file decode; a few-minute track takes ~1–2s. A seek-sampled fast path is
  a possible later optimisation.

[0.2.1]: https://github.com/MikereDD/It-Works-On-My-Machine/tree/main/Android/Resound

## [0.2.0] - 2026-06-21

### Added
- Real SAF file picker (`OpenDocument`, audio/* + video/*); loads source
  metadata (duration, sample rate, channels) into `AudioFile` via
  `MediaExtractor`.
- `AudioFiles.resolveToCache()` — copies a `content://` source into cacheDir so
  FFmpeg has a real path to read.
- `core/io/Outputs` — output path in the app's external Music dir plus a
  `MediaScanner` pass so results show up in media apps.
- `FFmpegKitRunner` — real `FFmpegRunner` over the ffmpeg-kit wrapper API, with
  completion → result and statistics → progress mapping, and cancellation.
- First end-to-end action: **Trim** → `Effects.trimEncode` → FFmpeg → save +
  scan, with live progress and a status line.
- `flatDir` repo + vendored-AAR wiring; `app/libs/README.md` documents how to
  obtain/build `ffmpeg-kit-audio.aar` (Maven binaries are gone post-retirement).
- kotlinx-coroutines-android dependency made explicit.
- FFmpeg backend made opt-in: the build is green without the AAR (falls back to
  `StubFFmpegRunner`); dropping `ffmpeg-kit-audio.aar` into `app/libs/` compiles
  in `FFmpegKitRunner` (opt-in source dir) and auto-selects it via
  `BuildConfig.HAS_FFMPEG`.

### Changed
- DI now defaults to `FFmpegKitRunner` (requires the AAR in `app/libs/`; swap to
  `StubFFmpegRunner` to run the UI without it).
- Editor starts empty with an "Open file" action instead of a demo track.

### Fixed
- Rotating the device no longer drops the loaded file back to the Open screen
  (activity now handles config changes instead of recreating).
- Dragging one selection handle no longer snaps the other back to its start —
  the drag gesture was reading stale selection values captured by `pointerInput`
  (fixed with `rememberUpdatedState`).

### Notes
- Waveform shape is still the synthesised placeholder; only its length reflects
  the real file. Real `MediaCodec` decode is planned for v0.2.1.
- Buttons other than Trim remain TODO.

[0.2.0]: https://github.com/MikereDD/It-Works-On-My-Machine/tree/main/Android/Resound

## [0.1.0] - 2026-06-21

### Added
- Initial project scaffold against the standard typezero Android build matrix
  (AGP 8.7.2, Kotlin 2.0.21, Compose BOM 2024.10.01, compileSdk/targetSdk 35,
  minSdk 26, Java 17, manual DI, arm64-v8a only).
- `core/ffmpeg`: `FFmpegRunner` interface + `StubFFmpegRunner` placeholder.
- `core/audio`: `AudioFile` model, `WaveformExtractor` interface +
  `FakeWaveformExtractor` for UI development.
- `feature/effects`: `Effects` builders mapping every planned operation
  (trim, concat, mix, fade, volume, speed, pitch, EQ, vocal-remove, convert,
  compress, video-to-audio) to FFmpeg argument lists.
- `feature/edit`: `WaveformView` Canvas widget with draggable two-handle
  selection + playhead, and an `EditScreen` shell rendering it.
- `feature/record`: `Recorder` interface stub.
- Manual DI container, Application, single-activity host, minimal dark theme.

### Notes
- No FFmpeg backend is wired in yet (see README → "FFmpeg dependency").
- No file picker yet; the editor renders a synthesised demo waveform.

### Fixed
- Editor content now clears the status bar / home indicator via
  `safeDrawingPadding()` under edge-to-edge.

[0.1.0]: https://github.com/MikereDD/It-Works-On-My-Machine/tree/main/Android/Resound
