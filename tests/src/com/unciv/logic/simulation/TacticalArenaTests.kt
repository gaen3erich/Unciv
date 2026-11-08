package com.unciv.logic.simulation

import com.unciv.UncivGame
import com.unciv.logic.automation.AiProfile
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.files.UncivFiles
import com.unciv.models.metadata.GameSettings
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.nation.Nation
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files

@RunWith(GdxTestRunner::class)
class TacticalArenaTests {
    private val profile = AiProfile.parse("default+UnitTactics")
    private fun position(id: String = "s1-t50", cost: Int = 400, holdout: Boolean = false) =
        ArenaPosition(id, 1L, 50, ArenaPositionKind.Field, 6, cost, holdout)

    private fun side(killed: Int = 0, lost: Int = 0, conquered: Int = 0, citiesLost: Int = 0) =
        ArenaSide(killed, lost, conquered, citiesLost)

    private fun playout(a: ArenaSide, b: ArenaSide) = ArenaPlayout(a, b, 15)

    @After
    fun removeBlankNations() = AiExperiment.removeBlankNations()

    @Test
    fun `side score is destroyed production minus lost with cities weighted`() {
        assertEquals(100 - 40 + 2 * 300, side(100, 40, 3, 1).score(300))
    }

    @Test
    fun `effect compares each side with the same side of the control and averages`() {
        val result = ArenaPositionResult(
            position(),
            control = playout(side(100, 50), side(50, 100)),
            controlReplay = playout(side(100, 50), side(50, 100)),
            candidateAsA = playout(side(200, 50), side(50, 200)),
            candidateAsB = playout(side(100, 50), side(50, 90)),
        )

        assertEquals(100, result.effectAsA(300))
        assertEquals(10, result.effectAsB(300))
        assertEquals(55.0, result.effect(300), 1e-9)
        assertEquals(55.0 / 400, result.relativeEffect(300), 1e-9)
        assertTrue(result.controlIsReproducible)
    }

    @Test
    fun `a position that favours one side does not count as an effect`() {
        val lopsided = playout(side(500, 0), side(0, 500))
        val result = ArenaPositionResult(position(), lopsided, lopsided, lopsided, lopsided)

        assertEquals(0.0, result.effect(300), 1e-9)
    }

    @Test
    fun `bootstrap interval brackets the mean and collapses without variance`() {
        val spread = TacticalArenaReport.bootstrapMean(List(50) { it.toDouble() })
        assertEquals(24.5, spread.mean, 1e-9)
        assertTrue(spread.low < 24.5 && spread.high > 24.5)

        val constant = TacticalArenaReport.bootstrapMean(List(20) { 7.0 })
        assertEquals(7.0, constant.low, 1e-9)
        assertEquals(7.0, constant.high, 1e-9)
    }

    private fun resultWithEffect(index: Int, effect: Int): ArenaPositionResult {
        val control = playout(side(100, 100), side(100, 100))
        val better = playout(side(100 + effect, 100), side(100, 100 + effect))
        val mirrored = playout(side(100, 100 + effect), side(100 + effect, 100))
        return ArenaPositionResult(position("s$index-t50"), control, control, better, mirrored)
    }

    private val config = ArenaConfig(AiProfile.parse("default+FocusFire"), profile, log = {})

    @Test
    fun `verdict needs an interval that excludes zero`() {
        val better = TacticalArenaReport(config, List(20) { resultWithEffect(it, 40 + it) })
        assertTrue(better.verdict(), better.verdict().startsWith("candidate is better"))
        assertEquals(20, better.positionsWon)

        val worse = TacticalArenaReport(config, List(20) { resultWithEffect(it, -40 - it) })
        assertTrue(worse.verdict(), worse.verdict().contains("WORSE"))

        val mixed = TacticalArenaReport(config, List(20) { resultWithEffect(it, if (it % 2 == 0) 50 else -50) })
        assertEquals("no detectable difference", mixed.verdict())
    }

    @Test
    fun `too few positions give no verdict`() {
        val report = TacticalArenaReport(config, List(3) { resultWithEffect(it, 100) })

        assertTrue(report.verdict().startsWith("too few"))
    }

    @Test
    fun `report warns when controls do not replay identically`() {
        val result = resultWithEffect(1, 10).copy(controlReplay = playout(side(1, 2), side(2, 1)))
        val report = TacticalArenaReport(config, listOf(result))

        assertEquals(0, report.reproducibleControls)
        assertTrue(report.markdown().contains("did not replay identically"))
    }

    @Test
    fun `manifest line round trips`() {
        val original = ArenaPosition("s12-t88", 12L, 88, ArenaPositionKind.City, 9, 730, true)

        assertEquals(original, ArenaPosition.fromCsv(original.toCsv()))
    }

    @Test
    fun `holdout split is close to the requested fraction even for consecutive seeds`() {
        val consecutive = (1L..2000L).count { ArenaPosition.isHoldout(it, 0.3) }
        assertEquals(0.3, consecutive / 2000.0, 0.03)

        // Small runs use the first few seeds, which must not all land on one side
        val firstSeeds = (1L..24L).count { ArenaPosition.isHoldout(it, 0.3) }
        assertTrue("got $firstSeeds of 24", firstSeeds in 3..12)
    }

    @Test
    fun `all positions of a game are on the same side of the split`() {
        val field = battlefield()
        val battle = ArenaPositionCollector.largestBattle(listOf(field.civA, field.civB), 6, 2)!!
        val game = field.testGame.gameInfo
        val first = ArenaPositionCollector.describe(5L, game, battle, 0.3)
        game.turns += 40
        val second = ArenaPositionCollector.describe(5L, game, battle, 0.3)

        assertFalse(first.id == second.id)
        assertEquals(first.holdout, second.holdout)
    }

    @Test
    fun `positions of one game count as one sample in the interval`() {
        // Two games, ten positions each: the games disagree, so the interval must span nearly the whole range
        val clusters = listOf(List(10) { 100.0 }, List(10) { -100.0 })
        val clustered = TacticalArenaReport.clusterBootstrapMean(clusters)
        val independent = TacticalArenaReport.bootstrapMean(clusters.flatten())

        assertEquals(0.0, clustered.mean, 1e-9)
        assertTrue(clustered.high - clustered.low > 2 * (independent.high - independent.low))
    }

    @Test
    fun `sets select tune and holdout positions`() {
        val tune = position(holdout = false)
        val holdout = position(holdout = true)

        assertTrue(tune.isIn(ArenaSet.Tune) && !tune.isIn(ArenaSet.Holdout))
        assertTrue(holdout.isIn(ArenaSet.Holdout) && !holdout.isIn(ArenaSet.Tune))
        assertTrue(tune.isIn(ArenaSet.All) && holdout.isIn(ArenaSet.All))
    }

    @Test
    fun `store keeps positions and saves and the later copy of a duplicate`() {
        val store = ArenaPositionStore(Files.createTempDirectory("arena").toFile())
        assertTrue(store.positions().isEmpty())

        store.add(position("s2-t40"), "second")
        store.add(position("s1-t40", cost = 100), "old")
        store.add(position("s1-t40", cost = 200), "new")

        assertEquals(listOf("s1-t40", "s2-t40"), store.positions().map { it.id })
        assertEquals(200, store.positions().first().engagedCost)
        assertEquals("new", store.gameData(store.positions().first()))
    }

    private class Battlefield(val testGame: TestGame, val civA: Civilization, val civB: Civilization)

    /** Two blank-nation civs at war, each with a city and an army in contact in the middle of the map */
    private fun battlefield(unitsPerSide: Int = 4): Battlefield {
        val testGame = TestGame()
        testGame.makeHexagonalMap(10, com.unciv.Constants.grassland)
        AiExperiment.ensureBlankNations(2)
        fun blank(index: Int) = Nation().apply { name = "${AiExperiment.CIV_NAME_PREFIX}$index" }
        val civA = testGame.addCiv(blank(1))
        val civB = testGame.addCiv(blank(2))
        // setTransients needs a human player, and a save can only name nations that the cached ruleset knows
        testGame.addCiv(testGame.ruleset.nations[com.unciv.Constants.spectator]!!, isPlayer = true)
        civA.diplomacyFunctions.makeCivilizationsMeet(civB)
        civA.getDiplomacyManager(civB)!!.declareWar()
        testGame.addCity(civA, testGame.getTile(-8, 0))
        testGame.addCity(civB, testGame.getTile(8, 0))
        for (i in 0 until unitsPerSide) {
            testGame.addUnit("Warrior", civA, testGame.getTile(-1, i - 2))
            testGame.addUnit("Archer", civB, testGame.getTile(1, i - 2))
        }
        for (civ in listOf(civA, civB)) civ.cache.updateViewableTiles()
        UncivGame.Current = UncivGame(isConsoleMode = true).apply { settings = GameSettings() }
        if (RulesetCache.isEmpty()) RulesetCache.loadRulesets(noMods = true)
        return Battlefield(testGame, civA, civB)
    }

    @Test
    fun `largest battle needs enough units on both sides`() {
        val field = battlefield()
        val civs = listOf(field.civA, field.civB)

        val battle = ArenaPositionCollector.largestBattle(civs, minUnits = 6, minUnitsPerSide = 2)
        assertNotNull(battle)
        assertEquals(8, battle!!.unitCount)
        assertNull(ArenaPositionCollector.largestBattle(civs, minUnits = 9, minUnitsPerSide = 2))
        assertNull(ArenaPositionCollector.largestBattle(civs, minUnits = 6, minUnitsPerSide = 5))
    }

    @Test
    fun `describe records the battle`() {
        val field = battlefield()
        val battle = ArenaPositionCollector.largestBattle(listOf(field.civA, field.civB), 6, 2)!!

        val position = ArenaPositionCollector.describe(7L, field.testGame.gameInfo, battle, 0.3)

        assertEquals("s7-t${field.testGame.gameInfo.turns}", position.id)
        assertEquals(ArenaPositionKind.Field, position.kind)
        assertEquals(8, position.units)
        assertTrue(position.engagedCost > 0)
    }

    @Test
    fun `playout fights from a saved position and leaves the save untouched`() {
        val field = battlefield()
        val save = UncivFiles.gameInfoToString(field.testGame.gameInfo, forceZip = false)

        val result = TacticalArena.playOut(save, profile, profile, horizon = 10)

        assertEquals(10, result.turnsPlayed)
        assertTrue("armies in contact should destroy something: $result", result.sideA.costKilled + result.sideB.costKilled > 0)
        assertEquals("what one side destroys the other loses", result.sideA.costKilled, result.sideB.costLost)
        assertEquals(result.sideB.costKilled, result.sideA.costLost)
        assertEquals(save, UncivFiles.gameInfoToString(field.testGame.gameInfo, forceZip = false))
    }

    @Test
    fun `arena plays the positions of the chosen set`() {
        val field = battlefield()
        val store = ArenaPositionStore(Files.createTempDirectory("arena").toFile())
        val save = UncivFiles.gameInfoToString(field.testGame.gameInfo, forceZip = true)
        store.add(position("s1-t1", holdout = false), save)
        store.add(position("s2-t1", holdout = true), save)
        val arena = TacticalArena(ArenaConfig(profile, profile, horizon = 8, threads = 2, log = {}))

        val results = arena.run(store)

        assertEquals(listOf("s1-t1"), results.map { it.position.id })
        assertEquals(0, arena.crashedPositions)
        assertFalse(TacticalArenaReport(ArenaConfig(profile, profile, log = {}), results).markdown().isEmpty())
    }
}
