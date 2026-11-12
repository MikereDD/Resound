package com.typezero.resound.core.update

import com.typezero.resound.core.update.ManifestFixtures.parse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyPair
import kotlin.random.Random

/**
 * Detached release-signature verification and the standard's tamper cases, on throwaway copies
 * signed with throwaway keys: altered APK, altered/truncated/garbage signature, unapproved key,
 * pinned-key mismatch and exact names and sizes.
 */
class ReleaseVerifierTest {
    @get:Rule val tmp = TemporaryFolder()

    private val anchors = TestKeys.anchors()
    private val apkBytes = Random(11).nextBytes(200_000)

    private inner class Release(
        apk: ByteArray = apkBytes,
        signedBy: KeyPair = TestKeys.approved,
        signature: ByteArray = TestKeys.sign(signedBy, apk),
        val manifestApk: ByteArray = apk,
        val manifestSignature: ByteArray = signature,
        publicKeySha256: String = anchors.releasePublicKeySha256,
        keyId: String = ManifestFixtures.KEY_ID,
        apkName: String = "Resound-v0.8.0-dev.12.apk",
        signatureName: String = "$apkName.sig",
    ) {
        val dir: File = tmp.newFolder()
        val apkFile = File(dir, apkName).apply { writeBytes(apk) }
        val signatureFile = File(dir, signatureName).apply { writeBytes(signature) }
        val asset = parse(
            ManifestFixtures.manifest(
                apkSha256 = TestKeys.sha256(manifestApk),
                apkSize = manifestApk.size.toLong(),
                sigSha256 = TestKeys.sha256(manifestSignature),
                sigSize = manifestSignature.size.toLong(),
                publicKeySha256 = publicKeySha256,
            ).apply { getJSONArray("assets").getJSONObject(0).getJSONObject("signature").put("keyId", keyId) },
        ).assets.single()

        fun verify(trust: TrustAnchors = anchors) = ReleaseVerifier.verify(apkFile, signatureFile, asset, trust)
    }

    private fun rejects(reason: String, block: () -> Unit) {
        try {
            block()
            fail("expected rejection: $reason")
        } catch (e: IllegalArgumentException) {
            assertTrue("'${e.message}' should mention '$reason'", e.message.orEmpty().contains(reason))
        }
    }

    private fun ByteArray.flipped(index: Int) = copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }

    @Test fun genuineReleasePasses() {
        Release().verify()
    }

    @Test fun pinnedFingerprintIsTheSpkiSha256() {
        assertEquals(TestKeys.fingerprint(TestKeys.approved), ReleaseVerifier.publicKeySha256(TestKeys.pem(TestKeys.approved)))
        assertEquals(64, anchors.releasePublicKeySha256.length)
    }

    @Test fun alteredApkFailsSha256() {
        val release = Release(manifestApk = apkBytes)
        release.apkFile.writeBytes(apkBytes.flipped(1234))
        rejects("APK failed SHA-256") { release.verify() }
    }

    @Test fun alteredApkWithMatchingManifestHashFailsTheSignature() {
        val altered = apkBytes.flipped(99_999)
        rejects("Detached release signature verification failed") {
            Release(apk = altered, signature = TestKeys.sign(TestKeys.approved, apkBytes)).verify()
        }
    }

    @Test fun alteredSignatureFailsSha256AndThenTheSignature() {
        val good = TestKeys.sign(TestKeys.approved, apkBytes)
        val release = Release()
        release.signatureFile.writeBytes(good.flipped(10))
        rejects("signature failed SHA-256") { release.verify() }
        rejects("Detached release signature verification failed") { Release(signature = good.flipped(10)).verify() }
    }

    @Test fun truncatedEmptyOrGarbageSignaturesFail() {
        val good = TestKeys.sign(TestKeys.approved, apkBytes)
        rejects("Detached release signature verification failed") { Release(signature = good.copyOf(good.size - 1)).verify() }
        rejects("Detached release signature verification failed") { Release(signature = byteArrayOf(0)).verify() }
        rejects("Detached release signature verification failed") { Release(signature = Random(3).nextBytes(512)).verify() }
    }

    @Test fun releaseSignedByAnUnapprovedKeyFails() {
        // Correct metadata for the approved key, but signed by another key.
        rejects("Detached release signature verification failed") { Release(signedBy = TestKeys.other).verify() }
        // Metadata naming the other key is rejected before any cryptography.
        rejects("does not match the pinned key") {
            Release(signedBy = TestKeys.other, publicKeySha256 = TestKeys.fingerprint(TestKeys.other)).verify()
        }
        rejects("unapproved key") { Release(keyId = "typezero-resound-release-02").verify() }
    }

    @Test fun pinnedKeyAndPinnedFingerprintMustAgree() {
        rejects("does not match the pinned key") {
            Release().verify(anchors.copy(releasePublicKeyPem = TestKeys.pem(TestKeys.other)))
        }
        rejects("not configured") { Release().verify(anchors.copy(releasePublicKeySha256 = "")) }
    }

    @Test fun exactNamesAndSizesAreRequired() {
        rejects("file names") { Release(apkName = "Resound.apk").verify() }
        rejects("file names") { Release(signatureName = "Resound-v0.8.0-dev.12.sig").verify() }
        val release = Release()
        release.apkFile.appendBytes(byteArrayOf(0))
        rejects("APK size") { release.verify() }
        val release2 = Release()
        release2.signatureFile.appendBytes(byteArrayOf(0))
        rejects("signature size") { release2.verify() }
    }
}
