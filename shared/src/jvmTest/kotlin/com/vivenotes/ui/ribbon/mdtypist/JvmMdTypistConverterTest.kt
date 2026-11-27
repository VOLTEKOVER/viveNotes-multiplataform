package com.vivenotes.ui.ribbon.mdtypist

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.registerSkikoComposeImplementation
import com.vivenotes.ai.AiModelStore
import com.vivenotes.ai.LocalAiClient
import com.vivenotes.ai.LocalAiRequest
import com.vivenotes.ai.LocalAiSettings
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
import com.vivenotes.workspace.WorkspaceState
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The desktop converter must work from the document even when there is no ink to recognize. */
class JvmMdTypistConverterTest {

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
        id = "typed-page",
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
                TableRow(id = "row", cells = listOf(
                    TableCell(id = "a", blocks = listOf(Block.of("Reagente"))),
                    TableCell(id = "b", blocks = listOf(Block.of("Prodotto"))),
                )),
            )),
            Outline.Shape(id = "shape", y = 50f, kind = ShapeKind.Rectangle, width = 120f, height = 80f,
                segments = listOf(ShapeSegment("edge", 0f, 50f, 120f, 50f))),
            Outline.Graph(id = "graph", y = 60f, expression = "sin(x)", latex = "\\sin x"),
        )),
    )

    private fun stateWith(page: PageSummary): WorkspaceState {
        val demo = WorkspaceState.demo()
        return demo.copy(
            selectedPageId = page.id,
            notebooks = demo.notebooks.map { notebook ->
                if (notebook.id != demo.selectedNotebookId) notebook else notebook.copy(
                    sections = notebook.sections.map { section ->
                        if (section.id != demo.selectedSectionId) section else section.copy(pages = listOf(page))
                    },
                )
            },
        )
    }

    @Test
    fun `a typed page without ink converts every element`() = runBlocking {
        val requests = mutableListOf<LocalAiRequest>()
        val client = object : LocalAiClient {
            override suspend fun complete(request: LocalAiRequest): Result<String> {
                requests += request
                return Result.success("# Reazioni")
            }
        }
        val models = AiModelStore(File(System.getProperty("java.io.tmpdir"), "mdtypist-${System.nanoTime()}"))
        try {
            val result = JvmMdTypistConverter(models, client, pictures = pictures).convert(
                stateWith(page()), MdTypistFormat.Markdown, LocalAiSettings(),
            )

            assertTrue(result is MdTypistConversionResult.Success, "expected a conversion, got $result")
            val source = result.source
            assertTrue("Ossidazione" in source)
            assertTrue("pi r^2" in source)
            assertTrue("x^2 + y^2" in source)
            assertTrue("Reagente" in source)
            assertTrue("![image-1.png](image-1.png)" in source)
            assertTrue("![shape-1.png](shape-1.png)" in source)
            assertTrue("![graph-1.png](graph-1.png)" in source)
            assertEquals(
                setOf("image-1.png", "shape-1.png", "graph-1.png"),
                result.figures.map { it.name }.toSet(),
            )
            assertTrue(result.figures.all { it.png.isNotEmpty() })

            val prompt = requests.single().prompt
            assertTrue("TYPED TEXT BLOCKS" in prompt)
            assertTrue("DISPLAY FORMULAS" in prompt)
            assertTrue("--- TABLES ---" in prompt)
            assertTrue("--- SHAPES ---" in prompt)
            assertTrue("--- GRAPHS ---" in prompt)
            assertTrue("image-1.png" in prompt)
            assertTrue("shape-1.png" in prompt)
            assertTrue("graph-1.png" in prompt)
        } finally {
            models.close()
        }
    }
}
