package com.unciv.logic.llm.policy

import com.unciv.logic.llm.LlmLegalAction
import com.unciv.logic.llm.LlmObservation
import com.unciv.logic.llm.client.LlmClientException
import com.unciv.logic.llm.client.LlmResponse

sealed interface LlmPolicyFallback {
    val name: String
    fun onClientError(observation: LlmObservation, e: LlmClientException): List<LlmLegalAction>
    fun onParseFailure(observation: LlmObservation, response: LlmResponse, reason: String): List<LlmLegalAction>

    object EndTurnOnly : LlmPolicyFallback {
        override val name = "end_turn_only"
        override fun onClientError(observation: LlmObservation, e: LlmClientException) = listOf(LlmLegalAction.EndTurn())
        override fun onParseFailure(observation: LlmObservation, response: LlmResponse, reason: String) = listOf(LlmLegalAction.EndTurn())
    }

    object Empty : LlmPolicyFallback {
        override val name = "empty"
        override fun onClientError(observation: LlmObservation, e: LlmClientException) = emptyList<LlmLegalAction>()
        override fun onParseFailure(observation: LlmObservation, response: LlmResponse, reason: String) = emptyList<LlmLegalAction>()
    }
}
