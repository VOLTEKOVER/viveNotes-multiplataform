package com.vivenotes.ui.ribbon.mdtypist

import com.vivenotes.ai.InkTextRegion
import com.vivenotes.ai.renderDrawInk
import com.vivenotes.byteink.kit.PageStroke
import org.jetbrains.skia.EncodedImageFormat

/** A stroke in page coordinates, used to decide which ink belongs to a figure rather than text. */
internal data class FigureBox(
    val id: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** Strokes the handwriting pass did not read as words are candidate figure ink. */
internal fun figureBoxes(strokes: List<PageStroke>, textStrokeIds: Set<String>): List<FigureBox> =
    strokes.mapNotNull { stroke ->
        if (stroke.id in textStrokeIds) return@mapNotNull null
        val bounds = stroke.pageBounds ?: return@mapNotNull null
        FigureBox(stroke.id, bounds.left, bounds.top, bounds.right, bounds.bottom)
    }.filter { box ->
        (box.right - box.left) >= MIN_FIGURE_SIZE && (box.bottom - box.top) >= MIN_FIGURE_SIZE
    }

/**
 * Connected components of nearby boxes. Expanding each box by half the gap before testing overlap
 * keeps a diagram whose parts were drawn with pauses in one figure, without swallowing the writing
 * on the next line.
 */
internal fun groupFigureBoxes(boxes: List<FigureBox>, gap: Float = FIGURE_GAP): List<List<FigureBox>> {
    val parent = IntArray(boxes.size) { it }
    fun root(index: Int): Int {
        var current = index
        while (parent[current] != current) current = parent[current]
        var cursor = index
        while (parent[cursor] != cursor) {
            val next = parent[cursor]
            parent[cursor] = current
            cursor = next
        }
        return current
    }
    fun join(a: Int, b: Int) {
        val ra = root(a)
        val rb = root(b)
        if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb)
    }

    val half = gap / 2f
    for (first in boxes.indices) {
        val a = boxes[first]
        for (second in first + 1 until boxes.size) {
            val b = boxes[second]
            val overlaps = a.left - half <= b.right + half && b.left - half <= a.right + half &&
                a.top - half <= b.bottom + half && b.top - half <= a.bottom + half
            if (overlaps) join(first, second)
        }
    }
    return boxes.indices.groupBy(::root).values.toList()
        .map { indices -> indices.map { boxes[it] } }
        .sortedBy { group -> group.minOf { it.top } }
}

/** Turns one cluster of leftover ink into a PNG, in the same black-on-white style as the note. */
internal fun renderFigurePng(strokes: List<PageStroke>, stemPx: Float): ByteArray? =
    renderDrawInk(strokes, stemPx).use { image ->
        image.encodeToData(EncodedImageFormat.PNG)?.use { data -> data.bytes }
    }

/** Rasterizes every detected figure and gives it the name the model was told to reference. */
internal fun detectFigures(
    strokes: List<PageStroke>,
    recognized: List<InkTextRegion>,
    stemPx: Float = FIGURE_STEM_PX,
): List<MdTypistFigure> {
    val textStrokeIds = recognized.flatMapTo(HashSet()) { it.strokeIds }
    val byId = strokes.associateBy { it.id }
    return groupFigureBoxes(figureBoxes(strokes, textStrokeIds)).mapIndexedNotNull { index, group ->
        val png = renderFigurePng(group.mapNotNull { byId[it.id] }, stemPx) ?: return@mapIndexedNotNull null
        MdTypistFigure("figure-${index + 1}.png", png)
    }
}

private const val MIN_FIGURE_SIZE = 12f
private const val FIGURE_GAP = 16f
private const val FIGURE_STEM_PX = 1.5f
