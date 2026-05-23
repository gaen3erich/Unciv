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
    val systemPrompt: String? = null,
    val userPrompt: String? = null
)

interface LlmTraceablePolicy {
    fun consumeLastCallTrace(): LlmCallTrace?
}
