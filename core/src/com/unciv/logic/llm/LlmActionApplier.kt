package com.unciv.logic.llm

import com.unciv.Constants
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.logic.trade.TradeRequest
import com.unciv.models.ruleset.IConstruction

class LlmActionApplier(
    private val unitCommander: LlmUnitCommander = LlmUnitCommander()
) {
    /** Called by the gateway before applying the actions of one step, so that unit orders don't carry over. */
    fun beginStep() = unitCommander.beginStep()

    fun apply(gameInfo: GameInfo, civ: Civilization, action: LlmLegalAction): LlmRejectedAction? {
        return when (action) {
            is LlmLegalAction.ChooseTechnology -> applyChooseTechnology(civ, action)
            is LlmLegalAction.ChooseCityConstruction -> applyChooseCityConstruction(civ, action)
            is LlmLegalAction.RunUnitAutomation -> {
                unitCommander.runAutomation(civ)
                null
            }
            is LlmLegalAction.AttackCity -> applyAttackCity(gameInfo, civ, action)
            is LlmLegalAction.DefendCity -> applyDefendCity(civ, action)
            is LlmLegalAction.DeclareWar -> applyDeclareWar(gameInfo, civ, action)
            is LlmLegalAction.OfferPeace -> applyOfferPeace(gameInfo, civ, action)
            is LlmLegalAction.FoundCity -> applyFoundCity(gameInfo, civ, action)
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

    private fun applyAttackCity(gameInfo: GameInfo, civ: Civilization, action: LlmLegalAction.AttackCity): LlmRejectedAction? {
        val targetCiv = gameInfo.getCivilization(action.targetCivId)
        val city = targetCiv.cities.firstOrNull { LlmActionCollector.cityIdFor(targetCiv, it) == action.targetCityId }
            ?: return LlmRejectedAction(action, "Unknown enemy city: ${action.targetCityId}")
        if (!civ.isAtWarWith(targetCiv)) return LlmRejectedAction(action, "Not at war with ${targetCiv.civName}")
        unitCommander.attackCity(civ, city)
        return null
    }

    private fun applyDefendCity(civ: Civilization, action: LlmLegalAction.DefendCity): LlmRejectedAction? {
        val city = civ.cities.firstOrNull { LlmActionCollector.cityIdFor(civ, it) == action.cityId }
            ?: return LlmRejectedAction(action, "Unknown city: ${action.cityId}")
        unitCommander.defendCity(civ, city)
        return null
    }

    private fun applyDeclareWar(gameInfo: GameInfo, civ: Civilization, action: LlmLegalAction.DeclareWar): LlmRejectedAction? {
        val target = gameInfo.getCivilization(action.targetCivId)
        val diplomacy = civ.getDiplomacyManager(target)
            ?: return LlmRejectedAction(action, "We have not met ${target.civName}")
        if (!diplomacy.canDeclareWar()) return LlmRejectedAction(action, "Cannot declare war on ${target.civName} now")
        diplomacy.declareWar()
        return null
    }

    private fun applyOfferPeace(gameInfo: GameInfo, civ: Civilization, action: LlmLegalAction.OfferPeace): LlmRejectedAction? {
        val target = gameInfo.getCivilization(action.targetCivId)
        if (civ.getDiplomacyManager(target)?.diplomaticStatus != DiplomaticStatus.War)
            return LlmRejectedAction(action, "Not at war with ${target.civName}")
        // Same shape as the AI's own peace proposal in DiplomacyAutomation.offerPeaceTreaty, without gold
        val tradeLogic = TradeLogic(civ, target)
        val peaceTreaty = TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, speed = gameInfo.speed)
        tradeLogic.currentTrade.ourOffers.add(peaceTreaty)
        tradeLogic.currentTrade.theirOffers.add(peaceTreaty)
        target.tradeRequests.add(TradeRequest(civ.civID, tradeLogic.currentTrade.reverse()))
        return null
    }

    private fun applyFoundCity(gameInfo: GameInfo, civ: Civilization, action: LlmLegalAction.FoundCity): LlmRejectedAction? {
        val unit = civ.units.getCivUnits().firstOrNull { it.id == action.unitId }
            ?: return LlmRejectedAction(action, "Unknown unit: ${action.unitId}")
        val site = gameInfo.tileMap.getIfTileExistsOrNull(action.location.x, action.location.y)
            ?: return LlmRejectedAction(action, "Unknown tile: ${action.location.x},${action.location.y}")
        val failure = unitCommander.foundCity(civ, unit, site) ?: return null
        return LlmRejectedAction(action, failure)
    }
}
