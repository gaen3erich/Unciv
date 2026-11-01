package com.unciv.logic.llm.prompt

import com.unciv.logic.llm.LlmObservation

/**
 * Describes what changed between the observation the model last decided on and the current one,
 * so the model does not have to diff two full states itself.
 */
object LlmTurnChanges {

    fun describe(previous: LlmObservation, current: LlmObservation): List<String> {
        val changes = ArrayList<String>()

        val newTechs = current.researchedTechs.toSet() - previous.researchedTechs.toSet()
        if (newTechs.isNotEmpty()) changes += "researched ${newTechs.sorted().joinToString(", ")}"
        if (previous.currentResearch != current.currentResearch && current.currentResearch != null)
            changes += "now researching ${current.currentResearch}"

        val goldDelta = current.economy.gold - previous.economy.gold
        if (goldDelta != 0) changes += "gold ${LlmCompactRenderer.signed(goldDelta)} (now ${current.economy.gold})"
        val happinessDelta = current.economy.happiness - previous.economy.happiness
        if (happinessDelta != 0) changes += "happiness ${LlmCompactRenderer.signed(happinessDelta)} (now ${current.economy.happiness})"

        val previousCities = previous.cities.associateBy { it.cityId }
        val currentCities = current.cities.associateBy { it.cityId }
        for (city in current.cities) {
            val before = previousCities[city.cityId]
            if (before == null) {
                changes += "new city ${city.name}"
                continue
            }
            val cityChanges = ArrayList<String>()
            if (city.population != before.population)
                cityChanges += "pop ${before.population}->${city.population}"
            if (city.currentConstruction != before.currentConstruction)
                cityChanges += "production ${before.currentConstruction ?: "nothing"}->${city.currentConstruction ?: "nothing"}"
            if (city.health.current < before.health.current)
                cityChanges += "lost ${before.health.current - city.health.current} HP"
            if (city.visibleEnemies.totalCount != before.visibleEnemies.totalCount)
                cityChanges += "enemies near ${before.visibleEnemies.totalCount}->${city.visibleEnemies.totalCount}"
            if ((city.defenders.garrison == null) != (before.defenders.garrison == null))
                cityChanges += if (city.defenders.garrison == null) "garrison gone" else "garrisoned"
            if (cityChanges.isNotEmpty()) changes += "${city.name}: ${cityChanges.joinToString(", ")}"
        }
        for (city in previous.cities) {
            if (city.cityId !in currentCities) changes += "LOST city ${city.name}"
        }

        val previousCivs = previous.diplomacy.knownCivs.associateBy { it.civName }
        for (civ in current.diplomacy.knownCivs) {
            val before = previousCivs[civ.civName]
            when {
                before == null -> changes += "met ${civ.civName}"
                before.diplomaticStatus != civ.diplomaticStatus ->
                    changes += "${civ.civName}: ${before.diplomaticStatus}->${civ.diplomaticStatus}"
                before.relationshipLevel != civ.relationshipLevel ->
                    changes += "${civ.civName}: relationship ${before.relationshipLevel}->${civ.relationshipLevel}"
            }
        }
        val currentCivs = current.diplomacy.knownCivs.map { it.civName }.toSet()
        for (civ in previous.diplomacy.knownCivs) {
            if (civ.civName !in currentCivs) changes += "${civ.civName} is gone"
        }

        val previousResources = (previous.resources.strategic + previous.resources.luxury).associate { it.name to it.amount }
        val currentResources = (current.resources.strategic + current.resources.luxury).associate { it.name to it.amount }
        val resourceChanges = (previousResources.keys + currentResources.keys).sorted().mapNotNull { name ->
            val before = previousResources[name] ?: 0
            val after = currentResources[name] ?: 0
            if (before == after) null else "$name $before->$after"
        }
        if (resourceChanges.isNotEmpty()) changes += "resources " + resourceChanges.joinToString(", ")

        return changes
    }
}
