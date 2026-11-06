package com.typezero.resound.core.update

import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime

/**
 * Typezer∅ release manifest, schema revision 2 (Android form). Parsing enforces the schema
 * itself: exact keys (unknown properties are rejected), exact JSON types, patterns and the
 * Android conditionals. Comparing the values with Resound's locally pinned trust is
 * [UpdatePolicy]'s job; nothing parsed here is trusted on its own.
 */
data class ReleaseManifest(
    val schemaVersion: Int,
    val appId: String,
    val displayName: String,
    val platform: String,
    val architecture: String,
    val channel: String,
    val version: String,
    val publishedAt: String,
    val minimumVersion: String,
    val updaterProtocolVersion: Int,
    val minimumUpdaterProtocolVersion: Int,
    val mandatory: Boolean,
    val mandatoryReason: String?,
    val releaseNotesUrl: String,
    val changelogUrl: String,
    val assets: List<Asset>,
    val source: Source,
    val rollback: Rollback,
) {
    data class Signature(
        val algorithm: String,
        val fileName: String,
        val downloadUrl: String,
        val size: Long,
        val sha256: String,
        val keyId: String,
        val publicKeySha256: String,
    )

    data class Asset(
        val fileName: String,
        val downloadUrl: String,
        val size: Long,
        val sha256: String,
        val signature: Signature,
        val packageId: String,
        val signingCertificateSha256: String,
    )

    data class Source(val repositoryUrl: String, val tag: String, val commit: String)

    data class Rollback(val supported: Boolean, val retainVersions: Int, val minimumRollbackVersion: String?)

    companion object {
        const val MAX_BYTES = 64 * 1024

        private val APP_ID = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
        private val SHA256 = Regex("^[A-Fa-f0-9]{64}$")
        private val KEY_ID = Regex("^[A-Za-z0-9._-]{1,128}$")
        private val PACKAGE_ID = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")
        private val TAG = Regex("^v[0-9]+(?:\\.[0-9]+)+(?:-dev(?:\\.[0-9]+)+)?$")
        private val COMMIT = Regex("^(?:[A-Fa-f0-9]{40}|[A-Fa-f0-9]{64})$")
        private val THUMBPRINT = Regex("^[A-Fa-f0-9]{40,64}$")
        private val RELATIVE_PATH = Regex("^(?!/)(?!.*(?:^|/)\\.\\.(?:/|$))[A-Za-z0-9._/-]+$")
        private val ARCHITECTURES = setOf(
            "win-x64", "win-arm64",
            "android-universal", "android-arm64-v8a", "android-armeabi-v7a", "android-x86_64",
        )
        private val SIGNATURE_ALGORITHMS = setOf("rsa-sha256", "ecdsa-sha256", "ed25519")

        fun parse(text: String): ReleaseManifest {
            require(text.length <= MAX_BYTES) { "Release manifest is too large" }
            val root = Strict(JSONObject(text), "manifest").keys(
                required = setOf(
                    "schemaVersion", "appId", "displayName", "platform", "architecture", "channel",
                    "version", "publishedAt", "minimumVersion", "updaterProtocolVersion",
                    "minimumUpdaterProtocolVersion", "mandatory", "releaseNotesUrl", "changelogUrl",
                    "assets", "source", "rollback",
                ),
                optional = setOf("mandatoryReason"),
            )
            require(root.int("schemaVersion") == 2L) { "Unsupported release manifest schema" }
            val platform = root.string("platform")
            require(platform == "windows" || platform == "android") { "Invalid manifest platform" }
            require(platform == "android") { "Release manifest is not for Android" }
            val architecture = root.string("architecture")
            require(architecture in ARCHITECTURES && architecture.startsWith("android-")) {
                "Invalid manifest architecture"
            }
            val channel = root.string("channel")
            require(channel == "stable" || channel == "development") { "Invalid manifest channel" }
            val updaterProtocol = root.int("updaterProtocolVersion")
            val minimumProtocol = root.int("minimumUpdaterProtocolVersion")
            require(updaterProtocol in 1..Int.MAX_VALUE && minimumProtocol in 1..Int.MAX_VALUE) {
                "Invalid updater protocol version"
            }
            val mandatory = root.boolean("mandatory")
            val mandatoryReason = root.optionalString("mandatoryReason")
            require(!mandatory || mandatoryReason != null) { "Mandatory release is missing its reason" }
            val assetArray = root.array("assets")
            require(assetArray.length() >= 1) { "Release manifest has no assets" }

            return ReleaseManifest(
                schemaVersion = 2,
                appId = root.string("appId").also { require(APP_ID.matches(it)) { "Invalid manifest appId" } },
                displayName = root.string("displayName"),
                platform = platform,
                architecture = architecture,
                channel = channel,
                version = root.version("version"),
                publishedAt = root.string("publishedAt").also {
                    require(runCatching { OffsetDateTime.parse(it) }.isSuccess) { "Invalid manifest publishedAt" }
                },
                minimumVersion = root.version("minimumVersion"),
                updaterProtocolVersion = updaterProtocol.toInt(),
                minimumUpdaterProtocolVersion = minimumProtocol.toInt(),
                mandatory = mandatory,
                mandatoryReason = mandatoryReason,
                releaseNotesUrl = root.httpsOrRelative("releaseNotesUrl"),
                changelogUrl = root.httpsOrRelative("changelogUrl"),
                assets = (0 until assetArray.length()).map { index ->
                    val item = assetArray.opt(index) as? JSONObject ?: throw IllegalArgumentException("Invalid manifest asset")
                    asset(Strict(item, "asset"))
                },
                source = source(root.child("source")),
                rollback = rollback(root.child("rollback")),
            )
        }

        private fun asset(o: Strict): Asset {
            o.keys(
                required = setOf("fileName", "downloadUrl", "size", "sha256", "signature", "packageId", "signingCertificateSha256"),
                optional = setOf("authenticodeSignerThumbprint"),
            )
            o.optionalString("authenticodeSignerThumbprint")?.let {
                require(THUMBPRINT.matches(it)) { "Invalid asset authenticodeSignerThumbprint" }
            }
            val s = o.child("signature").keys(
                required = setOf("algorithm", "fileName", "downloadUrl", "size", "sha256", "keyId", "publicKeySha256"),
            )
            return Asset(
                fileName = o.string("fileName"),
                downloadUrl = o.https("downloadUrl"),
                size = o.positive("size"),
                sha256 = o.matching("sha256", SHA256),
                signature = Signature(
                    algorithm = s.string("algorithm").also {
                        require(it in SIGNATURE_ALGORITHMS) { "Invalid signature algorithm" }
                    },
                    fileName = s.string("fileName"),
                    downloadUrl = s.https("downloadUrl"),
                    size = s.positive("size"),
                    sha256 = s.matching("sha256", SHA256),
                    keyId = s.matching("keyId", KEY_ID),
                    publicKeySha256 = s.matching("publicKeySha256", SHA256),
                ),
                packageId = o.matching("packageId", PACKAGE_ID),
                signingCertificateSha256 = o.matching("signingCertificateSha256", SHA256),
            )
        }

        private fun source(o: Strict): Source {
            o.keys(required = setOf("repositoryUrl", "tag", "commit"))
            val repository = o.string("repositoryUrl")
            require(repository.length <= 2048) { "Invalid source repositoryUrl" }
            return Source(repository, o.matching("tag", TAG), o.matching("commit", COMMIT))
        }

        /** Android: no automatic rollback (supported=false, retainVersions=0). */
        private fun rollback(o: Strict): Rollback {
            o.keys(required = setOf("supported", "retainVersions"), optional = setOf("minimumRollbackVersion"))
            val supported = o.boolean("supported")
            val retain = o.int("retainVersions")
            require(!supported && retain == 0L) { "Android releases do not support automatic rollback" }
            return Rollback(supported, 0, if (o.has("minimumRollbackVersion")) o.version("minimumRollbackVersion") else null)
        }

        /** Exact-type accessors: no string-to-number or number-to-boolean coercion, no JSON null. */
        private class Strict(private val o: JSONObject, private val where: String) {
            fun keys(required: Set<String>, optional: Set<String> = emptySet()): Strict {
                val actual = o.keys().asSequence().toSet()
                require(actual.containsAll(required)) { "Release $where is missing required fields" }
                require((actual - required - optional).isEmpty()) { "Release $where contains unknown fields" }
                return this
            }

            fun has(key: String) = o.has(key)

            fun string(key: String): String =
                (o.opt(key) as? String)?.takeIf { it.isNotEmpty() } ?: invalid(key)

            fun optionalString(key: String): String? = if (o.has(key)) string(key) else null

            fun int(key: String): Long = when (val value = o.opt(key)) {
                is Int -> value.toLong()
                is Long -> value
                else -> invalid(key)
            }

            fun positive(key: String): Long = int(key).also { if (it < 1) invalid(key) }

            fun boolean(key: String): Boolean = o.opt(key) as? Boolean ?: invalid(key)

            fun array(key: String): JSONArray = o.opt(key) as? JSONArray ?: invalid(key)

            fun child(key: String): Strict = Strict(o.opt(key) as? JSONObject ?: invalid(key), key)

            fun matching(key: String, pattern: Regex): String = string(key).also { if (!pattern.matches(it)) invalid(key) }

            fun version(key: String): String = string(key).also {
                runCatching { TypezeroVersion.parse(it) }.getOrElse { invalid(key) }
            }

            fun https(key: String): String = string(key).also { if (!it.startsWith("https://")) invalid(key) }

            fun httpsOrRelative(key: String): String =
                string(key).also { if (!it.startsWith("https://") && !RELATIVE_PATH.matches(it)) invalid(key) }

            private fun invalid(key: String): Nothing = throw IllegalArgumentException("Invalid release $where field: $key")
        }
    }
}
