package com.unciv.logic.llm.prompt

import com.badlogic.gdx.utils.JsonReader
import com.unciv.logic.llm.LlmActionCollector
import com.unciv.logic.llm.LlmGatewayTestFixtures
import com.unciv.logic.llm.LlmLegalAction
import com.unciv.logic.llm.client.LlmMessage
import com.unciv.logic.llm.client.LlmRequestKind
import com.unciv.logic.llm.io.LlmJsonCodec
import com.unciv.logic.map.HexCoord
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class LlmPromptBuilderTests {
    @Test
    fun `builds decision request with compact state and every legal action`() {
        val observation = LlmActionCollector().buildObservation(LlmGatewayTestFixtures.gameWithFoundedCity().gameInfo)

        val request = LlmPromptBuilder().build(observation)
        val content = request.messages.single().content

        assertEquals(LlmRequestKind.DECISION, request.kind)
        assertEquals(0.0, request.temperature, 0.0)
        assertTrue(request.cacheSystemPrompt)
        assertEquals(LlmMessage.Role.USER, request.messages.single().role)
        assertFalse("legal actions must not be embedded as JSON", content.contains("```json"))
        assertFalse("raw city ids must not leak into the prompt", content.contains(observation.cities.single().cityId))
        assertListsEveryAction(content, observation.legalActions, LlmActionAliases.of(observation))
        JsonReader().parse(request.toolSchema)
    }

    @Test
    fun `stable prefix does not depend on the observation`() {
        val first = LlmPromptBuilder().build(LlmActionCollector().buildObservation(LlmGatewayTestFixtures.smallGame().gameInfo))
        val second = LlmPromptBuilder().build(LlmActionCollector().buildObservation(LlmGatewayTestFixtures.gameWithFoundedCity().gameInfo))

        assertEquals(first.systemPrompt, second.systemPrompt)
        assertEquals(first.toolSchema, second.toolSchema)
    }

    @Test
    fun `compact prompt is less than half of the json observation`() {
        val fixture = LlmGatewayTestFixtures.twoHumanCivGame()
        fixture.testGame.addCity(fixture.firstCiv, fixture.testGame.getTile(HexCoord(-2, 0)))
        fixture.testGame.addCity(fixture.firstCiv, fixture.testGame.getTile(HexCoord(0, -3)))
        val observation = LlmActionCollector().buildObservation(fixture.gameInfo, fixture.firstCiv)

        val prompt = LlmPromptBuilder().build(observation).messages.single().content
        val json = LlmJsonCodec().encodeObservation(observation)

        assertTrue("prompt ${prompt.length} chars vs observation json ${json.length}", prompt.length * 2 < json.length)
    }

    @Test
    fun `memory adds notes, last decision and changes`() {
        val fixture = LlmGatewayTestFixtures.gameWithFoundedCity()
        val collector = LlmActionCollector()
        val before = collector.buildObservation(fixture.gameInfo)
        fixture.civ.addGold(50)
        fixture.civ.cities.single().population.addPopulation(1)
        val after = collector.buildObservation(fixture.gameInfo)

        val withMemory = LlmPromptBuilder().build(after, memory = LlmPromptMemory(before, listOf("end_turn"), "grow Rome")).messages.single().content
        val withoutMemory = LlmPromptBuilder().build(after).messages.single().content

        assertTrue(withMemory.contains("Your notes: grow Rome"))
        assertTrue(withMemory.contains("Your last decision"))
        assertTrue(withMemory, withMemory.contains("gold +50"))
        assertTrue(withMemory, withMemory.contains("pop ${before.cities.single().population}->${after.cities.single().population}"))
        assertFalse(withoutMemory.contains("Your notes"))
    }

    companion object {
        fun assertListsEveryAction(content: String, actions: List<LlmLegalAction>, aliases: LlmActionAliases) {
            for (action in actions) {
                val alias = aliases.aliasOf(action)
                val listed = when {
                    action is LlmLegalAction.ChooseCityConstruction && alias.startsWith("build:") ->
                        content.contains("build:<city>:<item>") && content.contains(action.constructionName)
                    action is LlmLegalAction.ChooseTechnology && alias == "tech:${action.techName}" ->
                        content.contains("tech:<name>") && content.contains(action.techName)
                    else -> content.contains(alias)
                }
                assertTrue("$alias is not listed in:\n$content", listed)
                assertEquals(action, aliases.resolve(alias))
            }
        }
    }
}
