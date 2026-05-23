package com.unciv.logic.llm.policy

import com.unciv.logic.llm.LlmLegalAction
import com.unciv.logic.llm.RandomPolicySmokeTests
import com.unciv.logic.llm.client.FakeLlmClient
import com.unciv.logic.llm.client.LlmResponse
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    fun `parse failure falls back to end turn`() {
        val action = LlmLegalAction.EndTurn()
        val observation = RandomPolicySmokeTests.observationWithLegalActionsForTests(action)
        val client = FakeLlmClient.byCallIndex(listOf(LlmResponse("garbage", null, "end", 1, 1)))

        assertEquals(listOf(action), RealLlmPolicy(client).chooseActions(observation))
    }
}
