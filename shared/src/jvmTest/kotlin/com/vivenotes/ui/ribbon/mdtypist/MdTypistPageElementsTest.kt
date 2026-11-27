package com.vivenotes.ui.ribbon.mdtypist

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.registerSkikoComposeImplementation
import com.vivenotes.data.ImportedPicture
import com.vivenotes.data.PictureLibrary
import com.vivenotes.model.Block
import com.vivenotes.model.BlockType
import com.vivenotes.model.Mark
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.TableCell
import com.vivenotes.model.TableRow
import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.model.ink.ShapeSegment
import com.vivenotes.workspace.PageSummary
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Convert must read the document, not just its handwriting. */
class MdTypistPageElementsTest {

    @OptIn(InternalComposeUiApi::class)
    @BeforeTest
    fun initializeStandaloneGraphics() = registerSkikoComposeImplementation()

    private val png by lazy {
        Surface.makeRasterN32Premul(2, 2).use { surface ->
            checkNotNull(surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)).use { it.bytes }
        }
    }

    private val pictures = object : PictureLibrary {
        override suspend fun choose(): ImportedPicture? = null
        override suspend fun import(bytes: ByteArray): ImportedPicture? = null
        override suspend fun bytes(attachmentId: String): ByteArray? = png.takeIf { attachmentId == "pic" }
    }

    private fun page() = PageSummary(
        id = "page-1",
        title = "Reazioni",
        preview = "",
        createdLabel = "",
        document = PageDoc(outlines = listOf(
            Outline.Text(id = "text", y = 10f, blocks = listOf(
                Block.of("Ossidazione", BlockType.Heading2),
                Block(id = "inline", type = BlockType.Paragraph, runs = listOf(
                    com.vivenotes.model.Run("Area "),
                    com.vivenotes.model.Run("pi r^2", setOf(Mark.Equation("pi r^2"))),
                )),
            )),
            Outline.Equation(id = "formula", y = 20f, latex = "x^2 + y^2"),
            Outline.Image(id = "picture", y = 30f, attachmentId = "pic", height = 100f),
            Outline.Table(id = "table", y = 40f, columns = listOf(100f, 100f), headerRow = true, rows = listOf(
                TableRow(id = "row", cells = listOf(TableCell(id = "a", blocks = listOf(Block.of("Reagente"))),
                    TableCell(id = "b", blocks = listOf(Block.of("Prodotto")))))
            )),
            Outline.Shape(id = "shape", y = 50f, kind = ShapeKind.Rectangle, width = 120f, height = 80f,
                segments = listOf(ShapeSegment("edge", 0f, 50f, 120f, 50f))),
            Outline.Graph(id = "graph", y = 60f, expression = "sin(x)", latex = "\\sin x"),
        )),
    )

    @Test
    fun `describes typed text formulas pictures tables shapes and graphs`() = runBlocking {
        val elements = describePage(page(), pictures, renderer = null)
        val input = elements.input

        assertEquals(listOf("image-1.png", "shape-1.png", "graph-1.png"), input.imageNames)
        assertEquals(3, elements.assets.size)
        assertTrue(elements.assets.any { it.name == "image-1.png" && it.png.isNotEmpty() })
        assertTrue(elements.assets.any { it.name == "shape-1.png" && it.png.isNotEmpty() })
        assertTrue(elements.assets.any { it.name == "graph-1.png" && it.png.isNotEmpty() })
        assertTrue(input.blocks.any { it.text == "Ossidazione" })
        assertTrue(input.blocks.any { "pi r^2" in it.text })
        assertTrue("x^2 + y^2" in input.formulas)
        assertTrue("Reagente" in input.tables.single().rows.single())
        assertTrue(input.shapes.single().contains("Rectangle"))
        assertTrue(input.graphs.single().contains("\\sin x"))
    }

    @Test
    fun `prompt and fallback carry every element`() = runBlocking {
        val input = describePage(page(), pictures, renderer = null).input.copy(handwriting = "CIAO")
        val prompt = MdTypistPrompts.prompt(input)
        val markdown = MdTypistPrompts.fallback(MdTypistFormat.Markdown, input)
        val typst = MdTypistPrompts.fallback(MdTypistFormat.Typst, input)

        assertTrue("TYPED TEXT BLOCKS" in prompt)
        assertTrue("DISPLAY FORMULAS" in prompt)
        assertTrue("image-1.png" in prompt)
        assertTrue("shape-1.png" in prompt)
        assertTrue("graph-1.png" in prompt)
        assertTrue("CIAO" in prompt)
        assertTrue("Ossidazione" in markdown)
        assertTrue("x^2 + y^2" in markdown)
        assertTrue("![image-1.png](image-1.png)" in markdown)
        assertTrue("![shape-1.png](shape-1.png)" in markdown)
        assertTrue("![graph-1.png](graph-1.png)" in markdown)
        assertTrue("Reagente" in markdown)
        assertTrue("= Reazioni" in typst)
        assertTrue("#image(\"image-1.png\")" in typst)
    }

    @Test
    fun `document restores every element the model omitted`() = runBlocking {
        val input = describePage(page(), pictures, renderer = null).input.copy(handwriting = "CIAO")
        val markdown = MdTypistPrompts.document(
            MdTypistFormat.Markdown, input, "# Reazioni\n\nUna sola frase.",
        )

        assertTrue(markdown.startsWith("# Reazioni"))
        assertTrue("Ossidazione" in markdown)
        assertTrue("pi r^2" in markdown)
        assertTrue("x^2 + y^2" in markdown)
        assertTrue("Reagente" in markdown)
        assertTrue("CIAO" in markdown)
        assertTrue("![image-1.png](image-1.png)" in markdown)
        assertTrue("![shape-1.png](shape-1.png)" in markdown)
        assertTrue("![graph-1.png](graph-1.png)" in markdown)
    }
}
