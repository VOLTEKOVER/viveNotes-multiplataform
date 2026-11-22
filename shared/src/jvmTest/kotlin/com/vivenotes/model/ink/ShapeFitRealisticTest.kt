package com.vivenotes.model.ink

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Recognition has to survive a stroke drawn by a person, not just a perfect trace: corners are
 * rounded, speed varies along the edge, and the ends rarely meet exactly. These generators keep
 * the shape honest while adding the small imperfections a real pen introduces.
 */
class ShapeFitRealisticTest {

    @Test
    fun everyKindSurvivesARealisticStroke() {
        listOf(0L, 7L, 19L).forEach { seed ->
            ShapeKind.entries.forEach { kind ->
                val points = realistic(kind, 100f, 120f, 320f, 280f, 3f, seed)
                val got = ShapeFit.classify(points)
                assertNotNull("$kind (seed $seed) was refused on a realistic stroke", got)
                assertEquals("$kind (seed $seed) was misread as ${got!!.kind}", kind, got.kind)
            }
        }
    }

    @Test
    fun commonShapesSurviveStrongerWobble() {
        // A rectangle drawn with rounded corners and heavy wobble is honestly closer to a rounded
        // rectangle, so that pair is covered by the lower-wobble test above.
        listOf(ShapeKind.Line, ShapeKind.Ellipse,
            ShapeKind.Triangle, ShapeKind.RightTriangle, ShapeKind.Pentagon).forEach { kind ->
            listOf(0L, 11L, 23L).forEach { seed ->
                val points = realistic(kind, 100f, 120f, 320f, 280f, 4f, seed)
                val got = ShapeFit.classify(points)
                assertNotNull("$kind (seed $seed) was refused with heavier wobble", got)
                assertEquals("$kind (seed $seed) was misread as ${got!!.kind}", kind, got.kind)
            }
        }
    }

    @Test
    fun allTrianglesAreRecognisedRegardlessOfOrientation() {
        val box = listOf(100f, 120f, 320f, 280f)
        val variants = listOf(
            // Upright
            listOf(210f to 120f, 320f to 280f, 100f to 280f, 210f to 120f),
            // Inverted
            listOf(210f to 280f, 320f to 120f, 100f to 120f, 210f to 280f),
            // Right angle at each of the four corners
            listOf(100f to 120f, 320f to 280f, 100f to 280f, 100f to 120f),
            listOf(320f to 120f, 320f to 280f, 100f to 280f, 320f to 120f),
            listOf(100f to 280f, 320f to 120f, 100f to 120f, 100f to 280f),
            listOf(320f to 280f, 320f to 120f, 100f to 120f, 320f to 280f),
        )
        variants.forEachIndexed { index, corners ->
            val pts = roundedPolyline(corners, radius = 8f)
                .let { ps -> FloatArray(ps.size * 2).also { out ->
                    ps.forEachIndexed { i, p -> out[i * 2] = p.first; out[i * 2 + 1] = p.second }
                } }
            val got = ShapeFit.classify(pts)
            val expected = if (index < 2) ShapeKind.Triangle else ShapeKind.RightTriangle
            assertNotNull("triangle orientation $index was refused", got)
            assertEquals("orientation $index misread as ${got!!.kind}", expected, got.kind)
        }
    }

    @Test
    fun arbitraryTrianglesAreRecognisedFromTheirDrawnCorners() {
        // The box-only templates cover isosceles and axis-aligned right triangles. A scalene one,
        // or a right triangle turned off the axes, must be recovered from the stroke itself.
        val axes = listOf(120f to 270f, 300f to 270f, 120f to 140f, 120f to 270f)
        val cases = listOf(
            "scalene" to listOf(105f to 275f, 185f to 121f, 318f to 263f, 105f to 275f),
            "right-15" to rotate(axes, 15f, 210f, 205f),
            "right-30" to rotate(axes, 30f, 210f, 205f),
            "right-45" to rotate(axes, 45f, 210f, 205f),
            "right-60" to rotate(axes, 60f, 210f, 205f),
        )
        cases.forEach { (name, corners) ->
            val pts = densify(corners, 8f).toFloatArray()
            val got = ShapeFit.classify(pts)
            val expected = if (name == "scalene") ShapeKind.Triangle else ShapeKind.RightTriangle
            assertNotNull("$name was refused", got)
            assertEquals("$name misread as ${got!!.kind}", expected, got.kind)
            assertEquals("$name did not report its drawn corners", 3, got.vertices?.size)
        }
    }

    @Test
    fun arbitraryTriangleKeepsItsDrawnCornersWithWobble() {
        val corners = listOf(105f to 275f, 185f to 121f, 318f to 263f, 105f to 275f)
        listOf(0L, 7L, 19L).forEach { seed ->
            val pts = jitterAndUneven(roundedPolyline(corners, radius = 7f), 2f, seed, closed = true)
            val got = ShapeFit.classify(pts)
            assertNotNull("scalene (seed $seed) was refused", got)
            assertEquals("scalene (seed $seed) misread as ${got!!.kind}", ShapeKind.Triangle, got.kind)
            val drawn = got.vertices ?: error("scalene (seed $seed) has no drawn corners")
            assertEquals(3, drawn.size)
            // The reported corners must be the stroke's own vertices, not the box template's
            // isosceles apex: the top corner must stay off the box centre by a clear margin.
            val topCorner = drawn.minByOrNull { it.second }!!
            val boxCentreX = (105f + 318f) / 2f
            kotlin.test.assertTrue(
                kotlin.math.abs(topCorner.first - boxCentreX) > 10f,
                "corner was snapped to the box template: $topCorner",
            )
        }
    }

    private fun rotate(points: List<Pair<Float, Float>>, degrees: Float, cx: Float, cy: Float): List<Pair<Float, Float>> {
        val a = Math.toRadians(degrees.toDouble())
        val cos = cos(a).toFloat()
        val sin = sin(a).toFloat()
        return points.map { p ->
            val dx = p.first - cx
            val dy = p.second - cy
            (cx + dx * cos - dy * sin) to (cy + dx * sin + dy * cos)
        }
    }

    private fun densify(points: List<Pair<Float, Float>>, spacing: Float): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>()
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val steps = maxOf(1, ceil(hypot(b.first - a.first, b.second - a.second) / spacing).toInt())
            repeat(steps) { s ->
                val t = s / steps.toFloat()
                out += a.first + (b.first - a.first) * t to a.second + (b.second - a.second) * t
            }
        }
        out += points.last()
        return out
    }

    private fun List<Pair<Float, Float>>.toFloatArray(): FloatArray {
        val out = FloatArray(size * 2)
        forEachIndexed { index, p -> out[index * 2] = p.first; out[index * 2 + 1] = p.second }
        return out
    }

    private fun realistic(kind: ShapeKind, left: Float, top: Float, right: Float, bottom: Float,
        wobble: Float, seed: Long): FloatArray {
        val centerX = (left + right) / 2f
        val centerY = (top + bottom) / 2f
        val rx = (right - left) / 2f
        val ry = (bottom - top) / 2f
        val base: List<Pair<Float, Float>> = when (kind) {
            ShapeKind.Ellipse -> (0..90).map { i ->
                val a = 2.0 * PI * i / 90.0
                centerX + rx * cos(a).toFloat() to centerY + ry * sin(a).toFloat()
            }
            ShapeKind.Curve -> (0..60).map { i ->
                val t = i / 60f
                left + (right - left) * t to bottom - (bottom - top) * sin(t * PI).toFloat()
            }
            ShapeKind.RoundedRectangle -> roundedPolyline(
                corners(kind, left, top, right, bottom),
                radius = minOf(right - left, bottom - top) * 0.18f,
            )
            ShapeKind.Line -> (0..40).map { i ->
                val t = i / 40f
                left + (right - left) * t to bottom - (bottom - top) * t
            }
            else -> roundedPolyline(corners(kind, left, top, right, bottom), radius = 10f)
        }
        return jitterAndUneven(base, wobble, seed, closed = kind != ShapeKind.Line)
    }

    private fun corners(kind: ShapeKind, l: Float, t: Float, r: Float, b: Float): List<Pair<Float, Float>> {
        val cx = (l + r) / 2f
        val cy = (t + b) / 2f
        val rx = (r - l) / 2f
        val ry = (b - t) / 2f
        return when (kind) {
            ShapeKind.Line -> listOf(l to b, r to t)
            ShapeKind.Arrow -> {
                val tip = r to cy
                val head = minOf((r - l) * 0.2f, 28f)
                listOf(l to cy, tip,
                    (r - head) to (cy - head * 0.45f), tip,
                    (r - head) to (cy + head * 0.45f))
            }
            ShapeKind.Rectangle -> listOf(l to t, r to t, r to b, l to b, l to t)
            ShapeKind.RoundedRectangle -> listOf(l + 8 to t, r - 8 to t, r to t + 8,
                r to b - 8, r - 8 to b, l + 8 to b, l to b - 8, l to t + 8, l + 8 to t)
            ShapeKind.Triangle -> listOf(cx to t, r to b, l to b, cx to t)
            ShapeKind.RightTriangle -> listOf(l to t, r to b, l to b, l to t)
            ShapeKind.Diamond -> listOf(cx to t, r to cy, cx to b, l to cy, cx to t)
            ShapeKind.Pentagon -> regular(5, cx, cy, minOf(rx, ry), -PI / 2.0)
            ShapeKind.Hexagon -> regular(6, cx, cy, minOf(rx, ry), 0.0)
            ShapeKind.L -> listOf(l to t, l to b, r to b)
            else -> tracedPoints(kind, l, t, r, b)
        }
    }

    private fun regular(n: Int, cx: Float, cy: Float, radius: Float, phase: Double) =
        (0..n).map { i ->
            val a = phase + 2.0 * PI * i / n
            (cx + radius * cos(a).toFloat()) to (cy + radius * sin(a).toFloat())
        }

    /** Stitch the visible edges into one continuous pen gesture, the way a person draws a solid. */
    private fun tracedPoints(kind: ShapeKind, l: Float, t: Float, r: Float, b: Float): List<Pair<Float, Float>> {
        val remaining = trace(kind, l, t, r, b).solid.map { it.copyOf() }.toMutableList()
        val out = ArrayList<Pair<Float, Float>>()
        out.addAll(remaining.removeAt(0).toPairs())
        while (remaining.isNotEmpty()) {
            val tail = out.last()
            var bestIndex = 0
            var bestReverse = false
            var bestDistance = Float.POSITIVE_INFINITY
            remaining.forEachIndexed { index, poly ->
                listOf(false, true).forEach { reverse ->
                    val head = if (reverse) poly.toPairs().last() else poly.toPairs().first()
                    val distance = hypot(head.first - tail.first, head.second - tail.second)
                    if (distance < bestDistance) {
                        bestDistance = distance; bestIndex = index; bestReverse = reverse
                    }
                }
            }
            var next = remaining.removeAt(bestIndex).toPairs()
            if (bestReverse) next = next.reversed()
            if (hypot(next.first().first - tail.first, next.first().second - tail.second) > 0.01f) {
                repeat(8) { s ->
                    val u = s / 7f
                    out += tail.first + (next.first().first - tail.first) * u to
                        tail.second + (next.first().second - tail.second) * u
                }
            }
            out.addAll(next)
        }
        return out
    }

    private fun FloatArray.toPairs(): List<Pair<Float, Float>> =
        (indices step 2).map { this[it] to this[it + 1] }

    /** Quadratic rounding at each vertex, with points along the straight segments between them. */
    private fun roundedPolyline(vertices: List<Pair<Float, Float>>, radius: Float): List<Pair<Float, Float>> {
        if (vertices.size < 3) return vertices
        val out = ArrayList<Pair<Float, Float>>()
        val entry = ArrayList<Pair<Float, Float>>()
        val exit = ArrayList<Pair<Float, Float>>()
        for (i in vertices.indices) {
            val prev = vertices[maxOf(0, i - 1)]
            val cur = vertices[i]
            val next = vertices[minOf(vertices.lastIndex, i + 1)]
            val d1 = hypot(cur.first - prev.first, cur.second - prev.second)
            val d2 = hypot(next.first - cur.first, next.second - cur.second)
            if (d1 < 0.01f || d2 < 0.01f) {
                entry += cur; exit += cur
                continue
            }
            val r1 = minOf(radius, d1 * 0.3f)
            val r2 = minOf(radius, d2 * 0.3f)
            entry += cur.first + (prev.first - cur.first) / d1 * r1 to
                cur.second + (prev.second - cur.second) / d1 * r1
            exit += cur.first + (next.first - cur.first) / d2 * r2 to
                cur.second + (next.second - cur.second) / d2 * r2
        }
        for (i in 0 until vertices.size - 1) {
            val cur = vertices[i]
            for (s in 0..6) {
                val u = s / 6f
                val v = 1f - u
                out += v * v * entry[i].first + 2f * v * u * cur.first + u * u * exit[i].first to
                    v * v * entry[i].second + 2f * v * u * cur.second + u * u * exit[i].second
            }
            val from = exit[i]
            val to = entry[i + 1]
            val segment = hypot(to.first - from.first, to.second - from.second)
            val steps = maxOf(2, (segment / 10f).toInt())
            repeat(steps) { s ->
                val t = s / steps.toFloat()
                out += from.first + (to.first - from.first) * t to
                    from.second + (to.second - from.second) * t
            }
        }
        val closing = vertices.last()
        for (s in 0..6) {
            val u = s / 6f
            val v = 1f - u
            out += v * v * entry[entry.lastIndex].first + 2f * v * u * closing.first + u * u * exit[exit.lastIndex].first to
                v * v * entry[entry.lastIndex].second + 2f * v * u * closing.second + u * u * exit[exit.lastIndex].second
        }
        return out
    }

    private fun jitterAndUneven(points: List<Pair<Float, Float>>, amount: Float, seed: Long,
        closed: Boolean): FloatArray {
        val random = Random(seed)
        val out = ArrayList<Float>()
        var carry = 0f
        points.forEachIndexed { index, p ->
            if (amount > 0f && index in 1 until points.size - 1) {
                carry = carry * 0.8f + (random.nextFloat() - 0.5f) * amount
                val wobble = carry
                val dx = if (index + 1 < points.size) points[index + 1].first - p.first else 0f
                val dy = if (index + 1 < points.size) points[index + 1].second - p.second else 0f
                val len = hypot(dx, dy).coerceAtLeast(0.001f)
                out += p.first - dy / len * wobble + (random.nextFloat() - 0.5f) * amount * 0.4f
                out += p.second + dx / len * wobble + (random.nextFloat() - 0.5f) * amount * 0.4f
            } else {
                out += p.first
                out += p.second
            }
        }
        if (closed && out.size >= 6 && amount > 0f) {
            val keep = out.size - 4
            return out.subList(0, keep).toFloatArray()
        }
        return out.toFloatArray()
    }
}
