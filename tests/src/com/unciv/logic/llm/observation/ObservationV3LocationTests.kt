package com.unciv.logic.llm.observation

import com.unciv.logic.llm.LlmActionCollector
import com.unciv.logic.llm.LlmGatewayTestFixtures
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class ObservationV3LocationTests {

    @Test
    fun `city observation includes stable center location`() {
        val fixture = LlmGatewayTestFixtures.gameWithFoundedCity()
        val city = fixture.civ.cities.single()

        val observedCity = LlmActionCollector().buildObservation(fixture.gameInfo).cities.single()

        assertEquals(city.location.x, observedCity.location.x)
        assertEquals(city.location.y, observedCity.location.y)
    }

    @Test
    fun `same game produces same city location`() {
        val fixture = LlmGatewayTestFixtures.gameWithFoundedCity()
        val collector = LlmActionCollector()

        val first = collector.buildObservation(fixture.gameInfo).cities.single().location
        val second = collector.buildObservation(fixture.gameInfo).cities.single().location

        assertEquals(first, second)
    }
}
