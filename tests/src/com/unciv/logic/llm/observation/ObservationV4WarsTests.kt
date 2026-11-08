package com.unciv.logic.llm.observation

import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.llm.LlmActionCollector
import com.unciv.logic.llm.LlmGatewayTestFixtures
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class ObservationV4WarsTests {

    @Test
    fun `active wars contain only wars involving observing civ`() {
        val fixture = LlmGatewayTestFixtures.twoHumanCivGame()
        val (ourDiplo, _) = ObservationV4TestHelpers.meet(fixture.firstCiv, fixture.secondCiv, DiplomaticStatus.War)
        ourDiplo.setFlag(DiplomacyFlags.DeclaredWar, 10)

        val wars = LlmActionCollector().buildObservation(fixture.gameInfo, fixture.firstCiv).diplomacy.activeWars

        assertEquals(fixture.secondCiv.civName, wars.single().enemyCivName)
        assertTrue(wars.single().warDeclaredByUs)
    }

    @Test
    fun `canMakePeace is false while declined peace flag is active`() {
        val fixture = LlmGatewayTestFixtures.twoHumanCivGame()
        val (ourDiplo, _) = ObservationV4TestHelpers.meet(fixture.firstCiv, fixture.secondCiv, DiplomaticStatus.War)
        ourDiplo.setFlag(DiplomacyFlags.DeclinedPeace, 10)

        val war = LlmActionCollector().buildObservation(fixture.gameInfo, fixture.firstCiv).diplomacy.activeWars.single()

        assertFalse(war.canMakePeace)
    }
}
