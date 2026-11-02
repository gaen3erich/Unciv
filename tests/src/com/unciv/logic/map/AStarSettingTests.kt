package com.unciv.logic.map

import com.unciv.UncivGame
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The experimental A* setting is local to one client, so it must never change what AI civs do. */
@RunWith(GdxTestRunner::class)
class AStarSettingTests {
    private val game = TestGame()

    @After
    fun resetSetting() {
        UncivGame.Current.settings.useAStarPathfinding = false
    }

    @Test
    fun aiCivIgnoresLocalAStarSetting() {
        game.makeHexagonalMap(3)
        val ai = game.addCiv()
        val human = game.addCiv(isPlayer = true)
        UncivGame.Current.settings.useAStarPathfinding = true

        assertFalse(MapPathing.useAStarPathfinding(ai))
        assertTrue(MapPathing.useAStarPathfinding(human))
    }

    @Test
    fun nobodyUsesAStarWhenSettingIsOff() {
        game.makeHexagonalMap(3)
        val human = game.addCiv(isPlayer = true)
        UncivGame.Current.settings.useAStarPathfinding = false

        assertFalse(MapPathing.useAStarPathfinding(human))
    }
}
