package com.unciv.logic.llm.observation

import com.unciv.logic.llm.LlmActionCollector
import com.unciv.logic.llm.LlmGatewayTestFixtures
import com.unciv.logic.map.HexCoord
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class ObservationV3WorkableTilesTests {

    @Test
    fun `workable tiles summary counts tiles and never has worked tiles above total`() {
        val observation = LlmActionCollector().buildObservation(LlmGatewayTestFixtures.gameWithFoundedCity().gameInfo)
        val summary = observation.cities.single().workableTilesSummary

        assertTrue(summary.totalTiles > 0)
        assertTrue(summary.workedTiles <= summary.totalTiles)
    }

    @Test
    fun `workable tile resource counts include visible bonus resources`() {
        val fixture = LlmGatewayTestFixtures.gameWithFoundedCity()
        val wheatTile = fixture.testGame.getTile(HexCoord.Zero)
        wheatTile.setTileResource("Wheat")

        val resources = LlmActionCollector().buildObservation(fixture.gameInfo).cities.single().workableTilesSummary.resourceCounts

        assertEquals(1, resources["Wheat"])
    }

    @Test
    fun `workable tile map fields are deterministic`() {
        val fixture = LlmGatewayTestFixtures.gameWithFoundedCity()
        fixture.testGame.getTile(HexCoord.Zero).setTileResource("Wheat")
        val collector = LlmActionCollector()

        val first = collector.buildObservation(fixture.gameInfo).cities.single().workableTilesSummary
        val second = collector.buildObservation(fixture.gameInfo).cities.single().workableTilesSummary

        assertEquals(first.resourceCounts, second.resourceCounts)
        assertEquals(first.improvementCounts, second.improvementCounts)
    }
}
