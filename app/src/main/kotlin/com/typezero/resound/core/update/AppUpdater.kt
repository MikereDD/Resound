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
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Secure, user-initiated updater for the sideloaded APK. */
class AppUpdater(private val context: Context) {

    data class Release(
        val versionCode: Long,
        val versionName: String,
        val apkUrl: String,
        val sha256: String,
        val notes: String,
    )

    sealed interface CheckResult {
        data class Available(val release: Release) : CheckResult
        data class Current(val versionName: String) : CheckResult
        data class Failed(val message: String) : CheckResult
    }

    sealed interface DownloadResult {
        data class Ready(val apk: File) : DownloadResult
        data class Failed(val message: String) : DownloadResult
    }

    suspend fun check(): CheckResult = withContext(Dispatchers.IO) {
        val channel = UpdatePolicy.channel(BuildConfig.IS_DEVTEST, BuildConfig.VERSION_NAME)
            ?: return@withContext CheckResult.Failed("Updates are disabled in the DEV TEST build.")
        runCatching {
            val manifestUrl = UpdatePolicy.manifestUrl(
                channel, BuildConfig.UPDATE_MANIFEST_URL, BuildConfig.UPDATE_DEV_MANIFEST_URL,
            )
            UpdatePolicy.requireTrustedHttps(manifestUrl)
            val json = JSONObject(httpGetText(manifestUrl))
            val release = Release(
                versionCode = json.getLong("versionCode"),
                versionName = json.getString("versionName").trim(),
                apkUrl = json.getString("apkUrl").trim(),
                sha256 = json.getString("sha256").trim().lowercase(),
                notes = json.optString("notes", ""),
            )
            require(release.versionCode > 0) { "Invalid update version code" }
            require(release.versionName.isNotBlank()) { "Invalid update version name" }
            require(release.sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid SHA-256 in update manifest" }
            UpdatePolicy.requireTrustedHttps(release.apkUrl)

            val installed = installedPackageInfo()
            if (release.versionCode > installed.longVersionCodeCompat()) {
                CheckResult.Available(release)
            } else {
                CheckResult.Current(installed.versionName ?: "current")
            }
        }.getOrElse { CheckResult.Failed(it.message ?: "Update check failed") }
    }

    suspend fun download(release: Release, onProgress: (Int) -> Unit): DownloadResult =
        withContext(Dispatchers.IO) {
            runCatching {
                UpdatePolicy.requireTrustedHttps(release.apkUrl)
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val partial = File(dir, "Resound-${release.versionName}.apk.part")
                val apk = File(dir, "Resound-${release.versionName}.apk")

                val connection = openConnection(release.apkUrl)
                try {
                    val total = connection.contentLengthLong
                    connection.inputStream.use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var copied = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                copied += read
                                if (total > 0) onProgress(((copied * 100L) / total).toInt().coerceIn(0, 100))
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }

                val actualHash = sha256(partial)
                require(actualHash.equals(release.sha256, ignoreCase = true)) {
                    "Downloaded APK failed SHA-256 verification"
                }
                verifyArchiveIdentity(partial, release)
                require(partial.renameTo(apk)) { "Could not finalize downloaded APK" }
                onProgress(100)
                DownloadResult.Ready(apk)
            }.getOrElse { error ->
                DownloadResult.Failed(error.message ?: "Update download failed")
            }
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

    private fun verifyArchiveIdentity(apk: File, release: Release) {
        val archive = archivePackageInfo(apk)
            ?: error("Android could not read the downloaded APK")
        val installed = installedPackageInfo()
        UpdatePolicy.requireValidUpdate(
            expectedPackage = context.packageName,
            manifestVersionCode = release.versionCode,
            installedVersionCode = installed.longVersionCodeCompat(),
            installedSigners = signerDigests(installed),
            releaseCertSha256 = BuildConfig.UPDATE_RELEASE_CERT_SHA256,
            archive = UpdatePolicy.ApkIdentity(archive.packageName, archive.longVersionCodeCompat(), signerDigests(archive)),
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

    private fun httpGetText(url: String): String {
        val connection = openConnection(url)
        return try {
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
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
                setRequestProperty("Accept", "application/json, application/vnd.android.package-archive")
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
                    error("Server returned HTTP $code")
                }
            }
        }
        error("Too many update redirects")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return bytesToHex(digest.digest())
    }

    private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
