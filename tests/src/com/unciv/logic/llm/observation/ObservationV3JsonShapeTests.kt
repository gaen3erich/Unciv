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
class ObservationV3JsonShapeTests {

    @Test
    fun `encoded observation keeps v3 closed city shape`() {
        val observation = LlmActionCollector().buildObservation(LlmGatewayTestFixtures.gameWithFoundedCity().gameInfo)
        val json = JsonReader().parse(LlmJsonCodec().encodeObservation(observation))
        val city = json.get("cities").get(0)

        assertEquals(LlmObservationVersion.CURRENT, json.getString("schemaVersion"))
        assertEquals(
            setOf(
                "cityId",
                "name",
                "population",
                "currentConstruction",
                "productionPerTurn",
                "queue",
                "location",
                "health",
                "defenders",
                "visibleEnemies",
                "workableTilesSummary",
                "expansionPressure"
            ),
            city.map { it.name }.toSet()
        )
    }

    @Test
    fun `encoded v3 nested blocks have stable shape`() {
        val observation = LlmActionCollector().buildObservation(LlmGatewayTestFixtures.gameWithFoundedCity().gameInfo)
        val city = JsonReader().parse(LlmJsonCodec().encodeObservation(observation)).get("cities").get(0)

        assertEquals(setOf("x", "y"), city.get("location").map { it.name }.toSet())
        assertEquals(setOf("current", "max", "isUnderAttack", "isCritical"), city.get("health").map { it.name }.toSet())
        assertEquals(setOf("garrison", "supportingUnitCount", "supportingUnitTypeBreakdown"), city.get("defenders").map { it.name }.toSet())
        assertEquals(setOf("totalCount", "closestDistance", "byCiv", "brief"), city.get("visibleEnemies").map { it.name }.toSet())
        assertTrue(city.get("visibleEnemies").get("brief").size <= 5)
    }
}
