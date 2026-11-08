package com.unciv.logic.llm.observation

import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.llm.LlmActionCollector
import com.unciv.logic.map.HexCoord
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class ObservationV4KnownCivsTests {

    @Test
    fun `known civs include known living civs sorted and exclude observing civ`() {
        val fixture = diplomacyFixture()
        ObservationV4TestHelpers.meet(fixture.observer, fixture.majorB)
        ObservationV4TestHelpers.meet(fixture.observer, fixture.majorC)
        ObservationV4TestHelpers.meet(fixture.observer, fixture.cityState)

        val knownCivs = LlmActionCollector().buildObservation(fixture.game.gameInfo, fixture.observer).diplomacy.knownCivs

        assertEquals(3, knownCivs.size)
        assertEquals(knownCivs.map { it.civName }.sorted(), knownCivs.map { it.civName })
        assertFalse(knownCivs.any { it.civName == fixture.observer.civName })
    }

    @Test
    fun `city-state uses influence and null opinion while major uses opinion and null influence`() {
        val fixture = diplomacyFixture()
        val (majorDiplo, _) = ObservationV4TestHelpers.meet(fixture.observer, fixture.majorB)
        val (cityStateDiplo, _) = ObservationV4TestHelpers.meet(fixture.observer, fixture.cityState)
        majorDiplo.diplomaticModifiers["Test"] = 12.4f
        cityStateDiplo.setInfluenceWithoutSideEffects(37.8f)

        val knownCivs = LlmActionCollector().buildObservation(fixture.game.gameInfo, fixture.observer).diplomacy.knownCivs
        val major = knownCivs.single { it.civName == fixture.majorB.civName }
        val cityState = knownCivs.single { it.civName == fixture.cityState.civName }

        assertEquals(12, major.opinionScore)
        assertNull(major.cityStateInfluence)
        assertNull(cityState.opinionScore)
        assertEquals(37, cityState.cityStateInfluence)
    }

    @Test
    fun `open borders flags are from observing civ perspective`() {
        val fixture = diplomacyFixture()
        val (ourDiplo, theirDiplo) = ObservationV4TestHelpers.meet(fixture.observer, fixture.majorB)
        ourDiplo.hasOpenBorders = true
        theirDiplo.hasOpenBorders = false

        val known = LlmActionCollector().buildObservation(fixture.game.gameInfo, fixture.observer)
            .diplomacy.knownCivs.single { it.civName == fixture.majorB.civName }

        assertTrue(known.hasOpenBorders)
        assertFalse(known.theirsHaveOpenBorders)
    }

    @Test
    fun `war status is reflected in known civ entry`() {
        val fixture = diplomacyFixture()
        ObservationV4TestHelpers.meet(fixture.observer, fixture.majorB, DiplomaticStatus.War)

        val known = LlmActionCollector().buildObservation(fixture.game.gameInfo, fixture.observer)
            .diplomacy.knownCivs.single { it.civName == fixture.majorB.civName }

        assertEquals("War", known.diplomaticStatus)
    }

    private fun diplomacyFixture(): DiplomacyFixture {
        val game = TestGame()
        game.makeHexagonalMap(4)
        val observer = game.addCiv(isPlayer = true)
        val majorB = game.addCiv(isPlayer = true)
        val majorC = game.addCiv(isPlayer = true)
        val cityState = game.addCiv(cityStateType = "Cultured")
        game.addCity(observer, game.getTile(HexCoord.Zero))
        game.addCity(majorB, game.getTile(HexCoord(2, 0)))
        game.addCity(majorC, game.getTile(HexCoord(0, 2)))
        game.addCity(cityState, game.getTile(HexCoord(-2, 0)))
        game.gameInfo.currentPlayerCiv = observer
        game.gameInfo.currentPlayer = observer.civID
        return DiplomacyFixture(game, observer, majorB, majorC, cityState)
    }

    private data class DiplomacyFixture(
        val game: TestGame,
        val observer: com.unciv.logic.civilization.Civilization,
        val majorB: com.unciv.logic.civilization.Civilization,
        val majorC: com.unciv.logic.civilization.Civilization,
        val cityState: com.unciv.logic.civilization.Civilization
    )
}
