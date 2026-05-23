package com.unciv.app.desktop

import com.unciv.UncivGame
import com.unciv.app.desktop.llm.DesktopLlmPolicyFactory
import com.unciv.logic.files.IncompatibleGameInfoVersionException
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.llm.LlmLegalAction
import com.unciv.logic.llm.LlmObservation
import com.unciv.logic.llm.LlmPolicy
import com.unciv.logic.llm.RandomPolicy
import com.unciv.logic.llm.config.LlmAgentConfigLoader
import com.unciv.logic.llm.io.LlmTraceWriter
import com.unciv.logic.llm.runner.LlmGatewayRunner
import com.unciv.logic.llm.runner.LlmRunnerConfig
import com.unciv.logic.llm.runner.PolicyKind
import com.unciv.models.metadata.GameSettings
import com.unciv.models.ruleset.RulesetCache
import java.io.File
import kotlin.system.exitProcess

object LlmGatewayCli {
    @JvmStatic
    fun main(args: Array<String>) {
        exitProcess(run(args))
    }

    fun run(args: Array<String>): Int {
        return try {
            val config = parseArgs(args)
            val saveFile = config.saveFile ?: throw IllegalArgumentException("--save is required")
            val outputFile = config.outputFile ?: File("llm-trace.jsonl")
            if (!saveFile.isFile) {
                System.err.println("llm-cli: save file not found: ${saveFile.path}")
                return 1
            }

            UncivGame.Current = UncivGame(isConsoleMode = true)
            UncivGame.Current.settings = GameSettings()
            RulesetCache.loadRulesets(consoleMode = true, noMods = false)

            // gameInfoFromString calls setTransients(); this CLI relies on that loader contract.
            val gameInfo = UncivFiles.gameInfoFromString(saveFile.readText(Charsets.UTF_8))
            UncivGame.Current.gameInfo = gameInfo
            val policy = policyFor(config)
            outputFile.parentFile?.mkdirs()

            val summary = outputFile.writer(Charsets.UTF_8).use { fileWriter ->
                LlmGatewayRunner(
                    gameInfo = gameInfo,
                    policy = policy,
                    traceWriter = LlmTraceWriter(fileWriter),
                    config = config
                ).run()
            }

            println(
                "llm-cli: steps=${summary.stepsRun} applied=${summary.appliedCount} " +
                    "rejected=${summary.rejectedCount} final_turn=${summary.finalTurn} wrote=${outputFile.path}"
            )
            if (config.failOnRejected && summary.rejectedCount > 0) 3 else 0
        } catch (ex: IllegalArgumentException) {
            System.err.println("llm-cli: ${ex.message}")
            1
        } catch (ex: IncompatibleGameInfoVersionException) {
            System.err.println("llm-cli: save file is incompatible: ${ex.message}")
            1
        } catch (ex: Exception) {
            System.err.println("llm-cli: runtime error: ${ex.message}")
            ex.printStackTrace(System.err)
            2
        }
    }

    fun parseArgs(args: Array<String>): LlmRunnerConfig {
        var saveFile: File? = null
        var outputFile: File? = null
        var steps = 1
        var policy = PolicyKind.RANDOM
        var seed = 0L
        var allowOnlineMultiplayer = false
        var failOnRejected = false
        var agentConfigFile: File? = null

        var index = 0
        fun requireValue(option: String): String {
            if (index + 1 >= args.size) throw IllegalArgumentException("$option requires a value")
            index++
            return args[index]
        }

        while (index < args.size) {
            when (val option = args[index]) {
                "--save" -> saveFile = File(requireValue(option))
                "--out" -> outputFile = File(requireValue(option))
                "--steps" -> steps = requireValue(option).toIntOrNull()
                    ?: throw IllegalArgumentException("--steps must be an integer")
                "--policy" -> policy = when (val value = requireValue(option)) {
                    "random" -> PolicyKind.RANDOM
                    "end_turn_only" -> PolicyKind.END_TURN_ONLY
                    "llm" -> PolicyKind.LLM
                    else -> throw IllegalArgumentException("unknown policy: $value")
                }
                "--config" -> agentConfigFile = resolveInputFile(requireValue(option))
                "--seed" -> seed = requireValue(option).toLongOrNull()
                    ?: throw IllegalArgumentException("--seed must be a long")
                "--allow-multiplayer" -> allowOnlineMultiplayer = true
                "--fail-on-rejected" -> failOnRejected = true
                "-h", "--help" -> throw IllegalArgumentException(helpText())
                else -> throw IllegalArgumentException("unknown argument: $option")
            }
            index++
        }

        if (saveFile == null) throw IllegalArgumentException("--save is required")
        if (steps < 0) throw IllegalArgumentException("--steps must be non-negative")
        if (policy == PolicyKind.LLM && agentConfigFile == null) throw IllegalArgumentException("--config is required when --policy llm")
        val agentConfig = agentConfigFile?.let { LlmAgentConfigLoader().load(it).source }

        return LlmRunnerConfig(
            saveFile = saveFile,
            outputFile = outputFile ?: File("llm-trace.jsonl"),
            policy = policy,
            seed = seed,
            steps = steps,
            allowOnlineMultiplayer = allowOnlineMultiplayer,
            failOnRejected = failOnRejected,
            uncivVersion = UncivGame.VERSION.text,
            agentConfig = agentConfig
        )
    }

    private fun policyFor(config: LlmRunnerConfig): LlmPolicy = when (config.policy) {
        PolicyKind.RANDOM -> RandomPolicy(seed = config.seed)
        PolicyKind.END_TURN_ONLY -> EndTurnOnlyPolicy
        PolicyKind.LLM -> {
            val configSource = config.agentConfig ?: throw IllegalArgumentException("--config is required when --policy llm")
            val configFile = File(configSource.path)
            val agentConfig = LlmAgentConfigLoader().load(configFile)
            DesktopLlmPolicyFactory().buildPolicy(agentConfig, repoRootForConfig(configFile))
        }
    }

    private object EndTurnOnlyPolicy : LlmPolicy {
        override fun chooseActions(observation: LlmObservation): List<LlmLegalAction> =
            listOf(LlmLegalAction.EndTurn())
    }

    private fun resolveInputFile(path: String): File {
        val direct = File(path)
        if (direct.exists() || direct.isAbsolute) return direct
        val cwd = File(System.getProperty("user.dir"))
        val fromParent = cwd.parentFile?.resolve(path)
        if (fromParent?.exists() == true) return fromParent
        val fromGrandParent = cwd.parentFile?.parentFile?.resolve(path)
        if (fromGrandParent?.exists() == true) return fromGrandParent
        return direct
    }

    private fun repoRootForConfig(configFile: File): File {
        val absolute = configFile.absoluteFile
        return absolute.parentFile?.parentFile?.parentFile ?: File(".")
    }

    private fun helpText() = "usage: --save <path> [--steps <n>] [--policy random|end_turn_only|llm] [--config <path>] [--seed <long>] [--out <path>]"
}
