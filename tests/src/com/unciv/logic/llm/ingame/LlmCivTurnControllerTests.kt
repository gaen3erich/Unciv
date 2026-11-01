package com.unciv.logic.llm.ingame

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.llm.LlmLegalAction
import com.unciv.logic.llm.LlmObservation
import com.unciv.logic.llm.LlmPolicy
import com.unciv.logic.map.HexCoord
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class LlmCivTurnControllerTests {
    private lateinit var game: TestGame
    private lateinit var gameInfo: GameInfo
    private lateinit var human: Civilization
    private lateinit var firstAi: Civilization
    private lateinit var secondAi: Civilization

    /** Picks the first legal tech, then ends the turn; remembers whom it played for. */
    private class TechThenEndTurn : LlmPolicy {
        val calls = ArrayList<String>()
        var chosenTech: String? = null
        override fun chooseActions(observation: LlmObservation): List<LlmLegalAction> {
            calls += observation.currentPlayerCivName
            val tech = observation.legalActions.filterIsInstance<LlmLegalAction.ChooseTechnology>().first()
            chosenTech = tech.techName
            return listOf(tech, LlmLegalAction.EndTurn())
        }
    }

    @Before
    fun setUp() {
        game = TestGame()
        game.makeHexagonalMap(4)
        gameInfo = game.gameInfo
        human = game.addCiv(isPlayer = true)
        firstAi = game.addCiv()
        secondAi = game.addCiv()
        game.addCity(human, game.getTile(HexCoord.Zero))
        game.addCity(firstAi, game.getTile(HexCoord(3, 0)))
        game.addCity(secondAi, game.getTile(HexCoord(-3, 0)))
        gameInfo.currentPlayerCiv = human
        gameInfo.currentPlayer = human.civID
        gameInfo.turns = 1
    }

    @After
    fun tearDown() {
        LlmInGameAgent.controller = null
    }

    @Test
    fun `without civ names the first AI major civ is controlled`() {
        val controller = LlmCivTurnController(TechThenEndTurn())
        assertFalse(controller.controls(human))
        assertTrue(controller.controls(firstAi))
        assertFalse(controller.controls(secondAi))
    }

    @Test
    fun `civ names pick the controlled civs`() {
        val controller = LlmCivTurnController(TechThenEndTurn(), civNames = setOf(secondAi.civName.uppercase()))
        assertFalse(controller.controls(firstAi))
        assertTrue(controller.controls(secondAi))
    }

    @Test
    fun `the turn loop lets the agent play its civ and comes back to the human`() {
        val policy = TechThenEndTurn()
        val log = ArrayList<String>()
        LlmInGameAgent.controller = LlmCivTurnController(policy, civNames = setOf(firstAi.civName), log = { log += it })

        gameInfo.nextTurn(progressBar = null)

        // One call: end_turn ends the agent's loop without recursing into nextTurn
        assertEquals(listOf(firstAi.civName), policy.calls)
        assertEquals(human, gameInfo.currentPlayerCiv)
        assertEquals(2, gameInfo.turns)
        assertTrue(firstAi.tech.techsToResearch.isNotEmpty() || firstAi.tech.isResearched(policy.chosenTech!!))
        assertEquals(policy.chosenTech, firstAi.tech.techsToResearch.firstOrNull() ?: policy.chosenTech)
        assertTrue(log.single().contains("end_turn"))
    }

    @Test
    fun `a failing policy falls back to the built-in AI for that turn`() {
        LlmInGameAgent.controller = LlmCivTurnController(object : LlmPolicy {
            override fun chooseActions(observation: LlmObservation): List<LlmLegalAction> = throw IllegalStateException("boom")
        }, log = {})

        gameInfo.nextTurn(progressBar = null)

        assertEquals(human, gameInfo.currentPlayerCiv)
        assertTrue(firstAi.tech.techsToResearch.isNotEmpty())
    }
}
