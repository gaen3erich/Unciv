package com.unciv.logic.llm.observation

import com.unciv.logic.llm.LlmActionCollector
import com.unciv.logic.llm.LlmGatewayTestFixtures
import com.unciv.logic.llm.io.LlmJsonCodec
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class ObservationV1DeterminismTests {

    @Test
    fun `building the same observation twice is deterministic as object and json`() {
        val fixture = LlmGatewayTestFixtures.gameWithFoundedCity()
        val collector = LlmActionCollector()
        val codec = LlmJsonCodec()

        val first = collector.buildObservation(fixture.gameInfo)
        val second = collector.buildObservation(fixture.gameInfo)

        assertEquals(first, second)
        assertEquals(codec.encodeObservation(first), codec.encodeObservation(second))
    }

    @Test
    fun `cities and researched techs are sorted in observation`() {
        val fixture = LlmGatewayTestFixtures.gameWithFoundedCity()
        val observation = LlmActionCollector().buildObservation(fixture.gameInfo)

        assertEquals(observation.cities.map { it.cityId }.sorted(), observation.cities.map { it.cityId })
        assertEquals(observation.researchedTechs.sorted(), observation.researchedTechs)
    }

    @Test
    fun `legal actions order matches collector order`() {
        val fixture = LlmGatewayTestFixtures.gameWithFoundedCity()
        val collector = LlmActionCollector()

        val observation = collector.buildObservation(fixture.gameInfo)

        assertEquals(collector.collectLegalActions(fixture.gameInfo), observation.legalActions)
    }
}
