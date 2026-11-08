package com.unciv.logic.llm.prompt

import com.unciv.logic.llm.LlmCityObservation
import com.unciv.logic.llm.LlmKnownCiv
import com.unciv.logic.llm.LlmLegalAction
import com.unciv.logic.llm.LlmObservation
import com.unciv.logic.llm.LlmRankingEntry
import com.unciv.logic.llm.LlmTileYields

/**
 * Renders an [LlmObservation] as compact line-oriented text for the prompt.
 *
 * Rules: one line per entity, fields that hold their default value (false, null, 0, empty) are left out,
 * and the legal actions are listed once, grouped by template. The notation is explained in the
 * (cacheable) system prompt, see [LlmPromptBuilder.systemPrompt].
 */
class LlmCompactRenderer {

    fun renderState(observation: LlmObservation, includeResearchedTechs: Boolean): String = buildString {
        val economy = observation.economy
        appendLine("Economy: gold ${economy.gold} (${signed(economy.goldPerTurn)}/t), science ${signed(economy.sciencePerTurn)}/t, " +
            "culture ${signed(economy.culturePerTurn)}/t (stored ${economy.storedCulture}), " +
            "faith ${signed(economy.faithPerTurn)}/t (stored ${economy.storedFaith}), " +
            "happiness ${economy.happiness} (${signed(economy.happinessPerTurn)}/t)")
        append("Research: ${observation.currentResearch ?: "none"}; ${observation.researchedTechs.size} techs researched")
        if (includeResearchedTechs && observation.researchedTechs.isNotEmpty())
            append(": ${observation.researchedTechs.joinToString(", ")}")
        appendLine()

        val resources = observation.resources
        if (resources.strategic.isNotEmpty() || resources.luxury.isNotEmpty()) {
            val parts = ArrayList<String>()
            if (resources.strategic.isNotEmpty())
                parts += "strategic " + resources.strategic.joinToString(", ") { "${it.name} ${it.amount}" }
            if (resources.luxury.isNotEmpty())
                parts += "luxury " + resources.luxury.joinToString(", ") { "${it.name} ${it.amount}" }
            appendLine("Resources: ${parts.joinToString("; ")}")
        }

        val standing = observation.diplomacy.relativeStanding
        appendLine("Standing (ours rank/of leader): " + listOf(
            "score" to standing.score, "pop" to standing.population, "growth" to standing.growth,
            "prod" to standing.production, "gold" to standing.gold, "land" to standing.territory,
            "force" to standing.force, "happy" to standing.happiness, "tech" to standing.technologies,
            "culture" to standing.culture
        ).joinToString("; ") { (name, entry) -> "$name ${ranking(entry)}" })

        val threats = observation.diplomacy.threats
        val threatParts = listOfNotNull(
            threats.warCount.takeIf { it > 0 }?.let { "wars $it" },
            threats.recentDeclarationsAgainstUs.takeIf { it > 0 }?.let { "declared on us $it" },
            threats.cityStatesAtWarWithUs.takeIf { it > 0 }?.let { "city-states at war $it" },
            threats.denunciationCount.takeIf { it > 0 }?.let { "denunciations $it" },
            threats.nearbyHostileMilitaryCount.takeIf { it > 0 }?.let { "hostile units near cities $it" }
        )
        appendLine("Threats: " + threatParts.ifEmpty { listOf("none") }.joinToString(", "))

        appendLine()
        appendLine("Cities (${observation.cities.size}):")
        if (observation.cities.isEmpty()) appendLine("- none")
        for (city in observation.cities) appendLine("- " + renderCity(city))

        val diplomacy = observation.diplomacy
        if (diplomacy.knownCivs.isNotEmpty()) {
            appendLine()
            appendLine("Known civs:")
            val (majors, cityStates) = diplomacy.knownCivs.partition { !it.isCityState }
            for (civ in majors) appendLine("- " + renderKnownCiv(civ))
            if (cityStates.isNotEmpty())
                appendLine("- city-states: " + cityStates.joinToString("; ") { renderKnownCiv(it) })
        }
        if (diplomacy.activeWars.isNotEmpty()) {
            appendLine("Wars: " + diplomacy.activeWars.joinToString("; ") { war ->
                listOfNotNull(
                    war.enemyCivName,
                    if (war.warDeclaredByUs) "declared by us" else "declared on us",
                    war.turnsSinceWarDeclaration?.let { "${it}t ago" },
                    if (war.canMakePeace) null else "peace refused",
                    war.knownEnemyAlliesAtWar.takeIf { it.isNotEmpty() }?.let { "allies ${it.joinToString("/")}" }
                ).joinToString(", ")
            })
        }
        if (diplomacy.activeTrades.isNotEmpty()) {
            appendLine("Trades: " + diplomacy.activeTrades.joinToString("; ") { trade ->
                val give = trade.outgoing.joinToString(", ") { offer(it.name, it.amount) }.ifEmpty { "nothing" }
                val get = trade.incoming.joinToString(", ") { offer(it.name, it.amount) }.ifEmpty { "nothing" }
                "${trade.partnerCivName}: give $give, get $get" + (trade.turnsRemainingMin?.let { " (${it}t left)" } ?: "")
            })
        }
    }

    fun renderCity(city: LlmCityObservation): String {
        val parts = ArrayList<String>()
        parts += "${city.name} @${city.location.x},${city.location.y} pop ${city.population}, prod ${city.productionPerTurn}" +
            ", building ${city.currentConstruction ?: "nothing"}" +
            city.queue.drop(1).takeIf { it.isNotEmpty() }?.let { " then ${it.joinToString(", ")}" }.orEmpty()

        val health = city.health
        if (health.current < health.max)
            parts += "HP ${health.current}/${health.max}" + if (health.isCritical) " CRITICAL" else ""

        val defenders = city.defenders
        val garrison = defenders.garrison
        val defence = ArrayList<String>()
        if (garrison != null) defence += "garrison ${garrison.name}" + if (garrison.isInjured) " ${garrison.healthPercent}%" else ""
        if (defenders.supportingUnitCount > 0)
            defence += "nearby " + defenders.supportingUnitTypeBreakdown.entries.joinToString(", ") { count(it.key, it.value) }
        parts += defence.ifEmpty { listOf("undefended") }.joinToString(", ")

        val enemies = city.visibleEnemies
        if (enemies.totalCount > 0) {
            parts += "ENEMIES ${enemies.totalCount} (" + enemies.byCiv.joinToString(", ") { "${it.civName} ${it.count}" } +
                ")" + (enemies.closestDistance?.let { ", closest $it tiles" } ?: "") +
                enemies.brief.takeIf { it.isNotEmpty() }?.let { units ->
                    ": " + units.joinToString(", ") { if (it.isInjured) "${it.name} ${it.healthPercent}%" else it.name }
                }.orEmpty()
        }

        val tiles = city.workableTilesSummary
        parts += "works ${tiles.workedTiles}/${tiles.totalTiles} tiles, yields ${yields(tiles.totalYields)}"
        if (tiles.resourceCounts.isNotEmpty())
            parts += "res " + tiles.resourceCounts.entries.joinToString(", ") { count(it.key, it.value) }
        if (tiles.improvementCounts.isNotEmpty())
            parts += "impr " + tiles.improvementCounts.entries
                .filter { it.key != CITY_CENTER }
                .joinToString(", ") { count(it.key, it.value) }

        val expansion = city.expansionPressure
        val border = ArrayList<String>()
        border += "${expansion.ownedTiles} owned tiles"
        expansion.turnsToNextTile?.let { border += "next tile in ${it}t" }
        if (expansion.foreignBorderTiles > 0) border += "${expansion.foreignBorderTiles} foreign border tiles"
        expansion.nearestForeignCity?.let { border += "nearest foreign ${it.cityName} (${it.civName}) ${it.distance} tiles" }
        parts += border.joinToString(", ")

        return parts.joinToString(" | ")
    }

    private fun renderKnownCiv(civ: LlmKnownCiv): String {
        val parts = ArrayList<String>()
        parts += civ.civName
        parts += civ.diplomaticStatus
        parts += civ.relationshipLevel
        civ.opinionScore?.let { parts += "opinion ${signed(it)}" }
        civ.cityStateInfluence?.takeIf { it != 0 }?.let { parts += "influence $it" }
        if (civ.cityStateAlly == true) parts += "our ally"
        when {
            civ.hasOpenBorders && civ.theirsHaveOpenBorders -> parts += "open borders both ways"
            civ.hasOpenBorders -> parts += "we opened borders"
            civ.theirsHaveOpenBorders -> parts += "they opened borders"
        }
        if (civ.hasDeclarationOfFriendship) parts += "friendship"
        if (civ.hasDefensivePact) parts += "defensive pact"
        if (civ.hasResearchAgreement) parts += "research agreement"
        return parts.joinToString(" ")
    }

    /**
     * Lists every legal action exactly once, by alias, grouped by template.
     * Constructions buildable in every city are listed once under "all cities".
     */
    fun renderActions(observation: LlmObservation, aliases: LlmActionAliases): String = buildString {
        val actions = observation.legalActions
        val listed = HashSet<String>()
        fun mark(action: LlmLegalAction) { listed += action.id }

        val techs = actions.filterIsInstance<LlmLegalAction.ChooseTechnology>()
        if (techs.isNotEmpty() && techs.all { aliases.aliasOf(it) == "tech:${it.techName}" }) {
            appendLine("tech:<name> - research: " + techs.joinToString(", ") { it.techName })
            techs.forEach(::mark)
        }

        val builds = actions.filterIsInstance<LlmLegalAction.ChooseCityConstruction>()
            .filter { aliases.aliasOf(it).startsWith("build:") }
        if (builds.isNotEmpty()) {
            appendLine("build:<city>:<item> - set city production")
            val byCity = builds.groupBy { it.cityName }
            val common = if (byCity.size > 1)
                byCity.values.map { list -> list.map { it.constructionName }.toSet() }.reduce { a, b -> a intersect b }
            else emptySet()
            if (common.isNotEmpty())
                appendLine("  all cities: " + common.sorted().joinToString(", "))
            for ((cityName, list) in byCity) {
                val specific = list.map { it.constructionName }.filter { it !in common }
                if (specific.isNotEmpty())
                    appendLine("  $cityName: " + specific.joinToString(", "))
                else if (common.isEmpty())
                    appendLine("  $cityName: nothing")
            }
            builds.forEach(::mark)
        }

        val founds = actions.filterIsInstance<LlmLegalAction.FoundCity>()
        if (founds.isNotEmpty()) {
            appendLine("found:<unit>@<x>,<y> - settler moves there and founds a city: " +
                founds.groupBy { it.unitId }.entries.joinToString("; ") { (unitId, list) ->
                    "${list.first().unitName} $unitId -> " + list.joinToString(", ") { aliases.aliasOf(it) }
                })
            founds.forEach(::mark)
        }

        val attacks = actions.filterIsInstance<LlmLegalAction.AttackCity>()
        if (attacks.isNotEmpty()) {
            appendLine("attack:<city> - remaining army attacks: " + attacks.joinToString(", ") {
                "${aliases.aliasOf(it)} (${it.targetCivId} @${it.location.x},${it.location.y})"
            })
            attacks.forEach(::mark)
        }

        val defends = actions.filterIsInstance<LlmLegalAction.DefendCity>()
        if (defends.isNotEmpty()) {
            appendLine("defend:<city> - remaining army holds the city: " + defends.joinToString(", ") { aliases.aliasOf(it) })
            defends.forEach(::mark)
        }

        val wars = actions.filterIsInstance<LlmLegalAction.DeclareWar>()
        if (wars.isNotEmpty()) {
            val cityStates = observation.diplomacy.knownCivs.filter { it.isCityState }.map { it.civName }.toSet()
            val (minor, major) = wars.partition { it.targetCivName in cityStates }
            val groups = listOfNotNull(
                major.takeIf { it.isNotEmpty() }?.joinToString(", ") { aliases.aliasOf(it) },
                minor.takeIf { it.isNotEmpty() }?.let { "city-states: " + it.joinToString(", ") { a -> aliases.aliasOf(a) } }
            )
            appendLine("war:<civ> - declare war: " + groups.joinToString("; "))
            wars.forEach(::mark)
        }

        val peace = actions.filterIsInstance<LlmLegalAction.OfferPeace>()
        if (peace.isNotEmpty()) {
            appendLine("peace:<civ> - offer peace (answered on their turn): " + peace.joinToString(", ") { aliases.aliasOf(it) })
            peace.forEach(::mark)
        }

        val rest = actions.filter { it.id !in listed }
        if (rest.isNotEmpty())
            appendLine(rest.joinToString(", ") { aliases.aliasOf(it) })
    }

    private fun ranking(entry: LlmRankingEntry) =
        if (entry.rank == 1) "${entry.ourValue} 1/${entry.totalKnownMajors}"
        else "${entry.ourValue} ${entry.rank}/${entry.totalKnownMajors} ${entry.leaderValue}"

    private fun yields(y: LlmTileYields) = listOf(
        "F" to y.food, "P" to y.production, "G" to y.gold, "S" to y.science,
        "C" to y.culture, "Fa" to y.faith, "H" to y.happiness
    ).filter { it.second != 0 }.joinToString(" ") { "${it.first}${it.second}" }.ifEmpty { "none" }

    private fun count(name: String, amount: Int) = if (amount == 1) name else "$name x$amount"
    private fun offer(name: String, amount: Int) = if (amount == 0) name else "$name $amount"

    companion object {
        private const val CITY_CENTER = "City center"
        fun signed(value: Int) = if (value >= 0) "+$value" else value.toString()
    }
}
