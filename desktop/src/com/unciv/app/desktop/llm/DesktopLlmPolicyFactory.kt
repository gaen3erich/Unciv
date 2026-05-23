package com.unciv.app.desktop.llm

import com.unciv.logic.llm.LlmPolicy
import com.unciv.logic.llm.client.FakeLlmClient
import com.unciv.logic.llm.client.LlmClient
import com.unciv.logic.llm.client.LlmClientConfigurationException
import com.unciv.logic.llm.config.LlmAgentConfig
import com.unciv.logic.llm.policy.LlmPolicyFallback
import com.unciv.logic.llm.policy.RealLlmPolicy
import com.unciv.logic.llm.prompt.LlmPromptBuilder
import java.io.File

class DesktopLlmPolicyFactory {
    fun buildPolicy(config: LlmAgentConfig, baseDir: File = File(".")): LlmPolicy {
        return RealLlmPolicy(
            client = buildClient(config, baseDir),
            promptBuilder = LlmPromptBuilder(config.request.maxTokens, config.request.temperature),
            fallback = fallback(config.policy.fallback),
            includePrompts = config.trace.includePrompts
        )
    }

    private fun buildClient(config: LlmAgentConfig, baseDir: File): LlmClient = when (config.client.kind) {
        "fake" -> {
            val transcriptFile = resolve(baseDir, config.client.transcriptPath!!)
            FakeLlmClient.fromJsonLines(transcriptFile.readLines(Charsets.UTF_8), "transcript:${transcriptFile.name}")
        }
        "anthropic" -> AnthropicLlmClient(
            model = config.client.model!!,
            apiKey = apiKey(config.client.apiKeyEnv!!),
            baseUrl = config.client.baseUrl,
            timeoutSeconds = config.client.timeoutSeconds,
            maxRetries = config.client.maxRetries,
            extraHeaders = config.client.extraHeaders
        )
        "openai-compat" -> OpenAiCompatibleLlmClient(
            model = config.client.model!!,
            apiKey = apiKey(config.client.apiKeyEnv!!),
            baseUrl = config.client.baseUrl!!,
            timeoutSeconds = config.client.timeoutSeconds,
            maxRetries = config.client.maxRetries,
            extraHeaders = config.client.extraHeaders
        )
        else -> throw IllegalArgumentException("Unknown LLM client kind: ${config.client.kind}")
    }

    private fun apiKey(envName: String): String {
        val value = System.getenv(envName)
        if (value.isNullOrBlank()) throw LlmClientConfigurationException("Environment variable $envName is required for LLM client")
        return value
    }

    private fun fallback(name: String): LlmPolicyFallback = when (name) {
        "end_turn_only" -> LlmPolicyFallback.EndTurnOnly
        "empty" -> LlmPolicyFallback.Empty
        else -> throw IllegalArgumentException("Unknown LLM fallback: $name")
    }

    private fun resolve(baseDir: File, path: String): File {
        val file = File(path)
        return if (file.isAbsolute) file else File(baseDir, path)
    }
}
