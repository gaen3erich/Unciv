package com.unciv.logic.llm

import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class RandomPolicySmokeTests {

    @Test
    fun `RandomPolicy only returns actions present in observation legalActions`() {
        val observation = observationWithLegalActions(
            LlmLegalAction.EndTurn(),
            LlmLegalAction.ChooseTechnology(
                id = LlmActionCollector.chooseTechnologyId("Pottery"),
                techName = "Pottery"
            )
        )
        val policy = RandomPolicy(seed = 7L)

        repeat(20) {
            val choices = policy.chooseActions(observation)
            for (choice in choices) {
                assertTrue(observation.legalActions.any { it.id == choice.id })
            }
        }
    }

    @Test
    fun `RandomPolicy with same seed is reproducible`() {
        val observation = observationWithLegalActions(
            LlmLegalAction.EndTurn(),
            LlmLegalAction.ChooseTechnology(
                id = LlmActionCollector.chooseTechnologyId("Pottery"),
                techName = "Pottery"
            )
        )

        val first = RandomPolicy(seed = 99L).chooseActions(observation)
        val second = RandomPolicy(seed = 99L).chooseActions(observation)

        assertEquals(first, second)
    }

    @Test
    fun `random policy over several gateway steps applies only actions legal at observation time`() {
        val fixture = LlmGatewayTestFixtures.gameWithFoundedCity()
        val gateway = LlmGateway(RandomPolicy(seed = 42L))
        val allApplied = ArrayList<LlmLegalAction>()

        repeat(5) {
            val result = gateway.step(fixture.gameInfo)
            for (action in result.appliedActions) {
                assertTrue(
                    "Applied action $action was not in legalActions at step $it",
                    result.observationLegalActionIds.contains(action.id)
                )
            }
            allApplied += result.appliedActions
        }

        assertTrue("RandomPolicy produced zero applied actions in 5 steps", allApplied.isNotEmpty())
    }

    private fun observationWithLegalActions(vararg actions: LlmLegalAction): LlmObservation =
        observationWithLegalActionsForTests(*actions)

    companion object {
        fun observationWithLegalActionsForTests(vararg actions: LlmLegalAction): LlmObservation {
            return LlmObservation(
                gameId = "test",
                turn = 1,
                currentPlayerCivId = "civ",
                currentPlayerCivName = "Civ",
                isOnlineMultiplayer = false,
                currentResearch = null,
                sciencePerTurn = 0,
                cities = emptyList(),
                researchedTechs = emptyList(),
                legalActions = actions.toList(),
                economy = LlmEconomy(0, 0, 0, 0, 0, 0, 0, 0, 0),
                resources = LlmResources(emptyList(), emptyList()),
                diplomacy = emptyDiplomacy()
            )
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
}
