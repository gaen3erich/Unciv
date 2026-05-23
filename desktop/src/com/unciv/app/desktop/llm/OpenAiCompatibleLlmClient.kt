package com.unciv.app.desktop.llm

import com.unciv.logic.llm.client.LlmClient
import com.unciv.logic.llm.client.LlmRequest
import com.unciv.logic.llm.client.LlmResponse
import java.net.URI

class OpenAiCompatibleLlmClient(
    private val model: String,
    private val apiKey: String,
    baseUrl: String,
    timeoutSeconds: Int,
    maxRetries: Int,
    private val extraHeaders: Map<String, String> = emptyMap()
) : LlmClient {
    override val identifier = "openai-compat:$model"
    private val endpoint = URI(baseUrl.trimEnd('/') + "/chat/completions")
    private val http = DesktopLlmHttp(timeoutSeconds, maxRetries)

    override fun send(request: LlmRequest): LlmResponse {
        val result = http.postJson(
            endpoint,
            buildMap {
                put("content-type", "application/json")
                put("authorization", "Bearer $apiKey")
                putAll(extraHeaders)
            },
            requestBody(request)
        )
        return withLatency(parseResponse(result.body), result.latencyMs)
    }

    private fun requestBody(request: LlmRequest): String = buildString {
        append("{")
        append("\"model\":").append(jsonString(model)).append(",")
        append("\"messages\":[")
        append("{\"role\":\"system\",\"content\":").append(jsonString(request.systemPrompt)).append("},")
        append("{\"role\":\"user\",\"content\":").append(jsonString(firstUserMessage(request))).append("}")
        append("],")
        append("\"max_tokens\":").append(request.maxTokens).append(",")
        append("\"temperature\":").append(request.temperature)
        request.toolSchema?.let {
            append(",\"tools\":[{\"type\":\"function\",\"function\":").append(it).append("}]")
            append(",\"tool_choice\":{\"type\":\"function\",\"function\":{\"name\":\"pick_actions\"}}")
        }
        append("}")
    }

    private fun parseResponse(body: String): LlmResponse {
        val root = parseJson(body)
        val choice = root.get("choices")?.get(0)
        val message = choice?.get("message")
        val toolCallJson = message
            ?.get("tool_calls")
            ?.firstOrNull()
            ?.get("function")
            ?.getString("arguments", null)
        val (inputTokens, outputTokens, cacheReadTokens) = parseOpenAiUsage(root)
        return LlmResponse(
            rawText = message?.get("content")?.let { if (it.isNull) "" else it.asString() } ?: "",
            toolCallJson = toolCallJson,
            stopReason = choice?.getString("finish_reason", "unknown") ?: "unknown",
            usageInputTokens = inputTokens,
            usageOutputTokens = outputTokens,
            usageCacheReadTokens = cacheReadTokens
        )
    }
}
