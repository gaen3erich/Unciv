package com.unciv.logic.llm.observation

import com.unciv.logic.llm.LlmActionCollector
import com.unciv.logic.llm.LlmGatewayTestFixtures
import com.unciv.logic.llm.io.LlmJsonCodec
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class ObservationV3SizeBudgetTests {

    @Test
    fun `small current observation stays under eight kilobytes`() {
        val observation = LlmActionCollector().buildObservation(LlmGatewayTestFixtures.twoHumanCivGame().gameInfo)
        val encoded = LlmJsonCodec().encodeObservation(observation)

        assertTrue("Encoded observation was ${encoded.length} bytes", encoded.length < 8 * 1024)
    }

    @Test
    fun `single city observation stays under two kilobytes`() {
        val observation = LlmActionCollector().buildObservation(LlmGatewayTestFixtures.gameWithFoundedCity().gameInfo)
        val encodedCity = LlmJsonCodec().encodeObservation(observation)
            .substringAfter("\"cities\":[")
            .substringBefore("], \"researchedTechs\"")

        assertTrue("Encoded city was ${encodedCity.length} bytes", encodedCity.length < 2 * 1024)
    }
}
