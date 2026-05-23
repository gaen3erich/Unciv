package com.unciv.logic.llm

data class LlmDecisionResult(
    val observation: LlmObservation,
    val selectedActions: List<LlmLegalAction>,
    val appliedActions: List<LlmLegalAction>,
    val rejectedActions: List<LlmRejectedAction>
) {
    val observationTurn: Int get() = observation.turn
    val observationLegalActionIds: Set<String> get() = observation.legalActions.map { it.id }.toSet()
}

data class LlmRejectedAction(
    val action: LlmLegalAction,
    val reason: String
)
