package com.unciv.logic.llm.prompt

import com.unciv.logic.llm.LlmLegalAction
import com.unciv.logic.llm.RandomPolicySmokeTests
import com.unciv.logic.llm.client.LlmResponse
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class LlmResponseParserTests {
    private val parser = LlmResponseParser()
    private val endTurn = LlmLegalAction.EndTurn()
    private val observation = RandomPolicySmokeTests.observationWithLegalActionsForTests(endTurn)

    @Test
    fun `parses tool call json by legal action id`() {
        val response = response(toolCall = """{"actions":[{"kind":"EndTurn","id":"end_turn"}],"rationale":"done"}""")

        val result = parser.parse(response, observation)

        assertTrue(result is LlmResponseParser.ParseResult.Success)
        result as LlmResponseParser.ParseResult.Success
        assertEquals(listOf(endTurn), result.actions)
        assertEquals("done", result.rationale)
        assertEquals(LlmResponseParser.ParsePath.TOOL_CALL, result.parsePath)
    }

    @Test
    fun `unknown action id fails`() {
        val response = response(toolCall = """{"actions":[{"kind":"EndTurn","id":"not_legal"}]}""")

        val result = parser.parse(response, observation)

        assertTrue(result is LlmResponseParser.ParseResult.Failure)
    }

    @Test
    fun `parses fenced json when no tool call exists`() {
        val response = response(raw = "```json\n{\"actions\":[{\"kind\":\"EndTurn\",\"id\":\"end_turn\"}]}\n```")

        val result = parser.parse(response, observation)

        assertTrue(result is LlmResponseParser.ParseResult.Success)
        assertEquals(LlmResponseParser.ParsePath.FENCED_JSON, result.parsePath)
    }

    @Test
    fun `parses balanced json when no fence exists`() {
        val response = response(raw = "Here: {\"actions\":[{\"kind\":\"EndTurn\",\"id\":\"end_turn\"}]}")

        val result = parser.parse(response, observation)

        assertTrue(result is LlmResponseParser.ParseResult.Success)
        assertEquals(LlmResponseParser.ParsePath.BALANCED_JSON, result.parsePath)
    }

    private fun response(raw: String = "", toolCall: String? = null) =
        LlmResponse(raw, toolCall, "stop", 0, 0)
}
