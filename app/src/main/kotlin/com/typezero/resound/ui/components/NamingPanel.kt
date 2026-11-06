package com.typezero.resound.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.typezero.resound.core.io.FilenameOption
import com.typezero.resound.core.io.FilenameOptions
import com.typezero.resound.core.io.NamingRequest
import com.typezero.resound.core.io.PublicationNaming

/**
 * Contextual, opt-in controls: opening this panel is never required to record/export. The pattern
 * is the existing [PublicationNaming] syntax; checkboxes only add or remove its tokens.
 */
@Composable
fun NamingPanel(
    title: String,
    pattern: String,
    enabled: Boolean,
    ext: String,
    recording: Boolean = false,
    trimRange: Pair<Long, Long>? = null,
    frozen: NamingRequest? = null,
    trimOption: Boolean = false,
    onChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var optionsExpanded by remember { mutableStateOf(false) }
    val options = FilenameOption.entries.filter { it != FilenameOption.TRIM || (trimOption && !recording) }
    ResoundCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { expanded = !expanded }) {
                Text("$title · ${if (pattern.isEmpty()) "Automatic" else "Custom"} ${if (expanded) "−" else "+"}")
            }
            if (expanded) {
                OutlinedTextField(value = pattern, onValueChange = onChange, enabled = enabled,
                    label = { Text("Filename (leave empty for automatic)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { optionsExpanded = !optionsExpanded }) {
                    Text("Filename options ${if (optionsExpanded) "−" else "+"}")
                }
                if (optionsExpanded) {
                    options.forEach { option ->
                        val checked = FilenameOptions.isSelected(pattern, option)
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .toggleable(value = checked, enabled = enabled, role = Role.Checkbox,
                                    onValueChange = { onChange(FilenameOptions.toggle(pattern, option, it)) })) {
                            Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
                            Spacer(Modifier.width(8.dp))
                            Text(option.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                NamingPreview(pattern, ext, trimRange, frozen, trimAvailable = trimOption && !recording)
                if (pattern.contains("\$FORMAT")) {
                    Text("The format is added automatically as the file extension (.$ext); a format at the end of the name is not repeated.",
                        style = MaterialTheme.typography.bodyMedium)
                }
                if (recording && frozen == null && pattern.isNotEmpty()) {
                    Text("Applies to the next recording.", style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = { onChange("") }, enabled = enabled && pattern.isNotEmpty()) { Text("Use automatic naming") }
            }
        }
    }
}

/** Preview of the shared resolver; example values are labelled and never used for publication. */
@Composable
fun NamingPreview(
    pattern: String,
    ext: String,
    trimRange: Pair<Long, Long>? = null,
    frozen: NamingRequest? = null,
    trimAvailable: Boolean = false, // only Editor Trim exports can fill $TRIM
) {
    val example = frozen ?: remember(pattern, ext) { NamingRequest(pattern) }
    val usesDuration = FilenameOptions.isSelected(pattern, FilenameOption.DURATION)
    val usesTrim = FilenameOptions.isSelected(pattern, FilenameOption.TRIM)
    val sampleTrim = trimAvailable && trimRange == null && usesTrim
    val result = PublicationNaming.resolve(example.copy(pattern = pattern), ext,
        durationMs = if (usesDuration) FilenameOptions.SAMPLE_DURATION_MS else null,
        // Where $TRIM can never resolve, keep the real fallback instead of an example name.
        trimRange = if (!trimAvailable) null else trimRange ?: FilenameOptions.SAMPLE_TRIM_RANGE.takeIf { sampleTrim })
    val samples = buildList {
        if (frozen == null && (FilenameOptions.isSelected(pattern, FilenameOption.DATE) ||
                FilenameOptions.isSelected(pattern, FilenameOption.TIME))) add("date/time")
        if (usesDuration) add("duration")
        if (sampleTrim) add("trim range")
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(when {
            pattern.isEmpty() -> "Preview: automatic name with date, time and a short code (.$ext)"
            result.name != null -> "Preview: ${result.name}"
            else -> "Automatic name will be used: ${result.notice?.removePrefix("Automatic name used: ")}"
        }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        if (result.name != null && samples.isNotEmpty()) {
            Text("Example ${samples.joinToString(", ")}; actual values are filled in when saved.",
                style = MaterialTheme.typography.bodyMedium)
        }
        if (result.name != null && trimAvailable && usesTrim) {
            Text("Trim range is added only to Trim exports; other exports use automatic naming.",
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}
