package com.vivenotes.ui.ribbon.mdtypist

import com.vivenotes.ai.LocalAiProtocol
import com.vivenotes.ai.LocalAiSettings
import com.vivenotes.localization.Strings
import com.vivenotes.workspace.WorkspaceState
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

/** Session state around Convert: which draft is replaced, and what the user is told. */
class MdTypistDraftTest {
    private class FakeConverter(var result: MdTypistConversionResult) : MdTypistConverter {
        var calls = 0
        var seenFormat: MdTypistFormat? = null
        var seenSettings: LocalAiSettings? = null

        override suspend fun convert(
            state: WorkspaceState,
            format: MdTypistFormat,
            settings: LocalAiSettings,
        ): MdTypistConversionResult {
            calls++
            seenFormat = format
            seenSettings = settings
            return result
        }
    }

    @Test
    fun aSuccessfulConversionReplacesOnlyTheCurrentDraft() = runBlocking {
        val draft = MdTypistDraft()
        val converter = FakeConverter(MdTypistConversionResult.Success("# from ink"))
        draft.changeSource("written by hand")

        draft.convert(WorkspaceState.demo(), converter)

        assertEquals("# from ink", draft.markdown)
        assertEquals("", draft.typst)
        assertEquals(Strings.md_typist_converted, draft.status)
        assertFalse(draft.busy)
        assertEquals(1, converter.calls)
        assertEquals(MdTypistFormat.Markdown, converter.seenFormat)
    }

    @Test
    fun typstConversionLeavesTheMarkdownDraftAlone() = runBlocking {
        val draft = MdTypistDraft()
        draft.changeSource("# keep me")
        draft.use(MdTypistFormat.Typst)
        val converter = FakeConverter(MdTypistConversionResult.Success("#set text(size: 12pt)"))

        draft.convert(WorkspaceState.demo(), converter)

        assertEquals("# keep me", draft.markdown)
        assertEquals("#set text(size: 12pt)", draft.typst)
        assertEquals(MdTypistFormat.Typst, converter.seenFormat)
    }

    @Test
    fun aFailureReportsTheMessageAndKeepsTheDraft() = runBlocking {
        val draft = MdTypistDraft()
        draft.changeSource("# keep me too")
        val converter = FakeConverter(MdTypistConversionResult.Failure(Strings.md_typist_no_text))

        draft.convert(WorkspaceState.demo(), converter)

        assertEquals("# keep me too", draft.markdown)
        assertEquals(Strings.md_typist_no_text, draft.status)
        assertFalse(draft.busy)
    }

    @Test
    fun theConnectionIsPassedWithTheRequestAndPersistedOnEdit() = runBlocking {
        var saved: LocalAiSettings? = null
        val settings = LocalAiSettings(endpoint = "http://192.168.1.20:11434",
            model = "hermes3", protocol = LocalAiProtocol.OpenAiCompatible)
        val draft = MdTypistDraft(onAiSettingsChange = { saved = it })
        val converter = FakeConverter(MdTypistConversionResult.Success("source"))

        draft.updateAiSettings(settings)
        draft.convert(WorkspaceState.demo(), converter)

        assertSame(settings, saved)
        assertSame(settings, converter.seenSettings)
        assertEquals(settings, draft.aiSettings)
    }

    @Test
    fun withoutAConverterConvertOnlyExplainsItIsUnavailable() = runBlocking {
        val draft = MdTypistDraft()
        draft.changeSource("# keep me")

        draft.convert(WorkspaceState.demo(), null)

        assertEquals("# keep me", draft.markdown)
        assertEquals(Strings.md_typist_ai_unavailable, draft.status)
    }

    @Test
    fun conversionFiguresAreKeptForExportAndClearedWithTheSource() = runBlocking {
        val figure = MdTypistFigure("figure-1.png", byteArrayOf(1, 2, 3))
        val draft = MdTypistDraft()
        val converter = FakeConverter(MdTypistConversionResult.Success("# note", listOf(figure)))

        draft.convert(WorkspaceState.demo(), converter)
        assertEquals(listOf(figure), draft.figures)

        draft.clearSource()
        assertEquals(emptyList(), draft.figures)
    }

    @Test
    fun exportSendsTheCurrentDraftAndItsFigures() = runBlocking {
        var sent: MdTypistExportRequest? = null
        val exporter = object : MdTypistExporter {
            override suspend fun export(request: MdTypistExportRequest): Result<String> {
                sent = request
                return Result.success("/tmp/note.typ")
            }
        }
        val figure = MdTypistFigure("figure-1.png", byteArrayOf(4, 5))
        val draft = MdTypistDraft()
        draft.use(MdTypistFormat.Typst)
        draft.changeSource("#set text(size: 12pt)")
        draft.convert(WorkspaceState.demo(), FakeConverter(
            MdTypistConversionResult.Success("#set text(size: 12pt)", listOf(figure))))

        draft.export(exporter)

        assertEquals(MdTypistFormat.Typst, sent?.format)
        assertEquals("#set text(size: 12pt)", sent?.source)
        assertEquals(listOf(figure), sent?.figures)
        assertEquals(Strings.md_typist_exported, draft.status)
        assertFalse(draft.busy)
    }

    @Test
    fun exportWithoutSourceOrExporterDoesNotCallTheExporter() = runBlocking {
        var calls = 0
        val exporter = object : MdTypistExporter {
            override suspend fun export(request: MdTypistExportRequest): Result<String> {
                calls++
                return Result.success("unused")
            }
        }
        val draft = MdTypistDraft()

        draft.export(exporter)
        assertEquals(Strings.md_typist_export_empty, draft.status)
        draft.changeSource("# note")
        draft.export(null)
        assertEquals(Strings.md_typist_export_failed, draft.status)
        assertEquals(0, calls)
    }
}
