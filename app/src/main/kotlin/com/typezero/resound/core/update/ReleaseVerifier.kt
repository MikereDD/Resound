package com.typezero.resound.core.update

import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Verifies a downloaded release before Android's installer sees it, in the standard's order:
 * exact names and sizes, APK SHA-256, signature-file SHA-256, pinned release-key identity,
 * then the detached RSA-SHA256 signature over the APK. Android package identity and the APK
 * signing certificate are checked afterwards by [UpdatePolicy.requireValidUpdate].
 */
object ReleaseVerifier {

    fun verify(apk: File, signatureFile: File, asset: ReleaseManifest.Asset, anchors: TrustAnchors) {
        require(anchors.configured) { "Release trust anchors are not configured in this build" }
        val signature = asset.signature
        require(apk.name == asset.fileName && signatureFile.name == signature.fileName) {
            "Downloaded file names do not match the release"
        }
        require(apk.length() == asset.size) { "Downloaded APK size does not match the release" }
        require(signatureFile.length() == signature.size) { "Downloaded signature size does not match the release" }
        require(sha256(apk).equals(asset.sha256, ignoreCase = true)) { "Downloaded APK failed SHA-256 verification" }
        require(sha256(signatureFile).equals(signature.sha256, ignoreCase = true)) {
            "Downloaded signature failed SHA-256 verification"
        }
        require(signature.algorithm == UpdateTrust.SIGNATURE_ALGORITHM && signature.keyId == anchors.releaseKeyId) {
            "Release is signed with an unapproved key"
        }
        val key = publicKey(anchors.releasePublicKeyPem)
        val fingerprint = hex(MessageDigest.getInstance("SHA-256").digest(key.encoded))
        require(
            fingerprint == anchors.releasePublicKeySha256 &&
                fingerprint.equals(signature.publicKeySha256, ignoreCase = true),
        ) { "Release signing key does not match the pinned key" }

        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(key)
        apk.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                verifier.update(buffer, 0, read)
            }
        }
        val valid = runCatching { verifier.verify(signatureFile.readBytes()) }.getOrDefault(false)
        require(valid) { "Detached release signature verification failed" }
    }

    /** SHA-256 of the DER SubjectPublicKeyInfo, lowercase hex: the key's canonical fingerprint. */
    fun publicKeySha256(pem: String): String = hex(MessageDigest.getInstance("SHA-256").digest(publicKey(pem).encoded))

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return hex(digest.digest())
    }

    private fun publicKey(pem: String): PublicKey {
        val body = pem.trim()
        require(body.startsWith("-----BEGIN PUBLIC KEY-----") && body.endsWith("-----END PUBLIC KEY-----")) {
            "Invalid pinned release public key"
        }
        val der = Base64.getMimeDecoder().decode(
            body.removePrefix("-----BEGIN PUBLIC KEY-----").removeSuffix("-----END PUBLIC KEY-----"),
        )
        return KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
