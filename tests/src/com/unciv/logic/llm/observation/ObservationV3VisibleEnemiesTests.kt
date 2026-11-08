package com.unciv.logic.llm.observation

import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.civilization.diplomacy.DiplomacyManager
import com.unciv.logic.llm.LlmActionCollector
import com.unciv.logic.llm.LlmGatewayTestFixtures
import com.unciv.logic.map.HexCoord
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class ObservationV3VisibleEnemiesTests {

    @Test
    fun `peaceful civ has no visible enemies`() {
        val fixture = LlmGatewayTestFixtures.twoHumanCivGame()
        fixture.firstCiv.viewableTiles = fixture.gameInfo.tileMap.values.toSet()

        val enemies = LlmActionCollector().buildObservation(fixture.gameInfo, fixture.firstCiv)
            .cities.single { it.name == fixture.firstCiv.cities.single().name }
            .visibleEnemies

        assertEquals(0, enemies.totalCount)
        assertNull(enemies.closestDistance)
        assertEquals(emptyList<Any>(), enemies.byCiv)
        assertEquals(emptyList<Any>(), enemies.brief)
    }

    @Test
    fun `visible enemy military unit at war is counted`() {
        val fixture = LlmGatewayTestFixtures.twoHumanCivGame()
        makeWar(fixture)
        val enemyTile = fixture.testGame.getTile(HexCoord(1, 0))
        fixture.testGame.addUnit("Warrior", fixture.secondCiv, enemyTile)
        fixture.firstCiv.viewableTiles = setOf(enemyTile)

        val enemies = LlmActionCollector().buildObservation(fixture.gameInfo, fixture.firstCiv).cities.first().visibleEnemies

        assertEquals(1, enemies.totalCount)
        assertEquals(1, enemies.closestDistance)
        assertEquals(fixture.secondCiv.civName, enemies.byCiv.single().civName)
        assertEquals(1, enemies.byCiv.single().count)
        assertEquals("Warrior", enemies.brief.single().name)
    }

    @Test
    fun `enemy military unit on fogged tile is not counted`() {
        val fixture = LlmGatewayTestFixtures.twoHumanCivGame()
        makeWar(fixture)
        val enemyTile = fixture.testGame.getTile(HexCoord(1, 0))
        fixture.testGame.addUnit("Warrior", fixture.secondCiv, enemyTile)
        fixture.firstCiv.viewableTiles = emptySet()

        val enemies = LlmActionCollector().buildObservation(fixture.gameInfo, fixture.firstCiv).cities.first().visibleEnemies

        assertEquals(0, enemies.totalCount)
    }

    @Test
    fun `visible enemy brief is capped at five units`() {
        val fixture = LlmGatewayTestFixtures.twoHumanCivGame()
        makeWar(fixture)
        val enemyTiles = fixture.testGame.getTile(HexCoord.Zero).getTilesInDistance(5).drop(1).take(6).toList()
        for (tile in enemyTiles) fixture.testGame.addUnit("Warrior", fixture.secondCiv, tile)
        fixture.firstCiv.viewableTiles = enemyTiles.toSet()

        val enemies = LlmActionCollector().buildObservation(fixture.gameInfo, fixture.firstCiv).cities.first().visibleEnemies

        assertEquals(6, enemies.totalCount)
        assertEquals(5, enemies.brief.size)
    }

    private fun makeWar(fixture: LlmGatewayTestFixtures.TwoCivFixture) {
        fixture.firstCiv.diplomacy[fixture.secondCiv.civID] = DiplomacyManager(fixture.firstCiv, fixture.secondCiv)
            .apply { diplomaticStatus = DiplomaticStatus.War }
        fixture.secondCiv.diplomacy[fixture.firstCiv.civID] = DiplomacyManager(fixture.secondCiv, fixture.firstCiv)
            .apply { diplomaticStatus = DiplomaticStatus.War }
    }
}
