package com.unciv.logic.llm

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.testing.TestGame

object LlmGatewayTestFixtures {
    data class Fixture(
        val testGame: TestGame,
        val gameInfo: GameInfo,
        val civ: Civilization
    )

    data class TwoCivFixture(
        val testGame: TestGame,
        val gameInfo: GameInfo,
        val firstCiv: Civilization,
        val secondCiv: Civilization
    )

    fun smallGame(): Fixture {
        val testGame = TestGame()
        testGame.makeHexagonalMap(2)
        val civ = testGame.addCiv(isPlayer = true)
        testGame.gameInfo.currentPlayerCiv = civ
        testGame.gameInfo.currentPlayer = civ.civID
        testGame.gameInfo.turns = 1
        return Fixture(testGame, testGame.gameInfo, civ)
    }

    fun gameWithFoundedCity(): Fixture {
        val fixture = smallGame()
        fixture.testGame.addCity(fixture.civ, fixture.testGame.getTile(HexCoord.Zero))
        return fixture
    }

    fun twoHumanCivGame(): TwoCivFixture {
        val testGame = TestGame()
        testGame.makeHexagonalMap(3)
        val firstCiv = testGame.addCiv(isPlayer = true)
        val secondCiv = testGame.addCiv(isPlayer = true)
        testGame.addCity(firstCiv, testGame.getTile(HexCoord.Zero))
        testGame.addCity(secondCiv, testGame.getTile(HexCoord(2, 0)))
        testGame.gameInfo.currentPlayerCiv = firstCiv
        testGame.gameInfo.currentPlayer = firstCiv.civID
        testGame.gameInfo.turns = 1
        return TwoCivFixture(testGame, testGame.gameInfo, firstCiv, secondCiv)
    }

    fun legalActionById(gameInfo: GameInfo, id: String): LlmLegalAction {
        return LlmActionCollector().buildObservation(gameInfo).legalActions.single { it.id == id }
    }
}
