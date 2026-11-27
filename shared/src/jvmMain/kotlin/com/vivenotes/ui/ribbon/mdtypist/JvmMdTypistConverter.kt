package com.vivenotes.ui.ribbon.mdtypist

import com.vivenotes.ai.AiModelStore
import com.vivenotes.ai.HandwritingRecognizer
import com.vivenotes.ai.InkRecognitionEngine
import com.vivenotes.ai.LocalAiClient
import com.vivenotes.ai.LocalAiRequest
import com.vivenotes.ai.LocalAiSettings
import com.vivenotes.ai.OnnxInkRecognitionEngine
import com.vivenotes.ai.inkPageLayout
import com.vivenotes.data.PictureLibrary
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.ink.desktopGeometry
import com.vivenotes.localization.Strings
import com.vivenotes.typst.TypstRenderer
import com.vivenotes.workspace.WorkspaceState
import java.io.Closeable

/**
 * Notes → source on the desktop. The document supplies typed text, formulas, pictures, tables,
 * shapes and graphs; the bundled OCR reads any handwriting; the leftover ink becomes raster
 * figures. A local model server then writes the Markdown or Typst document around all of it.
 */
class JvmMdTypistConverter(
    private val models: AiModelStore,
    private val client: LocalAiClient,
    private val log: DebugLog = DebugLog(),
    private val pictures: PictureLibrary? = null,
    private val renderer: TypstRenderer? = null,
) : MdTypistConverter, Closeable {
    private var ownedEngine: Closeable? = null
    private val engine: InkRecognitionEngine by lazy {
        OnnxInkRecognitionEngine(models, lanes = 1, inferenceThreads = 1, log = log).also { ownedEngine = it }
    }
    private val recognizer: HandwritingRecognizer by lazy { HandwritingRecognizer(engine) }

    override suspend fun convert(
        state: WorkspaceState,
        format: MdTypistFormat,
        settings: LocalAiSettings,
    ): MdTypistConversionResult {
        val page = state.selectedPage
            ?: return MdTypistConversionResult.Failure(Strings.md_typist_no_page)
        val elements = describePage(page, pictures, renderer)
        var handwriting = ""
        val figures = mutableListOf<MdTypistFigure>()
        val ink = page.ink
        if (ink != null && ink.strokes.isNotEmpty()) {
            val projections = ink.desktopGeometry().projections
            val recognized = try {
                recognizer.recognize(projections, page.document.inkPageLayout())
            } catch (failure: Exception) {
                log.failure("md-typist", "handwriting recognition", failure)
                emptyList()
            }
            handwriting = recognized.sortedBy { it.top }.joinToString("\n") { it.text }.trim()
            try {
                figures += detectFigures(projections, recognized)
            } catch (failure: Exception) {
                log.failure("md-typist", "figure rasterization", failure)
            }
        }
        val input = elements.input.copy(
            figureNames = figures.map { it.name },
            handwriting = handwriting,
        )
        if (!input.hasContent) return MdTypistConversionResult.Failure(Strings.md_typist_no_text)

        val reply = client.complete(LocalAiRequest(
            settings = settings,
            system = MdTypistPrompts.system(format),
            prompt = MdTypistPrompts.prompt(input),
        ))
        return reply.fold(
            onSuccess = {
                MdTypistConversionResult.Success(
                    MdTypistPrompts.document(format, input, it),
                    elements.assets + figures,
                )
            },
            onFailure = { failure ->
                log.failure("md-typist", "local model request", failure)
                MdTypistConversionResult.Failure(
                    failure.message?.takeIf { it.isNotBlank() } ?: Strings.md_typist_ai_failed
                )
            },
        )
    }

    override fun close() {
        ownedEngine?.close()
        ownedEngine = null
    }
}
