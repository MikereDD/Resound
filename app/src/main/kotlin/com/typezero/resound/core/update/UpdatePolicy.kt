package com.typezero.resound.core.update

import java.net.URI
import java.util.Locale

/** Pure, host-testable update decisions: channel, trusted URLs and redirects, downloaded APK identity. */
object UpdatePolicy {
    const val MAX_REDIRECTS = 5

    /** GitHub raw manifests, release pages and the hosts GitHub redirects release assets to. */
    val TRUSTED_HOSTS = setOf(
        "raw.githubusercontent.com",
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
    )

    enum class Channel { DEVELOPMENT, STABLE }

    /** DEV TEST never updates; release builds with a "-dev" version follow the development manifest. */
    fun channel(isDevtest: Boolean, versionName: String): Channel? = when {
        isDevtest -> null
        versionName.contains("-dev") -> Channel.DEVELOPMENT
        else -> Channel.STABLE
    }

    fun manifestUrl(channel: Channel, stableUrl: String, developmentUrl: String): String =
        if (channel == Channel.DEVELOPMENT) developmentUrl else stableUrl

    fun requireTrustedHttps(url: String): URI {
        val uri = URI(url)
        require(uri.scheme.equals("https", ignoreCase = true)) { "Updater requires HTTPS" }
        require(uri.userInfo == null && uri.fragment == null && (uri.port == -1 || uri.port == 443)) { "Invalid update URL" }
        val host = uri.host?.lowercase(Locale.ROOT) ?: error("Invalid update host")
        require(host in TRUSTED_HOSTS) { "Untrusted update host: $host" }
        return uri
    }

    fun isRedirect(code: Int): Boolean = code == 301 || code == 302 || code == 303 || code == 307 || code == 308

    /** The next hop: Location resolved against [current]; it must itself be trusted HTTPS. */
    fun redirectTarget(current: String, location: String?): String {
        require(!location.isNullOrBlank()) { "Update redirect without a location" }
        val next = URI(current).resolve(location.trim()).toString()
        requireTrustedHttps(next)
        return next
    }

    /** Identity read from the downloaded archive. [signers] are SHA-256 digests of the signing certificates. */
    data class ApkIdentity(val packageName: String, val versionCode: Long, val signers: Set<String>)

    /** Throws with a readable reason unless the APK is exactly the announced, newer, release-signed Resound. */
    fun requireValidUpdate(
        expectedPackage: String,
        manifestVersionCode: Long,
        installedVersionCode: Long,
        installedSigners: Set<String>,
        releaseCertSha256: String,
        archive: ApkIdentity,
    ) {
        require(archive.packageName == expectedPackage) { "Downloaded APK package name does not match Resound" }
        require(archive.versionCode == manifestVersionCode) { "Downloaded APK version does not match the update manifest" }
        require(archive.versionCode > installedVersionCode) { "Downloaded APK is not newer than the installed version" }
        require(archive.signers == setOf(releaseCertSha256.lowercase(Locale.ROOT))) {
            "Downloaded APK is not signed with the Resound release certificate"
        }
        require(installedSigners == archive.signers) { "Downloaded APK signing certificate does not match the installed app" }
    }
}
