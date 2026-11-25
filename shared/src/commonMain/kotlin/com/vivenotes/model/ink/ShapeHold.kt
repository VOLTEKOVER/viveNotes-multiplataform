package com.vivenotes.model.ink

import kotlin.math.hypot

/**
 * Collects the points of a freehand stroke until it is spent, then offers them to [ShapeFit].
 *
 * The point cap, the slop/hysteresis anchor and the spent flag mirror [StraightenHold]: a gesture
 * that moves less than [slop] since the last anchor adds a point without claiming a visual update,
 * which keeps the recognition input identical to the ink actually laid down.
 */
internal class ShapeHold(
    private val slop: Float,
    private val enabledKinds: Set<ShapeKind> = ShapeKind.onPage(PAGE_BASIC).toSet(),
) {
    private val points = ArrayList<Float>()
    private var anchor: RulerPoint? = null
    private var overflowed = false
    var spent = false
        private set

    fun observe(x: Float, y: Float): Boolean {
        if (spent || overflowed) return false
        if (points.size >= 8192) { overflowed = true; return false }
        points.add(x); points.add(y)
        val a = anchor
        return if (a == null || hypot(x - a.x, y - a.y) > slop) {
            anchor = RulerPoint(x, y); true
        } else false
    }

    fun candidate(): ShapeCandidate? =
        if (overflowed || spent) null else ShapeFit.classify(points.toFloatArray(), enabledKinds)

    fun spend() { spent = true; points.clear() }
}
