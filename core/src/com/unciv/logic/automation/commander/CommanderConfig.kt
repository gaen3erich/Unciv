package com.unciv.logic.automation.commander

import java.io.File

/**
 * Settings of the LLM tactical commander ([TacticalCommander]).
 *
 * Read from Java system properties `unciv.commander.*` first, then environment variables `UNCIV_COMMANDER_*`,
 * so the same build can talk to DeepSeek behind an ssh tunnel (`baseUrl = http://localhost:8000/v1`)
 * or run with the built-in [StubCommanderModel] when no model is configured.
 */
data class CommanderConfig(
    /** OpenAI-compatible base URL, e.g. `http://localhost:8000/v1` for vLLM through an ssh tunnel. Empty: use the stub model */
    val baseUrl: String = "",
    /** Model name as the server knows it, e.g. `deepseek-ai/DeepSeek-R1` */
    val model: String = "",
    /** Sent as `Authorization: Bearer` if not empty. vLLM without `--api-key` needs none */
    val apiKey: String = "",
    val temperature: Double = 0.3,
    /** Generous by default: reasoning models think before they answer */
    val maxTokens: Int = 16000,
    val timeoutSeconds: Int = 600,
    /** Where prompts, answers and execution logs go, relative to the working directory (android/assets for Gradle tasks). Empty: no logs */
    val logDir: String = "commander-logs",
    /** Engagements with fewer units than this (both sides together) stay with UnitTactics, unless an own city is threatened */
    val minUnits: Int = 3,
    /** Stop calling the model after this many calls in one run (0 = unlimited); later battles fall back to UnitTactics */
    val maxCalls: Int = 0,
    /** How many times the model may revise its plan after seeing it played out on a copy of the game */
    val revisions: Int = 1,
    /** Language the model writes its intent in */
    val intentLanguage: String = "Russian",
    /** Own units at most this many turns away are listed as reinforcements */
    val reinforcementTurns: Int = 3,
) {
    val usesStub: Boolean get() = baseUrl.isBlank()

    fun createModel(): CommanderModel = if (usesStub) StubCommanderModel() else OpenAiCompatibleCommanderModel(this)

    companion object {
        private fun setting(key: String): String? {
            System.getProperty("unciv.commander.$key")?.let { return it }
            val envKey = "UNCIV_COMMANDER_" + key.replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()
            return System.getenv(envKey)
        }

        /** Builds the config from system properties / environment variables (see the class doc) */
        fun fromEnvironment(): CommanderConfig {
            val d = CommanderConfig()
            val apiKeyEnv = setting("apiKeyEnv")
            return CommanderConfig(
                baseUrl = setting("baseUrl") ?: d.baseUrl,
                model = setting("model") ?: d.model,
                apiKey = setting("apiKey") ?: apiKeyEnv?.let { System.getenv(it) } ?: d.apiKey,
                temperature = setting("temperature")?.toDoubleOrNull() ?: d.temperature,
                maxTokens = setting("maxTokens")?.toIntOrNull() ?: d.maxTokens,
                timeoutSeconds = setting("timeoutSeconds")?.toIntOrNull() ?: d.timeoutSeconds,
                logDir = setting("logDir") ?: d.logDir,
                minUnits = setting("minUnits")?.toIntOrNull() ?: d.minUnits,
                maxCalls = setting("maxCalls")?.toIntOrNull() ?: d.maxCalls,
                revisions = setting("revisions")?.toIntOrNull() ?: d.revisions,
                intentLanguage = setting("intentLanguage") ?: d.intentLanguage,
                reinforcementTurns = setting("reinforcementTurns")?.toIntOrNull() ?: d.reinforcementTurns,
            )
        }
    }

    fun logDirFile(): File? = if (logDir.isBlank()) null else File(logDir).absoluteFile
}
