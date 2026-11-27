package com.vivenotes.ui.ribbon.mdtypist

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vivenotes.localization.Strings
import com.vivenotes.ui.icons.GraphSymbols
import com.vivenotes.ui.icons.ShellSymbols
import com.vivenotes.ui.icons.ViewSymbols
import com.vivenotes.ui.ribbon.RibbonBar
import com.vivenotes.ui.ribbon.RibbonCommand
import com.vivenotes.ui.ribbon.RibbonDivider
import com.vivenotes.workspace.WorkspaceState
import kotlinx.coroutines.launch

/** Controls for the MD-Typist destination. */
internal object MdTypistRibbonTags {
    fun format(format: MdTypistFormat): String = "md-typist-format-${format.name.lowercase()}"
    const val Convert = "md-typist-convert"
    const val Export = "md-typist-export"
    const val Clear = "md-typist-clear"
    const val ApiModel = "md-typist-api-model"
}

/** The MD-Typist ribbon: pick the source dialect, convert the note, export or clear it. */
@Composable
internal fun MdTypistRibbon(
    draft: MdTypistDraft,
    state: WorkspaceState,
    converter: MdTypistConverter?,
    exporter: MdTypistExporter? = null,
) {
    val scope = rememberCoroutineScope()
    RibbonBar(spacing = 2.dp) {
        MdTypistFormat.entries.forEach { format ->
            RibbonCommand(
                label = format.label,
                onClick = { draft.use(format) },
                active = draft.format == format,
                icon = {
                    Icon(
                        if (format == MdTypistFormat.Markdown) ViewSymbols.Article else GraphSymbols.Function,
                        contentDescription = null,
                    )
                },
                modifier = Modifier.testTag(MdTypistRibbonTags.format(format))
                    .semantics { selected = draft.format == format },
            )
        }
        RibbonDivider()
        RibbonCommand(
            label = if (draft.busy) Strings.md_typist_converting else Strings.md_typist_convert,
            onClick = { scope.launch { draft.convert(state, converter) } },
            enabled = !draft.busy,
            icon = { Icon(GraphSymbols.InstantMix, contentDescription = null) },
            modifier = Modifier.testTag(MdTypistRibbonTags.Convert),
        )
        RibbonCommand(
            label = Strings.md_typist_export,
            onClick = { scope.launch { draft.export(exporter) } },
            enabled = !draft.busy,
            icon = { Icon(ShellSymbols.Download, contentDescription = null) },
            modifier = Modifier.testTag(MdTypistRibbonTags.Export),
        )
        RibbonDivider()
        RibbonCommand(Strings.clear, onClick = { draft.clearSource() },
            enabled = !draft.busy,
            modifier = Modifier.testTag(MdTypistRibbonTags.Clear))
        RibbonDivider()
        RibbonCommand(
            label = Strings.md_typist_api_model,
            onClick = { draft.openApiDialog() },
            icon = { Icon(GraphSymbols.Tune, contentDescription = null) },
            modifier = Modifier.testTag(MdTypistRibbonTags.ApiModel),
        )
    }
}
