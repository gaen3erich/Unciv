package com.unciv.logic.llm

import com.unciv.logic.automation.civilization.NextTurnAutomation
import com.unciv.logic.automation.unit.BattleHelper
import com.unciv.logic.automation.unit.HeadTowardsEnemyCityAutomation
import com.unciv.logic.automation.unit.UnitAutomation
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.unique.UniqueType

/**
 * Executes the agent's operational orders through the existing unit automation.
 *
 * The agent never moves single units: it picks one order per group (attack a city, defend a city,
 * found a city with a given settler, or "run the rest"). Each unit takes at most one order per
 * gateway step; units ordered earlier in the step are skipped by later orders.
 */
class LlmUnitCommander {
    private val orderedUnitIds = HashSet<Int>()

    fun beginStep() = orderedUnitIds.clear()

    fun runAutomation(civ: Civilization): Int {
        val isAtWar = civ.isAtWar()
        // Same ordering as the AI turn: settlers and civilians first, wounded units before healthy ones
        val units = takeUnordered(civ.units.getCivUnits().toList())
            .sortedBy { NextTurnAutomation.getUnitPriority(it, isAtWar) }
        for (unit in units) {
            if (unit.isDestroyed) continue
            UnitAutomation.automateUnitMoves(unit)
        }
        return units.size
    }

    fun attackCity(civ: Civilization, target: City): Int {
        val targetTile = target.getCenterTile()
        val units = takeUnordered(armyUnits(civ))
        for (unit in units) {
            if (unit.isDestroyed) continue
            if (unit.health < WOUNDED_HEALTH) {
                UnitAutomation.automateUnitMoves(unit) // heal or retreat
                continue
            }
            if (BattleHelper.tryAttackNearbyEnemy(unit)) continue
            if (!unit.hasMovement()) continue
            val path = if (unit.movement.canReach(targetTile)) unit.movement.getShortestPath(targetTile) else emptyList()
            if (path.isEmpty()) {
                UnitAutomation.automateUnitMoves(unit)
                continue
            }
            HeadTowardsEnemyCityAutomation.headTowardsEnemyCity(unit, targetTile, path)
            if (!unit.isDestroyed && unit.hasMovement()) BattleHelper.tryAttackNearbyEnemy(unit)
        }
        return units.size
    }

    fun defendCity(civ: Civilization, city: City): Int {
        val cityTile = city.getCenterTile()
        val units = takeUnordered(armyUnits(civ))
        for (unit in units) {
            if (unit.isDestroyed) continue
            if (BattleHelper.tryAttackNearbyEnemy(unit)) continue
            if (!unit.hasMovement()) continue
            when {
                cityTile.militaryUnit == null && unit.movement.canMoveTo(cityTile) && unit.movement.canReach(cityTile) ->
                    unit.movement.headTowards(cityTile)
                unit.getTile().aerialDistanceTo(cityTile) > DEFEND_RADIUS && unit.movement.canReach(cityTile) ->
                    unit.movement.headTowards(cityTile)
            }
            if (!unit.isDestroyed && unit.getTile().aerialDistanceTo(cityTile) <= DEFEND_RADIUS) unit.fortifyIfCan()
        }
        return units.size
    }

    /** @return null on success, or the reason the order could not be carried out at all */
    fun foundCity(civ: Civilization, unit: MapUnit, site: Tile): String? {
        if (!orderedUnitIds.add(unit.id)) return "Unit already has an order this turn: ${unit.name}"
        if (unit.getTile() != site) {
            if (!unit.movement.canReach(site)) return "Settler cannot reach (${site.position.x},${site.position.y})"
            unit.movement.headTowards(site)
        }
        if (unit.getTile() == site && unit.hasMovement() && canFoundCityOn(civ, unit, site)) {
            civ.addCity(site.position, unit)
            unit.destroy()
        }
        return null
    }

    private fun takeUnordered(units: List<MapUnit>): List<MapUnit> = units.filter { orderedUnitIds.add(it.id) }

    private fun armyUnits(civ: Civilization): List<MapUnit> = civ.units.getCivUnits()
        .filter { isArmyUnit(it) }
        .sortedBy { NextTurnAutomation.getUnitPriority(it, true) }
        .toList()

    companion object {
        private const val WOUNDED_HEALTH = 50
        private const val DEFEND_RADIUS = 2

        fun isArmyUnit(unit: MapUnit) =
            unit.isMilitary() && !unit.baseUnit.isAirUnit() && !unit.isNuclearWeapon()

        fun canFoundCity(civ: Civilization, unit: MapUnit) =
            unit.hasUnique(UniqueType.FoundCity)
                && !(civ.isOneCityChallenger() && civ.hasEverOwnedOriginalCapital)

        fun canFoundCityOn(civ: Civilization, unit: MapUnit, tile: Tile) =
            canFoundCity(civ, unit) && !tile.isWater && !tile.isImpassible() && tile.canBeSettled(civ)
    }
}
