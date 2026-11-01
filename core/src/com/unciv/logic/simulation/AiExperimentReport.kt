package com.unciv.logic.simulation

import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/** Statistics and text output for [AiExperiment] results. */
class AiExperimentReport(
    private val config: AiExperimentConfig,
    private val results: List<GameResult>,
    /** Games that crashed and are not in [results] */
    private val crashedGames: Int = 0,
) {

    val games = results.size
    val wins = results.count { it.candidateScore == 1.0 }
    val losses = results.count { it.candidateScore == 0.0 }
    val draws = results.count { it.winnerCiv == null }

    /** Mean candidate score (win 1, draw 0.5, loss 0) with a 95% confidence interval */
    val score: Estimate = meanWithCi(results.map { it.candidateScore })

    /** Share of decisive games won by the candidate, Wilson 95% interval */
    val decisiveWinRate: Estimate = wilson(wins, wins + losses)

    /** Two-sided p-value for "candidate score differs from 0.5" (normal approximation) */
    val pValue: Double get() {
        if (games < 2 || score.halfWidth == 0.0) return if (score.mean == 0.5) 1.0 else 0.0
        val z = (score.mean - 0.5) / (score.halfWidth / Z95)
        return 2 * (1 - normalCdf(abs(z)))
    }

    data class Estimate(val mean: Double, val low: Double, val high: Double) {
        val halfWidth get() = (high - low) / 2
        fun pct() = "%.1f%% [%.1f%%, %.1f%%]".format(Locale.ROOT, mean * 100, low * 100, high * 100)
    }

    fun verdict(): String = when {
        games == 0 -> "no games finished"
        score.low > 0.5 -> "candidate is stronger (95% CI above 50%)"
        score.high < 0.5 -> "candidate is weaker (95% CI below 50%)"
        else -> "no significant difference at this sample size"
    }

    fun markdown(): String = buildString {
        appendLine("# AI experiment: `${config.candidate}` vs `${config.baseline}`")
        appendLine()
        appendLine("- Candidate features: ${config.candidate.features.joinToString().ifEmpty { "none" }}")
        appendLine("- Baseline features: ${config.baseline.features.joinToString().ifEmpty { "none" }}")
        appendLine("- Format: ${if (config.civsPerGame == 2) "duel" else "${config.civsPerGame} civs, half per side"}, " +
            "${config.seeds.size} map seeds × 2 (sides swapped), max ${config.maxTurns} turns, ${config.difficulty}")
        appendLine("- Games finished: $games" + if (crashedGames > 0) ", **crashed: $crashedGames**" else "")
        appendLine()
        appendLine("## Result")
        appendLine()
        appendLine("**${verdict()}**")
        appendLine()
        appendLine("| | value |")
        appendLine("|---|---|")
        appendLine("| Candidate wins / draws / losses | $wins / $draws / $losses |")
        appendLine("| Candidate score (win 1, draw ½), 95% CI | ${score.pct()} |")
        appendLine("| p-value vs 50% | ${"%.3f".format(Locale.ROOT, pValue)} |")
        appendLine("| Candidate share of decisive games, Wilson 95% CI | ${decisiveWinRate.pct()} |")
        if (draws > 0) {
            val lead = results.filter { it.winnerCiv == null }.map { it.candidateLeadsOnScore }.average()
            appendLine("| Draws where candidate leads on game score | ${"%.0f%%".format(Locale.ROOT, lead * 100)} |")
        }
        val victories = results.mapNotNull { r -> r.victoryType?.let { r.candidateScore to it } }
            .groupBy({ it.second }, { it.first })
        for ((type, scores) in victories.toSortedMap())
            appendLine("| $type victories: candidate / baseline | ${scores.count { it == 1.0 }} / ${scores.count { it == 0.0 }} |")
        appendLine("| Average game length, turns | ${fmt(results.map { it.turns.toDouble() }.average())} |")
        appendLine()
        appendLine("## Secondary metrics (average per civ)")
        appendLine()
        appendLine("| metric | candidate | baseline |")
        appendLine("|---|---|---|")
        for (turn in config.statTurns.filter { it < config.maxTurns }.sorted()) {
            row("Cities on turn $turn") { it.snapshots[turn]?.cities?.toDouble() }
            row("Population on turn $turn") { it.snapshots[turn]?.population?.toDouble() }
        }
        row("Cities at game end") { it.end.cities.toDouble() }
        row("Population at game end") { it.end.population.toDouble() }
        row("Cities conquered") { it.citiesConquered.toDouble() }
        row("Cities lost") { it.citiesLost.toDouble() }
        row("Enemy units killed") { it.unitsKilled.toDouble() }
        row("Own units lost") { it.unitsLost.toDouble() }
        appendLine("| Kill / loss ratio | ${killLoss(true)} | ${killLoss(false)} |")
        row("Survived to the end, %") { if (it.alive) 100.0 else 0.0 }
        row("AI time per turn, ms") { it.aiTurnMs }
        appendLine()
        appendLine("Population and cities at turn N only count games that lasted that long.")
    }

    private fun StringBuilder.row(name: String, value: (CivResult) -> Double?) {
        fun side(candidate: Boolean) = results.flatMap { it.civs }.filter { it.isCandidate == candidate }
            .mapNotNull(value).let { if (it.isEmpty()) "–" else fmt(it.average()) }
        appendLine("| $name | ${side(true)} | ${side(false)} |")
    }

    private fun killLoss(candidate: Boolean): String {
        val civs = results.flatMap { it.civs }.filter { it.isCandidate == candidate }
        val lost = civs.sumOf { it.unitsLost }
        return if (lost == 0) "–" else fmt(civs.sumOf { it.unitsKilled }.toDouble() / lost)
    }

    /** One row per civ per game, for further analysis */
    fun csv(): String = buildString {
        val turns = config.statTurns.filter { it < config.maxTurns }.sorted()
        append("seed,swapped,turns,winner,victoryType,civ,side,won,alive,score,")
        append(turns.joinToString("") { "cities$it,pop$it," })
        appendLine("citiesEnd,popEnd,unitsKilled,unitsLost,citiesConquered,citiesLost,aiTurnMs")
        for (game in results) for (civ in game.civs) {
            append("${game.seed},${game.swapped},${game.turns},${game.winnerCiv ?: ""},${game.victoryType ?: ""},")
            append("${civ.civName},${if (civ.isCandidate) "candidate" else "baseline"},${civ.won},${civ.alive},${civ.score},")
            append(turns.joinToString("") { "${civ.snapshots[it]?.cities ?: ""},${civ.snapshots[it]?.population ?: ""}," })
            appendLine("${civ.end.cities},${civ.end.population},${civ.unitsKilled},${civ.unitsLost}," +
                "${civ.citiesConquered},${civ.citiesLost},${"%.2f".format(Locale.ROOT, civ.aiTurnMs)}")
        }
    }

    companion object {
        private const val Z95 = 1.959964

        private fun fmt(value: Double) = "%.2f".format(Locale.ROOT, value)

        fun meanWithCi(values: List<Double>): Estimate {
            if (values.isEmpty()) return Estimate(0.5, 0.0, 1.0)
            val mean = values.average()
            if (values.size < 2) return Estimate(mean, 0.0, 1.0)
            val variance = values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)
            val half = Z95 * sqrt(variance / values.size)
            return Estimate(mean, (mean - half).coerceAtLeast(0.0), (mean + half).coerceAtMost(1.0))
        }

        fun wilson(successes: Int, trials: Int): Estimate {
            if (trials == 0) return Estimate(0.5, 0.0, 1.0)
            val p = successes.toDouble() / trials
            val z2 = Z95 * Z95
            val denominator = 1 + z2 / trials
            val center = (p + z2 / (2 * trials)) / denominator
            val half = Z95 * sqrt(p * (1 - p) / trials + z2 / (4.0 * trials * trials)) / denominator
            return Estimate(p, center - half, center + half)
        }

        fun normalCdf(z: Double): Double = 0.5 * (1 + erf(z / sqrt(2.0)))

        /** Numerical Recipes approximation of the error function, accurate to ~1e-7 */
        private fun erf(x: Double): Double {
            val t = 1.0 / (1.0 + 0.5 * abs(x))
            val tau = t * exp(
                -x * x - 1.26551223 +
                    t * (1.00002368 + t * (0.37409196 + t * (0.09678418
                    + t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398 +
                    t * (1.48851587 + t * (-0.82215223 + t * 0.17087277))))))))
            )
            return if (x >= 0) 1 - tau else tau - 1
        }
    }
}
