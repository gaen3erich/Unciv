package com.unciv.logic.simulation

import java.util.Locale

/** Turns the results of a [TacticalArena] run into a verdict, a Markdown report and a CSV. */
class TacticalArenaReport(
    private val config: ArenaConfig,
    private val results: List<ArenaPositionResult>,
    private val crashedPositions: Int = 0,
) {
    private val effects = results.map { it.effect(config.cityWeight) }

    private fun byGame(group: List<ArenaPositionResult>, value: (ArenaPositionResult) -> Double) =
        clusterBootstrapMean(group.groupBy { it.position.seed }.values.map { results -> results.map(value) })

    /** Mean paired effect in production points, with a bootstrap interval over the games the positions came from */
    val effect: AiExperimentReport.Estimate = byGame(results) { it.effect(config.cityWeight) }
    val relativeEffect: AiExperimentReport.Estimate = byGame(results) { it.relativeEffect(config.cityWeight) }
    val positionsWon = effects.count { it > 0 }
    val positionsLost = effects.count { it < 0 }
    val reproducibleControls = results.count { it.controlIsReproducible }

    fun verdict(): String = when {
        results.size < MIN_POSITIONS -> "too few positions for a verdict (need at least $MIN_POSITIONS)"
        effect.low > 0 -> "candidate is better than the baseline"
        effect.high < 0 -> "candidate is WORSE than the baseline"
        else -> "no detectable difference"
    }

    fun markdown(): String = buildString {
        appendLine("# Tactical arena: `${config.candidate}` vs `${config.baseline}`")
        appendLine()
        appendLine("- Positions: ${results.size} from ${results.map { it.position.seed }.distinct().size} games (${config.set})" +
            if (crashedPositions > 0) ", $crashedPositions crashed and left out" else "")
        appendLine("- Horizon: ${config.horizon} turns; a city is worth ${config.cityWeight} production")
        appendLine("- Effect of a position: candidate's score minus the baseline's score on the same side of it, averaged over both sides")
        appendLine("- Intervals: bootstrap over games, 95%")
        appendLine()
        appendLine("## Result")
        appendLine()
        appendLine("**${verdict()}**")
        appendLine()
        appendLine("| | value |")
        appendLine("|---|---|")
        appendLine("| Mean effect, production per position | ${fmt(effect)} |")
        appendLine("| Mean effect, share of the production in the battle | ${pct(relativeEffect)} |")
        appendLine("| Positions better / worse / equal | $positionsWon / $positionsLost / ${results.size - positionsWon - positionsLost} |")
        appendLine("| Controls that replayed identically | $reproducibleControls of ${results.size} |")
        val calls = results.sumOf { it.candidateAsA.sideA.commanderCalls + it.candidateAsB.sideB.commanderCalls }
        if (calls > 0) {
            val errors = results.sumOf { it.candidateAsA.sideA.commanderModelErrors + it.candidateAsB.sideB.commanderModelErrors }
            appendLine("| LLM commander calls / battles lost to model errors | $calls / $errors |")
        }
        if (reproducibleControls < results.size) {
            appendLine()
            appendLine("Some controls did not replay identically, so part of the effect may be noise of the playouts themselves.")
        }
        if (config.candidate == config.baseline) {
            appendLine()
            appendLine("Candidate and baseline have the same features: the effect only shows how reproducible the arena is.")
        }
        val kinds = results.groupBy { it.position.kind }
        if (kinds.size > 1) {
            appendLine()
            appendLine("## By kind of battle")
            appendLine()
            appendLine("| kind | positions | mean effect |")
            appendLine("|---|---|---|")
            for ((kind, group) in kinds.toSortedMap())
                appendLine("| $kind | ${group.size} | ${fmt(byGame(group) { it.effect(config.cityWeight) })} |")
        }
    }

    fun csv(): String = buildString {
        appendLine("id,kind,units,engagedCost,effect,effectAsA,effectAsB,controlA,controlB,controlReproducible")
        for (result in results) {
            val weight = config.cityWeight
            appendLine(listOf(result.position.id, result.position.kind, result.position.units, result.position.engagedCost,
                result.effect(weight), result.effectAsA(weight), result.effectAsB(weight),
                result.control.sideA.score(weight), result.control.sideB.score(weight), result.controlIsReproducible).joinToString(","))
        }
    }

    companion object {
        const val MIN_POSITIONS = 10

        private fun fmt(e: AiExperimentReport.Estimate) = "%+.1f [%+.1f, %+.1f]".format(Locale.ROOT, e.mean, e.low, e.high)
        private fun pct(e: AiExperimentReport.Estimate) = "%+.1f%% [%+.1f%%, %+.1f%%]".format(Locale.ROOT, e.mean * 100, e.low * 100, e.high * 100)

        /** [clusterBootstrapMean] of independent values */
        fun bootstrapMean(values: List<Double>): AiExperimentReport.Estimate = clusterBootstrapMean(values.map { listOf(it) })

        /**
         * Mean with a 95% percentile bootstrap interval, resampling whole [clusters]. Positions of one game are
         * correlated, so they are resampled together; treating them as independent would make the interval too narrow.
         * Battles are few and their effects heavy-tailed, so the normal approximation of
         * [AiExperimentReport.meanWithCi] does not fit. Seeded: the same results give the same report.
         */
        fun clusterBootstrapMean(clusters: List<List<Double>>, resamples: Int = 10_000, seed: Long = 1L): AiExperimentReport.Estimate {
            val filled = clusters.filter { it.isNotEmpty() }
            if (filled.isEmpty()) return AiExperimentReport.Estimate(0.0, 0.0, 0.0)
            val random = java.util.Random(seed)
            val means = DoubleArray(resamples) {
                var sum = 0.0
                var count = 0
                repeat(filled.size) {
                    val cluster = filled[random.nextInt(filled.size)]
                    sum += cluster.sum()
                    count += cluster.size
                }
                sum / count
            }
            means.sort()
            return AiExperimentReport.Estimate(filled.flatten().average(), means[(resamples * 0.025).toInt()], means[(resamples * 0.975).toInt() - 1])
        }
    }
}
