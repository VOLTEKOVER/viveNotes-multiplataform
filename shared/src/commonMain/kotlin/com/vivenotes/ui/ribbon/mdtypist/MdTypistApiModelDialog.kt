package com.vivenotes.ui.ribbon.mdtypist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vivenotes.ai.LocalAiProtocol
import com.vivenotes.ai.LocalAiSettings
import com.vivenotes.localization.Strings
import com.vivenotes.ui.components.DesktopDialogFrame
import com.vivenotes.ui.icons.GraphSymbols

/** The ribbon's MODELLO API settings. Edits are local until Apply, so Cancel leaves Convert alone. */
@Composable
internal fun MdTypistApiModelDialog(
    settings: LocalAiSettings,
    onDismiss: () -> Unit,
    onApply: (LocalAiSettings) -> Unit,
) {
    var edited by remember(settings) { mutableStateOf(settings) }
    DesktopDialogFrame(
        title = Strings.md_typist_api_model,
        icon = GraphSymbols.Tune,
        iconTint = MaterialTheme.colorScheme.primary,
        onDismiss = onDismiss,
        modifier = Modifier.testTag(MdTypistPageTags.ApiDialog),
        backdropTag = "md-typist-api-backdrop",
        maxWidth = 520.dp,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = Strings.md_typist_api_hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = edited.endpoint,
                    onValueChange = { edited = edited.copy(endpoint = it) },
                    label = { Text(Strings.md_typist_ai_endpoint) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(MdTypistPageTags.AiEndpoint),
                )
                OutlinedTextField(
                    value = edited.model,
                    onValueChange = { edited = edited.copy(model = it) },
                    label = { Text(Strings.md_typist_ai_model) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(MdTypistPageTags.AiModel),
                )
                Text(Strings.md_typist_protocol, style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LocalAiProtocol.entries.forEach { protocol ->
                        val active = edited.protocol == protocol
                        TextButton(
                            onClick = { edited = edited.copy(protocol = protocol) },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                            modifier = Modifier.testTag(MdTypistPageTags.protocol(protocol)),
                        ) {
                            Text(protocol.label, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        },
        actions = {
            OutlinedButton(
                onClick = onDismiss,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.testTag(MdTypistPageTags.ApiCancel),
            ) { Text(Strings.cancel) }
            Button(
                onClick = { onApply(edited); onDismiss() },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.testTag(MdTypistPageTags.ApiApply),
            ) { Text(Strings.apply) }
        },
    )
}
