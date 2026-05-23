package com.unciv.logic.llm.client

data class LlmRequest(
    val kind: LlmRequestKind,
    val systemPrompt: String,
    val messages: List<LlmMessage>,
    val toolSchema: String?,
    val maxTokens: Int,
    val temperature: Double
)

enum class LlmRequestKind {
    DECISION
}
