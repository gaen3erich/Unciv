package com.unciv.logic.llm.observation

import com.unciv.logic.llm.LlmActionCollector
import com.unciv.logic.llm.LlmGatewayTestFixtures
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class ObservationV3ExpansionTests {

    @Test
    fun `city expansion pressure includes culture and owned tiles`() {
        val fixture = LlmGatewayTestFixtures.gameWithFoundedCity()
        val pressure = LlmActionCollector().buildObservation(fixture.gameInfo).cities.single().expansionPressure

        assertEquals(fixture.civ.cities.single().expansion.cultureStored, pressure.cultureStored)
        assertTrue(pressure.ownedTiles > 0)
    }

    @Test
    fun `nearest foreign city is included geometrically`() {
        val fixture = LlmGatewayTestFixtures.twoHumanCivGame()
        fixture.secondCiv.cities.single().getCenterTile().setExplored(fixture.firstCiv, true)

        val pressure = LlmActionCollector().buildObservation(fixture.gameInfo, fixture.firstCiv).cities.first().expansionPressure
        val nearestForeignCity = pressure.nearestForeignCity!!

        assertEquals(fixture.secondCiv.civName, nearestForeignCity.civName)
        assertEquals(fixture.secondCiv.cities.single().name, nearestForeignCity.cityName)
    }

    @Test
    fun `unexplored foreign city is not revealed`() {
        val fixture = LlmGatewayTestFixtures.twoHumanCivGame()
        fixture.secondCiv.cities.single().getCenterTile().setExplored(fixture.firstCiv, false)

        val pressure = LlmActionCollector().buildObservation(fixture.gameInfo, fixture.firstCiv).cities.first().expansionPressure

        assertNull(pressure.nearestForeignCity)
    }
}
