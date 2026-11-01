package com.unciv.logic.simulation

import com.unciv.UncivGame
import com.unciv.logic.automation.AiFeature
import com.unciv.logic.automation.AiProfile
import com.unciv.models.metadata.GameSettings
import com.unciv.models.ruleset.RulesetCache
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class AiExperimentTests {

    @Test
    fun profileSpecsParse() {
        assertEquals(AiProfile.DEFAULT.features, AiProfile.parse("default").features)
        assertTrue(AiProfile.parse("none").features.isEmpty())
        assertEquals(AiFeature.entries.toSet(), AiProfile.parse("all").features)
        assertTrue(AiProfile.parse("default+GreatPersonEscort").has(AiFeature.GreatPersonEscort))
        assertTrue(AiProfile.parse("greatpersonescort").has(AiFeature.GreatPersonEscort))
        assertFalse(AiProfile.parse("all-GreatPersonEscort").has(AiFeature.GreatPersonEscort))
        assertThrows(IllegalArgumentException::class.java) { AiProfile.parse("default+NoSuchFeature") }
    }

    @Test
    fun confidenceIntervals() {
        // 200 games at 50% should give roughly ±7%
        val scores = List(100) { 1.0 } + List(100) { 0.0 }
        val estimate = AiExperimentReport.meanWithCi(scores)
        assertEquals(0.5, estimate.mean, 1e-9)
        assertEquals(0.07, estimate.halfWidth, 0.005)

        val wilson = AiExperimentReport.wilson(0, 10)
        assertEquals(0.0, wilson.low, 1e-9)
        assertTrue(wilson.high > 0.25 && wilson.high < 0.35)
    }

    @Test
    fun shortDuelRunsWithSwappedSides() {
        UncivGame.Current = UncivGame(isConsoleMode = true).apply { settings = GameSettings() }
        if (RulesetCache.isEmpty()) RulesetCache.loadRulesets(noMods = true)
        val config = AiExperimentConfig(
            baseline = AiProfile.parse("none"),
            candidate = AiProfile.parse("all"),
            seeds = listOf(7L),
            maxTurns = 12,
            statTurns = listOf(6),
            threads = 2,
            log = {},
        )
        val results = try { AiExperiment(config).run() } finally { AiExperiment.removeBlankNations() }

        assertEquals(2, results.size)
        assertEquals(listOf(false, true), results.map { it.swapped })
        for (game in results) {
            assertEquals(2, game.civs.size)
            assertEquals(1, game.civs.count { it.isCandidate })
            assertTrue(game.civs.all { 6 in it.snapshots })
        }
        // Sides are swapped: the civ playing the candidate in one game plays the baseline in the other
        val candidateCivs = results.map { game -> game.civs.first { it.isCandidate }.civName }
        assertEquals(2, candidateCivs.toSet().size)

        val report = AiExperimentReport(config, results)
        assertTrue(report.markdown().contains("Candidate score"))
        assertEquals(1 + 2 * 2, report.csv().lines().count { it.isNotBlank() })
    }
}
