package com.unciv.logic.automation.commander

import com.unciv.logic.automation.unit.TacticalThreatMap
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.battle.TargetHelper
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile

/**
 * @property surprise set when the order went differently from what the menu promised: it could not be carried out,
 *   our unit died, a target the menu said would die survived, or one it did not expect to die fell. The model is asked
 *   again after a surprise. The plan's own mistakes (a unit ordered twice, two orders for one tile, an attack not in the
 *   menu, reinforcements that cannot move) are not surprises: nothing unexpected happened in the game.
 */
class OrderResult(
    val order: CommanderOrder,
    val ok: Boolean,
    val message: String,
    val surprise: String? = null,
    /** The surprise is good news (a kill the menu did not expect): worth a re-plan only if orders are left to revise */
    val favourable: Boolean = false,
) {
    override fun toString() = (if (ok) "OK   " else "FAIL ") + order + ": " + message + (if (surprise != null) "  <- SURPRISE: $surprise" else "")
}

/**
 * Carries out the model's orders in the given order, checking each one again right before it runs (the target may be
 * dead by then, the tile taken). An order that cannot run is reported and skipped; its unit is left to the normal
 * automation (UnitTactics), so a bad answer degrades to the usual AI instead of wasting the turn.
 *
 * Execution stops at the first surprise (see [OrderResult.surprise]) so the commander can show the model the real
 * situation and let it re-plan the units that have not acted yet, as a general would after a lost unit.
 */
class OrderExecutor(
    private val civ: Civilization,
    private val allowedUnitIds: Set<Int>,
    /** The menu the model is choosing from, to tell its mistakes from real surprises; replaced after a re-plan */
    var menu: BattleDigest? = null,
) {
    /** Units that carried out at least one order; the normal automation skips them this turn */
    val handledUnitIds = HashSet<Int>()
    val results = ArrayList<OrderResult>()
    /** Tiles earlier orders of this battle moved a unit to */
    private val claimedTiles = HashSet<Tile>()

    /** Executes all [orders], surprises or not */
    fun execute(orders: List<CommanderOrder>): List<OrderResult> {
        for (order in orders) run(order)
        return results
    }

    /**
     * Executes [orders] in sequence and stops right after the first order with a surprise.
     * @return the orders not executed yet (empty when everything ran)
     */
    fun executeUntilSurprise(orders: List<CommanderOrder>): List<CommanderOrder> {
        for ((i, order) in orders.withIndex()) {
            if (run(order).surprise != null) return orders.drop(i + 1)
        }
        return emptyList()
    }

    private fun run(order: CommanderOrder): OrderResult {
        val result = try { executeOne(order) } catch (e: Exception) {
            fail(order, "error: ${e.javaClass.simpleName} ${e.message}")
        }
        results += result
        if (result.ok) handledUnitIds += order.unitId
        return result
    }

    private fun fail(order: CommanderOrder, why: String) = OrderResult(order, false, why, surprise = "order failed")

    /**
     * A unit out of movement. After an earlier order of this plan that is the plan's own mistake (a ranged unit shot and
     * was also told to move): nothing happened in the game, so it is not worth one of the battle's few re-plans.
     */
    private fun noMovement(unit: MapUnit, order: CommanderOrder): OrderResult =
        if (results.any { it.order.unitId == unit.id && it.ok }) OrderResult(order, false, "no movement left after its earlier order")
        else fail(order, "no movement left")

    private fun executeOne(order: CommanderOrder): OrderResult {
        if (order.unitId !in allowedUnitIds) return fail(order, "unit is not one of ours in this battle")
        val unit = civ.units.getCivUnits().firstOrNull { it.id == order.unitId }
            ?: return fail(order, "unit no longer exists")
        return when (order.action) {
            "option" -> option(unit, order)
            "attack" -> attack(unit, order)
            "move" -> move(unit, order)
            "move_toward" -> moveToward(unit, order)
            "fortify" -> fortify(unit, order)
            "hold" -> OrderResult(order, true, "holds at ${unit.getTile().ref()}")
            else -> fail(order, "unknown action")
        }
    }

    fun resolveTile(ref: String?): Tile? {
        if (ref == null) return null
        val parts = ref.removePrefix("(").removeSuffix(")").split(',').map { it.trim() }
        if (parts.size != 2) return null
        val x = parts[0].toIntOrNull() ?: return null
        val y = parts[1].toIntOrNull() ?: return null
        val map = civ.gameInfo.tileMap
        return if (map.contains(x, y)) map[x, y] else null
    }

    /** Enemy unit id, `city@x,y`, `city:Name` or `x,y` -> tile of the target, if the civ can see it */
    fun resolveTarget(ref: String?): Tile? {
        if (ref == null) return null
        val trimmed = ref.trim()
        if (trimmed.startsWith("city@", ignoreCase = true)) return resolveTile(trimmed.substringAfter('@'))
        if (trimmed.startsWith("city:", ignoreCase = true)) {
            val name = trimmed.substringAfter(':').trim()
            return civ.gameInfo.civilizations.asSequence().flatMap { it.cities }
                .firstOrNull { it.name.equals(name, ignoreCase = true) }?.getCenterTile()
        }
        if (trimmed.contains(',')) return resolveTile(trimmed)
        val id = trimmed.filter { it.isDigit() }.toIntOrNull() ?: return null
        return civ.viewableTiles.firstOrNull { tile -> tile.militaryUnit?.id == id || tile.civilianUnit?.id == id }
    }

    private fun attack(unit: MapUnit, order: CommanderOrder): OrderResult {
        if (!unit.hasMovement()) return noMovement(unit, order)
        if (!unit.canAttack()) return fail(order, "cannot attack again this turn")
        val targetTile = resolveTarget(order.target ?: order.tile) ?: return fail(order, "target not found (dead or not visible)")
        val options = TargetHelper.getAttackableEnemies(unit, unit.movement.getDistanceToTiles(), listOf(targetTile))
            .filter { it.tileToAttack == targetTile }
        if (options.isEmpty()) {
            // The menu offered this attack from a tile an earlier order of the plan took: the plan's own conflict
            val wantedTile = resolveTile(order.from)
            if (wantedTile != null && wantedTile != unit.getTile() && wantedTile in claimedTiles)
                return OrderResult(order, false, "tile ${wantedTile.ref()} to attack from was taken by an earlier order, no other tile to attack ${targetTile.ref()} from")
            val offered = menu?.own?.firstOrNull { it.unit == unit }?.attacks?.any { resolveTarget(it.targetRef) == targetTile }
            // An attack the menu never offered is the model's mistake: the situation is what it was, so re-planning is wasted
            return if (offered == false) OrderResult(order, false, "target ${targetTile.ref()} is out of reach this turn (not in this unit's menu)")
                else fail(order, "target ${targetTile.ref()} is out of reach this turn")
        }
        val wantedFrom = resolveTile(order.from)
        val chosen = options.firstOrNull { it.tileToAttackFrom == wantedFrom }
            ?: BattleDigestBuilder.bestAttackTile(unit, options, TacticalThreatMap.of(civ))!!
        val defender = chosen.combatant ?: return fail(order, "nothing to attack at ${targetTile.ref()}")
        // What the menu promises, at average luck, to compare with what really happens
        val expected = BattleDigestBuilder.describeAttack(unit, chosen, TacticalThreatMap.of(civ))
        // Whether the model planned on a kill: the menu it saw, not the situation now, which earlier orders changed
        val plannedKill = menu?.own?.firstOrNull { it.unit == unit }?.attacks
            ?.firstOrNull { resolveTarget(it.targetRef) == targetTile }?.kills ?: expected?.kills
        val defenderName = defender.getName()
        val targetHealthBefore = defender.getHealth()
        val healthBefore = unit.health
        val attacksBefore = unit.attacksThisTurn
        Battle.moveAndAttack(MapUnitCombatant(unit), chosen)
        if (!unit.isDestroyed && unit.attacksThisTurn == attacksBefore)
            return fail(order, "could not attack from ${chosen.tileToAttackFrom.ref()}")
        if (!unit.isDestroyed) claimedTiles += unit.getTile()
        val targetHealthAfter = if (defender.isDefeated()) 0 else defender.getHealth()
        val outcome = when {
            unit.isDestroyed -> "our unit died"
            defender.isDefeated() -> "target destroyed"
            targetTile.getOwner() == civ && targetTile.isCityCenter() -> "city captured"
            else -> "target hp $targetHealthBefore -> $targetHealthAfter"
        }
        val ourHealth = if (unit.isDestroyed) 0 else unit.health
        val targetFell = defender.isDefeated() || (targetTile.getOwner() == civ && targetTile.isCityCenter())
        val luckyKill = targetFell && plannedKill == false && !unit.isDestroyed
        val surprise = when {
            unit.isDestroyed && expected?.attackerDies != true -> "our ${unit.name} died"
            expected?.kills == true && !defender.isDefeated() && targetHealthAfter > 0 && targetTile.getOwner() != civ ->
                "$defenderName survived with $targetHealthAfter hp"
            // Plans for the rest of the turn assumed it would still stand, e.g. a second attack on it
            luckyKill -> "$defenderName fell to this attack alone, the menu did not expect a kill"
            else -> null
        }
        return OrderResult(order, true, "attacked $defenderName from ${chosen.tileToAttackFrom.ref()}: $outcome; our hp $healthBefore -> $ourHealth",
            surprise, favourable = luckyKill && surprise?.startsWith(defenderName) == true)
    }

    private fun move(unit: MapUnit, order: CommanderOrder): OrderResult {
        val tile = resolveTile(order.tile) ?: return fail(order, "bad tile '${order.tile}'")
        if (tile != unit.getTile()) {
            // The menu lists tiles per unit, so two units can pick the same one. Not a surprise: the unit takes the free
            // tile of its menu closest to the one it wanted
            if (tile in claimedTiles) {
                val alternative = freeMenuTile(unit, tile)
                    ?: return OrderResult(order, false, "tile ${tile.ref()} is already taken by an earlier order")
                val redirected = move(unit, order.copy(tile = alternative.ref()))
                return OrderResult(order, redirected.ok, "tile ${tile.ref()} was taken, instead: ${redirected.message}", redirected.surprise)
            }
            if (!unit.hasMovement()) return noMovement(unit, order)
            if (!unit.movement.canReachInCurrentTurn(tile) || !unit.movement.canMoveTo(tile))
                return fail(order, "cannot reach ${tile.ref()} this turn")
            unit.movement.moveToTile(tile)
            if (unit.getTile() != tile) return fail(order, "stopped at ${unit.getTile().ref()} instead of ${tile.ref()}")
            claimedTiles += tile
        }
        val fortified = if (order.then == "fortify" && unit.canFortify()) { unit.fortify(); ", fortified" } else ""
        return OrderResult(order, true, "now at ${tile.ref()}$fortified")
    }

    /**
     * An option id from the menu (`28.a1`, `28.m2`) turned into the attack or move it stands for. An id the unit does
     * not have is the model's mistake, not a surprise: the answer names the ids the unit really has.
     */
    private fun option(unit: MapUnit, order: CommanderOrder): OrderResult {
        val own = menu?.own?.firstOrNull { it.unit == unit }
            ?: return OrderResult(order, false, "unit has no options in this battle")
        val code = order.option!!.substringAfter('.')
        val index = code.drop(1).toIntOrNull()?.minus(1)
        val resolved = when (code.firstOrNull()) {
            'a' -> index?.let { own.attacks.getOrNull(it) }?.let { CommanderOrder(order.unitId, "attack", target = it.targetRef, from = it.fromTile.ref()) }
            'm' -> index?.let { own.moves.getOrNull(it) }?.let { CommanderOrder(order.unitId, "move", tile = it.tile.ref(), then = order.then) }
            else -> null
        } ?: return OrderResult(order, false, "no option ${order.option}; this unit has: " +
            BattleDigestBuilder.optionIds(own).ifEmpty { listOf("none") }.joinToString())
        val result = if (resolved.action == "attack") attack(unit, resolved) else move(unit, resolved)
        return OrderResult(order, result.ok, "${resolved.action} ${resolved.target ?: resolved.tile}: ${result.message}", result.surprise)
    }

    /** The tile of the unit's menu, as of now, closest to [wanted] that no earlier order took */
    private fun freeMenuTile(unit: MapUnit, wanted: Tile): Tile? {
        val engagement = menu?.engagement ?: return null
        return BattleDigestBuilder.moveOptions(unit, TacticalThreatMap.of(civ), engagement)
            .filter { it.tile !in claimedTiles }
            .minWithOrNull(compareBy<MoveOption> { it.tile.aerialDistanceTo(wanted) }.thenBy { it.threat }.thenByDescending { it.defence })
            ?.tile
    }

    /**
     * Reinforcements on their way. A failure here is never a surprise: the unit is off the battlefield, the battle is
     * what it was, and re-planning only made the model repeat the same move_toward.
     */
    private fun moveToward(unit: MapUnit, order: CommanderOrder): OrderResult {
        val tile = resolveTile(order.tile) ?: return OrderResult(order, false, "bad tile '${order.tile}'")
        if (!unit.hasMovement()) return OrderResult(order, false, "no movement left")
        val start = unit.getTile()
        if (!unit.movement.canReach(tile)) return OrderResult(order, false, "no path to ${tile.ref()}")
        unit.movement.headTowards(tile)
        if (unit.getTile() != start) claimedTiles += unit.getTile()
        return if (unit.getTile() == start && start != tile) OrderResult(order, false, "could not move closer to ${tile.ref()}")
            else OrderResult(order, true, "moved ${start.ref()} -> ${unit.getTile().ref()}")
    }

    private fun fortify(unit: MapUnit, order: CommanderOrder): OrderResult {
        if (!unit.canFortify()) return OrderResult(order, true, "holds at ${unit.getTile().ref()} (cannot fortify)")
        unit.fortify()
        return OrderResult(order, true, "fortified at ${unit.getTile().ref()}")
    }
}
