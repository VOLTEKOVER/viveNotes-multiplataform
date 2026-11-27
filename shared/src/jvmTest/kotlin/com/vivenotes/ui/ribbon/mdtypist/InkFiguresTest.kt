package com.vivenotes.ui.ribbon.mdtypist

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.registerSkikoComposeImplementation
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.ai.InkTextRegion
import com.vivenotes.byteink.kit.PageStroke
import com.vivenotes.byteink.kit.ViveBrushes
import com.vivenotes.byteink.kit.ViveInkTool
import org.jetbrains.skia.Image
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Which leftover ink becomes a figure, and what is written for it. */
class InkFiguresTest {
    @OptIn(InternalComposeUiApi::class)
    @BeforeTest
    fun initializeStandaloneGraphics() = registerSkikoComposeImplementation()

    private fun stroke(id: String, x: Float, y: Float): PageStroke {
        val tool = ViveInkTool(ViveBrushes.MARKER, 0, 0xff000000.toInt(), 3f)
        val shape = Stroke(tool.brush, MutableStrokeInputBatch().apply {
            add(InputToolType.MOUSE, 0f, 0f, 0L)
            add(InputToolType.MOUSE, 40f, 40f, 10L)
        })
        return PageStroke(id, shape, offsetX = x, offsetY = y)
    }

    private fun box(id: String, left: Float, top: Float, size: Float = 40f) =
        FigureBox(id, left, top, left + size, top + size)

    @Test
    fun textStrokesAndTinyMarksAreNotFigures() {
        val strokes = listOf(stroke("text", 0f, 0f), stroke("figure", 200f, 0f))
        val boxes = figureBoxes(strokes, setOf("text"))
        assertEquals(listOf("figure"), boxes.map { it.id })
    }

    @Test
    fun nearbyStrokesFormOneFigureAndDistantOnesStaySeparate() {
        val boxes = listOf(
            box("a", 0f, 0f), box("b", 20f, 20f), box("c", 400f, 0f),
        )
        val groups = groupFigureBoxes(boxes)
        assertEquals(2, groups.size)
        assertEquals(setOf("a", "b"), groups[0].map { it.id }.toSet())
        assertEquals(listOf("c"), groups[1].map { it.id })
    }

    @Test
    fun rasterizedFigureIsAValidPng() {
        val png = checkNotNull(renderFigurePng(listOf(stroke("curve", 0f, 0f)), 1.5f))
        assertTrue(png.size > 8)
        assertEquals(listOf(0x89, 0x50, 0x4e, 0x47), png.take(4).map { it.toInt() and 0xFF })
        val image = checkNotNull(Image.makeFromEncoded(png))
        assertTrue(image.width > 0 && image.height > 0)
    }

    @Test
    fun detectedFiguresAreNamedInReadingOrder() {
        val strokes = listOf(
            stroke("lower", 0f, 400f), stroke("upper", 0f, 0f),
            stroke("read-as-text", 900f, 0f),
        )
        val recognized = listOf(InkTextRegion(
            id = "line-1", text = "graph", confidence = 1f,
            left = 900f, top = 0f, right = 950f, bottom = 40f,
            strokeIds = listOf("read-as-text"),
        ))
        val figures = detectFigures(strokes, recognized)
        assertEquals(listOf("figure-1.png", "figure-2.png"), figures.map { it.name })
        figures.forEach { assertTrue(it.png.isNotEmpty()) }
    }
}
