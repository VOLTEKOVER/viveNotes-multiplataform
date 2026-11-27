package com.vivenotes.ui.ribbon.mdtypist

import com.vivenotes.data.PictureLibrary
import com.vivenotes.data.decodePictureImage
import com.vivenotes.model.Block
import com.vivenotes.model.BlockType
import com.vivenotes.model.Mark
import com.vivenotes.model.Outline
import com.vivenotes.pdf.PageRenderer
import com.vivenotes.pdf.PdfCanvasColors
import com.vivenotes.pdf.PdfShift
import com.vivenotes.typst.TypstDiagramSourceCodec
import com.vivenotes.typst.TypstRenderResult
import com.vivenotes.typst.TypstRenderer
import com.vivenotes.workspace.PageSummary
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import kotlin.math.roundToInt

/** The document's own content, plus the raster files an export of it needs. */
internal data class MdTypistPageElements(
    val input: MdTypistPageInput,
    val assets: List<MdTypistFigure>,
)

/**
 * Reads every outline on the page — not just ink. Pictures become PNG file references, equations
 * keep their LaTeX, and tables/shapes/graphs are described so the model has the whole page.
 */
internal suspend fun describePage(
    page: PageSummary,
    pictures: PictureLibrary?,
    renderer: TypstRenderer?,
): MdTypistPageElements {
    val blocks = mutableListOf<MdTypistTextBlock>()
    val formulas = mutableListOf<String>()
    val tables = mutableListOf<MdTypistTable>()
    val shapes = mutableListOf<String>()
    val graphs = mutableListOf<String>()
    val imageNames = mutableListOf<String>()
    val shapeNames = mutableListOf<String>()
    val graphNames = mutableListOf<String>()
    val assets = mutableListOf<MdTypistFigure>()

    page.document.outlines
        .sortedWith(compareBy({ it.y }, { it.x }))
        .forEach { outline ->
            when (outline) {
                is Outline.Text -> outline.blocks.forEach { block ->
                    val text = blockText(block)
                    if (text.isNotBlank()) {
                        blocks += MdTypistTextBlock(blockKind(block.type), text, block.checked)
                    }
                }
                is Outline.Image -> {
                    val bytes = pictures?.bytes(outline.attachmentId)
                    val png = bytes?.let { rasterizePicture(it, renderer) }
                    if (png != null) {
                        val name = "image-${imageNames.size + 1}.png"
                        imageNames += name
                        assets += MdTypistFigure(name, png)
                    }
                }
                is Outline.Equation -> if (outline.latex.isNotBlank()) formulas += outline.latex.trim()
                is Outline.Table -> {
                    val rows = outline.rows.map { row -> row.cells.map { it.plainText.trim() } }
                    if (rows.any { row -> row.any { it.isNotBlank() } }) {
                        tables += MdTypistTable(rows, outline.headerRow, outline.headerColumn)
                    }
                }
                is Outline.Shape -> {
                    shapes += "${outline.kind.name} " +
                        "(${outline.width.roundToInt()}x${outline.height.roundToInt()})"
                    // An empty shape has nothing to show; a real one is exported as pixels, never as
                    // its editing geometry.
                    if (outline.segments.isNotEmpty()) {
                        rasterShape(outline, FIGURE_COLORS)?.let { png ->
                            val name = "shape-${shapeNames.size + 1}.png"
                            shapeNames += name
                            imageNames += name
                            assets += MdTypistFigure(name, png)
                        }
                    }
                }
                is Outline.Graph -> {
                    val formula = outline.latex.ifBlank { outline.expression }
                    if (formula.isNotBlank()) {
                        graphs += "$formula; ${outline.variable} -> ${outline.dependent}"
                    }
                    rasterGraph(outline, FIGURE_COLORS)?.let { png ->
                        val name = "graph-${graphNames.size + 1}.png"
                        graphNames += name
                        imageNames += name
                        assets += MdTypistFigure(name, png)
                    }
                }
                is Outline.Ink -> Unit // Handwriting and leftover ink figures are added by the converter.
            }
        }

    return MdTypistPageElements(
        MdTypistPageInput(
            title = page.title,
            blocks = blocks,
            formulas = formulas,
            tables = tables,
            shapes = shapes,
            graphs = graphs,
            imageNames = imageNames,
        ),
        assets,
    )
}

/** Keeps a block's type while replacing an inline equation run with `$latex$`. */
private fun blockText(block: Block): String = block.runs.joinToString("") { run ->
    run.marks.filterIsInstance<Mark.Equation>().firstOrNull()
        ?.let { "${'$'}${it.latex.trim()}${'$'}" }
        ?: run.text
}

private fun blockKind(type: BlockType?): MdTypistBlockKind = when (type) {
    BlockType.Heading1 -> MdTypistBlockKind.Heading1
    BlockType.Heading2 -> MdTypistBlockKind.Heading2
    BlockType.Heading3 -> MdTypistBlockKind.Heading3
    BlockType.Bullet -> MdTypistBlockKind.Bullet
    BlockType.Numbered -> MdTypistBlockKind.Numbered
    BlockType.Todo -> MdTypistBlockKind.Todo
    BlockType.Quote -> MdTypistBlockKind.Quote
    BlockType.Code -> MdTypistBlockKind.Code
    BlockType.Paragraph, null -> MdTypistBlockKind.Paragraph
}

/** Stored SVG and editable CeTZ diagrams are rasterized; vector code never reaches the source. */
private suspend fun rasterizePicture(bytes: ByteArray, renderer: TypstRenderer?): ByteArray? {
    val source = if (renderer != null) {
        val diagram = runCatching { TypstDiagramSourceCodec.read(bytes.decodeToString()) }.getOrNull()
        if (diagram != null) {
            when (val result = renderer.render(diagram)) {
                is TypstRenderResult.Success -> result.svg.toByteArray()
                is TypstRenderResult.Failure -> return null
            }
        } else bytes
    } else bytes
    return decodePictureImage(source)?.use { image ->
        image.encodeToData(EncodedImageFormat.PNG)?.use { data -> data.bytes }
    }
}

/** A shape as pixels, using the same painter as the page and the PDF export. */
private fun rasterShape(shape: Outline.Shape, colors: PdfCanvasColors): ByteArray? {
    val padding = shape.borderWidth + 4f
    val width = (shape.width + padding * 2).roundToInt().coerceAtLeast(1)
    val height = (shape.height + padding * 2).roundToInt().coerceAtLeast(1)
    return Surface.makeRasterN32Premul(width, height).use { surface ->
        surface.canvas.clear(0x00000000)
        surface.canvas.translate(padding - shape.x, padding - shape.y)
        PageRenderer().use { renderer ->
            renderer.drawShape(surface.canvas, shape, colors.textArgb, PdfShift.NONE)
        }
        encodePng(surface)
    }
}

/** A graph as pixels, including its axes, labels and points of interest. */
private fun rasterGraph(graph: Outline.Graph, colors: PdfCanvasColors): ByteArray? {
    val width = graph.width.roundToInt().coerceAtLeast(1)
    val height = graph.height.roundToInt().coerceAtLeast(1)
    return Surface.makeRasterN32Premul(width, height).use { surface ->
        surface.canvas.translate(-graph.x, -graph.y)
        PageRenderer().use { renderer -> renderer.drawGraph(surface.canvas, graph, colors) }
        encodePng(surface)
    }
}

private fun encodePng(surface: Surface): ByteArray? =
    surface.makeImageSnapshot().use { image ->
        image.encodeToData(EncodedImageFormat.PNG)?.use { it.bytes }
    }

/** Fixed light colours: a source image must read the same in every Markdown or Typst viewer. */
private val FIGURE_COLORS = PdfCanvasColors(
    backgroundArgb = 0xFFFFFFFF.toInt(),
    ruleLineArgb = 0xFFC7C7C7.toInt(),
    textArgb = 0xFF000000.toInt(),
    secondaryTextArgb = 0xFF5E5E5E.toInt(),
    accentArgb = 0xFF0063C6.toInt(),
    codeBackgroundArgb = 0x14000000,
)
