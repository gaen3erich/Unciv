package com.unciv.logic.llm.observation

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyManager
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus

object ObservationV4TestHelpers {
    fun meet(
        first: Civilization,
        second: Civilization,
        status: DiplomaticStatus = DiplomaticStatus.Peace
    ): Pair<DiplomacyManager, DiplomacyManager> {
        val firstToSecond = DiplomacyManager(first, second).apply { diplomaticStatus = status }
        val secondToFirst = DiplomacyManager(second, first).apply { diplomaticStatus = status }
        first.diplomacy[second.civID] = firstToSecond
        second.diplomacy[first.civID] = secondToFirst
        return firstToSecond to secondToFirst
    }
}
