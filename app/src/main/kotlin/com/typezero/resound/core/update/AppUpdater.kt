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
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException

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

    private val stagingDir: File get() = UpdateStaging.dir(context.cacheDir)
    private val connector = UpdateDownloader.trustedConnector("Resound/${BuildConfig.VERSION_NAME}")

    /** Staging sessions started by this updater (one dialog); only these are discarded by it. */
    private val sessions = mutableListOf<File>()
    private var closed = false // guarded by sessions; set when the dialog discards its downloads

    suspend fun check(): CheckResult = withContext(Dispatchers.IO) {
        val channel = UpdatePolicy.channel(BuildConfig.IS_DEVTEST, BuildConfig.VERSION_NAME)
            ?: return@withContext CheckResult.Failed("Updates are disabled in the DEV TEST build.")
        try {
            require(context.packageName == UpdateTrust.PACKAGE_ID) { "Updates are only available in Resound" }
            val manifestUrl = UpdatePolicy.manifestUrl(
                channel, BuildConfig.UPDATE_MANIFEST_URL, BuildConfig.UPDATE_DEV_MANIFEST_URL,
            )
            val bytes = try {
                UpdateDownloader.fetchBytes(connector, manifestUrl, ReleaseManifest.MAX_BYTES.toLong())
            } catch (e: UpdateDownloader.HttpStatusException) {
                if (e.code != 404) throw e
                return@withContext CheckResult.NotPublished("No ${channel.label} update has been published yet.")
            }
            val manifest = ReleaseManifest.parseUtf8(bytes)
            when (val offer = UpdatePolicy.selectUpdate(manifest, channel, BuildConfig.VERSION_NAME, UpdateTrust.anchors)) {
                null -> CheckResult.Current(BuildConfig.VERSION_NAME)
                else -> CheckResult.Available(offer)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            CheckResult.Failed(error.message ?: "Update check failed")
        }
    }

    /**
     * Downloads and verifies [offer] in a new staging session. Cancellation (the dialog closing)
     * aborts the transfer, deletes the session and propagates; it is never reported as a result.
     */
    suspend fun download(offer: UpdatePolicy.Offer, onProgress: (Int) -> Unit): DownloadResult =
        withContext(Dispatchers.IO) {
            discardOwned() // a new download supersedes this dialog's earlier one
            try {
                val apk = UpdateDownloader.downloadVerified(
                    stagingRoot = stagingDir,
                    asset = offer.asset,
                    anchors = UpdateTrust.anchors,
                    connector = connector,
                    onSession = { session -> if (!synchronized(sessions) { !closed && sessions.add(session) }) UpdateStaging.discard(session) },
                    onProgress = onProgress,
                ) { apk -> verifyArchiveIdentity(apk, offer.manifest.version) }
                DownloadResult.Ready(apk)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                DownloadResult.Failed(error.message ?: "Update download failed")
            }
        }

    /** Deletes this updater's staged downloads; called when the update dialog closes (including after a cancelled install). */
    fun discardStaged() {
        synchronized(sessions) { closed = true }
        discardOwned()
    }

    private fun discardOwned() {
        val owned = synchronized(sessions) { sessions.toList().also { sessions.clear() } }
        owned.forEach(UpdateStaging::discard)
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

    private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
