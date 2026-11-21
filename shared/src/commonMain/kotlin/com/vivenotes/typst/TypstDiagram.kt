package com.vivenotes.typst

import com.vivenotes.model.DocumentJson
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable

/** One editable colour in a CeTZ diagram. [token] is the source text this slot replaces. */
@Serializable
data class TypstColorBinding(val token: String, val argb: Int, val used: Boolean = true)

/** The source and colours kept inside a rendered diagram's SVG attachment. */
@Serializable
data class TypstDiagramSource(val source: String, val colors: List<TypstColorBinding>)

/** CeTZ version bundled with the app; imports resolve offline from packaged archives. */
internal const val CetzVersion = "0.4.2"

/** Width of the temporary Typst page a diagram is laid out on, in points. */
internal const val DiagramWidthPt = 320

/** Upper bound for hand-written `viveC<n>` references, so a stray index cannot grow the slot list. */
private const val MaxColorSlots = 16

/** The palette offered for diagram elements, led by the app's usual accent colours. */
val DefaultTypstColors = listOf(
    0xFF3B82F6.toInt(),
    0xFFE53935.toInt(),
    0xFF00C853.toInt(),
    0xFFFFB300.toInt(),
    0xFF8E24AA.toInt(),
    0xFF00BCD4.toInt(),
)

/** Built-in Typst colours, with the values Typst 0.15 gives them. */
private val NamedColors = mapOf(
    "black" to 0xFF000000.toInt(),
    "gray" to 0xFFAAAAAA.toInt(),
    "silver" to 0xFFDDDDDD.toInt(),
    "white" to 0xFFFFFFFF.toInt(),
    "navy" to 0xFF001F3F.toInt(),
    "blue" to 0xFF0074D9.toInt(),
    "aqua" to 0xFF7FDBFF.toInt(),
    "teal" to 0xFF39CCCC.toInt(),
    "eastern" to 0xFF239DAD.toInt(),
    "purple" to 0xFFB10DC9.toInt(),
    "fuchsia" to 0xFFF012BE.toInt(),
    "maroon" to 0xFF85144B.toInt(),
    "red" to 0xFFFF4136.toInt(),
    "orange" to 0xFFFF851B.toInt(),
    "yellow" to 0xFFFFDC00.toInt(),
    "olive" to 0xFF3D9970.toInt(),
    "green" to 0xFF2ECC40.toInt(),
    "lime" to 0xFF01FF70.toInt(),
)

private val namedColorPattern = NamedColors.keys.sortedByDescending { it.length }
    .joinToString("|") { Regex.escape(it) }

/** Whole colour expressions only: strings and comments are masked before matching. */
private val colorTokenPattern = Regex(
    """(?<![\w])(?:rgb\s*\(\s*(?:"[^"]*"|\d{1,3}\s*,\s*\d{1,3}\s*,\s*\d{1,3}(?:\s*,\s*(?:\d+(?:\.\d+)?%?))?)\s*\)|(?:color\.)?(?:$namedColorPattern)|viveC\d+)(?![\w])"""
)

/** A sample that uses editable colours, so the panel is useful before anything is pasted. */
val SampleTypstSource = """
    #cetz.canvas({
      import cetz.draw: *
      line((0, 0), (2, 2), stroke: 2pt + red)
      circle((1, 1), radius: 0.6, fill: blue)
      rect((0, 0), (2, 2), stroke: 1pt + gray)
    })
""".trimIndent()

/**
 * CeTZ source kept with the colours its literals were bound to. Literal colours are found in code,
 * not in strings or comments, and each distinct one becomes a palette slot. Unused slots are kept so
 * [SampleTypstSource] and hand-written `viveC0` references can be recoloured too.
 */
object TypstDiagramSourceCodec {

    fun bindings(source: String, existing: List<TypstColorBinding> = emptyList()): List<TypstColorBinding> {
        val masked = maskNonCode(source)
        val tokens = colorTokenPattern.findAll(masked).map { source.substring(it.range) }.toSet()
        val bySlot = mutableMapOf<Int, TypstColorBinding>()
        fun bind(token: String, slot: Int) {
            bySlot[slot] = TypstColorBinding(token,
                existing.firstOrNull { it.token == token }?.argb ?: initialColor(token), used = true)
        }
        fun nextFree(from: Int = 0): Int {
            var slot = from
            while (bySlot.containsKey(slot)) slot++
            return slot
        }
        // Hand-written viveC<n> references keep their requested slot; literals fill the gaps.
        tokens.filter { it.startsWith("viveC") }.forEach { token ->
            val requested = token.removePrefix("viveC").toIntOrNull()
            if (requested != null && requested in 0 until MaxColorSlots) bind(token, nextFree(requested))
        }
        tokens.filterNot { it.startsWith("viveC") }.forEach { token -> bind(token, nextFree()) }
        val count = maxOf(DefaultTypstColors.size, (bySlot.keys.maxOrNull() ?: -1) + 1)
        return List(count) { index ->
            bySlot[index] ?: TypstColorBinding("viveC$index",
                DefaultTypstColors.getOrElse(index) { DefaultTypstColors[0] }, used = false)
        }
    }

    /** Builds the full Typst document: offline CeTZ import, colour definitions and a tight page. */
    fun renderSource(source: String, colors: List<TypstColorBinding>): String {
        val masked = maskNonCode(source)
        val ranges = colorTokenPattern.findAll(masked).map { match ->
            val token = source.substring(match.range)
            val index = colors.indexOfFirst { it.token == token }
            match.range to index
        }.filter { it.second >= 0 }.sortedByDescending { it.first.first }
        val rewritten = StringBuilder(source)
        ranges.forEach { (range, index) ->
            rewritten.replace(range.first, range.last + 1, "viveC$index")
        }
        return buildString {
            appendLine("#import \"@preview/cetz:$CetzVersion\"")
            colors.forEachIndexed { index, color ->
                appendLine("#let viveC$index = rgb(\"${hex(color.argb)}\")")
            }
            appendLine("#set page(width: ${DiagramWidthPt}pt, height: auto, margin: 0pt, fill: none)")
            appendLine()
            append(rewritten)
        }
    }

    /** Adds the retained source to SVG bytes, without changing what a vector painter draws. */
    @OptIn(ExperimentalEncodingApi::class)
    fun embed(svg: String, diagram: TypstDiagramSource): String {
        val payload = Base64.Default.encode(DocumentJson.encodeToString(diagram).encodeToByteArray())
        return svg.replaceFirst("</svg>", "<!--vive-typst:$payload-->\n</svg>")
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun read(svg: String): TypstDiagramSource? {
        val encoded = metadataPattern.find(svg)?.groupValues?.get(1) ?: return null
        val json = runCatching { Base64.Default.decode(encoded).decodeToString() }.getOrNull() ?: return null
        return runCatching { DocumentJson.decodeFromString<TypstDiagramSource>(json) }.getOrNull()
    }

    internal fun hex(argb: Int): String = buildString {
        append('#')
        append(((argb ushr 16) and 0xff).toString(16).padStart(2, '0'))
        append(((argb ushr 8) and 0xff).toString(16).padStart(2, '0'))
        append((argb and 0xff).toString(16).padStart(2, '0'))
        if ((argb ushr 24) and 0xff != 0xff) {
            append(((argb ushr 24) and 0xff).toString(16).padStart(2, '0'))
        }
    }

    private val metadataPattern = Regex("""<!--vive-typst:([A-Za-z0-9+/=]+)-->""")

    private fun initialColor(token: String): Int {
        NamedColors[token.removePrefix("color.")]?.let { return it }
        val rgb = Regex("""(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})""").find(token)
        if (rgb != null) {
            val red = rgb.groupValues[1].toInt().coerceAtMost(255)
            val green = rgb.groupValues[2].toInt().coerceAtMost(255)
            val blue = rgb.groupValues[3].toInt().coerceAtMost(255)
            return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
        }
        val hex = Regex("""#[0-9a-fA-F]{3,8}""").find(token)?.value?.removePrefix("#") ?: return 0xFF3B82F6.toInt()
        val expanded = if (hex.length <= 4) hex.map { "$it$it" }.joinToString("") else hex
        fun part(start: Int) = expanded.substring(start, start + 2).toInt(16)
        return when (expanded.length) {
            6 -> (0xFF shl 24) or (part(0) shl 16) or (part(2) shl 8) or part(4)
            8 -> (part(6) shl 24) or (part(0) shl 16) or (part(2) shl 8) or part(4)
            else -> 0xFF3B82F6.toInt()
        }
    }

    /** Blanks string contents and comments while keeping offsets, quotes and `rgb("…")` syntax. */
    private fun maskNonCode(text: String): String {
        val masked = text.toCharArray()
        var index = 0
        fun blank(from: Int, until: Int) {
            for (i in from until until.coerceAtMost(masked.size)) masked[i] = ' '
        }
        while (index < masked.size) {
            when {
                masked[index] == '/' && index + 1 < masked.size && masked[index + 1] == '/' -> {
                    val start = index
                    while (index < masked.size && masked[index] != '\n') index++
                    blank(start, index)
                }
                masked[index] == '/' && index + 1 < masked.size && masked[index + 1] == '*' -> {
                    val start = index
                    index += 2
                    while (index + 1 < masked.size && !(masked[index] == '*' && masked[index + 1] == '/')) index++
                    index = (index + 2).coerceAtMost(masked.size)
                    blank(start, index)
                }
                masked[index] == '"' -> {
                    val start = index++
                    while (index < masked.size) {
                        if (masked[index] == '\\') index += 2
                        else if (masked[index] == '"') { index++; break }
                        else index++
                    }
                    blank(start + 1, (index - 1).coerceAtLeast(start + 1))
                }
                else -> index++
            }
        }
        return String(masked)
    }
}
