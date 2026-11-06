package com.typezero.resound.core.update

import com.typezero.resound.BuildConfig

/**
 * Locally pinned update trust. Remote manifests are only compared with these values; they can
 * never add or replace a trust anchor. The APK signing certificate is pinned in BuildConfig.
 */
object UpdateTrust {
    const val MANIFEST_SCHEMA_VERSION = 2

    /** Resound updater contract revision. Raise it when an older updater could no longer complete an update. */
    const val UPDATER_PROTOCOL_VERSION = 1

    const val APP_ID = "resound"
    const val DISPLAY_NAME = "Resound"
    const val PACKAGE_ID = "com.typezero.resound"
    val ARCHITECTURES = setOf("android-arm64-v8a", "android-universal")

    const val SIGNATURE_ALGORITHM = "rsa-sha256"
    const val RELEASE_KEY_ID = "typezero-resound-release-01"

    /** SHA-256 of the DER SubjectPublicKeyInfo of [RELEASE_PUBLIC_KEY_PEM] (RSA-4096). */
    const val RELEASE_PUBLIC_KEY_SHA256 = "1761e5e580963fdf24bc18803d40cabe1d1f470ea8011282df3e9fc98a039341"

    /** Public half of the Resound release-signing key; the private key never leaves the release workstation. */
    val RELEASE_PUBLIC_KEY_PEM = """
        -----BEGIN PUBLIC KEY-----
        MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAxgkvWq3vvsjUPyG4B4ow
        cBbT7BGG4AIqCZfUVXgkzCOqXDTd/GZIHkE1+OgDT34GG71F2YeUxLCM7qXJJAKo
        cFybxUPpEtoxboQjBccQDbL5vFyVKKU9eEtK6P5Jhu1bBojHa+UNEe7uFbj77f3C
        g6m4+Os4pi4BmbmUGKu8r1A8kyUwKYSpKn5seHaYszHNbKJHnoGLouuCN190al1H
        0xTIA9o82Jwj/SvKHRrrtiXvb0yYHn0M8MHPlcE/D/KPNvkrp4ITOQyaEG57JYoV
        IZFVaPCFSo0tkojWRkjaBSnrV2A4O2zdTKGKvm/lFQLJEHK2CG8o4IXn3sScjDxA
        elu/US5+wtQmecMsgBH7o9h9pSl1kifdgz3Yll+JK9bgNoOmhu8Dy1UapdfPVv6R
        RtKz0Rhh/rP0dCxcONR7+8CFvGdXgONHkSVS3efN5t9oilUN2+Gs9jTdrsp8ukV6
        Salo+CrhZbIA+1hX/VY46jouWpbos2hcYpcdAMd3FmQgb+9et69firJ8gar1CMVZ
        6hDNnX5pJgNDg5DZ/lXrcwxKdFZkbeoeXy8dLswF3Ffbn4vQnyMeOX2B9LdfVp/Q
        XNEzXtOGtM8jIesY6IJ2CPhPrjYFeOH+7Nzmg9HbD2dnyvsaEm9rm4b83lpTXFXP
        GOJIb5PtZlHeTuHW/GlozBkCAwEAAQ==
        -----END PUBLIC KEY-----
    """.trimIndent()

    const val MAX_APK_BYTES = 256L * 1024 * 1024
    const val MAX_SIGNATURE_BYTES = 1024L

    val anchors: TrustAnchors
        get() = TrustAnchors(RELEASE_KEY_ID, RELEASE_PUBLIC_KEY_PEM, RELEASE_PUBLIC_KEY_SHA256, BuildConfig.UPDATE_RELEASE_CERT_SHA256)
}

/** The trust a build carries; tests substitute their own keys. */
data class TrustAnchors(
    val releaseKeyId: String,
    val releasePublicKeyPem: String,
    val releasePublicKeySha256: String,
    val apkCertSha256: String,
) {
    val configured: Boolean
        get() = releaseKeyId.isNotBlank() &&
            SHA256.matches(releasePublicKeySha256) &&
            releasePublicKeyPem.contains("-----BEGIN PUBLIC KEY-----") &&
            SHA256.matches(apkCertSha256)

    private companion object {
        val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}
