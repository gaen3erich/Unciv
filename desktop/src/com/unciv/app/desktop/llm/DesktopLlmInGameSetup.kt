package com.unciv.app.desktop.llm

import com.unciv.UncivGame
import com.unciv.app.desktop.LlmGatewayCli
import com.unciv.logic.CompatibilityVersion
import com.unciv.logic.llm.config.LlmAgentConfigLoader
import com.unciv.logic.llm.ingame.LlmCivTurnController
import com.unciv.logic.llm.ingame.LlmInGameAgent
import com.unciv.logic.llm.io.LlmTraceHeader
import com.unciv.logic.llm.io.LlmTracePolicy
import com.unciv.logic.llm.io.LlmTraceWriter
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

/**
 * Hands AI civs of the normal GUI game to the LLM agent.
 *
 * Every option is a launcher argument or, when the argument is absent, an environment variable:
 * - `--llm-config=<path>` / `UNCIV_LLM_CONFIG`: agent config (configs/llm-agent/...json). Without it nothing changes.
 * - `--llm-civ=Rome,Greece` / `UNCIV_LLM_CIV`: civs the agent plays. Default: the first AI major civ of the game.
 * - `--llm-max-calls=3` / `UNCIV_LLM_MAX_CALLS`: LLM calls per turn at most, when the model doesn't pick end_turn.
 * - `--llm-trace=<path>` / `UNCIV_LLM_TRACE`: JSONL trace. Default: ~/unciv-llm/ingame-<time>.jsonl
 */
object DesktopLlmInGameSetup {
    fun installIfConfigured(args: Array<String>) {
        fun option(name: String, env: String): String? =
            args.find { it.startsWith("--$name=") }?.substringAfter("=")?.takeIf { it.isNotBlank() }
                ?: System.getenv(env)?.takeIf { it.isNotBlank() }

        val configPath = option("llm-config", "UNCIV_LLM_CONFIG") ?: return
        try {
            val configFile = LlmGatewayCli.resolveInputFile(configPath)
            if (!configFile.isFile) throw IllegalArgumentException("config file not found: ${configFile.absolutePath}")
            val agentConfig = LlmAgentConfigLoader().load(configFile)
            val policy = DesktopLlmPolicyFactory().buildPolicy(agentConfig, LlmGatewayCli.repoRootForConfig(configFile))

            val civs = option("llm-civ", "UNCIV_LLM_CIV")
                ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
            val maxCalls = option("llm-max-calls", "UNCIV_LLM_MAX_CALLS")?.let {
                it.toIntOrNull()?.takeIf { n -> n > 0 } ?: throw IllegalArgumentException("--llm-max-calls must be a positive integer")
            } ?: 3
            val traceFile = option("llm-trace", "UNCIV_LLM_TRACE")?.let { File(it) }
                ?: File(System.getProperty("user.home"), "unciv-llm/ingame-" +
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".jsonl")
            traceFile.absoluteFile.parentFile?.mkdirs()

            val traceWriter = LlmTraceWriter(traceFile.writer(Charsets.UTF_8))
            traceWriter.writeHeader(
                LlmTraceHeader(
                    createdAt = Instant.now().toString(),
                    uncivVersion = UncivGame.VERSION.text,
                    compatibilityVersion = CompatibilityVersion.CURRENT_COMPATIBILITY_NUMBER,
                    saveFile = "",
                    policy = LlmTracePolicy("llm-ingame"),
                    allowOnlineMultiplayer = false,
                    agentConfig = agentConfig.source
                )
            )
            Runtime.getRuntime().addShutdownHook(Thread { traceWriter.close() })

            LlmInGameAgent.controller = LlmCivTurnController(policy, civs, maxCalls, traceWriter)
            println(
                "llm: agent '${agentConfig.name}' (${agentConfig.client.kind}:${agentConfig.client.model ?: "-"}) plays " +
                    (if (civs.isEmpty()) "the first AI major civ" else civs.joinToString(", ")) +
                    ", up to $maxCalls calls per turn, trace ${traceFile.absolutePath}"
            )
        } catch (ex: Exception) {
            System.err.println("llm: cannot start the in-game agent: ${ex.message}")
            exitProcess(1)
        }
    }
}
