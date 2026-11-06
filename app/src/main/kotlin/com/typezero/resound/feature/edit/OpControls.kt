/*
 * file:    OpControls.kt
 * author:  Typezer∅
 * version: 0.3.0
 * desc:    Edit operations and their parameter-collection dialogs. Each op
 *          either runs immediately, opens a dialog here to gather a parameter,
 *          or asks for a second file. The dialogs emit a typed OpParams that
 *          EditScreen turns into an Effects call.
 */
package com.typezero.resound.feature.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

enum class EditOp(
    val label: String,
    val needsParams: Boolean,
    val needsSecondFile: Boolean,
) {
    TRIM("Trim", false, false),
    MIX("Mix", false, true),
    CONCAT("Concat", false, true),
    FADE("Fade", true, false),
    VOLUME("Volume", true, false),
    SPEED("Speed", true, false),
    PITCH("Pitch", true, false),
    EQ("EQ", true, false),
    VOCAL("Vocal Remove", false, false),
    CONVERT("Convert", true, false),
    COMPRESS("Compress", true, false);

    companion object {
        val ALL: List<EditOp> = entries.toList()
    }
}

sealed interface OpParams {
    data class Volume(val factor: Double) : OpParams
    data class Speed(val factor: Double) : OpParams
    data class Pitch(val semitones: Double) : OpParams
    data class Fade(val fadeMs: Long) : OpParams
    data class Eq(val freq: Int, val gainDb: Double) : OpParams
    data class Convert(val ext: String) : OpParams
    data class Compress(val bitrateKbps: Int) : OpParams
}

@Composable
fun OpDialog(op: EditOp, onDismiss: () -> Unit,
    convertNamingPreview: @Composable (String) -> Unit = {}, onConfirm: (OpParams) -> Unit) {
    when (op) {
        EditOp.VOLUME -> SliderDialog(op, "Volume", 0f, 3f, 1f, { "${(it * 100).roundToInt()}%" }, onDismiss) {
            onConfirm(OpParams.Volume(it.toDouble()))
        }
        EditOp.SPEED -> SliderDialog(op, "Speed", 0.5f, 2f, 1f, { "%.2fx".format(it) }, onDismiss) {
            onConfirm(OpParams.Speed(it.toDouble()))
        }
        EditOp.PITCH -> SliderDialog(op, "Pitch (semitones)", -12f, 12f, 0f, { "${it.roundToInt()} st" }, onDismiss) {
            onConfirm(OpParams.Pitch(it.roundToInt().toDouble()))
        }
        EditOp.FADE -> SliderDialog(op, "Fade in/out (seconds)", 0.5f, 5f, 2f, { "%.1fs".format(it) }, onDismiss) {
            onConfirm(OpParams.Fade((it * 1000).toLong()))
        }
        EditOp.EQ -> EqDialog(onDismiss, onConfirm)
        EditOp.CONVERT -> ChoiceDialog("Convert to", listOf("mp3", "m4a", "wav", "flac", "aac", "ogg"), "mp3", onDismiss, preview = convertNamingPreview) {
            onConfirm(OpParams.Convert(it))
        }
        EditOp.COMPRESS -> ChoiceDialog("Bitrate (kbps)", listOf("64", "96", "128", "192", "256"), "128", onDismiss) {
            onConfirm(OpParams.Compress(it.toInt()))
        }
        else -> onDismiss() // no-param ops never open a dialog
    }
}

@Composable
private fun SliderDialog(
    op: EditOp,
    title: String,
    min: Float,
    max: Float,
    default: Float,
    format: (Float) -> String,
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit,
) {
    var v by remember(op) { mutableFloatStateOf(default) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(format(v))
                Slider(value = v, onValueChange = { v = it }, valueRange = min..max)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(v) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EqDialog(onDismiss: () -> Unit, onConfirm: (OpParams) -> Unit) {
    // freqT 0..1 maps logarithmically to 60..12000 Hz.
    var freqT by remember { mutableFloatStateOf(0.5f) }
    var gain by remember { mutableFloatStateOf(0f) }
    val freq = (60.0 * Math.pow(200.0, freqT.toDouble())).roundToInt()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Equalizer band") },
        text = {
            Column {
                Text("Frequency: $freq Hz")
                Slider(value = freqT, onValueChange = { freqT = it })
                Text("Gain: ${gain.roundToInt()} dB")
                Slider(value = gain, onValueChange = { gain = it }, valueRange = -12f..12f)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(OpParams.Eq(freq, gain.toDouble())) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ChoiceDialog(
    title: String,
    options: List<String>,
    default: String,
    onDismiss: () -> Unit,
    preview: @Composable (String) -> Unit = {},
    onConfirm: (String) -> Unit,
) {
    var sel by remember { mutableStateOf(default) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { opt ->
                            if (opt == sel) {
                                Button(onClick = { sel = opt }) { Text(opt) }
                            } else {
                                OutlinedButton(onClick = { sel = opt }) { Text(opt) }
                            }
                        }
                    }
                }
                preview(sel)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(sel) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
