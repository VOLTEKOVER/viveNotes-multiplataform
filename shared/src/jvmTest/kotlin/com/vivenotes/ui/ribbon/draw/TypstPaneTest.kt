package com.vivenotes.ui.ribbon.draw

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.data.ImportedPicture
import com.vivenotes.data.PictureLibrary
import com.vivenotes.typst.SampleTypstSource
import com.vivenotes.typst.TypstDiagramSource
import com.vivenotes.typst.TypstDiagramSourceCodec
import com.vivenotes.typst.TypstRenderer
import com.vivenotes.typst.TypstRenderResult
import com.vivenotes.ui.theme.ViveNotesTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TypstPaneTest {

    private class Renderer : TypstRenderer {
        val requests = mutableListOf<TypstDiagramSource>()
        override suspend fun render(diagram: TypstDiagramSource): TypstRenderResult {
            requests += diagram
            return TypstRenderResult.Success(
                """<svg xmlns="http://www.w3.org/2000/svg" width="100" height="50"><rect width="100" height="50" fill="red"/></svg>""",
            )
        }
    }

    private class Pictures : PictureLibrary {
        override suspend fun choose(): ImportedPicture? = null
        override suspend fun import(bytes: ByteArray): ImportedPicture = ImportedPicture("typst", 100, 50)
        override suspend fun bytes(attachmentId: String): ByteArray? = null
        override suspend fun vector(bytes: ByteArray): Painter = object : Painter() {
            override val intrinsicSize: Size = Size(100f, 50f)
            override fun DrawScope.onDraw() { drawRect(Color.Red) }
        }
    }

    @Test
    fun previewsPastesRecolorsAndPlacesTheDiagram() = runDesktopComposeUiTest(width = 640, height = 1000) {
        val renderer = Renderer()
        val source = mutableStateOf(SampleTypstSource)
        val colors = mutableStateOf(TypstDiagramSourceCodec.bindings(SampleTypstSource))
        val placed = mutableListOf<Pair<String, TypstDiagramSource>>()
        val sourceChanges = mutableListOf<String>()

        setContent {
            ViveNotesTheme {
                TypstPane(
                    source = source.value,
                    onSourceChange = { next ->
                        source.value = next
                        sourceChanges += next
                        colors.value = TypstDiagramSourceCodec.bindings(next, colors.value)
                    },
                    colors = colors.value,
                    onColorsChange = { colors.value = it },
                    renderer = renderer,
                    pictures = Pictures(),
                    enabled = true,
                    onPlace = { svg, diagram -> placed += svg to diagram },
                    onClose = {},
                )
            }
        }
        mainClock.advanceTimeBy(400)
        waitUntil { renderer.requests.isNotEmpty() }
        onNodeWithTag(TypstPaneTags.Preview).assertIsDisplayed()
        onNodeWithTag(TypstPaneTags.Insert).assertIsEnabled().performClick()
        assertEquals(1, placed.size)
        assertEquals(source.value, placed.single().second.source)

        val pasted = "#cetz.canvas({ line((0, 0), (1, 1), stroke: green) })"
        onNodeWithTag(TypstPaneTags.Source).performTextReplacement(pasted)
        assertEquals(pasted, sourceChanges.single())
        mainClock.advanceTimeBy(400)
        waitUntil { renderer.requests.last().source == pasted }

        onNodeWithTag(TypstPaneTags.EditToggle).performScrollTo().performClick()
        val firstSwatch = onNodeWithTag(TypstPaneTags.color(0)).performScrollTo()
        firstSwatch.performClick()
        onNodeWithText("#3b82f6").performClick()
        assertTrue(colors.value.first { it.token == "green" }.argb == 0xFF3B82F6.toInt())
    }

    @Test
    fun editModeShowsColorSlotsAndReplacesInsteadOfInserting() =
        runDesktopComposeUiTest(width = 640, height = 1000) {
            val renderer = Renderer()
            val source = mutableStateOf(SampleTypstSource)
            val colors = mutableStateOf(TypstDiagramSourceCodec.bindings(SampleTypstSource))
            val placed = mutableListOf<String>()

            setContent {
                ViveNotesTheme {
                    TypstPane(
                        source = source.value,
                        onSourceChange = { next ->
                            source.value = next
                            colors.value = TypstDiagramSourceCodec.bindings(next, colors.value)
                        },
                        colors = colors.value,
                        onColorsChange = { colors.value = it },
                        renderer = renderer,
                        pictures = Pictures(),
                        enabled = true,
                        onPlace = { svg, _ -> placed += svg },
                        onClose = {},
                        editing = true,
                        startInEditMode = true,
                    )
                }
            }
            mainClock.advanceTimeBy(400)
            waitUntil { renderer.requests.isNotEmpty() && placed.isEmpty() }
            onNodeWithTag(TypstPaneTags.PreviewToggle).assertIsDisplayed()
            onNodeWithTag(TypstPaneTags.EditToggle).assertIsDisplayed()
            onNodeWithTag(TypstPaneTags.color(0)).assertIsDisplayed()
            onNodeWithTag(TypstPaneTags.Insert).assertDoesNotExist()
            onNodeWithTag(TypstPaneTags.Replace).assertIsEnabled().performClick()
            assertEquals(1, placed.size)
        }
}
