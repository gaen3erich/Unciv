package com.unciv.logic.llm

interface LlmPolicy {
    fun chooseActions(observation: LlmObservation): List<LlmLegalAction>
}
