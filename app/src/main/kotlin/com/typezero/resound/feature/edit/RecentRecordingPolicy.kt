package com.typezero.resound.feature.edit

import kotlinx.coroutines.CancellationException

/** Display eligibility, shared by composition and click-time admission. */
fun showRecentRecording(hasCurrent: Boolean, hasRecent: Boolean, recording: Boolean,
                        recorderBusy: Boolean, loading: Boolean): Boolean =
    !hasCurrent && hasRecent && !recording && !recorderBusy && !loading

/** Native preparation return/path existence cannot substitute for actual preview readiness. */
suspend fun reopenRecentRecording(load: suspend () -> PreviewLoad.Result?,
                                 onUnavailable: suspend () -> Unit): String {
    val preview = try { load() } catch (c: CancellationException) { throw c }
        catch (_: Exception) { null }
    if (preview?.ready == true) {
        return "Reopened recording." + (preview.message?.let { " $it" } ?: "")
    }
    try { onUnavailable() } catch (c: CancellationException) { throw c }
        catch (_: Exception) { return "Recording unavailable. Playback cleanup could not be confirmed; try Open again." }
    return "Recording unavailable. It may have moved, access may be denied, or playback may be unsupported. Try Reopen again or use Open to locate it."
}

/** Unknown values are omitted rather than inferred from the Editor's metadata defaults. */
fun recentRecordingMetadata(record: RecentRecording): String = buildList {
    record.durationMs?.let { ms -> add("%d:%02d".format(ms / 60000, (ms / 1000) % 60)) }
    record.format?.let(::add)
    record.channels?.let { add(when (it) { 1 -> "Mono"; 2 -> "Stereo"; else -> "$it channels" }) }
}.joinToString(" · ").ifEmpty { "Metadata unknown" }
