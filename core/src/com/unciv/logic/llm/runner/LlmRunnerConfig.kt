package com.unciv.logic.llm.runner

import java.io.File

data class LlmRunnerConfig(
    val saveFile: File? = null,
    val outputFile: File? = null,
    val policy: PolicyKind = PolicyKind.RANDOM,
    val seed: Long = 0L,
    val steps: Int = 1,
    val allowOnlineMultiplayer: Boolean = false,
    val failOnRejected: Boolean = false,
    val uncivVersion: String = "unknown",
    val agentConfig: com.unciv.logic.llm.config.LlmAgentConfigSource? = null
)

enum class PolicyKind {
    RANDOM,
    END_TURN_ONLY,
    LLM
}
