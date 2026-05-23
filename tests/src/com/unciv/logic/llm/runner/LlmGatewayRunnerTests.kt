package com.unciv.logic.llm.runner

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.utils.JsonReader
import com.unciv.logic.llm.LlmLegalAction
import com.unciv.logic.llm.LlmPolicy
import com.unciv.logic.llm.LlmObservation
import com.unciv.logic.llm.RandomPolicy
import com.unciv.logic.llm.io.LlmTraceWriter
import com.unciv.logic.llm.LlmGatewayTestFixtures
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.StringWriter
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(GdxTestRunner::class)
class LlmGatewayRunnerTests {
    private val clock: Clock = Clock.fixed(Instant.parse("2026-05-22T00:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `end turn policy for three steps writes header and step records`() {
        val fixture = LlmGatewayTestFixtures.twoHumanCivGame()
        val output = StringWriter()
        val runner = LlmGatewayRunner(
            fixture.gameInfo,
            EndTurnOnlyPolicy,
            LlmTraceWriter(output),
            LlmRunnerConfig(policy = PolicyKind.END_TURN_ONLY, steps = 3),
            clock
        )

        val summary = runner.run()

        val lines = output.toString().lines().filter { it.isNotEmpty() }
        assertEquals(4, lines.size)
        assertEquals("header", JsonReader().parse(lines[0]).getString("type"))
        val stepTurns = lines.drop(1).map { JsonReader().parse(it).getInt("turn") }
        assertEquals(stepTurns.sorted(), stepTurns)
        for (line in lines.drop(1)) {
            val step = JsonReader().parse(line)
            assertEquals("EndTurn", step.get("appliedActions").get(0).getString("kind"))
        }
        assertEquals(3, summary.stepsRun)
        assertEquals(3, summary.appliedCount)
        assertEquals(0, summary.rejectedCount)
        assertEquals(StoppedReason.COMPLETED, summary.stoppedReason)
    }

    @Test
    fun `random policy trace is parseable and applied actions were legal at observation time`() {
        val fixture = LlmGatewayTestFixtures.gameWithFoundedCity()
        val output = StringWriter()
        val runner = LlmGatewayRunner(
            fixture.gameInfo,
            RandomPolicy(seed = 0L),
            LlmTraceWriter(output),
            LlmRunnerConfig(policy = PolicyKind.RANDOM, seed = 0L, steps = 5),
            clock
        )

        runner.run()

        val lines = output.toString().lines().filter { it.isNotEmpty() }
        assertTrue(lines.size >= 2)
        for (line in lines.drop(1)) {
            val step = JsonReader().parse(line)
            val legalIds = step.get("observation").get("legalActions").map { it.getString("id") }.toSet()
            for (appliedAction in step.get("appliedActions")) {
                assertTrue(legalIds.contains(appliedAction.getString("id")))
            }
        }
    }

    @Test
    fun `empty policy stops after two degenerate steps`() {
        val fixture = LlmGatewayTestFixtures.gameWithFoundedCity()
        val output = StringWriter()
        val runner = LlmGatewayRunner(
            fixture.gameInfo,
            EmptyPolicy,
            LlmTraceWriter(output),
            LlmRunnerConfig(steps = 10),
            clock
        )

        val summary = runner.run()

        assertEquals(2, summary.stepsRun)
        assertEquals(StoppedReason.DEGENERATE_POLICY, summary.stoppedReason)
        assertEquals(3, output.toString().lines().filter { it.isNotEmpty() }.size)
    }

    @Test
    fun `end turn policy does not touch music controller when Gdx app is null`() {
        val oldApp = Gdx.app
        try {
            Gdx.app = null
            val fixture = LlmGatewayTestFixtures.twoHumanCivGame()
            fixture.gameInfo.turns = 9
            val runner = LlmGatewayRunner(
                fixture.gameInfo,
                EndTurnOnlyPolicy,
                LlmTraceWriter(StringWriter()),
                LlmRunnerConfig(policy = PolicyKind.END_TURN_ONLY, steps = 2),
                clock
            )

            val summary = runner.run()

            assertEquals(StoppedReason.COMPLETED, summary.stoppedReason)
        } finally {
            Gdx.app = oldApp
        }
    }

    private object EndTurnOnlyPolicy : LlmPolicy {
        override fun chooseActions(observation: LlmObservation): List<LlmLegalAction> =
            listOf(LlmLegalAction.EndTurn())
    }

    private object EmptyPolicy : LlmPolicy {
        override fun chooseActions(observation: LlmObservation): List<LlmLegalAction> = emptyList()
    }
}
