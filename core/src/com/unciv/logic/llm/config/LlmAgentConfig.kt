package com.unciv.logic.llm.config

data class LlmAgentConfig(
    val schema: String,
    val name: String,
    val description: String?,
    val client: LlmClientConfig,
    val request: LlmRequestConfig,
    val policy: LlmPolicyConfig,
    val trace: LlmTraceConfig,
    val source: LlmAgentConfigSource? = null
)

data class LlmClientConfig(
    val kind: String,
    val apiKeyEnv: String?,
    val model: String?,
    val baseUrl: String?,
    val timeoutSeconds: Int,
    val maxRetries: Int,
    val extraHeaders: Map<String, String>,
    val transcriptPath: String?
)

data class LlmRequestConfig(
    val temperature: Double,
    val maxTokens: Int
)

data class LlmPolicyConfig(
    val fallback: String,
    val failOnLlmErrorAfterConsecutive: Int
)

data class LlmTraceConfig(
    val includePrompts: Boolean,
    val includeRationale: Boolean
)

data class LlmAgentConfigSource(
    val path: String,
    val sha256: String,
    val name: String,
    val clientKind: String,
    val model: String?
)
