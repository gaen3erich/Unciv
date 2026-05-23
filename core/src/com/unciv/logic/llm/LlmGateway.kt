package com.unciv.logic.llm

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization

class LlmGateway(
    private val policy: LlmPolicy,
    private val collector: LlmActionCollector = LlmActionCollector(),
    private val applier: LlmActionApplier = LlmActionApplier(),
    private val allowOnlineMultiplayer: Boolean = false
) {
    fun step(gameInfo: GameInfo, civ: Civilization = gameInfo.getCurrentPlayerCivilization()): LlmDecisionResult {
        val observation = collector.buildObservation(gameInfo, civ)

        val selectedActions = policy.chooseActions(observation)
        val legalActionsById = observation.legalActions.associateBy { it.id }
        val appliedActions = ArrayList<LlmLegalAction>()
        val rejectedActions = ArrayList<LlmRejectedAction>()

        for (selectedAction in selectedActions) {
            if (gameInfo.gameParameters.isOnlineMultiplayer && !allowOnlineMultiplayer) {
                rejectedActions += LlmRejectedAction(selectedAction, "Online multiplayer is not supported by the LLM gateway MVP")
                continue
            }
            val legalAction = legalActionsById[selectedAction.id]
            if (legalAction == null || legalAction != selectedAction) {
                rejectedActions += LlmRejectedAction(selectedAction, "Action is not legal in the current observation")
                continue
            }

            val rejection = applier.apply(gameInfo, civ, selectedAction)
            if (rejection == null) appliedActions += selectedAction
            else rejectedActions += rejection
        }

        return LlmDecisionResult(observation, selectedActions, appliedActions, rejectedActions)
    }
}
