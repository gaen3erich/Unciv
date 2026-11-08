package com.unciv.logic.llm.policy

data class LlmCallTrace(
    val clientId: String,
    val requestKind: String,
    val systemPromptHash: String,
    val userPromptHash: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val cacheReadTokens: Int,
    val latencyMs: Long,
    val stopReason: String,
    val parsedSuccessfully: Boolean,
    val parsePath: String,
    val fallbackUsed: String?,
    val rationale: String? = null,
    val notes: String? = null,
    val userPromptChars: Int = 0,
    val systemPrompt: String? = null,
    val userPrompt: String? = null,
    /** Why the call fell back: the client error message or the parser's reason. Null when the call succeeded. */
    val failureReason: String? = null,
    /** The model output the parser rejected, capped; only kept when prompts are included in the trace. */
    val failedResponse: String? = null,
    /** 1 when the model was asked again in the same step; tokens and latency then cover both calls. */
    val retryCount: Int = 0,
    /** Why the first answer was rejected, when the step was retried. */
    val firstAttemptRejection: String? = null,
    /** The first answer of a retried step, capped; only kept when prompts are included in the trace. */
    val firstAttemptResponse: String? = null,
    /** Illegal ids discarded from the answer that was finally applied. */
    val droppedActionIds: List<String> = emptyList()
)

interface LlmTraceablePolicy {
    fun consumeLastCallTrace(): LlmCallTrace?
}
