package com.unciv.logic.llm

/**
 * Current LLM observation contract.
 *
 * v2 fields:
 * - LlmObservation: schemaVersion, gameId, turn, currentPlayerCivId,
 *   currentPlayerCivName, isOnlineMultiplayer, currentResearch,
 *   sciencePerTurn, cities, researchedTechs, legalActions,
 *   legalActionsSummary, economy, resources.
 * - LlmCityObservation: cityId, name, population, currentConstruction,
 *   productionPerTurn, queue.
 * - LlmEconomy: gold, storedCulture, storedFaith, happiness,
 *   goldPerTurn, sciencePerTurn, culturePerTurn, faithPerTurn,
 *   happinessPerTurn.
 * - LlmResources: strategic, luxury.
 */
object LlmObservationVersion {
    const val CURRENT = "llm-obs/v4"
}
