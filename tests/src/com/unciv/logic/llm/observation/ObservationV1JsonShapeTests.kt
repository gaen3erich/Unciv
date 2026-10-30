package com.unciv.logic.llm.observation

import com.badlogic.gdx.utils.JsonReader
import com.unciv.logic.llm.LlmActionCollector
import com.unciv.logic.llm.LlmGatewayTestFixtures
import com.unciv.logic.llm.LlmObservationVersion
import com.unciv.logic.llm.io.LlmJsonCodec
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class ObservationV1JsonShapeTests {

    @Test
    fun `encoded observation keeps v1 city fields`() {
        val observation = LlmActionCollector().buildObservation(LlmGatewayTestFixtures.gameWithFoundedCity().gameInfo)
        val json = JsonReader().parse(LlmJsonCodec().encodeObservation(observation))

        assertEquals(LlmObservationVersion.CURRENT, json.getString("schemaVersion"))
        val city = json.get("cities").get(0)
        assertTrue(
            city.map { it.name }.toSet().containsAll(
            setOf("cityId", "name", "population", "currentConstruction", "productionPerTurn", "queue"),
            )
        )
    }

    @Test
    fun `legal actions summary matches encoded legal actions`() {
        val observation = LlmActionCollector().buildObservation(LlmGatewayTestFixtures.gameWithFoundedCity().gameInfo)
        val json = JsonReader().parse(LlmJsonCodec().encodeObservation(observation))
        val legalActions = json.get("legalActions")
        val summary = json.get("legalActionsSummary")

        val technologyCount = legalActions.count { it.getString("kind") == "ChooseTechnology" }
        val constructionCount = legalActions.count { it.getString("kind") == "ChooseCityConstruction" }
        val hasEndTurn = legalActions.any { it.getString("kind") == "EndTurn" }
        val constructionSummaryCount = summary.get("constructionsByCity").sumOf { it.getInt("constructions") }

        assertEquals(technologyCount, summary.getInt("technologies"))
        assertEquals(constructionCount, constructionSummaryCount)
        assertEquals(hasEndTurn, summary.getBoolean("endTurn"))
    }

    @Test
    fun `legal action kinds are closed set`() {
        val observation = LlmActionCollector().buildObservation(LlmGatewayTestFixtures.gameWithFoundedCity().gameInfo)
        val json = JsonReader().parse(LlmJsonCodec().encodeObservation(observation))
        val allowedKinds = setOf(
            "ChooseTechnology", "ChooseCityConstruction",
            "RunUnitAutomation", "AttackCity", "DefendCity", "DeclareWar", "OfferPeace", "FoundCity",
            "EndTurn"
        )

        for (action in json.get("legalActions")) {
            assertTrue(allowedKinds.contains(action.getString("kind")))
        }
    }
}
