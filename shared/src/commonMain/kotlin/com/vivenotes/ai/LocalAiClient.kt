package com.vivenotes.ai

import com.vivenotes.localization.Strings

/** The two chat protocols a locally running model server may expose. */
enum class LocalAiProtocol {
    Ollama,
    OpenAiCompatible;

    val label: String get() = when (this) {
        Ollama -> Strings.md_typist_ollama
        OpenAiCompatible -> Strings.md_typist_openai_compatible
    }

    companion object {
        fun fromName(value: String?): LocalAiProtocol =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: Ollama
    }
}

/** Where a local model server listens and which model answers. Carried per request so a change in
 * the API model dialog takes effect on the next Convert without rebuilding the client. */
data class LocalAiSettings(
    val endpoint: String = "http://127.0.0.1:11434",
    val model: String = "hermes3",
    val protocol: LocalAiProtocol = LocalAiProtocol.Ollama,
)

/** One chat completion request, independent of the wire protocol used to send it. */
data class LocalAiRequest(
    val settings: LocalAiSettings,
    val system: String,
    val prompt: String,
)

/**
 * A chat client for a model running on this machine or LAN. No cloud account, key or network call
 * happens until [complete] is asked for something.
 */
interface LocalAiClient {
    suspend fun complete(request: LocalAiRequest): Result<String>
}

/** The platform has nowhere to send a request; Convert reports it as unavailable. */
object UnsupportedLocalAiClient : LocalAiClient {
    override suspend fun complete(request: LocalAiRequest): Result<String> =
        Result.failure(IllegalStateException(Strings.md_typist_ai_unavailable))
}
