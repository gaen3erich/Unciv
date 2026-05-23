package com.unciv.logic.llm.runner

import com.unciv.logic.CompatibilityVersion
import com.unciv.logic.GameInfo
import com.unciv.logic.llm.LlmGateway
import com.unciv.logic.llm.LlmLegalAction
import com.unciv.logic.llm.LlmPolicy
import com.unciv.logic.llm.io.LlmTraceHeader
import com.unciv.logic.llm.io.LlmTracePolicy
import com.unciv.logic.llm.io.LlmTraceRecord
import com.unciv.logic.llm.io.LlmTraceWriter
import com.unciv.logic.llm.policy.LlmTraceablePolicy
import java.time.Clock
import java.time.Instant

class LlmGatewayRunner(
    private val gameInfo: GameInfo,
    private val policy: LlmPolicy,
    private val traceWriter: LlmTraceWriter,
    private val config: LlmRunnerConfig,
    private val clock: Clock = Clock.systemUTC()
) {
    fun run(): LlmRunnerSummary {
        var stepsRun = 0
        var appliedCount = 0
        var rejectedCount = 0
        var emptyDecisionStreak = 0
        var stoppedReason = StoppedReason.COMPLETED

        traceWriter.writeHeader(
            LlmTraceHeader(
                createdAt = now(),
                uncivVersion = config.uncivVersion,
                compatibilityVersion = CompatibilityVersion.CURRENT_COMPATIBILITY_NUMBER,
                saveFile = config.saveFile?.path ?: "",
                policy = LlmTracePolicy(config.policy.name, if (config.policy == PolicyKind.RANDOM) config.seed else null),
                allowOnlineMultiplayer = config.allowOnlineMultiplayer,
                agentConfig = config.agentConfig
            )
        )

        try {
            val gateway = LlmGateway(policy, allowOnlineMultiplayer = config.allowOnlineMultiplayer)
            var step = 0
            while (step < config.steps && stoppedReason == StoppedReason.COMPLETED) {
                val civBeforeStep = gameInfo.getCurrentPlayerCivilization()
                if (civBeforeStep.isDefeated()) {
                    stoppedReason = StoppedReason.CIV_DEFEATED
                    break
                }

                val result = gateway.step(gameInfo, civBeforeStep)
                traceWriter.writeStep(LlmTraceRecord(step, now(), result, llmCall = (policy as? LlmTraceablePolicy)?.consumeLastCallTrace()))
                stepsRun++
                appliedCount += result.appliedActions.size
                rejectedCount += result.rejectedActions.size

                val selectedEndTurn = result.selectedActions.any { it is LlmLegalAction.EndTurn }
                emptyDecisionStreak = if (result.appliedActions.isEmpty() && !selectedEndTurn) emptyDecisionStreak + 1 else 0
                if (emptyDecisionStreak >= 2) {
                    stoppedReason = StoppedReason.DEGENERATE_POLICY
                    break
                }
                if (gameInfo.getCurrentPlayerCivilization().isDefeated()) {
                    stoppedReason = StoppedReason.CIV_DEFEATED
                    break
                }
                step++
            }
        } catch (ex: Exception) {
            stoppedReason = StoppedReason.ERROR
            throw ex
        } finally {
            traceWriter.close()
        }

        return LlmRunnerSummary(
            stepsRun = stepsRun,
            appliedCount = appliedCount,
            rejectedCount = rejectedCount,
            finalTurn = gameInfo.turns,
            outputPath = config.outputFile?.path,
            stoppedReason = stoppedReason
        )
    }

    private fun now(): String = Instant.now(clock).toString()
}
