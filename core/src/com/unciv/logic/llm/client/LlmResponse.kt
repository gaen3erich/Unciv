package com.unciv.logic.llm.client

data class LlmResponse(
    val rawText: String,
    val toolCallJson: String?,
    val stopReason: String,
    val usageInputTokens: Int,
    val usageOutputTokens: Int,
    val usageCacheReadTokens: Int = 0,
    val latencyMs: Long = 0L
)
