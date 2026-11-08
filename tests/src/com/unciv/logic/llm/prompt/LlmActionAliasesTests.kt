package com.unciv.logic.llm.prompt

import com.unciv.logic.llm.LlmActionCollector
import com.unciv.logic.llm.LlmGatewayTestFixtures
import com.unciv.logic.llm.LlmHexCoord
import com.unciv.logic.llm.LlmLegalAction
import com.unciv.logic.llm.RandomPolicySmokeTests
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class LlmActionAliasesTests {
    @Test
    fun `every alias and every full id resolves to its action`() {
        val observation = LlmActionCollector().buildObservation(LlmGatewayTestFixtures.gameWithFoundedCity().gameInfo)
        val aliases = LlmActionAliases.of(observation)

        for (action in observation.legalActions) {
            assertEquals(action, aliases.resolve(aliases.aliasOf(action)))
            assertEquals(action, aliases.resolve(action.id))
        }
    }

    @Test
    fun `aliases use names instead of ids`() {
        val build = LlmLegalAction.ChooseCityConstruction("choose_city_construction:uuid-1:Monument", "uuid-1", "Rome", "Monument")
        val war = LlmLegalAction.DeclareWar("declare_war:Egypt", "Egypt", "Egypt")
        val found = LlmLegalAction.FoundCity("found_city:7:3,-2", 7, "Settler", LlmHexCoord(3, -2))
        val aliases = LlmActionAliases.of(RandomPolicySmokeTests.observationWithLegalActionsForTests(build, war, found))

        assertEquals("build:Rome:Monument", aliases.aliasOf(build))
        assertEquals("war:Egypt", aliases.aliasOf(war))
        assertEquals("found:7@3,-2", aliases.aliasOf(found))
        assertNull(aliases.resolve("build:Rome:Palace"))
    }

    @Test
    fun `ambiguous aliases fall back to full ids`() {
        val first = LlmLegalAction.AttackCity("attack_city:a", "Egypt", "a", "Thebes", LlmHexCoord(0, 0))
        val second = LlmLegalAction.AttackCity("attack_city:b", "Greece", "b", "Thebes", LlmHexCoord(1, 0))
        val aliases = LlmActionAliases.of(RandomPolicySmokeTests.observationWithLegalActionsForTests(first, second))

        assertEquals(first.id, aliases.aliasOf(first))
        assertEquals(second, aliases.resolve(second.id))
        assertNull(aliases.resolve("attack:Thebes"))
    }
}
