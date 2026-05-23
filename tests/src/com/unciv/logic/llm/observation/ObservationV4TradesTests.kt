package com.unciv.logic.llm.observation

import com.unciv.logic.llm.LlmActionCollector
import com.unciv.logic.llm.LlmGatewayTestFixtures
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class ObservationV4TradesTests {

    @Test
    fun `active trades aggregate by partner and sort offers`() {
        val fixture = LlmGatewayTestFixtures.twoHumanCivGame()
        val (ourDiplo, _) = ObservationV4TestHelpers.meet(fixture.firstCiv, fixture.secondCiv)
        ourDiplo.trades += Trade().apply {
            ourOffers += TradeOffer("Iron", TradeOfferType.Strategic_Resource, amount = 3, duration = 30)
            theirOffers += TradeOffer("Gold", TradeOfferType.Gold, amount = 120, duration = -1)
        }
        ourDiplo.trades += Trade().apply {
            ourOffers += TradeOffer("Open Borders", TradeOfferType.Agreement, amount = 1, duration = 20)
        }

        val trade = LlmActionCollector().buildObservation(fixture.gameInfo, fixture.firstCiv).diplomacy.activeTrades.single()

        assertEquals(fixture.secondCiv.civName, trade.partnerCivName)
        assertEquals(listOf("Agreement", "Strategic_Resource"), trade.outgoing.map { it.type })
        assertEquals(20, trade.turnsRemainingMin)
        assertEquals(120, trade.incoming.single().amount)
    }

    @Test
    fun `immediate only trade has null turns remaining`() {
        val fixture = LlmGatewayTestFixtures.twoHumanCivGame()
        val (ourDiplo, _) = ObservationV4TestHelpers.meet(fixture.firstCiv, fixture.secondCiv)
        ourDiplo.trades += Trade().apply {
            theirOffers += TradeOffer("Gold", TradeOfferType.Gold, amount = 120, duration = -1)
        }

        val trade = LlmActionCollector().buildObservation(fixture.gameInfo, fixture.firstCiv).diplomacy.activeTrades.single()

        assertNull(trade.turnsRemainingMin)
    }
}
