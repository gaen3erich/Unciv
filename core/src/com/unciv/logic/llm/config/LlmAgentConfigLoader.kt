package com.unciv.logic.llm.config

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue
import java.io.File
import java.security.MessageDigest

class LlmAgentConfigLoader {
    fun load(file: File): LlmAgentConfig {
        val bytes = file.readBytes()
        val root = JsonReader().parse(String(bytes, Charsets.UTF_8))
        val schema = root.getString("schema")
        require(schema == SCHEMA) { "Unsupported LLM agent config schema: $schema" }
        val name = root.getString("name")
        require(!name.contains(' ') && !name.contains('/')) { "Config name must not contain spaces or '/': $name" }

        val client = root.get("client")
        val request = root.get("request")
        val policy = root.get("policy")
        val trace = root.get("trace")
        val clientKind = client.getString("kind")
        val model = client.getString("model", null)
        val config = LlmAgentConfig(
            schema = schema,
            name = name,
            description = root.getString("description", null),
            client = LlmClientConfig(
                kind = clientKind,
                apiKeyEnv = client.getString("apiKeyEnv", null),
                model = model,
                baseUrl = client.getString("baseUrl", null),
                timeoutSeconds = client.getInt("timeoutSeconds", if (clientKind == "fake") 0 else 60),
                maxRetries = client.getInt("maxRetries", if (clientKind == "fake") 0 else 2),
                extraHeaders = stringMap(client.get("extraHeaders")),
                transcriptPath = client.getString("transcriptPath", null)
            ),
            request = LlmRequestConfig(
                temperature = request?.getDouble("temperature", 0.0) ?: 0.0,
                maxTokens = request?.getInt("maxTokens", 4096) ?: 4096
            ),
            policy = LlmPolicyConfig(
                fallback = policy?.getString("fallback", "end_turn_only") ?: "end_turn_only",
                failOnLlmErrorAfterConsecutive = policy?.getInt("failOnLlmErrorAfterConsecutive", 5) ?: 5
            ),
            trace = LlmTraceConfig(
                includePrompts = trace?.getBoolean("includePrompts", false) ?: false,
                includeRationale = trace?.getBoolean("includeRationale", true) ?: true
            ),
            source = LlmAgentConfigSource(file.path, sha256(bytes), name, clientKind, model)
        )
        validate(config)
        return config
    }

    private fun validate(config: LlmAgentConfig) {
        when (config.client.kind) {
            "fake" -> require(!config.client.transcriptPath.isNullOrBlank()) { "fake client requires transcriptPath" }
            "anthropic" -> {
                require(!config.client.apiKeyEnv.isNullOrBlank()) { "anthropic client requires apiKeyEnv" }
                require(!config.client.model.isNullOrBlank()) { "anthropic client requires model" }
            }
            "openai-compat" -> {
                require(!config.client.apiKeyEnv.isNullOrBlank()) { "openai-compat client requires apiKeyEnv" }
                require(!config.client.model.isNullOrBlank()) { "openai-compat client requires model" }
                require(!config.client.baseUrl.isNullOrBlank()) { "openai-compat client requires baseUrl" }
            }
            else -> throw IllegalArgumentException("Unknown LLM client kind: ${config.client.kind}")
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun stringMap(value: JsonValue?): Map<String, String> {
        if (value == null) return emptyMap()
        require(value.isObject) { "extraHeaders must be a JSON object" }
        return value.associate { it.name to it.asString() }
    }

    companion object {
        const val SCHEMA = "llm-agent-config/v1"
    }
}
