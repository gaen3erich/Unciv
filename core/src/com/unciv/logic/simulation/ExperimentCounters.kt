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

    /** Detailed strategic plan reviews, see [com.unciv.logic.automation.civilization.strategy.StrategicPlanner] */
    var strategicReviews = 0
    /** Reviews that ran because something happened, not because they were scheduled */
    var triggeredStrategicReviews = 0
    /** Turns spent in each strategic phase */
    val phaseTurns = IntArray(StrategicPhase.entries.size)

    fun countPhaseTurn(phase: StrategicPhase) { phaseTurns[phase.ordinal]++ }
}
