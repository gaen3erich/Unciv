package com.unciv.logic.automation.commander

import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.unit.BaseUnit

/** Builds what the model reads: a fixed system prompt and one user message per battle */
object CommanderPrompt {

    fun systemPrompt(intentLanguage: String): String = """
You are the field commander of one civilization in the turn-based strategy game Unciv (a Civilization V clone).
Each turn you get ONE battle: our units, visible enemies, cities, reinforcements. You decide what each of our units
does this turn and in which order. Units you do not mention are moved by the regular AI.

The game engine already did the geometry and arithmetic for you. Every option listed is legal right now:
- "attack T from X,Y": the unit moves to X,Y if needed and attacks T. deal/take are expected damage (average luck).
  "KILLS" means the target dies; a melee unit that kills moves into the target's tile. "WE DIE" means don't.
- "move <kind> X,Y": tiles the unit can reach this turn. def = terrain defence bonus, "enemy dmg next turn" = damage
  the visible enemies can deal to this unit there on their turn (if it is >= the unit's hp, the unit probably dies).
- Units have 100 hp. Damage is applied in the order you give, so later options assume earlier attacks happened:
  options are computed for the start of the turn, so if an earlier attack already killed a target, attack something else.
- Ranged and siege units do not take damage when attacking units. A unit that attacks usually cannot move afterwards.
- Usually: ranged units shoot first, melee units finish wounded targets, wounded units pull back to safe or
  defensive tiles, units adjacent to enemy melee should stand on good defence and fortify.
- Use only unit ids, targets and tiles that appear in the battle description. Do not invent coordinates.
- A tile holds one military unit, and options are listed per unit without regard to each other: never send two units
  to the same tile. A second order for a tile that is already taken is dropped and that unit is left to the regular AI.
- Orders are carried out one by one with real dice. If something goes differently from the menu (an order fails,
  a unit dies, a target survives a "KILLS" attack), execution stops and you get the new situation to re-plan.

Think about coordination: focus fire to kill units (a dead unit deals no damage next turn), protect ranged units,
do not leave units on tiles where they will die for nothing, take cities when their hp is low.

Answer with ONE JSON object and nothing after it:
{
  "intent": "short plan for this and the next turn, in $intentLanguage",
  "orders": [
    {"unit": 17, "action": "attack", "target": 42},
    {"unit": 23, "action": "attack", "target": "city@8,11", "from": "7,11"},
    {"unit": 9,  "action": "move", "tile": "7,12", "then": "fortify"},
    {"unit": 31, "action": "move_toward", "tile": "6,10"},
    {"unit": 12, "action": "fortify"}
  ],
  "production_hint": {"city": "city@3,4", "prefer": ["Archer", "Spearman"]}
}
Actions: attack (target = enemy unit id or city@x,y, optional "from" tile), move (tile reachable this turn,
optional "then": "fortify"), move_toward (any tile, for reinforcements far away), fortify, hold.
production_hint is optional: units to build next in one of our cities (city@x,y) for this fight, from the lineup you are given.
""".trimIndent()

    fun userPrompt(digest: BattleDigest, previousIntent: String?, previousOutcome: String?): String = buildString {
        val civ = digest.civ
        appendLine("We are ${civ.civName}. At war with: ${civ.getCivsAtWarWith().joinToString { it.civName }.ifEmpty { "nobody" }}.")
        appendLine()
        if (previousIntent != null) {
            appendLine("Your intent for this battle last turn: $previousIntent")
            if (previousOutcome != null) appendLine("What happened: $previousOutcome")
            appendLine()
        }
        append(BattleDigestBuilder.render(digest))
        append(lineup(civ, digest))
        appendLine()
        appendLine("Give your orders as JSON.")
    }

    /** What we can fight with: units buildable now, units one or two techs away, strategic resources, production nearby */
    fun lineup(civ: Civilization, digest: BattleDigest): String = buildString {
        val ruleset = civ.gameInfo.ruleset
        val resources = civ.getCivResourcesByName()
        val strategic = ruleset.tileResources.values.filter { it.resourceType == ResourceType.Strategic }
            .map { it.name to (resources[it.name] ?: 0) }
        appendLine("### Army lineup")
        appendLine("Strategic resources: " + strategic.joinToString { "${it.first} ${it.second}" })

        val military = ruleset.units.values.filter { it.isMilitary && !it.isCivilian() && it.uniqueTo.let { u -> u == null || u == civ.civName } }
        val buildable = military.filter { it.isBuildable(civ) }
        appendLine("Can build now: " + buildable.joinToString { describe(it, resources) }.ifEmpty { "nothing" })

        val soon = military.filter { unit ->
            if (unit in buildable) return@filter false
            val tech = unit.requiredTech?.let { ruleset.technologies[it] } ?: return@filter false
            if (civ.tech.isResearched(tech.name)) return@filter false
            civ.tech.getRequiredTechsToDestination(tech).size <= 2
        }.filter { it.replaces == null || ruleset.units[it.replaces!!]?.let { base -> base.uniqueTo == null } == true || it.uniqueTo == civ.civName }
        if (soon.isNotEmpty()) appendLine("1-2 techs away: " + soon.joinToString { "${describe(it, resources)} after ${it.requiredTech}" })

        val center = digest.engagement.centerTile()
        val nearCities = civ.cities.sortedBy { it.getCenterTile().aerialDistanceTo(center) }.take(3)
        if (nearCities.isNotEmpty()) {
            appendLine("Nearest own cities (production_hint can name one):")
            for (city in nearCities) {
                val current = city.cityConstructions.currentConstructionName()
                appendLine("- ${city.ref()} ${city.name}, ${city.getCenterTile().aerialDistanceTo(center)} tiles from the battle, " +
                    "${city.cityStats.currentCityStats.production.toInt()} production/turn, building $current")
            }
        }
    }

    private fun describe(unit: BaseUnit, resources: Map<String, Int>): String {
        val stats = if (unit.isRanged()) "${unit.strength}/${unit.rangedStrength}r" else "${unit.strength}"
        val needs = unit.getResourceRequirementsPerTurn(null).entries
            .joinToString("") { (res, n) -> ", needs $n $res" + if ((resources[res] ?: 0) < n) " (MISSING)" else "" }
        return "${unit.name} ($stats, cost ${unit.cost}$needs)"
    }
}
