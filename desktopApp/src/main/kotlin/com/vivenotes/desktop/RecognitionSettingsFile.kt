package com.vivenotes.desktop

import com.vivenotes.model.ink.PAGE_BASIC
import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.workspace.ShapeRecognitionSettings
import java.io.File
import java.util.Properties

/** Device choices for Settings → Shape recognition, kept separately from the ink and shape kits. */
internal class RecognitionSettingsFile(private val file: File) {
    fun load(): ShapeRecognitionSettings {
        val values = file.readProperties() ?: return ShapeRecognitionSettings.Default
        val savedKinds = values.getProperty("enabledKinds")
        val enabledKinds = when (savedKinds) {
            null -> ShapeRecognitionSettings.Default.enabledKinds
            // A saved blank line means the user unchecked everything, not an older file.
            else -> savedKinds.split(',').mapNotNull { name ->
                ShapeKind.entries.firstOrNull { it.name == name && it.page == PAGE_BASIC }
            }.toSet()
        }
        return ShapeRecognitionSettings(
            automatic = values.getProperty("automatic")?.toBooleanStrictOrNull() ?: true,
            enabledKinds = enabledKinds,
        ).normalized()
    }

    fun save(settings: ShapeRecognitionSettings) {
        val value = settings.normalized()
        file.writePropertiesAtomically(Properties().apply {
            setProperty("automatic", value.automatic.toString())
            setProperty("enabledKinds", ShapeKind.entries.filter { it in value.enabledKinds }
                .joinToString(",") { it.name })
        }, "ViveNotes shape recognition settings")
    }
}
