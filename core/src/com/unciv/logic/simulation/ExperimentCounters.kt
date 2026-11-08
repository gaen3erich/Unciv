package com.unciv.logic.simulation

import com.unciv.logic.automation.civilization.strategy.StrategicPhase

/**
 * Per-civilization counters for AI experiments ([AiExperiment]).
 * Kept on [com.unciv.logic.civilization.Civilization] as a transient, so they are never saved.
 * Each game in an experiment uses its own Civilization objects, so plain fields are thread-safe enough.
 */
class ExperimentCounters {
    /** Enemy military units this civ destroyed in combat */
    var unitsKilled = 0
    /** Own military units destroyed in combat */
    var unitsLost = 0
    var citiesConquered = 0
    var citiesLost = 0
    /** Wall-clock time spent in this civ's AI automation, nanoseconds */
    var aiTurnNanos = 0L
    var aiTurns = 0

    /** [com.unciv.logic.automation.AiFeature.UnitTactics]: attack options dropped as too dangerous */
    var tacticalAttacksSkipped = 0
    /** UnitTactics: units that stepped off a tile where they would probably have been killed */
    var tacticalEscapes = 0
    /** UnitTactics: advances that stopped short of a deadly tile */
    var tacticalStops = 0
    /** UnitTactics: approaches to an enemy city held back for lack of local superiority */
    var tacticalStagingHolds = 0
    /** [com.unciv.logic.automation.AiFeature.FocusFire]: attacks chosen because they kill or set up a kill */
    var focusFireAttacks = 0

    /** [com.unciv.logic.automation.AiFeature.LlmCommander]: battles handed to the model */
    var commanderBattles = 0
    /** LlmCommander: model calls, re-plans after surprises included */
    var commanderCalls = 0
    var commanderOrdersOk = 0
    /** LlmCommander: orders that could not be carried out; their units fell back to the default AI */
    var commanderOrdersFailed = 0
    /** LlmCommander: battles where the model call or the answer failed and the default AI took over */
    var commanderModelErrors = 0
    var commanderProductionHints = 0
    /** Detailed strategic plan reviews, see [com.unciv.logic.automation.civilization.strategy.StrategicPlanner] */
    var strategicReviews = 0
    /** Reviews that ran because something happened, not because they were scheduled */
    var triggeredStrategicReviews = 0
    /** Turns spent in each strategic phase */
    val phaseTurns = IntArray(StrategicPhase.entries.size)

    fun countPhaseTurn(phase: StrategicPhase) { phaseTurns[phase.ordinal]++ }

    /** Own military units that died attacking (the rest died defending) */
    var unitsLostAttacking = 0
    /** Kills and losses by the strategic phase the civ was in; only counted for civs with a plan */
    val unitsKilledByPhase = IntArray(StrategicPhase.entries.size)
    val unitsLostByPhase = IntArray(StrategicPhase.entries.size)

    /** Production cost of the enemy military units this civ destroyed in combat; the score of [TacticalArena] */
    var unitCostKilled = 0
    /** Production cost of the own military units destroyed in combat */
    var unitCostLost = 0

    /** @param cost production cost of the killed unit; units that cannot be built have a negative one and count as 0 */
    fun unitKilled(phase: StrategicPhase?, cost: Int = 0) {
        unitsKilled++
        unitCostKilled += cost.coerceAtLeast(0)
        if (phase != null) unitsKilledByPhase[phase.ordinal]++
    }

    fun unitLost(phase: StrategicPhase?, attacking: Boolean, cost: Int = 0) {
        unitsLost++
        unitCostLost += cost.coerceAtLeast(0)
        if (attacking) unitsLostAttacking++
        if (phase != null) unitsLostByPhase[phase.ordinal]++
    }
}
