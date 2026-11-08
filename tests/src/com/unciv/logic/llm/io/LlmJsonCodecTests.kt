package com.unciv.logic.llm.io

import com.badlogic.gdx.utils.JsonReader
import com.unciv.logic.llm.LlmActionCollector
import com.unciv.logic.llm.LlmGateway
import com.unciv.logic.llm.LlmGatewayTestFixtures
import com.unciv.logic.llm.LlmLegalAction
import com.unciv.logic.llm.LlmObservationVersion
import com.unciv.logic.llm.ScriptedPolicy
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class LlmJsonCodecTests {
    private val codec = LlmJsonCodec()

    @Test
    fun `encodes ChooseTechnology action with canonical collector id`() {
        val action = LlmLegalAction.ChooseTechnology(
            id = LlmActionCollector.chooseTechnologyId("Pottery"),
            techName = "Pottery"
        )

        val json = parse(codec.encodeAction(action))

        assertEquals("ChooseTechnology", json.getString("kind"))
        assertEquals("choose_technology:Pottery", json.getString("id"))
        assertEquals("Pottery", json.getString("techName"))
        assertEquals("Pottery", json.get("path").getString(0))
    }

    @Test
    fun `encodes ChooseCityConstruction action with canonical collector id`() {
        val action = LlmLegalAction.ChooseCityConstruction(
            id = LlmActionCollector.chooseCityConstructionId("Babylon", "Monument"),
            cityId = "Babylon",
            cityName = "Babylon",
            constructionName = "Monument"
        )

        val json = parse(codec.encodeAction(action))

        assertEquals("ChooseCityConstruction", json.getString("kind"))
        assertEquals("choose_city_construction:Babylon:Monument", json.getString("id"))
        assertEquals("Babylon", json.getString("cityId"))
        assertEquals("Monument", json.getString("constructionName"))
        assertEquals(false, json.getBoolean("addToTop"))
    }

    @Test
    fun `encodes EndTurn action`() {
        val json = parse(codec.encodeAction(LlmLegalAction.EndTurn()))

        assertEquals("EndTurn", json.getString("kind"))
        assertEquals("end_turn", json.getString("id"))
    }

    @Test
    fun `encodes observation with schema cities researched techs and legal actions`() {
        val fixture = LlmGatewayTestFixtures.gameWithFoundedCity()
        val observation = LlmActionCollector().buildObservation(fixture.gameInfo)

        val json = parse(codec.encodeObservation(observation))

        assertEquals(LlmObservationVersion.CURRENT, json.getString("schemaVersion"))
        assertEquals(fixture.gameInfo.gameId, json.getString("gameId"))
        assertEquals(fixture.civ.civID, json.getString("currentPlayerCivId"))
        assertEquals(1, json.get("cities").size)
        assertTrue(json.get("researchedTechs").size > 0)
        assertTrue(json.get("legalActions").size > 0)
    }

    @Test
    fun `encodes decision result produced by gateway`() {
        val fixture = LlmGatewayTestFixtures.smallGame()
        val action = LlmGatewayTestFixtures.legalActionById(
            fixture.gameInfo,
            LlmActionCollector.chooseTechnologyId("Pottery")
        )
        val result = LlmGateway(ScriptedPolicy(listOf(action))).step(fixture.gameInfo)

        val json = parse(codec.encodeDecisionResult(result))

        assertEquals(LlmJsonCodec.SCHEMA, json.getString("schema"))
        assertEquals(1, json.get("selectedActions").size)
        assertEquals("ChooseTechnology", json.get("selectedActions").get(0).getString("kind"))
        assertTrue(json.get("appliedActions").size <= 1)
    }

    private fun parse(json: String) = JsonReader().parse(json)
}
