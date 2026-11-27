package com.vivenotes.ui.ribbon.mdtypist

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vivenotes.ai.LocalAiSettings
import com.vivenotes.localization.Strings
import com.vivenotes.workspace.WorkspaceState
import kotlinx.coroutines.CancellationException

/** The two source dialects offered by the MD-Typist page. */
enum class MdTypistFormat {
    Markdown,
    Typst;

    val label: String get() = when (this) {
        Markdown -> Strings.md_typist_markdown
        Typst -> Strings.md_typist_typst
    }

    val fileExtension: String get() = when (this) {
        Markdown -> "md"
        Typst -> "typ"
    }
}

/**
 * Session-only state for the MD-Typist page. The two dialects keep their own drafts so toggling the
 * ribbon's format never discards what was written in the other one. Convert overwrites only the
 * current draft; nothing here is persisted with the notebook.
 */
internal class MdTypistDraft(
    initialAiSettings: LocalAiSettings = LocalAiSettings(),
    private val onAiSettingsChange: (LocalAiSettings) -> Unit = {},
) {
    var format by mutableStateOf(MdTypistFormat.Markdown)
        private set

    var markdown by mutableStateOf("")
        private set

    var typst by mutableStateOf("")
        private set

    val source: String get() = when (format) {
        MdTypistFormat.Markdown -> markdown
        MdTypistFormat.Typst -> typst
    }

    /** Figures from the last conversion, written next to the source by Export. */
    var figures by mutableStateOf<List<MdTypistFigure>>(emptyList())
        private set

    /** Connection to the local model server, editable from the ribbon's MODELLO API dialog. */
    var aiSettings by mutableStateOf(initialAiSettings)
        private set

    /** Whether the ribbon's MODELLO API dialog is open. */
    var apiDialog by mutableStateOf(false)
        private set

    var busy by mutableStateOf(false)
        private set

    /** The last Convert or Export outcome, shown under the source editor. */
    var status by mutableStateOf<String?>(null)
        private set

    fun use(format: MdTypistFormat) {
        this.format = format
    }

    fun changeSource(value: String) {
        when (format) {
            MdTypistFormat.Markdown -> markdown = value
            MdTypistFormat.Typst -> typst = value
        }
    }

    fun clearSource() {
        changeSource("")
        figures = emptyList()
    }

    fun updateAiSettings(value: LocalAiSettings) {
        aiSettings = value
        onAiSettingsChange(value)
    }

    fun openApiDialog() {
        apiDialog = true
    }

    fun closeApiDialog() {
        apiDialog = false
    }

    suspend fun convert(state: WorkspaceState, converter: MdTypistConverter?) {
        if (busy) return
        if (converter == null) {
            status = Strings.md_typist_ai_unavailable
            return
        }
        busy = true
        status = Strings.md_typist_converting
        val result = try {
            converter.convert(state, format, aiSettings)
        } catch (cancelled: CancellationException) {
            busy = false
            throw cancelled
        } catch (failure: Exception) {
            MdTypistConversionResult.Failure(failure.message ?: Strings.md_typist_ai_failed)
        }
        when (result) {
            is MdTypistConversionResult.Success -> {
                changeSource(result.source)
                figures = result.figures
                status = Strings.md_typist_converted
            }
            is MdTypistConversionResult.Failure -> status = result.message
        }
        busy = false
    }

    suspend fun export(exporter: MdTypistExporter?) {
        if (busy) return
        if (exporter == null || source.isBlank()) {
            status = if (source.isBlank()) Strings.md_typist_export_empty
            else Strings.md_typist_export_failed
            return
        }
        busy = true
        val result = try {
            exporter.export(MdTypistExportRequest(source, format, figures))
        } catch (cancelled: CancellationException) {
            busy = false
            throw cancelled
        } catch (failure: Exception) {
            Result.failure<String>(failure)
        }
        status = result.fold(
            onSuccess = { Strings.md_typist_exported },
            onFailure = { Strings.md_typist_export_failed },
        )
        busy = false
    }
}
