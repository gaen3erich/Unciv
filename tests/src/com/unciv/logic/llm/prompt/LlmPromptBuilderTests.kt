package com.unciv.logic.llm.prompt

import com.badlogic.gdx.utils.JsonReader
import com.unciv.logic.llm.LlmActionCollector
import com.unciv.logic.llm.LlmGatewayTestFixtures
import com.unciv.logic.llm.client.LlmMessage
import com.unciv.logic.llm.client.LlmRequestKind
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class LlmPromptBuilderTests {
    @Test
    fun `builds decision request with observation and legal action ids`() {
        val observation = LlmActionCollector().buildObservation(LlmGatewayTestFixtures.gameWithFoundedCity().gameInfo)

        val request = LlmPromptBuilder().build(observation)

        assertEquals(LlmRequestKind.DECISION, request.kind)
        assertEquals(0.0, request.temperature, 0.0)
        assertEquals(1, request.messages.size)
        assertEquals(LlmMessage.Role.USER, request.messages.single().role)
        assertTrue(request.messages.single().content.contains("```json"))
        for (action in observation.legalActions) {
            assertTrue(request.messages.single().content.contains(action.id))
        }
        JsonReader().parse(request.toolSchema)
    }
}
