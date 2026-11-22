package com.vivenotes.model.ink

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recognition has to be predictable: the same ink must always produce the same shape, and a
 * stroke that is not a shape must produce nothing. All coordinates are page units, the same space
 * [trace] draws in, so a jitter of two units here is the wobble of a real hand at page scale.
 */
class ShapeFitTest {

    @Test
    fun `a clean diagonal is a line with its drawn ends`() {
        val fitted = ShapeFit.classify(line(100f, 100f, 400f, 260f))

        assertNotNull(fitted)
        assertEquals(ShapeKind.Line, fitted!!.kind)
        assertEquals(100f, fitted.startX, 0.01f)
        assertEquals(100f, fitted.startY, 0.01f)
        assertEquals(400f, fitted.endX, 0.01f)
        assertEquals(260f, fitted.endY, 0.01f)
        assertEquals(100f, fitted.left, 0.01f)
        assertEquals(100f, fitted.top, 0.01f)
        assertEquals(400f, fitted.right, 0.01f)
        assertEquals(260f, fitted.bottom, 0.01f)
        assertConfidence(fitted)
    }

    @Test
    fun `a clean rectangle is a rectangle`() {
        val fitted = ShapeFit.classify(rectangle(100f, 120f, 300f, 280f))

        assertNotNull(fitted)
        assertEquals(ShapeKind.Rectangle, fitted!!.kind)
        assertEquals(100f, fitted.left, 0.01f)
        assertEquals(120f, fitted.top, 0.01f)
        assertEquals(300f, fitted.right, 0.01f)
        assertEquals(280f, fitted.bottom, 0.01f)
        assertConfidence(fitted)
    }

    @Test
    fun `a clean ellipse is an ellipse`() {
        val fitted = ShapeFit.classify(ellipse(200f, 200f, 100f, 80f))

        assertNotNull(fitted)
        assertEquals(ShapeKind.Ellipse, fitted!!.kind)
        assertConfidence(fitted)
    }

    @Test
    fun `a clean triangle is a triangle`() {
        val fitted = ShapeFit.classify(triangle(100f, 120f, 300f, 280f))

        assertNotNull(fitted)
        assertEquals(ShapeKind.Triangle, fitted!!.kind)
        assertConfidence(fitted)
    }

    @Test
    fun `an arrow keeps the direction it was drawn in`() {
        val rightwards = ShapeFit.classify(arrow(100f, 200f, 400f, 200f))
        val leftwards = ShapeFit.classify(arrow(400f, 200f, 100f, 200f))

        assertNotNull(rightwards)
        assertEquals(ShapeKind.Arrow, rightwards!!.kind)
        assertEquals(100f, rightwards.startX, 1f)
        assertEquals(400f, rightwards.endX, 1f)
        assertTrue("the rightward arrow should point right", rightwards.endX > rightwards.startX)

        assertNotNull(leftwards)
        assertEquals(ShapeKind.Arrow, leftwards!!.kind)
        assertEquals(400f, leftwards.startX, 1f)
        assertEquals(100f, leftwards.endX, 1f)
        assertTrue("the leftward arrow should point left", leftwards.endX < leftwards.startX)
        assertConfidence(rightwards)
        assertConfidence(leftwards)
    }

    @Test
    fun `a few units of wobble still reads the intended shape`() {
        val rectangle = ShapeFit.classify(jitter(rectangle(100f, 120f, 300f, 280f), 2f, 101))
        val ellipse = ShapeFit.classify(jitter(ellipse(200f, 200f, 100f, 80f), 2f, 202))
        val triangle = ShapeFit.classify(jitter(triangle(100f, 120f, 300f, 280f), 2f, 303))
        val arrow = ShapeFit.classify(jitter(arrow(100f, 200f, 400f, 200f), 2f, 404))

        assertNotNull("a wobbly rectangle was refused", rectangle)
        assertEquals(ShapeKind.Rectangle, rectangle!!.kind)
        assertNotNull("a wobbly ellipse was refused", ellipse)
        assertEquals(ShapeKind.Ellipse, ellipse!!.kind)
        assertNotNull("a wobbly triangle was refused", triangle)
        assertEquals(ShapeKind.Triangle, triangle!!.kind)
        assertNotNull("a wobbly arrow was refused", arrow)
        assertEquals(ShapeKind.Arrow, arrow!!.kind)
    }

    @Test
    fun `every remaining shape kind is recognised from its own traced geometry`() {
        val left = 100f
        val top = 120f
        val right = 320f
        val bottom = 280f
        ShapeKind.entries.filter { it != ShapeKind.Line && it != ShapeKind.Arrow }.forEach { kind ->
            val fitted = ShapeFit.classify(traced(kind, left, top, right, bottom))
            assertNotNull("$kind was refused", fitted)
            assertEquals("$kind was misread as ${fitted!!.kind}", kind, fitted.kind)
            assertConfidence(fitted)
        }
    }

    @Test
    fun `new flat shapes and a solid tolerate the same hand wobble`() {
        val diamond = ShapeFit.classify(jitter(traced(ShapeKind.Diamond, 100f, 120f, 320f, 280f), 2f, 505))
        val hexagon = ShapeFit.classify(jitter(traced(ShapeKind.Hexagon, 100f, 120f, 320f, 280f), 2f, 606))
        val cube = ShapeFit.classify(jitter(traced(ShapeKind.Cube, 100f, 120f, 320f, 280f), 2f, 707))

        assertNotNull("a wobbly diamond was refused", diamond)
        assertEquals(ShapeKind.Diamond, diamond!!.kind)
        assertNotNull("a wobbly hexagon was refused", hexagon)
        assertEquals(ShapeKind.Hexagon, hexagon!!.kind)
        assertNotNull("a wobbly cube was refused", cube)
        assertEquals(ShapeKind.Cube, cube!!.kind)
    }

    @Test
    fun `disabled kinds do not win or hide an enabled match`() {
        val rectangle = rectangle(100f, 120f, 300f, 280f)

        assertNull(ShapeFit.classify(rectangle, emptySet()))
        assertNull(ShapeFit.classify(rectangle, setOf(ShapeKind.Ellipse)))
        assertEquals(
            ShapeKind.Rectangle,
            ShapeFit.classify(rectangle, setOf(ShapeKind.Rectangle, ShapeKind.Ellipse))!!.kind,
        )
    }

    @Test
    fun `an open scribble is refused rather than guessed`() {
        val scribble = floatArrayOf(
            100f, 100f,
            300f, 260f,
            100f, 260f,
            300f, 100f,
            200f, 180f,
        )
        assertNull(ShapeFit.classify(scribble))
    }

    @Test
    fun `a short stroke is refused`() {
        assertNull(ShapeFit.classify(line(100f, 100f, 115f, 100f)))
        assertNull(ShapeFit.classify(floatArrayOf(100f, 100f, 104f, 102f, 108f, 100f)))
    }

    @Test
    fun `malformed and non finite input is refused without crashing`() {
        assertNull(ShapeFit.classify(FloatArray(0)))
        assertNull(ShapeFit.classify(floatArrayOf(1f, 2f)))
        assertNull(ShapeFit.classify(floatArrayOf(1f, 2f, 3f)))
        assertNull(ShapeFit.classify(floatArrayOf(0f, 0f, Float.NaN, 10f, 20f, 20f)))
        assertNull(ShapeFit.classify(floatArrayOf(0f, 0f, 10f, Float.POSITIVE_INFINITY, 20f, 20f)))
        assertNull(
            ShapeFit.classify(
                floatArrayOf(0f, 0f, 10f, 10f, Float.NEGATIVE_INFINITY, 20f),
            ),
        )
    }

    @Test
    fun `a shape hold recognises the stroke it collected and stops after being spent`() {
        val hold = ShapeHold(slop = 4f)
        val points = rectangle(100f, 120f, 300f, 280f)
        var visualUpdates = 0
        for (index in points.indices step 2) {
            if (hold.observe(points[index], points[index + 1])) visualUpdates++
        }

        assertTrue("the initial point should be an update", visualUpdates > 1)
        assertFalse(hold.spent)
        val candidate = hold.candidate()
        assertNotNull(candidate)
        assertEquals(ShapeKind.Rectangle, candidate!!.kind)

        hold.spend()
        assertTrue(hold.spent)
        assertNull(hold.candidate())
        assertFalse(hold.observe(400f, 400f))

        val disabled = ShapeHold(slop = 4f, enabledKinds = setOf(ShapeKind.Ellipse))
        for (index in points.indices step 2) {
            disabled.observe(points[index], points[index + 1])
        }
        assertNull(disabled.candidate())
    }

    // -----------------------------------------------------------------------------------------

    private fun assertConfidence(candidate: ShapeCandidate) {
        assertTrue("confidence was ${candidate.confidence}", candidate.confidence > 0f)
        assertTrue("confidence was ${candidate.confidence}", candidate.confidence <= 1f)
    }

    private fun line(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        samples: Int = 64,
    ): FloatArray {
        val out = FloatArray(samples * 2)
        repeat(samples) { index ->
            val t = index / (samples - 1f)
            out[index * 2] = fromX + (toX - fromX) * t
            out[index * 2 + 1] = fromY + (toY - fromY) * t
        }
        return out
    }

    private fun corners(vararg vertices: Pair<Float, Float>): FloatArray {
        val out = ArrayList<Float>()
        for (index in 0 until vertices.size - 1) {
            val from = vertices[index]
            val to = vertices[index + 1]
            out.addAll(line(from.first, from.second, to.first, to.second, 24).asList())
        }
        return out.toFloatArray()
    }

    private fun rectangle(left: Float, top: Float, right: Float, bottom: Float): FloatArray =
        corners(left to top, right to top, right to bottom, left to bottom, left to top)

    private fun triangle(left: Float, top: Float, right: Float, bottom: Float): FloatArray =
        corners((left + right) / 2f to top, right to bottom, left to bottom, (left + right) / 2f to top)

    private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, samples: Int = 120): FloatArray {
        val out = FloatArray(samples * 2)
        repeat(samples) { index ->
            val angle = 2.0 * PI * index / (samples - 1)
            out[index * 2] = (cx + rx * cos(angle)).toFloat()
            out[index * 2 + 1] = (cy + ry * sin(angle)).toFloat()
        }
        return out
    }

    /** Shaft from tail to tip, then one wing, back through the tip, and the other wing. */
    private fun arrow(tailX: Float, tailY: Float, tipX: Float, tipY: Float): FloatArray {
        val shaft = line(tailX, tailY, tipX, tipY)
        val length = hypot(tipX - tailX, tipY - tailY)
        val head = min(length * 0.22f, 26f)
        val back = atan2(tipY - tailY, tipX - tailX) + PI.toFloat()
        val wingOneX = tipX + head * cos(back - 0.42f)
        val wingOneY = tipY + head * sin(back - 0.42f)
        val wingTwoX = tipX + head * cos(back + 0.42f)
        val wingTwoY = tipY + head * sin(back + 0.42f)
        return shaft + floatArrayOf(
            wingOneX.toFloat(), wingOneY.toFloat(),
            tipX, tipY,
            wingTwoX.toFloat(), wingTwoY.toFloat(),
        )
    }

    /** Jitters interior points only, so a closed stroke keeps its exact closure. */
    private fun jitter(points: FloatArray, amount: Float, seed: Long): FloatArray {
        val random = Random(seed)
        val out = points.copyOf()
        for (index in 2 until out.size - 2) {
            out[index] += (random.nextFloat() - 0.5f) * 2f * amount
        }
        return out
    }

    /**
     * One continuous gesture visiting every polyline of a trace. The short connector between two
     * edges is how a pen moves from one to the next without lifting; it keeps the stroke honest for
     * the symmetric score instead of pretending the segments are unrelated.
     */
    private fun traced(kind: ShapeKind, left: Float, top: Float, right: Float, bottom: Float): FloatArray {
        val remaining = trace(kind, left, top, right, bottom).solid.map { it.copyOf() }.toMutableList()
        val out = ArrayList<Float>()
        out.addAll(remaining.removeAt(0).asList())
        while (remaining.isNotEmpty()) {
            val tailX = out[out.size - 2]
            val tailY = out[out.size - 1]
            var bestIndex = 0
            var bestReverse = false
            var bestDistance = Float.POSITIVE_INFINITY
            remaining.forEachIndexed { index, polyline ->
                listOf(false, true).forEach { reverse ->
                    val headX = if (reverse) polyline[polyline.size - 2] else polyline[0]
                    val headY = if (reverse) polyline[polyline.size - 1] else polyline[1]
                    val distance = hypot(headX - tailX, headY - tailY)
                    if (distance < bestDistance) {
                        bestDistance = distance
                        bestIndex = index
                        bestReverse = reverse
                    }
                }
            }
            var next = remaining.removeAt(bestIndex)
            if (bestReverse) next = next.reversedPoints()
            if (hypot(next[0] - tailX, next[1] - tailY) > 0.01f) {
                out.addAll(line(tailX, tailY, next[0], next[1], 8).drop(2))
            }
            out.addAll(next.asList())
        }
        return out.toFloatArray()
    }

    private fun FloatArray.reversedPoints(): FloatArray {
        val out = FloatArray(size)
        for (index in indices step 2) {
            out[index] = this[size - 2 - index]
            out[index + 1] = this[size - 1 - index]
        }
        return out
    }
}
