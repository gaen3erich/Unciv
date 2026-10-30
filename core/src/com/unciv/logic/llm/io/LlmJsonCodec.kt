package com.unciv.logic.llm.io

import com.unciv.logic.llm.LlmCityObservation
import com.unciv.logic.llm.LlmCityDefenders
import com.unciv.logic.llm.LlmCityHealth
import com.unciv.logic.llm.LlmDecisionResult
import com.unciv.logic.llm.LlmDiplomacy
import com.unciv.logic.llm.LlmEconomy
import com.unciv.logic.llm.LlmEnemyCivCount
import com.unciv.logic.llm.LlmExpansionPressure
import com.unciv.logic.llm.LlmHexCoord
import com.unciv.logic.llm.LlmKnownCiv
import com.unciv.logic.llm.LlmLegalAction
import com.unciv.logic.llm.LlmNearbyCity
import com.unciv.logic.llm.LlmObservation
import com.unciv.logic.llm.LlmRankingEntry
import com.unciv.logic.llm.LlmRejectedAction
import com.unciv.logic.llm.LlmRelativeStanding
import com.unciv.logic.llm.LlmResourceEntry
import com.unciv.logic.llm.LlmResources
import com.unciv.logic.llm.LlmThreats
import com.unciv.logic.llm.LlmTileYields
import com.unciv.logic.llm.LlmTradeOfferBrief
import com.unciv.logic.llm.LlmTradeSummary
import com.unciv.logic.llm.LlmUnitBrief
import com.unciv.logic.llm.LlmVisibleEnemies
import com.unciv.logic.llm.LlmWarStatus
import com.unciv.logic.llm.LlmWorkableTilesSummary
import com.unciv.logic.llm.policy.LlmCallTrace

class LlmJsonCodec {
    fun encodeHeader(header: LlmTraceHeader): String = obj(
        field("schema", SCHEMA),
        field("observationSchema", header.observationSchema),
        field("type", "header"),
        field("createdAt", header.createdAt),
        field("uncivVersion", header.uncivVersion),
        rawField("compatibilityVersion", header.compatibilityVersion.toString()),
        field("saveFile", header.saveFile),
        rawField("policy", encodePolicy(header.policy)),
        rawField("agentConfig", header.agentConfig?.let { encodeAgentConfig(it) } ?: "null"),
        rawField("allowOnlineMultiplayer", header.allowOnlineMultiplayer.toString())
    )

    fun encodeTraceRecord(record: LlmTraceRecord): String = obj(
        field("schema", SCHEMA),
        field("type", "step"),
        rawField("step", record.step.toString()),
        field("createdAt", record.createdAt),
        rawField("turn", record.turn.toString()),
        field("civId", record.civId),
        rawField("observation", encodeObservation(record.observation)),
        rawField("selectedActions", array(record.selectedActions, ::encodeAction)),
        rawField("appliedActions", array(record.appliedActions, ::encodeAction)),
        rawField("rejectedActions", array(record.rejectedActions, ::encodeRejectedAction)),
        rawField("llmCall", record.llmCall?.let { encodeLlmCallTrace(it) } ?: "null"),
        rawField("error", record.error?.let { quote(it) } ?: "null")
    )

    fun encodeDecisionResult(result: LlmDecisionResult): String = obj(
        field("schema", SCHEMA),
        rawField("observation", encodeObservation(result.observation)),
        rawField("selectedActions", array(result.selectedActions, ::encodeAction)),
        rawField("appliedActions", array(result.appliedActions, ::encodeAction)),
        rawField("rejectedActions", array(result.rejectedActions, ::encodeRejectedAction))
    )

    fun encodeObservation(observation: LlmObservation): String = obj(
        field("schemaVersion", observation.schemaVersion),
        field("gameId", observation.gameId),
        rawField("turn", observation.turn.toString()),
        field("currentPlayerCivId", observation.currentPlayerCivId),
        field("currentPlayerCivName", observation.currentPlayerCivName),
        rawField("isOnlineMultiplayer", observation.isOnlineMultiplayer.toString()),
        rawField("currentResearch", observation.currentResearch?.let { quote(it) } ?: "null"),
        rawField("sciencePerTurn", observation.sciencePerTurn.toString()),
        rawField("cities", array(observation.cities, ::encodeCityObservation)),
        rawField("researchedTechs", stringArray(observation.researchedTechs)),
        rawField("legalActions", array(observation.legalActions, ::encodeAction)),
        rawField("legalActionsSummary", encodeLegalActionsSummary(observation.legalActionsSummary)),
        rawField("economy", encodeEconomy(observation.economy)),
        rawField("resources", encodeResources(observation.resources)),
        rawField("diplomacy", encodeDiplomacy(observation.diplomacy))
    )

    fun encodeAction(action: LlmLegalAction): String = when (action) {
        is LlmLegalAction.ChooseTechnology -> obj(
            field("kind", "ChooseTechnology"),
            field("id", action.id),
            field("techName", action.techName),
            rawField("path", stringArray(action.path))
        )
        is LlmLegalAction.ChooseCityConstruction -> obj(
            field("kind", "ChooseCityConstruction"),
            field("id", action.id),
            field("cityId", action.cityId),
            field("cityName", action.cityName),
            field("constructionName", action.constructionName),
            rawField("addToTop", action.addToTop.toString())
        )
        is LlmLegalAction.RunUnitAutomation -> obj(
            field("kind", "RunUnitAutomation"),
            field("id", action.id)
        )
        is LlmLegalAction.AttackCity -> obj(
            field("kind", "AttackCity"),
            field("id", action.id),
            field("targetCivId", action.targetCivId),
            field("targetCityId", action.targetCityId),
            field("targetCityName", action.targetCityName),
            rawField("location", encodeHexCoord(action.location))
        )
        is LlmLegalAction.DefendCity -> obj(
            field("kind", "DefendCity"),
            field("id", action.id),
            field("cityId", action.cityId),
            field("cityName", action.cityName)
        )
        is LlmLegalAction.DeclareWar -> obj(
            field("kind", "DeclareWar"),
            field("id", action.id),
            field("targetCivId", action.targetCivId),
            field("targetCivName", action.targetCivName)
        )
        is LlmLegalAction.OfferPeace -> obj(
            field("kind", "OfferPeace"),
            field("id", action.id),
            field("targetCivId", action.targetCivId),
            field("targetCivName", action.targetCivName)
        )
        is LlmLegalAction.FoundCity -> obj(
            field("kind", "FoundCity"),
            field("id", action.id),
            rawField("unitId", action.unitId.toString()),
            field("unitName", action.unitName),
            rawField("location", encodeHexCoord(action.location))
        )
        is LlmLegalAction.EndTurn -> obj(
            field("kind", "EndTurn"),
            field("id", action.id)
        )
    }

    fun encodeRejectedAction(rejectedAction: LlmRejectedAction): String = obj(
        rawField("action", encodeAction(rejectedAction.action)),
        field("reason", rejectedAction.reason)
    )

    private fun encodePolicy(policy: LlmTracePolicy): String = obj(
        field("kind", policy.kind),
        rawField("seed", policy.seed?.toString() ?: "null")
    )

    private fun encodeAgentConfig(config: com.unciv.logic.llm.config.LlmAgentConfigSource): String = obj(
        field("path", config.path),
        field("sha256", config.sha256),
        field("name", config.name),
        field("clientKind", config.clientKind),
        rawField("model", config.model?.let { quote(it) } ?: "null")
    )

    private fun encodeCityObservation(city: LlmCityObservation): String = obj(
        field("cityId", city.cityId),
        field("name", city.name),
        rawField("population", city.population.toString()),
        rawField("currentConstruction", city.currentConstruction?.let { quote(it) } ?: "null"),
        rawField("productionPerTurn", city.productionPerTurn.toString()),
        rawField("queue", stringArray(city.queue)),
        rawField("location", encodeHexCoord(city.location)),
        rawField("health", encodeCityHealth(city.health)),
        rawField("defenders", encodeCityDefenders(city.defenders)),
        rawField("visibleEnemies", encodeVisibleEnemies(city.visibleEnemies)),
        rawField("workableTilesSummary", encodeWorkableTilesSummary(city.workableTilesSummary)),
        rawField("expansionPressure", encodeExpansionPressure(city.expansionPressure))
    )

    private fun encodeHexCoord(coord: LlmHexCoord): String = obj(
        rawField("x", coord.x.toString()),
        rawField("y", coord.y.toString())
    )

    private fun encodeCityHealth(health: LlmCityHealth): String = obj(
        rawField("current", health.current.toString()),
        rawField("max", health.max.toString()),
        rawField("isUnderAttack", health.isUnderAttack.toString()),
        rawField("isCritical", health.isCritical.toString())
    )

    private fun encodeCityDefenders(defenders: LlmCityDefenders): String = obj(
        rawField("garrison", defenders.garrison?.let { encodeUnitBrief(it) } ?: "null"),
        rawField("supportingUnitCount", defenders.supportingUnitCount.toString()),
        rawField("supportingUnitTypeBreakdown", intMap(defenders.supportingUnitTypeBreakdown))
    )

    private fun encodeVisibleEnemies(enemies: LlmVisibleEnemies): String = obj(
        rawField("totalCount", enemies.totalCount.toString()),
        rawField("closestDistance", enemies.closestDistance?.toString() ?: "null"),
        rawField("byCiv", array(enemies.byCiv, ::encodeEnemyCivCount)),
        rawField("brief", array(enemies.brief, ::encodeUnitBrief))
    )

    private fun encodeEnemyCivCount(count: LlmEnemyCivCount): String = obj(
        field("civName", count.civName),
        rawField("count", count.count.toString())
    )

    private fun encodeUnitBrief(unit: LlmUnitBrief): String = obj(
        field("name", unit.name),
        rawField("healthPercent", unit.healthPercent.toString()),
        rawField("isInjured", unit.isInjured.toString()),
        rawField("movementLeft", unit.movementLeft.toString())
    )

    private fun encodeWorkableTilesSummary(summary: LlmWorkableTilesSummary): String = obj(
        rawField("totalTiles", summary.totalTiles.toString()),
        rawField("workedTiles", summary.workedTiles.toString()),
        rawField("totalYields", encodeTileYields(summary.totalYields)),
        rawField("resourceCounts", intMap(summary.resourceCounts)),
        rawField("improvementCounts", intMap(summary.improvementCounts))
    )

    private fun encodeTileYields(yields: LlmTileYields): String = obj(
        rawField("food", yields.food.toString()),
        rawField("production", yields.production.toString()),
        rawField("gold", yields.gold.toString()),
        rawField("science", yields.science.toString()),
        rawField("culture", yields.culture.toString()),
        rawField("faith", yields.faith.toString()),
        rawField("happiness", yields.happiness.toString())
    )

    private fun encodeExpansionPressure(pressure: LlmExpansionPressure): String = obj(
        rawField("cultureStored", pressure.cultureStored.toString()),
        rawField("turnsToNextTile", pressure.turnsToNextTile?.toString() ?: "null"),
        rawField("ownedTiles", pressure.ownedTiles.toString()),
        rawField("foreignBorderTiles", pressure.foreignBorderTiles.toString()),
        rawField("nearestForeignCity", pressure.nearestForeignCity?.let { encodeNearbyCity(it) } ?: "null")
    )

    private fun encodeNearbyCity(city: LlmNearbyCity): String = obj(
        field("civName", city.civName),
        field("cityName", city.cityName),
        rawField("distance", city.distance.toString())
    )

    private fun encodeLegalActionsSummary(summary: com.unciv.logic.llm.LlmLegalActionsSummary): String = obj(
        rawField("technologies", summary.technologies.toString()),
        rawField("constructionsByCity", array(summary.constructionsByCity) {
            obj(
                field("cityId", it.cityId),
                rawField("constructions", it.constructions.toString())
            )
        }),
        rawField("endTurn", summary.endTurn.toString())
    )

    private fun encodeEconomy(economy: LlmEconomy): String = obj(
        rawField("gold", economy.gold.toString()),
        rawField("storedCulture", economy.storedCulture.toString()),
        rawField("storedFaith", economy.storedFaith.toString()),
        rawField("happiness", economy.happiness.toString()),
        rawField("goldPerTurn", economy.goldPerTurn.toString()),
        rawField("sciencePerTurn", economy.sciencePerTurn.toString()),
        rawField("culturePerTurn", economy.culturePerTurn.toString()),
        rawField("faithPerTurn", economy.faithPerTurn.toString()),
        rawField("happinessPerTurn", economy.happinessPerTurn.toString())
    )

    private fun encodeResources(resources: LlmResources): String = obj(
        rawField("strategic", array(resources.strategic, ::encodeResourceEntry)),
        rawField("luxury", array(resources.luxury, ::encodeResourceEntry))
    )

    private fun encodeResourceEntry(resource: LlmResourceEntry): String = obj(
        field("name", resource.name),
        rawField("amount", resource.amount.toString())
    )

    private fun encodeDiplomacy(diplomacy: LlmDiplomacy): String = obj(
        rawField("knownCivs", array(diplomacy.knownCivs, ::encodeKnownCiv)),
        rawField("activeWars", array(diplomacy.activeWars, ::encodeWarStatus)),
        rawField("activeTrades", array(diplomacy.activeTrades, ::encodeTradeSummary)),
        rawField("threats", encodeThreats(diplomacy.threats)),
        rawField("relativeStanding", encodeRelativeStanding(diplomacy.relativeStanding))
    )

    private fun encodeKnownCiv(civ: LlmKnownCiv): String = obj(
        field("civName", civ.civName),
        rawField("isMajorCiv", civ.isMajorCiv.toString()),
        rawField("isCityState", civ.isCityState.toString()),
        rawField("isDefeated", civ.isDefeated.toString()),
        field("diplomaticStatus", civ.diplomaticStatus),
        field("relationshipLevel", civ.relationshipLevel),
        rawField("opinionScore", civ.opinionScore?.toString() ?: "null"),
        rawField("hasOpenBorders", civ.hasOpenBorders.toString()),
        rawField("theirsHaveOpenBorders", civ.theirsHaveOpenBorders.toString()),
        rawField("hasDeclarationOfFriendship", civ.hasDeclarationOfFriendship.toString()),
        rawField("hasDefensivePact", civ.hasDefensivePact.toString()),
        rawField("hasResearchAgreement", civ.hasResearchAgreement.toString()),
        rawField("cityStateInfluence", civ.cityStateInfluence?.toString() ?: "null"),
        rawField("cityStateAlly", civ.cityStateAlly?.toString() ?: "null")
    )

    private fun encodeWarStatus(war: LlmWarStatus): String = obj(
        field("enemyCivName", war.enemyCivName),
        rawField("turnsSinceWarDeclaration", war.turnsSinceWarDeclaration?.toString() ?: "null"),
        rawField("warDeclaredByUs", war.warDeclaredByUs.toString()),
        rawField("knownEnemyAlliesAtWar", stringArray(war.knownEnemyAlliesAtWar)),
        rawField("canMakePeace", war.canMakePeace.toString())
    )

    private fun encodeTradeSummary(trade: LlmTradeSummary): String = obj(
        field("partnerCivName", trade.partnerCivName),
        rawField("outgoing", array(trade.outgoing, ::encodeTradeOfferBrief)),
        rawField("incoming", array(trade.incoming, ::encodeTradeOfferBrief)),
        rawField("turnsRemainingMin", trade.turnsRemainingMin?.toString() ?: "null")
    )

    private fun encodeTradeOfferBrief(offer: LlmTradeOfferBrief): String = obj(
        field("type", offer.type),
        field("name", offer.name),
        rawField("amount", offer.amount.toString())
    )

    private fun encodeThreats(threats: LlmThreats): String = obj(
        rawField("warCount", threats.warCount.toString()),
        rawField("denunciationCount", threats.denunciationCount.toString()),
        rawField("recentDeclarationsAgainstUs", threats.recentDeclarationsAgainstUs.toString()),
        rawField("nearbyHostileMilitaryCount", threats.nearbyHostileMilitaryCount.toString()),
        rawField("cityStatesAtWarWithUs", threats.cityStatesAtWarWithUs.toString())
    )

    private fun encodeRelativeStanding(standing: LlmRelativeStanding): String = obj(
        rawField("score", encodeRankingEntry(standing.score)),
        rawField("population", encodeRankingEntry(standing.population)),
        rawField("growth", encodeRankingEntry(standing.growth)),
        rawField("production", encodeRankingEntry(standing.production)),
        rawField("gold", encodeRankingEntry(standing.gold)),
        rawField("territory", encodeRankingEntry(standing.territory)),
        rawField("force", encodeRankingEntry(standing.force)),
        rawField("happiness", encodeRankingEntry(standing.happiness)),
        rawField("technologies", encodeRankingEntry(standing.technologies)),
        rawField("culture", encodeRankingEntry(standing.culture))
    )

    private fun encodeRankingEntry(entry: LlmRankingEntry): String = obj(
        rawField("ourValue", entry.ourValue.toString()),
        rawField("rank", entry.rank.toString()),
        rawField("totalKnownMajors", entry.totalKnownMajors.toString()),
        rawField("leaderValue", entry.leaderValue.toString())
    )

    private fun encodeLlmCallTrace(trace: LlmCallTrace): String = obj(
        field("clientId", trace.clientId),
        field("requestKind", trace.requestKind),
        field("systemPromptHash", trace.systemPromptHash),
        field("userPromptHash", trace.userPromptHash),
        rawField("inputTokens", trace.inputTokens.toString()),
        rawField("outputTokens", trace.outputTokens.toString()),
        rawField("cacheReadTokens", trace.cacheReadTokens.toString()),
        rawField("latencyMs", trace.latencyMs.toString()),
        field("stopReason", trace.stopReason),
        rawField("parsedSuccessfully", trace.parsedSuccessfully.toString()),
        field("parsePath", trace.parsePath),
        rawField("fallbackUsed", trace.fallbackUsed?.let { quote(it) } ?: "null"),
        rawField("rationale", trace.rationale?.let { quote(it) } ?: "null"),
        rawField("systemPrompt", trace.systemPrompt?.let { quote(it) } ?: "null"),
        rawField("userPrompt", trace.userPrompt?.let { quote(it) } ?: "null")
    )

    private fun field(name: String, value: String) = rawField(name, quote(value))
    private fun rawField(name: String, value: String) = "${quote(name)}:$value"
    private fun obj(vararg fields: String) = fields.joinToString(prefix = "{", postfix = "}")
    private fun <T> array(items: List<T>, encoder: (T) -> String) = items.joinToString(prefix = "[", postfix = "]", transform = encoder)
    private fun stringArray(items: List<String>) = items.joinToString(prefix = "[", postfix = "]") { quote(it) }
    private fun intMap(items: Map<String, Int>) = items.toSortedMap().entries.joinToString(prefix = "{", postfix = "}") {
        rawField(it.key, it.value.toString())
    }

    private fun quote(value: String): String = buildString {
        append('"')
        for (char in value) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (char < ' ') append("\\u%04x".format(char.code))
                    else append(char)
                }
            }
        }
        append('"')
    }

    companion object {
        const val SCHEMA = "llm-trace/v1"
    }
}
