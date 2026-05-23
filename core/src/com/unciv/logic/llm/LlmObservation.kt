package com.unciv.logic.llm

data class LlmObservation(
    val gameId: String,
    val turn: Int,
    val currentPlayerCivId: String,
    val currentPlayerCivName: String,
    val isOnlineMultiplayer: Boolean,
    val currentResearch: String?,
    val sciencePerTurn: Int,
    val cities: List<LlmCityObservation>,
    val researchedTechs: List<String>,
    val legalActions: List<LlmLegalAction>,
    val economy: LlmEconomy,
    val resources: LlmResources,
    val diplomacy: LlmDiplomacy,
    val legalActionsSummary: LlmLegalActionsSummary = LlmLegalActionsSummary.from(legalActions),
    val schemaVersion: String = LlmObservationVersion.CURRENT
)

data class LlmCityObservation(
    val cityId: String,
    val name: String,
    val population: Int,
    val currentConstruction: String?,
    val productionPerTurn: Int,
    val queue: List<String>,
    val location: LlmHexCoord,
    val health: LlmCityHealth,
    val defenders: LlmCityDefenders,
    val visibleEnemies: LlmVisibleEnemies,
    val workableTilesSummary: LlmWorkableTilesSummary,
    val expansionPressure: LlmExpansionPressure
)

data class LlmLegalActionsSummary(
    val technologies: Int,
    val constructionsByCity: List<LlmCityLegalActionsSummary>,
    val endTurn: Boolean
) {
    companion object {
        fun from(actions: List<LlmLegalAction>): LlmLegalActionsSummary {
            val constructions = linkedMapOf<String, Int>()
            for (action in actions) {
                if (action is LlmLegalAction.ChooseCityConstruction)
                    constructions[action.cityId] = (constructions[action.cityId] ?: 0) + 1
            }
            return LlmLegalActionsSummary(
                technologies = actions.count { it is LlmLegalAction.ChooseTechnology },
                constructionsByCity = constructions.entries
                    .sortedBy { it.key }
                    .map { LlmCityLegalActionsSummary(it.key, it.value) },
                endTurn = actions.any { it is LlmLegalAction.EndTurn }
            )
        }
    }
}

data class LlmCityLegalActionsSummary(
    val cityId: String,
    val constructions: Int
)
