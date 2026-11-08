package com.unciv.logic.automation.commander

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile

/**
 * One battle as the code sees it: a connected group of own and enemy forces that can hit each other within a turn or so.
 *
 * @property ownUnits own military units in the fight
 * @property enemyUnits visible enemy military units (fog of war is respected: nothing the civ cannot see)
 * @property ownCities own cities enemy units can strike next turn
 * @property enemyCities known enemy cities our units in the fight can reach
 * @property reinforcements own military units outside the fight that can join within [CommanderConfig.reinforcementTurns]
 */
class Engagement(
    val index: Int,
    val ownUnits: List<MapUnit>,
    val enemyUnits: List<MapUnit>,
    val ownCities: List<City>,
    val enemyCities: List<City>,
    val reinforcements: List<MapUnit>,
) {
    val unitCount get() = ownUnits.size + enemyUnits.size
    val isOwnCityThreatened get() = ownCities.isNotEmpty()

    /** Battles with this many units or a threatened city go to the model, smaller skirmishes stay with UnitTactics */
    fun isImportant(minUnits: Int) = ownUnits.isNotEmpty() && (unitCount >= minUnits || isOwnCityThreatened)

    /** Ids of all units involved, used to recognise the same battle on the next turn */
    val unitIds: Set<Int> get() = (ownUnits + enemyUnits).map { it.id }.toSet()

    fun centerTile(): Tile {
        val tiles = (ownUnits + enemyUnits).map { it.getTile() } + (ownCities + enemyCities).map { it.getCenterTile() }
        val x = tiles.sumOf { it.position.x } / tiles.size
        val y = tiles.sumOf { it.position.y } / tiles.size
        return tiles.minBy { tile -> kotlin.math.abs(tile.position.x - x) + kotlin.math.abs(tile.position.y - y) }
    }
}

/**
 * Finds the battles a civilization is in.
 *
 * Every own and visible enemy military unit, own city and known enemy city is a node. An own unit and an enemy are
 * linked when one of them can hit the other within about a turn (reach = max movement - 1 + range, plus one tile
 * of slack, as in [com.unciv.logic.automation.unit.TacticalThreatMap]). An enemy unit is linked to an own city it
 * can strike, an own unit to an enemy city it can strike. Each connected group with both sides in it is one battle.
 */
object BattleDetector {

    /** Tiles within which [unit] can hit something by the end of its next turn */
    fun reach(unit: MapUnit): Int = (unit.getMaxMovement() - 1).coerceAtLeast(0) + unit.getRange()

    private const val SLACK = 1

    fun visibleEnemyUnits(civ: Civilization): List<MapUnit> {
        val result = ArrayList<MapUnit>()
        for (tile in civ.viewableTiles) {
            for (enemy in tile.getUnits()) {
                if (!enemy.isMilitary() || !civ.isAtWarWith(enemy.civ) || enemy.isInvisible(civ)) continue
                if (enemy.baseUnit.isAirUnit()) continue
                result += enemy
            }
        }
        return result
    }

    fun knownEnemyCities(civ: Civilization): List<City> = civ.getCivsAtWarWith()
        .flatMap { it.cities }
        .filter { it.getCenterTile().isExplored(civ) }

    private fun ownCombatUnits(civ: Civilization) = civ.units.getCivUnits()
        .filter { it.isMilitary() && !it.baseUnit.isAirUnit() && !it.isDestroyed }
        .toList()

    fun detect(civ: Civilization, reinforcementTurns: Int = 3): List<Engagement> {
        val own = ownCombatUnits(civ)
        val enemies = visibleEnemyUnits(civ)
        if (enemies.isEmpty() && civ.getCivsAtWarWith().none()) return emptyList()
        val enemyCities = knownEnemyCities(civ)
        val ownCities = civ.cities.toList()

        // Node layout: own units, enemy units, own cities, enemy cities
        val nodeCount = own.size + enemies.size + ownCities.size + enemyCities.size
        val parent = IntArray(nodeCount) { it }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }
            return x
        }
        fun union(a: Int, b: Int) { parent[find(a)] = find(b) }
        val enemyOffset = own.size
        val ownCityOffset = enemyOffset + enemies.size
        val enemyCityOffset = ownCityOffset + ownCities.size
        val linked = BooleanArray(nodeCount)
        fun link(a: Int, b: Int) { union(a, b); linked[a] = true; linked[b] = true }

        val ownReach = own.map { reach(it) }
        val enemyReach = enemies.map { reach(it) }
        for ((i, unit) in own.withIndex()) {
            val tile = unit.getTile()
            for ((j, enemy) in enemies.withIndex()) {
                if (tile.aerialDistanceTo(enemy.getTile()) <= maxOf(ownReach[i], enemyReach[j]) + SLACK)
                    link(i, enemyOffset + j)
            }
            for ((j, city) in enemyCities.withIndex()) {
                if (tile.aerialDistanceTo(city.getCenterTile()) <= ownReach[i] + SLACK)
                    link(i, enemyCityOffset + j)
            }
        }
        for ((j, enemy) in enemies.withIndex()) {
            val tile = enemy.getTile()
            for ((k, city) in ownCities.withIndex()) {
                if (tile.aerialDistanceTo(city.getCenterTile()) <= enemyReach[j] + SLACK)
                    link(enemyOffset + j, ownCityOffset + k)
            }
        }

        val groups = LinkedHashMap<Int, Group>()
        for (i in 0 until nodeCount) {
            if (!linked[i]) continue
            val group = groups.getOrPut(find(i)) { Group() }
            when {
                i < enemyOffset -> group.ownUnits += own[i]
                i < ownCityOffset -> group.enemyUnits += enemies[i - enemyOffset]
                i < enemyCityOffset -> group.ownCities += ownCities[i - ownCityOffset]
                else -> group.enemyCities += enemyCities[i - enemyCityOffset]
            }
        }
        val battles = groups.values.filter { (it.ownUnits.isNotEmpty() || it.ownCities.isNotEmpty()) &&
            (it.enemyUnits.isNotEmpty() || it.enemyCities.isNotEmpty()) }

        // Reinforcements: own units not in any fight, assigned to the nearest one they can join in time
        val engaged = battles.flatMap { it.ownUnits }.toSet()
        val reinforcements = battles.associateWith { ArrayList<MapUnit>() }
        for (unit in own) {
            if (unit in engaged) continue
            val maxDistance = unit.getMaxMovement() * reinforcementTurns + unit.getRange()
            val tile = unit.getTile()
            val nearest = battles.minByOrNull { distanceTo(tile, it) } ?: continue
            if (distanceTo(tile, nearest) <= maxDistance) reinforcements[nearest]!! += unit
        }

        return battles.mapIndexed { index, group ->
            Engagement(index + 1, group.ownUnits, group.enemyUnits, group.ownCities, group.enemyCities,
                reinforcements[group]!!)
        }
    }

    private class Group {
        val ownUnits = ArrayList<MapUnit>(); val enemyUnits = ArrayList<MapUnit>()
        val ownCities = ArrayList<City>(); val enemyCities = ArrayList<City>()
        fun tiles(): List<Tile> = (ownUnits + enemyUnits).map { it.getTile() } +
            (ownCities + enemyCities).map { it.getCenterTile() }
    }

    private fun distanceTo(tile: Tile, group: Group): Int = group.tiles().minOfOrNull { tile.aerialDistanceTo(it) } ?: Int.MAX_VALUE
}
