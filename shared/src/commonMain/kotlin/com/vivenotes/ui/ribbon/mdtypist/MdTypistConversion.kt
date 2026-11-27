package com.vivenotes.ui.ribbon.mdtypist

import com.vivenotes.ai.LocalAiSettings
import com.vivenotes.localization.Strings
import com.vivenotes.workspace.WorkspaceState

/** A rasterized image file that must sit beside the exported source. */
data class MdTypistFigure(val name: String, val png: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is MdTypistFigure && name == other.name && png.contentEquals(other.png)

    override fun hashCode(): Int = 31 * name.hashCode() + png.contentHashCode()
}

/** What Convert produced, or why it could not produce anything. */
sealed interface MdTypistConversionResult {
    data class Success(val source: String, val figures: List<MdTypistFigure> = emptyList()) :
        MdTypistConversionResult

    data class Failure(val message: String) : MdTypistConversionResult
}

/** A source document and the image files its figure references need. */
data class MdTypistExportRequest(
    val source: String,
    val format: MdTypistFormat,
    val figures: List<MdTypistFigure>,
)

/** Writes the generated source and its figures somewhere the user chosen. */
interface MdTypistExporter {
    suspend fun export(request: MdTypistExportRequest): Result<String>
}

/** A platform without a file picker cannot export; the ribbon disables Export there. */
internal object UnsupportedMdTypistExporter : MdTypistExporter {
    override suspend fun export(request: MdTypistExportRequest): Result<String> =
        Result.failure(IllegalStateException(Strings.md_typist_export_failed))
}

/**
 * Turns the selected page into source for the chosen dialect. The platform owns the implementation
 * because reading ink, pictures and a local model server are platform concerns; the page only knows
 * this one entry point.
 */
interface MdTypistConverter {
    suspend fun convert(
        state: WorkspaceState,
        format: MdTypistFormat,
        settings: LocalAiSettings,
    ): MdTypistConversionResult
}

/** A paragraph-level item as it appears on the page, independent of its screen position. */
enum class MdTypistBlockKind { Heading1, Heading2, Heading3, Bullet, Numbered, Todo, Quote, Code, Paragraph }

data class MdTypistTextBlock(
    val kind: MdTypistBlockKind,
    val text: String,
    val checked: Boolean? = null,
)

/** A table as rows of cell text; the model can lay it out however the dialect prefers. */
data class MdTypistTable(
    val rows: List<List<String>>,
    val headerRow: Boolean = false,
    val headerColumn: Boolean = false,
)

/**
 * Everything Convert knows about the page. Ink is still OCR'd, but typed text, formulas, tables,
 * shapes, graphs and pictures are read from the document itself instead of being guessed from ink.
 */
data class MdTypistPageInput(
    val title: String,
    val blocks: List<MdTypistTextBlock> = emptyList(),
    val formulas: List<String> = emptyList(),
    val tables: List<MdTypistTable> = emptyList(),
    val shapes: List<String> = emptyList(),
    val graphs: List<String> = emptyList(),
    /** Every raster asset the source must reference: placed pictures, shapes, graphs and ink. */
    val imageNames: List<String> = emptyList(),
    val figureNames: List<String> = emptyList(),
    val handwriting: String = "",
) {
    val assetNames: List<String> get() = imageNames + figureNames

    val hasContent: Boolean get() =
        title.isNotBlank() || blocks.isNotEmpty() || formulas.isNotEmpty() || tables.isNotEmpty() ||
            shapes.isNotEmpty() || graphs.isNotEmpty() || assetNames.isNotEmpty() || handwriting.isNotBlank()
}

/** The prompt sent to the local model, the cleaning applied to its answer, and a safe fallback. */
internal object MdTypistPrompts {
    fun system(format: MdTypistFormat): String = when (format) {
        MdTypistFormat.Markdown -> """
            You are a silent formatter. Turn a notebook page into a Markdown document.
            Output ONLY the document. You are not a chat assistant: never greet, never ask a question,
            never explain what you did, never offer further help, and never repeat the title, the
            recognized handwriting or these rules as metadata. Start with the document's first
            character and stop with its last character. No surrounding code fence.
            The request carries typed text blocks, display formulas in LaTeX, tables, shapes, graphs,
            picture file names and OCR'd handwriting. Preserve every element; never omit a picture or
            a formula. Infer headings, lists and prose from the words. Keep the note's language. Put
            each picture on its own line as ![figure-1](figure-1.png), using only the exact image
            names listed in the request. Write display formulas in `$ ... $`.
        """.trimIndent()
        MdTypistFormat.Typst -> """
            You are a silent formatter. Turn a notebook page into a Typst document.
            Output ONLY valid Typst markup. You are not a chat assistant: never greet, never ask a
            question, never explain what you did, never offer further help, and never repeat the title,
            the recognized handwriting or these rules as metadata. Start with the document's first
            character and stop with its last character. No surrounding code fence.
            The request carries typed text blocks, display formulas in LaTeX, tables, shapes, graphs,
            picture file names and OCR'd handwriting. Preserve every element; never omit a picture or
            a formula. Infer headings, lists and prose from the words. Keep the note's language. Put
            each picture on its own line as #image("figure-1.png"), using only the exact image names
            listed in the request. Write display formulas in `$ ... $`.
        """.trimIndent()
    }

    /** A page prompt: all structured elements first, then the handwriting the OCR read. */
    fun prompt(input: MdTypistPageInput): String = buildString {
        appendLine("Convert this page into the document described above.")
        appendLine("--- NOTE TITLE ---")
        appendLine(input.title.ifBlank { "untitled" })
        if (input.blocks.isNotEmpty()) {
            appendLine("--- TYPED TEXT BLOCKS ---")
            input.blocks.forEach { block ->
                appendLine("${block.kind.name}: ${block.text}")
            }
        }
        if (input.formulas.isNotEmpty()) {
            appendLine("--- DISPLAY FORMULAS (LaTeX) ---")
            input.formulas.forEach(::appendLine)
        }
        if (input.tables.isNotEmpty()) {
            appendLine("--- TABLES ---")
            input.tables.forEach { table ->
                table.rows.forEach { row -> appendLine(row.joinToString(" | ")) }
                appendLine()
            }
        }
        if (input.shapes.isNotEmpty()) {
            appendLine("--- SHAPES ---")
            input.shapes.forEach(::appendLine)
        }
        if (input.graphs.isNotEmpty()) {
            appendLine("--- GRAPHS ---")
            input.graphs.forEach(::appendLine)
        }
        if (input.imageNames.isNotEmpty()) {
            appendLine("--- IMAGE FILES (use these exact names) ---")
            input.imageNames.joinToString(", ") { "`$it`" }.let(::appendLine)
        }
        if (input.figureNames.isNotEmpty()) {
            appendLine("--- FIGURE FILES (use these exact names) ---")
            input.figureNames.joinToString(", ") { "`$it`" }.let(::appendLine)
        }
        appendLine("--- RECOGNIZED HANDWRITING ---")
        appendLine(input.handwriting)
        appendLine("--- END OF NOTE ---")
    }

    /** The old ink-only call is still what the focused prompt tests specify. */
    fun prompt(pageTitle: String, noteText: String, figureNames: List<String> = emptyList()): String =
        prompt(MdTypistPageInput(title = pageTitle, figureNames = figureNames, handwriting = noteText))

    /** The only value that may reach the editor: the model's document, or a local formatted page. */
    fun document(format: MdTypistFormat, input: MdTypistPageInput, response: String): String {
        val extracted = extract(response)
        val base = extracted.ifBlank { fallback(format, input) }
        return appendMissingAssets(format, appendMissingContent(format, base, input), input.assetNames)
    }

    fun document(
        format: MdTypistFormat,
        pageTitle: String,
        noteText: String,
        response: String,
        figureNames: List<String> = emptyList(),
    ): String = document(format, MdTypistPageInput(
        title = pageTitle, figureNames = figureNames, handwriting = noteText,
    ), response)

    /**
     * Small local models ignore "no preamble" surprisingly often. Take a fenced block when there is
     * one, otherwise drop the conversational wrapper lines before and after the actual document.
     */
    fun extract(response: String): String {
        val fenced = fencedDocument(response)
        if (fenced != null) return fenced
        val lines = response.trim().lines()
        val first = lines.indexOfFirst { !isChatter(it) && it.isNotBlank() }
        if (first < 0) return ""
        var last = lines.lastIndex
        while (last >= first && (isChatter(lines[last]) || lines[last].isBlank())) last--
        if (last < first) return ""
        return lines.subList(first, last + 1).joinToString("\n").trim()
    }

    /** When the model only chats, format the whole page here so the editor never holds chat. */
    fun fallback(format: MdTypistFormat, input: MdTypistPageInput): String {
        val heading = when (format) {
            MdTypistFormat.Markdown -> "# "
            MdTypistFormat.Typst -> "= "
        }
        val title = input.title.trim()
        val sections = mutableListOf<String>()
        if (title.isNotBlank()) sections += heading + title
        if (input.blocks.isNotEmpty()) sections += renderBlocks(format, input.blocks)
        if (input.formulas.isNotEmpty()) {
            sections += input.formulas.joinToString("\n\n") { renderFormula(format, it.trim()) }
        }
        if (input.tables.isNotEmpty()) sections += input.tables.joinToString("\n\n") { renderTable(format, it) }
        if (input.shapes.isNotEmpty()) sections += input.shapes.joinToString("\n") { comment(format, "Shape: $it") }
        if (input.graphs.isNotEmpty()) sections += input.graphs.joinToString("\n") { comment(format, "Graph: $it") }
        if (input.handwriting.isNotBlank()) sections += input.handwriting.trim()
        if (input.assetNames.isNotEmpty()) {
            sections += input.assetNames.joinToString("\n") { name ->
                when (format) {
                    MdTypistFormat.Markdown -> "![$name]($name)"
                    MdTypistFormat.Typst -> "#image(\"$name\")"
                }
            }
        }
        return sections.filter { it.isNotBlank() }.joinToString("\n\n")
    }

    fun fallback(
        format: MdTypistFormat,
        pageTitle: String,
        noteText: String,
        figureNames: List<String> = emptyList(),
    ): String = fallback(format, MdTypistPageInput(
        title = pageTitle, figureNames = figureNames, handwriting = noteText,
    ))

    /**
     * A local model can return a tidy document that still forgets a table, an equation or a picture.
     * Each element we sent it is checked against the answer; anything missing is formatted locally
     * and appended, so Convert never silently loses part of the page.
     */
    private fun appendMissingContent(format: MdTypistFormat, source: String, input: MdTypistPageInput): String {
        val additions = mutableListOf<String>()
        val title = input.title.trim()
        if (title.isNotBlank() && !containsText(source, title)) {
            additions += when (format) {
                MdTypistFormat.Markdown -> "# $title"
                MdTypistFormat.Typst -> "= $title"
            }
        }
        input.blocks.forEach { block ->
            val text = block.text.trim()
            if (text.isNotBlank() && !containsText(source, text)) additions += renderBlock(format, block)
        }
        input.formulas.forEach { latex ->
            val formula = latex.trim()
            if (formula.isNotBlank() && !containsText(source, formula)) additions += renderFormula(format, formula)
        }
        input.tables.forEach { table ->
            if (table.rows.flatten().any { it.isNotBlank() && !containsText(source, it) }) {
                additions += renderTable(format, table)
            }
        }
        input.shapes.forEach { shape ->
            if (!containsText(source, shape)) additions += comment(format, "Shape: $shape")
        }
        input.graphs.forEach { graph ->
            if (!containsText(source, graph)) additions += comment(format, "Graph: $graph")
        }
        input.handwriting.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !containsText(source, it) }
            .forEach(additions::add)
        if (additions.isEmpty()) return source
        return "$source\n\n${additions.joinToString("\n\n")}"
    }

    private fun containsText(source: String, text: String): Boolean =
        source.contains(text.trim(), ignoreCase = true)

    private fun appendMissingAssets(format: MdTypistFormat, source: String, names: List<String>): String {
        val missing = names.filterNot(source::contains)
        if (missing.isEmpty()) return source
        val lines = missing.joinToString("\n") { name ->
            when (format) {
                MdTypistFormat.Markdown -> "![$name]($name)"
                MdTypistFormat.Typst -> "#image(\"$name\")"
            }
        }
        return "$source\n\n$lines"
    }

    private fun renderBlocks(format: MdTypistFormat, blocks: List<MdTypistTextBlock>): String =
        blocks.map { renderBlock(format, it) }.filter { it.isNotBlank() }.joinToString("\n")

    private fun renderBlock(format: MdTypistFormat, block: MdTypistTextBlock): String {
        val text = block.text.trim()
        if (text.isEmpty()) return ""
        return when (format) {
            MdTypistFormat.Markdown -> when (block.kind) {
                MdTypistBlockKind.Heading1 -> "# $text"
                MdTypistBlockKind.Heading2 -> "## $text"
                MdTypistBlockKind.Heading3 -> "### $text"
                MdTypistBlockKind.Bullet -> "- $text"
                MdTypistBlockKind.Numbered -> "1. $text"
                MdTypistBlockKind.Todo -> "- [${if (block.checked == true) "x" else " "}] $text"
                MdTypistBlockKind.Quote -> "> $text"
                MdTypistBlockKind.Code -> "```\n$text\n```"
                MdTypistBlockKind.Paragraph -> text
            }
            MdTypistFormat.Typst -> when (block.kind) {
                MdTypistBlockKind.Heading1 -> "= $text"
                MdTypistBlockKind.Heading2 -> "== $text"
                MdTypistBlockKind.Heading3 -> "=== $text"
                MdTypistBlockKind.Bullet -> "- $text"
                MdTypistBlockKind.Numbered -> "+ $text"
                MdTypistBlockKind.Todo -> "- [${if (block.checked == true) "x" else " "}] $text"
                MdTypistBlockKind.Quote -> "#quote[$text]"
                MdTypistBlockKind.Code -> "```\n$text\n```"
                MdTypistBlockKind.Paragraph -> text
            }
        }
    }

    private fun renderFormula(format: MdTypistFormat, latex: String): String = when (format) {
        MdTypistFormat.Markdown -> "$$\n$latex\n$$"
        MdTypistFormat.Typst -> "$ $latex $"
    }

    private fun renderTable(format: MdTypistFormat, table: MdTypistTable): String {
        if (table.rows.isEmpty()) return ""
        val width = table.rows.maxOf { it.size }
        val rows = table.rows.map { row -> (row + List(width - row.size) { "" }).map { it.trim() } }
        return when (format) {
            MdTypistFormat.Markdown -> buildString {
                rows.forEachIndexed { index, row ->
                    appendLine("| ${row.joinToString(" | ")} |")
                    if (index == 0 && table.headerRow) {
                        appendLine("| ${List(width) { "---" }.joinToString(" | ")} |")
                    }
                }
            }.trim()
            MdTypistFormat.Typst -> buildString {
                append("#table(columns: $width, ")
                append(rows.flatten().joinToString(", ") { "[$it]" })
                appendLine(")")
            }.trim()
        }
    }

    private fun comment(format: MdTypistFormat, text: String): String = when (format) {
        MdTypistFormat.Markdown -> "<!-- $text -->"
        MdTypistFormat.Typst -> "// $text"
    }

    private fun fencedDocument(text: String): String? {
        val lines = text.trim().lines()
        val open = lines.indexOfFirst { it.trim().startsWith("```") }
        if (open < 0) return null
        val close = (open + 1..lines.lastIndex).firstOrNull { lines[it].trim().startsWith("```") }
            ?: return null
        return lines.subList(open + 1, close).joinToString("\n").trim()
    }

    private fun isChatter(line: String): Boolean {
        val trimmed = line.trim()
        // Every `--- SECTION ---` is a prompt header, never part of the source document.
        if (SECTION_HEADER.matches(trimmed)) return true
        // `**Note Title:** Welcome` is a prompt echo, not source.
        val text = trimmed.trimStart('*', '#', '>', '-', ' ').lowercase().trimStart('*', '-', ' ')
        if (text.isEmpty()) return false
        if (CHATTER.any(text::startsWith)) return true
        return OFFERED_OPTION.matches(trimmed)
    }

    private val CHATTER = listOf(
        "it looks like", "it seems", "looks like", "i can ", "i'll ", "i will ",
        "sure", "certainly", "of course", "here is", "here's", "based on",
        "i understand", "would you like", "do you want", "let me know", "is there anything",
        "i hope this helps", "feel free", "as an ai", "as requested", "please note",
        "note title", "recognized handwriting", "handwriting:", "for example",
        "figure files", "picture files", "end of note", "convert this note into",
        "typed text blocks", "display formulas", "tables:", "shapes:", "graphs:",
        "formatted note",
    )
    private val OFFERED_OPTION = Regex("""^\d+\.\s+\*\*.*""")
    private val SECTION_HEADER = Regex("""^---\s+.*\s+---$""")
}

/** A converter for platforms that have no OCR or local model server yet. */
internal object UnsupportedMdTypistConverter : MdTypistConverter {
    override suspend fun convert(
        state: WorkspaceState,
        format: MdTypistFormat,
        settings: LocalAiSettings,
    ): MdTypistConversionResult = MdTypistConversionResult.Failure(Strings.md_typist_ai_unavailable)
}
