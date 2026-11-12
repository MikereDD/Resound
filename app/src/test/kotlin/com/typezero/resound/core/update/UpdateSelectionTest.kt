package com.typezero.resound.core.update

import com.typezero.resound.core.update.ManifestFixtures.asset
import com.typezero.resound.core.update.ManifestFixtures.manifest
import com.typezero.resound.core.update.ManifestFixtures.parse
import com.typezero.resound.core.update.ManifestFixtures.signature
import com.typezero.resound.core.update.UpdatePolicy.Channel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Eligibility: channel, protocol compatibility, identity, version ordering and exact asset metadata. */
class UpdateSelectionTest {
    private val anchors = TestKeys.anchors()
    private fun good() = manifest(publicKeySha256 = anchors.releasePublicKeySha256)

    private fun select(
        json: JSONObject = good(),
        installed: String = "0.8.0-dev.11",
        channel: Channel = Channel.DEVELOPMENT,
        protocol: Int = UpdateTrust.UPDATER_PROTOCOL_VERSION,
        trust: TrustAnchors = anchors,
    ) = UpdatePolicy.selectUpdate(parse(json), channel, installed, trust, protocol)

    private fun rejects(reason: String, block: () -> Unit) {
        try {
            block()
            fail("expected rejection: $reason")
        } catch (e: IllegalArgumentException) {
            assertTrue("'${e.message}' should mention '$reason'", e.message.orEmpty().contains(reason))
        }
    }

    @Test fun newerDevelopmentReleaseIsOffered() {
        val offer = select()!!
        assertEquals("0.8.0-dev.12", offer.manifest.version)
        assertEquals("Resound-v0.8.0-dev.12.apk", offer.asset.fileName)
    }

    @Test fun sameVersionAndDowngradeAreNoUpdate() {
        assertNull(select(installed = "0.8.0-dev.12"))
        assertNull(select(installed = "0.8.0-dev.12.0"))
        assertNull(select(installed = "0.8.0-dev.13"))
        assertNull(select(manifest(version = "0.8.0-dev.10", publicKeySha256 = anchors.releasePublicKeySha256).put("minimumVersion", "0.8.0-dev.10")))
    }

    /** Lexically "dev.12" < "dev.9" and "dev.100" < "dev.12"; numerically both orderings flip. */
    @Test fun numericNotLexicalOrdering() {
        val lowMinimum = good().put("minimumVersion", "0.8.0-dev.1")
        assertEquals("0.8.0-dev.12", select(lowMinimum, installed = "0.8.0-dev.9")!!.manifest.version)
        assertNull(select(lowMinimum, installed = "0.8.0-dev.100"))
    }

    @Test fun channelMismatchIsRejectedInBothDirections() {
        rejects("development channel, not stable") { select(channel = Channel.STABLE, installed = "0.8.0") }
        rejects("stable channel, not development") { select(good().put("channel", "stable")) }
        rejects("does not belong to the development channel") {
            select(manifest(version = "0.8.1", publicKeySha256 = anchors.releasePublicKeySha256))
        }
        rejects("does not belong to the stable channel") {
            select(good().put("channel", "stable"), installed = "0.7.0", channel = Channel.STABLE)
        }
    }

    @Test fun updaterProtocolCompatibilityIsEnforced() {
        rejects("too old") { select(good().put("minimumUpdaterProtocolVersion", 2).put("updaterProtocolVersion", 2)) }
        rejects("Invalid updater protocol range") { select(good().put("minimumUpdaterProtocolVersion", 3).put("updaterProtocolVersion", 2)) }
        // A newer contract that still accepts protocol 1 clients is fine.
        assertTrue(select(good().put("updaterProtocolVersion", 2)) != null)
        assertTrue(select(good().put("updaterProtocolVersion", 2).put("minimumUpdaterProtocolVersion", 2), protocol = 2) != null)
    }

    @Test fun minimumVersionBlocksDirectUpdatesFromOlderBuilds() {
        rejects("cannot update 0.8.0-dev.10 directly") { select(installed = "0.8.0-dev.10") }
        rejects("cannot update 0.8.0-dev.9 directly") { select(installed = "0.8.0-dev.9") }
        assertTrue(select(good().put("minimumVersion", "0.8.0-dev.9"), installed = "0.8.0-dev.10") != null)
    }

    @Test fun manifestIdentityMustBeResound() {
        rejects("not for Resound") { select(good().put("appId", "siphon")) }
        rejects("not for Resound") { select(good().put("displayName", "Siphon")) }
        rejects("architecture") { select(good().put("architecture", "android-x86_64")) }
        assertTrue(select(good().put("architecture", "android-universal")) != null)
        rejects("source tag") { select(good().apply { getJSONObject("source").put("tag", "v0.8.0-dev.13") }) }
    }

    @Test fun exactlyOneResoundAssetWithExactNamesAndUrls() {
        rejects("single Resound APK") { select(good().apply { asset().put("packageId", "com.typezero.resound.devtest") }) }
        rejects("single Resound APK") {
            select(good().apply { put("assets", JSONArray().put(asset()).put(JSONObject(asset().toString()))) })
        }
        rejects("asset name") { select(good().apply { asset().put("fileName", "Resound-latest.apk") }) }
        rejects("signature name") { select(good().apply { signature().put("fileName", "Resound.sig") }) }
        rejects("Untrusted update host") {
            select(good().apply { asset().put("downloadUrl", "https://evil.example/Resound-v0.8.0-dev.12.apk") })
        }
        rejects("Untrusted update host") {
            select(good().apply { signature().put("downloadUrl", "https://evil.example/Resound-v0.8.0-dev.12.apk.sig") })
        }
        rejects("does not match its file name") {
            select(good().apply { asset().put("downloadUrl", "${ManifestFixtures.BASE}/v0.8.0-dev.12/Other.apk") })
        }
        rejects("does not match its file name") {
            select(good().apply { asset().put("downloadUrl", "${ManifestFixtures.BASE}/v0.8.0-dev.12/Resound-v0.8.0-dev.12.apk?x=1") })
        }
        rejects("does not match the Resound release") {
            select(good().apply {
                asset().put("downloadUrl", "https://github.com/Other/Resound/releases/download/v0.8.0-dev.12/Resound-v0.8.0-dev.12.apk")
            })
        }
        rejects("does not match the Resound release") {
            select(good().apply {
                signature().put("downloadUrl", "https://github.com/Other/Resound/releases/download/v0.8.0-dev.12/Resound-v0.8.0-dev.12.apk.sig")
            })
        }
        rejects("does not match the Resound release") {
            select(good().apply {
                asset().put("downloadUrl", "https://github.com/MikereDD/Resound/releases/tag/v0.8.0-dev.12/Resound-v0.8.0-dev.12.apk")
            })
        }
        rejects("does not match the Resound release") {
            select(good().apply {
                asset().put("downloadUrl", "${ManifestFixtures.BASE}/v0.8.0-dev.13/Resound-v0.8.0-dev.12.apk")
            })
        }
        rejects("does not match the Resound release") {
            select(good().apply {
                asset().put("downloadUrl", "${ManifestFixtures.BASE}/v0.8.0-dev.12/%52esound-v0.8.0-dev.12.apk")
            })
        }
        rejects("too large") { select(good().apply { asset().put("size", UpdateTrust.MAX_APK_BYTES + 1) }) }
        rejects("too large") { select(good().apply { signature().put("size", UpdateTrust.MAX_SIGNATURE_BYTES + 1) }) }
    }

    @Test fun releaseKeyAndCertificateMetadataMustMatchLocalPins() {
        rejects("Unsupported release signature algorithm") { select(good().apply { signature().put("algorithm", "ed25519") }) }
        rejects("unapproved key") { select(good().apply { signature().put("keyId", "typezero-resound-release-02") }) }
        rejects("does not match the pinned key") {
            select(good().apply { signature().put("publicKeySha256", TestKeys.fingerprint(TestKeys.other)) })
        }
        rejects("pinned certificate") { select(good().apply { asset().put("signingCertificateSha256", "40e05cb6ec3e2ebebea336b359cf96b2d9c19361df84d99b79e211ec5203e778") }) }
        // Uppercase hex in the manifest is valid schema and still matches.
        assertTrue(select(good().apply { signature().put("publicKeySha256", anchors.releasePublicKeySha256.uppercase()) }) != null)
    }

    @Test fun unconfiguredTrustFailsClosed() {
        rejects("not configured") { select(trust = anchors.copy(releasePublicKeyPem = "")) }
        rejects("not configured") { select(trust = anchors.copy(releasePublicKeySha256 = "")) }
        rejects("not configured") { select(trust = anchors.copy(releaseKeyId = "")) }
        rejects("not configured") { select(trust = anchors.copy(apkCertSha256 = "")) }
    }

    @Test fun thisBuildPinsTheResoundReleaseKeyAndCertificate() {
        val pinned = UpdateTrust.anchors
        assertTrue(pinned.configured)
        assertEquals("typezero-resound-release-01", pinned.releaseKeyId)
        assertEquals("1761e5e580963fdf24bc18803d40cabe1d1f470ea8011282df3e9fc98a039341", pinned.releasePublicKeySha256)
        assertEquals(pinned.releasePublicKeySha256, ReleaseVerifier.publicKeySha256(pinned.releasePublicKeyPem))
        assertEquals(ManifestFixtures.CERT, pinned.apkCertSha256)
        // A manifest naming the real pins is offered; the test key's fingerprint is not.
        assertTrue(select(manifest(publicKeySha256 = pinned.releasePublicKeySha256), trust = pinned) != null)
        rejects("does not match the pinned key") { select(good(), trust = pinned) }
    }

    @Test fun apkAndSignatureUrlsRejectEveryNonCanonicalForm() {
        val v = "0.8.0-dev.12"
        val apk = "Resound-v$v.apk"
        fun variants(name: String) = listOf(
            "https://GitHub.com/MikereDD/Resound/releases/download/v$v/$name",
            "https://github.com/mikeredd/Resound/releases/download/v$v/$name",
            "https://github.com/MikereDD/resound/releases/download/v$v/$name",
            "https://github.com:443/MikereDD/Resound/releases/download/v$v/$name",
            "https://github.com/MikereDD/Resound/releases/download/v$v/./$name",
            "https://github.com/MikereDD/Resound/releases/download/v0.8.0-dev.11/../v$v/$name",
            "https://github.com/MikereDD/Resound/releases/download/v$v%2F$name",
            "https://github.com/MikereDD/Resound/releases/download/v$v/$name?",
            "https://github.com/MikereDD/Resound/releases/download/v$v/$name#x",
            "https://github.com/MikereDD/Resound/releases/download/v$v/$name/",
            "https://github.com/MikereDD/Resound/releases/download/v$v//$name",
            "https://github.com/MikereDD/Resound/releases/latest/download/$name",
            "https://user@github.com/MikereDD/Resound/releases/download/v$v/$name",
            "https://github.com./MikereDD/Resound/releases/download/v$v/$name",
            "http://github.com/MikereDD/Resound/releases/download/v$v/$name",
            "https://objects.githubusercontent.com/MikereDD/Resound/releases/download/v$v/$name",
            "https://raw.githubusercontent.com/MikereDD/Resound/main/$name",
            "https://github.com/MikereDD/Resound-evil/releases/download/v$v/$name",
            "https://github.com/MikereDD/Resound/releases/download/v$v/$name ",
            "https://github.com/MikereDD/Resound/releases/download/v$v/${name.replace(".apk", "%2Eapk")}",
        )
        val accepted = mutableListOf<String>()
        variants(apk).forEach { url ->
            try {
                select(good().apply { asset().put("downloadUrl", url) })
                accepted += "apk $url"
            } catch (_: IllegalArgumentException) {
            }
        }
        variants("$apk.sig").forEach { url ->
            try {
                select(good().apply { signature().put("downloadUrl", url) })
                accepted += "sig $url"
            } catch (_: IllegalArgumentException) {
            }
        }
        assertEquals(emptyList<String>(), accepted)
        rejects("does not match its file name") { select(good().apply { signature().put("downloadUrl", "${ManifestFixtures.BASE}/v$v/$apk") }) }
        rejects("does not match its file name") { select(good().apply { asset().put("downloadUrl", "${ManifestFixtures.BASE}/v$v/$apk.sig") }) }
        assertEquals(apk, select()!!.asset.fileName)
    }
}
