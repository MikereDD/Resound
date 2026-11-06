package com.typezero.resound.feature.timeline.draft

import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

class CorruptArrangement(message: String) : IOException(message)

/**
 * Automatic recovery metadata, separate from audio and explicit user saves.
 * Two committed slots: always replace the absent/corrupt/older slot, retaining the latest valid one.
 * A stage is not committed until ATOMIC_MOVE succeeds. No unsafe fallback on unsupported filesystems.
 * All calls (including construction/directory resolution) belong on IO. Single-process application;
 * same-directory instances share a lock. No audio file is opened/deleted here.
 */
class ArrangementStore(
    private val directory: File,
    private val beforePublish: (File) -> Unit = {}, // fault-injection seam: stage already flushed/synced
) {
    private val lock = locks.computeIfAbsent(directory.absoluteFile.normalize().path) { Any() }
    private val slots get() = listOf(File(directory, "active-a.draft"), File(directory, "active-b.draft"))

    fun load(): ArrangementDraft? = synchronized(lock) { readSlots().maxByOrNull { it.second.revision }?.second }

    /** False means a stale/already committed revision. Never overwrites an unreadable future schema. */
    fun save(draft: ArrangementDraft): Boolean = synchronized(lock) {
        val bytes = ArrangementCodec.encode(draft)
        val existing = readSlots()
        if (existing.any { it.second.revision >= draft.revision }) return@synchronized false
        check(directory.isDirectory || directory.mkdirs()) { "Could not create arrangement recovery directory" }
        val target = slots.firstOrNull { slot -> existing.none { it.first == slot } }
            ?: existing.minBy { it.second.revision }.first
        val stage = File.createTempFile("active-", ".pending", directory)
        try {
            FileOutputStream(stage).use { out -> out.write(bytes); out.flush(); out.fd.sync() }
            beforePublish(stage)
            Files.move(stage.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            true
        } finally {
            // Only our unique stage, never old committed snapshots/audio/user media.
            stage.delete()
        }
    }

    private fun readSlots(): List<Pair<File, ArrangementDraft>> {
        var present = 0
        val valid = ArrayList<Pair<File, ArrangementDraft>>()
        for (slot in slots) {
            if (!slot.exists()) continue
            present++
            if (!slot.isFile) throw CorruptArrangement("Arrangement slot is not a file")
            if (slot.length() > ArrangementCodec.MAX_BYTES) {
                throw CorruptArrangement("Oversized arrangement slot; originals retained, recovery/editing blocked")
            }
            val bytes = slot.readBytes() // inaccessible files are IO failures, not permission to overwrite them
            try {
                valid += slot to ArrangementCodec.decode(bytes)
            } catch (future: UnsupportedDraftSchema) {
                throw future // do not replace user data written by a newer application
            } catch (_: IllegalArgumentException) {
                // Bad checksum/ranges: other slot can still recover the previous valid revision.
            } catch (_: EOFException) {
                // Truncated payload (other slot remains valid).
            } catch (_: UTFDataFormatException) {
                // Malformed modified-UTF string in corrupt data.
            }
        }
        if (present > 0 && valid.isEmpty()) throw CorruptArrangement("No valid arrangement snapshot; originals retained")
        return valid
    }

    private companion object { val locks = ConcurrentHashMap<String, Any>() }
}
