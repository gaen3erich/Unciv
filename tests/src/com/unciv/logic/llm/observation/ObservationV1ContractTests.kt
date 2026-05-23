package com.unciv.logic.llm.observation

import com.unciv.logic.llm.LlmActionCollector
import com.unciv.logic.llm.LlmGatewayTestFixtures
import com.unciv.logic.llm.LlmObservationVersion
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.reflect.full.memberProperties

@RunWith(GdxTestRunner::class)
class ObservationV1ContractTests {

    @Test
    fun `observation has v1 schema and expected top level properties`() {
        val observation = LlmActionCollector().buildObservation(LlmGatewayTestFixtures.gameWithFoundedCity().gameInfo)

        assertEquals(LlmObservationVersion.CURRENT, observation.schemaVersion)
        assertTrue(
            observation::class.memberProperties.map { it.name }.toSet().containsAll(
                setOf(
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
                    "economy",
                    "resources",
                    "legalActionsSummary",
                    "schemaVersion"
                )
            )
        )
    }

    @Test
    fun `empty city queue is represented as null currentConstruction`() {
        val observation = LlmActionCollector().buildObservation(LlmGatewayTestFixtures.gameWithFoundedCity().gameInfo)

        assertNull(observation.cities.single().currentConstruction)
    }

    @Test
    fun `civ without active research has null currentResearch`() {
        val observation = LlmActionCollector().buildObservation(LlmGatewayTestFixtures.smallGame().gameInfo)

        assertNull(observation.currentResearch)
    }
}
