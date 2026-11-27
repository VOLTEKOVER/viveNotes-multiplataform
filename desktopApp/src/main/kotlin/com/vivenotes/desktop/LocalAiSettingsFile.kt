package com.vivenotes.desktop

import com.vivenotes.ai.LocalAiProtocol
import com.vivenotes.ai.LocalAiSettings
import java.io.File
import java.util.Properties

/** The MD-Typist page's local model connection, beside the app's other device preferences. */
internal class LocalAiSettingsFile(private val file: File) {
    fun load(): LocalAiSettings {
        val values = file.readProperties() ?: return LocalAiSettings()
        return LocalAiSettings(
            endpoint = values.getProperty(ENDPOINT)?.takeIf { it.isNotBlank() }
                ?: LocalAiSettings().endpoint,
            model = values.getProperty(MODEL)?.takeIf { it.isNotBlank() }
                ?: LocalAiSettings().model,
            protocol = LocalAiProtocol.fromName(values.getProperty(PROTOCOL)),
        )
    }

    fun save(settings: LocalAiSettings) {
        file.writePropertiesAtomically(Properties().apply {
            setProperty(ENDPOINT, settings.endpoint)
            setProperty(MODEL, settings.model)
            setProperty(PROTOCOL, settings.protocol.name)
        }, "ViveNotes local AI connection")
    }

    private companion object {
        const val ENDPOINT = "endpoint"
        const val MODEL = "model"
        const val PROTOCOL = "protocol"
    }
}
