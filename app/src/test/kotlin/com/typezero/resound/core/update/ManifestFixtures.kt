package com.typezero.resound.core.update

import org.json.JSONArray
import org.json.JSONObject

/** Valid schema-2 development manifest for Resound; tests mutate copies of it. */
object ManifestFixtures {
    const val CERT = "3aaba0c794a652511c65272f7cf9210432a23069f5a27bd2d700c06891bed499"
    const val KEY_ID = "typezero-resound-release-01"
    const val BASE = "https://github.com/MikereDD/Resound/releases/download"

    fun manifest(
        version: String = "0.8.0-dev.12",
        apkSha256: String = "a".repeat(64),
        apkSize: Long = 45_000_000,
        sigSha256: String = "b".repeat(64),
        sigSize: Long = 512,
        publicKeySha256: String = "c".repeat(64),
    ): JSONObject {
        val apk = "Resound-v$version.apk"
        return JSONObject()
            .put("schemaVersion", 2)
            .put("appId", "resound")
            .put("displayName", "Resound")
            .put("platform", "android")
            .put("architecture", "android-arm64-v8a")
            .put("channel", "development")
            .put("version", version)
            .put("publishedAt", "2026-11-07T12:00:00Z")
            .put("minimumVersion", "0.8.0-dev.11")
            .put("updaterProtocolVersion", 1)
            .put("minimumUpdaterProtocolVersion", 1)
            .put("mandatory", false)
            .put("releaseNotesUrl", "https://github.com/MikereDD/Resound/releases/tag/v$version")
            .put("changelogUrl", "CHANGELOG.md")
            .put(
                "assets",
                JSONArray().put(
                    JSONObject()
                        .put("fileName", apk)
                        .put("downloadUrl", "$BASE/v$version/$apk")
                        .put("size", apkSize)
                        .put("sha256", apkSha256)
                        .put(
                            "signature",
                            JSONObject()
                                .put("algorithm", "rsa-sha256")
                                .put("fileName", "$apk.sig")
                                .put("downloadUrl", "$BASE/v$version/$apk.sig")
                                .put("size", sigSize)
                                .put("sha256", sigSha256)
                                .put("keyId", KEY_ID)
                                .put("publicKeySha256", publicKeySha256),
                        )
                        .put("packageId", "com.typezero.resound")
                        .put("signingCertificateSha256", CERT),
                ),
            )
            .put(
                "source",
                JSONObject()
                    .put("repositoryUrl", "https://github.com/MikereDD/Resound")
                    .put("tag", "v$version")
                    .put("commit", "e5508067330c193470bfa0b2fb886257f23b3d26"),
            )
            .put("rollback", JSONObject().put("supported", false).put("retainVersions", 0))
    }

    fun JSONObject.asset(): JSONObject = getJSONArray("assets").getJSONObject(0)
    fun JSONObject.signature(): JSONObject = asset().getJSONObject("signature")

    fun parse(json: JSONObject): ReleaseManifest = ReleaseManifest.parse(json.toString())
}
