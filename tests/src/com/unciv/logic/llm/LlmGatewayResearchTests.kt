package com.unciv.logic.llm

import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class LlmGatewayResearchTests {

    @Test
    fun `scripted ChooseTechnology chooses research and does not advance turn`() {
        val fixture = LlmGatewayTestFixtures.smallGame()
        val initialTurn = fixture.gameInfo.turns
        val initialPlayer = fixture.gameInfo.currentPlayer
        val targetTech = "Pottery"
        check(fixture.civ.tech.canBeResearched(targetTech)) {
            "Test fixture assumption broken: $targetTech not researchable from start"
        }
        val action = LlmGatewayTestFixtures.legalActionById(
            fixture.gameInfo,
            LlmActionCollector.chooseTechnologyId(targetTech)
        )
        val gateway = LlmGateway(ScriptedPolicy(listOf(action)))

        val result = gateway.step(fixture.gameInfo)

        assertTrue(
            fixture.civ.tech.techsToResearch.firstOrNull() == targetTech || fixture.civ.tech.isResearched(targetTech)
        )
        assertTrue(result.appliedActions.any { it is LlmLegalAction.ChooseTechnology })
        assertTrue(result.rejectedActions.isEmpty())
        assertEquals(initialTurn, fixture.gameInfo.turns)
        assertEquals(initialPlayer, fixture.gameInfo.currentPlayer)
    }

    @Test
    fun `unknown technology is rejected and leaves current research unchanged`() {
        val fixture = LlmGatewayTestFixtures.smallGame()
        val initialResearch = fixture.civ.tech.techsToResearch.toList()
        val action = LlmLegalAction.ChooseTechnology(
            id = "choose_technology:DefinitelyNotARealTech",
            techName = "DefinitelyNotARealTech"
        )
        val gateway = LlmGateway(ScriptedPolicy(listOf(action)))

        val result = gateway.step(fixture.gameInfo)

        assertTrue(result.appliedActions.isEmpty())
        assertEquals(action, result.rejectedActions.single().action)
        assertTrue(result.rejectedActions.single().reason.isNotBlank())
        assertEquals(initialResearch, fixture.civ.tech.techsToResearch)
    }

    @Test
    fun `already researched technology is rejected and leaves current research unchanged`() {
        val fixture = LlmGatewayTestFixtures.smallGame()
        fixture.civ.tech.addTechnology("Pottery", showNotification = false)
        val initialResearch = fixture.civ.tech.techsToResearch.toList()
        val action = LlmLegalAction.ChooseTechnology(
            id = LlmActionCollector.chooseTechnologyId("Pottery"),
            techName = "Pottery"
        )
        val gateway = LlmGateway(ScriptedPolicy(listOf(action)))

        val result = gateway.step(fixture.gameInfo)

        assertTrue(result.appliedActions.isEmpty())
        assertEquals(action, result.rejectedActions.single().action)
        assertTrue(result.rejectedActions.single().reason.isNotBlank())
        assertEquals(initialResearch, fixture.civ.tech.techsToResearch)
    }
}
