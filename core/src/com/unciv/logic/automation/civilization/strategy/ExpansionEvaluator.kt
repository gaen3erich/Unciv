package com.unciv.logic.automation.civilization.strategy

import com.unciv.logic.automation.Automation
import com.unciv.logic.automation.unit.CityLocationTileRanker
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import kotlin.math.max

/**
 * Answers "is the next settler worth more than developing the cities we have?".
 *
 * Both options are priced in the same currency, [Automation.rankStatsValue] per turn summed over
 * [HORIZON_TURNS], for the same amount of production (one settler):
 * - **New city**: the best free spot near our cities, ranked with the settler's own [CityLocationTileRanker]. Its
 *   expected yield (center tile plus the best tiles a young city works) counts from the turn the city would be
 *   founded (build time plus travel), discounted for danger nearby and for the unhappiness a city brings.
 * - **Development**: the same production put into the best building of the city that would build the settler.
 */
object ExpansionEvaluator {
    /** How far ahead both options are valued */
    const val HORIZON_TURNS = 50
    /** Citizens a new city works on average during the horizon */
    const val NEW_CITY_WORKED_TILES = 3
    /** How far from our cities we look for spots */
    const val SEARCH_RADIUS = 8

    class Decision(
        val expand: Boolean,
        /** Where the next city should go; null when nowhere is worth it */
        val target: Tile?,
        /** City that should build the settler */
        val builder: City?,
        val newCityValue: Float,
        val developValue: Float,
    ) {
        override fun toString() = "expand=$expand, new city ${"%.0f".format(newCityValue)} vs develop ${"%.0f".format(developValue)}" +
            (target?.let { " at ${it.position}" } ?: "")
    }

    private val NOTHING = Decision(false, null, null, 0f, 0f)

    fun evaluate(civ: Civilization): Decision {
        if (civ.cities.isEmpty()) return NOTHING
        val settler = cheapestSettler(civ) ?: return NOTHING
        val spots = rankFreeSpots(civ)
        if (spots.isEmpty()) return NOTHING

        val builders = civ.cities.filter { !it.isPuppet && it.population.population >= 2 }
        if (builders.isEmpty()) return NOTHING

        var best: Triple<Tile, City, Float>? = null
        for ((spot, rank) in spots) {
            for (city in builders) {
                val value = newCityValue(civ, city, spot, rank, settler)
                if (best == null || value > best.third) best = Triple(spot, city, value)
            }
        }
        val (target, builder, newCityValue) = best ?: return NOTHING
        val developValue = developValue(civ, builder, settler.getProductionCost(civ, builder).toFloat())
        return Decision(newCityValue > developValue, target, builder, newCityValue, developValue)
    }

    fun cheapestSettler(civ: Civilization): BaseUnit? = civ.gameInfo.ruleset.units.values
        .filter { it.isCityFounder() && it.isBuildable(civ) }
        .filter { unit -> civ.getPersonality().getMatchingUniques(UniqueType.WillNotBuild, civ.state)
            .none { unit.matchesFilter(it.params[0], civ.state) } }
        .minByOrNull { it.cost }

    /** Explored spots where we may found a city, with the settler's ranking, best first */
    fun rankFreeSpots(civ: Civilization): List<Pair<Tile, Float>> {
        val minimum = civ.gameInfo.ruleset.modOptions.constants.minimumCityLocationTileValue
        val baseTileMap = HashMap<Tile, Float>()
        val seen = HashSet<Tile>()
        val result = ArrayList<Pair<Tile, Float>>()
        for (city in civ.cities) {
            for (tile in city.getCenterTile().getTilesInDistanceRange(3..SEARCH_RADIUS)) {
                if (!seen.add(tile)) continue
                if (!tile.isExplored(civ) || !tile.canBeSettled(civ)) continue
                val rank = CityLocationTileRanker.rankLocationForCiv(tile, civ, baseTileMap) ?: continue
                if (rank >= minimum) result.add(tile to rank)
            }
        }
        return result.sortedByDescending { it.second }.take(10)
    }

    /** Yield of a city at [spot] over the horizon, built by [builder] */
    fun newCityValue(civ: Civilization, builder: City, spot: Tile, rank: Float, settler: BaseUnit): Float {
        val production = builder.cityStats.currentCityStats.production.coerceAtLeast(1f)
        val buildTurns = settler.getProductionCost(civ, builder) / production
        val travelTurns = builder.getCenterTile().aerialDistanceTo(spot) / max(1, settler.movement).toFloat()
        val productiveTurns = HORIZON_TURNS - buildTurns - travelTurns
        if (productiveTurns <= 0f) return 0f

        val tileValues = spot.getTilesInDistance(2)
            .filter { it != spot && it.getOwner().let { owner -> owner == null || owner == civ } }
            .map { Automation.rankStatsValue(it.stats.getTileStats(null, civ), civ) }
            .sortedDescending().take(NEW_CITY_WORKED_TILES).toList()
        // A city center yields at least 2 food, 1 production, 1 science/culture-ish extras
        val centerValue = Automation.rankStatsValue(Stats(food = 2f, production = 1f, science = 1f, culture = 1f), civ)
        var perTurn = centerValue + tileValues.sum()
        // The settler's own ranking captures rivers, hills, luxuries; use it as a mild quality factor around the minimum
        val minimum = civ.gameInfo.ruleset.modOptions.constants.minimumCityLocationTileValue
        perTurn *= (rank / minimum).coerceIn(0.8f, 1.5f)

        perTurn *= riskFactor(civ, spot)
        perTurn *= happinessFactor(civ)
        // Growth stops while a settler is built, which the development option doesn't pay
        val lostFood = builder.cityStats.currentCityStats.food.coerceAtLeast(0f) * buildTurns * 1.2f
        return perTurn * productiveTurns - lostFood
    }

    /** Foreign military or borders nearby make a new city less likely to survive and grow */
    private fun riskFactor(civ: Civilization, spot: Tile): Float {
        var factor = 1f
        val nearby = spot.getTilesInDistance(3).toList()
        if (nearby.any { tile -> tile.militaryUnit?.let { it.civ != civ && (it.civ.isBarbarian || it.civ.isAtWarWith(civ)) } == true })
            factor *= 0.5f
        if (nearby.any { it.getOwner().let { owner -> owner != null && owner != civ } }) factor *= 0.8f
        return factor
    }

    /** Each city costs happiness; when we would go unhappy the whole empire suffers */
    private fun happinessFactor(civ: Civilization): Float {
        val happinessAfter = civ.getHappiness() - 4
        return when {
            happinessAfter >= 0 -> 1f
            happinessAfter >= -5 -> 0.5f
            else -> 0.2f
        }
    }

    /** What [production] buys in [city] if it goes into the best available building instead */
    fun developValue(civ: Civilization, city: City, production: Float): Float {
        val cityProduction = city.cityStats.currentCityStats.production.coerceAtLeast(1f)
        var bestPerProduction = 0f
        for (building in civ.gameInfo.ruleset.buildings.values) {
            if (building.isAnyWonder()) continue
            if (!building.isBuildable(city.cityConstructions)) continue
            val cost = building.getProductionCost(civ, city).toFloat().coerceAtLeast(1f)
            val stats = building.getStats(city)
            val percent = building.getStatPercentageBonuses(city)
            val current = city.cityStats.currentCityStats
            for (stat in Stat.entries) stats[stat] += current[stat] * percent[stat] / 100f
            stats.gold -= building.maintenance
            val perTurn = Automation.rankStatsValue(stats, civ)
            if (perTurn / cost > bestPerProduction) bestPerProduction = perTurn / cost
        }
        // Buildings start paying back as soon as they are done
        val productiveTurns = HORIZON_TURNS - production / cityProduction
        return (bestPerProduction * production * productiveTurns).coerceAtLeast(0f)
    }
}
