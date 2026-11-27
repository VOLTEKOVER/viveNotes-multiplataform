package com.vivenotes.desktop

import com.vivenotes.ai.LocalAiProtocol
import com.vivenotes.ai.LocalAiSettings
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** The MD-Typist connection survives a restart, including the chosen wire protocol. */
class LocalAiSettingsFileTest {
    @Test
    fun defaultsWhenTheFileIsAbsentAndRoundTripWhenItExists() {
        val directory = Files.createTempDirectory("local-ai-settings").toFile()
        try {
            val file = File(directory, "config/local-ai.properties")
            assertEquals(LocalAiSettings(), LocalAiSettingsFile(file).load())

            val chosen = LocalAiSettings(
                endpoint = "http://192.168.1.20:1234",
                model = "hermes3",
                protocol = LocalAiProtocol.OpenAiCompatible,
            )
            LocalAiSettingsFile(file).save(chosen)
            assertEquals(chosen, LocalAiSettingsFile(file).load())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun anUnknownProtocolFallsBackToOllama() {
        val file = Files.createTempFile("local-ai-settings", ".properties").toFile()
        try {
            file.writeText("endpoint=http://localhost:11434\nmodel=hermes3\nprotocol=Mystery\n")
            assertEquals(LocalAiProtocol.Ollama, LocalAiSettingsFile(file).load().protocol)
        } finally {
            file.delete()
        }
    }
}
