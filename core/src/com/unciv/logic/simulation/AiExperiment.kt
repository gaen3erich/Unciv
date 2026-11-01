package com.unciv.logic.simulation

import com.unciv.Constants
import com.unciv.logic.GameInfo
import com.unciv.logic.GameStarter
import com.unciv.logic.automation.AiProfile
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapSize
import com.unciv.logic.map.MirroringType
import com.unciv.models.metadata.GameParameters
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.Speed
import com.unciv.models.ruleset.nation.Nation
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/** Settings for one [AiExperiment] run. */
data class AiExperimentConfig(
    val baseline: AiProfile,
    val candidate: AiProfile,
    /** Major civs per game. Must be even: half play [candidate], half [baseline]. 2 = duel. */
    val civsPerGame: Int = 2,
    /** Map seeds. Every seed is played twice, the second time with sides swapped. */
    val seeds: List<Long> = (1L..50L).toList(),
    val maxTurns: Int = 400,
    /** Turns at which cities/population are recorded */
    val statTurns: List<Int> = listOf(100, 200),
    val threads: Int = Runtime.getRuntime().availableProcessors(),
    val difficulty: String = "King",
    /** Map size name from [MapSize]; null picks Tiny for duels and Small otherwise */
    val mapSize: String? = null,
    val log: (String) -> Unit = ::println,
) {
    init {
        require(civsPerGame >= 2 && civsPerGame % 2 == 0) { "civsPerGame must be even and at least 2, got $civsPerGame" }
        require(seeds.isNotEmpty()) { "Need at least one map seed" }
    }
}

/** Snapshot of one civ at one moment of a game */
data class CivSnapshot(val cities: Int, val population: Int)

/** Result for one civ in one game */
data class CivResult(
    val civName: String,
    val isCandidate: Boolean,
    val won: Boolean,
    val alive: Boolean,
    val score: Int,
    val snapshots: Map<Int, CivSnapshot>,
    val end: CivSnapshot,
    val unitsKilled: Int,
    val unitsLost: Int,
    val citiesConquered: Int,
    val citiesLost: Int,
    val aiTurnMs: Double,
)

/** Result of one game */
data class GameResult(
    val seed: Long,
    val swapped: Boolean,
    val turns: Int,
    /** null when [AiExperimentConfig.maxTurns] was reached without a victory */
    val winnerCiv: String?,
    val victoryType: String?,
    val civs: List<CivResult>,
    val wallClockMs: Long,
) {
    /** 1 if a candidate civ won, 0 if a baseline civ won, 0.5 for a draw */
    val candidateScore: Double get() = when {
        winnerCiv == null -> 0.5
        civs.first { it.civName == winnerCiv }.isCandidate -> 1.0
        else -> 0.0
    }
    /** For draws: which side has the higher in-game score; 1, 0 or 0.5 */
    val candidateLeadsOnScore: Double get() {
        val candidate = civs.filter { it.isCandidate }.maxOf { it.score }
        val baseline = civs.filter { !it.isCandidate }.maxOf { it.score }
        return if (candidate > baseline) 1.0 else if (candidate < baseline) 0.0 else 0.5
    }
}

/**
 * Plays AI version against AI version inside the same games.
 *
 * Every civ in a game is an identical blank nation (no uniques), so the only difference between the sides is
 * their [AiProfile]. Every map seed is played twice with sides swapped, so map and turn-order advantages cancel out.
 *
 * Does not touch `UncivGame.Current.gameInfo`, so games can run in parallel. Game logic still reads
 * `UncivGame.Current.settings`, so the caller must set those up (see `AiExperimentLauncher` in desktop).
 */
class AiExperiment(private val config: AiExperimentConfig, rulesetName: String = BaseRuleset.Civ_V_GnK.fullName) {

    /** GameStarter takes rulesets from [RulesetCache], so the blank nations must be added to the cached instance */
    private val ruleset: Ruleset = RulesetCache[rulesetName] ?: throw IllegalArgumentException("Unknown ruleset $rulesetName")
    private val civNames = (1..config.civsPerGame).map { "$CIV_NAME_PREFIX$it" }

    /** Games that threw an exception in the last [run]; they are left out of the results */
    var crashedGames = 0
        private set

    init {
        for (name in civNames)
            if (name !in ruleset.nations) ruleset.nations[name] = Nation().apply { this.name = name }
    }

    companion object {
        const val CIV_NAME_PREFIX = "SimulationCiv"

        /** Removes the blank nations added by experiments from the cached ruleset */
        fun removeBlankNations(rulesetName: String = BaseRuleset.Civ_V_GnK.fullName) {
            RulesetCache[rulesetName]?.nations?.keys?.removeAll { it.startsWith(CIV_NAME_PREFIX) }
        }
    }

    fun run(): List<GameResult> = runBlocking {
        val jobs = config.seeds.flatMap { seed -> listOf(seed to false, seed to true) }
        val queue = ConcurrentLinkedQueue(jobs)
        val results = ConcurrentLinkedQueue<GameResult>()
        val done = AtomicInteger(0)
        config.log("AI experiment: candidate '${config.candidate}' vs baseline '${config.baseline}', " +
            "${config.civsPerGame} civs, ${jobs.size} games on ${config.threads} threads")

        val workers = (1..config.threads.coerceAtLeast(1)).map { threadId ->
            launch(Dispatchers.Default + CoroutineName("ai-experiment-$threadId")) {
                while (true) {
                    val (seed, swapped) = queue.poll() ?: break
                    val result = try {
                        playGame(seed, swapped)
                    } catch (ex: Exception) {
                        synchronized(this@AiExperiment) { crashedGames++ }
                        config.log("Game seed=$seed swapped=$swapped crashed: $ex")
                        ex.printStackTrace()
                        null
                    }
                    if (result != null) {
                        results.add(result)
                        val outcome = result.winnerCiv?.let { winner ->
                            val side = if (result.civs.first { it.civName == winner }.isCandidate) "candidate" else "baseline"
                            "$side ($winner) won ${result.victoryType} on turn ${result.turns}"
                        } ?: "draw after ${result.turns} turns"
                        config.log("[${done.incrementAndGet()}/${jobs.size}] seed=$seed swapped=$swapped: $outcome " +
                            "(${result.wallClockMs / 1000}s)")
                    }
                }
            }
        }
        workers.forEach { it.join() }
        results.sortedWith(compareBy({ it.seed }, { it.swapped }))
    }

    /** Which civ indices play the candidate profile. Swapping flips every civ to the other side. */
    internal fun isCandidateSlot(index: Int, swapped: Boolean) = (index % 2 == 0) != swapped

    private fun newGameSetup(seed: Long): GameSetupInfo {
        val gameParameters = GameParameters().apply {
            difficulty = config.difficulty
            numberOfCityStates = 0
            speed = Speed.DEFAULT
            noBarbarians = true
            shufflePlayerOrder = false // sides are swapped explicitly, shuffling would undo that
            baseRuleset = ruleset.name
            players = ArrayList<Player>().apply {
                for (name in civNames) add(Player(ruleset.nations[name]!!))
                add(Player(Constants.spectator, PlayerType.Human).apply { setNationTransient(ruleset) })
            }
            victoryTypes = ArrayList(ruleset.victories.keys)
        }
        val isDuel = config.civsPerGame == 2
        val mapParameters = MapParameters().apply {
            mapSize = MapSize(config.mapSize ?: if (isDuel) MapSize.Tiny.name else MapSize.Small.name)
            noRuins = true
            noNaturalWonders = true
            legendaryStart = true
            strategicBalance = true
            if (isDuel) {
                mirroring = MirroringType.aroundCenterTile
                waterThreshold -= 0.1f // prevents mirrored continent from splitting in two
            }
            this.seed = seed
        }
        return GameSetupInfo(gameParameters, mapParameters)
    }

    internal fun playGame(seed: Long, swapped: Boolean): GameResult {
        val startMs = System.currentTimeMillis()
        // GameStarter keeps state in an object, so only one game may be created at a time
        val gameInfo = synchronized(GameStarter) { GameStarter.startNewGame(newGameSetup(seed)) }

        val majorCivs = civNames.map { gameInfo.getCivilization(it) }
        majorCivs.forEachIndexed { index, civ ->
            civ.aiProfile = if (isCandidateSlot(index, swapped)) config.candidate else config.baseline
        }

        val snapshots = majorCivs.associate { it.civName to HashMap<Int, CivSnapshot>() }
        gameInfo.simulateUntilWin = true
        var winner: Civilization? = null
        for (turn in config.statTurns.filter { it < config.maxTurns }.sorted() + config.maxTurns) {
            gameInfo.simulateMaxTurns = turn
            gameInfo.nextTurn()
            winner = findWinner(gameInfo)
            if (winner != null) break
            if (turn != config.maxTurns) for (civ in majorCivs) snapshots[civ.civName]!![turn] = snapshot(civ)
        }

        val civResults = majorCivs.mapIndexed { index, civ ->
            val counters = civ.experimentCounters
            CivResult(
                civName = civ.civName,
                isCandidate = isCandidateSlot(index, swapped),
                won = civ == winner,
                alive = !civ.isDefeated(),
                score = civ.calculateTotalScore().roundToInt(),
                snapshots = snapshots[civ.civName]!!,
                end = snapshot(civ),
                unitsKilled = counters.unitsKilled,
                unitsLost = counters.unitsLost,
                citiesConquered = counters.citiesConquered,
                citiesLost = counters.citiesLost,
                aiTurnMs = if (counters.aiTurns == 0) 0.0 else counters.aiTurnNanos / 1e6 / counters.aiTurns,
            )
        }
        return GameResult(
            seed = seed,
            swapped = swapped,
            turns = gameInfo.turns,
            winnerCiv = winner?.civName,
            victoryType = winner?.victoryManager?.getVictoryTypeAchieved(),
            civs = civResults,
            wallClockMs = System.currentTimeMillis() - startMs,
        )
    }

    private fun findWinner(gameInfo: GameInfo): Civilization? =
        gameInfo.civilizations.firstOrNull { it.isMajorCiv() && !it.isSpectator() && it.victoryManager.hasWon() }

    private fun snapshot(civ: Civilization) =
        CivSnapshot(civ.cities.size, civ.cities.sumOf { it.population.population })
}
