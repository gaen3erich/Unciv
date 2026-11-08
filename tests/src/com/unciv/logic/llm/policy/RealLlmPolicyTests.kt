package com.unciv.logic.llm.policy

import com.unciv.logic.llm.LlmLegalAction
import com.unciv.logic.llm.RandomPolicySmokeTests
import com.unciv.logic.llm.client.FakeLlmClient
import com.unciv.logic.llm.client.LlmClient
import com.unciv.logic.llm.client.LlmClientException
import com.unciv.logic.llm.client.LlmRequest
import com.unciv.logic.llm.client.LlmResponse
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class RealLlmPolicyTests {
    @Test
    fun `happy path returns parsed actions and exposes trace`() {
        val action = LlmLegalAction.EndTurn()
        val observation = RandomPolicySmokeTests.observationWithLegalActionsForTests(action)
        val client = FakeLlmClient.byCallIndex(listOf(LlmResponse("", """{"actions":[{"kind":"EndTurn","id":"end_turn"}],"rationale":"done"}""", "tool_use", 10, 5)))
        val policy = RealLlmPolicy(client)

        assertEquals(listOf(action), policy.chooseActions(observation))
        assertNotNull(policy.consumeLastCallTrace())
    }

    @Test
    fun `notes from one step are shown in the next prompt`() {
        val action = LlmLegalAction.EndTurn()
        val observation = RandomPolicySmokeTests.observationWithLegalActionsForTests(action)
        val client = FakeLlmClient.byCallIndex(listOf(
            LlmResponse("", """{"actions":[{"id":"end_turn"}],"notes":"build a navy"}""", "tool_use", 10, 5),
            LlmResponse("", """{"actions":[{"id":"end_turn"}]}""", "tool_use", 10, 5)
        ))
        val policy = RealLlmPolicy(client, includePrompts = true)

        policy.chooseActions(observation)
        assertFalse(policy.consumeLastCallTrace()!!.userPrompt!!.contains("build a navy"))
        policy.chooseActions(observation.copy(turn = 2))
        val trace = policy.consumeLastCallTrace()!!
        assertTrue(trace.userPrompt!!.contains("Your notes: build a navy"))
        assertTrue(trace.userPrompt!!.contains("Your last decision (turn 1): end_turn"))
        assertEquals(trace.userPrompt!!.length, trace.userPromptChars)
    }

    @Test
    fun `parse failure falls back to end turn`() {
        val action = LlmLegalAction.EndTurn()
        val observation = RandomPolicySmokeTests.observationWithLegalActionsForTests(action)
        val client = FakeLlmClient.byCallIndex(listOf(LlmResponse("garbage", null, "end", 1, 1)))

        assertEquals(listOf(action), RealLlmPolicy(client).chooseActions(observation))
    }

    @Test
    fun `parse failure trace records the reason and the rejected response`() {
        val observation = RandomPolicySmokeTests.observationWithLegalActionsForTests(LlmLegalAction.EndTurn())
        val toolCall = """{"actions":[{"id":"no_such_action"}]}"""
        val client = FakeLlmClient.byCallIndex(listOf(LlmResponse("", toolCall, "tool_use", 1, 1)))
        val policy = RealLlmPolicy(client, includePrompts = true)

        policy.chooseActions(observation)

        val trace = policy.consumeLastCallTrace()!!
        assertEquals("parse_failure", trace.fallbackUsed)
        assertTrue(trace.failureReason!!.contains("no_such_action"))
        assertEquals(toolCall, trace.failedResponse)
    }

    @Test
    fun `parse failure keeps the reason but not the response when prompts are excluded`() {
        val observation = RandomPolicySmokeTests.observationWithLegalActionsForTests(LlmLegalAction.EndTurn())
        val client = FakeLlmClient.byCallIndex(listOf(LlmResponse("garbage", null, "end", 1, 1)))
        val policy = RealLlmPolicy(client)

        policy.chooseActions(observation)

        val trace = policy.consumeLastCallTrace()!!
        assertNotNull(trace.failureReason)
        assertNull(trace.failedResponse)
    }

    @Test
    fun `client error trace records the exception message`() {
        val action = LlmLegalAction.EndTurn()
        val observation = RandomPolicySmokeTests.observationWithLegalActionsForTests(action)
        val client = object : LlmClient {
            override val identifier = "failing"
            override fun send(request: LlmRequest): LlmResponse = throw LlmClientException("LLM HTTP 400: body missing")
        }
        val policy = RealLlmPolicy(client)

        assertEquals(listOf(action), policy.chooseActions(observation))

        val trace = policy.consumeLastCallTrace()!!
        assertEquals("client_error", trace.fallbackUsed)
        assertEquals("LLM HTTP 400: body missing", trace.failureReason)
    }

    @Test
    fun `successful call has no failure fields`() {
        val observation = RandomPolicySmokeTests.observationWithLegalActionsForTests(LlmLegalAction.EndTurn())
        val client = FakeLlmClient.byCallIndex(listOf(LlmResponse("", """{"actions":[{"id":"end_turn"}]}""", "tool_use", 1, 1)))
        val policy = RealLlmPolicy(client, includePrompts = true)

        policy.chooseActions(observation)

        val trace = policy.consumeLastCallTrace()!!
        assertNull(trace.failureReason)
        assertNull(trace.failedResponse)
    }
}
