package com.unciv.logic.llm.observation

import com.unciv.logic.llm.LlmActionCollector
import com.unciv.logic.llm.LlmGatewayTestFixtures
import com.unciv.logic.llm.io.LlmJsonCodec
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class ObservationV2ResourcesTests {

    @Test
    fun `resources include strategic and luxury but skip bonus`() {
        val fixture = LlmGatewayTestFixtures.gameWithFoundedCity()
        val city = fixture.civ.cities.single()
        val strategic = resourceName(fixture, ResourceType.Strategic)
        val luxury = resourceName(fixture, ResourceType.Luxury)
        val bonus = resourceName(fixture, ResourceType.Bonus)
        city.cityConstructions.addBuilding(fixture.testGame.createBuilding("Provides [3] [$strategic]"))
        city.cityConstructions.addBuilding(fixture.testGame.createBuilding("Provides [2] [$luxury]"))
        city.cityConstructions.addBuilding(fixture.testGame.createBuilding("Provides [5] [$bonus]"))

        val resources = LlmActionCollector().buildObservation(fixture.gameInfo).resources

        assertEquals(listOf(strategic), resources.strategic.map { it.name })
        assertEquals(3, resources.strategic.single().amount)
        assertEquals(listOf(luxury), resources.luxury.map { it.name })
        assertEquals(2, resources.luxury.single().amount)
        assertFalse((resources.strategic + resources.luxury).any { it.name == bonus })
    }

    @Test
    fun `resources include negative amounts and skip zero amounts`() {
        val fixture = LlmGatewayTestFixtures.gameWithFoundedCity()
        val city = fixture.civ.cities.single()
        val strategic = resourceName(fixture, ResourceType.Strategic)
        city.cityConstructions.addBuilding(fixture.testGame.createBuilding("Consumes [1] [$strategic]"))

        val resources = LlmActionCollector().buildObservation(fixture.gameInfo).resources

        assertEquals(strategic, resources.strategic.single().name)
        assertEquals(-1, resources.strategic.single().amount)
        assertFalse(resources.luxury.any { it.amount == 0 })
    }

    @Test
    fun `resources are sorted by name and json is deterministic`() {
        val fixture = LlmGatewayTestFixtures.gameWithFoundedCity()
        val city = fixture.civ.cities.single()
        val strategicNames = fixture.gameInfo.ruleset.tileResources.values
            .filter { it.resourceType == ResourceType.Strategic }
            .map { it.name }
            .sorted()
            .take(2)
        for (name in strategicNames.reversed()) {
            city.cityConstructions.addBuilding(fixture.testGame.createBuilding("Provides [1] [$name]"))
        }

        val collector = LlmActionCollector()
        val codec = LlmJsonCodec()
        val first = collector.buildObservation(fixture.gameInfo)
        val second = collector.buildObservation(fixture.gameInfo)

        assertEquals(strategicNames, first.resources.strategic.map { it.name })
        assertEquals(codec.encodeObservation(first), codec.encodeObservation(second))
    }

    private fun resourceName(fixture: LlmGatewayTestFixtures.Fixture, type: ResourceType): String =
        fixture.gameInfo.ruleset.tileResources.values.first { it.resourceType == type }.name
}
