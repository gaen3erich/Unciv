package com.unciv.logic.llm.io

import com.badlogic.gdx.utils.JsonReader
import com.unciv.logic.llm.LlmActionCollector
import com.unciv.logic.llm.LlmGateway
import com.unciv.logic.llm.LlmGatewayTestFixtures
import com.unciv.logic.llm.ScriptedPolicy
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.StringWriter

@RunWith(GdxTestRunner::class)
class LlmTraceWriterTests {

    @Test
    fun `writeHeader writes one newline terminated header record`() {
        val output = StringWriter()
        val writer = LlmTraceWriter(output)

        writer.writeHeader(header())

        val lines = output.toString().lines().filter { it.isNotEmpty() }
        assertEquals(1, lines.size)
        assertTrue(output.toString().endsWith("\n"))
        val json = JsonReader().parse(lines.single())
        assertEquals("header", json.getString("type"))
        assertEquals(LlmJsonCodec.SCHEMA, json.getString("schema"))
        assertEquals(com.unciv.logic.llm.LlmObservationVersion.CURRENT, json.getString("observationSchema"))
    }

    @Test
    fun `writeStep writes one parseable newline terminated step record`() {
        val output = StringWriter()
        val writer = LlmTraceWriter(output)
        val fixture = LlmGatewayTestFixtures.smallGame()
        val action = LlmGatewayTestFixtures.legalActionById(
            fixture.gameInfo,
            LlmActionCollector.chooseTechnologyId("Pottery")
        )
        val result = LlmGateway(ScriptedPolicy(listOf(action))).step(fixture.gameInfo)

        writer.writeStep(LlmTraceRecord(0, "2026-05-22T00:00:00Z", result))

        val lines = output.toString().lines().filter { it.isNotEmpty() }
        assertEquals(1, lines.size)
        assertTrue(output.toString().endsWith("\n"))
        val json = JsonReader().parse(lines.single())
        assertEquals("step", json.getString("type"))
        assertEquals(0, json.getInt("step"))
    }

    @Test
    fun `close is idempotent`() {
        val writer = LlmTraceWriter(StringWriter())

        writer.close()
        writer.close()
    }

    private fun header() = LlmTraceHeader(
        createdAt = "2026-05-22T00:00:00Z",
        uncivVersion = "test",
        compatibilityVersion = 1,
        saveFile = "save.unciv",
        policy = LlmTracePolicy("RANDOM", 0L),
        allowOnlineMultiplayer = false
    )
}
