package com.unciv.app.desktop

import com.unciv.UncivGame
import com.unciv.logic.automation.AiFeature
import com.unciv.logic.automation.AiProfile
import com.unciv.logic.automation.commander.TacticalCommander
import com.unciv.logic.simulation.ArenaCollectConfig
import com.unciv.logic.simulation.ArenaConfig
import com.unciv.logic.simulation.ArenaPositionCollector
import com.unciv.logic.simulation.ArenaPositionStore
import com.unciv.logic.simulation.ArenaSet
import com.unciv.logic.simulation.TacticalArena
import com.unciv.logic.simulation.TacticalArenaReport
import com.unciv.models.metadata.GameSettings
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.skins.SkinCache
import com.unciv.models.tilesets.TileSetCache
import com.unciv.utils.Log
import java.io.File
import kotlin.system.exitProcess

/**
 * Tactical arena, see docs/TACTICAL_AI_TESTING.md: collects saved battles and plays them out.
 *
 * `./gradlew :desktop:aiArena --args="collect --seeds=20"`, then
 * `./gradlew :desktop:aiArena --args="run --candidate=default+UnitTactics+FocusFire"`.
 * Working directory must be android/assets (the Gradle task sets it).
 */
internal object TacticalArenaLauncher {

    private const val usage = """
Usage: collect|run [options]

Common:
  --positions=<dir>        folder with the saved positions (default: build/ai-arena/positions when run from Gradle)
  --threads=<n>            parallel games (default: CPU count)

collect:
  --profile=<profile>      AI profile of both sides (default: default+UnitTactics)
  --seeds=<n>              number of map seeds (default: 20)
  --seed-start=<n>         first map seed (default: 1)
  --max-turns=<n>          (default: 250)
  --min-units=<n>          units of both sides together in a battle (default: 6)
  --spacing=<n>            turns between positions of one game (default: 10)
  --max-per-game=<n>       (default: 6)

run:
  --candidate=<profile>    (default: default+UnitTactics+LlmCommander)
  --baseline=<profile>     (default: default+UnitTactics)
  --horizon=<n>            turns to play from each position (default: 15)
  --city-weight=<n>        production a city is worth in the score (default: 300)
  --set=tune|holdout|all   which positions to play (default: tune); holdout is for the final check only
  --max-units=<n>          skip positions with more units than this (default: no limit)
  --out=<dir>              where to write report.md and positions.csv (default: build/ai-arena when run from Gradle)

LlmCommander is configured with UNCIV_COMMANDER_* environment variables, see docs/LLM_COMMANDER.md
"""

    @JvmStatic
    fun main(args: Array<String>) {
        val mode = args.firstOrNull()
        if (mode != "collect" && mode != "run") {
            println(usage)
            exitProcess(if (mode == null || mode == "--help") 0 else 1)
        }
        val options = args.drop(1).associate { arg ->
            require(arg.startsWith("--")) { "Unexpected argument '$arg'\n$usage" }
            val body = arg.removePrefix("--")
            val eq = body.indexOf('=')
            if (eq < 0) body to "" else body.substring(0, eq) to body.substring(eq + 1)
        }

        Log.backend = DesktopLogBackend()
        UncivGame.Current = UncivGame(true)
        UncivGame.Current.settings = GameSettings().apply {
            showTutorials = false
            turnsBetweenAutosaves = 10000
        }
        RulesetCache.loadRulesets(true)
        TileSetCache.loadTileSetConfigs(true)
        SkinCache.loadSkinConfigs(true)

        val root = System.getProperty("aiArenaOut") ?: "ai-arena"
        val store = ArenaPositionStore(File(options["positions"] ?: "$root/positions").absoluteFile)
        val threads = options["threads"]?.toInt() ?: Runtime.getRuntime().availableProcessors()
        when (mode) {
            "collect" -> collect(options, store, threads)
            else -> run(options, store, threads, File(options["out"] ?: root).absoluteFile)
        }
        exitProcess(0)
    }

    private fun collect(options: Map<String, String>, store: ArenaPositionStore, threads: Int) {
        val seedStart = options["seed-start"]?.toLong() ?: 1L
        val seedCount = options["seeds"]?.toInt() ?: 20
        val collector = ArenaPositionCollector(ArenaCollectConfig(
            profile = AiProfile.parse(options["profile"] ?: "default+UnitTactics"),
            seeds = (seedStart until seedStart + seedCount).toList(),
            maxTurns = options["max-turns"]?.toInt() ?: 250,
            minUnits = options["min-units"]?.toInt() ?: 6,
            spacing = options["spacing"]?.toInt() ?: 10,
            maxPerGame = options["max-per-game"]?.toInt() ?: 6,
            threads = threads,
        ))
        val collected = collector.collect(store)
        val all = store.positions()
        println("Collected ${collected.size} positions, ${collector.crashedGames} games crashed. " +
            "In ${store.directory.path}: ${all.size} positions, ${all.count { it.holdout }} of them holdout, " +
            all.groupingBy { it.kind }.eachCount())
    }

    private fun run(options: Map<String, String>, store: ArenaPositionStore, threads: Int, outDir: File) {
        val config = ArenaConfig(
            candidate = AiProfile.parse(options["candidate"] ?: "default+UnitTactics+LlmCommander"),
            baseline = AiProfile.parse(options["baseline"] ?: "default+UnitTactics"),
            horizon = options["horizon"]?.toInt() ?: 15,
            cityWeight = options["city-weight"]?.toInt() ?: 300,
            set = ArenaSet.entries.first { it.name.equals(options["set"] ?: "tune", ignoreCase = true) },
            maxUnits = options["max-units"]?.toInt() ?: Int.MAX_VALUE,
            threads = threads,
        )
        if (config.candidate.has(AiFeature.LlmCommander) || config.baseline.has(AiFeature.LlmCommander)) {
            val commander = TacticalCommander.config
            println("LLM commander: " + (if (commander.usesStub) "stub model (set UNCIV_COMMANDER_BASE_URL for a real one)"
                else "${commander.model} at ${commander.baseUrl}") + ", logs in ${commander.logDirFile() ?: "nowhere"}")
        }
        val arena = TacticalArena(config)
        outDir.mkdirs()
        fun write(report: TacticalArenaReport) {
            File(outDir, "report.md").writeText(report.markdown())
            File(outDir, "positions.csv").writeText(report.csv())
        }
        // Written after every position, so that an interrupted run keeps what it has played
        val results = arena.run(store) { write(TacticalArenaReport(config, it, arena.crashedPositions)) }
        val report = TacticalArenaReport(config, results, arena.crashedPositions)
        write(report)
        println()
        println(report.markdown())
        println("Report written to ${outDir.path}")
    }
}
