package com.unciv.logic.automation.unit

import com.unciv.logic.automation.AiFeature
import com.unciv.logic.battle.AttackableTile
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.city.City
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.movement.UnitMovement
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.unique.UniqueType
import yairm210.purity.annotations.Readonly
import kotlin.math.pow

/**
 * Unit tactics on top of [UnitAutomation], switched on by [AiFeature.UnitTactics].
 *
 * Benchmarks showed AI units mostly die on the enemy's turn, not in their own attacks: they end their turn
 * next to enemy cities and armies. These helpers use [TacticalThreatMap] to
 * - skip attacks that leave the attacker on a tile where it will be killed,
 * - step off deadly tiles when there is movement left,
 * - stop short of deadly tiles when advancing,
 * - wait at a staging area near an enemy city until the local army outnumbers its defenders.
 */
object TacticalAutomation {

    /** Our force near a city must be this many times the enemy force there before we close in */
    private const val REQUIRED_LOCAL_SUPERIORITY = 1.5f

    @Readonly
    fun isActive(unit: MapUnit): Boolean = unit.civ.aiProfile.has(AiFeature.UnitTactics)

    /**
     * Whether attacking [target] is acceptable given what happens on the enemy turn.
     * Attacks that kill the defender or capture a city are always fine; others must leave the attacker alive.
     */
    @Readonly
    fun isAttackSafeEnough(unit: MapUnit, target: AttackableTile): Boolean {
        if (unit.hasUnique(UniqueType.SelfDestructs)) return true
        val defender = Battle.getMapCombatantOfTile(target.tileToAttack) ?: return true
        val attacker = MapUnitCombatant(unit)
        val damageDealt = BattleDamage.calculateDamageToDefender(attacker, defender, target.tileToAttackFrom, 0.5f)
        val damageTaken = BattleDamage.calculateDamageToAttacker(attacker, defender, target.tileToAttackFrom, 0.5f)
        val killsDefender = damageDealt >= defender.getHealth()
        val canCapture = unit.baseUnit.isMelee() && !unit.hasUnique(UniqueType.CannotCaptureCities)
        if (defender is CityCombatant && killsDefender && canCapture) return true

        val healthLeft = unit.health - damageTaken
        // Melee units that kill a unit move into its tile; everyone else stays where they attacked from
        val endTile = if (killsDefender && unit.baseUnit.isMelee() && defender is MapUnitCombatant
                && target.tileToAttack.militaryUnit != null) target.tileToAttack
            else target.tileToAttackFrom
        val threats = TacticalThreatMap.of(unit.civ)
        val ignore = if (killsDefender && defender is MapUnitCombatant) target.tileToAttack else null
        if (!threats.isDeadly(unit, endTile, healthLeft, ignore)) return true
        // We will probably die anyway. Trading ourselves for a kill is fine; for chip damage it is not
        return killsDefender
    }

    /** If the unit stands on a tile where it will probably be killed, move to the safest tile it can reach. */
    fun tryEscapeDeadlyTile(unit: MapUnit): Boolean {
        if (!unit.hasMovement()) return false
        val current = unit.getTile()
        // Garrisons defend with the city, leaving makes it easier to take
        if (current.isCityCenter() && current.getOwner() == unit.civ) return false
        val threats = TacticalThreatMap.of(unit.civ)
        val currentDamage = threats.expectedDamage(unit, current)
        if (currentDamage < unit.health) return false

        val best = unit.movement.getDistanceToTiles().keys.asSequence()
            .filter { it != current && unit.movement.canMoveTo(it) && unit.getDamageFromTerrain(it) <= 0 }
            .map { it to threats.expectedDamage(unit, it) - safetyBonus(unit, it) }
            .minByOrNull { it.second }
            ?: return false
        if (best.second >= currentDamage) return false
        unit.civ.experimentCounters.tacticalEscapes++
        unit.movement.moveToTile(best.first)
        return true
    }

    /** Own cities and friendly neighbours make a tile safer than the raw damage says */
    @Readonly
    private fun safetyBonus(unit: MapUnit, tile: Tile): Int {
        var bonus = 0
        if (tile.isCityCenter() && tile.getOwner() == unit.civ) bonus += 30
        if (tile.getOwner() == unit.civ) bonus += 5
        bonus += (tile.getDefensiveBonus(unit = unit) * 20).toInt()
        bonus += tile.neighbors.count { it.militaryUnit?.civ == unit.civ } * 3
        return bonus
    }

    /**
     * Like [com.unciv.logic.map.mapunit.movement.UnitMovement.headTowards], but does not end the turn on a tile
     * where the unit would probably be killed: it stops on the closest safe tile instead.
     */
    fun headTowardsSafely(unit: MapUnit, destination: Tile, plannedStep: Tile? = null) {
        val step = plannedStep ?: try {
            unit.movement.getTileToMoveToThisTurn(destination)
        } catch (_: UnitMovement.UnreachableDestinationException) {
            return
        }
        val threats = TacticalThreatMap.of(unit.civ)
        if (!threats.isDeadly(unit, step)) {
            unit.movement.moveToTile(step)
            return
        }
        val current = unit.getTile()
        val safeTile = unit.movement.getDistanceToTiles().keys.asSequence()
            .filter { (it == current || unit.movement.canMoveTo(it)) && unit.getDamageFromTerrain(it) <= 0 }
            .filter { !threats.isDeadly(unit, it) }
            .minWithOrNull(compareBy<Tile>({ it.aerialDistanceTo(destination) }, { threats.expectedDamage(unit, it) }))
        if (safeTile != null) unit.civ.experimentCounters.tacticalStops++
        when {
            safeTile == null -> unit.movement.moveToTile(step) // nowhere is safe; at least keep going
            safeTile != current -> unit.movement.moveToTile(safeTile)
            else -> unit.fortifyIfCan()
        }
    }

    /**
     * Whether our military near [city] clearly outnumbers its defenders, so it is worth closing in.
     * Counts forces by [MapUnit.getForceEvaluation]; the city itself counts as a defender of its own strength.
     * Our units within 5 tiles count, theirs within 3.
     */
    @Readonly
    fun hasLocalSuperiority(unit: MapUnit, city: City): Boolean {
        val center = city.getCenterTile()
        var ours = 0
        // Same scale as unit force evaluation (strength^1.5), weakened by the damage the city has taken
        var theirs = (CityCombatant(city).getDefendingStrength(null).toFloat().pow(1.5f) * city.health / city.getMaxHealth()).toInt()
        for (tile in center.getTilesInDistance(5)) {
            for (other in tile.getUnits()) {
                if (!other.isMilitary()) continue
                if (other.civ == unit.civ) ours += other.getForceEvaluation()
                else if (tile.aerialDistanceTo(center) <= 3 && unit.civ.isAtWarWith(other.civ)
                    && tile.isVisible(unit.civ) && !other.isInvisible(unit.civ))
                    theirs += other.getForceEvaluation()
            }
        }
        return ours >= theirs * REQUIRED_LOCAL_SUPERIORITY
    }
}
