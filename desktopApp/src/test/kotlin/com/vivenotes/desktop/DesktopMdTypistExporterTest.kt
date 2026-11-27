package com.vivenotes.desktop

import com.vivenotes.ui.ribbon.mdtypist.MdTypistExportRequest
import com.vivenotes.ui.ribbon.mdtypist.MdTypistFigure
import com.vivenotes.ui.ribbon.mdtypist.MdTypistFormat
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The file the user picks gets both the source and the images its figure references need. */
class DesktopMdTypistExporterTest {
    @Test
    fun writesSourceAndFiguresNextToTheChosenFile() = runBlocking {
        val directory = Files.createTempDirectory("md-typist-export").toFile()
        try {
            val destination = File(directory, "exported/note.typ")
            var suggestedName: String? = null
            var extensions = emptyList<String>()
            val exporter = DesktopMdTypistExporter { _, chosenExtensions, name ->
                extensions = chosenExtensions
                suggestedName = name
                destination
            }
            val source = "#set text(size: 12pt)\n#image(\"figure-1.png\")"
            val figure = MdTypistFigure("figure-1.png", byteArrayOf(1, 2, 3))

            val result = exporter.export(MdTypistExportRequest(source, MdTypistFormat.Typst, listOf(figure)))

            assertTrue(result.isSuccess)
            assertEquals(destination.absolutePath, result.getOrThrow())
            assertEquals(listOf("typ"), extensions)
            assertEquals("note.typ", suggestedName)
            assertEquals(source, destination.readText())
            assertEquals(figure.png.toList(), File(directory, "exported/figure-1.png").readBytes().toList())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun cancellingTheDialogReportsAFailure() = runBlocking {
        val exporter = DesktopMdTypistExporter { _, _, _ -> null }
        val result = exporter.export(MdTypistExportRequest("# note", MdTypistFormat.Markdown, emptyList()))
        assertTrue(result.isFailure)
    }
}
