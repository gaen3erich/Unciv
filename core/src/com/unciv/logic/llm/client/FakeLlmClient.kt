package com.unciv.logic.llm.client

import com.badlogic.gdx.utils.JsonReader

class FakeLlmClient private constructor(
    private val responses: List<LlmResponse>,
    override val identifier: String
) : LlmClient {
    private var callIndex = 0

    override fun send(request: LlmRequest): LlmResponse {
        if (callIndex >= responses.size)
            throw IllegalStateException("FakeLlmClient exhausted at call $callIndex")
        return responses[callIndex++]
    }

    companion object {
        fun byCallIndex(responses: List<LlmResponse>) = FakeLlmClient(responses, "fake:byCallIndex")

        fun fromJsonLines(lines: List<String>, identifierSuffix: String = "jsonLines"): FakeLlmClient {
            val parsed = lines
                .filter { it.isNotBlank() }
                .map { line ->
                    val json = JsonReader().parse(line)
                    LlmResponse(
                        rawText = json.getString("rawText", ""),
                        toolCallJson = json.getString("toolCallJson", null),
                        stopReason = json.getString("stopReason", "unknown"),
                        usageInputTokens = json.getInt("usageInputTokens", 0),
                        usageOutputTokens = json.getInt("usageOutputTokens", 0),
                        usageCacheReadTokens = json.getInt("usageCacheReadTokens", 0),
                        latencyMs = json.getLong("latencyMs", 0L)
                    )
                }
            return FakeLlmClient(parsed, "fake:$identifierSuffix")
        }
    }
}
