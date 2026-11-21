package com.vivenotes.typst

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TypstDiagramSourceCodecTest {

    @Test
    fun bindsDistinctLiteralColorsAndIgnoresStringsAndComments() {
        val source = """
            // red is only a comment
            #let label = "blue and #ff0000"
            #cetz.canvas({
              import cetz.draw: *
              line((0, 0), (2, 2), stroke: 2pt + red)
              circle((1, 1), radius: 0.5, fill: rgb("#3776ab"))
              rect((0, 0), (2, 2), stroke: 1pt + gray)
            })
        """.trimIndent()

        val colors = TypstDiagramSourceCodec.bindings(source)

        assertEquals(listOf("red", "rgb(\"#3776ab\")", "gray"),
            colors.filter { it.used }.map { it.token })
        assertEquals(0xFFFF4136.toInt(), colors[0].argb)
        assertEquals(0xFF3776AB.toInt(), colors[1].argb)
        assertEquals(0xFFAAAAAA.toInt(), colors[2].argb)
        assertFalse(colors[3].used)
    }

    @Test
    fun renderSourceRewritesOnlyCodeTokensAndKeepsEditedColors() {
        val source = """
            #cetz.canvas({
              line((0, 0), (1, 1), stroke: red)
              circle((0, 0), fill: rgb("#123456"))
              let note = "red"
            })
        """.trimIndent()
        val colors = TypstDiagramSourceCodec.bindings(source).mapIndexed { index, binding ->
            if (index == 0) binding.copy(argb = 0xFF123456.toInt()) else binding
        }

        val rendered = TypstDiagramSourceCodec.renderSource(source, colors)

        assertTrue(rendered.contains("#let viveC0 = rgb(\"#123456\")"))
        assertTrue(rendered.contains("stroke: viveC0"))
        assertTrue(rendered.contains("fill: viveC1"))
        assertTrue(rendered.contains("let note = \"red\""))
        assertFalse(rendered.contains("stroke: red"))
    }

    @Test
    fun allocatesUniqueSlotsBeyondThePaletteAndKeepsExplicitReferences() {
        val source = listOf("red", "blue", "green", "yellow", "purple", "orange", "navy")
            .joinToString("\n") { "line((0, 0), (1, 1), stroke: $it)" }
            .plus("\nline((0, 0), (1, 1), stroke: viveC2)")

        val colors = TypstDiagramSourceCodec.bindings(source)
        val used = colors.filter { it.used }

        assertEquals(8, used.size)
        assertEquals((0 until 8).toList(), used.map { colors.indexOf(it) })
        assertTrue(used.any { it.token == "viveC2" && colors.indexOf(it) == 2 })
    }

    @Test
    fun metadataSurvivesEmbeddingAndReading() {
        val diagram = TypstDiagramSource("#cetz.canvas({})",
            listOf(TypstColorBinding("red", 0xFF112233.toInt(), used = true)))
        val svg = """<svg xmlns="http://www.w3.org/2000/svg"><path d="M0 0"/></svg>"""

        val embedded = TypstDiagramSourceCodec.embed(svg, diagram)

        assertTrue(embedded.startsWith("<svg"))
        assertTrue(embedded.trimEnd().endsWith("</svg>"))
        assertEquals(diagram, TypstDiagramSourceCodec.read(embedded))
    }

    @Test
    fun missingMetadataIsNotAnError() {
        assertEquals(null, TypstDiagramSourceCodec.read("<svg xmlns=\"http://www.w3.org/2000/svg\"/>"))
    }

    @Test
    fun existingColorsArePreservedWhenSourceChanges() {
        val original = TypstDiagramSourceCodec.bindings("line((0, 0), (1, 1), stroke: red)")
        val edited = original.map { if (it.token == "red") it.copy(argb = 0xFF00FF00.toInt()) else it }

        val rebound = TypstDiagramSourceCodec.bindings("line((0, 0), (1, 1), stroke: red)\ncircle((0, 0), fill: blue)", edited)

        assertNotNull(rebound.firstOrNull { it.token == "red" })
        assertEquals(0xFF00FF00.toInt(), rebound.first { it.token == "red" }.argb)
        assertTrue(rebound.any { it.token == "blue" && it.used })
    }
}
