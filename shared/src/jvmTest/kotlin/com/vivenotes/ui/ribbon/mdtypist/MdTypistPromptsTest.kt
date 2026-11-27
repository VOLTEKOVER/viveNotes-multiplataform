package com.vivenotes.ui.ribbon.mdtypist

import com.vivenotes.localization.Strings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The contract with the local model: what we ask for, and what we accept back. */
class MdTypistPromptsTest {
    @Test
    fun markdownPromptAsksForMarkdownAndFigurePlaceholders() {
        val system = MdTypistPrompts.system(MdTypistFormat.Markdown)
        assertTrue(system.contains("Markdown", ignoreCase = true))
        assertTrue(system.contains("figure-1.png"))
        assertFalse(system.contains("#image", ignoreCase = true))
    }

    @Test
    fun typstPromptAsksForTypstAndImageCalls() {
        val system = MdTypistPrompts.system(MdTypistFormat.Typst)
        assertTrue(system.contains("Typst", ignoreCase = true))
        assertTrue(system.contains("#image(\"figure-1.png\")"))
        assertFalse(system.contains("figure-1.png]"))
    }

    @Test
    fun promptCarriesTheTitleAndEveryRecognizedLine() {
        val prompt = MdTypistPrompts.prompt("Oxidation", "first line\nsecond line")
        assertTrue(prompt.contains("Oxidation"))
        assertTrue(prompt.contains("first line"))
        assertTrue(prompt.contains("second line"))
    }

    @Test
    fun promptNamesTheDetectedFigures() {
        val prompt = MdTypistPrompts.prompt("Graphs", "a graph", listOf("figure-1.png", "figure-2.png"))
        assertTrue(prompt.contains("figure-1.png"))
        assertTrue(prompt.contains("figure-2.png"))
        assertTrue(prompt.contains("exact names"))
    }

    @Test
    fun extractionStripsAFencedAnswerButKeepsTheDocument() {
        val answer = "```markdown\n# Title\n\n- one\n```"
        assertEquals("# Title\n\n- one", MdTypistPrompts.extract(answer))
    }

    @Test
    fun extractionAcceptsPlainTextAndRejectsAnEmptyFence() {
        assertEquals("# Title", MdTypistPrompts.extract("  # Title  "))
        assertEquals("", MdTypistPrompts.extract("```markdown\n```"))
    }

    @Test
    fun aChattyAnswerBecomesOnlyTheFormattedNote() {
        val chatty = """
            It looks like you are transcribing or categorizing a note.

            **Note Title:** Welcome
            **Recognized handwriting:** CIAO

            Would you like me to do anything with this information? For example, I can:
            1. **Translate** the word (it is Italian for "Hello" or "Goodbye").
            2. **Format** it into a specific list or database.
            3. **Continue** transcribing more notes if you provide images or text.
        """.trimIndent()

        assertEquals("", MdTypistPrompts.extract(chatty))
        assertEquals(
            "# Welcome\n\nCIAO",
            MdTypistPrompts.document(MdTypistFormat.Markdown, "Welcome", "CIAO", chatty),
        )
        assertEquals(
            "= Welcome\n\nCIAO",
            MdTypistPrompts.document(MdTypistFormat.Typst, "Welcome", "CIAO", chatty),
        )
    }

    @Test
    fun promptEchoAboveARealDocumentIsStripped() {
        val answer = "**Note Title:** Welcome\n**Recognized handwriting:** CIAO\n\n# Welcome\n\nCIAO"
        assertEquals("# Welcome\n\nCIAO", MdTypistPrompts.extract(answer))
    }

    @Test
    fun promptSectionHeadersAreNotMistakenForSource() {
        val answer = "--- SHAPES ---\n--- GRAPHS ---\n# Welcome\n\nCIAO"
        assertEquals("# Welcome\n\nCIAO", MdTypistPrompts.extract(answer))
    }

    @Test
    fun fallbackStillReferencesDetectedFigures() {
        val markdown = MdTypistPrompts.fallback(
            MdTypistFormat.Markdown, "Graphs", "a graph", listOf("figure-1.png"),
        )
        val typst = MdTypistPrompts.fallback(
            MdTypistFormat.Typst, "Graphs", "a graph", listOf("figure-1.png"),
        )
        assertEquals("# Graphs\n\na graph\n\n![figure-1.png](figure-1.png)", markdown)
        assertEquals("= Graphs\n\na graph\n\n#image(\"figure-1.png\")", typst)
    }

    @Test
    fun formatLabelsComeFromResources() {
        assertEquals(Strings.md_typist_markdown, MdTypistFormat.Markdown.label)
        assertEquals(Strings.md_typist_typst, MdTypistFormat.Typst.label)
    }
}
