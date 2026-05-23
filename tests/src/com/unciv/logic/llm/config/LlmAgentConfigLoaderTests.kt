package com.unciv.logic.llm.config

import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(GdxTestRunner::class)
class LlmAgentConfigLoaderTests {

    @Test
    fun `loads anthropic client config`() {
        val config = load(
            """
            {
              "schema": "llm-agent-config/v1",
              "name": "anthropic-test",
              "client": {
                "kind": "anthropic",
                "apiKeyEnv": "ANTHROPIC_API_KEY",
                "model": "claude-sonnet-4-5-20250929"
              }
            }
            """.trimIndent()
        )

        assertEquals("anthropic", config.client.kind)
        assertEquals("ANTHROPIC_API_KEY", config.client.apiKeyEnv)
        assertEquals("claude-sonnet-4-5-20250929", config.client.model)
        assertEquals(60, config.client.timeoutSeconds)
        assertEquals(2, config.client.maxRetries)
    }

    @Test
    fun `loads openai compatible extra headers`() {
        val config = load(
            """
            {
              "schema": "llm-agent-config/v1",
              "name": "openai-compat-test",
              "client": {
                "kind": "openai-compat",
                "apiKeyEnv": "OPENROUTER_API_KEY",
                "model": "provider/model",
                "baseUrl": "https://example.invalid/api/v1",
                "extraHeaders": {
                  "HTTP-Referer": "https://unciv.app",
                  "X-Title": "Unciv LLM Gateway"
                }
              }
            }
            """.trimIndent()
        )

        assertEquals("openai-compat", config.client.kind)
        assertEquals("https://example.invalid/api/v1", config.client.baseUrl)
        assertEquals("https://unciv.app", config.client.extraHeaders["HTTP-Referer"])
        assertEquals("Unciv LLM Gateway", config.client.extraHeaders["X-Title"])
    }

    @Test
    fun `rejects anthropic config without api key env`() {
        val error = runCatching {
            load(
                """
                {
                  "schema": "llm-agent-config/v1",
                  "name": "broken",
                  "client": { "kind": "anthropic", "model": "claude" }
                }
                """.trimIndent()
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("apiKeyEnv"))
    }

    private fun load(json: String): LlmAgentConfig {
        val file = File.createTempFile("llm-agent-config", ".json")
        file.writeText(json, Charsets.UTF_8)
        file.deleteOnExit()
        return LlmAgentConfigLoader().load(file)
    }
}
