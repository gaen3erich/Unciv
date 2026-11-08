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
    fun `parses short alias and notes`() {
        val war = LlmLegalAction.DeclareWar("declare_war:Egypt", "Egypt", "Egypt")
        val observation = RandomPolicySmokeTests.observationWithLegalActionsForTests(war, endTurn)
        val response = response(toolCall = """{"actions":[{"id":"war:Egypt"},{"id":"end_turn"}],"notes":"take Thebes"}""")

        val result = parser.parse(response, observation)

        result as LlmResponseParser.ParseResult.Success
        assertEquals(listOf(war, endTurn), result.actions)
        assertEquals("take Thebes", result.notes)
    }

    @Test
    fun `notes are truncated`() {
        val response = response(toolCall = """{"actions":[{"id":"end_turn"}],"notes":"${"x".repeat(2000)}"}""")

        val result = parser.parse(response, observation) as LlmResponseParser.ParseResult.Success

        assertEquals(LlmPromptBuilder.NOTES_MAX_CHARS, result.notes!!.length)
    }

    @Test
    fun `unknown action id fails`() {
        val response = response(toolCall = """{"actions":[{"kind":"EndTurn","id":"not_legal"}]}""")

        val result = parser.parse(response, observation)

        assertTrue(result is LlmResponseParser.ParseResult.Failure)
    }

    @Test
    fun `illegal ids are dropped and the legal rest is kept`() {
        val response = response(toolCall = """{"actions":[{"id":"defend:Rome"},{"id":"end_turn"},{"id":"tech:Writing"}]}""")

        val result = parser.parse(response, observation) as LlmResponseParser.ParseResult.Success

        assertEquals(listOf(endTurn), result.actions)
        assertEquals(listOf("defend:Rome", "tech:Writing"), result.droppedActionIds)
    }

    @Test
    fun `a fully illegal answer fails and names every id`() {
        val response = response(toolCall = """{"actions":[{"id":"defend:Rome"},{"id":"tech:Writing"}]}""")

        val result = parser.parse(response, observation) as LlmResponseParser.ParseResult.Failure

        assertTrue(result.reason.contains("defend:Rome"))
        assertTrue(result.reason.contains("tech:Writing"))
    }

    @Test
    fun `an explicitly empty action list stays a success`() {
        val result = parser.parse(response(toolCall = """{"actions":[]}"""), observation) as LlmResponseParser.ParseResult.Success

        assertTrue(result.actions.isEmpty())
        assertTrue(result.droppedActionIds.isEmpty())
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
