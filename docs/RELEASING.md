# Releasing Resound

Resound is distributed as a signed sideload APK through **GitHub Releases**:
https://github.com/MikereDD/Resound/releases

The Git repository contains source and release metadata — never APK binaries.
The updater reads `releases/update-dev.json` (development builds) and
`releases/update.json` (stable builds, added with the first stable release).

Only the production package (`com.typezero.resound`, release-signed) is
published. DEV TEST builds (`com.typezero.resound.devtest`, debug-signed) are for
local device testing and are never attached to a release.

## Development pre-releases

- Tag: `v<versionName>`, for example `v0.8.0-dev.10`.
- Asset: `Resound-v<versionName>.apk`, for example `Resound-v0.8.0-dev.10.apk`,
  with its SHA-256 in the release notes.
- Mark the GitHub Release as a **pre-release**.
- After the release asset is live, update `releases/update-dev.json` on `main`
  through a pull request. Never point the stable `releases/update.json` at a
  development build.

## Release checklist

1. Complete physical-device regression testing.
2. Set the final `versionName` and monotonically increasing `versionCode`.
3. Update `CHANGELOG.md` and any release notes.
4. Build the release APK with the established Resound signing key.
5. Verify that the signed APK installs over the previous release.
6. Compute SHA-256 for the exact APK that will be published.
7. Create the GitHub Release for the matching tag and attach the signed APK.
8. Verify the final GitHub Release asset URL.
9. Update `releases/update.json` with the final version, asset URL, SHA-256, and notes.
10. Commit the manifest and release metadata.
11. Push the release commit and tag only after the APK, URL, and manifest are verified.
12. Re-check the in-app updater from the previous release.

## APK policy

Do **not** commit APK files to the repository and do not use Git LFS for release
binaries. Release APKs belong only on the matching GitHub Release.

The repository `.gitignore` should continue to exclude `*.apk`.

## Update manifest

The updater reads `releases/update-dev.json` (development builds) or
`releases/update.json` (stable builds) from the `main` branch; both use the same
format, see [`releases/update.example.json`](../releases/update.example.json).
A typical release entry looks like:

```json
{
  "versionCode": 20,
  "versionName": "0.8.0",
  "apkUrl": "https://github.com/MikereDD/Resound/releases/download/v0.8.0/Resound-v0.8.0.apk",
  "sha256": "64-lowercase-hex-characters",
  "notes": "Release notes"
}
```

The updater rejects a wrong hash, a wrong package name, a version code that is
not newer or differs from the manifest, and any APK not signed with the Resound
release certificate.

Do not point either manifest at an asset until that exact signed APK is
available at the final GitHub Release URL.
