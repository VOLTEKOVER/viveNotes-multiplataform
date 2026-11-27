package com.vivenotes.ai

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Talks to a model server running on this machine or LAN. Ollama's native chat endpoint and the
 * OpenAI-compatible endpoint used by LM Studio, llama.cpp and Hermes servers differ only in their
 * path and a few field names, so one client covers both.
 */
class LocalAiHttpClient(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).build(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val timeout: Duration = Duration.ofSeconds(120),
) : LocalAiClient {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun complete(request: LocalAiRequest): Result<String> = withContext(dispatcher) {
        runCatching {
            val protocol = request.settings.protocol
            val body = when (protocol) {
                LocalAiProtocol.Ollama -> ollamaBody(request)
                LocalAiProtocol.OpenAiCompatible -> openAiBody(request)
            }
            val http = HttpRequest.newBuilder(chatUri(request.settings.endpoint, protocol))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val response = client.send(http, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                error("HTTP ${response.statusCode()}: ${response.body().take(160)}")
            }
            val tree = json.parseToJsonElement(response.body())
            val content = when (protocol) {
                LocalAiProtocol.Ollama -> tree.jsonObject["message"]?.jsonObject?.get("content")?.jsonPrimitive?.content
                LocalAiProtocol.OpenAiCompatible -> tree.jsonObject["choices"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
            }
            requireNotNull(content?.takeIf { it.isNotBlank() }) { "The model returned no text" }
            content
        }
    }

    private fun ollamaBody(request: LocalAiRequest): String = buildJsonObject {
        put("model", request.settings.model)
        put("stream", false)
        if (request.system.isNotBlank()) put("system", request.system)
        put("messages", buildJsonArray {
            add(buildJsonObject {
                put("role", "user")
                put("content", request.prompt)
            })
        })
    }.toString()

    private fun openAiBody(request: LocalAiRequest): String = buildJsonObject {
        put("model", request.settings.model)
        put("stream", false)
        put("messages", buildJsonArray {
            if (request.system.isNotBlank()) {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", request.system)
                })
            }
            add(buildJsonObject {
                put("role", "user")
                put("content", request.prompt)
            })
        })
    }.toString()

    companion object {
        /** Accepts a bare host, a host with a port, or an already complete endpoint. */
        internal fun chatUri(endpoint: String, protocol: LocalAiProtocol): URI {
            val base = endpoint.trim().trimEnd('/')
            require(base.isNotBlank()) { "Endpoint is empty" }
            val path = when (protocol) {
                LocalAiProtocol.Ollama -> when {
                    base.endsWith("/api/chat") -> base
                    base.endsWith("/v1") -> base.removeSuffix("/v1") + "/api/chat"
                    else -> "$base/api/chat"
                }
                LocalAiProtocol.OpenAiCompatible -> when {
                    base.endsWith("/v1/chat/completions") -> base
                    base.endsWith("/chat/completions") -> base
                    base.endsWith("/v1") -> "$base/chat/completions"
                    else -> "$base/v1/chat/completions"
                }
            }
            return URI.create(path)
        }
    }
}
