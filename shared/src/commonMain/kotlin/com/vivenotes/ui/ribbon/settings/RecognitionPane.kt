package com.vivenotes.ui.ribbon.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.vivenotes.localization.Strings
import com.vivenotes.ui.components.PaneGroup
import com.vivenotes.ui.components.ToolPane
import com.vivenotes.workspace.ShapeRecognitionGroup
import com.vivenotes.workspace.ShapeRecognitionSettings

/** Semantics identifiers for Settings → Shape recognition. */
object RecognitionTags {
    const val Open = "settings-recognition-open"
    const val Automatic = "shape-recognition-automatic"
    const val NoneSelected = "shape-recognition-none-selected"

    fun group(group: ShapeRecognitionGroup): String = "shape-recognition-group-${group.name.lowercase()}"
}

/**
 * Settings → Shape recognition. The main switch decides whether the hold gesture looks for a shape
 * at all; the group checkboxes below decide which kinds it may produce. Both changes reach the host
 * at once and are mirrored into the workspace, so the next stroke uses them without a restart.
 */
@Composable
internal fun RecognitionPane(
    settings: ShapeRecognitionSettings,
    onSettingsChange: (ShapeRecognitionSettings) -> Unit,
    onClose: () -> Unit,
) {
    ToolPane(title = Strings.shape_recognition, onClose = onClose) {
        PaneGroup(Strings.recognition) {
            ToggleRow(
                label = Strings.automatic_shape_recognition,
                hint = Strings.automatic_shape_recognition_hint,
                checked = settings.automatic,
                role = Role.Switch,
                tag = RecognitionTags.Automatic,
                onCheckedChange = { onSettingsChange(settings.copy(automatic = it)) },
            ) { checked ->
                Switch(checked = checked, onCheckedChange = null)
            }
        }
        PaneGroup(Strings.shapes_to_recognize) {
            ShapeRecognitionGroup.entries.forEachIndexed { index, group ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ToggleRow(
                    label = group.label,
                    hint = Strings.recognition_group_partial.takeIf { group.partlyEnabledIn(settings) },
                    checked = group.enabledIn(settings),
                    role = Role.Checkbox,
                    tag = RecognitionTags.group(group),
                    onCheckedChange = { onSettingsChange(group.setEnabled(settings, it)) },
                ) { checked ->
                    Checkbox(checked = checked, onCheckedChange = null)
                }
            }
        }
        if (settings.enabledKinds.isEmpty()) {
            Text(Strings.no_shapes_selected, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 12.dp).testTag(RecognitionTags.NoneSelected))
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    role: Role,
    tag: String,
    onCheckedChange: (Boolean) -> Unit,
    hint: String? = null,
    control: @Composable (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .toggleable(value = checked, role = role, onValueChange = onCheckedChange)
            .testTag(tag)
            .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface)
            if (hint != null) {
                Text(hint, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // The row is the control; the switch or checkbox only shows its state.
        control(checked)
    }
}
