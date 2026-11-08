package com.unciv.logic.llm.observation

import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.llm.LlmActionCollector
import com.unciv.logic.llm.LlmGatewayTestFixtures
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class ObservationV4ThreatsTests {

    @Test
    fun `threat counters cross-check against observation data`() {
        val fixture = LlmGatewayTestFixtures.twoHumanCivGame()
        ObservationV4TestHelpers.meet(fixture.firstCiv, fixture.secondCiv, DiplomaticStatus.War)

        val observation = LlmActionCollector().buildObservation(fixture.gameInfo, fixture.firstCiv)

        assertEquals(observation.diplomacy.activeWars.size, observation.diplomacy.threats.warCount)
        assertEquals(
            observation.cities.sumOf { it.visibleEnemies.totalCount },
            observation.diplomacy.threats.nearbyHostileMilitaryCount
        )
    }

    @Test
    fun `denunciation count counts partner once even when both sides denounced`() {
        val fixture = LlmGatewayTestFixtures.twoHumanCivGame()
        val (ourDiplo, theirDiplo) = ObservationV4TestHelpers.meet(fixture.firstCiv, fixture.secondCiv)
        ourDiplo.setFlag(DiplomacyFlags.Denunciation, 30)
        theirDiplo.setFlag(DiplomacyFlags.Denunciation, 30)

        val threats = LlmActionCollector().buildObservation(fixture.gameInfo, fixture.firstCiv).diplomacy.threats

        assertEquals(1, threats.denunciationCount)
    }

    @Test
    fun `recent declarations against us use reverse diplomacy flag`() {
        val fixture = LlmGatewayTestFixtures.twoHumanCivGame()
        val (_, theirDiplo) = ObservationV4TestHelpers.meet(fixture.firstCiv, fixture.secondCiv, DiplomaticStatus.War)
        theirDiplo.setFlag(DiplomacyFlags.DeclaredWar, 10)

        val threats = LlmActionCollector().buildObservation(fixture.gameInfo, fixture.firstCiv).diplomacy.threats

        assertEquals(1, threats.recentDeclarationsAgainstUs)
    }
}
