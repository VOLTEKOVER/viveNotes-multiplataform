package com.vivenotes.model.ink

import com.vivenotes.model.Outline
import com.vivenotes.model.newId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

class ShapeReseedTest {

    @Test
    fun aVariantSwapReseedsInsideTheSameBoundsAndKeepsTheStyle() {
        val original = Outline.Shape(
            id = "shape", kind = ShapeKind.Rectangle,
            segments = seedSegments(ShapeKind.Rectangle, 100f, 120f, 300f, 280f, nextId = ::newId),
            borderArgb = 0xFF112233.toInt(), borderWidth = 4f,
            lineType = LineType.Dashed, fillArgb = 0xFF445566.toInt(),
        ).withRecomputedBounds()

        val rounded = original.reseedAs(ShapeKind.RoundedRectangle, nextId = ::newId)

        assertNotSame(original, rounded)
        assertEquals("shape", rounded.id)
        assertEquals(ShapeKind.RoundedRectangle, rounded.kind)
        assertEquals(original.x, rounded.x, 0.01f)
        assertEquals(original.y, rounded.y, 0.01f)
        assertEquals(original.width, rounded.width, 0.01f)
        assertEquals(original.height, rounded.height, 0.01f)
        assertEquals(8, rounded.segments.size)
        assertEquals(original.borderArgb, rounded.borderArgb)
        assertEquals(original.borderWidth, rounded.borderWidth)
        assertEquals(original.lineType, rounded.lineType)
        assertEquals(original.fillArgb, rounded.fillArgb)
    }

    @Test
    fun anOpenVariantDropsTheFillItCannotShow() {
        val rectangle = Outline.Shape(
            id = "shape", kind = ShapeKind.Rectangle,
            segments = seedSegments(ShapeKind.Rectangle, 100f, 120f, 300f, 280f, nextId = ::newId),
            fillArgb = 0xFF445566.toInt(),
        ).withRecomputedBounds()

        val arrow = rectangle.reseedAs(ShapeKind.Arrow, nextId = ::newId)

        assertEquals(ShapeKind.Arrow, arrow.kind)
        assertEquals(null, arrow.fillArgb)
    }
}
