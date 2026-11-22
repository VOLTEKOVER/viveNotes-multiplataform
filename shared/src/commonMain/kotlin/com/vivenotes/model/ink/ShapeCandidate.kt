package com.vivenotes.model.ink

/**
 * The result of recognising a hand-drawn ink stroke as a traceable shape.
 *
 * The bounds always cover the whole stroke (not just the snapped endpoints), and `start`/`end`
 * keep the direction the stroke was drawn in — which is the only thing that tells a left-pointing
 * arrow from a right-pointing one.
 *
 * [vertices] carries the drawn corners when recognition found a shape the fixed `kind` template
 * cannot express, such as a scalene or rotated triangle. The inserted outline then keeps those
 * corners instead of snapping back to the template's box.
 */
data class ShapeCandidate(
    val kind: ShapeKind,
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val startX: Float, val startY: Float, val endX: Float, val endY: Float,
    val confidence: Float,
    val vertices: List<Pair<Float, Float>>? = null,
)
