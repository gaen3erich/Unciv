package com.unciv.logic.llm.observation

import com.badlogic.gdx.utils.JsonReader
import com.unciv.logic.llm.LlmActionCollector
import com.unciv.logic.llm.LlmGatewayTestFixtures
import com.unciv.logic.llm.io.LlmJsonCodec
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class ObservationV2AdditiveTests {

    @Test
    fun `encoded v2 observation keeps all v1 top level fields`() {
        val observation = LlmActionCollector().buildObservation(LlmGatewayTestFixtures.gameWithFoundedCity().gameInfo)
        val json = JsonReader().parse(LlmJsonCodec().encodeObservation(observation))
        val v1Fields = setOf(
            "schemaVersion",
            "gameId",
            "turn",
            "currentPlayerCivId",
            "currentPlayerCivName",
            "isOnlineMultiplayer",
            "currentResearch",
            "sciencePerTurn",
            "cities",
            "researchedTechs",
            "legalActions",
            "legalActionsSummary"
        )

        for (field in v1Fields) {
            assertTrue("Missing v1 field $field", json.has(field))
        }
    }

    @Test
    fun `top level sciencePerTurn duplicates economy sciencePerTurn`() {
        val observation = LlmActionCollector().buildObservation(LlmGatewayTestFixtures.gameWithFoundedCity().gameInfo)

        assertEquals(observation.sciencePerTurn, observation.economy.sciencePerTurn)
    }

    @Test
    fun `city observation keeps v1 fields`() {
        val observation = LlmActionCollector().buildObservation(LlmGatewayTestFixtures.gameWithFoundedCity().gameInfo)
        val json = JsonReader().parse(LlmJsonCodec().encodeObservation(observation))

        val cityFields = json.get("cities").get(0).map { it.name }.toSet()
        for (field in setOf("cityId", "name", "population", "currentConstruction", "productionPerTurn", "queue")) {
            assertTrue("Missing v1 city field $field", cityFields.contains(field))
        }
    }
}
