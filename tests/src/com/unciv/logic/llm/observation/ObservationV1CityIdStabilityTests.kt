package com.unciv.logic.llm.observation

import com.unciv.logic.llm.LlmActionCollector
import com.unciv.logic.llm.LlmGatewayTestFixtures
import com.unciv.logic.map.HexCoord
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class ObservationV1CityIdStabilityTests {

    @Test
    fun `cityId is stable when city is renamed`() {
        val fixture = LlmGatewayTestFixtures.gameWithFoundedCity()
        val city = fixture.civ.cities.single()
        val before = LlmActionCollector().buildObservation(fixture.gameInfo).cities.single()

        city.name = "Renamed City"
        val after = LlmActionCollector().buildObservation(fixture.gameInfo).cities.single()

        assertEquals(before.cityId, after.cityId)
        assertNotEquals(before.name, after.name)
    }

    @Test
    fun `cityId differs for two cities with the same name`() {
        val fixture = LlmGatewayTestFixtures.gameWithFoundedCity()
        val first = fixture.civ.cities.single()
        val second = fixture.testGame.addCity(fixture.civ, fixture.testGame.getTile(HexCoord(1, 0)))
        second.name = first.name

        val cities = LlmActionCollector().buildObservation(fixture.gameInfo).cities

        assertEquals(2, cities.map { it.cityId }.toSet().size)
        assertEquals(1, cities.map { it.name }.toSet().size)
    }
}
