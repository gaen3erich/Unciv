package com.unciv.logic.automation.commander

import com.unciv.logic.automation.unit.TacticalThreatMap
import com.unciv.logic.civilization.Civilization

/**
 * Plays a plan on a copy of the game, so the model sees what its combination of orders does as a whole (targets
 * dying before later attacks, tiles taken by earlier moves) and can revise it once before anything happens for real.
 */
object PlanSimulator {

    class Simulation(val results: List<OrderResult>, val feedback: String)

    fun simulate(civ: Civilization, engagement: Engagement, plan: CommanderPlan, allowedUnitIds: Set<Int>): Simulation {
        val copy = civ.gameInfo.clone()
        copy.setTransients()
        val copyCiv = copy.getCivilization(civ.civID)
        try { return simulateOnCopy(copyCiv, civ, engagement, plan, allowedUnitIds) }
        finally { for (c in copy.civilizations) TacticalThreatMap.forget(c) }
    }

    private fun simulateOnCopy(copyCiv: Civilization, civ: Civilization, engagement: Engagement, plan: CommanderPlan, allowedUnitIds: Set<Int>): Simulation {
        val copy = copyCiv.gameInfo
        val executor = OrderExecutor(copyCiv, allowedUnitIds)
        val results = executor.execute(plan.orders)

        val feedback = buildString {
            appendLine("Simulated result of your plan, played on a copy of the game (real combat rolls may differ a little):")
            for ((i, r) in results.withIndex()) appendLine("${i + 1}. $r")
            val allUnits = copy.civilizations.asSequence().flatMap { it.units.getCivUnits() }.associateBy { it.id }
            val enemiesKilled = engagement.enemyUnits.filter { it.id !in allUnits }
            val ownLost = engagement.ownUnits.filter { it.id !in allUnits }
            appendLine()
            appendLine("Enemy units destroyed: " + if (enemiesKilled.isEmpty()) "none" else enemiesKilled.joinToString { "#${it.id} ${it.name}" })
            appendLine("Own units lost: " + if (ownLost.isEmpty()) "none" else ownLost.joinToString { "#${it.id} ${it.name}" })
            for (city in engagement.enemyCities) {
                val copyCity = copy.civilizations.asSequence().flatMap { it.cities }.firstOrNull { it.id == city.id } ?: continue
                if (copyCity.civ.civName == civ.civName) appendLine("${city.ref()} ${city.name} CAPTURED")
                else if (copyCity.health != city.health) appendLine("${city.ref()} ${city.name} hp ${city.health} -> ${copyCity.health}")
            }
            // Where our units end up, and what the enemy can do to them there
            val threatMap = TacticalThreatMap.of(copyCiv)
            appendLine()
            appendLine("Our units after the plan (enemy damage expected on their turn):")
            for (unit in engagement.ownUnits + engagement.reinforcements) {
                val copyUnit = allUnits[unit.id] ?: continue
                val threat = threatMap.damageOn(copyUnit, copyUnit.getTile())
                val danger = if (threat >= copyUnit.health) " PROBABLY DIES" else ""
                appendLine("#${unit.id} ${unit.name} at ${copyUnit.getTile().ref()} hp ${copyUnit.health}, expected damage $threat$danger" +
                    if (unit.id !in executor.handledUnitIds) " (no order: default AI moves it)" else "")
            }
        }
        return Simulation(results, feedback)
    }
}
