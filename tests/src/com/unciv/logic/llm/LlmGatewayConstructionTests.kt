package com.unciv.logic.llm

import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class LlmGatewayConstructionTests {

    @Test
    fun `scripted ChooseCityConstruction adds construction to city queue and does not advance turn`() {
        val fixture = LlmGatewayTestFixtures.gameWithFoundedCity()
        val city = fixture.civ.cities.single()
        val initialTurn = fixture.gameInfo.turns
        val action = LlmGatewayTestFixtures.legalActionById(
            fixture.gameInfo,
            LlmActionCollector.chooseCityConstructionId(LlmActionCollector.cityIdFor(fixture.civ, city), "Monument")
        )
        val gateway = LlmGateway(ScriptedPolicy(listOf(action)))

        val result = gateway.step(fixture.gameInfo)

        assertEquals(listOf("Monument"), city.cityConstructions.constructionQueue)
        assertTrue(city.cityConstructions.currentConstructionIsUserSet)
        assertTrue(result.appliedActions.any { it is LlmLegalAction.ChooseCityConstruction })
        assertTrue(result.rejectedActions.isEmpty())
        assertEquals(initialTurn, fixture.gameInfo.turns)
    }

    @Test
    fun `construction targeting missing city is rejected and does not mutate existing city queue`() {
        val fixture = LlmGatewayTestFixtures.gameWithFoundedCity()
        val city = fixture.civ.cities.single()
        val initialQueue = city.cityConstructions.constructionQueue.toList()
        val action = LlmLegalAction.ChooseCityConstruction(
            id = "choose_city_construction:Missing:Monument",
            cityId = "Missing",
            cityName = "Missing",
            constructionName = "Monument"
        )
        val gateway = LlmGateway(ScriptedPolicy(listOf(action)))

        val result = gateway.step(fixture.gameInfo)

        assertTrue(result.appliedActions.isEmpty())
        assertEquals(action, result.rejectedActions.single().action)
        assertTrue(result.rejectedActions.single().reason.isNotBlank())
        assertEquals(initialQueue, city.cityConstructions.constructionQueue)
    }

    @Test
    fun `construction already in city queue is rejected and queue is unchanged`() {
        val fixture = LlmGatewayTestFixtures.gameWithFoundedCity()
        val city = fixture.civ.cities.single()
        city.cityConstructions.addToQueue("Monument")
        val initialQueue = city.cityConstructions.constructionQueue.toList()
        val action = LlmLegalAction.ChooseCityConstruction(
            id = LlmActionCollector.chooseCityConstructionId(LlmActionCollector.cityIdFor(fixture.civ, city), "Monument"),
            cityId = LlmActionCollector.cityIdFor(fixture.civ, city),
            cityName = city.name,
            constructionName = "Monument"
        )
        val gateway = LlmGateway(ScriptedPolicy(listOf(action)))

        val result = gateway.step(fixture.gameInfo)

        assertTrue(result.appliedActions.isEmpty())
        assertEquals(action, result.rejectedActions.single().action)
        assertTrue(result.rejectedActions.single().reason.isNotBlank())
        assertEquals(initialQueue, city.cityConstructions.constructionQueue)
    }
}
