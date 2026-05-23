package com.unciv.logic.llm.policy

import com.unciv.logic.llm.LlmLegalAction
import com.unciv.logic.llm.LlmObservation
import com.unciv.logic.llm.LlmPolicy
import com.unciv.logic.llm.client.LlmClient
import com.unciv.logic.llm.client.LlmClientException
import com.unciv.logic.llm.client.LlmResponse
import com.unciv.logic.llm.prompt.LlmPromptBuilder
import com.unciv.logic.llm.prompt.LlmResponseParser
import java.security.MessageDigest

class RealLlmPolicy(
    private val client: LlmClient,
    private val promptBuilder: LlmPromptBuilder = LlmPromptBuilder(),
    private val parser: LlmResponseParser = LlmResponseParser(),
    private val fallback: LlmPolicyFallback = LlmPolicyFallback.EndTurnOnly,
    private val includePrompts: Boolean = false
) : LlmPolicy, LlmTraceablePolicy {
    private var lastTrace: LlmCallTrace? = null

    override fun chooseActions(observation: LlmObservation): List<LlmLegalAction> {
        val request = promptBuilder.build(observation, observation.currentPlayerCivName)
        val userPrompt = request.messages.firstOrNull()?.content.orEmpty()
        val response = try {
            client.send(request)
        } catch (e: LlmClientException) {
            lastTrace = traceForClientError(request.systemPrompt, userPrompt, request.kind.name, e)
            return fallback.onClientError(observation, e)
        }

        return when (val result = parser.parse(response, observation)) {
            is LlmResponseParser.ParseResult.Success -> {
                lastTrace = traceForResponse(request.systemPrompt, userPrompt, request.kind.name, response, true, result.parsePath.name, null, result.rationale)
                result.actions
            }
            is LlmResponseParser.ParseResult.Failure -> {
                lastTrace = traceForResponse(request.systemPrompt, userPrompt, request.kind.name, response, false, result.parsePath.name, "parse_failure", null)
                fallback.onParseFailure(observation, response, result.reason)
            }
        }
    }

    override fun consumeLastCallTrace(): LlmCallTrace? {
        val trace = lastTrace
        lastTrace = null
        return trace
    }

    private fun traceForClientError(systemPrompt: String, userPrompt: String, kind: String, e: LlmClientException) =
        LlmCallTrace(
            clientId = client.identifier,
            requestKind = kind,
            systemPromptHash = sha256Prefix(systemPrompt),
            userPromptHash = sha256Prefix(userPrompt),
            inputTokens = 0,
            outputTokens = 0,
            cacheReadTokens = 0,
            latencyMs = 0,
            stopReason = e::class.simpleName ?: "client_error",
            parsedSuccessfully = false,
            parsePath = "FAILED",
            fallbackUsed = "client_error",
            systemPrompt = if (includePrompts) systemPrompt else null,
            userPrompt = if (includePrompts) userPrompt else null
        )

    private fun traceForResponse(
        systemPrompt: String,
        userPrompt: String,
        kind: String,
        response: LlmResponse,
        parsedSuccessfully: Boolean,
        parsePath: String,
        fallbackUsed: String?,
        rationale: String?
    ) = LlmCallTrace(
        clientId = client.identifier,
        requestKind = kind,
        systemPromptHash = sha256Prefix(systemPrompt),
        userPromptHash = sha256Prefix(userPrompt),
        inputTokens = response.usageInputTokens,
        outputTokens = response.usageOutputTokens,
        cacheReadTokens = response.usageCacheReadTokens,
        latencyMs = response.latencyMs,
        stopReason = response.stopReason,
        parsedSuccessfully = parsedSuccessfully,
        parsePath = parsePath,
        fallbackUsed = fallbackUsed,
        rationale = rationale,
        systemPrompt = if (includePrompts) systemPrompt else null,
        userPrompt = if (includePrompts) userPrompt else null
    )

    private fun sha256Prefix(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }
}
