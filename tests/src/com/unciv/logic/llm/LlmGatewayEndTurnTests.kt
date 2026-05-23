package com.unciv.logic.llm

import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class LlmGatewayEndTurnTests {

    @Test
    fun `scripted EndTurn advances to the next human civilization`() {
        val fixture = LlmGatewayTestFixtures.twoHumanCivGame()
        val gateway = LlmGateway(ScriptedPolicy(listOf(LlmLegalAction.EndTurn())))

        val result = gateway.step(fixture.gameInfo)

        assertEquals(fixture.secondCiv, fixture.gameInfo.currentPlayerCiv)
        assertEquals(fixture.secondCiv.civID, fixture.gameInfo.currentPlayer)
        assertEquals(1, fixture.gameInfo.turns)
        assertTrue(result.appliedActions.single() is LlmLegalAction.EndTurn)
        assertTrue(result.rejectedActions.isEmpty())
    }

    @Test
    fun `online multiplayer game rejects selected actions by default`() {
        val fixture = LlmGatewayTestFixtures.twoHumanCivGame()
        fixture.gameInfo.gameParameters.isOnlineMultiplayer = true
        val initialPlayer = fixture.gameInfo.currentPlayer
        val gateway = LlmGateway(ScriptedPolicy(listOf(LlmLegalAction.EndTurn())))

        val result = gateway.step(fixture.gameInfo)

        assertTrue(result.appliedActions.isEmpty())
        assertEquals(LlmLegalAction.EndTurn(), result.rejectedActions.single().action)
        assertTrue(result.rejectedActions.single().reason.isNotBlank())
        assertEquals(initialPlayer, fixture.gameInfo.currentPlayer)
    }
}
