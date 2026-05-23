package com.unciv.logic.llm

class ScriptedPolicy(actions: List<LlmLegalAction>) : LlmPolicy {
    private val remainingActions = ArrayDeque(actions)

    override fun chooseActions(observation: LlmObservation): List<LlmLegalAction> {
        if (remainingActions.isEmpty()) return emptyList()
        return listOf(remainingActions.removeFirst())
    }
}
