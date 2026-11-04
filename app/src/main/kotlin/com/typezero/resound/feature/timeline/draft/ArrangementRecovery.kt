package com.typezero.resound.feature.timeline.draft

data class RecoveredArrangement(val draft: ArrangementDraft, val missingClipIds: Set<Long>)
object ArrangementRecovery {
    /** Never drops a clip. Missing/revoked sources become explicit unavailable placeholders in UI. */
    fun inspect(draft: ArrangementDraft, canRead: (DraftSource) -> Boolean): RecoveredArrangement {
        val readability = HashMap<String, Boolean>()
        val missing = draft.tracks.flatMap { it.clips }.filter { clip ->
            !readability.getOrPut(clip.source.uri) {
                try { canRead(clip.source) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { false }
            }
        }.map { it.id }.toSet()
        return RecoveredArrangement(draft, missing)
    }
}
