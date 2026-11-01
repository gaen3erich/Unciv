package com.unciv.logic.llm.prompt

import com.unciv.logic.llm.LlmObservation
import com.unciv.logic.llm.client.LlmMessage
import com.unciv.logic.llm.client.LlmRequest
import com.unciv.logic.llm.client.LlmRequestKind

/** What the agent carries over from its previous decision into the next prompt. */
data class LlmPromptMemory(
    val previousObservation: LlmObservation,
    val lastDecision: List<String>,
    val notes: String?
)

/**
 * Builds the per-step request.
 *
 * Layout, from most stable to least stable so the provider can cache the prefix:
 * tool schema and system prompt (identical for the whole game, marked cacheable),
 * then one user message with the notes, the changes since the previous decision,
 * the compact state and the grouped legal actions.
 */
class LlmPromptBuilder(
    private val maxTokens: Int = 4096,
    private val temperature: Double = 0.0,
    private val renderer: LlmCompactRenderer = LlmCompactRenderer()
) {
    fun build(
        observation: LlmObservation,
        civName: String = observation.currentPlayerCivName,
        memory: LlmPromptMemory? = null
    ): LlmRequest {
        return LlmRequest(
            kind = LlmRequestKind.DECISION,
            systemPrompt = systemPrompt,
            messages = listOf(LlmMessage(LlmMessage.Role.USER, buildUserPrompt(observation, civName, memory))),
            toolSchema = toolSchema,
            maxTokens = maxTokens,
            temperature = temperature,
            cacheSystemPrompt = true
        )
    }

    private fun buildUserPrompt(observation: LlmObservation, civName: String, memory: LlmPromptMemory?): String = buildString {
        appendLine("You are $civName. Turn ${observation.turn}.")

        if (memory != null) {
            val sameTurn = memory.previousObservation.turn == observation.turn
            appendLine()
            if (!memory.notes.isNullOrBlank()) appendLine("Your notes: ${memory.notes.trim()}")
            if (memory.lastDecision.isNotEmpty())
                appendLine("Your last decision (turn ${memory.previousObservation.turn}): ${memory.lastDecision.joinToString(", ")}")
            val changes = LlmTurnChanges.describe(memory.previousObservation, observation)
            appendLine((if (sameTurn) "Changes since your last decision this turn: " else "Changes since turn ${memory.previousObservation.turn}: ") +
                changes.ifEmpty { listOf("none") }.joinToString("; "))
        }

        appendLine()
        append(renderer.renderState(observation, includeResearchedTechs = memory == null))
        appendLine()
        appendLine("Legal actions:")
        append(renderer.renderActions(observation, LlmActionAliases.of(observation)))
    }

    companion object {
        const val NOTES_MAX_CHARS = 600

        val systemPrompt: String = """
            You play one civilization in Unciv as a strategic agent. Each step you get the state of your civilization
            as compact text and must answer with a single pick_actions tool call.

            Actions:
            - Pick zero or more legal actions by exact id; they are applied in the order given.
            - Ids follow the templates listed under "Legal actions", e.g. tech:Writing, build:Rome:Monument,
              found:12@3,-4, attack:Memphis, defend:Rome, war:Egypt, peace:Egypt, run_units, end_turn.
              Only combinations that are listed are legal; "all cities" items can be built in every listed city.
            - Units are commanded in groups, not one by one, and each unit follows only the first order that covers it
              this turn: found moves one settler, attack and defend move the whole remaining army, run_units hands every
              remaining unit to the built-in automation. Units that get no order stay idle, so include run_units every
              turn before end_turn.
            - Choose end_turn to advance the game; without it you are asked again on the same turn.

            Memory: you do not see earlier prompts. Put your plan in the notes field (at most $NOTES_MAX_CHARS characters);
            it is shown back to you next step together with your last decision and what changed since then.

            Notation: /t = per turn, @x,y = map position, xN = count, HP = hit points,
            yields F food, P production, G gold, S science, C culture, Fa faith, H happiness.
            Standing lists our value, our rank of the known major civs, and the leader's value when we are not first.
            Fields that are zero, false or empty are omitted.
        """.trimIndent()

        val toolSchema: String = """
            {"name":"pick_actions","description":"Apply zero or more legal actions to the current game state, in order.","input_schema":{"type":"object","properties":{"actions":{"type":"array","items":{"type":"object","properties":{"id":{"type":"string","description":"Exact id of a legal action, e.g. build:Rome:Monument"}},"required":["id"]}},"notes":{"type":"string","description":"Your plan and anything to remember next step, at most $NOTES_MAX_CHARS characters"},"rationale":{"type":"string"}},"required":["actions"]}}
        """.trimIndent()
    }
}
