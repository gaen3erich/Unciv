package com.unciv.logic.llm.observation

import com.badlogic.gdx.utils.JsonReader
import com.unciv.logic.llm.LlmActionCollector
import com.unciv.logic.llm.LlmGatewayTestFixtures
import com.unciv.logic.llm.io.LlmJsonCodec
import com.unciv.models.stats.Stats
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class ObservationV2EconomyTests {

    @Test
    fun `economy stockpiles come from civ managers and reserves`() {
        val fixture = LlmGatewayTestFixtures.gameWithFoundedCity()
        fixture.civ.addGold(42)
        fixture.civ.policies.storedCulture = 17
        fixture.civ.religionManager.storedFaith = 9
        fixture.civ.stats.happiness = 6

        val economy = LlmActionCollector().buildObservation(fixture.gameInfo).economy

        assertEquals(42, economy.gold)
        assertEquals(17, economy.storedCulture)
        assertEquals(9, economy.storedFaith)
        assertEquals(6, economy.happiness)
    }

    @Test
    fun `economy per turn fields come from statsForNextTurn rounded to int`() {
        val fixture = LlmGatewayTestFixtures.gameWithFoundedCity()
        fixture.civ.stats.statsForNextTurn = Stats(
            gold = 1.4f,
            science = 2.5f,
            culture = 3.6f,
            faith = 4.2f,
            happiness = -1.6f
        )

        val economy = LlmActionCollector().buildObservation(fixture.gameInfo).economy

        assertEquals(1, economy.goldPerTurn)
        assertEquals(3, economy.sciencePerTurn)
        assertEquals(4, economy.culturePerTurn)
        assertEquals(4, economy.faithPerTurn)
        assertEquals(-2, economy.happinessPerTurn)
    }

    @Test
    fun `happiness level and happiness per turn are not swapped`() {
        val fixture = LlmGatewayTestFixtures.gameWithFoundedCity()
        fixture.civ.stats.happiness = 11
        fixture.civ.stats.statsForNextTurn = Stats(happiness = -3f)

        val economy = LlmActionCollector().buildObservation(fixture.gameInfo).economy

        assertEquals(11, economy.happiness)
        assertEquals(-3, economy.happinessPerTurn)
    }

    @Test
    fun `economy is encoded as json object`() {
        val fixture = LlmGatewayTestFixtures.gameWithFoundedCity()
        fixture.civ.addGold(42)
        val observation = LlmActionCollector().buildObservation(fixture.gameInfo)

        val json = JsonReader().parse(LlmJsonCodec().encodeObservation(observation))

        assertEquals(42, json.get("economy").getInt("gold"))
    }
}
