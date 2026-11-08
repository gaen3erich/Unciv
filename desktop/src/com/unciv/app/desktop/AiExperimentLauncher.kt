package com.unciv.app.desktop

import com.unciv.UncivGame
import com.unciv.logic.automation.AiFeature
import com.unciv.logic.automation.AiProfile
import com.unciv.logic.automation.commander.TacticalCommander
import com.unciv.logic.simulation.AiExperiment
import com.unciv.logic.simulation.AiExperimentConfig
import com.unciv.logic.simulation.AiExperimentReport
import com.unciv.models.metadata.GameSettings
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.skins.SkinCache
import com.unciv.models.tilesets.TileSetCache
import com.unciv.utils.Log
import java.io.File
import kotlin.system.exitProcess

/**
 * Headless AI-vs-AI experiment: plays the candidate AI profile against the baseline in the same games.
 *
 * Run with `./gradlew :desktop:aiExperiment --args="--candidate=default+GreatPersonEscort --seeds=50"`.
 * Working directory must be android/assets (the Gradle task sets it).
 */
internal object AiExperimentLauncher {

    private const val usage = """
Options (all optional):
  --baseline=<profile>     AI profile of the baseline side (default: default)
  --candidate=<profile>    AI profile of the candidate side (default: all)
  --civs=<n>               major civs per game, even; 2 = duel (default: 2)
  --seeds=<n>              number of map seeds; each is played twice with sides swapped (default: 50)
  --seed-start=<n>         first map seed (default: 1)
  --max-turns=<n>          game counts as a draw after this turn (default: 400)
  --stat-turns=<a,b>       turns to record cities and population (default: 100,200)
  --threads=<n>            parallel games (default: CPU count)
  --difficulty=<name>      (default: King)
  --map-size=<name>        Tiny, Small, Medium... (default: Tiny for duels, Small otherwise)
  --out=<dir>              where to write report.md and games.csv (default: build/ai-experiment when run from Gradle)
  --fail-if-worse          exit code 2 when the candidate is significantly weaker

Profiles: default | none | all, followed by +Feature / -Feature, e.g. default+GreatPersonEscort
LlmCommander is configured with UNCIV_COMMANDER_* environment variables, see docs/LLM_COMMANDER.md
"""

    @JvmStatic
    fun main(args: Array<String>) {
        val options = parseArgs(args)
        if ("help" in options) {
            println(usage)
            println("Features: " + AiFeature.entries.joinToString("\n  ", "\n  ") {
                "${it.name}${if (it.enabledByDefault) " (default)" else ""}: ${it.description}"
            })
            return
        }

        Log.backend = DesktopLogBackend()
        val game = UncivGame(true)
        UncivGame.Current = game
        UncivGame.Current.settings = GameSettings().apply {
            showTutorials = false
            turnsBetweenAutosaves = 10000
        }
        RulesetCache.loadRulesets(true)
        TileSetCache.loadTileSetConfigs(true)
        SkinCache.loadSkinConfigs(true)

        val seedStart = options["seed-start"]?.toLong() ?: 1L
        val seedCount = options["seeds"]?.toInt() ?: 50
        val config = AiExperimentConfig(
            baseline = AiProfile.parse(options["baseline"] ?: "default"),
            candidate = AiProfile.parse(options["candidate"] ?: "all"),
            civsPerGame = options["civs"]?.toInt() ?: 2,
            seeds = (seedStart until seedStart + seedCount).toList(),
            maxTurns = options["max-turns"]?.toInt() ?: 400,
            statTurns = options["stat-turns"]?.split(',')?.map { it.trim().toInt() } ?: listOf(100, 200),
            threads = options["threads"]?.toInt() ?: Runtime.getRuntime().availableProcessors(),
            difficulty = options["difficulty"] ?: "King",
            mapSize = options["map-size"],
        )
        if (config.candidate.has(AiFeature.LlmCommander) || config.baseline.has(AiFeature.LlmCommander)) {
            val commander = TacticalCommander.config
            println("LLM commander: " + (if (commander.usesStub) "stub model (set UNCIV_COMMANDER_BASE_URL for a real one)"
                else "${commander.model} at ${commander.baseUrl}") + ", logs in ${commander.logDirFile() ?: "nowhere"}")
            if (!commander.usesStub && config.threads > 1)
                println("Hint: a real model answers one battle at a time anyway, --threads=1 keeps the logs in game order")
        }
        if (config.candidate == config.baseline)
            println("Warning: candidate and baseline have the same features, expect ~50%")

        val experiment = AiExperiment(config)
        val results = experiment.run()
        val report = AiExperimentReport(config, results, experiment.crashedGames)

        val outDir = File(options["out"] ?: System.getProperty("aiExperimentOut") ?: "ai-experiment").absoluteFile
        outDir.mkdirs()
        File(outDir, "report.md").writeText(report.markdown())
        File(outDir, "games.csv").writeText(report.csv())
        println()
        println(report.markdown())
        println("Report written to ${outDir.path}")

        val failIfWorse = "fail-if-worse" in options
        exitProcess(if (failIfWorse && report.score.high < 0.5) 2 else 0)
    }

    private fun parseArgs(args: Array<String>): Map<String, String> = args.associate { arg ->
        require(arg.startsWith("--")) { "Unexpected argument '$arg'\n$usage" }
        val body = arg.removePrefix("--")
        val eq = body.indexOf('=')
        if (eq < 0) body to "" else body.substring(0, eq) to body.substring(eq + 1)
    }
}
