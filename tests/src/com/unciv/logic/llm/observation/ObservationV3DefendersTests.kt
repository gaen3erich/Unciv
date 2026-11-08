package com.unciv.logic.llm.observation

import com.unciv.logic.llm.LlmActionCollector
import com.unciv.logic.llm.LlmGatewayTestFixtures
import com.unciv.logic.map.HexCoord
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class ObservationV3DefendersTests {

    @Test
    fun `city with no military unit has no garrison`() {
        val fixture = LlmGatewayTestFixtures.gameWithFoundedCity()

        val defenders = LlmActionCollector().buildObservation(fixture.gameInfo).cities.single().defenders

        assertNull(defenders.garrison)
        assertEquals(0, defenders.supportingUnitCount)
    }

    @Test
    fun `city garrison is represented as unit brief`() {
        val fixture = LlmGatewayTestFixtures.gameWithFoundedCity()
        val city = fixture.civ.cities.single()
        val warrior = fixture.testGame.addUnit("Warrior", fixture.civ, city.getCenterTile())
        warrior.health = 30

        val garrison = LlmActionCollector().buildObservation(fixture.gameInfo).cities.single().defenders.garrison!!

        assertEquals("Warrior", garrison.name)
        assertEquals(30, garrison.healthPercent)
        assertTrue(garrison.isInjured)
    }

    @Test
    fun `friendly military units within distance two are counted as support and garrison is excluded`() {
        val fixture = LlmGatewayTestFixtures.gameWithFoundedCity()
        val city = fixture.civ.cities.single()
        fixture.testGame.addUnit("Warrior", fixture.civ, city.getCenterTile())
        fixture.testGame.addUnit("Archer", fixture.civ, fixture.testGame.getTile(HexCoord(1, 0)))
        fixture.testGame.addUnit("Archer", fixture.civ, fixture.testGame.getTile(HexCoord(0, 1)))

        val defenders = LlmActionCollector().buildObservation(fixture.gameInfo).cities.single().defenders

        assertEquals(2, defenders.supportingUnitCount)
        assertEquals(mapOf("Archer" to 2), defenders.supportingUnitTypeBreakdown)
        assertFalse(defenders.garrison!!.isInjured)
    }
}
