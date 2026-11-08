package com.unciv.logic.llm.observation

import com.unciv.logic.llm.LlmActionCollector
import com.unciv.logic.llm.LlmGatewayTestFixtures
import com.unciv.logic.map.HexCoord
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class ObservationV3ResourceVisibilityTests {

    @Test
    fun `unrevealed strategic resource does not appear in workable resource counts`() {
        val fixture = LlmGatewayTestFixtures.gameWithFoundedCity()
        val iron = fixture.gameInfo.ruleset.tileResources["Iron"]!!
        check(iron.revealedBy != null)
        fixture.testGame.getTile(HexCoord.Zero).setTileResource("Iron")

        val resources = LlmActionCollector().buildObservation(fixture.gameInfo).cities.single().workableTilesSummary.resourceCounts

        assertFalse(resources.containsKey("Iron"))
    }

    @Test
    fun `revealed strategic resource appears in workable resource counts`() {
        val fixture = LlmGatewayTestFixtures.gameWithFoundedCity()
        val iron = fixture.gameInfo.ruleset.tileResources["Iron"]!!
        fixture.testGame.getTile(HexCoord.Zero).setTileResource("Iron")
        fixture.civ.tech.addTechnology(iron.revealedBy!!)

        val resources = LlmActionCollector().buildObservation(fixture.gameInfo).cities.single().workableTilesSummary.resourceCounts

        assertTrue(resources.containsKey("Iron"))
    }
}
