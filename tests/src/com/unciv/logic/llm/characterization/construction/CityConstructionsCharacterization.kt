package com.unciv.logic.llm.characterization.construction

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CHARACTERIZATION TEST
 *
 * Captures the current behavior of CityConstructions queue operations
 * so the gateway can apply city production through existing manager APIs.
 *
 * Captured on:  2026-05-22
 * Captured by:  Codex
 * Source:       Boundary exploration for LlmActionApplier construction actions.
 * Status:       FAITHFUL
 * Notes:        Current construction is queue[0]; user-set bookkeeping is changed by add/remove APIs.
 * Related:      docs/LLM_GATEWAY_MVP_PLAN.md section 2 "Apply city construction action"
 */
@RunWith(GdxTestRunner::class)
class CityConstructionsCharacterization {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var city: City

    @Before
    fun setUp() {
        game = TestGame()
        game.makeHexagonalMap(2)
        civ = game.addCiv(isPlayer = true)
        game.gameInfo.currentPlayerCiv = civ
        game.gameInfo.currentPlayer = civ.civID
        city = game.addCity(civ, game.getTile(HexCoord.Zero))
    }

    @Test
    fun `addToQueue on empty queue sets current construction and marks it user set`() {
        city.cityConstructions.addToQueue("Monument")

        assertEquals(listOf("Monument"), city.cityConstructions.constructionQueue)
        assertEquals("Monument", city.cityConstructions.currentConstructionName())
        assertTrue(city.cityConstructions.currentConstructionIsUserSet)
    }

    @Test
    fun `addToQueue appends unit after current building`() {
        city.cityConstructions.addToQueue("Monument")
        city.cityConstructions.addToQueue("Warrior")

        assertEquals(listOf("Monument", "Warrior"), city.cityConstructions.constructionQueue)
        assertEquals("Monument", city.cityConstructions.currentConstructionName())
        assertTrue(city.cityConstructions.currentConstructionIsUserSet)
    }

    @Test
    fun `canAddToQueue is false for building already in queue`() {
        val monument = game.ruleset.buildings["Monument"]!!
        city.cityConstructions.addToQueue(monument)

        val actual = city.cityConstructions.canAddToQueue(monument)

        assertFalse(actual)
        assertEquals(listOf("Monument"), city.cityConstructions.constructionQueue)
    }

    @Test
    fun `removing only queue entry non-automatically leaves idle construction and clears user set flag`() {
        city.cityConstructions.addToQueue("Monument")

        city.cityConstructions.removeFromQueue(0, automatic = false)

        assertEquals(listOf(PerpetualConstruction.idle.name), city.cityConstructions.constructionQueue)
        assertEquals(PerpetualConstruction.idle.name, city.cityConstructions.currentConstructionName())
        assertFalse(city.cityConstructions.currentConstructionIsUserSet)
    }
}
