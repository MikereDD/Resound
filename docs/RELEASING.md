# Releasing Resound

Resound is distributed as a signed sideload APK through **GitHub Releases**:
https://github.com/MikereDD/Resound/releases

The Git repository contains source and release metadata — never APK binaries.
The updater reads `releases/development/release-manifest.json` (development
builds) and `releases/update.json` (stable builds, added with the first stable
release). Both use the Typezer∅ release manifest, schema revision 2.

Releases follow the
[Typezer∅ Release Standards](https://github.com/MikereDD/Typezero-Release-Standards).

Only the production package (`com.typezero.resound`, release-signed) is
published. DEV TEST builds (`com.typezero.resound.devtest`, debug-signed) are for
local device testing and are never attached to a release.

## Signing identities

Every release carries two independent signatures:

- **APK signing certificate** (`resound-release`, SHA-256
  `3aaba0c794a652511c65272f7cf9210432a23069f5a27bd2d700c06891bed499`): Android
  package continuity. Resound pins it and Android enforces it on upgrade.
- **Detached release signature** (`typezero-resound-release-01`, RSA-4096,
  `rsa-sha256`): authorizes the published APK. Resound pins the public key and
  its SHA-256 SubjectPublicKeyInfo fingerprint.

Private keys and passwords never enter the repository, the release assets or
logs. Signing is done locally and interactively.

## Release-key rotation

Resound trusts exactly one release key, pinned in the app (`UpdateTrust`). A
release manifest can never add or replace a trusted key; it only names the key,
and the updater compares that with the pin.

- Rotate the release key only for a real reason: suspected or confirmed
  compromise, or a security policy change. Tooling or storage changes are not a
  reason.
- **Planned rotation** from key A to key B: release N is signed with A, so every
  installed updater can verify it, and N pins B. From release N+1 on, releases
  are signed with B and set `minimumVersion` to N, so older installations update
  to N first. Release N stays available. Key A is then retired and never signs
  again; its key record is marked `retired`.
- **Compromise of the release key:** a release signed with a compromised key
  cannot be trusted to introduce its replacement. Stop publishing, mark the key
  `revoked` in its key record, and publish a release pinning the new key through
  a manual installation from GitHub Releases, announced in the release notes.
- **APK signing key:** Android enforces it on every upgrade and Resound pins
  exactly one certificate, so it is never rotated without a separately reviewed
  plan for Android signing-certificate lineage.

## Update channels

The update channel is fixed by the installed build:

- **Development:** release-signed builds whose version contains `-dev` read
  `releases/development/release-manifest.json`. A user joins Development only by
  manually downloading and installing a development pre-release from GitHub
  Releases.
- **Stable:** release-signed builds without `-dev` read `releases/update.json`.
- **DEV TEST** builds never check for updates.

Resound never switches channels or downgrades itself: a manifest for the other
channel, or a release whose version does not belong to the installed channel, is
rejected. Moving between channels is always a manual installation.

This differs from the Typezer∅ channel policy, which has users explicitly choose
Stable or Development in the app. There is no in-app channel choice yet, and no
stable channel exists. Before the first stable release, either add a channel
choice (which explains any version decrease and never downgrades
automatically) or approve this build-selected model as a documented exception
and show the active channel in the updater.

## Release assets

Each GitHub Release carries exactly these files:

```text
Resound-v<version>.apk
Resound-v<version>.apk.sha256
Resound-v<version>.apk.sig
release-manifest.json
release-notes.md
release-validation.md
```

## Development pre-releases

- Tag: annotated `v<versionName>`, for example `v0.8.0-dev.11`, created locally
  with `git tag -a` on the exact public source commit that built the APK and
  pushed to GitHub only with an explicit refspec
  (`git push github refs/tags/v<versionName>`). Create the release with
  `gh release create v<versionName> --verify-tag`, which refuses to create a
  (lightweight) tag itself. Verify that the remote tag is an annotated tag
  object that peels to the source commit.
- Mark the GitHub Release as a **pre-release**.
- After every asset is live and verified, publish the same
  `release-manifest.json` as `releases/development/release-manifest.json` on
  `main` through a pull request, then verify it from
  `raw.githubusercontent.com`. Never point the stable `releases/update.json` at
  a development build.

## Release checklist

1. Set the final `versionName` and monotonically increasing `versionCode`;
   update `CHANGELOG.md`.
2. Run the host test suites.
3. Commit the public release source; build the release APK from that exact
   commit.
4. Sign the APK with the Resound APK signing key and verify the certificate.
5. Create the detached release signature over the signed APK and verify it
   against the pinned public key.
6. Generate the `.sha256` file, `release-manifest.json`, release notes and the
   validation record; validate the manifest against the schema and the files.
7. Stop for explicit approval before any public step.
8. Push the source, create the annotated tag and the GitHub pre-release with
   all six assets; verify the published names, sizes and digests.
9. Publish and verify the development manifest.
10. Check for updates from the previous release on a device.

## APK policy

Do **not** commit APK files to the repository and do not use Git LFS for release
binaries. Release APKs belong only on the matching GitHub Release.

The repository `.gitignore` should continue to exclude `*.apk`.

## Update manifest

Both channels use the Typezer∅ release manifest, schema revision 2; see
[`releases/release-manifest.example.json`](../releases/release-manifest.example.json).
The updater rejects unknown fields, another app, platform, architecture or
channel, an updater protocol it does not support, a version that is not newer or
is below `minimumVersion`, unexpected file names or URLs, any size or SHA-256
mismatch, a signature that does not verify with the pinned release key, and any
APK whose package, version or signing certificate is not the expected Resound
release.

Manifest bytes must be valid UTF-8 and strict JSON (no duplicate keys, trailing
content or lenient syntax); integer fields must be written as plain integers.

Download trust: the manifest's APK and signature URLs must be exactly
`https://github.com/MikereDD/Resound/releases/download/v<version>/<file name>`.
GitHub then redirects to its asset CDN. Every redirect hop (at most 5) must be
HTTPS on port 443 to `github.com`, `raw.githubusercontent.com`,
`objects.githubusercontent.com` or `release-assets.githubusercontent.com`; paths
after the first hop are chosen by GitHub and are not pinned. Integrity never
depends on the redirect path: the exact size, SHA-256, pinned detached signature
and APK signer are checked on the downloaded bytes. Narrowing the redirect hosts
needs the live CDN chain observed first, because an unexpected hop would break
every update.

Each download is staged in its own directory under `cache/updates`. Leaving the
updater (the dialog is disposed) cancels the download at once, disconnects the
transfer, stops it writing at its next read and deletes that directory; a failed
or superseded download deletes only its own files, and startup clears all of
them.

Resound's updater protocol is `1`. Raise it, and `minimumUpdaterProtocolVersion`
where needed, only when an older updater could no longer complete an update.

Do not publish a manifest until every asset it names is available at its final
GitHub Release URL.
