package com.unciv.app.desktop.llm

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue
import com.badlogic.gdx.utils.JsonWriter
import com.unciv.logic.llm.client.LlmClientException
import com.unciv.logic.llm.client.LlmRequest
import com.unciv.logic.llm.client.LlmResponse
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.math.max

internal class DesktopLlmHttp(
    timeoutSeconds: Int,
    private val maxRetries: Int
) {
    private val timeout = Duration.ofSeconds(max(1, timeoutSeconds).toLong())
    private val client = HttpClient.newBuilder()
        .connectTimeout(timeout)
        .build()

    fun postJson(uri: URI, headers: Map<String, String>, body: String): HttpResult {
        val startedAt = System.nanoTime()
        var attempt = 0
        var lastError: Exception? = null
        while (attempt <= maxRetries) {
            try {
                val requestBuilder = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                headers.forEach { (name, value) -> requestBuilder.header(name, value) }
                val response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() in 200..299) {
                    return HttpResult(response.statusCode(), response.body(), elapsedMs(startedAt))
                }
                if (!isRetryable(response.statusCode()) || attempt == maxRetries) {
                    throw LlmClientException("LLM HTTP ${response.statusCode()}: ${response.body().take(500)}")
                }
            } catch (e: IOException) {
                lastError = e
                if (attempt == maxRetries) throw LlmClientException("LLM HTTP request failed: ${e.message}", e)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw LlmClientException("LLM HTTP request interrupted", e)
            }
            sleepBeforeRetry(attempt)
            attempt++
        }
        throw LlmClientException("LLM HTTP request failed", lastError)
    }

    private fun isRetryable(statusCode: Int) = statusCode == 429 || statusCode >= 500

    private fun sleepBeforeRetry(attempt: Int) {
        try {
            Thread.sleep(250L * (attempt + 1))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw LlmClientException("LLM HTTP retry interrupted", e)
        }
    }

    private fun elapsedMs(startedAt: Long) = (System.nanoTime() - startedAt) / 1_000_000
}

internal data class HttpResult(
    val statusCode: Int,
    val body: String,
    val latencyMs: Long
)

internal fun parseJson(text: String): JsonValue =
    try {
        JsonReader().parse(text)
    } catch (e: Exception) {
        throw LlmClientException("LLM response was not valid JSON: ${e.message}", e)
    }

internal fun JsonValue?.asJsonObjectStringOrNull(): String? =
    this?.toJson(JsonWriter.OutputType.json)

internal fun escapeJson(value: String): String = buildString(value.length + 16) {
    for (char in value) {
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> {
                if (char.code < 0x20) append("\\u%04x".format(char.code))
                else append(char)
            }
        }
    }
}

internal fun jsonString(value: String) = "\"${escapeJson(value)}\""

internal fun firstUserMessage(request: LlmRequest): String =
    request.messages.firstOrNull()?.content.orEmpty()

internal fun parseOpenAiUsage(root: JsonValue): Triple<Int, Int, Int> {
    val usage = root.get("usage") ?: return Triple(0, 0, 0)
    val input = usage.getInt("prompt_tokens", 0)
    val output = usage.getInt("completion_tokens", 0)
    val cached = usage.get("prompt_tokens_details")?.getInt("cached_tokens", 0) ?: 0
    return Triple(input, output, cached)
}

internal fun parseAnthropicUsage(root: JsonValue): Triple<Int, Int, Int> {
    val usage = root.get("usage") ?: return Triple(0, 0, 0)
    return Triple(
        usage.getInt("input_tokens", 0),
        usage.getInt("output_tokens", 0),
        usage.getInt("cache_read_input_tokens", 0)
    )
}

internal fun withLatency(response: LlmResponse, latencyMs: Long) = response.copy(latencyMs = latencyMs)
