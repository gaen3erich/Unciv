package com.unciv.logic.llm.characterization.turn

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CHARACTERIZATION TEST
 *
 * Captures the current behavior of GameInfo.nextTurn(null) for a local
 * two-human-civilization game so the gateway can rely on the headless
 * end-turn path without going through WorldScreen.
 *
 * Captured on:  2026-05-22
 * Captured by:  Codex
 * Source:       Boundary exploration for LlmActionApplier EndTurn.
 * Status:       FAITHFUL for turn rotation, SMELL for turn-10 music-controller access.
 * Notes:        The first end-turn switches to the second human without incrementing turns;
 *               the second wraps to the first human and increments turns. At turn multiples
 *               of 10, nextTurn touches UncivGame.Current.musicController.
 * Related:      docs/LLM_GATEWAY_MVP_PLAN.md section 2 "End turn"
 */
@RunWith(GdxTestRunner::class)
class GameInfoNextTurnCharacterization {
    private lateinit var game: TestGame
    private lateinit var gameInfo: GameInfo
    private lateinit var firstCiv: Civilization
    private lateinit var secondCiv: Civilization

    @Before
    fun setUp() {
        game = TestGame()
        game.makeHexagonalMap(3)
        gameInfo = game.gameInfo
        firstCiv = game.addCiv(isPlayer = true)
        secondCiv = game.addCiv(isPlayer = true)
        game.addCity(firstCiv, game.getTile(HexCoord.Zero))
        game.addCity(secondCiv, game.getTile(HexCoord(2, 0)))
        gameInfo.currentPlayerCiv = firstCiv
        gameInfo.currentPlayer = firstCiv.civID
        // Avoid the music-change branch at turn multiples of 10; that dependency is
        // captured explicitly below.
        gameInfo.turns = 1
    }

    @Test
    fun `nextTurn moves from first human to second human without incrementing turn number`() {
        gameInfo.nextTurn(progressBar = null)

        assertEquals(secondCiv, gameInfo.currentPlayerCiv)
        assertEquals(secondCiv.civID, gameInfo.currentPlayer)
        assertEquals(1, gameInfo.turns)
    }

    @Test
    fun `nextTurn increments turn number after wrapping from second human to first human`() {
        gameInfo.nextTurn(progressBar = null)

        gameInfo.nextTurn(progressBar = null)

        assertEquals(firstCiv, gameInfo.currentPlayerCiv)
        assertEquals(firstCiv.civID, gameInfo.currentPlayer)
        assertEquals(2, gameInfo.turns)
    }

    @Test
    fun `nextTurn at a turn multiple of 10 touches uninitialized music controller in this headless fixture`() {
        gameInfo.turns = 0

        // SMELL: GameInfo.nextTurn is mostly core logic, but every 10 turns it reaches
        // UncivGame.Current.musicController. Headless callers need a bootstrap that
        // initializes musicController or avoids this branch.
        // See docs/known-behaviors.md #gameinfo-nextturn-music-controller.
        assertThrows(UninitializedPropertyAccessException::class.java) {
            gameInfo.nextTurn(progressBar = null)
        }
    }
}
