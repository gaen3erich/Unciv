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
class ObservationV3AdditiveTests {

    @Test
    fun `v3 keeps v1 and v2 top level fields`() {
        val observation = LlmActionCollector().buildObservation(LlmGatewayTestFixtures.gameWithFoundedCity().gameInfo)
        val json = JsonReader().parse(LlmJsonCodec().encodeObservation(observation))

        assertTrue(json.map { it.name }.toSet().containsAll(
            setOf(
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
                "legalActionsSummary",
                "economy",
                "resources"
            )
        ))
        assertEquals(json.getInt("sciencePerTurn"), json.get("economy").getInt("sciencePerTurn"))
    }
}
