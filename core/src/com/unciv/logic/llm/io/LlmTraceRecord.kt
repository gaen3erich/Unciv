package com.unciv.logic.llm.io

import com.unciv.logic.llm.LlmDecisionResult
import com.unciv.logic.llm.LlmLegalAction
import com.unciv.logic.llm.LlmObservation
import com.unciv.logic.llm.LlmRejectedAction
import com.unciv.logic.llm.policy.LlmCallTrace

data class LlmTraceHeader(
    val createdAt: String,
    val uncivVersion: String,
    val compatibilityVersion: Int,
    val saveFile: String,
    val policy: LlmTracePolicy,
    val allowOnlineMultiplayer: Boolean,
    val agentConfig: com.unciv.logic.llm.config.LlmAgentConfigSource? = null,
    val observationSchema: String = com.unciv.logic.llm.LlmObservationVersion.CURRENT
)

data class LlmTracePolicy(
    val kind: String,
    val seed: Long? = null
)

data class LlmTraceRecord(
    val step: Int,
    val createdAt: String,
    val turn: Int,
    val civId: String,
    val observation: LlmObservation,
    val selectedActions: List<LlmLegalAction>,
    val appliedActions: List<LlmLegalAction>,
    val rejectedActions: List<LlmRejectedAction>,
    val llmCall: LlmCallTrace? = null,
    val error: String? = null
) {
    constructor(step: Int, createdAt: String, result: LlmDecisionResult, llmCall: LlmCallTrace? = null, error: String? = null) : this(
        step = step,
        createdAt = createdAt,
        turn = result.observation.turn,
        civId = result.observation.currentPlayerCivId,
        observation = result.observation,
        selectedActions = result.selectedActions,
        appliedActions = result.appliedActions,
        rejectedActions = result.rejectedActions,
        llmCall = llmCall,
        error = error
    )
}
