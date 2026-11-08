package com.unciv.logic.llm.observation

import com.unciv.logic.llm.LlmActionCollector
import com.unciv.logic.llm.LlmGatewayTestFixtures
import com.unciv.logic.llm.io.LlmJsonCodec
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class ObservationV4SizeBudgetTests {

    @Test
    fun `small v4 observation stays under forty kilobytes`() {
        val observation = LlmActionCollector().buildObservation(LlmGatewayTestFixtures.twoHumanCivGame().gameInfo)
        val encoded = LlmJsonCodec().encodeObservation(observation)

        assertTrue("Encoded observation was ${encoded.length} bytes", encoded.length < 40 * 1024)
    }
}
