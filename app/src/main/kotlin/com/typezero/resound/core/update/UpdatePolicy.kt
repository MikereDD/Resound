package com.typezero.resound.core.update

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/** Pure, host-testable update decisions: channel, trusted URLs and redirects, eligibility, downloaded APK identity. */
object UpdatePolicy {
    const val MAX_REDIRECTS = 5

    /** GitHub raw manifests, release pages and the hosts GitHub redirects release assets to. */
    val TRUSTED_HOSTS = setOf(
        "raw.githubusercontent.com",
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
    )

    /** Manifest asset URLs must name this exact Resound release download origin. */
    private const val RESOUND_RELEASE_DOWNLOAD = "https://github.com/MikereDD/Resound/releases/download"

    enum class Channel(val wire: String, val label: String) {
        DEVELOPMENT("development", "development"),
        STABLE("stable", "stable"),
    }

    /** DEV TEST never updates; release builds with a "-dev" version follow the development manifest. */
    fun channel(isDevtest: Boolean, versionName: String): Channel? = when {
        isDevtest -> null
        versionName.contains("-dev") -> Channel.DEVELOPMENT
        else -> Channel.STABLE
    }

    fun manifestUrl(channel: Channel, stableUrl: String, developmentUrl: String): String =
        if (channel == Channel.DEVELOPMENT) developmentUrl else stableUrl

    fun requireTrustedHttps(url: String): URI {
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            throw IllegalArgumentException("Invalid update URL")
        }
        require(uri.scheme.equals("https", ignoreCase = true)) { "Updater requires HTTPS" }
        require(uri.userInfo == null && uri.fragment == null && (uri.port == -1 || uri.port == 443)) { "Invalid update URL" }
        val host = uri.host?.lowercase(Locale.ROOT) ?: error("Invalid update host")
        require(host in TRUSTED_HOSTS) { "Untrusted update host: $host" }
        return uri
    }

    fun isRedirect(code: Int): Boolean = code == 301 || code == 302 || code == 303 || code == 307 || code == 308

    /** The next hop: Location resolved against [current]; it must itself pass [requireUrl] (trusted HTTPS). */
    fun redirectTarget(
        current: String,
        location: String?,
        requireUrl: (String) -> Unit = { requireTrustedHttps(it) },
    ): String {
        require(!location.isNullOrBlank()) { "Update redirect without a location" }
        val next = URI(current).resolve(location.trim()).toString()
        requireUrl(next)
        return next
    }

    /** An eligible update: the manifest and the single Resound APK asset it offers. */
    data class Offer(val manifest: ReleaseManifest, val asset: ReleaseManifest.Asset)

    /**
     * Checks a parsed manifest against local trust and the channel policy. Returns null when the
     * release is not newer than [installedVersion] (same version or a downgrade: no update) and
     * throws with a readable reason when the release must not be offered.
     */
    fun selectUpdate(
        manifest: ReleaseManifest,
        channel: Channel,
        installedVersion: String,
        anchors: TrustAnchors,
        updaterProtocol: Int = UpdateTrust.UPDATER_PROTOCOL_VERSION,
    ): Offer? {
        require(anchors.configured) { "Release trust anchors are not configured in this build" }
        require(manifest.schemaVersion == UpdateTrust.MANIFEST_SCHEMA_VERSION) { "Unsupported release manifest schema" }
        require(manifest.appId == UpdateTrust.APP_ID && manifest.displayName == UpdateTrust.DISPLAY_NAME) {
            "Release manifest is not for Resound"
        }
        require(manifest.platform == "android") { "Release manifest is not for Android" }
        require(manifest.architecture in UpdateTrust.ARCHITECTURES) { "Release architecture is not compatible with this build" }
        require(manifest.channel == channel.wire) {
            "Release belongs to the ${manifest.channel} channel, not ${channel.wire}"
        }
        require(manifest.minimumUpdaterProtocolVersion <= manifest.updaterProtocolVersion) { "Invalid updater protocol range" }
        require(updaterProtocol >= manifest.minimumUpdaterProtocolVersion) {
            "This Resound updater is too old for that release. Install it manually from GitHub Releases."
        }

        val candidate = TypezeroVersion.parse(manifest.version)
        val installed = TypezeroVersion.parse(installedVersion)
        val minimum = TypezeroVersion.parse(manifest.minimumVersion)
        require(candidate.isDevelopment == (channel == Channel.DEVELOPMENT)) {
            "Release version does not belong to the ${channel.wire} channel"
        }
        require(manifest.source.tag == "v${manifest.version}") { "Release source tag does not match its version" }
        if (candidate <= installed) return null
        require(installed >= minimum) {
            "This release cannot update $installedVersion directly. Install it manually from GitHub Releases."
        }

        val asset = manifest.assets.singleOrNull { it.packageId == UpdateTrust.PACKAGE_ID }
            ?: throw IllegalArgumentException("Release has no single Resound APK asset")
        val apkName = "${UpdateTrust.DISPLAY_NAME}-v${manifest.version}.apk"
        require(asset.fileName == apkName) { "Release asset name does not match the version" }
        require(asset.signature.fileName == "$apkName.sig") { "Release signature name does not match the APK" }
        requireAssetUrl(asset.downloadUrl, manifest.version, asset.fileName)
        requireAssetUrl(asset.signature.downloadUrl, manifest.version, asset.signature.fileName)
        require(asset.size <= UpdateTrust.MAX_APK_BYTES) { "Release APK is too large" }
        require(asset.signature.size <= UpdateTrust.MAX_SIGNATURE_BYTES) { "Release signature is too large" }
        require(asset.signature.algorithm == UpdateTrust.SIGNATURE_ALGORITHM) { "Unsupported release signature algorithm" }
        require(asset.signature.keyId == anchors.releaseKeyId) { "Release is signed with an unapproved key" }
        require(asset.signature.publicKeySha256.equals(anchors.releasePublicKeySha256, ignoreCase = true)) {
            "Release signing key does not match the pinned key"
        }
        require(asset.signingCertificateSha256.equals(anchors.apkCertSha256, ignoreCase = true)) {
            "Release APK certificate does not match the pinned certificate"
        }
        return Offer(manifest, asset)
    }

    /**
     * Trusted HTTPS and the exact Resound release URL: expected repository, release tag and file
     * name, with no query or encoded alias. Redirects after this point remain governed by
     * [requireTrustedHttps] and [redirectTarget].
     */
    private fun requireAssetUrl(url: String, version: String, fileName: String) {
        val uri = requireTrustedHttps(url)
        require(uri.rawQuery == null) { "Release download URL does not match its file name" }
        require(uri.rawPath == uri.path) { "Release download URL does not match the Resound release" }
        require(uri.rawPath?.substringAfterLast('/') == fileName) {
            "Release download URL does not match its file name"
        }
        val expected = "$RESOUND_RELEASE_DOWNLOAD/v$version/$fileName"
        require(url == expected) { "Release download URL does not match the Resound release" }
    }

    /** Identity read from the downloaded archive. [signers] are SHA-256 digests of the signing certificates. */
    data class ApkIdentity(val packageName: String, val versionName: String, val versionCode: Long, val signers: Set<String>)

    /** Throws with a readable reason unless the APK is exactly the announced, newer, release-signed Resound. */
    fun requireValidUpdate(
        expectedPackage: String,
        manifestVersion: String,
        installedVersionCode: Long,
        installedSigners: Set<String>,
        releaseCertSha256: String,
        archive: ApkIdentity,
    ) {
        require(archive.packageName == expectedPackage) { "Downloaded APK package name does not match Resound" }
        require(archive.versionName == manifestVersion) { "Downloaded APK version does not match the release manifest" }
        require(archive.versionCode > installedVersionCode) { "Downloaded APK is not newer than the installed version" }
        require(archive.signers == setOf(releaseCertSha256.lowercase(Locale.ROOT))) {
            "Downloaded APK is not signed with the Resound release certificate"
        }
        require(installedSigners == archive.signers) { "Downloaded APK signing certificate does not match the installed app" }
    }
}
