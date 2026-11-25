package com.vivenotes.model.ink

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.min

/**
 * Deterministic recognition of a hand-drawn ink stroke.
 *
 * No learning and no state: a stroke is compared with the same geometry [trace] draws, using a
 * robust symmetric nearest-distance measure so a missing edge hurts as much as an extra one. Both
 * sides are densified first, so a sparse polyline cannot hide an edge between two distant points,
 * and a high percentile each way tolerates the occasional shaky sample without ignoring whole
 * edges. Distances are measured in page units and divided by the stroke's diagonal, which makes
 * the tolerances independent of how big the shape was drawn.
 *
 * Recognition is deliberately unwilling: an open scribble or a stroke too short to read returns
 * `null`. A wrong shape committed to the page is worse than no shape at all.
 *
 * Only mother shapes are compared, never every variant: a rounded rectangle and a diamond are read
 * as `Rectangle`, a drawn arrow as `Line`, and a right triangle as `Triangle`. The user picks the
 * exact variant from the style menu that follows recognition. 3D solids are drawn from the shape
 * toolkit but are never recognized.
 */
object ShapeFit {

    /** Below this the stroke is a dot or a tick, not a shape. Matches StraightLineFit's floor. */
    private const val MIN_STROKE_DIAGONAL = 24f

    /** A closed shape needs a real second dimension; a flat line must not fit a degenerate box. */
    private const val MIN_SPAN_FRACTION = 0.08f

    /** The ends of a hand-drawn closed shape need not meet exactly. */
    private const val CLOSURE_FRACTION = 0.20f

    /** Largest error, as a fraction of the diagonal, still accepted as a match. */
    private const val MAX_MATCH_SCORE = 0.09f

    /** An arrow's shaft must dominate its bounding box; a short stub with wings is not an arrow. */
    private const val ARROW_CHORD_FRACTION = 0.55f

    /**
     * A real hand stroke has an occasional stray point. Dropping the worst few percent keeps one
     * wobble from rejecting a whole shape, while the template side stays strict so extra edges on
     * a solid (a pyramid's inner lines) cannot hide inside the allowance.
     */
    private const val USER_DISTANCE_PERCENTILE = 0.92f
    private const val TEMPLATE_DISTANCE_PERCENTILE = 0.97f

    /**
     * A hand-drawn polygon need not match a fixed template exactly. A coarse simplification of the
     * stroke recovers its corners; the right count is what separates a triangle, a quadrilateral and
     * a higher polygon, regardless of how the shape is rotated or where the pen started.
     */
    private const val CORNER_FRACTION = 0.04f
    private const val MIN_POLYGON_AREA_FRACTION = 0.01f
    /** A wireframe solid retraces interior edges; its stroke is much longer than its outline. */
    private const val MAX_POLYGON_STROKE_RATIO = 1.25f

    private const val MAX_SAMPLED_POINTS = 256

    /**
     * One representative per recognized family. Variants (rounded/diamond, arrow, right triangle,
     * …) are chosen by the user after recognition, not guessed here. Solids are not recognized.
     */
    private val CLOSED_KINDS = listOf(
        ShapeKind.Rectangle, ShapeKind.Ellipse,
        ShapeKind.Triangle, ShapeKind.Pentagon, ShapeKind.Hexagon,
    )

    /** Open flat shapes: they have two dimensions but no closure requirement. */
    private val OPEN_KINDS = listOf(ShapeKind.L, ShapeKind.Curve)

    /**
     * [enabledKinds] filters recognition before ambiguity is decided, so a disabled kind cannot win
     * and hide an enabled runner-up. An empty set recognizes nothing.
     */
    fun classify(
        points: FloatArray,
        enabledKinds: Set<ShapeKind> = ShapeKind.onPage(PAGE_BASIC).toSet(),
    ): ShapeCandidate? {
        if (enabledKinds.isEmpty()) return null
        if (points.size < 6 || points.size % 2 != 0 || points.any { !it.isFinite() }) return null

        var left = Float.POSITIVE_INFINITY
        var top = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        var bottom = Float.NEGATIVE_INFINITY
        for (index in points.indices step 2) {
            val x = points[index]
            val y = points[index + 1]
            if (x < left) left = x
            if (y < top) top = y
            if (x > right) right = x
            if (y > bottom) bottom = y
        }

        val width = right - left
        val height = bottom - top
        val diagonal = hypot(width, height)
        if (diagonal < MIN_STROKE_DIAGONAL) return null

        val sampled = sample(points, MAX_SAMPLED_POINTS)
        val matches = ArrayList<Match>(4)

        // Paths: the straight line is the mother shape. A drawn arrow is recognized as a line with
        // the stroke's direction; the arrow is offered as a style variant after recognition.
        // A shaft with a head is not a StraightLineFit (the wings backtrack sideways), so it needs
        // its own template. The template decides *that* it is an arrow; the committed shape is the
        // mother Line, never the Arrow variant.
        val arrowEnds = arrowEnds(points)
        val tailX = arrowEnds[0]
        val tailY = arrowEnds[1]
        val tipX = arrowEnds[2]
        val tipY = arrowEnds[3]
        val arrowChord = hypot(tipX - tailX, tipY - tailY)
        if (arrowChord >= ARROW_CHORD_FRACTION * diagonal) {
            val template = trace(ShapeKind.Arrow, tailX, tailY, tipX, tipY).solid
            val score = score(sampled, template, diagonal)
            if (score <= MAX_MATCH_SCORE) {
                matches += Match(
                    ShapeCandidate(
                        ShapeKind.Line, left, top, right, bottom,
                        tailX, tailY, tipX, tipY, confidence(score),
                    ),
                    score,
                )
            }
        }
        StraightLineFit.of(points)?.let { line ->
            val template = trace(ShapeKind.Line, line.startX, line.startY, line.endX, line.endY).solid
            val score = score(sampled, template, diagonal)
            if (score <= MAX_MATCH_SCORE) {
                val (tailX, tailY, tipX, tipY) = directedEnds(points, line)
                val l = minOf(left, line.startX, line.endX)
                val t = minOf(top, line.startY, line.endY)
                val r = maxOf(right, line.startX, line.endX)
                val b = maxOf(bottom, line.startY, line.endY)
                matches += Match(
                    ShapeCandidate(
                        ShapeKind.Line, l, t, r, b,
                        tailX, tailY, tipX, tipY, confidence(score),
                    ),
                    score,
                )
            }
        }

        val gap = hypot(points[0] - points[points.size - 2], points[1] - points[points.size - 1])
        val closed = gap <= CLOSURE_FRACTION * diagonal
        val twoDimensional = min(width, height) >= MIN_SPAN_FRACTION * diagonal
        val firstX = points[0]
        val firstY = points[1]
        val lastX = points[points.size - 2]
        val lastY = points[points.size - 1]

        // Closed flat shapes normalise inside trace(), so one box fits every template. Requiring
        // closure here is what stops a stray open stroke from being guessed as a polygon.
        if (closed && twoDimensional) {
            addTemplates(CLOSED_KINDS, left, top, right, bottom, sampled, diagonal,
                firstX, firstY, lastX, lastY, matches)
            addDrawnTriangle(sampled, diagonal, left, top, right, bottom,
                firstX, firstY, lastX, lastY, matches)
            // A diamond or a rounded rectangle has the same four corners as an axis-aligned box.
            // Reading them as the quadrilateral mother lets the style menu offer the exact variant.
            addDrawnQuadrilateral(sampled, diagonal, left, top, right, bottom,
                firstX, firstY, lastX, lastY, matches)
        }

        // Open flat shapes need two dimensions but deliberately do not close: the L and the curve.
        if (!closed && twoDimensional) {
            addTemplates(OPEN_KINDS, left, top, right, bottom, sampled, diagonal,
                firstX, firstY, lastX, lastY, matches)
        }

        val allowed = matches.filter { it.candidate.kind in enabledKinds }
        if (allowed.isEmpty()) return null
        val flat = allowed.minBy { it.score }
        // A solid is never committed, even when its visible edges happen to look like a flat mother:
        // if the stroke fits a solid at least as closely as its best flat reading, it stays ink.
        val solidScore = ShapeKind.onPage(PAGE_SOLID).minOf { kind ->
            score(sampled, trace(kind, left, top, right, bottom).solid, diagonal)
        }
        if (solidScore <= flat.score) return null
        return flat.candidate
    }

    private data class Match(val candidate: ShapeCandidate, val score: Float)

    private fun addTemplates(
        kinds: List<ShapeKind>,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        sampled: FloatArray,
        diagonal: Float,
        firstX: Float,
        firstY: Float,
        lastX: Float,
        lastY: Float,
        matches: ArrayList<Match>,
    ) {
        kinds.forEach { kind ->
            // Some shapes can be drawn in more than one orientation. Score against every plausible
            // variant and keep the best, so an upside-down triangle or a right angle in the other
            // corner still reads as the shape the user drew.
            val best = orientationVariants(kind, left, top, right, bottom)
                .map { template -> template to score(sampled, template, diagonal) }
                .minByOrNull { it.second } ?: return@forEach
            val (template, score) = best
            // A wireframe solid retraces edges inside its outline. Its ink is far longer than the
            // closed figure it happens to resemble, so no solid can borrow a flat mother's template.
            if (kind in CLOSED_KINDS &&
                strokeLength(sampled) > MAX_POLYGON_STROKE_RATIO * polylineLength(template)) return@forEach
            if (score <= MAX_MATCH_SCORE) {
                matches += Match(
                    ShapeCandidate(
                        kind, left, top, right, bottom,
                        firstX, firstY, lastX, lastY, confidence(score),
                    ),
                    score,
                )
            }
        }
    }

    /**
     * Recognises a triangle drawn from arbitrary vertices. Fixed templates only cover the
     * isosceles and axis-aligned right triangles; a scalene or rotated one is still a triangle, so
     * its own three corners are recovered from the stroke and used as the template.
     */
    private fun addDrawnTriangle(
        sampled: FloatArray,
        diagonal: Float,
        left: Float, top: Float, right: Float, bottom: Float,
        firstX: Float, firstY: Float, lastX: Float, lastY: Float,
        matches: ArrayList<Match>,
    ) {
        val corners = triangleCorners(sampled, diagonal) ?: return
        val outline = floatArrayOf(
            corners[0].first, corners[0].second,
            corners[1].first, corners[1].second,
            corners[2].first, corners[2].second,
            corners[0].first, corners[0].second,
        )
        val score = score(sampled, listOf(outline), diagonal)
        if (score > MAX_MATCH_SCORE) return
        matches += Match(
            ShapeCandidate(
                ShapeKind.Triangle, left, top, right, bottom,
                firstX, firstY, lastX, lastY, confidence(score),
                vertices = corners,
            ),
            score,
        )
    }

    /**
     * Recognises a quadrilateral drawn from arbitrary vertices. A diamond and a rounded rectangle
     * are both variants of the `Rectangle` family: their own four corners are the template, so the
     * user can pick the exact variant from the style menu after recognition.
     */
    private fun addDrawnQuadrilateral(
        sampled: FloatArray,
        diagonal: Float,
        left: Float, top: Float, right: Float, bottom: Float,
        firstX: Float, firstY: Float, lastX: Float, lastY: Float,
        matches: ArrayList<Match>,
    ) {
        val corners = polygonCorners(sampled, diagonal, 4) ?: return
        val outline = FloatArray(corners.size * 2 + 2).also { path ->
            corners.forEachIndexed { index, corner ->
                path[index * 2] = corner.first
                path[index * 2 + 1] = corner.second
            }
            path[path.size - 2] = corners.first().first
            path[path.size - 1] = corners.first().second
        }
        val score = score(sampled, listOf(outline), diagonal)
        if (score > MAX_MATCH_SCORE) return
        matches += Match(
            ShapeCandidate(
                ShapeKind.Rectangle, left, top, right, bottom,
                firstX, firstY, lastX, lastY, confidence(score),
            ),
            score,
        )
    }

    /** The three corners of a closed stroke, or null when it has more or fewer. */
    private fun triangleCorners(points: FloatArray, diagonal: Float): List<Pair<Float, Float>>? =
        polygonCorners(points, diagonal, 3)

    /** The [expected] corners of a closed stroke, or null for any other polygon. */
    private fun polygonCorners(
        points: FloatArray,
        diagonal: Float,
        expected: Int,
    ): List<Pair<Float, Float>>? {
        val pairs = (points.indices step 2).map { points[it] to points[it + 1] }
        val simplified = simplify(pairs, CORNER_FRACTION * diagonal)
        if (simplified.size < expected + 1) return null
        val gap = hypot(
            simplified.first().first - simplified.last().first,
            simplified.first().second - simplified.last().second,
        )
        if (gap > CLOSURE_FRACTION * diagonal) return null
        val corners = simplified.dropLast(1)
        if (corners.size != expected) return null
        var twiceArea = 0f
        for (index in corners.indices) {
            val a = corners[index]
            val b = corners[(index + 1) % corners.size]
            twiceArea += a.first * b.second - b.first * a.second
        }
        if (abs(twiceArea) < MIN_POLYGON_AREA_FRACTION * diagonal * diagonal) return null
        var outlineLength = 0f
        for (index in corners.indices) {
            val a = corners[index]
            val b = corners[(index + 1) % corners.size]
            outlineLength += hypot(b.first - a.first, b.second - a.second)
        }
        if (strokeLength(points) > MAX_POLYGON_STROKE_RATIO * outlineLength) return null
        return corners
    }

    /** Ramer-Douglas-Peucker on an open stroke; rounded corners collapse into their corner point. */
    private fun simplify(
        points: List<Pair<Float, Float>>,
        epsilon: Float,
    ): List<Pair<Float, Float>> {
        if (points.size <= 2) return points
        val first = points.first()
        val last = points.last()
        var farthest = -1f
        var index = 0
        for (position in 1 until points.size - 1) {
            val point = points[position]
            val distance = distanceToSegment(
                point.first, point.second,
                first.first, first.second, last.first, last.second,
            )
            if (distance > farthest) {
                farthest = distance
                index = position
            }
        }
        if (farthest <= epsilon) return listOf(first, last)
        return simplify(points.take(index + 1), epsilon).dropLast(1) +
            simplify(points.drop(index), epsilon)
    }

    /** The template orientations a person might realistically draw for [kind]. */
    private fun orientationVariants(
        kind: ShapeKind,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): List<List<FloatArray>> {
        val base = trace(kind, left, top, right, bottom).solid
        return when (kind) {
            // A triangle's apex can point any of the four ways; a person starts wherever feels natural.
            ShapeKind.Triangle -> listOf(
                base,
                base.mirrorY(left, right, top, bottom),
                base.rotated(left, right, top, bottom, quarterTurns = 1),
                base.rotated(left, right, top, bottom, quarterTurns = 3),
            )
            // The corner can sit in any of the four corners too.
            ShapeKind.L -> listOf(
                base,
                base.mirrorX(left, right, top, bottom),
                base.mirrorY(left, right, top, bottom),
                base.mirrorX(left, right, top, bottom).mirrorY(left, right, top, bottom),
            )
            // The seed curve is an arch. Its bowl and both sideways turns are the same gesture
            // rotated, so a hook-like stroke gets a fair comparison too.
            ShapeKind.Curve -> listOf(
                base,
                base.mirrorY(left, right, top, bottom),
                base.rotated(left, right, top, bottom, quarterTurns = 1),
                base.rotated(left, right, top, bottom, quarterTurns = 3),
            )
            else -> listOf(base)
        }
    }

    private fun List<FloatArray>.mirrorX(left: Float, right: Float, top: Float, bottom: Float) =
        map { polyline -> polyline.mirror(left, right, top, bottom, flipX = true) }

    private fun List<FloatArray>.mirrorY(left: Float, right: Float, top: Float, bottom: Float) =
        map { polyline -> polyline.mirror(left, right, top, bottom, flipX = false) }

    private fun FloatArray.mirror(
        left: Float, right: Float, top: Float, bottom: Float, flipX: Boolean,
    ): FloatArray {
        val out = copyOf()
        for (index in indices step 2) {
            out[index] = if (flipX) left + right - this[index] else this[index]
            out[index + 1] = if (flipX) this[index + 1] else top + bottom - this[index + 1]
        }
        return out
    }

    /**
     * Turns the outline inside its own bounding box, in 90-degree steps. Normalised coordinates
     * keep the template inside [left..right] x [top..bottom] even when the box is not square.
     */
    private fun List<FloatArray>.rotated(
        left: Float, right: Float, top: Float, bottom: Float, quarterTurns: Int,
    ): List<FloatArray> {
        val width = (right - left).coerceAtLeast(0.001f)
        val height = (bottom - top).coerceAtLeast(0.001f)
        return map { path ->
            val out = path.copyOf()
            for (index in path.indices step 2) {
                val u = (path[index] - left) / width
                val v = (path[index + 1] - top) / height
                val (turnedU, turnedV) = when (quarterTurns and 3) {
                    1 -> 1f - v to u
                    2 -> 1f - u to 1f - v
                    3 -> v to 1f - u
                    else -> u to v
                }
                out[index] = left + turnedU * width
                out[index + 1] = top + turnedV * height
            }
            out
        }
    }

    private fun confidence(score: Float): Float = (1f - score / MAX_MATCH_SCORE).coerceIn(0f, 1f)

    /** Total travel along a polyline, used to tell a plain outline from a retraced wireframe. */
    private fun strokeLength(points: FloatArray): Float {
        var length = 0f
        for (index in 2 until points.size step 2) {
            length += hypot(points[index] - points[index - 2], points[index + 1] - points[index - 1])
        }
        return length
    }

    private fun polylineLength(paths: List<FloatArray>): Float {
        var total = 0f
        paths.forEach { total += strokeLength(it) }
        return total
    }

    /**
     * The tail is the start of the stroke and the tip is the furthest point from it. A shaft is
     * drawn first and is longer than either head wing, so this recovers the arrow's direction
     * without guessing which end the head sits on.
     */
    private fun arrowEnds(points: FloatArray): FloatArray {
        val tailX = points[0]
        val tailY = points[1]
        var tipX = tailX
        var tipY = tailY
        var furthest = -1f
        for (index in 2 until points.size step 2) {
            val distance = hypot(points[index] - tailX, points[index + 1] - tailY)
            if (distance > furthest) {
                furthest = distance
                tipX = points[index]
                tipY = points[index + 1]
            }
        }
        return floatArrayOf(tailX, tailY, tipX, tipY)
    }

    /**
     * Keeps the direction the stroke was drawn in, projected onto the snapped line. A line
     * recognized from an arrow-shaped stroke therefore points the same way; choosing the arrow
     * style later keeps that direction.
     */
    private fun directedEnds(points: FloatArray, line: StraightLine): FloatArray {
        val dx = line.endX - line.startX
        val dy = line.endY - line.startY
        val lengthSq = dx * dx + dy * dy
        if (lengthSq <= 0f) return floatArrayOf(line.startX, line.startY, line.endX, line.endY)
        val lastX = points[points.size - 2]
        val lastY = points[points.size - 1]
        val along = (lastX - line.startX) * dx + (lastY - line.startY) * dy
        return if (along >= lengthSq / 2f) {
            floatArrayOf(line.startX, line.startY, line.endX, line.endY)
        } else {
            floatArrayOf(line.endX, line.endY, line.startX, line.startY)
        }
    }

    /** Inserts points along each segment so no edge is represented by fewer than a few samples. */
    private fun densify(points: FloatArray): FloatArray {
        if (points.size < 4) return points
        val out = ArrayList<Float>(points.size * 2)
        for (index in 0 until points.size - 2 step 2) {
            val ax = points[index]
            val ay = points[index + 1]
            val bx = points[index + 2]
            val by = points[index + 3]
            val length = hypot(bx - ax, by - ay)
            val steps = maxOf(1, (ceil(length / DENSIFY_SPACING)).toInt())
            repeat(steps) { step ->
                val t = step / steps.toFloat()
                out += ax + (bx - ax) * t
                out += ay + (by - ay) * t
            }
        }
        out += points[points.size - 2]
        out += points[points.size - 1]
        return out.toFloatArray()
    }

    /** Evenly spaced points, keeping both ends. Bounds the cost of matching a very long stroke. */
    private fun sample(points: FloatArray, limit: Int): FloatArray {
        val count = points.size / 2
        if (count <= limit) return points
        val out = FloatArray(limit * 2)
        repeat(limit) { index ->
            val source = (index.toLong() * (count - 1) / (limit - 1)).toInt() * 2
            out[index * 2] = points[source]
            out[index * 2 + 1] = points[source + 1]
        }
        return out
    }

    /** Rough spacing of synthetic points laid along every edge before comparison, in page units. */
    private const val DENSIFY_SPACING = 8f

    /**
     * Robust symmetric nearest distance between two sets of polylines, normalised by [diagonal].
     *
     * Plain Hausdorff took the single worst point on either side, so one shaky sample rejected an
     * otherwise perfect shape. Taking a high percentile on each side keeps the comparison honest:
     * ink that strays from the template is still penalised, and a solid's extra edges (template
     * points without ink) cost just as much as stray ink.
     *
     * Both sides are densified first so a sparse polyline cannot hide a missing edge: five corner
     * points would otherwise let a right triangle's diagonal vanish between two rectangle corners.
     */
    private fun score(user: FloatArray, templates: List<FloatArray>, diagonal: Float): Float {
        val denseUser = sample(densify(user), MAX_SAMPLED_POINTS)
        val traced = templates.map { sample(densify(it), MAX_SAMPLED_POINTS) }
        val userDistances = ArrayList<Float>(denseUser.size / 2)
        for (index in denseUser.indices step 2) {
            val x = denseUser[index]
            val y = denseUser[index + 1]
            var nearest = Float.POSITIVE_INFINITY
            traced.forEach { polyline ->
                val distance = distanceToPolyline(x, y, polyline)
                if (distance < nearest) nearest = distance
            }
            userDistances += nearest
        }
        val templateDistances = ArrayList<Float>(256)
        traced.forEach { polyline ->
            for (index in polyline.indices step 2) {
                templateDistances += distanceToPolyline(polyline[index], polyline[index + 1], denseUser)
            }
        }
        return maxOf(
            percentile(userDistances, USER_DISTANCE_PERCENTILE),
            percentile(templateDistances, TEMPLATE_DISTANCE_PERCENTILE),
        ) / diagonal
    }

    /** The value below which [fraction] of [values] fall; near 1.0 means "almost the worst". */
    private fun percentile(values: List<Float>, fraction: Float): Float {
        if (values.isEmpty()) return Float.POSITIVE_INFINITY
        val sorted = values.sorted()
        val index = ((sorted.size - 1) * fraction).toInt()
        return sorted[index]
    }

    /** Distance from a point to the nearest segment of one polyline, or to its only point. */
    private fun distanceToPolyline(x: Float, y: Float, path: FloatArray): Float {
        if (path.size < 4) {
            return if (path.size >= 2) hypot(x - path[0], y - path[1]) else Float.POSITIVE_INFINITY
        }
        var nearest = Float.POSITIVE_INFINITY
        for (index in 0 until path.size - 2 step 2) {
            val distance = distanceToSegment(
                x, y,
                path[index], path[index + 1], path[index + 2], path[index + 3],
            )
            if (distance < nearest) nearest = distance
        }
        return nearest
    }

    private fun distanceToSegment(
        x: Float,
        y: Float,
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
    ): Float {
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared <= 0f) return hypot(x - ax, y - ay)
        val along = ((x - ax) * dx + (y - ay) * dy) / lengthSquared
        val t = along.coerceIn(0f, 1f)
        return hypot(x - (ax + dx * t), y - (ay + dy * t))
    }
}
