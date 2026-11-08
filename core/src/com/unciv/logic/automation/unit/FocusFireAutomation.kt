package com.unciv.logic.automation.unit

import com.unciv.logic.automation.AiFeature
import com.unciv.logic.battle.AttackableTile
import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.map.mapunit.MapUnit
import yairm210.purity.annotations.Readonly

/**
 * Focus fire, switched on by [AiFeature.FocusFire] (meant to be used with [AiFeature.UnitTactics]).
 *
 * Ranged units move before melee units (see NextTurnAutomation.getUnitPriority). An attack that kills is worth
 * much more than chip damage, and a ranged unit prefers a target that a nearby melee unit of ours can then finish
 * this turn, so damage is not spread over several enemies.
 *
 * A battle line was tried too (ranged units avoiding tiles enemy melee can reach, melee units stepping in front of
 * exposed ranged units and fortifying). It lost clearly in 4-civ benchmarks (39-40% vs UnitTactics alone, p<0.05
 * on two seed sets) while focus fire alone scored 58%, so it was removed.
 */
object FocusFireAutomation {
    /** Added to [BattleHelper]'s value of an attack that kills the defender */
    private const val KILL_BONUS = 150
    /** Added for a ranged attack that leaves the defender for a melee unit of ours to finish this turn */
    private const val SETUP_KILL_BONUS = 80

    @Readonly
    fun isActive(unit: MapUnit): Boolean = unit.civ.aiProfile.has(AiFeature.FocusFire)

    /** Bonus for an attack that kills, or that sets up a kill for one of our melee units */
    @Readonly
    fun focusBonus(attacker: MapUnit, target: AttackableTile, damage: Int): Int {
        val defender = target.tileToAttack.militaryUnit ?: return 0
        if (defender.civ == attacker.civ) return 0
        if (damage >= defender.health) return KILL_BONUS
        if (attacker.baseUnit.isRanged() && canBeFinished(attacker, defender, defender.health - damage)) return SETUP_KILL_BONUS
        return 0
    }

    /** Whether a melee unit of ours near [target] can still attack this turn and deal at least [healthLeft] */
    @Readonly
    private fun canBeFinished(shooter: MapUnit, target: MapUnit, healthLeft: Int): Boolean {
        for (tile in target.getTile().getTilesInDistance(2)) {
            val friend = tile.militaryUnit ?: continue
            if (friend == shooter || friend.civ != shooter.civ || !friend.baseUnit.isMelee()) continue
            if (!friend.hasMovement() || !friend.canAttack()) continue
            val damage = BattleDamage.calculateDamageToDefender(MapUnitCombatant(friend), MapUnitCombatant(target), randomnessFactor = 0.5f)
            if (damage >= healthLeft) return true
        }
        return false
    }
}
