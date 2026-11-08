package com.unciv.logic.llm.serialization

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.Constants
import com.unciv.logic.GameInfo
import com.unciv.logic.GameStarter
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.llm.LlmActionCollector
import com.unciv.logic.llm.LlmGateway
import com.unciv.logic.llm.LlmLegalAction
import com.unciv.logic.llm.ScriptedPolicy
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapSize
import com.unciv.models.metadata.GameParameters
import com.unciv.models.metadata.GameSettings
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class GatewayRoundTripTests {

    @Test
    fun `gateway can end turn on a deserialized game`() {
        val restored = roundTripGameWithFoundedCity()
        val gateway = LlmGateway(ScriptedPolicy(listOf(LlmLegalAction.EndTurn())))

        val result = gateway.step(restored)

        assertTrue(result.appliedActions.any { it is LlmLegalAction.EndTurn })
    }

    @Test
    fun `gateway can choose technology on a deserialized game`() {
        val restored = roundTripGameWithFoundedCity()
        val civ = restored.getCurrentPlayerCivilization()
        val targetTech = "Pottery"
        check(civ.tech.canBeResearched(targetTech)) {
            "Test fixture assumption broken: $targetTech not researchable after round trip"
        }
        val action = LlmLegalAction.ChooseTechnology(
            id = LlmActionCollector.chooseTechnologyId(targetTech),
            techName = targetTech
        )
        val gateway = LlmGateway(ScriptedPolicy(listOf(action)))

        val result = gateway.step(restored)

        assertTrue(result.appliedActions.any { it is LlmLegalAction.ChooseTechnology })
        assertTrue(result.rejectedActions.isEmpty())
        assertTrue(civ.tech.techsToResearch.firstOrNull() == targetTech || civ.tech.isResearched(targetTech))
    }

    @Test
    fun `gateway can choose city construction on a deserialized game`() {
        val restored = roundTripGameWithFoundedCity()
        val city = restored.getCurrentPlayerCivilization().cities.single()
        val civ = restored.getCurrentPlayerCivilization()
        val action = LlmLegalAction.ChooseCityConstruction(
            id = LlmActionCollector.chooseCityConstructionId(LlmActionCollector.cityIdFor(civ, city), "Monument"),
            cityId = LlmActionCollector.cityIdFor(civ, city),
            cityName = city.name,
            constructionName = "Monument"
        )
        val gateway = LlmGateway(ScriptedPolicy(listOf(action)))

        val result = gateway.step(restored)

        assertTrue(result.appliedActions.any { it is LlmLegalAction.ChooseCityConstruction })
        assertTrue(result.rejectedActions.isEmpty())
        assertEquals(listOf("Monument"), city.cityConstructions.constructionQueue)
    }

    private fun roundTripGameWithFoundedCity(): GameInfo {
        val gameInfo = startSerializableGame()
        val civ = gameInfo.getCurrentPlayerCivilization()
        val settler = civ.units.getCivUnits().first { it.hasUnique(UniqueType.FoundCity) }
        val tile = settler.getTile()
        civ.addCity(tile.position)
        if (tile.ruleset.tileImprovements.containsKey(Constants.cityCenter))
            tile.setImprovement(Constants.cityCenter)
        settler.destroy()
        gameInfo.turns = 1

        val serialized = UncivFiles.gameInfoToString(gameInfo)
        return UncivFiles.gameInfoFromString(serialized).apply {
            turns = 1
        }
    }

    private fun startSerializableGame(): GameInfo {
        RulesetCache.loadRulesets(noMods = true)
        UncivGame.Current = UncivGame()
        UncivGame.Current.files = UncivFiles(Gdx.files)
        UncivGame.Current.settings = GameSettings()
        val parameters = GameParameters().apply {
            numberOfCityStates = 0
            players.clear()
            players.add(Player("Rome", PlayerType.Human))
            players.add(Player("Greece"))
        }
        val setup = GameSetupInfo(
            parameters,
            MapParameters().apply {
                mapSize = MapSize.Tiny
                seed = 42L
            }
        )
        return GameStarter.startNewGame(setup)
    }
}
