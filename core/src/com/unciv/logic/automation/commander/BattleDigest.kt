package com.unciv.logic.automation.commander

import com.unciv.logic.automation.unit.TacticalThreatMap
import com.unciv.logic.battle.AttackableTile
import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.ICombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.battle.TargetHelper
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import kotlin.math.roundToInt

/** `x,y` of a tile, the only way tiles are named to the model */
fun Tile.ref(): String = "${position.x},${position.y}"

/** Short terrain description: base terrain plus features, e.g. `Grassland+Hill+Forest`, `+river` if next to one */
fun Tile.terrainLabel(): String {
    val parts = listOf(baseTerrain) + terrainFeatures
    return parts.joinToString("+") + if (isAdjacentToRiver()) " (river)" else ""
}

/** Expected enemy damage on [tile]; zero in an own city, where the city takes the hits instead of the garrison */
fun TacticalThreatMap.damageOn(unit: MapUnit, tile: Tile, ignore: Tile? = null): Int =
    if (tile.isCityCenter() && tile.getOwner() == unit.civ) 0 else expectedDamage(unit, tile, ignore)

/** How cities are named to the model: by position, since names need not be unique */
fun City.ref(): String = "city@" + getCenterTile().ref()

fun Tile.defencePercent(unit: MapUnit? = null): Int = (getDefensiveBonus(true, unit) * 100).roundToInt()

/** What an attack is expected to do, from Unciv's own damage formula at average luck */
class AttackOption(
    val targetRef: String,
    val targetName: String,
    val fromTile: Tile,
    val damageDealt: Int,
    val damageTaken: Int,
    val targetHealth: Int,
    val attackerHealth: Int,
    /** Enemy damage expected on [fromTile] next turn (melee units that kill move into the target tile instead) */
    val threatAfter: Int,
    val needsMove: Boolean,
) {
    val kills get() = damageDealt >= targetHealth
    val attackerDies get() = damageTaken >= attackerHealth
}

class MoveOption(val kind: String, val tile: Tile, val defence: Int, val threat: Int, val distanceToEnemy: Int)

class OwnUnitDigest(
    val unit: MapUnit,
    val threatHere: Int,
    val attacks: List<AttackOption>,
    val moves: List<MoveOption>,
)

class EnemyUnitDigest(val unit: MapUnit, val canHit: List<Pair<String, Int>>)

class BattleDigest(
    val civ: Civilization,
    val engagement: Engagement,
    val own: List<OwnUnitDigest>,
    val enemies: List<EnemyUnitDigest>,
)

/**
 * Turns an [Engagement] into a menu of legal actions with computed consequences.
 *
 * The model is bad at hex geometry and at the combat formula, so it never sees raw geometry it would have to reason
 * about: for each own unit it gets the targets it can really attack this turn (with damage both ways from
 * [BattleDamage]) and a handful of tiles it can really reach (with defence bonus and expected enemy damage from
 * [TacticalThreatMap]); for each enemy, which of our units and cities it can reach on its turn.
 */
object BattleDigestBuilder {
    /** How many move options each unit gets; more tiles cost prompt space without adding real choices */
    const val MAX_MOVES = 5

    fun build(civ: Civilization, engagement: Engagement): BattleDigest {
        val threatMap = TacticalThreatMap.of(civ)
        val battleTargets = engagement.enemyUnits.map { it.getTile() } + engagement.enemyCities.map { it.getCenterTile() }
        val own = engagement.ownUnits.map { unit -> digestOwnUnit(unit, threatMap, battleTargets, engagement) }
        val enemies = engagement.enemyUnits.map { enemy -> digestEnemy(enemy, engagement) }
        return BattleDigest(civ, engagement, own, enemies)
    }

    private fun digestOwnUnit(unit: MapUnit, threatMap: TacticalThreatMap, battleTargets: List<Tile>, engagement: Engagement): OwnUnitDigest {
        val threatHere = threatMap.damageOn(unit, unit.getTile())
        val attacks = if (!unit.hasMovement() || !unit.canAttack()) emptyList()
            else attackOptions(unit, threatMap, battleTargets)
        val moves = if (!unit.hasMovement()) emptyList() else moveOptions(unit, threatMap, engagement)
        return OwnUnitDigest(unit, threatHere, attacks, moves)
    }

    fun attackOptions(unit: MapUnit, threatMap: TacticalThreatMap, targets: List<Tile>): List<AttackOption> {
        val attackable = TargetHelper.getAttackableEnemies(unit, unit.movement.getDistanceToTiles(), targets)
        return attackable.groupBy { it.tileToAttack }.mapNotNull { (targetTile, options) ->
            val best = bestAttackTile(unit, options, threatMap) ?: return@mapNotNull null
            describeAttack(unit, best, threatMap)
        }.sortedWith(compareByDescending<AttackOption> { it.kills }.thenByDescending { it.damageDealt - it.damageTaken })
    }

    /** Where to attack [AttackableTile.tileToAttack] from: ranged units prefer not to move, then least enemy damage, then most defence */
    fun bestAttackTile(unit: MapUnit, options: List<AttackableTile>, threatMap: TacticalThreatMap): AttackableTile? {
        if (options.isEmpty()) return null
        if (unit.baseUnit.isRanged()) options.firstOrNull { it.tileToAttackFrom == unit.getTile() }?.let { return it }
        return options.minWith(compareBy<AttackableTile> { threatMap.damageOn(unit, it.tileToAttackFrom, ignore = it.tileToAttack) }
            .thenByDescending { it.tileToAttackFrom.getDefensiveBonus(true, unit) }
            .thenByDescending { it.movementLeftAfterMovingToAttackTile })
    }

    fun describeAttack(unit: MapUnit, option: AttackableTile, threatMap: TacticalThreatMap): AttackOption? {
        val defender = option.combatant ?: return null
        val attacker = MapUnitCombatant(unit)
        val dealt = BattleDamage.calculateDamageToDefender(attacker, defender, option.tileToAttackFrom, 0.5f)
        val taken = if (unit.baseUnit.isRanged() && defender !is CityCombatant) 0
            else BattleDamage.calculateDamageToAttacker(attacker, defender, option.tileToAttackFrom, 0.5f)
        val targetHealth = defender.getHealth()
        val kills = dealt >= targetHealth
        // A melee unit that kills (or captures a city) ends on the target tile
        val endTile = if (kills && unit.baseUnit.isMelee()) option.tileToAttack else option.tileToAttackFrom
        val threatAfter = threatMap.damageOn(unit, endTile, ignore = option.tileToAttack)
        return AttackOption(targetRef(defender), defender.getName(), option.tileToAttackFrom, dealt, taken,
            targetHealth, unit.health, threatAfter, option.tileToAttackFrom != unit.getTile())
    }

    fun targetRef(combatant: ICombatant): String = when (combatant) {
        is MapUnitCombatant -> combatant.unit.id.toString()
        is CityCombatant -> combatant.city.ref()
        else -> combatant.getTile().ref()
    }

    /**
     * A few reachable tiles, one per purpose: hold here, safest, best defence, closest to the enemy without dying,
     * and towards a threatened own city. Duplicates are dropped.
     */
    fun moveOptions(unit: MapUnit, threatMap: TacticalThreatMap, engagement: Engagement): List<MoveOption> {
        val enemyTiles = engagement.enemyUnits.map { it.getTile() } + engagement.enemyCities.map { it.getCenterTile() }
        fun distanceToEnemy(tile: Tile) = enemyTiles.minOfOrNull { tile.aerialDistanceTo(it) } ?: 99
        val reachable = unit.movement.getDistanceToTiles().keys
            .filter { it == unit.getTile() || unit.movement.canMoveTo(it) }
            .filter { it.militaryUnit == null || it.militaryUnit == unit }
        if (reachable.isEmpty()) return emptyList()
        val threat = reachable.associateWith { threatMap.damageOn(unit, it) }
        fun option(kind: String, tile: Tile) = MoveOption(kind, tile, tile.defencePercent(unit), threat[tile]!!, distanceToEnemy(tile))

        val result = ArrayList<MoveOption>()
        fun add(kind: String, tile: Tile?) {
            if (tile == null || result.any { it.tile == tile }) return
            result += option(kind, tile)
        }
        add("stay", unit.getTile())
        add("safest", reachable.minWith(compareBy<Tile> { threat[it]!! }.thenByDescending { it.getDefensiveBonus(true, unit) }))
        val survivable = reachable.filter { threat[it]!! < unit.health }
        add("best_defence", survivable.maxWithOrNull(compareBy<Tile> { it.getDefensiveBonus(true, unit) }.thenBy { -threat[it]!! }))
        // Ranged units want to be in range, not adjacent; melee units want to be adjacent
        val wanted = if (unit.baseUnit.isRanged()) unit.getRange() else 1
        add("advance", survivable.minWithOrNull(compareBy<Tile> { kotlin.math.abs(distanceToEnemy(it) - wanted) }
            .thenBy { threat[it]!! }))
        val city = engagement.ownCities.minByOrNull { it.getCenterTile().aerialDistanceTo(unit.getTile()) }
        if (city != null) add("cover_city", reachable.minWith(compareBy<Tile> { it.aerialDistanceTo(city.getCenterTile()) }
            .thenBy { threat[it]!! }))
        return result.take(MAX_MOVES)
    }

    private fun digestEnemy(enemy: MapUnit, engagement: Engagement): EnemyUnitDigest {
        val reach = BattleDetector.reach(enemy)
        val attacker = MapUnitCombatant(enemy)
        val canHit = ArrayList<Pair<String, Int>>()
        for (target in engagement.ownUnits) {
            if (enemy.getTile().aerialDistanceTo(target.getTile()) > reach) continue
            canHit += target.id.toString() to BattleDamage.calculateDamageToDefender(attacker, MapUnitCombatant(target), randomnessFactor = 0.5f)
        }
        for (city in engagement.ownCities) {
            if (enemy.getTile().aerialDistanceTo(city.getCenterTile()) > reach) continue
            canHit += city.ref() to BattleDamage.calculateDamageToDefender(attacker, CityCombatant(city), randomnessFactor = 0.5f)
        }
        return EnemyUnitDigest(enemy, canHit.sortedByDescending { it.second })
    }

    // region Text rendering

    fun role(unit: MapUnit): String = when {
        unit.baseUnit.isWaterUnit -> if (unit.baseUnit.isRanged()) "naval ranged" else "naval melee"
        unit.baseUnit.isRanged() && unit.baseUnit.isProbablySiegeUnit() && unit.baseUnit.rangedStrength > unit.baseUnit.strength * 2 -> "siege"
        unit.baseUnit.isRanged() -> "ranged"
        else -> "melee"
    }

    private fun strengthLabel(unit: MapUnit): String =
        if (unit.baseUnit.isRanged()) "str ${unit.baseUnit.strength}/rng ${unit.baseUnit.rangedStrength} range ${unit.getRange()}"
        else "str ${unit.baseUnit.strength}"

    fun render(digest: BattleDigest): String = buildString {
        val engagement = digest.engagement
        val civ = digest.civ
        appendLine("## Battle ${engagement.index} near ${engagement.centerTile().ref()} (turn ${civ.gameInfo.turns})")
        appendLine("Own units: ${engagement.ownUnits.size}, visible enemy units: ${engagement.enemyUnits.size}" +
            (if (engagement.isOwnCityThreatened) ", own city threatened: ${engagement.ownCities.joinToString { it.name }}" else ""))
        appendLine()

        if (engagement.ownCities.isNotEmpty() || engagement.enemyCities.isNotEmpty()) {
            appendLine("### Cities")
            for (city in engagement.ownCities) appendLine(cityLine(city, "OWN"))
            for (city in engagement.enemyCities) appendLine(cityLine(city, "ENEMY ${city.civ.civName}"))
            appendLine()
        }

        appendLine("### Own units (act in the order you list them)")
        for (u in digest.own) {
            val unit = u.unit
            appendLine("#${unit.id} ${unit.name} [${role(unit)}] hp ${unit.health}/100, ${strengthLabel(unit)}, " +
                "moves ${fmtMoves(unit.currentMovement)}/${unit.getMaxMovement()}, at ${unit.getTile().ref()} ${unit.getTile().terrainLabel()} " +
                "def ${unit.getTile().defencePercent(unit)}%, enemy dmg next turn here ${u.threatHere}" +
                (if (unit.isFortified()) ", fortified" else "") +
                (if (unit.getTile().isCityCenter()) ", garrison (the city takes the hits)" else "") +
                (if (!unit.canAttack() || !unit.hasMovement()) ", cannot attack this turn" else ""))
            if (u.attacks.isEmpty() && unit.canAttack() && unit.hasMovement())
                appendLine("  no attack available: no target in range this turn, even after moving")
            for (a in u.attacks) {
                val outcome = when {
                    a.attackerDies -> "WE DIE"
                    a.kills -> "KILLS (${a.targetName} has ${a.targetHealth})"
                    else -> "target left with ${a.targetHealth - a.damageDealt}"
                }
                appendLine("  attack ${a.targetRef} (${a.targetName}) from ${a.fromTile.ref()}${if (a.needsMove) " (moves there)" else ""}: " +
                    "deal ${a.damageDealt}, take ${a.damageTaken}, $outcome; enemy dmg on us next turn ${a.threatAfter}")
            }
            for (m in u.moves) {
                appendLine("  move ${m.kind} ${m.tile.ref()} ${m.tile.terrainLabel()}: def ${m.defence}%, enemy dmg next turn ${m.threat}, " +
                    "distance to nearest enemy ${m.distanceToEnemy}")
            }
        }
        appendLine()

        appendLine("### Enemy units (what they can do on their turn)")
        if (digest.enemies.isEmpty()) appendLine("none visible")
        for (e in digest.enemies) {
            val unit = e.unit
            appendLine("#${unit.id} ${unit.civ.civName} ${unit.name} [${role(unit)}] hp ${unit.health}/100, ${strengthLabel(unit)}, " +
                "at ${unit.getTile().ref()} ${unit.getTile().terrainLabel()} def ${unit.getTile().defencePercent(unit)}%" +
                if (e.canHit.isEmpty()) ", cannot reach our units" else ", can hit: " + e.canHit.joinToString { "${it.first} for ${it.second}" })
        }
        appendLine()

        if (engagement.reinforcements.isNotEmpty()) {
            appendLine("### Reinforcements (can be ordered with move_toward)")
            val center = engagement.centerTile()
            for (unit in engagement.reinforcements) {
                val turns = (unit.getTile().aerialDistanceTo(center) + unit.getMaxMovement() - 1) / unit.getMaxMovement().coerceAtLeast(1)
                appendLine("#${unit.id} ${unit.name} [${role(unit)}] hp ${unit.health}/100, ${strengthLabel(unit)}, at ${unit.getTile().ref()}, ~$turns turns away")
            }
            appendLine()
        }
    }

    private fun fmtMoves(movement: Float): String = if (movement == movement.toInt().toFloat()) movement.toInt().toString() else "%.1f".format(movement)

    private fun cityLine(city: City, owner: String): String {
        val combatant = CityCombatant(city)
        return "${city.ref()} ${city.name} ($owner), hp ${city.health}/${combatant.getMaxHealth()}, " +
            "defence ${combatant.getDefendingStrength()}" +
            (if (city.getCenterTile().militaryUnit != null) ", garrison ${city.getCenterTile().militaryUnit!!.name}" else "")
    }
    // endregion
}
