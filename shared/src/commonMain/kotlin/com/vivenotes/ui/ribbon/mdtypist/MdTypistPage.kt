package com.vivenotes.ui.ribbon.mdtypist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.vivenotes.ai.LocalAiProtocol
import com.vivenotes.localization.Strings
import com.vivenotes.workspace.WorkspaceState

/** Semantics identifiers for the MD-Typist page and its MODELLO API dialog. */
internal object MdTypistPageTags {
    const val Page = "md-typist-page"
    const val HandwritingPane = "md-typist-handwriting-pane"
    const val HandwritingCanvas = "md-typist-handwriting-canvas"
    const val EmptyHandwriting = "md-typist-empty-handwriting"
    const val SourcePane = "md-typist-source-pane"
    const val SourceEditor = "md-typist-source-editor"
    const val Status = "md-typist-status"
    const val ApiDialog = "md-typist-api-dialog"
    const val ApiApply = "md-typist-api-apply"
    const val ApiCancel = "md-typist-api-cancel"
    const val AiEndpoint = "md-typist-ai-endpoint"
    const val AiModel = "md-typist-ai-model"
    fun protocol(protocol: LocalAiProtocol): String = "md-typist-protocol-${protocol.name.lowercase()}"
}

/**
 * A full workspace page: the real page canvas on the left — text, equations, pictures and ink, not
 * just handwriting — and Markdown or Typst source on the right. The source half is [draft]'s
 * session-only text, filled with source by Convert and written to disk by Export.
 */
@Composable
internal fun MdTypistPage(
    state: WorkspaceState,
    draft: MdTypistDraft,
    pagePreview: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize().testTag(MdTypistPageTags.Page)) {
        val wide = maxWidth >= 900.dp
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                PagePreviewPane(state.selectedPage?.title.orEmpty(), pagePreview, Modifier.weight(1f))
                Box(Modifier.fillMaxHeight().width(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                SourcePane(draft, Modifier.weight(1f))
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                PagePreviewPane(state.selectedPage?.title.orEmpty(), pagePreview, Modifier.weight(1f))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SourcePane(draft, Modifier.weight(1f))
            }
        }
    }
}

/** Hosts the platform's complete page canvas, supplied by the shell so images and formulas draw. */
@Composable
private fun PagePreviewPane(
    pageTitle: String,
    pagePreview: @Composable (Modifier) -> Unit,
    modifier: Modifier,
) {
    Column(modifier.testTag(MdTypistPageTags.HandwritingPane)) {
        PaneHeading(Strings.md_typist_page_preview, pageTitle)
        Box(Modifier.fillMaxSize().testTag(MdTypistPageTags.HandwritingCanvas)) {
            pagePreview(Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun SourcePane(draft: MdTypistDraft, modifier: Modifier) {
    Column(modifier.background(MaterialTheme.colorScheme.surface).testTag(MdTypistPageTags.SourcePane)) {
        PaneHeading(Strings.md_typist_code, draft.format.label)
        draft.status?.let { message ->
            Text(message, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp).testTag(MdTypistPageTags.Status))
        }
        OutlinedTextField(
            value = draft.source,
            onValueChange = { draft.changeSource(it) },
            label = { Text(Strings.md_typist_editor_label) },
            placeholder = { Text(Strings.md_typist_source_hint) },
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            minLines = 12,
            modifier = Modifier.fillMaxSize().padding(12.dp)
                .testTag(MdTypistPageTags.SourceEditor),
        )
    }
}

@Composable
private fun PaneHeading(title: String, detail: String) {
    Row(
        Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface)
        if (detail.isNotBlank()) {
            Text(detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 10.dp))
        }
    }
}
