package com.typezero.resound.core.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.typezero.resound.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Secure, user-initiated updater for the sideloaded APK, following the Typezer∅ Android updater
 * standard: schema-2 manifest from a pinned origin, local trust and eligibility checks, exact
 * asset download, detached-signature and APK-identity verification, then Android's installer.
 */
class AppUpdater(private val context: Context) {

    sealed interface CheckResult {
        data class Available(val offer: UpdatePolicy.Offer) : CheckResult
        data class Current(val versionName: String) : CheckResult
        data class NotPublished(val message: String) : CheckResult
        data class Failed(val message: String) : CheckResult
    }

    sealed interface DownloadResult {
        data class Ready(val apk: File) : DownloadResult
        data class Failed(val message: String) : DownloadResult
    }

    private class HttpStatusException(val code: Int) : IllegalStateException("Server returned HTTP $code")

    private val stagingDir: File get() = UpdateStaging.dir(context.cacheDir)

    suspend fun check(): CheckResult = withContext(Dispatchers.IO) {
        val channel = UpdatePolicy.channel(BuildConfig.IS_DEVTEST, BuildConfig.VERSION_NAME)
            ?: return@withContext CheckResult.Failed("Updates are disabled in the DEV TEST build.")
        runCatching {
            require(context.packageName == UpdateTrust.PACKAGE_ID) { "Updates are only available in Resound" }
            val manifestUrl = UpdatePolicy.manifestUrl(
                channel, BuildConfig.UPDATE_MANIFEST_URL, BuildConfig.UPDATE_DEV_MANIFEST_URL,
            )
            val text = try {
                httpGetText(manifestUrl, ReleaseManifest.MAX_BYTES)
            } catch (e: HttpStatusException) {
                if (e.code != 404) throw e
                return@withContext CheckResult.NotPublished("No ${channel.label} update has been published yet.")
            }
            val manifest = ReleaseManifest.parse(text)
            when (val offer = UpdatePolicy.selectUpdate(manifest, channel, BuildConfig.VERSION_NAME, UpdateTrust.anchors)) {
                null -> CheckResult.Current(BuildConfig.VERSION_NAME)
                else -> CheckResult.Available(offer)
            }
        }.getOrElse { CheckResult.Failed(it.message ?: "Update check failed") }
    }

    suspend fun download(offer: UpdatePolicy.Offer, onProgress: (Int) -> Unit): DownloadResult =
        withContext(Dispatchers.IO) {
            val dir = stagingDir.apply { mkdirs() }
            UpdateStaging.clear(dir)
            runCatching {
                val asset = offer.asset
                val signature = File(dir, asset.signature.fileName)
                val apk = File(dir, asset.fileName)
                downloadTo(asset.signature.downloadUrl, signature, asset.signature.size) {}
                downloadTo(asset.downloadUrl, apk, asset.size, onProgress)
                ReleaseVerifier.verify(apk, signature, asset, UpdateTrust.anchors)
                verifyArchiveIdentity(apk, offer.manifest.version)
                onProgress(100)
                DownloadResult.Ready(apk)
            }.getOrElse { error ->
                UpdateStaging.clear(dir)
                DownloadResult.Failed(error.message ?: "Update download failed")
            }
        }

    /** Deletes staged downloads; called when the update dialog closes (including after a cancelled install). */
    fun discardStaged() {
        UpdateStaging.clear(stagingDir)
    }

    fun canRequestInstalls(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"),
    )

    fun installIntent(apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun verifyArchiveIdentity(apk: File, manifestVersion: String) {
        val archive = archivePackageInfo(apk)
            ?: error("Android could not read the downloaded APK")
        val installed = installedPackageInfo()
        UpdatePolicy.requireValidUpdate(
            expectedPackage = UpdateTrust.PACKAGE_ID,
            manifestVersion = manifestVersion,
            installedVersionCode = installed.longVersionCodeCompat(),
            installedSigners = signerDigests(installed),
            releaseCertSha256 = UpdateTrust.anchors.apkCertSha256,
            archive = UpdatePolicy.ApkIdentity(
                archive.packageName,
                archive.versionName.orEmpty(),
                archive.longVersionCodeCompat(),
                signerDigests(archive),
            ),
        )
    }

    private fun installedPackageInfo(): PackageInfo {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        return if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(flags.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, flags)
        }
    }

    private fun archivePackageInfo(apk: File): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        return if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.PackageInfoFlags.of(flags.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
        }
    }

    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo ?: return emptySet()
            if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION")
            info.signatures ?: emptyArray()
        }
        return signatures.map { bytesToHex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toSet()
    }

    private fun PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else {
            @Suppress("DEPRECATION")
            versionCode.toLong()
        }

    private fun httpGetText(url: String, maxBytes: Int): String {
        val connection = openConnection(url)
        return try {
            val buffer = ByteArrayOutputStream()
            connection.inputStream.use { copyLimited(it, buffer, maxBytes.toLong()) {} }
            buffer.toString(Charsets.UTF_8.name())
        } finally {
            connection.disconnect()
        }
    }

    /** Downloads exactly [expectedSize] bytes at most; a larger response is rejected while streaming. */
    private fun downloadTo(url: String, target: File, expectedSize: Long, onProgress: (Int) -> Unit) {
        val connection = openConnection(url)
        try {
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    copyLimited(input, output, expectedSize) { copied ->
                        onProgress(((copied * 100L) / expectedSize).toInt().coerceIn(0, 100))
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun copyLimited(input: InputStream, output: OutputStream, limit: Long, onCopied: (Long) -> Unit) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            copied += read
            require(copied <= limit) { "Download is larger than the release metadata allows" }
            output.write(buffer, 0, read)
            onCopied(copied)
        }
    }

    /** Follows at most [UpdatePolicy.MAX_REDIRECTS] redirects; every hop must be trusted HTTPS. */
    private fun openConnection(url: String): HttpURLConnection {
        var target = url
        repeat(UpdatePolicy.MAX_REDIRECTS + 1) {
            UpdatePolicy.requireTrustedHttps(target)
            val connection = (URL(target).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json, application/octet-stream, application/vnd.android.package-archive")
                setRequestProperty("User-Agent", "Resound/${BuildConfig.VERSION_NAME}")
                connect()
            }
            val code = connection.responseCode
            when {
                code in 200..299 -> return connection
                UpdatePolicy.isRedirect(code) -> {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    target = UpdatePolicy.redirectTarget(target, location)
                }
                else -> {
                    connection.disconnect()
                    throw HttpStatusException(code)
                }
            }
        }
        error("Too many update redirects")
    }

    private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
