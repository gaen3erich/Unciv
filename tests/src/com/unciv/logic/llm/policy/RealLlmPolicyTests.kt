package com.unciv.logic.llm.policy

import com.unciv.logic.llm.LlmLegalAction
import com.unciv.logic.llm.RandomPolicySmokeTests
import com.unciv.logic.llm.client.FakeLlmClient
import com.unciv.logic.llm.client.LlmResponse
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
}
