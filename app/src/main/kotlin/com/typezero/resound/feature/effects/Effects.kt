/*
 * file:    Effects.kt
 * author:  Typezer∅
 * version: 0.1.0
 * desc:    Pure builders that turn an edit intent into an FFmpeg argument list.
 *          No Android/FFmpeg deps here on purpose — easy to unit test, and the
 *          feature screens just pick a builder and hand the result to
 *          FFmpegRunner.run(). This is where the "rich feature list" lives.
 *
 *          Times are milliseconds. Paths are already-resolved absolute paths
 *          (resolve SAF Uris to a working file/cache path before calling).
 */
package com.typezero.resound.feature.effects

private fun sec(ms: Long): String = (ms / 1000.0).toString()

object Effects {

    /** Lossless trim via stream copy. Fast, but cuts land on keyframes. */
    fun trimCopy(inPath: String, outPath: String, startMs: Long, endMs: Long): List<String> =
        listOf("-y", "-ss", sec(startMs), "-to", sec(endMs), "-i", inPath, "-c", "copy", outPath)

    /** Sample-accurate trim (re-encode). Use when the user needs precise cuts. */
    fun trimEncode(inPath: String, outPath: String, startMs: Long, endMs: Long): List<String> =
        listOf("-y", "-i", inPath, "-ss", sec(startMs), "-to", sec(endMs), outPath)

    /** Concatenate same-codec files via the concat filter (re-encodes audio). */
    fun concat(inPaths: List<String>, outPath: String): List<String> {
        val args = mutableListOf("-y")
        inPaths.forEach { args += listOf("-i", it) }
        val n = inPaths.size
        val inputs = (0 until n).joinToString("") { "[$it:a]" }
        args += listOf("-filter_complex", "${inputs}concat=n=$n:v=0:a=1[out]", "-map", "[out]", outPath)
        return args
    }

    /** Mix N tracks down to one. duration=longest keeps the tail of every track. */
    fun mix(inPaths: List<String>, outPath: String): List<String> {
        val args = mutableListOf("-y")
        inPaths.forEach { args += listOf("-i", it) }
        args += listOf("-filter_complex", "amix=inputs=${inPaths.size}:duration=longest", outPath)
        return args
    }

    fun fade(inPath: String, outPath: String, totalMs: Long, fadeMs: Long): List<String> {
        val outStart = sec((totalMs - fadeMs).coerceAtLeast(0))
        val d = sec(fadeMs)
        return listOf("-y", "-i", inPath, "-af", "afade=t=in:d=$d,afade=t=out:st=$outStart:d=$d", outPath)
    }

    /** factor 1.0 = unchanged, 2.0 = +6 dB-ish louder, 0.5 = quieter. */
    fun volume(inPath: String, outPath: String, factor: Double): List<String> =
        listOf("-y", "-i", inPath, "-af", "volume=$factor", outPath)

    /** Speed without pitch change. atempo is valid 0.5..2.0; chain for extremes. */
    fun speed(inPath: String, outPath: String, factor: Double): List<String> {
        val chain = buildAtempoChain(factor)
        return listOf("-y", "-i", inPath, "-af", chain, outPath)
    }

    /**
     * Pitch shift in semitones, tempo preserved. Cheap FFmpeg-only approach:
     * resample to shift pitch, then atempo back to original length. Bundle
     * Rubber Band later if quality matters.
     */
    fun pitchSemitones(inPath: String, outPath: String, semitones: Double, sampleRate: Int): List<String> {
        val ratio = Math.pow(2.0, semitones / 12.0)
        val newRate = (sampleRate * ratio).toInt()
        val chain = "asetrate=$newRate,aresample=$sampleRate,${buildAtempoChain(1.0 / ratio)}"
        return listOf("-y", "-i", inPath, "-af", chain, outPath)
    }

    /** Cheap center-channel vocal removal. Fast, free, mediocre quality. */
    fun vocalRemoveCenter(inPath: String, outPath: String): List<String> =
        listOf("-y", "-i", inPath, "-af", "pan=stereo|c0=c0-c1|c1=c1-c0", outPath)

    /** One parametric EQ band. Stack the filter string for a full equalizer. */
    fun equalizerBand(inPath: String, outPath: String, freq: Int, gainDb: Double, q: Double = 1.0): List<String> =
        listOf("-y", "-i", inPath, "-af", "equalizer=f=$freq:width_type=q:width=$q:g=$gainDb", outPath)

    /** Re-encode to a smaller file. */
    fun compress(inPath: String, outPath: String, bitrateKbps: Int, sampleRate: Int, channels: Int): List<String> =
        listOf("-y", "-i", inPath, "-b:a", "${bitrateKbps}k", "-ar", "$sampleRate", "-ac", "$channels", outPath)

    /** Strip video, keep audio. Output extension decides the codec. */
    fun videoToAudio(inPath: String, outPath: String): List<String> =
        listOf("-y", "-i", inPath, "-vn", outPath)

    /** Container/codec conversion driven purely by the output extension. */
    fun convert(inPath: String, outPath: String): List<String> =
        listOf("-y", "-i", inPath, outPath)

    /** One positioned clip in a timeline mixdown. */
    data class TimelineInput(
        val path: String,
        val startMs: Long,
        val inMs: Long,
        val outMs: Long,
        val volume: Double,
    )

    /**
     * Mix positioned clips into a single track. Each input is trimmed to its
     * source in/out (atrim), reset to zero PTS, delayed to its timeline start
     * (adelay), optionally volume-scaled, then amix'd together. normalize=0
     * keeps amix from auto-attenuating by input count.
     */
    fun mixTimeline(inputs: List<TimelineInput>, outPath: String): List<String> {
        val args = mutableListOf("-y")
        inputs.forEach { args += listOf("-i", it.path) }
        val sb = StringBuilder()
        val labels = mutableListOf<String>()
        inputs.forEachIndexed { i, c ->
            val inS = c.inMs / 1000.0
            val outS = c.outMs / 1000.0
            sb.append("[$i:a]atrim=start=$inS:end=$outS,asetpts=PTS-STARTPTS,adelay=delays=${c.startMs}:all=1")
            if (c.volume != 1.0) sb.append(",volume=${c.volume}")
            sb.append("[c$i];")
            labels.add("[c$i]")
        }
        sb.append(labels.joinToString(""))
            .append("amix=inputs=${inputs.size}:normalize=0[out]")
        args += listOf("-filter_complex", sb.toString(), "-map", "[out]", outPath)
        return args
    }

    /** atempo only accepts 0.5..2.0, so chain factors to reach beyond that. */
    private fun buildAtempoChain(factor: Double): String {
        if (factor in 0.5..2.0) return "atempo=$factor"
        var remaining = factor
        val parts = mutableListOf<String>()
        while (remaining > 2.0) { parts += "atempo=2.0"; remaining /= 2.0 }
        while (remaining < 0.5) { parts += "atempo=0.5"; remaining /= 0.5 }
        parts += "atempo=$remaining"
        return parts.joinToString(",")
    }
}
