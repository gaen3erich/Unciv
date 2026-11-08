package com.unciv.logic.llm.observation

import com.unciv.logic.llm.LlmActionCollector
import com.unciv.logic.llm.LlmGatewayTestFixtures
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(GdxTestRunner::class)
class ObservationV4RelativeStandingTests {

    @Test
    fun `single known cohort ranks observer first across categories`() {
        val fixture = LlmGatewayTestFixtures.gameWithFoundedCity()

        val standing = LlmActionCollector().buildObservation(fixture.gameInfo, fixture.civ).diplomacy.relativeStanding

        assertEquals(1, standing.gold.rank)
        assertEquals(1, standing.gold.totalKnownMajors)
        assertEquals(fixture.civ.gold, standing.gold.ourValue)
        assertEquals(fixture.civ.stats.statsForNextTurn.food.roundToInt(), standing.growth.ourValue)
        assertEquals(fixture.civ.getHappiness(), standing.happiness.ourValue)
    }

    @Test
    fun `known major civs are included in ranking cohort but city-states are excluded`() {
        val fixture = LlmGatewayTestFixtures.twoHumanCivGame()
        ObservationV4TestHelpers.meet(fixture.firstCiv, fixture.secondCiv)

        val standing = LlmActionCollector().buildObservation(fixture.gameInfo, fixture.firstCiv).diplomacy.relativeStanding

        assertEquals(2, standing.score.totalKnownMajors)
    }
}
