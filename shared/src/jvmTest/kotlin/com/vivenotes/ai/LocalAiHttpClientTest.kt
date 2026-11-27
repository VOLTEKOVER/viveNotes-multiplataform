package com.vivenotes.ai

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The two wire protocols against a real loopback server: request shape and reply parsing. */
class LocalAiHttpClientTest {
    private var server: HttpServer? = null

    @AfterTest fun stopServer() {
        server?.stop(0)
        server = null
    }

    private fun serve(
        path: String,
        reply: String,
        status: Int = 200,
        protocol: LocalAiProtocol = LocalAiProtocol.Ollama,
        onBody: (String) -> Unit = {},
    ): LocalAiSettings {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext(path) { exchange ->
            val requestBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            onBody(requestBody)
            val bytes = reply.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        http.start()
        server = http
        return LocalAiSettings(
            endpoint = "http://127.0.0.1:${http.address.port}",
            model = "hermes3",
            protocol = protocol,
        )
    }

    @Test
    fun ollamaPostsToItsNativeChatEndpointAndReadsTheMessage() = runBlocking {
        var sent = ""
        val settings = serve("/api/chat", """{"message":{"content":"# Hello"}}""") { sent = it }

        val reply = LocalAiHttpClient().complete(LocalAiRequest(settings, "be brief", "read this"))

        assertTrue(reply.isSuccess)
        assertEquals("# Hello", reply.getOrThrow())
        val request = Json.parseToJsonElement(sent).jsonObject
        assertEquals("hermes3", request.getValue("model").jsonPrimitive.content)
        assertEquals(false, request.getValue("stream").jsonPrimitive.boolean)
        assertEquals("be brief", request.getValue("system").jsonPrimitive.content)
        val message = request.getValue("messages").jsonArray.single().jsonObject
        assertEquals("user", message.getValue("role").jsonPrimitive.content)
        assertEquals("read this", message.getValue("content").jsonPrimitive.content)
    }

    @Test
    fun openAiCompatiblePostsAStandardMessageListAndReadsTheFirstChoice() = runBlocking {
        var sent = ""
        val settings = serve("/v1/chat/completions",
            """{"choices":[{"message":{"content":"# Typ"}}]}""",
            protocol = LocalAiProtocol.OpenAiCompatible,
        ) { sent = it }

        val reply = LocalAiHttpClient().complete(LocalAiRequest(settings, "be brief", "read this"))

        assertTrue(reply.isSuccess)
        assertEquals("# Typ", reply.getOrThrow())
        val messages = Json.parseToJsonElement(sent).jsonObject.getValue("messages").jsonArray
        assertEquals(2, messages.size)
        assertEquals("system", messages[0].jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals("be brief", messages[0].jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals("user", messages[1].jsonObject.getValue("role").jsonPrimitive.content)
    }

    @Test
    fun aFailedStatusIsReportedAsAFailure() = runBlocking {
        val settings = serve("/api/chat", """{"error":"model missing"}""", status = 404)

        val reply = LocalAiHttpClient().complete(LocalAiRequest(settings, "", "read this"))

        assertTrue(reply.isFailure)
        assertTrue(reply.exceptionOrNull()?.message?.contains("404") == true)
    }

    @Test
    fun endpointsAreNormalizedForBothProtocols() {
        val ollama = "http://127.0.0.1:11434"
        assertEquals("$ollama/api/chat", LocalAiHttpClient.chatUri(ollama, LocalAiProtocol.Ollama).toString())
        assertEquals("http://x/api/chat",
            LocalAiHttpClient.chatUri("http://x/v1/", LocalAiProtocol.Ollama).toString())
        assertEquals("$ollama/v1/chat/completions",
            LocalAiHttpClient.chatUri(ollama, LocalAiProtocol.OpenAiCompatible).toString())
        assertEquals("http://x/v1/chat/completions",
            LocalAiHttpClient.chatUri("http://x/v1", LocalAiProtocol.OpenAiCompatible).toString())
    }
}
