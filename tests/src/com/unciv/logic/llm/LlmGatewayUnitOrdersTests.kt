package com.unciv.logic.llm

import com.unciv.Constants
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.llm.observation.ObservationV4TestHelpers
import com.unciv.logic.map.HexCoord
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class LlmGatewayUnitOrdersTests {

    private fun legalIds(fixture: LlmGatewayTestFixtures.TwoCivFixture) =
        LlmActionCollector().buildObservation(fixture.gameInfo, fixture.firstCiv).legalActions.map { it.id }

    private fun step(fixture: LlmGatewayTestFixtures.TwoCivFixture, vararg ids: String): LlmDecisionResult {
        val legal = LlmActionCollector().buildObservation(fixture.gameInfo, fixture.firstCiv).legalActions
        val actions = ids.map { id -> legal.single { it.id == id } }
        return LlmGateway(ScriptedPolicy(actions)).step(fixture.gameInfo, fixture.firstCiv)
    }

    @Test
    fun `run_units is offered when the civ has units and not otherwise`() {
        val fixture = LlmGatewayTestFixtures.twoHumanCivGame()
        assertFalse(legalIds(fixture).contains(LlmLegalAction.RUN_UNITS_ID))

        fixture.testGame.addUnit("Warrior", fixture.firstCiv, fixture.testGame.getTile(HexCoord(-1, 0)))

        val ids = legalIds(fixture)
        assertTrue(ids.contains(LlmLegalAction.RUN_UNITS_ID))
        val ourCityId = LlmActionCollector.cityIdFor(fixture.firstCiv, fixture.firstCiv.cities.single())
        assertTrue(ids.contains(LlmActionCollector.defendCityId(ourCityId)))
        // Not at war, so there is nothing to attack
        assertTrue(ids.none { it.startsWith("attack_city:") })
    }

    @Test
    fun `declare war is legal for a met civ and switches both sides to war`() {
        val fixture = LlmGatewayTestFixtures.twoHumanCivGame()
        ObservationV4TestHelpers.meet(fixture.firstCiv, fixture.secondCiv)
        val declareWarId = LlmActionCollector.declareWarId(fixture.secondCiv.civID)
        assertTrue(legalIds(fixture).contains(declareWarId))

        val result = step(fixture, declareWarId)

        assertTrue(result.rejectedActions.isEmpty())
        assertEquals(DiplomaticStatus.War, fixture.firstCiv.getDiplomacyManager(fixture.secondCiv)!!.diplomaticStatus)
        assertEquals(DiplomaticStatus.War, fixture.secondCiv.getDiplomacyManager(fixture.firstCiv)!!.diplomaticStatus)
        val idsAfter = legalIds(fixture)
        assertFalse(idsAfter.contains(declareWarId))
        assertTrue(idsAfter.contains(LlmActionCollector.offerPeaceId(fixture.secondCiv.civID)))
    }

    @Test
    fun `offer peace sends a peace treaty request and is not offered twice`() {
        val fixture = LlmGatewayTestFixtures.twoHumanCivGame()
        ObservationV4TestHelpers.meet(fixture.firstCiv, fixture.secondCiv, DiplomaticStatus.War)
        val offerPeaceId = LlmActionCollector.offerPeaceId(fixture.secondCiv.civID)

        val result = step(fixture, offerPeaceId)

        assertTrue(result.rejectedActions.isEmpty())
        val request = fixture.secondCiv.tradeRequests.single()
        assertEquals(fixture.firstCiv.civID, request.requestingCiv)
        assertTrue(request.trade.isPeaceTreaty())
        assertFalse(legalIds(fixture).contains(offerPeaceId))
    }

    @Test
    fun `attack targets are explored enemy cities of civs we are at war with`() {
        val fixture = LlmGatewayTestFixtures.twoHumanCivGame()
        fixture.testGame.addUnit("Warrior", fixture.firstCiv, fixture.testGame.getTile(HexCoord(-1, 0)))
        ObservationV4TestHelpers.meet(fixture.firstCiv, fixture.secondCiv, DiplomaticStatus.War)
        val enemyCity = fixture.secondCiv.cities.single()
        val attackId = LlmActionCollector.attackCityId(LlmActionCollector.cityIdFor(fixture.secondCiv, enemyCity))

        enemyCity.getCenterTile().setExplored(fixture.firstCiv, false)
        assertFalse(legalIds(fixture).contains(attackId))

        enemyCity.getCenterTile().setExplored(fixture.firstCiv, true)
        assertTrue(legalIds(fixture).contains(attackId))
    }

    @Test
    fun `attack order advances the army towards the target city`() {
        val testGame = TestGame()
        testGame.makeHexagonalMap(6)
        val ourCiv = testGame.addCiv(isPlayer = true)
        val enemyCiv = testGame.addCiv(isPlayer = true)
        testGame.addCity(ourCiv, testGame.getTile(HexCoord(-4, 0)))
        val enemyCity = testGame.addCity(enemyCiv, testGame.getTile(HexCoord(4, 0)))
        testGame.gameInfo.currentPlayerCiv = ourCiv
        testGame.gameInfo.currentPlayer = ourCiv.civID
        ObservationV4TestHelpers.meet(ourCiv, enemyCiv, DiplomaticStatus.War)
        enemyCity.getCenterTile().setExplored(ourCiv, true)
        val warrior = testGame.addUnit("Warrior", ourCiv, testGame.getTile(HexCoord(-3, 0)))
        val distanceBefore = warrior.getTile().aerialDistanceTo(enemyCity.getCenterTile())
        val attack = LlmActionCollector().buildObservation(testGame.gameInfo, ourCiv).legalActions
            .single { it.id == LlmActionCollector.attackCityId(LlmActionCollector.cityIdFor(enemyCiv, enemyCity)) }

        val result = LlmGateway(ScriptedPolicy(listOf(attack))).step(testGame.gameInfo, ourCiv)

        assertTrue(result.rejectedActions.isEmpty())
        assertTrue(warrior.getTile().aerialDistanceTo(enemyCity.getCenterTile()) < distanceBefore)
    }

    @Test
    fun `defend order garrisons the city and a later run_units leaves the unit there`() {
        val fixture = LlmGatewayTestFixtures.twoHumanCivGame()
        val warrior = fixture.testGame.addUnit("Warrior", fixture.firstCiv, fixture.testGame.getTile(HexCoord(-1, 0)))
        val ourCity = fixture.firstCiv.cities.single()
        val defendId = LlmActionCollector.defendCityId(LlmActionCollector.cityIdFor(fixture.firstCiv, ourCity))

        val legal = LlmActionCollector().buildObservation(fixture.gameInfo, fixture.firstCiv).legalActions
        val actions = listOf(defendId, LlmLegalAction.RUN_UNITS_ID).map { id -> legal.single { it.id == id } }
        // ScriptedPolicy hands out one action per step, so defend and run_units take two steps
        val gateway = LlmGateway(ScriptedPolicy(actions))

        val defendResult = gateway.step(fixture.gameInfo, fixture.firstCiv)
        assertTrue(defendResult.rejectedActions.isEmpty())
        assertEquals(1, defendResult.appliedActions.size)
        assertEquals(ourCity.getCenterTile(), warrior.getTile())

        val runResult = gateway.step(fixture.gameInfo, fixture.firstCiv)
        assertTrue(runResult.rejectedActions.isEmpty())
        assertEquals(1, runResult.appliedActions.size)
        assertEquals(ourCity.getCenterTile(), warrior.getTile())
    }

    @Test
    fun `found city moves the settler to a ranked site and founds the city there`() {
        val testGame = TestGame()
        testGame.makeHexagonalMap(4, Constants.grassland)
        val civ = testGame.addCiv(isPlayer = true)
        testGame.gameInfo.currentPlayerCiv = civ
        testGame.gameInfo.currentPlayer = civ.civID
        testGame.gameInfo.turns = 1
        val settler = testGame.addUnit("Settler", civ, testGame.getTile(HexCoord.Zero))

        val foundActions = LlmActionCollector().buildObservation(testGame.gameInfo, civ).legalActions
            .filterIsInstance<LlmLegalAction.FoundCity>()
        assertTrue(foundActions.isNotEmpty())
        assertTrue(foundActions.size <= 3)
        assertTrue(foundActions.all { it.unitId == settler.id })

        // Pick a site the settler already stands on, if ranked, so the city is founded this step
        val action = foundActions.firstOrNull { it.location == LlmHexCoord(0, 0) } ?: foundActions.first()
        val site = testGame.getTile(HexCoord(action.location.x, action.location.y))
        val distanceBefore = settler.getTile().aerialDistanceTo(site)

        val result = LlmGateway(ScriptedPolicy(listOf(action))).step(testGame.gameInfo, civ)

        assertTrue(result.rejectedActions.isEmpty())
        if (settler.isDestroyed) {
            assertNotNull(civ.cities.singleOrNull { it.getCenterTile() == site })
        } else {
            assertTrue(settler.getTile().aerialDistanceTo(site) < distanceBefore)
        }
    }

    @Test
    fun `found city on the current tile founds immediately`() {
        val testGame = TestGame()
        testGame.makeHexagonalMap(4, Constants.grassland)
        val civ = testGame.addCiv(isPlayer = true)
        testGame.gameInfo.currentPlayerCiv = civ
        testGame.gameInfo.currentPlayer = civ.civID
        val settler = testGame.addUnit("Settler", civ, testGame.getTile(HexCoord.Zero))
        val action = LlmLegalAction.FoundCity(LlmActionCollector.foundCityId(settler.id, 0, 0), settler.id, settler.name, LlmHexCoord(0, 0))

        val rejection = LlmActionApplier().apply(testGame.gameInfo, civ, action)

        assertEquals(null, rejection)
        assertTrue(settler.isDestroyed)
        assertEquals(testGame.getTile(HexCoord.Zero), civ.cities.single().getCenterTile())
    }
}
