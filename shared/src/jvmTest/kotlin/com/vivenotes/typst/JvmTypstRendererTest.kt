package com.vivenotes.typst

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JvmTypstRendererTest {

    private val source = """
        #cetz.canvas({
          import cetz.draw: *
          line((0, 0), (2, 2), stroke: 2pt + red)
          circle((1, 1), radius: 0.5, fill: blue)
          rect((0, 0), (2, 2), stroke: 1pt + gray)
        })
    """.trimIndent()

    @Test
    fun rendersCeTZOfflineAndRetainsEditableSource() = runBlocking {
        val renderer = JvmTypstRenderer()

        val result = renderer.render(TypstDiagramSource(source, TypstDiagramSourceCodec.bindings(source)))

        val svg = (result as? TypstRenderResult.Success)?.svg ?: error("Compilation failed: $result")
        assertTrue(svg.startsWith("<svg"))
        assertTrue(svg.contains("stroke=\"#ff4136\"") || svg.contains("stroke=\"#FF4136\""))
        assertTrue(svg.contains("#0074d9") || svg.contains("#0074D9"))
        assertEquals(source, TypstDiagramSourceCodec.read(svg)?.source)
    }

    @Test
    fun recoloredBindingsChangeTheRenderedSvg() = runBlocking {
        val renderer = JvmTypstRenderer()
        val colors = TypstDiagramSourceCodec.bindings(source).map {
            when (it.token) {
                "red" -> it.copy(argb = 0xFF123456.toInt())
                "blue" -> it.copy(argb = 0xFFABCDEF.toInt())
                else -> it
            }
        }

        val result = renderer.render(TypstDiagramSource(source, colors))

        val svg = (result as? TypstRenderResult.Success)?.svg ?: error("Compilation failed: $result")
        assertTrue(svg.contains("#123456", ignoreCase = true))
        assertTrue(svg.contains("#abcdef", ignoreCase = true))
        assertEquals(0xFF123456.toInt(), TypstDiagramSourceCodec.read(svg)?.colors?.first { it.token == "red" }?.argb)
    }
}
