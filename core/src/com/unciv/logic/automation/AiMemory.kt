package com.unciv.logic.automation

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.automation.civilization.strategy.StrategicPlan

/**
 * What the AI of one civilization remembers between turns, saved with the game.
 *
 * Most AI decisions are recomputed from scratch every turn. Anything that needs to persist
 * (intentions, plans, things the AI noticed earlier) belongs here rather than in hacks such as
 * negative diplomacy flags.
 */
class AiMemory : IsPartOfGameInfoSerialization {
    /** Long-term goal and current phase, see [com.unciv.logic.automation.civilization.strategy.StrategicPlanner] */
    var strategicPlan = StrategicPlan()

    fun clone(): AiMemory {
        val toReturn = AiMemory()
        toReturn.strategicPlan = strategicPlan.clone()
        return toReturn
    }
}
