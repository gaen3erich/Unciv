package com.unciv.logic.llm.policy

import com.unciv.logic.llm.LlmLegalAction
import com.unciv.logic.llm.LlmObservation
import com.unciv.logic.llm.LlmPolicy
import com.unciv.logic.llm.client.LlmClient
import com.unciv.logic.llm.client.LlmClientException
import com.unciv.logic.llm.client.LlmRequest
import com.unciv.logic.llm.client.LlmResponse
import com.unciv.logic.llm.prompt.LlmActionAliases
import com.unciv.logic.llm.prompt.LlmPromptBuilder
import com.unciv.logic.llm.prompt.LlmPromptMemory
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

    /**
     * Carried between steps, per game and civ: the observation of the last successful decision,
     * its actions and the model's notes.
     */
    private val memories = HashMap<String, LlmPromptMemory>()

    override fun chooseActions(observation: LlmObservation): List<LlmLegalAction> {
        val memoryKey = "${observation.gameId}/${observation.currentPlayerCivId}"
        val currentMemory = memories[memoryKey]
        val request = promptBuilder.build(observation, observation.currentPlayerCivName, currentMemory)
        val userPrompt = request.messages.firstOrNull()?.content.orEmpty()
        val first = try {
            attempt(request, observation)
        } catch (e: LlmClientException) {
            lastTrace = traceForClientError(request.systemPrompt, userPrompt, request.kind.name, e)
            return fallback.onClientError(observation, e)
        }

        // Nothing has been applied yet, so the legal actions are still the ones the model was shown
        val firstRejection = rejectionOf(first.result)
        var retryError: String? = null
        val second = if (firstRejection == null) null else try {
            attempt(withCorrection(request, firstRejection), observation)
        } catch (e: LlmClientException) {
            retryError = e.message ?: e::class.simpleName
            null
        }

        // A usable first answer beats a retry that produced nothing
        val final = when {
            second?.result is LlmResponseParser.ParseResult.Success -> second
            first.result is LlmResponseParser.ParseResult.Success -> first
            else -> second ?: first
        }
        lastTrace = traceFor(request, userPrompt, final, listOfNotNull(first, second), firstRejection, retryError)

        return when (val result = final.result) {
            is LlmResponseParser.ParseResult.Success -> {
                val aliases = LlmActionAliases.of(observation)
                memories[memoryKey] = LlmPromptMemory(
                    previousObservation = observation,
                    lastDecision = result.actions.map { aliases.aliasOf(it) },
                    notes = result.notes ?: currentMemory?.notes
                )
                result.actions
            }
            is LlmResponseParser.ParseResult.Failure -> fallback.onParseFailure(observation, final.response, result.reason)
        }
    }

    private fun attempt(request: LlmRequest, observation: LlmObservation): Attempt {
        val response = client.send(request)
        return Attempt(response, parser.parse(response, observation))
    }

    private fun rejectionOf(result: LlmResponseParser.ParseResult): String? = when (result) {
        is LlmResponseParser.ParseResult.Success ->
            if (result.droppedActionIds.isEmpty()) null else LlmResponseParser.illegalIdsReason(result.droppedActionIds)
        is LlmResponseParser.ParseResult.Failure -> result.reason
    }

    private fun withCorrection(request: LlmRequest, rejection: String): LlmRequest {
        val correction = "\n\nYour previous answer was rejected and none of it was applied: $rejection. " +
            "Answer again using only ids from the Legal actions list above."
        val userMessage = request.messages.first()
        return request.copy(messages = listOf(userMessage.copy(content = userMessage.content + correction)) + request.messages.drop(1))
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
            userPromptChars = userPrompt.length,
            systemPrompt = if (includePrompts) systemPrompt else null,
            userPrompt = if (includePrompts) userPrompt else null,
            failureReason = e.message ?: e::class.simpleName
        )

    private fun traceFor(
        request: LlmRequest,
        userPrompt: String,
        final: Attempt,
        answered: List<Attempt>,
        firstRejection: String?,
        retryError: String?
    ): LlmCallTrace {
        val success = final.result as? LlmResponseParser.ParseResult.Success
        val failure = final.result as? LlmResponseParser.ParseResult.Failure
        val retryFailure = retryError?.let { "Retry call failed: $it" }
        return LlmCallTrace(
            clientId = client.identifier,
            requestKind = request.kind.name,
            systemPromptHash = sha256Prefix(request.systemPrompt),
            userPromptHash = sha256Prefix(userPrompt),
            inputTokens = answered.sumOf { it.response.usageInputTokens },
            outputTokens = answered.sumOf { it.response.usageOutputTokens },
            cacheReadTokens = answered.sumOf { it.response.usageCacheReadTokens },
            latencyMs = answered.sumOf { it.response.latencyMs },
            stopReason = final.response.stopReason,
            parsedSuccessfully = success != null,
            parsePath = final.result.parsePath.name,
            fallbackUsed = if (failure != null) "parse_failure" else null,
            rationale = success?.rationale,
            notes = success?.notes,
            userPromptChars = userPrompt.length,
            systemPrompt = if (includePrompts) request.systemPrompt else null,
            userPrompt = if (includePrompts) userPrompt else null,
            failureReason = listOfNotNull(failure?.reason, retryFailure).joinToString("; ").ifEmpty { null },
            failedResponse = if (includePrompts) failure?.rawResponse?.take(RESPONSE_TRACE_MAX_CHARS) else null,
            retryCount = if (firstRejection != null) 1 else 0,
            firstAttemptRejection = firstRejection,
            firstAttemptResponse = if (includePrompts && firstRejection != null) answered.first().response.answerText().take(RESPONSE_TRACE_MAX_CHARS) else null,
            droppedActionIds = success?.droppedActionIds.orEmpty()
        )
    }

    private fun LlmResponse.answerText() = toolCallJson ?: rawText

    private fun sha256Prefix(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    private class Attempt(val response: LlmResponse, val result: LlmResponseParser.ParseResult)

    private companion object {
        const val RESPONSE_TRACE_MAX_CHARS = 4000
    }
}
