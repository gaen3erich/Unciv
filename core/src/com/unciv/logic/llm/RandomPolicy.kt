package com.unciv.logic.llm

import kotlin.random.Random

class RandomPolicy(seed: Long? = null) : LlmPolicy {
    private val random = if (seed == null) Random.Default else Random(seed)

    override fun chooseActions(observation: LlmObservation): List<LlmLegalAction> {
        if (observation.legalActions.isEmpty()) return emptyList()
        return listOf(observation.legalActions[random.nextInt(observation.legalActions.size)])
    }
}
