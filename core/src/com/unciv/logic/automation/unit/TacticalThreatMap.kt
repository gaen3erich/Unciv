package com.unciv.logic.automation.unit

import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.ICombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import yairm210.purity.annotations.Readonly

/**
 * Who can hit which tile on the enemies' next turn, for [com.unciv.logic.automation.AiFeature.UnitTactics].
 *
 * Built from the visible enemy military units and the known enemy cities of civs we are at war with.
 * A unit threatens every tile within `(max movement - 1) + range` (aerial distance, so terrain is ignored
 * and the estimate errs on the cautious side); a city threatens its bombard range.
 *
 * The map is rebuilt whenever [Civilization.viewableTiles] is replaced (that is, after our units moved and saw
 * something new) or the turn changes. Enemies killed during our turn are skipped when queried.
 */
class TacticalThreatMap private constructor(private val civ: Civilization) {

    private inner class Attacker(val unit: MapUnit?, val city: City?, val origin: Tile, val reach: Int, val isMelee: Boolean) {
        @Readonly fun isGone(): Boolean = if (unit != null) unit.isDestroyed || unit.currentTile != origin
            else !civ.isAtWarWith(city!!.civ) || city.isInResistance()
        @Readonly fun combatant(): ICombatant = if (unit != null) MapUnitCombatant(unit) else CityCombatant(city!!)
    }

    private val attackers = ArrayList<Attacker>()
    private val turn = civ.gameInfo.turns
    private val builtFor = civ.viewableTiles

    init {
        for (tile in civ.viewableTiles) {
            for (enemy in tile.getUnits()) {
                if (!enemy.isMilitary() || !civ.isAtWarWith(enemy.civ) || enemy.isInvisible(civ)) continue
                if (enemy.baseUnit.isAirUnit()) continue // air units are handled (badly) by interception, not here
                val isMelee = !enemy.baseUnit.isRanged()
                val reach = (enemy.getMaxMovement() - 1).coerceAtLeast(0) + enemy.getRange()
                attackers += Attacker(enemy, null, tile, reach, isMelee)
            }
        }
        for (enemyCiv in civ.getCivsAtWarWith()) {
            for (city in enemyCiv.cities) {
                val center = city.getCenterTile()
                if (!center.isExplored(civ)) continue
                attackers += Attacker(null, city, center, city.getBombardRange(), false)
            }
        }
    }

    @Readonly
    fun isFresh(): Boolean = turn == civ.gameInfo.turns && builtFor === civ.viewableTiles

    /**
     * Expected damage [unit] would take on [tile] during the enemies' next turn.
     * Melee attackers are capped at the [MAX_MELEE_ATTACKERS] strongest, since only so many can stand next to one tile.
     * @param ignore an enemy (unit or city) on this tile is not counted, e.g. because we are about to kill it
     */
    @Readonly
    fun expectedDamage(unit: MapUnit, tile: Tile, ignore: Tile? = null): Int {
        if (attackers.isEmpty()) return 0
        val defender = MapUnitCombatant(unit)
        var ranged = 0
        // The MAX_MELEE_ATTACKERS = 3 strongest melee hits, strongest first
        var melee1 = 0
        var melee2 = 0
        var melee3 = 0
        for (attacker in attackers) {
            if (attacker.origin == ignore) continue
            if (attacker.origin.aerialDistanceTo(tile) > attacker.reach) continue
            if (attacker.origin == tile) continue
            if (attacker.isGone()) continue
            val damage = BattleDamage.calculateDamageToDefender(attacker.combatant(), defender, randomnessFactor = 0.5f)
            if (!attacker.isMelee) ranged += damage
            else if (damage > melee1) { melee3 = melee2; melee2 = melee1; melee1 = damage }
            else if (damage > melee2) { melee3 = melee2; melee2 = damage }
            else if (damage > melee3) melee3 = damage
        }
        return ranged + melee1 + melee2 + melee3
    }

    /** Whether [unit] would probably die on [tile] if it ended its turn there with [healthLeft] health */
    @Readonly
    fun isDeadly(unit: MapUnit, tile: Tile, healthLeft: Int = unit.health, ignore: Tile? = null): Boolean =
        expectedDamage(unit, tile, ignore) >= healthLeft

    companion object {
        const val MAX_MELEE_ATTACKERS = 3

        private val cache = java.util.WeakHashMap<Civilization, TacticalThreatMap>()

        /** Drops the cached map of [civ]; used for throwaway copies of the game, since a cached map keeps its civ alive */
        fun forget(civ: Civilization) = synchronized(cache) { cache.remove(civ) }

        /** The current threat map of [civ], rebuilt when stale */
        @Readonly @Suppress("purity") // the cache is invisible to callers
        fun of(civ: Civilization): TacticalThreatMap = synchronized(cache) {
            val cached = cache[civ]
            if (cached != null && cached.isFresh()) return cached
            val fresh = TacticalThreatMap(civ)
            cache[civ] = fresh
            fresh
        }
    }
}
