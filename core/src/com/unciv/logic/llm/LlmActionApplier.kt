package com.unciv.logic.llm

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.IConstruction

class LlmActionApplier {
    fun apply(gameInfo: GameInfo, civ: Civilization, action: LlmLegalAction): LlmRejectedAction? {
        return when (action) {
            is LlmLegalAction.ChooseTechnology -> applyChooseTechnology(civ, action)
            is LlmLegalAction.ChooseCityConstruction -> applyChooseCityConstruction(civ, action)
            is LlmLegalAction.EndTurn -> {
                gameInfo.nextTurn(progressBar = null)
                null
            }
        }
    }

    private fun applyChooseTechnology(civ: Civilization, action: LlmLegalAction.ChooseTechnology): LlmRejectedAction? {
        val ruleset = civ.gameInfo.ruleset
        val path = action.path.ifEmpty { listOf(action.techName) }
        val unknownTech = path.firstOrNull { it !in ruleset.technologies }
        if (unknownTech != null) return LlmRejectedAction(action, "Unknown technology: $unknownTech")
        val illegalTech = path.firstOrNull { !civ.tech.canBeResearched(it) }
        if (illegalTech != null) return LlmRejectedAction(action, "Technology is not researchable: $illegalTech")

        civ.tech.techsToResearch = ArrayList(path)
        civ.tech.updateResearchProgress()
        return null
    }

    private fun applyChooseCityConstruction(
        civ: Civilization,
        action: LlmLegalAction.ChooseCityConstruction
    ): LlmRejectedAction? {
        val city = civ.cities.firstOrNull {
            LlmActionCollector.cityIdFor(civ, it) == action.cityId || it.name == action.cityName
        }
            ?: return LlmRejectedAction(action, "Unknown city: ${action.cityId}")
        val construction = city.getRuleset().buildings[action.constructionName] as IConstruction?
            ?: city.getRuleset().units[action.constructionName]
            ?: return LlmRejectedAction(action, "Unknown construction: ${action.constructionName}")
        if (!city.cityConstructions.canAddToQueue(construction))
            return LlmRejectedAction(action, "Construction is not buildable in ${city.name}: ${action.constructionName}")

        city.cityConstructions.addToQueue(construction, action.addToTop)
        city.reassignPopulation()
        return null
    }
}
