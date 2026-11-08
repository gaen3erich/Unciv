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
        val client = FakeLlmClient.byCallIndex(listOf(LlmResponse("garbage", null, "end", 1, 1), LlmResponse("garbage", null, "end", 1, 1)))

        assertEquals(listOf(action), RealLlmPolicy(client).chooseActions(observation))
    }

    private val endTurn = LlmLegalAction.EndTurn()
    private val war = LlmLegalAction.DeclareWar("declare_war:Egypt", "Egypt", "Egypt")
    private val observationWithWar = RandomPolicySmokeTests.observationWithLegalActionsForTests(war, endTurn)

    private fun toolCall(json: String) = LlmResponse("", json, "stop", 10, 5, latencyMs = 100)

    private class RecordingClient(private vararg val outcomes: () -> LlmResponse) : LlmClient {
        override val identifier = "recording"
        val prompts = ArrayList<String>()
        override fun send(request: LlmRequest): LlmResponse {
            prompts += request.messages.first().content
            return outcomes[prompts.size - 1]()
        }
    }

    @Test
    fun `a valid answer is not retried`() {
        val client = RecordingClient({ toolCall("""{"actions":[{"id":"end_turn"}]}""") })
        val policy = RealLlmPolicy(client)

        policy.chooseActions(observationWithWar)

        assertEquals(1, client.prompts.size)
        assertEquals(0, policy.consumeLastCallTrace()!!.retryCount)
    }

    @Test
    fun `illegal id triggers a retry that names it and the corrected answer is used`() {
        val client = RecordingClient(
            { toolCall("""{"actions":[{"id":"defend:Rome"},{"id":"end_turn"}]}""") },
            { toolCall("""{"actions":[{"id":"war:Egypt"},{"id":"end_turn"}]}""") }
        )
        val policy = RealLlmPolicy(client, includePrompts = true)

        assertEquals(listOf(war, endTurn), policy.chooseActions(observationWithWar))

        assertTrue(client.prompts[1].startsWith(client.prompts[0]))
        assertTrue(client.prompts[1].substring(client.prompts[0].length).contains("defend:Rome"))
        val trace = policy.consumeLastCallTrace()!!
        assertEquals(1, trace.retryCount)
        assertTrue(trace.firstAttemptRejection!!.contains("defend:Rome"))
        assertTrue(trace.firstAttemptResponse!!.contains("defend:Rome"))
        assertTrue(trace.droppedActionIds.isEmpty())
        assertEquals(null, trace.fallbackUsed)
        assertEquals(20, trace.inputTokens)
        assertEquals(200L, trace.latencyMs)
    }

    @Test
    fun `an unparsable first answer is retried`() {
        val client = RecordingClient(
            { LlmResponse("no json here", null, "stop", 1, 1) },
            { toolCall("""{"actions":[{"id":"end_turn"}]}""") }
        )

        assertEquals(listOf(endTurn), RealLlmPolicy(client).chooseActions(observationWithWar))
        assertEquals(2, client.prompts.size)
    }

    @Test
    fun `illegal ids surviving the retry are dropped and the legal rest is applied`() {
        val client = RecordingClient(
            { toolCall("""{"actions":[{"id":"defend:Rome"}]}""") },
            { toolCall("""{"actions":[{"id":"tech:Writing"},{"id":"end_turn"}]}""") }
        )
        val policy = RealLlmPolicy(client)

        assertEquals(listOf(endTurn), policy.chooseActions(observationWithWar))

        val trace = policy.consumeLastCallTrace()!!
        assertEquals(listOf("tech:Writing"), trace.droppedActionIds)
        assertEquals(null, trace.fallbackUsed)
        assertEquals(2, client.prompts.size)
    }

    @Test
    fun `two fully illegal answers fall back after exactly one retry`() {
        val client = RecordingClient(
            { toolCall("""{"actions":[{"id":"defend:Rome"}]}""") },
            { toolCall("""{"actions":[{"id":"tech:Writing"}]}""") }
        )
        val policy = RealLlmPolicy(client)

        assertEquals(listOf(endTurn), policy.chooseActions(observationWithWar).filterIsInstance<LlmLegalAction.EndTurn>())

        val trace = policy.consumeLastCallTrace()!!
        assertEquals("parse_failure", trace.fallbackUsed)
        assertTrue(trace.failureReason!!.contains("tech:Writing"))
        assertEquals(2, client.prompts.size)
    }

    @Test
    fun `a first answer with legal actions survives a useless retry`() {
        val client = RecordingClient(
            { toolCall("""{"actions":[{"id":"defend:Rome"},{"id":"war:Egypt"}]}""") },
            { LlmResponse("garbage", null, "stop", 1, 1) }
        )
        val policy = RealLlmPolicy(client)

        assertEquals(listOf(war), policy.chooseActions(observationWithWar))
        assertEquals(listOf("defend:Rome"), policy.consumeLastCallTrace()!!.droppedActionIds)
    }

    @Test
    fun `a first answer with legal actions survives a client error on retry`() {
        val client = RecordingClient(
            { toolCall("""{"actions":[{"id":"defend:Rome"},{"id":"war:Egypt"}]}""") },
            { throw LlmClientException("connection lost") }
        )
        val policy = RealLlmPolicy(client)

        assertEquals(listOf(war), policy.chooseActions(observationWithWar))

        val trace = policy.consumeLastCallTrace()!!
        assertEquals(null, trace.fallbackUsed)
        assertTrue(trace.failureReason!!.contains("connection lost"))
    }

    @Test
    fun `a client error on the first call is not retried`() {
        val client = RecordingClient({ throw LlmClientException("down") })
        val policy = RealLlmPolicy(client)

        policy.chooseActions(observationWithWar)

        assertEquals(1, client.prompts.size)
        assertEquals("client_error", policy.consumeLastCallTrace()!!.fallbackUsed)
    }

    @Test
    fun `memory comes from the corrected answer`() {
        val client = RecordingClient(
            { toolCall("""{"actions":[{"id":"defend:Rome"}],"notes":"stale plan"}""") },
            { toolCall("""{"actions":[{"id":"end_turn"}],"notes":"fresh plan"}""") },
            { toolCall("""{"actions":[{"id":"end_turn"}]}""") }
        )
        val policy = RealLlmPolicy(client, includePrompts = true)

        policy.chooseActions(observationWithWar)
        policy.chooseActions(observationWithWar.copy(turn = 2))

        assertTrue(client.prompts[2].contains("Your notes: fresh plan"))
        assertFalse(client.prompts[2].contains("stale plan"))
    }

    @Test
    fun `parse failure trace records the reason and the rejected response`() {
        val observation = RandomPolicySmokeTests.observationWithLegalActionsForTests(LlmLegalAction.EndTurn())
        val toolCall = """{"actions":[{"id":"no_such_action"}]}"""
        val client = FakeLlmClient.byCallIndex(listOf(LlmResponse("", toolCall, "tool_use", 1, 1), LlmResponse("", toolCall, "tool_use", 1, 1)))
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
        val client = FakeLlmClient.byCallIndex(listOf(LlmResponse("garbage", null, "end", 1, 1), LlmResponse("garbage", null, "end", 1, 1)))
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
