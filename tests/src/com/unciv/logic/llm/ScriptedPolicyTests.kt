package com.unciv.logic.llm

import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class ScriptedPolicyTests {

    @Test
    fun `ScriptedPolicy returns scripted actions one per call in order`() {
        val firstAction = LlmLegalAction.EndTurn(id = "et1")
        val secondAction = LlmLegalAction.EndTurn(id = "et2")
        val policy = ScriptedPolicy(listOf(firstAction, secondAction))
        val observation = LlmObservation(
            gameId = "test",
            turn = 1,
            currentPlayerCivId = "civ",
            currentPlayerCivName = "Civ",
            isOnlineMultiplayer = false,
            currentResearch = null,
            sciencePerTurn = 0,
            cities = emptyList(),
            researchedTechs = emptyList(),
            legalActions = listOf(firstAction, secondAction),
            economy = LlmEconomy(0, 0, 0, 0, 0, 0, 0, 0, 0),
            resources = LlmResources(emptyList(), emptyList()),
            diplomacy = emptyDiplomacy()
        )

        assertEquals(listOf(firstAction), policy.chooseActions(observation))
        assertEquals(listOf(secondAction), policy.chooseActions(observation))
        assertTrue(policy.chooseActions(observation).isEmpty())
    }

    private fun emptyDiplomacy(): LlmDiplomacy {
        val zero = LlmRankingEntry(ourValue = 0, rank = 1, totalKnownMajors = 1, leaderValue = 0)
        return LlmDiplomacy(
            knownCivs = emptyList(),
            activeWars = emptyList(),
            activeTrades = emptyList(),
            threats = LlmThreats(0, 0, 0, 0, 0),
            relativeStanding = LlmRelativeStanding(zero, zero, zero, zero, zero, zero, zero, zero, zero, zero)
        )
    }
}
