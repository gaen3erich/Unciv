package com.unciv.app.desktop.llm

import com.unciv.logic.llm.client.LlmClient
import com.unciv.logic.llm.client.LlmClientException
import com.unciv.logic.llm.client.LlmRequest
import com.unciv.logic.llm.client.LlmResponse
import java.net.URI

class AnthropicLlmClient(
    private val model: String,
    private val apiKey: String,
    baseUrl: String?,
    timeoutSeconds: Int,
    maxRetries: Int,
    private val extraHeaders: Map<String, String> = emptyMap()
) : LlmClient {
    override val identifier = "anthropic:$model"
    private val endpoint = URI((baseUrl ?: DEFAULT_BASE_URL).trimEnd('/') + "/v1/messages")
    private val http = DesktopLlmHttp(timeoutSeconds, maxRetries)

    override fun send(request: LlmRequest): LlmResponse {
        val result = http.postJson(
            endpoint,
            buildMap {
                put("content-type", "application/json")
                put("x-api-key", apiKey)
                put("anthropic-version", "2023-06-01")
                putAll(extraHeaders)
            },
            requestBody(request)
        )
        return withLatency(parseResponse(result.body), result.latencyMs)
    }

    private fun requestBody(request: LlmRequest): String = buildString {
        append("{")
        append("\"model\":").append(jsonString(model)).append(",")
        append("\"system\":").append(jsonString(request.systemPrompt)).append(",")
        append("\"messages\":[{\"role\":\"user\",\"content\":").append(jsonString(firstUserMessage(request))).append("}],")
        append("\"max_tokens\":").append(request.maxTokens).append(",")
        append("\"temperature\":").append(request.temperature)
        request.toolSchema?.let {
            append(",\"tools\":[").append(it).append("]")
            append(",\"tool_choice\":{\"type\":\"tool\",\"name\":\"pick_actions\"}")
        }
        append("}")
    }

    private fun parseResponse(body: String): LlmResponse {
        val root = parseJson(body)
        val content = root.get("content")
        val rawText = buildString {
            if (content != null) {
                for (item in content) {
                    if (item.getString("type", "") == "text") append(item.getString("text", ""))
                }
            }
        }
        val toolCall = content?.firstOrNull {
            it.getString("type", "") == "tool_use" && it.getString("name", "") == "pick_actions"
        }?.get("input")?.asJsonObjectStringOrNull()
        val (inputTokens, outputTokens, cacheReadTokens) = parseAnthropicUsage(root)
        return LlmResponse(
            rawText = rawText,
            toolCallJson = toolCall,
            stopReason = root.getString("stop_reason", "unknown"),
            usageInputTokens = inputTokens,
            usageOutputTokens = outputTokens,
            usageCacheReadTokens = cacheReadTokens
        )
    }

    companion object {
        private const val DEFAULT_BASE_URL = "https://api.anthropic.com"
    }
}
