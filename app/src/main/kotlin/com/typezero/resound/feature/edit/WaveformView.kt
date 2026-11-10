/*
 * file:    WaveformView.kt
 * author:  Typezer∅
 * version: 0.6.0
 * desc:    The editor's signature widget. Draws the downsampled Waveform as a
 *          filled, mirrored shape with a vertical signal-teal gradient, a soft
 *          selection wash with grip handles, and a bright playhead. Gesture math
 *          (two-handle selection) unchanged from 0.2.0; rememberUpdatedState
 *          keeps the drag closures reading live selection values.
 */
package com.typezero.resound.feature.edit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.typezero.resound.core.audio.Waveform
import com.typezero.resound.ui.theme.Accent
import com.typezero.resound.ui.theme.Signal
import com.typezero.resound.ui.theme.SignalDeep
import com.typezero.resound.ui.theme.TextHi
import kotlin.math.abs
import kotlin.math.roundToLong

/** Maps a horizontal position to a time, clamped to the waveform; 0 before layout. */
internal fun waveformXToMs(x: Float, width: Float, durationMs: Long): Long =
    if (width <= 0f || durationMs <= 0L) 0L
    else ((x / width) * durationMs).roundToLong().coerceIn(0, durationMs)

internal fun waveformMsToX(ms: Long, width: Float, durationMs: Long): Float =
    if (durationMs <= 0L) 0f else (ms.toFloat() / durationMs) * width

@androidx.compose.runtime.Composable
fun WaveformView(
    waveform: Waveform,
    selStartMs: Long,
    selEndMs: Long,
    playheadMs: Long,
    onSelectionChange: (startMs: Long, endMs: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var activeHandle by remember { mutableFloatStateOf(-1f) }

    val curStart = rememberUpdatedState(selStartMs)
    val curEnd = rememberUpdatedState(selEndMs)
    val curOnChange = rememberUpdatedState(onSelectionChange)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(168.dp)
            .pointerInput(waveform) {
                // Read the current width on every event: the activity handles rotation itself, so this
                // gesture block outlives size changes and a captured width maps drags to wrong times.
                fun xToMs(x: Float) = waveformXToMs(x, size.width.toFloat(), waveform.durationMs)
                fun msToX(ms: Long) = waveformMsToX(ms, size.width.toFloat(), waveform.durationMs)

                detectHorizontalDragGestures(
                    onDragStart = { pos ->
                        val dStart = abs(pos.x - msToX(curStart.value))
                        val dEnd = abs(pos.x - msToX(curEnd.value))
                        activeHandle = if (dStart <= dEnd) 0f else 1f
                    },
                    onDragEnd = { activeHandle = -1f },
                ) { change, _ ->
                    val ms = xToMs(change.position.x)
                    if (activeHandle == 0f) {
                        curOnChange.value(ms.coerceAtMost(curEnd.value), curEnd.value)
                    } else {
                        curOnChange.value(curStart.value, ms.coerceAtLeast(curStart.value))
                    }
                }
            }
    ) {
        val w = size.width
        val h = size.height
        val midY = h / 2f
        val n = waveform.bucketCount
        if (n == 0 || waveform.durationMs <= 0L) return@Canvas
        val step = w / n

        // Faint centre baseline.
        drawLine(Color(0x22FFFFFF), Offset(0f, midY), Offset(w, midY), strokeWidth = 1.dp.toPx())

        // Filled mirrored waveform: top edge along maxs, back along mins.
        val path = Path().apply {
            moveTo(0f, midY)
            for (i in 0 until n) {
                val x = i * step
                lineTo(x, midY - waveform.maxs[i] * midY)
            }
            for (i in n - 1 downTo 0) {
                val x = i * step
                lineTo(x, midY - waveform.mins[i] * midY)
            }
            close()
        }
        drawPath(
            path = path,
            brush = Brush.verticalGradient(
                colors = listOf(Signal, SignalDeep),
                startY = 0f,
                endY = h,
            ),
        )

        // Selection wash + grip handles.
        val xs = (selStartMs.toFloat() / waveform.durationMs) * w
        val xe = (selEndMs.toFloat() / waveform.durationMs) * w
        drawRect(
            color = Accent.copy(alpha = 0.18f),
            topLeft = Offset(xs, 0f),
            size = Size((xe - xs).coerceAtLeast(0f), h),
        )
        listOf(xs, xe).forEach { hx ->
            drawLine(Accent, Offset(hx, 0f), Offset(hx, h), strokeWidth = 2.5.dp.toPx())
            drawCircle(Accent, radius = 7.dp.toPx(), center = Offset(hx, 10.dp.toPx()))
            drawCircle(Accent, radius = 7.dp.toPx(), center = Offset(hx, h - 10.dp.toPx()))
        }

        // Playhead.
        val xp = (playheadMs.toFloat() / waveform.durationMs) * w
        drawLine(TextHi, Offset(xp, 0f), Offset(xp, h), strokeWidth = 1.5.dp.toPx())
    }
}
