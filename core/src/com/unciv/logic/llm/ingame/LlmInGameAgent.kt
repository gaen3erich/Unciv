package com.unciv.logic.llm.ingame

import com.unciv.logic.automation.civilization.NextTurnAutomation
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.llm.LlmActionApplier
import com.unciv.logic.llm.LlmGateway
import com.unciv.logic.llm.LlmLegalAction
import com.unciv.logic.llm.LlmPolicy
import com.unciv.logic.llm.io.LlmTraceRecord
import com.unciv.logic.llm.io.LlmTraceWriter
import com.unciv.logic.llm.policy.LlmTraceablePolicy
import com.unciv.logic.llm.prompt.LlmActionAliases
import java.time.Instant

/**
 * Lets the LLM agent play AI civilizations inside a normal game, with the usual UI.
 *
 * A platform launcher installs a [controller] at startup (the desktop one does that from
 * `--llm-config` / `UNCIV_LLM_CONFIG`). The turn loop then asks the controller for every AI civ
 * and lets it play the civs it [controls][LlmCivTurnController.controls] instead of the built-in AI.
 */
object LlmInGameAgent {
    @Volatile
    var controller: LlmCivTurnController? = null
}

/**
 * Plays the turn of one civ: asks the policy up to [maxCallsPerTurn] times, applying the chosen
 * actions, until it picks end_turn; then runs the AI housekeeping the agent has no actions for.
 *
 * @param civNames civs to control, by name as in the ruleset (e.g. "Rome"); empty means the first
 *   AI major civ of the game.
 */
class LlmCivTurnController(
    private val policy: LlmPolicy,
    private val civNames: Set<String> = emptySet(),
    private val maxCallsPerTurn: Int = 3,
    private val traceWriter: LlmTraceWriter? = null,
    private val log: (String) -> Unit = { println(it) }
) {
    private var step = 0

    fun controls(civ: Civilization): Boolean {
        if (!civ.isMajorCiv() || !civ.isAI() || civ.isDefeated()) return false
        if (civNames.isNotEmpty()) return civNames.any { it.equals(civ.civName, ignoreCase = true) }
        return civ == civ.gameInfo.civilizations.firstOrNull { it.isMajorCiv() && it.isAI() && !it.isDefeated() }
    }

    fun playTurn(civ: Civilization) {
        val gameInfo = civ.gameInfo
        // end_turn only ends the agent's loop here: GameInfo.nextTurn moves on to the next civ itself
        val gateway = LlmGateway(policy, applier = LlmActionApplier(endTurn = {}))
        try {
            for (call in 1..maxCallsPerTurn) {
                val result = gateway.step(gameInfo, civ)
                val llmCall = (policy as? LlmTraceablePolicy)?.consumeLastCallTrace()
                traceWriter?.writeStep(LlmTraceRecord(step++, Instant.now().toString(), result, llmCall))

                val aliases = LlmActionAliases.of(result.observation)
                val chosen = result.selectedActions.joinToString(", ") { aliases.aliasOf(it) }.ifEmpty { "-" }
                val line = StringBuilder("[LLM ${civ.civName} T${gameInfo.turns} #$call] $chosen")
                if (llmCall != null) line.append("  (${llmCall.latencyMs} ms, in ${llmCall.inputTokens} / out ${llmCall.outputTokens} tok)")
                if (llmCall?.fallbackUsed != null) line.append("  FALLBACK: ${llmCall.fallbackUsed} / ${llmCall.stopReason}")
                if (llmCall != null && llmCall.retryCount > 0) line.append("\n    retried: ${llmCall.firstAttemptRejection}")
                if (llmCall != null && llmCall.droppedActionIds.isNotEmpty()) line.append("\n    dropped: ${llmCall.droppedActionIds.joinToString()}")
                for (rejected in result.rejectedActions) line.append("\n    rejected ${aliases.aliasOf(rejected.action)}: ${rejected.reason}")
                llmCall?.rationale?.takeIf { it.isNotBlank() }?.let { line.append("\n    why: ").append(it.trim()) }
                llmCall?.notes?.takeIf { it.isNotBlank() }?.let { line.append("\n    notes: ").append(it.trim()) }
                log(line.toString())

                if (civ.isDefeated()) return
                if (result.selectedActions.any { it is LlmLegalAction.EndTurn }) break
                if (result.appliedActions.isEmpty()) break // nothing happened, asking again would give the same answer
            }
        } catch (ex: Exception) {
            log("[LLM ${civ.civName} T${gameInfo.turns}] error, the built-in AI plays this turn: $ex")
            NextTurnAutomation.automateCivMoves(civ)
            return
        }
        NextTurnAutomation.automateHousekeepingForExternalAgent(civ)
    }
}
