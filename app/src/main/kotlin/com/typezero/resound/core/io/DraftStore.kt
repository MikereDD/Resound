/*
 * file:    DraftStore.kt
 * desc:    App-private recording drafts (filesDir, NOT cache). Receipts are untrusted
 *          references, not proof of publication or permission to delete local audio.
 *          Only a readable finalized byte-identical separate copy counts as published.
 *          Unverifiable copies remain discoverable; lifecycle deletion is disabled.
 *          Directory/provider access and verification belong on IO, never Main.
 */
package com.typezero.resound.core.io

import android.content.Context
import com.typezero.resound.feature.record.RecordingRecoveryAdmission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class DraftStore private constructor(
    private val dirProvider: () -> File,
    private val openFinalizedCopy: (java.net.URI) -> java.io.InputStream?,
    private val admission: RecordingRecoveryAdmission,
) {
    /** Resolved/canonicalized only by IO callers, never during composition. */
    private val dir: File by lazy { dirProvider().canonicalFile }

    constructor(dir: File, admission: RecordingRecoveryAdmission = RecordingRecoveryAdmission()) :
        this({ dir }, ::openLocalCopy, admission)
    internal constructor(dir: File, reader: (java.net.URI) -> java.io.InputStream?) :
        this({ dir }, reader, RecordingRecoveryAdmission())

    companion object {
        internal fun forContextForTest(provider: () -> File) = DraftStore(provider, ::openLocalCopy, RecordingRecoveryAdmission())
        private fun openLocalCopy(uri: java.net.URI): java.io.InputStream? =
            if (uri.scheme == "file") File(uri).inputStream() else null

        /** No directory, resolver or provider access until an IO caller uses the store. */
        fun forContext(context: Context, admission: RecordingRecoveryAdmission): DraftStore = DraftStore({ File(context.filesDir, "drafts") }, reader@ { reference ->
            val uri = android.net.Uri.parse(reference.toString())
            val resolver = context.contentResolver
            if (reference.scheme == "content" && android.os.Build.VERSION.SDK_INT >= 29) {
                val cursor = resolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.IS_PENDING), null, null, null)
                    ?: return@reader null
                cursor.use {
                    val column = it.getColumnIndex(android.provider.MediaStore.MediaColumns.IS_PENDING)
                    if (!it.moveToFirst() || column < 0 || it.isNull(column) || it.getInt(column) != 0) return@reader null
                }
            }
            resolver.openInputStream(uri)
        }, admission)
        private const val RECEIPT = ".saved"
        private const val MAX_RECEIPT_BYTES = 8192
    }

    fun newFile(prefix: String, ext: String): File {
        require(ext.matches(Regex("[A-Za-z0-9]{1,10}"))) { "Invalid audio extension" }
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("Could not create draft storage")
        return File.createTempFile("${prefix}_", ".$ext", dir)
    }

    private fun receipt(f: File) = File(f.path + RECEIPT)
    private fun isDraftFile(f: File) = f.isFile && !f.name.endsWith(RECEIPT) && !f.name.endsWith(".tmp")

    /** Record a reference only after verifying a separate publication; never delete the draft. */
    fun markPublished(draft: File, receiptText: String) {
        require(receiptText.isNotBlank()) { "Receipt must not be blank" }
        if (!publicationMatches(draft, receiptText)) {
            throw IOException("Publication could not be verified; recording draft retained")
        }
        val target = receipt(draft)
        val tmp = File(target.path + ".tmp")
        try {
            tmp.writeText(receiptText)
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            if (target.readText() != receiptText) throw IOException("Receipt verification failed")
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
            throw if (t is IOException) t else IOException("Could not persist publish receipt", t)
        }
    }

    /** A receipt is only a reference: require a readable, finalized, byte-identical separate copy. */
    fun isPublished(draft: File): Boolean = try {
        val r = receipt(draft)
        if (!r.isFile || r.length() !in 1..MAX_RECEIPT_BYTES.toLong()) false else {
            val bytes = ByteArray(MAX_RECEIPT_BYTES + 1)
            val size = r.inputStream().use { input ->
                var count = 0
                while (count < bytes.size) {
                    val n = input.read(bytes, count, bytes.size - count)
                    if (n < 0) break
                    count += n
                }
                count
            }
            if (size > MAX_RECEIPT_BYTES) false else {
                val text = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes, 0, size)).toString()
                publicationMatches(draft, text)
            }
        }
    } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
      catch (_: Exception) { false } // missing/denied/corrupt/provider failure means keep the draft

    private fun publicationMatches(draft: File, reference: String): Boolean = try {
        if (!isDraftFile(draft) || draft.length() <= 0L || reference.toByteArray(Charsets.UTF_8).size !in 1..MAX_RECEIPT_BYTES) false else {
            val uri = java.net.URI(reference)
            if (uri.isOpaque || uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null || uri.port != -1) false
            else {
                val allowed = when (uri.scheme) {
                    "content" -> uri.rawAuthority == "media" &&
                        uri.path.matches(Regex("/[A-Za-z0-9_-]+/audio/media/[1-9][0-9]*")) &&
                        uri.path.substringAfterLast('/').toLongOrNull() != null
                    "file" -> uri.rawAuthority.isNullOrEmpty() &&
                        !File(uri).canonicalFile.toPath().startsWith(dir.canonicalFile.toPath())
                    else -> false
                }
                if (!allowed) false else draft.inputStream().use { source ->
                    openFinalizedCopy(uri)?.use { published -> sameBytes(source, published) } ?: false
                }
            }
        }
    } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
      catch (_: Exception) { false }

    /** Short provider reads must not be mistaken for a mismatch; no whole-audio allocation. */
    private fun sameBytes(source: java.io.InputStream, published: java.io.InputStream): Boolean {
        val expected = ByteArray(8192)
        val actual = ByteArray(8192)
        while (true) {
            val count = source.read(expected)
            if (count < 0) return published.read() == -1
            var offset = 0
            while (offset < count) {
                val n = published.read(actual, offset, count - offset)
                if (n < 0) return false
                if (n == 0) {
                    val one = published.read()
                    if (one < 0) return false
                    actual[offset++] = one.toByte()
                } else offset += n
            }
            for (i in 0 until count) if (expected[i] != actual[i]) return false
        }
    }

    /** Canonical direct-child ownership validation. Caller must be on IO. */
    private fun canonicalDraft(file: File): File = file.canonicalFile.also {
        require(it.parentFile == dir && !it.name.endsWith(RECEIPT) && !it.name.endsWith(".tmp")) {
            "Recovery path is outside owned draft storage"
        }
    }

    /** Snapshot for a specific finalized writer generation, not authority to perform an action. */
    fun candidate(file: File, expectedGeneration: Long? = null): RecordingRecoveryAdmission.Candidate? =
        admission.candidate(canonicalDraft(file))?.takeIf { expectedGeneration == null || it.generation == expectedGeneration }

    /**
     * Actual action interval, including suspension. Acquire on IO, retain locally before the
     * dispatcher return, and close identity-specifically even if return delivery is cancelled.
     * Native producers must be awaited by the action; unknown retirement must retain the lease.
     */
    suspend fun <T> withRecovery(
        candidate: RecordingRecoveryAdmission.Candidate,
        action: suspend (File, RecordingRecoveryAdmission.Lease) -> T,
    ): T {
        var lease: RecordingRecoveryAdmission.Lease? = null
        try {
            withContext(Dispatchers.IO) {
                require(canonicalDraft(candidate.file) == candidate.file) { "Recovery path changed" }
                lease = admission.acquire(candidate)
            }
            return action(candidate.file, checkNotNull(lease))
        } finally { lease?.close() }
    }

    /** Only quiescent candidates are inspected for publication; listing itself also leases reads. */
    fun listCandidates(): List<RecordingRecoveryAdmission.Candidate> = inspectCandidates { admission.tryInspection(it) }

    private suspend fun observedCandidates(): List<RecordingRecoveryAdmission.Candidate> =
        inspectCandidates { admission.acquire(it, inspection = true) }

    private inline fun inspectCandidates(acquire: (RecordingRecoveryAdmission.Candidate) -> RecordingRecoveryAdmission.Lease?): List<RecordingRecoveryAdmission.Candidate> =
        (dir.listFiles() ?: emptyArray()).mapNotNull { file ->
            val candidate = try { candidate(file) } catch (_: IllegalArgumentException) { null }
                ?: return@mapNotNull null
            val lease = try { acquire(candidate) } catch (_: IllegalStateException) { null } ?: return@mapNotNull null
            try {
                if (isDraftFile(candidate.file) && candidate.file.length() > 0L && !isPublished(candidate.file)) candidate else null
            } finally { lease.close() }
        }.sortedBy { it.file.lastModified() }

    data class RecoveryListing(
        val candidates: List<RecordingRecoveryAdmission.Candidate>,
        val protections: List<RecordingRecoveryAdmission.Protection> = emptyList(),
    )

    /**
     * The same replacement observer sees later terminal/uncertain writer outcomes. Listing
     * and provider/canonical work stay on IO. Temporary listing leases never publish updates.
     */
    fun recoveryUpdates(): Flow<RecoveryListing> = flow {
        admission.lifecycleChanges.collect {
            emit(withContext(Dispatchers.IO) {
                RecoveryListing(observedCandidates(), admission.protections().filter { it.file.parentFile == dir })
            })
        }
    }

    /** Explicit Save/Discard refresh; observing a listing must not trigger a feedback loop. */
    fun requestRecoveryRefresh() = admission.notifyLifecycleChange()

    /** Compatibility only; UI keeps versioned candidates through callback dispatch. */
    fun list(): List<File> = listCandidates().map { it.file }

    /**
     * Compatibility entry point only: automatic deletion is disabled even for verified copies.
     * A provider can disappear after verification, and another editor/arrangement may reference the
     * original. Lifecycle callers must only list recovery drafts; explicit user Discard is separate.
     */
    @Suppress("UNUSED_PARAMETER")
    fun purgePublished(inUse: Set<File> = emptySet()): Int = 0
}
