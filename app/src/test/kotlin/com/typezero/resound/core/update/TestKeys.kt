package com.typezero.resound.core.update

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64

/** Throwaway RSA keys for host tests; never related to the real Resound release key. */
object TestKeys {
    val approved: KeyPair by lazy { generate(4096) }
    val other: KeyPair by lazy { generate(2048) }

    private fun generate(bits: Int): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(bits) }.generateKeyPair()

    fun pem(keys: KeyPair): String =
        "-----BEGIN PUBLIC KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keys.public.encoded) +
            "\n-----END PUBLIC KEY-----\n"

    fun fingerprint(keys: KeyPair): String = sha256(keys.public.encoded)

    fun sign(keys: KeyPair, data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withRSA").run { initSign(keys.private); update(data); sign() }

    fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    fun anchors(keys: KeyPair = approved, keyId: String = ManifestFixtures.KEY_ID) =
        TrustAnchors(keyId, pem(keys), fingerprint(keys), ManifestFixtures.CERT)
}
