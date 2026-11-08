package com.unciv.logic.llm.client

import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class FakeLlmClientTests {
    @Test
    fun `byCallIndex returns responses in order`() {
        val first = LlmResponse("a", null, "stop", 1, 2)
        val second = LlmResponse("b", null, "stop", 3, 4)
        val client = FakeLlmClient.byCallIndex(listOf(first, second))
        val request = request()

        assertEquals(first, client.send(request))
        assertEquals(second, client.send(request))
    }

    @Test(expected = IllegalStateException::class)
    fun `byCallIndex throws when exhausted`() {
        FakeLlmClient.byCallIndex(emptyList()).send(request())
    }

    @Test
    fun `fromJsonLines reads transcript responses`() {
        val client = FakeLlmClient.fromJsonLines(listOf("""{"rawText":"x","toolCallJson":null,"stopReason":"end","usageInputTokens":5,"usageOutputTokens":6}"""))

        val response = client.send(request())

        assertEquals("x", response.rawText)
        assertEquals(5, response.usageInputTokens)
        assertEquals(6, response.usageOutputTokens)
    }

    private fun request() = LlmRequest(LlmRequestKind.DECISION, "", emptyList(), null, 1, 0.0)
}
