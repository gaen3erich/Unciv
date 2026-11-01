package com.unciv.logic.llm.client

data class LlmRequest(
    val kind: LlmRequestKind,
    val systemPrompt: String,
    val messages: List<LlmMessage>,
    val toolSchema: String?,
    val maxTokens: Int,
    val temperature: Double,
    /** Ask the provider to cache the stable prefix (tools + system prompt). */
    val cacheSystemPrompt: Boolean = false
)

enum class LlmRequestKind {
    DECISION
}
