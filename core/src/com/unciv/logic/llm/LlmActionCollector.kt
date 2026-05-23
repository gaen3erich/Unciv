package com.unciv.logic.llm

import com.unciv.logic.GameInfo
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.ruleset.IConstruction
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.stats.Stats
import com.unciv.ui.screens.victoryscreen.RankingType
import kotlin.math.ceil
import kotlin.math.roundToInt

class LlmActionCollector {
    fun buildObservation(gameInfo: GameInfo, civ: Civilization = gameInfo.getCurrentPlayerCivilization()): LlmObservation {
        val legalActions = collectLegalActions(gameInfo, civ)
        val cities = civ.cities.sortedBy { cityIdFor(civ, it) }.map { city ->
            LlmCityObservation(
                cityId = cityIdFor(civ, city),
                name = city.name,
                population = city.population.population,
                // promoted to economy block in v2
                currentConstruction = city.cityConstructions.currentConstructionName().ifEmpty { null },
                // promoted to economy block in v2
                productionPerTurn = city.cityStats.currentCityStats.production.roundToInt(),
                queue = city.cityConstructions.constructionQueue.toList(),
                location = collectLocation(city),
                health = collectHealth(city),
                defenders = collectDefenders(civ, city),
                visibleEnemies = collectVisibleEnemies(civ, city),
                workableTilesSummary = collectWorkableTilesSummary(civ, city),
                expansionPressure = collectExpansionPressure(gameInfo, civ, city)
            )
        }
        return LlmObservation(
            gameId = gameInfo.gameId,
            turn = gameInfo.turns,
            currentPlayerCivId = civ.civID,
            currentPlayerCivName = civ.civName,
            isOnlineMultiplayer = gameInfo.gameParameters.isOnlineMultiplayer,
            currentResearch = civ.tech.currentTechnologyName(),
            sciencePerTurn = civ.stats.statsForNextTurn.science.roundToInt(),
            cities = cities,
            researchedTechs = gameInfo.ruleset.technologies.keys
                .filter { civ.tech.isResearched(it) }
                .sorted(),
            legalActions = legalActions,
            economy = collectEconomy(civ),
            resources = collectResources(gameInfo, civ),
            diplomacy = collectDiplomacy(civ, cities)
        )
    }

    private fun collectLocation(city: City) = LlmHexCoord(city.location.x, city.location.y)

    private fun collectHealth(city: City): LlmCityHealth {
        val maxHealth = city.getMaxHealth()
        return LlmCityHealth(
            current = city.health,
            max = maxHealth,
            isUnderAttack = city.health < maxHealth,
            isCritical = city.health <= maxHealth / 4
        )
    }

    private fun collectDefenders(civ: Civilization, city: City): LlmCityDefenders {
        val center = city.getCenterTile()
        val garrisonUnit = center.militaryUnit
            ?.takeIf { it.civ == civ && it.isMilitary() }
        val supportingUnits = center.getTilesInDistance(SUPPORTING_UNIT_RADIUS)
            .asSequence()
            .flatMap { it.getUnits().asSequence() }
            .filter { it.civ == civ && it.isMilitary() && it != garrisonUnit }
            .sortedWith(compareBy<MapUnit> { it.baseUnit.name }.thenBy { it.id })
            .toList()

        return LlmCityDefenders(
            garrison = garrisonUnit?.toBrief(),
            supportingUnitCount = supportingUnits.size,
            supportingUnitTypeBreakdown = supportingUnits
                .groupingBy { it.baseUnit.name }
                .eachCount()
                .toSortedMap()
        )
    }

    private fun collectVisibleEnemies(civ: Civilization, city: City): LlmVisibleEnemies {
        val center = city.getCenterTile()
        val visibleEnemyUnits = center.getTilesInDistance(VISIBLE_ENEMY_RADIUS)
            .asSequence()
            .filter { it in civ.viewableTiles }
            .flatMap { it.getUnits().asSequence() }
            .filter { it.civ.isAtWarWith(civ) && it.isMilitary() }
            .sortedWith(
                compareBy<MapUnit> { it.getTile().aerialDistanceTo(center) }
                    .thenBy { it.id }
                    .thenBy { it.civ.civName }
                    .thenBy { it.baseUnit.name }
            )
            .toList()

        return LlmVisibleEnemies(
            totalCount = visibleEnemyUnits.size,
            closestDistance = visibleEnemyUnits.minOfOrNull { it.getTile().aerialDistanceTo(center) },
            byCiv = visibleEnemyUnits
                .groupingBy { it.civ.civName }
                .eachCount()
                .toSortedMap()
                .map { LlmEnemyCivCount(it.key, it.value) },
            brief = visibleEnemyUnits
                .take(VISIBLE_ENEMY_BRIEF_LIMIT)
                .map { it.toBrief() }
        )
    }

    private fun collectWorkableTilesSummary(civ: Civilization, city: City): LlmWorkableTilesSummary {
        val workableTiles = city.getWorkableTiles().toList()
        val yieldSum = Stats()
        for (tile in workableTiles) {
            yieldSum.add(tile.stats.getTileStats(city, civ))
        }

        return LlmWorkableTilesSummary(
            totalTiles = workableTiles.size,
            workedTiles = workableTiles.count { city.isWorked(it) },
            totalYields = LlmTileYields(
                food = yieldSum.food.roundToInt(),
                production = yieldSum.production.roundToInt(),
                gold = yieldSum.gold.roundToInt(),
                science = yieldSum.science.roundToInt(),
                culture = yieldSum.culture.roundToInt(),
                faith = yieldSum.faith.roundToInt(),
                happiness = yieldSum.happiness.roundToInt()
            ),
            resourceCounts = workableTiles
                .mapNotNull { visibleResourceName(civ, it) }
                .groupingBy { it }
                .eachCount()
                .toSortedMap(),
            improvementCounts = workableTiles
                .mapNotNull { it.improvement }
                .groupingBy { it }
                .eachCount()
                .toSortedMap()
        )
    }

    private fun visibleResourceName(civ: Civilization, tile: Tile): String? {
        val resource = tile.tileResource ?: return null
        if (!civ.canSeeResource(resource)) return null
        return tile.resource
    }

    private fun collectExpansionPressure(gameInfo: GameInfo, civ: Civilization, city: City): LlmExpansionPressure {
        val culturePerTurn = city.cityStats.currentCityStats.culture
        val cultureNeeded = city.expansion.getCultureToNextTile() - city.expansion.cultureStored
        val turnsToNextTile = if (culturePerTurn <= 0f) null
            else ceil(cultureNeeded / culturePerTurn).toInt().coerceAtLeast(1)
        val center = city.getCenterTile()
        val nearestForeignCity = gameInfo.getCities()
            .filter { it.civ != civ }
            .minWithOrNull(
                compareBy<City> { it.getCenterTile().aerialDistanceTo(center) }
                    .thenBy { it.civ.civName }
                    .thenBy { it.name }
            )

        return LlmExpansionPressure(
            cultureStored = city.expansion.cultureStored,
            turnsToNextTile = turnsToNextTile,
            ownedTiles = city.getTiles().count(),
            foreignBorderTiles = city.getTiles()
                .flatMap { it.neighbors }
                .distinct()
                .count { it.getOwner() != null && it.getOwner() != civ },
            nearestForeignCity = nearestForeignCity?.let {
                LlmNearbyCity(
                    civName = it.civ.civName,
                    cityName = it.name,
                    distance = it.getCenterTile().aerialDistanceTo(center)
                )
            }
        )
    }

    private fun MapUnit.toBrief() = LlmUnitBrief(
        name = baseUnit.name,
        healthPercent = health,
        isInjured = health < 100,
        movementLeft = currentMovement.toInt()
    )

    private fun collectEconomy(civ: Civilization): LlmEconomy {
        val statsForNextTurn = civ.stats.statsForNextTurn
        return LlmEconomy(
            gold = civ.gold,
            storedCulture = civ.policies.storedCulture,
            storedFaith = civ.religionManager.storedFaith,
            happiness = civ.getHappiness(),
            goldPerTurn = statsForNextTurn.gold.roundToInt(),
            sciencePerTurn = statsForNextTurn.science.roundToInt(),
            culturePerTurn = statsForNextTurn.culture.roundToInt(),
            faithPerTurn = statsForNextTurn.faith.roundToInt(),
            happinessPerTurn = statsForNextTurn.happiness.roundToInt()
        )
    }

    private fun collectResources(gameInfo: GameInfo, civ: Civilization): LlmResources {
        val strategic = ArrayList<LlmResourceEntry>()
        val luxury = ArrayList<LlmResourceEntry>()
        for ((resourceName, amount) in civ.getCivResourcesByName()) {
            if (amount == 0) continue
            when (gameInfo.ruleset.tileResources[resourceName]?.resourceType) {
                ResourceType.Strategic -> strategic += LlmResourceEntry(resourceName, amount)
                ResourceType.Luxury -> luxury += LlmResourceEntry(resourceName, amount)
                else -> {}
            }
        }
        return LlmResources(
            strategic = strategic.sortedBy { it.name },
            luxury = luxury.sortedBy { it.name }
        )
    }

    private fun collectDiplomacy(civ: Civilization, cities: List<LlmCityObservation>): LlmDiplomacy {
        return LlmDiplomacy(
            knownCivs = collectKnownCivs(civ),
            activeWars = collectActiveWars(civ),
            activeTrades = collectActiveTrades(civ),
            threats = collectThreats(civ, cities),
            relativeStanding = collectRelativeStanding(civ)
        )
    }

    private fun collectKnownCivs(civ: Civilization): List<LlmKnownCiv> {
        return civ.diplomacy.values
            .asSequence()
            .filter { !it.otherCiv.isDefeated() }
            .sortedBy { it.otherCiv.civName }
            .map { diplomacyManager ->
                val other = diplomacyManager.otherCiv
                LlmKnownCiv(
                    civName = other.civName,
                    isMajorCiv = other.isMajorCiv(),
                    isCityState = other.isCityState,
                    isDefeated = other.isDefeated(),
                    diplomaticStatus = diplomacyManager.diplomaticStatus.name,
                    relationshipLevel = diplomacyManager.relationshipLevel().name,
                    opinionScore = if (other.isCityState) null
                        else diplomacyManager.diplomaticModifiers.values.sum().roundToInt(),
                    hasOpenBorders = diplomacyManager.hasOpenBorders,
                    theirsHaveOpenBorders = diplomacyManager.otherCivDiplomacy().hasOpenBorders,
                    hasDeclarationOfFriendship = diplomacyManager.hasFlag(DiplomacyFlags.DeclarationOfFriendship),
                    hasDefensivePact = diplomacyManager.diplomaticStatus == DiplomaticStatus.DefensivePact,
                    hasResearchAgreement = diplomacyManager.hasFlag(DiplomacyFlags.ResearchAgreement),
                    cityStateInfluence = if (other.isCityState) diplomacyManager.getInfluence().toInt() else null,
                    cityStateAlly = if (other.isCityState) null else null
                )
            }
            .toList()
    }

    private fun collectActiveWars(civ: Civilization): List<LlmWarStatus> {
        return civ.diplomacy.values
            .asSequence()
            .filter { it.diplomaticStatus == DiplomaticStatus.War && !it.otherCiv.isDefeated() }
            .sortedBy { it.otherCiv.civName }
            .map { diplomacyManager ->
                LlmWarStatus(
                    enemyCivName = diplomacyManager.otherCiv.civName,
                    turnsSinceWarDeclaration = null,
                    warDeclaredByUs = diplomacyManager.hasFlag(DiplomacyFlags.DeclaredWar),
                    knownEnemyAlliesAtWar = emptyList(),
                    canMakePeace = !diplomacyManager.hasFlag(DiplomacyFlags.DeclinedPeace)
                )
            }
            .toList()
    }

    private fun collectActiveTrades(civ: Civilization): List<LlmTradeSummary> {
        return civ.diplomacy.values
            .asSequence()
            .filter { it.trades.isNotEmpty() && !it.otherCiv.isDefeated() }
            .sortedBy { it.otherCiv.civName }
            .map { diplomacyManager ->
                val outgoingOffers = diplomacyManager.trades.flatMap { it.ourOffers }
                val incomingOffers = diplomacyManager.trades.flatMap { it.theirOffers }
                LlmTradeSummary(
                    partnerCivName = diplomacyManager.otherCiv.civName,
                    outgoing = outgoingOffers.map { it.toBrief() }.sortedWith(tradeOfferBriefComparator),
                    incoming = incomingOffers.map { it.toBrief() }.sortedWith(tradeOfferBriefComparator),
                    turnsRemainingMin = (outgoingOffers + incomingOffers)
                        .mapNotNull { if (it.duration > 0) it.duration else null }
                        .minOrNull()
                )
            }
            .toList()
    }

    private fun TradeOffer.toBrief() = LlmTradeOfferBrief(
        type = type.name,
        name = name,
        amount = if (type.numberType == TradeOfferType.TradeTypeNumberType.None) 0 else amount
    )

    private fun collectThreats(civ: Civilization, cities: List<LlmCityObservation>): LlmThreats {
        return LlmThreats(
            warCount = civ.diplomacy.values.count {
                it.diplomaticStatus == DiplomaticStatus.War && !it.otherCiv.isDefeated()
            },
            denunciationCount = civ.diplomacy.values.count {
                !it.otherCiv.isDefeated() &&
                    (it.hasFlag(DiplomacyFlags.Denunciation)
                        || it.otherCivDiplomacy().hasFlag(DiplomacyFlags.Denunciation))
            },
            recentDeclarationsAgainstUs = civ.diplomacy.values.count {
                it.diplomaticStatus == DiplomaticStatus.War
                    && !it.otherCiv.isDefeated()
                    && it.otherCivDiplomacy().hasFlag(DiplomacyFlags.DeclaredWar)
            },
            nearbyHostileMilitaryCount = cities.sumOf { it.visibleEnemies.totalCount },
            cityStatesAtWarWithUs = civ.diplomacy.values.count {
                it.otherCiv.isCityState && it.diplomaticStatus == DiplomaticStatus.War && !it.otherCiv.isDefeated()
            }
        )
    }

    private fun collectRelativeStanding(civ: Civilization): LlmRelativeStanding {
        return LlmRelativeStanding(
            score = collectRanking(civ, RankingType.Score),
            population = collectRanking(civ, RankingType.Population),
            growth = collectRanking(civ, RankingType.Growth),
            production = collectRanking(civ, RankingType.Production),
            gold = collectRanking(civ, RankingType.Gold),
            territory = collectRanking(civ, RankingType.Territory),
            force = collectRanking(civ, RankingType.Force),
            happiness = collectRanking(civ, RankingType.Happiness),
            technologies = collectRanking(civ, RankingType.Technologies),
            culture = collectRanking(civ, RankingType.Culture)
        )
    }

    private fun collectRanking(civ: Civilization, category: RankingType): LlmRankingEntry {
        val cohort = (civ.diplomacy.values.asSequence()
            .map { it.otherCiv }
            .filter { it.isMajorCiv() && !it.isDefeated() }
            .toList() + civ)
            .distinctBy { it.civID }
        val pairs = cohort.map { it to it.getStatForRanking(category) }
        val sorted = pairs.sortedWith(compareByDescending<Pair<Civilization, Int>> { it.second }.thenBy { it.first.civName })
        val ourPair = pairs.first { it.first == civ }
        return LlmRankingEntry(
            ourValue = ourPair.second,
            rank = sorted.indexOf(ourPair) + 1,
            totalKnownMajors = cohort.size,
            leaderValue = sorted.firstOrNull()?.second ?: ourPair.second
        )
    }

    fun collectLegalActions(gameInfo: GameInfo, civ: Civilization = gameInfo.getCurrentPlayerCivilization()): List<LlmLegalAction> {
        if (gameInfo.gameParameters.isOnlineMultiplayer) return emptyList()

        val actions = ArrayList<LlmLegalAction>()
        for (technology in gameInfo.ruleset.technologies.values.sortedBy { it.name }) {
            if (!civ.tech.canBeResearched(technology.name)) continue
            actions += LlmLegalAction.ChooseTechnology(
                id = chooseTechnologyId(technology.name),
                techName = technology.name,
                path = listOf(technology.name)
            )
        }

        for (city in civ.cities.sortedBy { cityIdFor(civ, it) }) {
            val cityId = cityIdFor(civ, city)
            val constructions = (city.getRuleset().buildings.values.asSequence() + city.getRuleset().units.values.asSequence())
                .filter { city.cityConstructions.canAddToQueue(it) }
                .sortedBy { it.name }
                .toList()
            for (construction in constructions) {
                actions += LlmLegalAction.ChooseCityConstruction(
                    id = chooseCityConstructionId(cityId, construction.name),
                    cityId = cityId,
                    cityName = city.name,
                    constructionName = construction.name
                )
            }
        }

        actions += LlmLegalAction.EndTurn()
        return actions
    }

    companion object {
        private const val SUPPORTING_UNIT_RADIUS = 2
        private const val VISIBLE_ENEMY_RADIUS = 5
        private const val VISIBLE_ENEMY_BRIEF_LIMIT = 5
        private val tradeOfferBriefComparator = compareBy<LlmTradeOfferBrief> { it.type }
            .thenBy { it.name }
            .thenBy { it.amount }

        fun chooseTechnologyId(techName: String) = "choose_technology:$techName"
        fun chooseCityConstructionId(cityId: String, constructionName: String) =
            "choose_city_construction:$cityId:$constructionName"
        fun cityIdFor(civ: Civilization, city: City): String =
            if (city.id != City.NO_ID) city.id
            else "${civ.civName}/${city.location.x},${city.location.y}"
    }
}

private operator fun Sequence<IConstruction>.plus(other: Sequence<IConstruction>): Sequence<IConstruction> =
    sequence {
        yieldAll(this@plus)
        yieldAll(other)
    }
