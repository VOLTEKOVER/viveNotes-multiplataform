package com.vivenotes.desktop

import com.vivenotes.localization.Strings
import com.vivenotes.ui.ribbon.mdtypist.MdTypistExportRequest
import com.vivenotes.ui.ribbon.mdtypist.MdTypistExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Writes the generated source and the rasterized figures beside it, using the native save dialog. */
internal class DesktopMdTypistExporter(
    private val chooseDestination: suspend (String, List<String>, String?) -> File? =
        { title, extensions, name ->
            withContext(FlatpakFileDialogs.dispatcher) {
                FlatpakFileDialogs.choose(title, extensions, name)
            }
        },
) : MdTypistExporter {
    override suspend fun export(request: MdTypistExportRequest): Result<String> = runCatching {
        val destination = checkNotNull(chooseDestination(
            Strings.md_typist_export,
            listOf(request.format.fileExtension),
            "note.${request.format.fileExtension}",
        )) { "No destination chosen" }
        withContext(Dispatchers.IO) {
            destination.parentFile?.mkdirs()
            destination.writeText(request.source)
            request.figures.forEach { figure ->
                File(destination.parentFile, figure.name).writeBytes(figure.png)
            }
        }
        destination.absolutePath
    }
}
