package com.unciv.logic.llm.characterization.tech

import com.unciv.logic.civilization.Civilization
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
 * Captures the current behavior of TechManager.canBeResearched(...)
 * so the gateway can rely on it when collecting legal research actions.
 *
 * Captured on:  2026-05-22
 * Captured by:  Codex
 * Source:       Boundary exploration for LlmActionCollector research filtering.
 * Status:       FAITHFUL for ordinary known-tech cases, SMELL for unknown tech.
 * Notes:        Unknown tech throws NullPointerException; see docs/known-behaviors.md
 *               #techmanager-unknown-tech-throws.
 * Related:      docs/LLM_GATEWAY_MVP_PLAN.md section 2 "Collect legal research actions"
 */
@RunWith(GdxTestRunner::class)
class TechManagerCharacterization {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization

    @Before
    fun setUp() {
        game = TestGame()
        game.makeHexagonalMap(2)
        civ = game.addCiv(isPlayer = true)
        game.gameInfo.currentPlayerCiv = civ
        game.gameInfo.currentPlayer = civ.civID
    }

    @Test
    fun `canBeResearched is true for Pottery at default TestGame start`() {
        val actual = civ.tech.canBeResearched("Pottery")

        assertEquals(true, actual)
    }

    @Test
    fun `canBeResearched is false for Philosophy at default TestGame start`() {
        val actual = civ.tech.canBeResearched("Philosophy")

        assertEquals(false, actual)
    }

    @Test
    fun `canBeResearched is false for an already researched non-repeatable tech`() {
        civ.tech.addTechnology("Pottery", showNotification = false)

        val actual = civ.tech.canBeResearched("Pottery")

        assertEquals(false, actual)
    }

    @Test
    fun `canBeResearched throws for unknown tech name`() {
        // SMELL: Unknown tech is not distinguishable by return value because this throws.
        // Gateway collectors must check ruleset.technologies before calling canBeResearched.
        // See docs/known-behaviors.md #techmanager-unknown-tech-throws.
        assertThrows(NullPointerException::class.java) {
            civ.tech.canBeResearched("DefinitelyNotARealTech")
        }
    }
}
