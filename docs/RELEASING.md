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

- Tag: annotated `v<versionName>`, for example `v0.8.0-dev.11`, on the exact
  public source commit that built the APK.
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

Resound's updater protocol is `1`. Raise it, and `minimumUpdaterProtocolVersion`
where needed, only when an older updater could no longer complete an update.

Do not publish a manifest until every asset it names is available at its final
GitHub Release URL.
