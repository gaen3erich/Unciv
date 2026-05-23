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
class ObservationV4JsonShapeTests {

    @Test
    fun `encoded observation has v4 diplomacy shape`() {
        val observation = LlmActionCollector().buildObservation(LlmGatewayTestFixtures.gameWithFoundedCity().gameInfo)
        val json = JsonReader().parse(LlmJsonCodec().encodeObservation(observation))
        val diplomacy = json.get("diplomacy")

        assertEquals("llm-obs/v4", json.getString("schemaVersion"))
        assertEquals(LlmObservationVersion.CURRENT, json.getString("schemaVersion"))
        assertEquals(
            setOf("knownCivs", "activeWars", "activeTrades", "threats", "relativeStanding"),
            diplomacy.map { it.name }.toSet()
        )
        assertEquals(
            setOf(
                "score",
                "population",
                "growth",
                "production",
                "gold",
                "territory",
                "force",
                "happiness",
                "technologies",
                "culture"
            ),
            diplomacy.get("relativeStanding").map { it.name }.toSet()
        )
    }
}
