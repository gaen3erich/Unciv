package com.unciv.logic.llm.prompt

import com.unciv.logic.llm.LlmLegalAction
import com.unciv.logic.llm.LlmObservation

/**
 * Short, human-readable ids for the legal actions of one observation, used only inside prompts.
 *
 * The gateway keeps working with the full [LlmLegalAction.id]s; this table maps both directions so the
 * model can answer with `build:Antium:Bank` instead of `choose_city_construction:<uuid>:Bank`.
 * Aliases are derived from names, so they stay stable from turn to turn and can be referenced in notes.
 * Whenever an alias would be ambiguous the full id is used instead.
 */
class LlmActionAliases private constructor(
    private val aliasByActionId: Map<String, String>,
    private val actionByAlias: Map<String, LlmLegalAction>
) {
    fun aliasOf(action: LlmLegalAction): String = aliasByActionId[action.id] ?: action.id

    /** Accepts either an alias or a full legal action id. */
    fun resolve(idOrAlias: String): LlmLegalAction? = actionByAlias[idOrAlias.trim()]

    companion object {
        fun of(observation: LlmObservation): LlmActionAliases {
            val cityNameById = observation.cities.associate { it.cityId to it.name }
            val ownNameCounts = observation.cities.groupingBy { it.name }.eachCount()
            fun ownCity(cityId: String, fallbackName: String): String {
                val name = cityNameById[cityId] ?: fallbackName
                return if ((ownNameCounts[name] ?: 0) > 1) cityId else name
            }

            val candidates = observation.legalActions.map { action ->
                action to when (action) {
                    is LlmLegalAction.ChooseTechnology -> "tech:${action.techName}"
                    is LlmLegalAction.ChooseCityConstruction ->
                        "build:${ownCity(action.cityId, action.cityName)}:${action.constructionName}"
                    is LlmLegalAction.RunUnitAutomation -> action.id
                    is LlmLegalAction.AttackCity -> "attack:${action.targetCityName}"
                    is LlmLegalAction.DefendCity -> "defend:${ownCity(action.cityId, action.cityName)}"
                    is LlmLegalAction.DeclareWar -> "war:${action.targetCivName}"
                    is LlmLegalAction.OfferPeace -> "peace:${action.targetCivName}"
                    is LlmLegalAction.FoundCity -> "found:${action.unitId}@${action.location.x},${action.location.y}"
                    is LlmLegalAction.EndTurn -> action.id
                }
            }
            val aliasCounts = candidates.groupingBy { it.second }.eachCount()
            val fullIds = observation.legalActions.map { it.id }.toSet()

            val aliasByActionId = LinkedHashMap<String, String>()
            val actionByAlias = LinkedHashMap<String, LlmLegalAction>()
            for ((action, alias) in candidates) {
                // An alias must be unique and must not shadow another action's full id
                val usable = aliasCounts[alias] == 1 && (alias == action.id || alias !in fullIds)
                val chosen = if (usable) alias else action.id
                aliasByActionId[action.id] = chosen
                actionByAlias[chosen] = action
            }
            for (action in observation.legalActions) actionByAlias.putIfAbsent(action.id, action)
            return LlmActionAliases(aliasByActionId, actionByAlias)
        }
    }
}
