package com.typezero.resound.core.update

import com.typezero.resound.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Development/stable OTA channels, trusted redirects and downloaded-APK identity (dev.11). */
class UpdatePolicyTest {
    private val stable = "https://raw.githubusercontent.com/MikereDD/Resound/main/releases/update.json"
    private val dev = "https://raw.githubusercontent.com/MikereDD/Resound/main/releases/update-dev.json"
    private val cert = "3aaba0c794a652511c65272f7cf9210432a23069f5a27bd2d700c06891bed499"
    private val debugCert = "40e05cb6ec3e2ebebea336b359cf96b2d9c19361df84d99b79e211ec5203e778"

    private fun rejects(reason: String, block: () -> Unit) {
        try { block(); fail("expected rejection: $reason") } catch (e: IllegalArgumentException) {
            assertTrue("${e.message} should mention $reason", e.message!!.contains(reason))
        }
    }

    @Test fun devTestNeverUpdatesAndDevVersionsUseTheDevelopmentManifest() {
        assertNull(UpdatePolicy.channel(isDevtest = true, versionName = "0.8.0-dev.11"))
        assertEquals(UpdatePolicy.Channel.DEVELOPMENT, UpdatePolicy.channel(false, "0.8.0-dev.11"))
        assertEquals(UpdatePolicy.Channel.STABLE, UpdatePolicy.channel(false, "0.8.0"))
        assertEquals(dev, UpdatePolicy.manifestUrl(UpdatePolicy.Channel.DEVELOPMENT, stable, dev))
        assertEquals(stable, UpdatePolicy.manifestUrl(UpdatePolicy.Channel.STABLE, stable, dev))
    }

    @Test fun buildConfigKeepsTheStableEndpointAndPinsTheReleaseCertificate() {
        assertEquals(stable, BuildConfig.UPDATE_MANIFEST_URL)
        assertEquals(dev, BuildConfig.UPDATE_DEV_MANIFEST_URL)
        assertEquals(cert, BuildConfig.UPDATE_RELEASE_CERT_SHA256)
        val channel = UpdatePolicy.channel(BuildConfig.IS_DEVTEST, BuildConfig.VERSION_NAME)
        if (BuildConfig.IS_DEVTEST) assertNull(channel) else assertTrue(channel != null)
    }

    @Test fun onlyExplicitlyTrustedHttpsHostsAreAccepted() {
        UpdatePolicy.requireTrustedHttps(dev)
        UpdatePolicy.requireTrustedHttps("https://github.com/MikereDD/Resound/releases/download/v0.8.0-dev.11/Resound-v0.8.0-dev.11.apk")
        UpdatePolicy.requireTrustedHttps("https://release-assets.githubusercontent.com/github-production-release-asset/1/2?sp=r&sig=x%2By")
        rejects("HTTPS") { UpdatePolicy.requireTrustedHttps("http://github.com/MikereDD/Resound") }
        rejects("Untrusted") { UpdatePolicy.requireTrustedHttps("https://github.com.evil.example/x.apk") }
        rejects("Untrusted") { UpdatePolicy.requireTrustedHttps("https://evil.githubusercontent.com/x.apk") }
        rejects("Invalid update URL") { UpdatePolicy.requireTrustedHttps("https://user@github.com/x.apk") }
        rejects("Invalid update URL") { UpdatePolicy.requireTrustedHttps("https://github.com:8443/x.apk") }
    }

    @Test fun redirectsAreFollowedOnlyToTrustedHttpsTargets() {
        val asset = "https://github.com/MikereDD/Resound/releases/download/v0.8.0-dev.11/Resound-v0.8.0-dev.11.apk"
        val cdn = "https://release-assets.githubusercontent.com/github-production-release-asset/1/2?sp=r&sig=abc%2Bdef"
        assertEquals(cdn, UpdatePolicy.redirectTarget(asset, cdn))
        assertEquals("https://github.com/MikereDD/Resound/releases/x.apk", UpdatePolicy.redirectTarget(asset, "/MikereDD/Resound/releases/x.apk"))
        rejects("Untrusted") { UpdatePolicy.redirectTarget(asset, "https://example.com/Resound.apk") }
        rejects("HTTPS") { UpdatePolicy.redirectTarget(asset, "http://release-assets.githubusercontent.com/x") }
        rejects("location") { UpdatePolicy.redirectTarget(asset, null) }
        assertTrue(listOf(301, 302, 303, 307, 308).all(UpdatePolicy::isRedirect))
        assertFalse(UpdatePolicy.isRedirect(200) || UpdatePolicy.isRedirect(304) || UpdatePolicy.isRedirect(404))
        assertEquals(5, UpdatePolicy.MAX_REDIRECTS)
    }

    private fun check(archive: UpdatePolicy.ApkIdentity, installedCode: Long = 23, installedSigners: Set<String> = setOf(cert)) =
        UpdatePolicy.requireValidUpdate("com.typezero.resound", 24, installedCode, installedSigners, cert, archive)

    @Test fun downloadedApkMustBeTheAnnouncedNewerReleaseSignedResound() {
        val good = UpdatePolicy.ApkIdentity("com.typezero.resound", 24, setOf(cert))
        check(good)
        rejects("package name") { check(good.copy(packageName = "com.typezero.resound.devtest")) }
        rejects("does not match the update manifest") { check(good.copy(versionCode = 25)) }
        rejects("not newer") { check(good, installedCode = 24) }
        rejects("not newer") { check(good, installedCode = 30) }
        rejects("release certificate") { check(good.copy(signers = setOf(debugCert))) }
        rejects("release certificate") { check(good.copy(signers = setOf(cert, debugCert))) }
        rejects("release certificate") { check(good.copy(signers = emptySet())) }
        rejects("installed app") { check(good, installedSigners = setOf(debugCert)) }
    }
}
