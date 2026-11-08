package com.unciv.logic.simulation

import com.unciv.logic.GameInfo
import com.unciv.logic.automation.AiProfile
import com.unciv.logic.automation.commander.BattleDetector
import com.unciv.logic.automation.commander.Engagement
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.files.UncivFiles
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/*
 * The tactical arena, see docs/TACTICAL_AI_TESTING.md, step 3.
 *
 * Whole games say little about tactics and cost hours with a real model, so tactics are measured on saved battles:
 * [ArenaPositionCollector] saves games at the moment a real battle is on, [TacticalArena] plays each of them out for a
 * few turns with the candidate on either side, and compares the result with the baseline playing the same position.
 */

enum class ArenaPositionKind { City, Field }

/** Which saved positions a run uses. [Holdout] positions are kept away from tuning, for the final check only. */
enum class ArenaSet { Tune, Holdout, All }

/** A saved battle. The game itself is kept by [ArenaPositionStore]. */
data class ArenaPosition(
    val id: String,
    val seed: Long,
    val turn: Int,
    val kind: ArenaPositionKind,
    /** Military units of both sides in the battle */
    val units: Int,
    /** Production cost of those units, to compare effects between small and large battles */
    val engagedCost: Int,
    val holdout: Boolean,
) {
    fun toCsv() = listOf(id, seed, turn, kind.name, units, engagedCost, holdout).joinToString(",")

    fun isIn(set: ArenaSet) = when (set) {
        ArenaSet.Tune -> !holdout
        ArenaSet.Holdout -> holdout
        ArenaSet.All -> true
    }

    companion object {
        const val CSV_HEADER = "id,seed,turn,kind,units,engagedCost,holdout"

        fun fromCsv(line: String): ArenaPosition {
            val fields = line.trim().split(',')
            require(fields.size == 7) { "Bad arena position line: $line" }
            return ArenaPosition(fields[0], fields[1].toLong(), fields[2].toInt(), ArenaPositionKind.valueOf(fields[3]),
                fields[4].toInt(), fields[5].toInt(), fields[6].toBooleanStrict())
        }

        /**
         * Whether the game with this map seed is kept for the final check. All positions of a game go to the same side,
         * otherwise tuning would see the very battles it is checked on. Depends on the seed alone, so the split stays
         * the same when more positions are collected.
         */
        fun isHoldout(seed: Long, fraction: Double): Boolean {
            // Murmur3 finalizer: consecutive seeds must not land on the same side
            var hash = seed xor (seed ushr 32)
            hash = (hash xor (hash ushr 33)) * -0xae502812aa7333L
            hash = (hash xor (hash ushr 33)) * -0x3b314601e57a13adL
            hash = hash xor (hash ushr 33)
            return Math.floorMod(hash, 10_000L) / 10_000.0 < fraction
        }
    }
}

/** A folder with a `positions.csv` manifest and one save file per position. */
class ArenaPositionStore(val directory: File) {
    private val manifest get() = File(directory, "positions.csv")
    private fun gameFile(id: String) = File(directory, "$id.unciv")

    @Synchronized
    fun add(position: ArenaPosition, gameData: String) {
        directory.mkdirs()
        gameFile(position.id).writeText(gameData)
        if (!manifest.exists()) manifest.writeText(ArenaPosition.CSV_HEADER + "\n")
        manifest.appendText(position.toCsv() + "\n")
    }

    /** All positions sorted by id; a position collected twice counts once, the later entry wins */
    fun positions(): List<ArenaPosition> {
        if (!manifest.exists()) return emptyList()
        return manifest.readLines().drop(1).filter { it.isNotBlank() }
            .map { ArenaPosition.fromCsv(it) }
            .associateBy { it.id }.values
            .sortedBy { it.id }
    }

    fun gameData(position: ArenaPosition): String = gameFile(position.id).readText()
}

data class ArenaCollectConfig(
    /** AI profile both sides play with while the positions are being collected */
    val profile: AiProfile,
    val seeds: List<Long>,
    val maxTurns: Int = 250,
    /** A battle needs this many units of both sides together */
    val minUnits: Int = 6,
    /** ... and this many on each side, so that a lone scout next to an army does not count as a battle */
    val minUnitsPerSide: Int = 2,
    /** Turns between two positions of one game, so that they are different battles */
    val spacing: Int = 10,
    val maxPerGame: Int = 6,
    val holdoutFraction: Double = 0.3,
    val threads: Int = Runtime.getRuntime().availableProcessors(),
    val difficulty: String = "King",
    val log: (String) -> Unit = ::println,
)

/** Plays duels and saves the game whenever a battle of sufficient size is on, see [ArenaPositionStore]. */
class ArenaPositionCollector(private val config: ArenaCollectConfig) {
    var crashedGames = 0
        private set

    fun collect(store: ArenaPositionStore): List<ArenaPosition> {
        val experiment = AiExperiment(AiExperimentConfig(
            baseline = config.profile,
            candidate = config.profile,
            seeds = config.seeds,
            maxTurns = config.maxTurns,
            difficulty = config.difficulty,
            log = config.log,
        ))
        val found = AtomicInteger()
        val results = runArenaJobs(config.seeds, config.threads,
            onError = { seed, ex ->
                synchronized(this) { crashedGames++ }
                config.log("Game seed=$seed crashed: $ex")
            }
        ) { seed ->
            val positions = playAndCapture(experiment, seed, store)
            config.log("seed=$seed: ${positions.size} positions (${found.addAndGet(positions.size)} in total)")
            positions
        }
        return results.flatten().sortedBy { it.id }
    }

    private fun playAndCapture(experiment: AiExperiment, seed: Long, store: ArenaPositionStore): List<ArenaPosition> {
        val (game, civs) = experiment.startGame(seed, swapped = false)
        val positions = ArrayList<ArenaPosition>()
        var lastCapture = Int.MIN_VALUE / 2
        game.simulateUntilWin = true
        for (turn in game.turns + 1..config.maxTurns) {
            game.simulateMaxTurns = turn
            game.nextTurn()
            if (!game.simulateUntilWin || civs.any { it.isDefeated() }) break
            if (game.turns - lastCapture < config.spacing) continue
            val battle = largestBattle(civs, config.minUnits, config.minUnitsPerSide) ?: continue
            val position = describe(seed, game, battle, config.holdoutFraction)
            store.add(position, UncivFiles.gameInfoToString(game, forceZip = true))
            positions += position
            lastCapture = game.turns
            if (positions.size >= config.maxPerGame) break
        }
        return positions
    }

    companion object {
        /** The largest battle either of [civs] is in that is big enough for the arena, or null */
        fun largestBattle(civs: List<Civilization>, minUnits: Int, minUnitsPerSide: Int): Engagement? =
            civs.flatMap { BattleDetector.detect(it) }
                .filter { it.unitCount >= minUnits && it.ownUnits.size >= minUnitsPerSide && it.enemyUnits.size >= minUnitsPerSide }
                .maxByOrNull { it.unitCount }

        fun describe(seed: Long, game: GameInfo, battle: Engagement, holdoutFraction: Double): ArenaPosition {
            val id = "s$seed-t${game.turns}"
            val hasCity = battle.ownCities.isNotEmpty() || battle.enemyCities.isNotEmpty()
            return ArenaPosition(
                id = id,
                seed = seed,
                turn = game.turns,
                kind = if (hasCity) ArenaPositionKind.City else ArenaPositionKind.Field,
                units = battle.unitCount,
                engagedCost = (battle.ownUnits + battle.enemyUnits).sumOf { it.baseUnit.cost.coerceAtLeast(0) },
                holdout = ArenaPosition.isHoldout(seed, holdoutFraction),
            )
        }
    }
}

/** What one side did during a playout */
data class ArenaSide(
    val costKilled: Int,
    val costLost: Int,
    val citiesConquered: Int,
    val citiesLost: Int,
    val commanderCalls: Int = 0,
    val commanderModelErrors: Int = 0,
) {
    /** Enemy production destroyed minus own production lost, with each city worth [cityWeight] production */
    fun score(cityWeight: Int) = costKilled - costLost + cityWeight * (citiesConquered - citiesLost)
}

data class ArenaPlayout(val sideA: ArenaSide, val sideB: ArenaSide, val turnsPlayed: Int)

/** One position played by every pairing the comparison needs. A and B are the two civs of the position. */
data class ArenaPositionResult(
    val position: ArenaPosition,
    /** Baseline on both sides */
    val control: ArenaPlayout,
    /** The same control played again: with a deterministic game it matches [control] exactly */
    val controlReplay: ArenaPlayout,
    val candidateAsA: ArenaPlayout,
    val candidateAsB: ArenaPlayout,
) {
    val controlIsReproducible get() = control == controlReplay

    /** How much better the candidate did than the baseline on the same side of the same position */
    fun effectAsA(cityWeight: Int) = candidateAsA.sideA.score(cityWeight) - control.sideA.score(cityWeight)
    fun effectAsB(cityWeight: Int) = candidateAsB.sideB.score(cityWeight) - control.sideB.score(cityWeight)

    /** Paired effect of this position, averaged over the two sides so that a position's own imbalance cancels out */
    fun effect(cityWeight: Int) = (effectAsA(cityWeight) + effectAsB(cityWeight)) / 2.0

    /** [effect] as a share of the production in the battle, so that late-game battles do not dominate the mean */
    fun relativeEffect(cityWeight: Int) = effect(cityWeight) / position.engagedCost.coerceAtLeast(1)
}

data class ArenaConfig(
    val candidate: AiProfile,
    val baseline: AiProfile,
    /** Turns to play from each position */
    val horizon: Int = 15,
    /** Production a city is worth in the score */
    val cityWeight: Int = 300,
    val set: ArenaSet = ArenaSet.Tune,
    val threads: Int = Runtime.getRuntime().availableProcessors(),
    val log: (String) -> Unit = ::println,
)

/** Plays saved positions out, see the comment at the top of this file. */
class TacticalArena(private val config: ArenaConfig) {
    var crashedPositions = 0
        private set

    fun run(store: ArenaPositionStore): List<ArenaPositionResult> {
        AiExperiment.ensureBlankNations(2)
        val positions = store.positions().filter { it.isIn(config.set) }
        val done = AtomicInteger()
        config.log("Tactical arena: candidate '${config.candidate}' vs baseline '${config.baseline}', " +
            "${positions.size} positions (${config.set}), ${config.horizon} turns each, ${config.threads} threads")
        return runArenaJobs(positions, config.threads,
            onError = { position, ex ->
                synchronized(this) { crashedPositions++ }
                config.log("Position ${position.id} crashed: $ex")
            }
        ) { position ->
            val result = playPosition(position, store.gameData(position))
            config.log("[${done.incrementAndGet()}/${positions.size}] ${position.id}: effect %.0f".format(result.effect(config.cityWeight)))
            result
        }.sortedBy { it.position.id }
    }

    fun playPosition(position: ArenaPosition, gameData: String): ArenaPositionResult {
        val (candidate, baseline) = config.candidate to config.baseline
        return ArenaPositionResult(
            position = position,
            control = playOut(gameData, baseline, baseline, config.horizon),
            controlReplay = playOut(gameData, baseline, baseline, config.horizon),
            candidateAsA = playOut(gameData, candidate, baseline, config.horizon),
            candidateAsB = playOut(gameData, baseline, candidate, config.horizon),
        )
    }

    companion object {
        private val loadLock = Any()

        /** Plays [horizon] turns from a saved game with the given profiles. The save itself is not changed. */
        fun playOut(gameData: String, profileA: AiProfile, profileB: AiProfile, horizon: Int): ArenaPlayout {
            // Loading reads the shared ruleset cache, the playouts themselves are independent
            val game = synchronized(loadLock) { UncivFiles.gameInfoFromString(gameData) }
            val civs = game.civilizations.filter { it.civName.startsWith(AiExperiment.CIV_NAME_PREFIX) }.sortedBy { it.civName }
            require(civs.size == 2) { "An arena position must be a duel, found ${civs.size} simulation civs" }
            val (civA, civB) = civs
            civA.aiProfile = profileA
            civB.aiProfile = profileB

            val startTurn = game.turns
            game.simulateUntilWin = true
            game.simulateMaxTurns = startTurn + horizon
            game.nextTurn()
            return ArenaPlayout(sideOf(civA), sideOf(civB), game.turns - startTurn)
        }

        private fun sideOf(civ: Civilization) = civ.experimentCounters.let {
            ArenaSide(it.unitCostKilled, it.unitCostLost, it.citiesConquered, it.citiesLost, it.commanderCalls, it.commanderModelErrors)
        }
    }
}

/** Runs [action] for every job on [threads] workers; a job that throws is reported to [onError] and left out. */
internal fun <T, R> runArenaJobs(jobs: List<T>, threads: Int, onError: (T, Exception) -> Unit, action: (T) -> R): List<R> = runBlocking {
    val queue = ConcurrentLinkedQueue(jobs)
    val results = ConcurrentLinkedQueue<R>()
    val workers = (1..threads.coerceAtLeast(1)).map { threadId ->
        launch(Dispatchers.Default + CoroutineName("tactical-arena-$threadId")) {
            while (true) {
                val job = queue.poll() ?: break
                try {
                    results.add(action(job))
                } catch (ex: Exception) {
                    onError(job, ex)
                    ex.printStackTrace()
                }
            }
        }
    }
    workers.forEach { it.join() }
    results.toList()
}
