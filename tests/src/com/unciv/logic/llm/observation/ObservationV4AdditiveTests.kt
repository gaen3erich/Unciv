package com.unciv.logic.llm.observation

import com.badlogic.gdx.utils.JsonReader
import com.unciv.logic.llm.LlmActionCollector
import com.unciv.logic.llm.LlmGatewayTestFixtures
import com.unciv.logic.llm.io.LlmJsonCodec
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class ObservationV4AdditiveTests {

    @Test
    fun `v4 keeps previous top level fields and v3 city shape`() {
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
                "resources",
                "diplomacy"
            )
        ))
        assertTrue(json.get("cities").get(0).map { it.name }.toSet().containsAll(
            setOf("location", "health", "defenders", "visibleEnemies", "workableTilesSummary", "expansionPressure")
        ))
    }
}
