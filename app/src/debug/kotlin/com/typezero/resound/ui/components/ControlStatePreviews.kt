package com.typezero.resound.ui.components

import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.typezero.resound.feature.edit.EditorPreviewEvents
import com.typezero.resound.ui.theme.Ink
import com.typezero.resound.ui.theme.ResoundTheme
import com.typezero.resound.ui.theme.TextLo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Debug-only specimens of the shared controls in every renderable state. Pressed and focused states are
 * driven through the controls' real interaction-state handling by an interaction source that reports one
 * held interaction (Layoutlib cannot hold a press or focus while capturing).
 */
private class HeldInteraction(held: Interaction) : MutableInteractionSource {
    override val interactions: Flow<Interaction> = MutableStateFlow(held)
    override suspend fun emit(interaction: Interaction) = Unit
    override fun tryEmit(interaction: Interaction): Boolean = true
}

@Composable
private fun Specimen(label: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = TextLo, modifier = Modifier.padding(end = 4.dp))
        content()
    }
}

@Preview(widthDp = 412, heightDp = 850) @Composable
fun ControlStatesPreview() = ResoundTheme {
    val pressed = remember { HeldInteraction(PressInteraction.Press(Offset.Zero)) }
    val focused = remember { HeldInteraction(FocusInteraction.Focus()) }
    val record: (String) -> () -> Unit = { name -> { EditorPreviewEvents.record(name) } }
    Surface(color = Ink, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Primary — Export Mix reference", style = MaterialTheme.typography.titleSmall)
            Specimen("enabled") { ResoundAction("Export Mix", Icons.Outlined.FileUpload, record("export-enabled")) }
            Specimen("pressed") { ResoundAction("Export Mix", Icons.Outlined.FileUpload, {}, interactionSource = pressed) }
            Specimen("focused") { ResoundAction("Export Mix", Icons.Outlined.FileUpload, {}, interactionSource = focused) }
            Specimen("disabled") { ResoundAction("Export Mix", Icons.Outlined.FileUpload, {}, enabled = false) }
            Text("Secondary", style = MaterialTheme.typography.titleSmall)
            Specimen("enabled") {
                ResoundAction("Add Track", Icons.AutoMirrored.Outlined.PlaylistAdd, record("add-track"),
                    prominence = ActionProminence.Secondary)
            }
            Specimen("disabled") {
                ResoundAction("Add Track", Icons.AutoMirrored.Outlined.PlaylistAdd, {}, enabled = false,
                    prominence = ActionProminence.Secondary)
            }
            Text("Icon actions and choices", style = MaterialTheme.typography.titleSmall)
            Specimen("icons") {
                ResoundIconAction(Icons.Outlined.Refresh, "Refresh", record("refresh"))
                ResoundIconAction(Icons.Outlined.Share, "Share", record("share"))
                ResoundIconAction(Icons.Outlined.Refresh, "Refresh disabled", {}, enabled = false)
            }
            Specimen("choice") {
                ResoundChoiceChip("WAV", selected = true, onClick = record("choice-wav"))
                ResoundChoiceChip("MP3", selected = false, onClick = record("choice-mp3"))
            }
        }
    }
}
