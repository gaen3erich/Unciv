package com.unciv.logic.llm.observation

import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.llm.LlmActionCollector
import com.unciv.logic.map.HexCoord
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class ObservationV4VisibilityTests {

    @Test
    fun `third party wars do not appear in observer active wars`() {
        val game = TestGame()
        game.makeHexagonalMap(4)
        val observer = game.addCiv(isPlayer = true)
        val civA = game.addCiv(isPlayer = true)
        val civB = game.addCiv(isPlayer = true)
        game.addCity(observer, game.getTile(HexCoord.Zero))
        game.addCity(civA, game.getTile(HexCoord(2, 0)))
        game.addCity(civB, game.getTile(HexCoord(0, 2)))
        ObservationV4TestHelpers.meet(observer, civA)
        ObservationV4TestHelpers.meet(observer, civB)
        ObservationV4TestHelpers.meet(civA, civB, DiplomaticStatus.War)

        val diplomacy = LlmActionCollector().buildObservation(game.gameInfo, observer).diplomacy

        assertEquals(emptyList<Any>(), diplomacy.activeWars)
        assertFalse(diplomacy.knownCivs.any { it.diplomaticStatus == "War" })
    }

    @Test
    fun `unknown civ does not appear in diplomacy observation`() {
        val game = TestGame()
        game.makeHexagonalMap(3)
        val observer = game.addCiv(isPlayer = true)
        val unknown = game.addCiv(isPlayer = true)
        game.addCity(observer, game.getTile(HexCoord.Zero))
        game.addUnit("Warrior", unknown, game.getTile(HexCoord(2, 0)))
        observer.diplomacy.remove(unknown.civID)
        unknown.diplomacy.remove(observer.civID)

        val diplomacy = LlmActionCollector().buildObservation(game.gameInfo, observer).diplomacy

        assertEquals(emptyList<Any>(), diplomacy.knownCivs)
        assertEquals(1, diplomacy.relativeStanding.score.totalKnownMajors)
    }
}
