package com.typezero.resound.core.update

import com.typezero.resound.core.update.ManifestFixtures.asset
import com.typezero.resound.core.update.ManifestFixtures.manifest
import com.typezero.resound.core.update.ManifestFixtures.parse
import com.typezero.resound.core.update.ManifestFixtures.signature
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Strict schema-2 parsing (schemas/release-manifest.schema.json at standards commit 90f2720a). */
class ReleaseManifestTest {
    private fun rejects(reason: String, mutate: JSONObject.() -> Unit) {
        val json = manifest().apply(mutate)
        try {
            parse(json)
            fail("accepted manifest that should fail: $reason")
        } catch (e: IllegalArgumentException) {
            assertTrue("'${e.message}' should mention '$reason'", e.message.orEmpty().contains(reason))
        }
    }

    private fun rejectsStrictJson(reason: String, text: String) {
        try {
            ReleaseManifest.parse(text)
            fail("accepted invalid strict JSON: $reason")
        } catch (e: IllegalArgumentException) {
            assertTrue("'${e.message}' should mention '$reason'", e.message.orEmpty().contains(reason))
        }
    }

    @Test fun validDevelopmentManifestParses() {
        val m = parse(manifest())
        assertEquals(2, m.schemaVersion)
        assertEquals("resound", m.appId)
        assertEquals("development", m.channel)
        assertEquals("0.8.0-dev.12", m.version)
        assertEquals(1, m.updaterProtocolVersion)
        assertEquals("Resound-v0.8.0-dev.12.apk", m.assets.single().fileName)
        assertEquals("Resound-v0.8.0-dev.12.apk.sig", m.assets.single().signature.fileName)
        assertEquals("v0.8.0-dev.12", m.source.tag)
        assertNull(m.mandatoryReason)
        assertEquals(false, m.rollback.supported)
    }

    @Test fun optionalSchemaFieldsAreAccepted() {
        val m = parse(manifest().apply {
            put("mandatory", true).put("mandatoryReason", "Security fix")
            getJSONObject("rollback").put("minimumRollbackVersion", "0.8.0-dev.11")
        })
        assertEquals("Security fix", m.mandatoryReason)
        assertEquals("0.8.0-dev.11", m.rollback.minimumRollbackVersion)
    }

    @Test fun unknownFieldsAreRejectedAtEveryLevel() {
        rejects("manifest contains unknown") { put("versionCode", 25) }
        rejects("asset contains unknown") { asset().put("apkUrl", "https://github.com/x.apk") }
        rejects("signature contains unknown") { signature().put("publicKey", "x") }
        rejects("source contains unknown") { getJSONObject("source").put("branch", "main") }
        rejects("rollback contains unknown") { getJSONObject("rollback").put("keep", 1) }
    }

    @Test fun missingRequiredFieldsAreRejected() {
        listOf(
            "schemaVersion", "appId", "displayName", "platform", "architecture", "channel", "version",
            "publishedAt", "minimumVersion", "updaterProtocolVersion", "minimumUpdaterProtocolVersion",
            "mandatory", "releaseNotesUrl", "changelogUrl", "assets", "source", "rollback",
        ).forEach { key -> rejects("missing required") { remove(key) } }
        listOf("fileName", "downloadUrl", "size", "sha256", "signature", "packageId", "signingCertificateSha256")
            .forEach { key -> rejects("missing required") { asset().remove(key) } }
        listOf("algorithm", "fileName", "downloadUrl", "size", "sha256", "keyId", "publicKeySha256")
            .forEach { key -> rejects("missing required") { signature().remove(key) } }
        rejects("missing required") { getJSONObject("source").remove("commit") }
        rejects("missing required") { getJSONObject("rollback").remove("retainVersions") }
    }

    @Test fun typesAreExactWithoutCoercion() {
        rejects("schemaVersion") { put("schemaVersion", "2") }
        rejects("size") { asset().put("size", "45000000") }
        rejects("mandatory") { put("mandatory", "false") }
        rejects("updaterProtocolVersion") { put("updaterProtocolVersion", JSONObject.NULL) }
        rejects("assets") { put("assets", JSONObject()) }
        rejects("source") { put("source", "https://github.com/MikereDD/Resound") }
        rejects("appId") { put("appId", "") }
    }

    /** org.json writes 2.0 as "2", so non-integer numbers are tested as raw manifest text. */
    @Test fun nonIntegerNumbersAreRejected() {
        val text = manifest().toString()
        listOf(
            "\"schemaVersion\":2" to "\"schemaVersion\":2.0",
            "\"size\":45000000" to "\"size\":4.5E7",
            "\"retainVersions\":0" to "\"retainVersions\":0.0",
        ).forEach { (from, to) ->
            assertTrue("fixture contains $from", text.contains(from))
            try {
                ReleaseManifest.parse(text.replace(from, to))
                fail("accepted $to")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!, e.message!!.contains(from.substringAfter('"').substringBefore('"')))
            }
        }
    }

    @Test fun schemaValuesArePatternChecked() {
        rejects("Unsupported release manifest schema") { put("schemaVersion", 1) }
        rejects("Unsupported release manifest schema") { put("schemaVersion", 3) }
        rejects("appId") { put("appId", "Resound") }
        rejects("not for Android") { put("platform", "windows") }
        rejects("Invalid manifest platform") { put("platform", "linux") }
        rejects("architecture") { put("architecture", "arm64-v8a") }
        rejects("architecture") { put("architecture", "win-x64") }
        rejects("channel") { put("channel", "beta") }
        rejects("version") { put("version", "0.8.0-dev") }
        rejects("minimumVersion") { put("minimumVersion", "latest") }
        rejects("publishedAt") { put("publishedAt", "yesterday") }
        rejects("updater protocol") { put("updaterProtocolVersion", 0) }
        rejects("reason") { put("mandatory", true) }
        rejects("releaseNotesUrl") { put("releaseNotesUrl", "http://example.com/notes") }
        rejects("changelogUrl") { put("changelogUrl", "../CHANGELOG.md") }
        rejects("changelogUrl") { put("changelogUrl", "/etc/passwd") }
        rejects("no assets") { put("assets", org.json.JSONArray()) }
        rejects("downloadUrl") { asset().put("downloadUrl", "http://github.com/x.apk") }
        rejects("size") { asset().put("size", 0) }
        rejects("sha256") { asset().put("sha256", "z".repeat(64)) }
        rejects("sha256") { asset().put("sha256", "a".repeat(63)) }
        rejects("packageId") { asset().put("packageId", "resound") }
        rejects("signingCertificateSha256") { asset().put("signingCertificateSha256", "AB:CD") }
        rejects("signature algorithm") { signature().put("algorithm", "md5") }
        rejects("keyId") { signature().put("keyId", "key id with spaces") }
        rejects("publicKeySha256") { signature().put("publicKeySha256", "") }
        rejects("downloadUrl") { signature().put("downloadUrl", "ftp://github.com/x.sig") }
        rejects("tag") { getJSONObject("source").put("tag", "0.8.0-dev.12") }
        rejects("commit") { getJSONObject("source").put("commit", "e550806") }
        rejects("rollback") { getJSONObject("rollback").put("supported", true) }
        rejects("rollback") { getJSONObject("rollback").put("retainVersions", 1) }
    }

    @Test fun repositoryExampleManifestIsValid() {
        val example = java.io.File("../releases/release-manifest.example.json")
        assertTrue("run from the app module: ${example.absolutePath}", example.isFile)
        val m = ReleaseManifest.parse(example.readText())
        assertEquals("resound", m.appId)
        assertEquals("Resound-v${m.version}.apk", m.assets.single().fileName)
    }

    @Test fun malformedOrOversizedDocumentsAreRejected() {
        listOf("", "[]", "not json", "{\"schemaVersion\": 2").forEach { text ->
            try {
                ReleaseManifest.parse(text)
                fail("accepted '$text'")
            } catch (expected: Exception) {
            }
        }
        try {
            ReleaseManifest.parse(" ".repeat(ReleaseManifest.MAX_BYTES + 1))
            fail("accepted an oversized manifest")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("too large"))
        }
    }

    @Test fun strictJsonSyntaxIsEnforcedBeforeSchemaValidation() {
        val valid = manifest().toString()
        assertTrue(valid.contains("\"appId\":\"resound\""))
        rejectsStrictJson(
            "Duplicate JSON key",
            valid.replace("\"appId\":\"resound\"", "\"appId\":\"resound\",\"appId\":\"resound\""),
        )
        rejectsStrictJson(
            "Duplicate JSON key",
            valid.replace("\"size\":45000000", "\"size\":45000000,\"size\":1"),
        )
        rejectsStrictJson("Trailing content", valid + " \n\t{}")
        rejectsStrictJson("Invalid strict JSON", valid.replace("\"appId\":\"resound\"", "\"appId\":// comment\n\"resound\""))
        rejectsStrictJson("Invalid strict JSON", valid.replace("\"appId\":\"resound\"", "'appId':'resound'"))
        rejectsStrictJson("Invalid strict JSON", valid.replace("\"appId\":\"resound\"", "appId:\"resound\""))
        rejectsStrictJson("Invalid strict JSON", valid.replace("\"schemaVersion\":2", "\"schemaVersion\":02"))
        rejectsStrictJson("Invalid strict JSON", valid.dropLast(1) + ",}")
        assertEquals("resound", ReleaseManifest.parse(valid).appId)
    }

    @Test fun decodedDuplicateKeysAreRejectedThroughoutSchema2() {
        val valid = manifest().toString()
        listOf(
            "\"appId\":\"resound\"" to "\"appId\":\"resound\",\"\\u0061ppId\":\"resound\"",
            "\"algorithm\":\"rsa-sha256\"" to "\"algorithm\":\"rsa-sha256\",\"algorithm\":\"rsa-sha256\"",
            "\"tag\":\"v0.8.0-dev.12\"" to "\"tag\":\"v0.8.0-dev.12\",\"tag\":\"v0.8.0-dev.12\"",
            "\"retainVersions\":0" to "\"retainVersions\":0,\"retainVersions\":0",
        ).forEach { (from, to) ->
            assertTrue("fixture contains $from", valid.contains(from))
            rejectsStrictJson("Duplicate JSON key", valid.replace(from, to))
        }
    }

    @Test fun integerSchemaFieldsCannotBeNarrowedFromOtherNumberTokens() {
        val valid = manifest().toString()
        val zero = "\"retainVersions\":0"
        assertTrue(valid.contains(zero))
        listOf("-0", "0.0", "0e0").forEach { token ->
            rejectsStrictJson("retainVersions", valid.replace(zero, "\"retainVersions\":$token"))
        }
    }

    @Test fun validUnicodeAndEscapedSchemaKeysRemainSupported() {
        val reason = "Music \uD834\uDD1E\nSecurity update"
        val valid = manifest().put("mandatory", true).put("mandatoryReason", reason).toString()
        val escaped = valid.replace("\"appId\":", "\"\\u0061ppId\":")
        assertTrue(valid != escaped)
        val parsed = ReleaseManifest.parse(" \r\n\t$escaped\n")
        assertEquals("resound", parsed.appId)
        assertEquals(reason, parsed.mandatoryReason)
    }

    @Test fun manifestBytesMustBeStrictUtf8() {
        val valid = manifest().put("mandatory", true).put("mandatoryReason", "Caf\u00e9 \uD834\uDD1E").toString()
        assertEquals("Caf\u00e9 \uD834\uDD1E", ReleaseManifest.parseUtf8(valid.toByteArray(Charsets.UTF_8)).mandatoryReason)
        val at = valid.toByteArray(Charsets.UTF_8).indexOfFirst { it == 'C'.code.toByte() }
        listOf(
            byteArrayOf(0xFF.toByte()),                                // invalid byte
            byteArrayOf(0xC0.toByte(), 0xAF.toByte()),                 // overlong '/'
            byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()),  // encoded surrogate
            byteArrayOf(0xE2.toByte(), 0x82.toByte()),                 // truncated sequence
        ).forEach { bad ->
            val bytes = valid.toByteArray(Charsets.UTF_8).let { it.copyOfRange(0, at) + bad + it.copyOfRange(at, it.size) }
            try {
                ReleaseManifest.parseUtf8(bytes)
                fail("accepted malformed UTF-8 ${bad.joinToString { "%02x".format(it) }}")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message.orEmpty().contains("not valid UTF-8"))
            }
        }
        try {
            ReleaseManifest.parseUtf8(ByteArray(ReleaseManifest.MAX_BYTES + 1) { ' '.code.toByte() })
            fail("oversized bytes accepted")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("too large"))
        }
    }
}
