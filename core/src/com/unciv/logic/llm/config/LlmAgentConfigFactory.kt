package com.unciv.logic.llm.config

import com.unciv.logic.llm.LlmPolicy
import com.unciv.logic.llm.client.FakeLlmClient
import com.unciv.logic.llm.policy.LlmPolicyFallback
import com.unciv.logic.llm.policy.RealLlmPolicy
import com.unciv.logic.llm.prompt.LlmPromptBuilder
import java.io.File

class LlmAgentConfigFactory {
    fun buildPolicy(config: LlmAgentConfig, baseDir: File = File(".")): LlmPolicy {
        val client = when (config.client.kind) {
            "fake" -> {
                val transcriptFile = resolve(baseDir, config.client.transcriptPath!!)
                FakeLlmClient.fromJsonLines(transcriptFile.readLines(Charsets.UTF_8), "transcript:${transcriptFile.name}")
            }
            else -> throw IllegalArgumentException("Unsupported LLM client kind in core factory: ${config.client.kind}")
        }
        return RealLlmPolicy(
            client = client,
            promptBuilder = LlmPromptBuilder(config.request.maxTokens, config.request.temperature),
            fallback = fallback(config.policy.fallback),
            includePrompts = config.trace.includePrompts
        )
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
