package com.unciv.logic.automation

import com.unciv.Constants
import com.unciv.logic.automation.civilization.strategy.ExpansionEvaluator
import com.unciv.logic.automation.civilization.strategy.SituationSnapshot
import com.unciv.logic.automation.civilization.strategy.StrategicPhase
import com.unciv.logic.automation.civilization.strategy.StrategicPlan
import com.unciv.logic.automation.civilization.strategy.StrategicPlanner
import com.unciv.logic.automation.civilization.strategy.StrategicWeights
import com.unciv.logic.automation.civilization.strategy.VictoryKind
import com.unciv.logic.civilization.Civilization
import com.unciv.models.stats.Stat
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.json.json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class StrategicPlanTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var enemy: Civilization

    @Before
    fun setUp() {
        game = TestGame()
        game.makeHexagonalMap(8)
        game.gameInfo.gameParameters.victoryTypes = ArrayList(game.ruleset.victories.keys)
        civ = game.addCiv()
        enemy = game.addCiv()
        game.addCity(civ, game.getTile(-3, 0))
        game.addCity(enemy, game.getTile(4, 0))
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        civ.aiProfile = AiProfile.parse("default+StrategicPlan")
    }

    @Test
    fun noPlanWithoutFeature() {
        civ.aiProfile = AiProfile.DEFAULT
        StrategicPlanner.update(civ)
        assertFalse(civ.aiMemory.strategicPlan.isActive())
        assertEquals(StrategicWeights.NEUTRAL, StrategicPlanner.weights(civ))
        assertEquals(0f, StrategicPlanner.attackMotivationModifier(civ, enemy))
    }

    @Test
    fun firstUpdateMakesAPlan() {
        StrategicPlanner.update(civ)
        val plan = civ.aiMemory.strategicPlan
        assertTrue(plan.isActive())
        assertTrue(plan.goal.isNotEmpty())
        assertEquals(game.gameInfo.turns + StrategicPlanner.PEACE_REVIEW_INTERVAL, plan.nextReviewTurn)
        assertTrue(plan.phase == StrategicPhase.Expansion || plan.phase == StrategicPhase.Development)
    }

    @Test
    fun quietTurnsDoNotTriggerReview() {
        StrategicPlanner.update(civ)
        val plan = civ.aiMemory.strategicPlan
        val now = StrategicPlanner.takeSnapshot(civ)
        assertNull(StrategicPlanner.findTrigger(plan, now, game.gameInfo.turns + 1))
        assertEquals("scheduled", StrategicPlanner.findTrigger(plan, now, plan.nextReviewTurn)?.reason)
    }

    @Test
    fun declaredWarIsUrgentTrigger() {
        StrategicPlanner.update(civ)
        val plan = civ.aiMemory.strategicPlan
        enemy.getDiplomacyManager(civ)!!.declareWar()
        val trigger = StrategicPlanner.findTrigger(plan, StrategicPlanner.takeSnapshot(civ), game.gameInfo.turns)
        assertNotNull(trigger)
        assertTrue(trigger!!.urgent)
        assertTrue(trigger.reason.startsWith("war with"))
    }

    @Test
    fun weakArmyAtWarMeansDefense() {
        StrategicPlanner.update(civ)
        // Give only the enemy an army
        for (y in 1..4) game.addUnit("Warrior", enemy, game.getTile(3, y - 2))
        enemy.getDiplomacyManager(civ)!!.declareWar()
        enemy.resetMilitaryMightCache()
        StrategicPlanner.update(civ)
        val plan = civ.aiMemory.strategicPlan
        assertEquals(StrategicPhase.Defense, plan.phase)
        assertEquals(enemy.civID, plan.warTarget)
        assertTrue(StrategicPlanner.attackMotivationModifier(civ, enemy) < 0f)
        assertTrue(plan.history.any { it.contains("Defense") })
    }

    @Test
    fun strongArmyAtWarMeansWar() {
        StrategicPlanner.update(civ)
        for (y in 1..6) game.addUnit("Warrior", civ, game.getTile(-2, y - 3))
        civ.getDiplomacyManager(enemy)!!.declareWar()
        civ.resetMilitaryMightCache()
        StrategicPlanner.update(civ)
        assertEquals(StrategicPhase.War, civ.aiMemory.strategicPlan.phase)
    }

    @Test
    fun peacefulPhaseChangesWaitForMinimumHold() {
        StrategicPlanner.update(civ)
        val plan = civ.aiMemory.strategicPlan
        plan.phase = StrategicPhase.Development
        plan.phaseSinceTurn = game.gameInfo.turns
        // Expansion would be chosen, but a non-urgent review right after the last change must keep the phase
        StrategicPlanner.review(civ, plan, StrategicPlanner.Trigger("new city", false))
        if (StrategicPlanner.wantsToExpand(civ)) assertEquals(StrategicPhase.Development, plan.phase)
        game.gameInfo.turns += StrategicPlanner.MIN_PHASE_TURNS
        StrategicPlanner.review(civ, plan, StrategicPlanner.Trigger("scheduled", false))
        if (StrategicPlanner.wantsToExpand(civ)) assertEquals(StrategicPhase.Expansion, plan.phase)
    }

    @Test
    fun weightsFollowPhaseAndGoal() {
        val science = StrategicWeights.of(StrategicPhase.Development, VictoryKind.Science)
        val culture = StrategicWeights.of(StrategicPhase.Development, VictoryKind.Culture)
        assertTrue(science.forStat(Stat.Science) > culture.forStat(Stat.Science))
        assertTrue(culture.forStat(Stat.Culture) > science.forStat(Stat.Culture))
        val defense = StrategicWeights.of(StrategicPhase.Defense, VictoryKind.Other)
        val expansion = StrategicWeights.of(StrategicPhase.Expansion, VictoryKind.Other)
        assertTrue(defense.military > expansion.military)
        assertTrue(defense.expansion < expansion.expansion)
        assertTrue(defense.warMotivation < 0f)
    }

    @Test
    fun planSurvivesSaveAndClone() {
        StrategicPlanner.update(civ)
        val plan = civ.aiMemory.strategicPlan
        plan.addHistory("T1 test")
        val restored = json().fromJson(AiMemory::class.java, json().toJson(civ.aiMemory)).strategicPlan
        assertEquals(plan.goal, restored.goal)
        assertEquals(plan.phase, restored.phase)
        assertEquals(plan.nextReviewTurn, restored.nextReviewTurn)
        assertEquals(plan.history, restored.history)
        assertEquals(plan.snapshot.cities, restored.snapshot.cities)

        val cloned = civ.clone().aiMemory.strategicPlan
        assertEquals(plan.toString(), cloned.toString())
        cloned.history.clear()
        assertEquals(1, plan.history.size)
    }

    @Test
    fun snapshotCountsEnemiesAndCities() {
        enemy.getDiplomacyManager(civ)!!.declareWar()
        val snapshot: SituationSnapshot = StrategicPlanner.takeSnapshot(civ)
        assertEquals(setOf(enemy.civID), snapshot.majorEnemies)
        assertEquals(1, snapshot.cities)
    }

    @Test
    fun techMultiplierNeutralWithoutPlan() {
        civ.aiProfile = AiProfile.DEFAULT
        val tech = game.ruleset.technologies.values.first()
        assertEquals(1f, StrategicPlanner.techMultiplier(civ, tech))
    }

    @Test
    fun preferredVictoryFollowsPlan() {
        StrategicPlanner.update(civ)
        val plan: StrategicPlan = civ.aiMemory.strategicPlan
        if (game.gameInfo.gameParameters.victoryTypes.size > 1)
            assertEquals(listOf(plan.goal), civ.getPreferredVictoryTypes())
    }

    @Test
    fun expansionFindsSpotAndComparesWithBuildings() {
        // A bigger grassland map with only our city on it: there must be a free spot worth a settler
        game = TestGame()
        game.makeHexagonalMap(12, Constants.grassland)
        val loner = game.addCiv()
        loner.aiProfile = AiProfile.parse("default+StrategicPlan")
        val city = game.addCity(loner, game.getTile(0, 0))
        city.population.setPopulation(3)
        city.cityStats.update()
        for (tile in game.tileMap.values) tile.setExplored(loner, true)

        val decision = ExpansionEvaluator.evaluate(loner)
        assertNotNull(decision.target)
        assertEquals(city, decision.builder)
        assertTrue(decision.newCityValue > 0f)
        val distance = decision.target!!.aerialDistanceTo(city.getCenterTile())
        assertTrue(distance in 3..ExpansionEvaluator.SEARCH_RADIUS)

        StrategicPlanner.update(loner)
        val plan = loner.aiMemory.strategicPlan
        assertEquals(decision.expand, plan.expansionWanted)
        if (plan.expansionWanted) {
            assertEquals(decision.target!!.position, plan.settleTarget)
            assertEquals(StrategicPhase.Expansion, plan.phase)
        }
    }

    @Test
    fun noExpansionWithoutExploredLand() {
        game = TestGame()
        game.makeHexagonalMap(12, Constants.grassland)
        val loner = game.addCiv()
        game.addCity(loner, game.getTile(0, 0))
        // Nothing explored beyond what the city sees, and city vision is 2-3 tiles: no spot at distance >= 3 known
        for (tile in game.tileMap.values) tile.setExplored(loner, false)
        assertNull(ExpansionEvaluator.evaluate(loner).target)
    }
}
