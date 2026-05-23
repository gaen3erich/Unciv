package com.unciv.logic.llm.observation

import com.badlogic.gdx.utils.JsonReader
import com.unciv.logic.llm.LlmActionCollector
import com.unciv.logic.llm.LlmGatewayTestFixtures
import com.unciv.logic.llm.LlmObservationVersion
import com.unciv.logic.llm.io.LlmJsonCodec
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class ObservationV2JsonShapeTests {

    @Test
    fun `encoded observation has v2 economy and resources shape`() {
        val observation = LlmActionCollector().buildObservation(LlmGatewayTestFixtures.gameWithFoundedCity().gameInfo)
        val json = JsonReader().parse(LlmJsonCodec().encodeObservation(observation))

        assertEquals(LlmObservationVersion.CURRENT, json.getString("schemaVersion"))
        assertEquals(
            setOf(
                "gold",
                "storedCulture",
                "storedFaith",
                "happiness",
                "goldPerTurn",
                "sciencePerTurn",
                "culturePerTurn",
                "faithPerTurn",
                "happinessPerTurn"
            ),
            json.get("economy").map { it.name }.toSet()
        )
        assertEquals(setOf("strategic", "luxury"), json.get("resources").map { it.name }.toSet())
    }

    @Test
    fun `resource entries have stable shape`() {
        val fixture = LlmGatewayTestFixtures.gameWithFoundedCity()
        val city = fixture.civ.cities.single()
        city.cityConstructions.addBuilding(fixture.testGame.createBuilding("Provides [1] [Coal]"))
        val observation = LlmActionCollector().buildObservation(fixture.gameInfo)

        val json = JsonReader().parse(LlmJsonCodec().encodeObservation(observation))

        assertEquals(setOf("name", "amount"), json.get("resources").get("strategic").get(0).map { it.name }.toSet())
    }
}
