package com.unciv.logic.llm

import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class LlmObservationTests {

    @Test
    fun `observation includes current civ state and legal MVP actions`() {
        val fixture = LlmGatewayTestFixtures.gameWithFoundedCity()
        val city = fixture.civ.cities.single()

        val observation = LlmActionCollector().buildObservation(fixture.gameInfo)

        assertEquals(fixture.gameInfo.gameId, observation.gameId)
        assertEquals(fixture.gameInfo.turns, observation.turn)
        assertEquals(fixture.civ.civID, observation.currentPlayerCivId)
        assertEquals(fixture.civ.civName, observation.currentPlayerCivName)
        assertEquals(city.name, observation.cities.single().name)
        assertTrue(observation.legalActions.any { it is LlmLegalAction.ChooseTechnology && it.techName == "Pottery" })
        assertTrue(observation.legalActions.any { it is LlmLegalAction.ChooseCityConstruction && it.constructionName == "Monument" })
        assertTrue(observation.legalActions.any { it is LlmLegalAction.EndTurn })
    }

    @Test
    fun `observation city queue is a snapshot and is not mutated by later city changes`() {
        val fixture = LlmGatewayTestFixtures.gameWithFoundedCity()
        val city = fixture.civ.cities.single()

        val observation = LlmActionCollector().buildObservation(fixture.gameInfo)
        city.cityConstructions.addToQueue("Monument")

        assertTrue(observation.cities.single().queue.isEmpty())
        assertEquals(listOf("Monument"), city.cityConstructions.constructionQueue)
    }

    @Test
    fun `online multiplayer observation exposes no legal actions`() {
        val fixture = LlmGatewayTestFixtures.gameWithFoundedCity()
        fixture.gameInfo.gameParameters.isOnlineMultiplayer = true

        val observation = LlmActionCollector().buildObservation(fixture.gameInfo)

        assertTrue(observation.isOnlineMultiplayer)
        assertFalse(observation.legalActions.isNotEmpty())
    }
}
