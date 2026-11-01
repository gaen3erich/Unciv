package com.unciv.logic.simulation

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
}
